package io.mosip.registration.liveness.audit;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Anonymous operational counters (no identities, no biometric data): processing time, challenge
 * completion time, retry and failure rates. Thread-safe.
 */
public final class LivenessMetrics {

    private final AtomicLong sessions = new AtomicLong();
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong passedPassiveOnly = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong padDetections = new AtomicLong();
    private final AtomicLong injectionDetections = new AtomicLong();
    private final AtomicLong activeVerifications = new AtomicLong();
    private final AtomicLong challengesCompleted = new AtomicLong();
    private final AtomicLong challengeMillis = new AtomicLong();
    private final AtomicLong framesProcessed = new AtomicLong();
    private final AtomicLong processingNanos = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();

    public void sessionStarted() { sessions.incrementAndGet(); }
    public void attemptStarted() { attempts.incrementAndGet(); }
    public void retried() { retries.incrementAndGet(); }
    public void cancelled() { cancelled.incrementAndGet(); }
    public void failed() { failed.incrementAndGet(); }
    public void padDetected() { padDetections.incrementAndGet(); }
    public void injectionDetected() { injectionDetections.incrementAndGet(); }
    public void activeStarted() { activeVerifications.incrementAndGet(); }
    public void frameDropped() { framesDropped.incrementAndGet(); }

    public void passed(boolean passiveOnly) {
        passed.incrementAndGet();
        if (passiveOnly) {
            passedPassiveOnly.incrementAndGet();
        }
    }

    public void challengeCompleted(long millis) {
        challengesCompleted.incrementAndGet();
        challengeMillis.addAndGet(millis);
    }

    public void frameProcessed(long nanos) {
        framesProcessed.incrementAndGet();
        processingNanos.addAndGet(nanos);
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public static final class Snapshot {
        public final long sessions;
        public final long attempts;
        public final long retries;
        public final long passed;
        public final long passedPassiveOnly;
        public final long failed;
        public final long cancelled;
        public final long padDetections;
        public final long injectionDetections;
        public final long activeVerifications;
        public final long challengesCompleted;
        public final long framesProcessed;
        public final long framesDropped;
        public final double avgFrameProcessingMs;
        public final double avgChallengeCompletionMs;

        private Snapshot(LivenessMetrics m) {
            sessions = m.sessions.get();
            attempts = m.attempts.get();
            retries = m.retries.get();
            passed = m.passed.get();
            passedPassiveOnly = m.passedPassiveOnly.get();
            failed = m.failed.get();
            cancelled = m.cancelled.get();
            padDetections = m.padDetections.get();
            injectionDetections = m.injectionDetections.get();
            activeVerifications = m.activeVerifications.get();
            challengesCompleted = m.challengesCompleted.get();
            framesProcessed = m.framesProcessed.get();
            framesDropped = m.framesDropped.get();
            avgFrameProcessingMs = framesProcessed == 0 ? 0 : m.processingNanos.get() / 1e6 / framesProcessed;
            avgChallengeCompletionMs = challengesCompleted == 0 ? 0 : (double) m.challengeMillis.get() / challengesCompleted;
        }

        /** Retries per session. */
        public double retryRate() {
            return sessions == 0 ? 0 : (double) retries / sessions;
        }

        /** Sessions that ended in final failure, out of those that finished passed or failed. */
        public double failureRate() {
            long finished = passed + failed;
            return finished == 0 ? 0 : (double) failed / finished;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "sessions=%d attempts=%d passed=%d (passive-only %d) failed=%d cancelled=%d "
                            + "pad=%d injection=%d active=%d retryRate=%.2f failureRate=%.2f avgFrame=%.2fms avgChallenge=%.0fms "
                            + "frames=%d dropped=%d",
                    sessions, attempts, passed, passedPassiveOnly, failed, cancelled, padDetections, injectionDetections,
                    activeVerifications, retryRate(), failureRate(), avgFrameProcessingMs, avgChallengeCompletionMs,
                    framesProcessed, framesDropped);
        }
    }
}
