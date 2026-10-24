package io.mosip.registration.liveness.config;

import io.mosip.registration.liveness.config.CaptureArea.Position;
import io.mosip.registration.liveness.engine.FaceObservation;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureAreaTest {

    private static final CaptureArea AREA = LivenessPolicy.builder().build().getCaptureArea();

    @Test
    void faceCentredInTheOvalAtANormalDistanceIsInside() {
        assertEquals(Position.INSIDE, AREA.locate(face(0.5f, 0.5f, 0.3f)));
        assertEquals(Position.INSIDE, AREA.locate(face(0.55f, 0.45f, 0.3f)), "small offsets and head turns are tolerated");
    }

    @Test
    void faceElsewhereInTheFrameIsOffCentre() {
        assertEquals(Position.OFF_CENTER, AREA.locate(face(0.85f, 0.5f, 0.3f)));
        assertEquals(Position.OFF_CENTER, AREA.locate(face(0.15f, 0.2f, 0.3f)));
        assertEquals(Position.OFF_CENTER, AREA.locate(face(0.5f, 0.9f, 0.3f)));
    }

    @Test
    void faceSizeMustFillTheOval() {
        assertEquals(Position.TOO_FAR, AREA.locate(face(0.5f, 0.48f, 0.12f)));
        assertEquals(Position.TOO_CLOSE, AREA.locate(face(0.5f, 0.48f, 0.6f)));
    }

    @Test
    void disabledAreaAcceptsAnyPosition() {
        CaptureArea off = LivenessPolicy.builder().captureAreaEnabled(false).build().getCaptureArea();
        assertEquals(Position.INSIDE, off.locate(face(0.9f, 0.1f, 0.05f)));
    }

    @Test
    void areaIsConfigurablePerWorkflowAndValidated() {
        Properties props = new Properties();
        props.setProperty("mosip.registration.liveness.operator.capture.area.radiusX", "0.3");
        LivenessConfig config = LivenessConfig.fromProperties(props);
        assertEquals(0.3f, config.policyFor(io.mosip.registration.liveness.LivenessWorkflow.OPERATOR_AUTHENTICATION)
                .getCaptureArea().getRadiusX());

        props.setProperty("mosip.registration.liveness.capture.area.centerX", "0.9");
        ConfigException e = assertThrows(ConfigException.class, () -> LivenessConfig.fromProperties(props));
        assertTrue(e.getMessage().contains("inside the frame"), e.getMessage());
    }

    @Test
    void fillRangeMustBeOrdered() {
        assertThrows(IllegalArgumentException.class,
                () -> LivenessPolicy.builder().captureAreaMinFill(0.8f).captureAreaMaxFill(0.5f).build());
    }

    private static FaceObservation face(float x, float y, float width) {
        return FaceObservation.builder().center(x, y).size(width, width * 1.3f).quality(80).build();
    }
}
