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
import java.time.Duration;
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

    @Value("${relay.demo.public-mode:false}")
    private boolean publicDemo;

    @Autowired private ServerKeyHolder serverKey;
    @Autowired private DemoService demo;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private IdempotencyService idempotency;
    @Autowired private DemoOtpService demoOtp;
    @Autowired private DemoUserRepository demoUsers;
    @Autowired private PublicDemoRateLimiter rateLimiter;
    @Autowired private PublicDemoWorkspaceService workspaces;

    @GetMapping("/health")
    public Map<String, String> health() {
        String mode = readOnlyPreview ? "READ_ONLY_PREVIEW" : publicDemo ? "PUBLIC_PRIVATE_SANDBOX" : "SIMULATION";
        return Map.of("status", "UP", "mode", mode,
                "readOnly", Boolean.toString(readOnlyPreview),
                "publicDemo", Boolean.toString(publicDemo),
                "maxTransferAmount", "500.00");
    }

    @PostMapping("/auth/request-otp")
    public ResponseEntity<?> requestOtp(@RequestBody OtpRequest request, HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        if (publicDemo) return guestOnly();
        ResponseEntity<?> limited = rateLimit(session, "otp-request", 3, 100, Duration.ofMinutes(5));
        if (limited != null) return limited;
        try {
            return ResponseEntity.ok(demoOtp.request(request.contact));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/auth/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody OtpVerifyRequest request, HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        if (publicDemo) return guestOnly();
        ResponseEntity<?> limited = rateLimit(session, "otp-verify", 6, 200, Duration.ofMinutes(5));
        if (limited != null) return limited;
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
        if (publicDemo) {
            PublicDemoWorkspaceService.Workspace workspace = workspaces.getOrCreate(session, null);
            return Map.of("authenticated", true, "guestMode", true,
                    "displayName", workspace.getDisplayName(), "contact", "Private guest workspace",
                    "accountVpa", workspace.getAccountVpa(), "publicDemo", true);
        }
        Object id = session.getAttribute("demoUserId");
        if (!(id instanceof Long userId)) {
            return Map.of("authenticated", false);
        }
        return demoUsers.findById(userId)
                .<Map<String, Object>>map(user -> Map.of("authenticated", true,
                        "displayName", user.getDisplayName(), "contact", user.getContact()))
                .orElseGet(() -> Map.of("authenticated", false));
    }

    @PostMapping("/auth/guest")
    public ResponseEntity<?> startGuestWorkspace(@RequestBody GuestWorkspaceRequest request, HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        if (!publicDemo) return ResponseEntity.status(403).body(Map.of("error", "Guest workspaces are enabled on the hosted demo."));
        ResponseEntity<?> limited = rateLimit(session, "guest-workspace", 5, 500, Duration.ofMinutes(5));
        if (limited != null) return limited;
        try {
            PublicDemoWorkspaceService.Workspace workspace = workspaces.getOrCreate(
                    session, request == null ? null : request.displayName);
            return ResponseEntity.ok(Map.of("authenticated", true, "guestMode", true,
                    "displayName", workspace.getDisplayName(), "contact", "Private guest workspace",
                    "accountVpa", workspace.getAccountVpa(), "publicDemo", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicDemoWorkspaceService.WorkspaceCapacityException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    public static class GuestWorkspaceRequest { public String displayName; }

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
    public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req, HttpSession session) throws Exception {
        if (readOnlyPreview) return previewLocked();
        ResponseEntity<?> limited = rateLimit(session, "demo-send", 8, 30, Duration.ofMinutes(1));
        if (limited != null) return limited;
        limited = rateLimit(session, "demo-send-daily", 500, 500, Duration.ofHours(24));
        if (limited != null) return limited;
        if (req.senderVpa == null || req.receiverVpa == null || req.amount == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sender, recipient and amount are required"));
        }
        if (publicDemo) {
            PublicDemoWorkspaceService.Workspace workspace = workspaces.getOrCreate(session, null);
            if (!workspaces.owns(workspace, req.senderVpa) || !workspaces.owns(workspace, req.receiverVpa)) {
                return ResponseEntity.status(403).body(Map.of("error", "Choose accounts from your private demo workspace."));
            }
        }
        if (req.senderVpa.equals(req.receiverVpa) || req.amount.signum() <= 0
                || req.amount.scale() > 2 || req.amount.compareTo(new BigDecimal("500.00")) > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "payment details are invalid"));
        }
        if (req.ttl != null && (req.ttl < 1 || req.ttl > 5)) {
            return ResponseEntity.badRequest().body(Map.of("error", "packet TTL must be between 1 and 5"));
        }
        if (!accountRepo.existsById(req.senderVpa) || !accountRepo.existsById(req.receiverVpa)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown demo account"));
        }
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, "offline-demo",
                req.ttl == null ? 5 : req.ttl);

        String startDevice = "phone-alice";
        activeMesh(session).inject(startDevice, packet);

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
    public Map<String, Object> meshState(HttpSession session) {
        MeshSimulatorService activeMesh = activeMesh(session);
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : activeMesh.getDevices()) {
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
                "idempotencyCacheSize", publicDemo
                        ? deviceData.stream().mapToInt(data -> (Integer) data.get("packetCount")).sum()
                        : idempotency.size()
        );
    }

    @PostMapping("/mesh/gossip")
    public ResponseEntity<?> meshGossip(HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        if (publicDemo) return ResponseEntity.status(403).body(Map.of("error", "Use the packet-specific demo flow."));
        ResponseEntity<?> limited = rateLimit(session, "mesh-gossip", 12, 180, Duration.ofMinutes(1));
        if (limited != null) return limited;
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return ResponseEntity.ok(Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        ));
    }

    @PostMapping("/mesh/gossip/{packetId}")
    public ResponseEntity<?> meshGossipPacket(@PathVariable String packetId, HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        ResponseEntity<?> limited = rateLimit(session, "mesh-gossip", 12, 180, Duration.ofMinutes(1));
        if (limited != null) return limited;
        MeshSimulatorService activeMesh = activeMesh(session);
        if (!activeMesh.containsPacket(packetId)) return ResponseEntity.badRequest().body(Map.of("error", "packet not found"));
        MeshSimulatorService.GossipResult r = activeMesh.gossipPacket(packetId);
        return ResponseEntity.ok(Map.of("transfers", r.transfers(), "deviceCounts", r.deviceCounts()));
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
    public ResponseEntity<?> meshFlush(HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        if (publicDemo) return ResponseEntity.status(403).body(Map.of("error", "Use the packet-specific demo flow."));
        ResponseEntity<?> limited = rateLimit(session, "mesh-flush", 12, 180, Duration.ofMinutes(1));
        if (limited != null) return limited;
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

    @PostMapping("/mesh/flush/{packetId}")
    public ResponseEntity<?> meshFlushPacket(@PathVariable String packetId, HttpSession session) {
        if (readOnlyPreview) return previewLocked();
        ResponseEntity<?> limited = rateLimit(session, "mesh-flush", 12, 180, Duration.ofMinutes(1));
        if (limited != null) return limited;
        MeshSimulatorService activeMesh = activeMesh(session);
        List<MeshSimulatorService.BridgeUpload> uploads = activeMesh.collectBridgeUploads(packetId);
        List<Map<String, Object>> results = new ArrayList<>();
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult result =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", packetId.substring(0, Math.min(8, packetId.length())),
                        "outcome", result.outcome(),
                        "reason", result.reason() == null ? "" : result.reason(),
                        "transactionId", result.transactionId() == null ? -1 : result.transactionId()
                ));
            }
        });
        if (!uploads.isEmpty()) activeMesh.removePacket(packetId);
        return ResponseEntity.ok(Map.of("uploadsAttempted", uploads.size(), "results", results));
    }

    @PostMapping("/mesh/reset")
    public ResponseEntity<?> meshReset() {
        if (readOnlyPreview) return previewLocked();
        if (publicDemo) return ResponseEntity.status(403).body(Map.of("error", "Mesh reset is disabled in the public guest workspaces."));
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
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount,
            HttpSession session) {

        if (readOnlyPreview) return previewLocked();
        if (publicDemo) return ResponseEntity.status(403).body(Map.of("error", "Direct bridge ingestion is disabled in public guest workspaces."));
        ResponseEntity<?> limited = rateLimit(session, "bridge-ingest", 10, 120, Duration.ofMinutes(1));
        if (limited != null) return limited;
        if (packet.getCiphertext() == null || packet.getCiphertext().length() > 16_384) {
            return ResponseEntity.badRequest().body(Map.of("error", "packet is invalid or too large"));
        }

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    public List<Account> listAccounts(HttpSession session) {
        if (publicDemo) return workspaces.getAccounts(workspaces.getOrCreate(session, null));
        return accountRepo.findAll();
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions(HttpSession session) {
        if (publicDemo) {
            PublicDemoWorkspaceService.Workspace workspace = workspaces.getOrCreate(session, null);
            return txRepo.findTop20BySenderVpaInOrReceiverVpaInOrderByIdDesc(
                    workspace.getVpas(), workspace.getVpas());
        }
        return txRepo.findTop20ByOrderByIdDesc();
    }

    private MeshSimulatorService activeMesh(HttpSession session) {
        return publicDemo ? workspaces.getMesh(session) : mesh;
    }

    private ResponseEntity<Map<String, String>> previewLocked() {
        return ResponseEntity.status(403).body(Map.of("error",
                "This public preview is guest-only and read-only. Run the app locally for the writable simulator."));
    }

    private ResponseEntity<Map<String, String>> guestOnly() {
        return ResponseEntity.status(403).body(Map.of("error",
                "No sign-in is needed for the public sandbox. Continue as a guest; no contact details are collected."));
    }

    private ResponseEntity<?> rateLimit(HttpSession session, String action,
                                        int perSession, int globalLimit, Duration window) {
        if (!publicDemo) return null;
        long retry = rateLimiter.retryAfterSeconds(session.getId(), action, perSession, globalLimit, window);
        if (retry == 0) return null;
        return ResponseEntity.status(429).header("Retry-After", Long.toString(retry))
                .body(Map.of("error", "Public demo rate limit reached. Please try again in " + retry + " seconds."));
    }
}
