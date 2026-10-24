package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitTest {

    private static final long MINUTE = 60_000;
    private static final LivenessPolicy POLICY = LivenessPolicy.builder()
            .rateLimitMaxFailures(4).rateLimitWindowMs(10 * MINUTE).rateLimitLockoutMs(15 * MINUTE).build();

    @Test
    void locksOutAfterTooManyFailuresInTheWindow() {
        AttemptRateLimiter limiter = new AttemptRateLimiter();
        long t = 1_000_000;
        for (int i = 0; i < 3; i++) {
            assertEquals(0, limiter.recordFailure("op:alice", 4, 10 * MINUTE, 15 * MINUTE, t + i * MINUTE));
        }
        long until = limiter.recordFailure("op:alice", 4, 10 * MINUTE, 15 * MINUTE, t + 3 * MINUTE);

        assertEquals(t + 3 * MINUTE + 15 * MINUTE, until);
        assertEquals(until, limiter.lockedUntil("op:alice", t + 4 * MINUTE));
        assertEquals(0, limiter.lockedUntil("op:bob", t + 4 * MINUTE), "keys are independent");
        assertEquals(0, limiter.lockedUntil("op:alice", until), "lockout expires");
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() {
        AttemptRateLimiter limiter = new AttemptRateLimiter();
        long t = 1_000_000;
        for (int i = 0; i < 6; i++) {
            assertEquals(0, limiter.recordFailure("k", 4, 10 * MINUTE, 15 * MINUTE, t + i * 4 * MINUTE),
                    "at most 3 failures fall in any 10-minute window");
        }
    }

    @Test
    void lockoutSurvivesARestart(@TempDir Path dir) {
        Path store = dir.resolve("rate-limit.properties");
        AttemptRateLimiter first = new AttemptRateLimiter(store);
        long until = 0;
        for (int i = 0; i < 4; i++) {
            until = first.recordFailure("op:alice", 4, 10 * MINUTE, 15 * MINUTE, 1_000 + i);
        }

        AttemptRateLimiter restarted = new AttemptRateLimiter(store);

        assertEquals(until, restarted.lockedUntil("op:alice", 2_000));
        restarted.reset("op:alice");
        assertEquals(0, new AttemptRateLimiter(store).lockedUntil("op:alice", 2_000));
    }

    @Test
    void storeDoesNotContainTheSubjectKey(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("rate-limit.properties");
        new AttemptRateLimiter(store).recordFailure("operator:alice.smith", 4, MINUTE, MINUTE, 1_000);

        String content = new String(java.nio.file.Files.readAllBytes(store), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(!content.contains("alice"), content);
    }

    @Test
    void repeatedSpoofingAcrossSessionsLocksOutAndANewSessionFailsImmediately() {
        SimulationHarness h = new SimulationHarness(51);
        h.subject.setPresentation(Presentation.PRINTED_PHOTO);
        AttemptRateLimiter limiter = new AttemptRateLimiter();

        // Session 1: 3 attempts (max retries 2), all PAD failures.
        h.newSession(POLICY, 51, h.engine.info(), limiter, "operator:alice");
        LivenessResult first = h.startAndRun();
        assertEquals(FailureReason.MAX_RETRIES_EXCEEDED, first.getFailureReason());

        // Session 2: the 4th failure in the window locks the operator out.
        h.newSession(POLICY, 52, h.engine.info(), limiter, "operator:alice");
        LivenessResult second = h.startAndRun();
        assertEquals(FailureReason.LOCKED_OUT, second.getFailureReason());
        assertTrue(second.getLockedUntil() > h.clock.get());
        assertEquals(UserMessage.FINAL_LOCKED_OUT, h.updates.get(h.updates.size() - 1).getMessage());
        assertEquals(1, h.audit.ofType(AuditEventType.RATE_LIMIT_LOCKOUT).size());

        // Session 3, even with a live face: refused before any frame is analysed.
        h.subject.setPresentation(Presentation.LIVE);
        h.newSession(POLICY, 53, h.engine.info(), limiter, "operator:alice");
        h.start();
        assertEquals(LivenessState.FAILED, h.session.state());
        assertEquals(FailureReason.LOCKED_OUT, h.result.getFailureReason());
        assertEquals(0, h.result.getAttempts());

        // Another operator is not affected, and alice can try again once the lockout ends.
        h.newSession(POLICY, 54, h.engine.info(), limiter, "operator:bob");
        assertEquals(LivenessOutcome.PASSED, h.startAndRun().getOutcome());
        h.clock.addAndGet(16 * MINUTE);
        h.newSession(POLICY, 55, h.engine.info(), limiter, "operator:alice");
        assertEquals(LivenessOutcome.PASSED, h.startAndRun().getOutcome());
    }

    @Test
    void deviceFaultsDoNotCountTowardsTheLockout() {
        AttemptRateLimiter limiter = new AttemptRateLimiter();
        SimulationHarness h = new SimulationHarness(56);
        h.autoRetry = false;
        for (int i = 0; i < 6; i++) {
            h.newSession(POLICY, 56 + i, h.engine.info(), limiter, "station");
            h.start();
            h.session.onSystemError(FailureReason.DEVICE_DISCONNECTED, "unplugged", h.clock.get());
            h.session.cancel(h.clock.get());
        }
        assertEquals(0, limiter.lockedUntil("station", h.clock.get()));
    }
}
