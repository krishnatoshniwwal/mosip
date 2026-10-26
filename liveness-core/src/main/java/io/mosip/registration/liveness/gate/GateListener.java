package io.mosip.registration.liveness.gate;

import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;

/**
 * UI callback for a {@link LivenessGate} run. Liveness updates and preview frames arrive as for a plain session;
 * preview frames keep coming while the capture runs. Calls arrive on worker threads: marshal to the UI thread.
 */
public interface GateListener<T> extends LivenessListener {

    /** Liveness passed and the capture step has started; ask the person to hold still. */
    default void onCaptureStarted() {
    }

    /** Called exactly once, after the device has been released. */
    void onGateComplete(GateResult<T> result);

    /** The liveness session ended. The gate result follows; most screens only need {@link #onGateComplete}. */
    @Override
    default void onComplete(LivenessResult result) {
    }
}
