package io.mosip.registration.liveness.session;

import io.mosip.registration.liveness.challenge.FaceBaseline;
import io.mosip.registration.liveness.engine.FaceObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Aggregates per-frame passive liveness scores over the passive window. */
final class PassiveEvaluator {

    private final List<FaceObservation> faces = new ArrayList<>();
    private final List<Float> scores = new ArrayList<>();

    void add(FaceObservation face, float livenessScore) {
        faces.add(face);
        scores.add(livenessScore);
    }

    int usableFrames() {
        return faces.size();
    }

    /** Trimmed mean (top and bottom 10% dropped once there are enough frames), so one outlier frame cannot decide. */
    float score() {
        if (scores.isEmpty()) {
            return Float.NaN;
        }
        List<Float> sorted = new ArrayList<>(scores);
        Collections.sort(sorted);
        int trim = sorted.size() >= 5 ? Math.max(1, sorted.size() / 10) : 0;
        double sum = 0;
        int n = 0;
        for (int i = trim; i < sorted.size() - trim; i++) {
            sum += sorted.get(i);
            n++;
        }
        return (float) (sum / n);
    }

    FaceBaseline baseline() {
        return FaceBaseline.of(faces);
    }

    void reset() {
        faces.clear();
        scores.clear();
    }
}
