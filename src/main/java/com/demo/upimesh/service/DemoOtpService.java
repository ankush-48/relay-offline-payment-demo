package com.demo.upimesh.service;

import com.demo.upimesh.model.DemoUser;
import com.demo.upimesh.model.DemoUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Local demonstration OTP. It intentionally does not send email or SMS. */
@Service
public class DemoOtpService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[1-9]\\d{7,14}$");
    private static final long OTP_TTL_SECONDS = 300;
    private static final long RESEND_WAIT_SECONDS = 30;
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastRequested = new ConcurrentHashMap<>();
    private final DemoUserRepository users;

    public DemoOtpService(DemoUserRepository users) { this.users = users; }

    public synchronized IssuedOtp request(String rawContact) {
        String contact = normalize(rawContact);
        Instant now = Instant.now();
        Instant last = lastRequested.get(contact);
        if (last != null && now.isBefore(last.plusSeconds(RESEND_WAIT_SECONDS))) {
            throw new IllegalArgumentException("Please wait 30 seconds before requesting another code.");
        }
        int number = 100000 + random.nextInt(900000);
        String code = Integer.toString(number);
        String id = UUID.randomUUID().toString();
        challenges.put(id, new Challenge(contact, digest(code), now.plusSeconds(OTP_TTL_SECONDS)));
        lastRequested.put(contact, now);
        return new IssuedOtp(id, code, OTP_TTL_SECONDS, "DEMO_ONLY_NOT_SENT");
    }

    @Transactional
    public synchronized DemoUser verify(String challengeId, String rawCode, String rawName) {
        Challenge challenge = challenges.get(challengeId);
        if (challenge == null) throw new IllegalArgumentException("Code expired or request not found. Request a new code.");
        if (Instant.now().isAfter(challenge.expiresAt)) {
            challenges.remove(challengeId);
            throw new IllegalArgumentException("Code expired. Request a new one.");
        }
        if (++challenge.attempts > MAX_ATTEMPTS) {
            challenges.remove(challengeId);
            throw new IllegalArgumentException("Too many attempts. Request a new code.");
        }
        if (rawCode == null || !MessageDigest.isEqual(challenge.codeHash, digest(rawCode.trim()))) {
            throw new IllegalArgumentException("That code does not match.");
        }
        if (rawName == null || rawName.trim().length() < 2 || rawName.trim().length() > 80) {
            throw new IllegalArgumentException("Enter a name between 2 and 80 characters.");
        }
        challenges.remove(challengeId);
        DemoUser user = users.findByContact(challenge.contact)
                .orElseGet(() -> users.save(new DemoUser(challenge.contact, rawName.trim())));
        user.setDisplayName(rawName.trim());
        return users.save(user);
    }

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("Enter an email address or phone number.");
        String contact = raw.trim().replaceAll("[\\s()-]", "");
        if (EMAIL.matcher(contact).matches()) return contact.toLowerCase();
        if (PHONE.matcher(contact).matches()) return contact;
        throw new IllegalArgumentException("Enter a valid email address or international phone number.");
    }

    private static byte[] digest(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final class Challenge {
        final String contact;
        final byte[] codeHash;
        final Instant expiresAt;
        int attempts;
        Challenge(String contact, byte[] codeHash, Instant expiresAt) {
            this.contact = contact; this.codeHash = codeHash; this.expiresAt = expiresAt;
        }
    }
    public record IssuedOtp(String challengeId, String demoOtp, long expiresInSeconds, String deliveryStatus) {}
}
