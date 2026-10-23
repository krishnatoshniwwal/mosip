package io.mosip.registration.liveness.challenge;

import io.mosip.registration.liveness.engine.FaceObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * The subject's resting expression and pose, taken as the median over the passive frames. Facial
 * actions are measured relative to it, so a person who naturally squints or smiles is still judged
 * by the change they make.
 */
public final class FaceBaseline {
    private final float eyeOpenness;
    private final float smile;
    private final float yaw;
    private final float pitch;

    public FaceBaseline(float eyeOpenness, float smile, float yaw, float pitch) {
        this.eyeOpenness = eyeOpenness;
        this.smile = smile;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static FaceBaseline neutral() {
        return new FaceBaseline(0.8f, 0.1f, 0f, 0f);
    }

    public static FaceBaseline of(List<FaceObservation> faces) {
        FaceBaseline neutral = neutral();
        return new FaceBaseline(
                median(faces, FaceObservation::eyeOpenness, neutral.eyeOpenness),
                median(faces, FaceObservation::getSmile, neutral.smile),
                median(faces, FaceObservation::getYaw, neutral.yaw),
                median(faces, FaceObservation::getPitch, neutral.pitch));
    }

    private static float median(List<FaceObservation> faces, Function<FaceObservation, Float> value, float fallback) {
        List<Float> values = new ArrayList<>();
        for (FaceObservation face : faces) {
            float v = value.apply(face);
            if (!Float.isNaN(v)) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            return fallback;
        }
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    public float getEyeOpenness() { return eyeOpenness; }
    public float getSmile() { return smile; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
}
