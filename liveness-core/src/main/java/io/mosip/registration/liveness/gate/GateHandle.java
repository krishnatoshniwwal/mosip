package io.mosip.registration.liveness.gate;

import io.mosip.registration.liveness.service.LivenessHandle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** A running {@link LivenessGate}: liveness first, then the guarded capture. */
public final class GateHandle<T> {

    private final CompletableFuture<GateResult<T>> result = new CompletableFuture<>();
    private volatile LivenessHandle liveness;
    private volatile Future<T> capture;
    private volatile boolean cancelled;

    GateHandle() {
    }

    void attach(LivenessHandle handle) {
        liveness = handle;
        if (cancelled) {
            handle.cancel();
        }
    }

    void captureStarted(Future<T> future) {
        capture = future;
        if (cancelled) {
            future.cancel(true);
        }
    }

    boolean isCancelled() {
        return cancelled;
    }

    public String getSessionId() {
        LivenessHandle h = liveness;
        return h == null ? null : h.getSessionId();
    }

    /** Starts the next liveness attempt after a failed one (when the update says a retry is available). */
    public void retry() {
        LivenessHandle h = liveness;
        if (h != null) {
            h.retry();
        }
    }

    /** Cancels liveness, or interrupts the capture step if it is already running. */
    public void cancel() {
        cancelled = true;
        LivenessHandle h = liveness;
        if (h != null) {
            h.cancel();
        }
        Future<T> f = capture;
        if (f != null) {
            f.cancel(true);
        }
    }

    /** Completes once, after the device is released. */
    public CompletableFuture<GateResult<T>> result() {
        return result;
    }
}
