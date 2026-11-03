package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.TransactionRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Creates temporary, isolated demo wallets and meshes for public browser sessions. */
@Service
public class PublicDemoWorkspaceService {
    private static final int MAX_WORKSPACES = 2_000;
    private static final long WORKSPACE_IDLE_MILLIS = 60 * 60 * 1_000L;

    private final ConcurrentHashMap<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final PublicDemoRateLimiter rateLimiter;

    public PublicDemoWorkspaceService(AccountRepository accounts, TransactionRepository transactions,
                                      PublicDemoRateLimiter rateLimiter) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public synchronized Workspace getOrCreate(HttpSession session, String requestedName) {
        String sessionId = session.getId();
        Workspace workspace = workspaces.get(sessionId);
        if (workspace == null) {
            long retry = rateLimiter.retryAfterSeconds(sessionId, "workspace-create", 1, 100,
                    java.time.Duration.ofMinutes(5));
            if (retry > 0) {
                throw new WorkspaceCapacityException("Too many new guest workspaces were started. Please try again in " + retry + " seconds.");
            }
            if (workspaces.size() >= MAX_WORKSPACES) {
                evictIdle(Instant.now().toEpochMilli());
                if (workspaces.size() >= MAX_WORKSPACES) {
                    throw new WorkspaceCapacityException("The guest sandbox is busy. Please try again shortly.");
                }
            }
            workspace = createWorkspace(sessionId, safeName(requestedName, "Guest Explorer"));
            workspaces.put(sessionId, workspace);
        } else if (requestedName != null && !requestedName.isBlank()) {
            String name = safeName(requestedName, workspace.getDisplayName());
            workspace.setDisplayName(name);
            accounts.findById(workspace.getAccountVpa()).ifPresent(account -> {
                account.setHolderName(name);
                accounts.save(account);
            });
        }
        workspace.touch();
        return workspace;
    }

    public List<Account> getAccounts(Workspace workspace) {
        List<Account> result = new ArrayList<>();
        accounts.findAllById(workspace.getVpas()).forEach(result::add);
        result.sort(Comparator.comparing(a -> !a.getVpa().equals(workspace.getAccountVpa())));
        return result;
    }

    public boolean owns(Workspace workspace, String vpa) {
        return workspace.getVpas().contains(vpa);
    }

    public MeshSimulatorService getMesh(HttpSession session) {
        return getOrCreate(session, null).getMesh();
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public synchronized void evictIdleWorkspaces() {
        evictIdle(Instant.now().toEpochMilli());
    }

    private void evictIdle(long now) {
        List<String> expiredSessions = workspaces.entrySet().stream()
                .filter(entry -> now - entry.getValue().getLastTouched() > WORKSPACE_IDLE_MILLIS)
                .map(java.util.Map.Entry::getKey)
                .toList();
        for (String sessionId : expiredSessions) {
            Workspace expired = workspaces.remove(sessionId);
            if (expired != null) {
                transactions.deleteBySenderVpaInOrReceiverVpaIn(expired.getVpas(), expired.getVpas());
                accounts.deleteAllById(expired.getVpas());
            }
        }
    }

    private Workspace createWorkspace(String sessionId, String displayName) {
        String prefix = "g" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String ownVpa = prefix + "-you@relay";
        List<String> vpas = List.of(ownVpa, prefix + "-mira@relay", prefix + "-noah@relay", prefix + "-lee@relay");
        String ownerName = displayName.length() > 40 ? displayName.substring(0, 40) : displayName;
        accounts.saveAll(List.of(
                new Account(ownVpa, ownerName, new BigDecimal("10000.00")),
                new Account(vpas.get(1), "Mira Chen", new BigDecimal("5000.00")),
                new Account(vpas.get(2), "Noah Patel", new BigDecimal("5000.00")),
                new Account(vpas.get(3), "Lee Rivera", new BigDecimal("5000.00"))
        ));
        return new Workspace(sessionId, displayName, ownVpa, vpas, new MeshSimulatorService());
    }

    private String safeName(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String name = raw.trim().replaceAll("[\\p{Cntrl}]", "");
        if (name.length() < 2 || name.length() > 40) {
            throw new IllegalArgumentException("Display name must be between 2 and 40 characters.");
        }
        return name;
    }

    public static final class Workspace {
        private final String sessionId;
        private final String accountVpa;
        private final List<String> vpas;
        private final MeshSimulatorService mesh;
        private volatile String displayName;
        private volatile long lastTouched = System.currentTimeMillis();

        private Workspace(String sessionId, String displayName, String accountVpa,
                          List<String> vpas, MeshSimulatorService mesh) {
            this.sessionId = sessionId;
            this.displayName = displayName;
            this.accountVpa = accountVpa;
            this.vpas = vpas;
            this.mesh = mesh;
        }

        public String getSessionId() { return sessionId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAccountVpa() { return accountVpa; }
        public List<String> getVpas() { return vpas; }
        public MeshSimulatorService getMesh() { return mesh; }
        public long getLastTouched() { return lastTouched; }
        public void touch() { lastTouched = System.currentTimeMillis(); }
    }

    public static final class WorkspaceCapacityException extends RuntimeException {
        public WorkspaceCapacityException(String message) { super(message); }
    }
}
