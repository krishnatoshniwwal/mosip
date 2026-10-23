package io.mosip.registration.liveness.mock;

import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.PadResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the simulated subject is doing in a frame. Attached to mock frames as metadata so that the
 * simulated engine can "analyse" them and the demo can draw the preview.
 */
public final class SimulatedFrameTruth {

    public static final String METADATA_KEY = "mosip.liveness.simulation";

    private final SimulatedSubject.Presentation presentation;
    private final List<FaceObservation> faces;
    private final float livenessScore;
    private final PadResult pad;

    SimulatedFrameTruth(SimulatedSubject.Presentation presentation, List<FaceObservation> faces,
                        float livenessScore, PadResult pad) {
        this.presentation = presentation;
        this.faces = Collections.unmodifiableList(new ArrayList<>(faces));
        this.livenessScore = livenessScore;
        this.pad = pad;
    }

    public SimulatedSubject.Presentation getPresentation() { return presentation; }
    public List<FaceObservation> getFaces() { return faces; }
    public float getLivenessScore() { return livenessScore; }
    public PadResult getPad() { return pad; }
}
