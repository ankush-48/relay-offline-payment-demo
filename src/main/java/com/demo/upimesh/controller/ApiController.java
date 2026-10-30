package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Public REST surface.
 *
 * The endpoints split into three groups:
 *   /api/server-key      → so simulated senders can fetch the server's public key
 *   /api/mesh/*          → simulator endpoints (inject, gossip, flush)
 *   /api/bridge/ingest   → THE real production endpoint a real bridge node would hit
 *   /api/accounts, /api/transactions → for the dashboard
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @Value("${relay.demo.read-only:false}")
    private boolean readOnlyPreview;

    @Autowired private ServerKeyHolder serverKey;
    @Autowired private DemoService demo;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private IdempotencyService idempotency;
    @Autowired private DemoOtpService demoOtp;
    @Autowired private DemoUserRepository demoUsers;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "mode", readOnlyPreview ? "READ_ONLY_PREVIEW" : "SIMULATION",
                "readOnly", Boolean.toString(readOnlyPreview));
    }

    @PostMapping("/auth/request-otp")
    public ResponseEntity<?> requestOtp(@RequestBody OtpRequest request) {
        if (readOnlyPreview) return previewLocked();
        try {
            return ResponseEntity.ok(demoOtp.request(request.contact));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/auth/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody OtpVerifyRequest request, HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        try {
            DemoUser user = demoOtp.verify(request.challengeId, request.code, request.displayName);
            session.setAttribute("demoUserId", user.getId());
            return ResponseEntity.ok(Map.of("authenticated", true, "displayName", user.getDisplayName(),
                    "contact", user.getContact()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/auth/me")
    public Map<String, Object> currentUser(HttpSession session) {
        if (readOnlyPreview) return Map.of("authenticated", true, "guestMode", true,
                "displayName", "Guest Explorer", "contact", "Guest preview");
        Object id = session.getAttribute("demoUserId");
        if (!(id instanceof Long userId)) return Map.of("authenticated", false);
        return demoUsers.findById(userId)
                .<Map<String, Object>>map(user -> Map.of("authenticated", true,
                        "displayName", user.getDisplayName(), "contact", user.getContact()))
                .orElseGet(() -> Map.of("authenticated", false));
    }

    @PostMapping("/auth/logout")
    public Map<String, String> logout(HttpSession session) {
        session.invalidate();
        return Map.of("status", "signed_out");
    }

    public static class OtpRequest { public String contact; }
    public static class OtpVerifyRequest { public String challengeId; public String code; public String displayName; }

    // ------------------------------------------------------------------ key

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    }

    // ---------------------------------------------------------------- demo

    /**
     * Demo helper: build a packet on the server (simulating a sender phone)
     * and inject it into the mesh at the given device.
     */
    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req) throws Exception {
        if (readOnlyPreview) return previewLocked();
        if (req.senderVpa == null || req.receiverVpa == null || req.amount == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sender, recipient and amount are required"));
        }
        if (req.senderVpa.equals(req.receiverVpa) || req.amount.signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "payment details are invalid"));
        }
        if (!accountRepo.existsById(req.senderVpa) || !accountRepo.existsById(req.receiverVpa)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown demo account"));
        }
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, "offline-demo",
                req.ttl == null ? 5 : req.ttl);

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        return ResponseEntity.ok(Map.of(
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    public static class DemoSendRequest {
        public String senderVpa;
        public String receiverVpa;
        public BigDecimal amount;
        public String pin;
        public Integer ttl;
        public String startDevice;
    }

    // -------------------------------------------------------------- mesh sim

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of(
                "devices", deviceData,
                "idempotencyCacheSize", idempotency.size()
        );
    }

    @PostMapping("/mesh/gossip")
    public ResponseEntity<?> meshGossip() {
        if (readOnlyPreview) return previewLocked();
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return ResponseEntity.ok(Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        ));
    }

    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * They all upload everything they hold to /api/bridge/ingest.
     *
     * THIS is the moment the duplicate-storm idempotency case is tested:
     * if multiple bridge nodes hold the same packet, the server gets multiple
     * concurrent POSTs of the same ciphertext, and only one should settle.
     */
    @PostMapping("/mesh/flush")
    public ResponseEntity<?> meshFlush() {
        if (readOnlyPreview) return previewLocked();
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();

        List<Map<String, Object>> results = new ArrayList<>();
        // Upload them in parallel to actually exercise concurrent idempotency.
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.outcome(),
                        "reason", r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });

        return ResponseEntity.ok(Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        ));
    }

    @PostMapping("/mesh/reset")
    public ResponseEntity<?> meshReset() {
        if (readOnlyPreview) return previewLocked();
        mesh.resetMesh();
        idempotency.clear();
        return ResponseEntity.ok(Map.of("status", "mesh and idempotency cache cleared"));
    }

    // -------------------------------------------------------------- bridge

    /**
     * THE PRODUCTION ENDPOINT.
     * In a real deployment, the Android app's bridge logic POSTs here whenever
     * the device has internet and is holding mesh packets.
     */
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        if (readOnlyPreview) return previewLocked();

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }

    private ResponseEntity<Map<String, String>> previewLocked() {
        return ResponseEntity.status(403).body(Map.of("error",
                "This public preview is guest-only and read-only. Run the app locally for the writable simulator."));
    }
}
