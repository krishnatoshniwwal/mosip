package io.mosip.registration.liveness.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Locks face verification after too many failed attempts in a sliding time window, so that an
 * attacker cannot try spoof after spoof. Failures are counted per key across sessions: the
 * Registration Client passes the operator or supervisor user ID for authentication, and the
 * registration ID (or nothing, meaning the whole station) for resident capture.
 *
 * <p>With a store file the state survives a restart of the client, so closing and reopening the
 * application does not reset a lockout. Keys are stored only as SHA-256 hashes. If the file cannot
 * be written the limiter keeps enforcing in memory and logs a warning.
 *
 * <p>Thread-safe. Times are epoch milliseconds from the caller's clock.
 */
public final class AttemptRateLimiter {

    private static final Logger LOG = Logger.getLogger(AttemptRateLimiter.class.getName());

    private final Path store;
    private final Map<String, Entry> entries = new HashMap<>();

    private static final class Entry {
        final Deque<Long> failures = new ArrayDeque<>();
        long lockedUntil;
    }

    /** In-memory only; state is lost when the process exits. */
    public AttemptRateLimiter() {
        this.store = null;
    }

    /** Persists state to {@code store}, loading any existing state from it. */
    public AttemptRateLimiter(Path store) {
        this.store = store;
        load();
    }

    /** @return the time the lockout for {@code key} ends, or 0 if it is not locked */
    public synchronized long lockedUntil(String key, long now) {
        Entry entry = entries.get(hash(key));
        return entry != null && entry.lockedUntil > now ? entry.lockedUntil : 0;
    }

    /**
     * Records one failed attempt.
     *
     * @return the time the resulting lockout ends, or 0 if this failure did not cause one
     */
    public synchronized long recordFailure(String key, int maxFailures, long windowMs, long lockoutMs, long now) {
        Entry entry = entries.computeIfAbsent(hash(key), k -> new Entry());
        if (entry.lockedUntil > now) {
            return entry.lockedUntil;
        }
        entry.lockedUntil = 0;
        prune(entry, windowMs, now);
        entry.failures.addLast(now);
        long until = 0;
        if (entry.failures.size() >= maxFailures) {
            until = now + lockoutMs;
            entry.lockedUntil = until;
            entry.failures.clear();
        }
        save();
        return until;
    }

    /** Clears the failures and any lockout for {@code key}, e.g. after a supervisor unlocks it. */
    public synchronized void reset(String key) {
        if (entries.remove(hash(key)) != null) {
            save();
        }
    }

    private static void prune(Entry entry, long windowMs, long now) {
        while (!entry.failures.isEmpty() && entry.failures.peekFirst() <= now - windowMs) {
            entry.failures.removeFirst();
        }
    }

    private void load() {
        if (store == null || !Files.isRegularFile(store)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(store)) {
            props.load(in);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read liveness rate-limit state " + store + "; starting empty", e);
            return;
        }
        for (String key : props.stringPropertyNames()) {
            try {
                String[] parts = props.getProperty(key).split(";", -1);
                Entry entry = new Entry();
                entry.lockedUntil = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    for (String t : parts[1].split(",")) {
                        entry.failures.addLast(Long.parseLong(t));
                    }
                }
                entries.put(key, entry);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                LOG.warning("Ignoring malformed rate-limit entry in " + store);
            }
        }
    }

    private void save() {
        if (store == null) {
            return;
        }
        Properties props = new Properties();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            StringBuilder failures = new StringBuilder();
            for (Long t : e.getValue().failures) {
                failures.append(failures.length() == 0 ? "" : ",").append(t);
            }
            props.setProperty(e.getKey(), e.getValue().lockedUntil + ";" + failures);
        }
        try {
            Path parent = store.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "Face liveness rate-limit state (hashed keys)");
            }
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot persist liveness rate-limit state to " + store
                    + "; lockouts are enforced in memory only", e);
        }
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
