package com.demo.upimesh.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lightweight per-session and global limits for the single-instance public demo. */
@Service
public class PublicDemoRateLimiter {
    private static final int MAX_TRACKED_SESSIONS = 10_000;
    private final Map<String, Window> sessions = new ConcurrentHashMap<>();
    private final Map<String, Window> global = new ConcurrentHashMap<>();

    /** Returns zero when allowed, otherwise the number of seconds to wait. */
    public synchronized long retryAfterSeconds(String sessionId, String action,
                                                int sessionLimit, int globalLimit,
                                                Duration duration) {
        long now = System.currentTimeMillis();
        long windowMs = duration.toMillis();
        String sessionKey = action + ":" + sessionId;
        if (!sessions.containsKey(sessionKey) && sessions.size() >= MAX_TRACKED_SESSIONS) {
            evictExpired(sessions, now);
            if (sessions.size() >= MAX_TRACKED_SESSIONS) return Math.max(1, duration.toSeconds());
        }
        Window user = current(sessions, sessionKey, now, windowMs);
        Window all = current(global, action, now, windowMs);

        if (user.count >= sessionLimit || all.count >= globalLimit) {
            long remaining = Math.max(user.start + windowMs - now, all.start + windowMs - now);
            return Math.max(1, (remaining + 999) / 1000);
        }
        user.count++;
        all.count++;
        if (sessions.size() > MAX_TRACKED_SESSIONS) evictExpired(sessions, now);
        if (sessions.size() > MAX_TRACKED_SESSIONS) return Math.max(1, duration.toSeconds());
        return 0;
    }

    private Window current(Map<String, Window> map, String key, long now, long windowMs) {
        return map.compute(key, (ignored, value) -> value == null || now - value.start >= value.windowMs
                ? new Window(now, windowMs) : value);
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        evictExpired(sessions, now);
        evictExpired(global, now);
    }

    private void evictExpired(Map<String, Window> map, long now) {
        map.entrySet().removeIf(entry -> now - entry.getValue().start > entry.getValue().windowMs);
    }

    private static final class Window {
        final long start;
        final long windowMs;
        int count;
        Window(long start, long windowMs) { this.start = start; this.windowMs = windowMs; }
    }
}
