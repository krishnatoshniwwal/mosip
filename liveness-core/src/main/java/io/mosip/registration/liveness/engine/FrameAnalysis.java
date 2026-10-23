package io.mosip.registration.liveness.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything the liveness engine reports about one frame. */
public final class FrameAnalysis {
    private final List<FaceObservation> faces;
    private final float livenessScore;
    private final PadResult pad;

    /**
     * @param faces         every face found in the frame
     * @param livenessScore passive liveness score (0..100) for the face when exactly one is present
     * @param pad           presentation attack assessment for that face
     */
    public FrameAnalysis(List<FaceObservation> faces, float livenessScore, PadResult pad) {
        this.faces = faces.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(faces));
        this.livenessScore = livenessScore;
        this.pad = pad == null ? PadResult.notEvaluated() : pad;
    }

    public List<FaceObservation> getFaces() { return faces; }
    public float getLivenessScore() { return livenessScore; }
    public PadResult getPad() { return pad; }
}
