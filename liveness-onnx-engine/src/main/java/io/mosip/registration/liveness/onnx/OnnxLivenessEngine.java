package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;
import io.mosip.registration.liveness.engine.PadResult;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * On-device liveness and PAD engine: YuNet face detection with five landmarks, landmark-based head
 * pose, face quality, and anti-spoofing (ONNX model or heuristic fallback).
 *
 * <p>Only challenges the engine can genuinely measure are advertised in {@link #info()}. Head turns
 * come from the landmarks. Blink and smile need a dense landmark model, so eye openness and smile
 * are reported as unknown (NaN) and those challenges are never issued until that model is wired in.
 */
public final class OnnxLivenessEngine implements LivenessEngine {

    /** System property overriding the model directory (default: {@code models} in the working directory). */
    public static final String MODEL_DIR_PROPERTY = "mosip.liveness.models";

    private static final Logger LOG = Logger.getLogger(OnnxLivenessEngine.class.getName());
    private static final Set<ChallengeType> MEASURABLE_CHALLENGES =
            EnumSet.of(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT);

    private final EngineInfo engineInfo;
    private final FaceDetector faceDetector;
    private final FaceLandmarkAnalyzer landmarkAnalyzer;
    private final AntiSpoofAnalyzer antiSpoofAnalyzer;

    /**
     * @param modelDir directory holding the model files
     * @throws LivenessEngineException if the face detection model is missing or unusable
     */
    public OnnxLivenessEngine(Path modelDir) throws LivenessEngineException {
        this.faceDetector = new FaceDetector(modelDir);
        this.landmarkAnalyzer = new FaceLandmarkAnalyzer(modelDir);
        this.antiSpoofAnalyzer = new AntiSpoofAnalyzer(modelDir);

        String modelVersion = "yunet-2023mar+" + (antiSpoofAnalyzer.isUsingModel() ? "minifasnet" : "heuristic-pad");
        this.engineInfo = new EngineInfo("MOSIP Real-Time Liveness Engine", "1.1.0", modelVersion,
                MEASURABLE_CHALLENGES, true);
    }

    /** Model directory from {@value #MODEL_DIR_PROPERTY}, or {@code models} in the working directory. */
    public static Path defaultModelDir() {
        return Paths.get(System.getProperty(MODEL_DIR_PROPERTY, "models")).toAbsolutePath();
    }

    @Override
    public EngineInfo info() {
        return engineInfo;
    }

    @Override
    public synchronized FrameAnalysis analyze(FaceFrame frame) throws LivenessEngineException {
        if (frame == null || !frame.isWellFormed()) {
            throw new LivenessEngineException("Invalid or malformed frame");
        }

        Mat bgrMat = null;
        try {
            bgrMat = FrameDecoder.decode(frame);
            if (bgrMat == null || bgrMat.empty()) {
                return new FrameAnalysis(Collections.emptyList(), 0f, PadResult.notEvaluated());
            }

            int frameW = bgrMat.cols();
            int frameH = bgrMat.rows();

            List<DetectedFace> detectedFaces = faceDetector.detect(bgrMat);
            if (detectedFaces.isEmpty()) {
                return new FrameAnalysis(Collections.emptyList(), 0f, PadResult.notEvaluated());
            }

            List<FaceObservation> observations = new ArrayList<>();
            for (DetectedFace face : detectedFaces) {
                Rect r = face.getBox();
                float[] pose = HeadPoseEstimator.estimate(face);
                observations.add(FaceObservation.builder()
                        .center((r.x + r.width / 2.0f) / frameW, (r.y + r.height / 2.0f) / frameH)
                        .size((float) r.width / frameW, (float) r.height / frameH)
                        .quality(landmarkAnalyzer.estimateQuality(bgrMat, r))
                        .pose(pose[0], pose[1], pose[2])
                        .build());
            }

            // PAD and passive score are evaluated for the primary face (when exactly one is present)
            PadResult padResult = PadResult.notEvaluated();
            float livenessScore = 0f;

            if (detectedFaces.size() == 1) {
                padResult = antiSpoofAnalyzer.analyze(bgrMat, detectedFaces.get(0).getBox());
                livenessScore = antiSpoofAnalyzer.livenessScore(padResult);
            }

            return new FrameAnalysis(observations, livenessScore, padResult);

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error analyzing frame: " + e.getMessage(), e);
            throw new LivenessEngineException("Analysis failed: " + e.getMessage(), e);
        } finally {
            if (bgrMat != null) {
                bgrMat.release();
            }
        }
    }

    @Override
    public void close() {
        faceDetector.close();
        landmarkAnalyzer.close();
        antiSpoofAnalyzer.close();
    }
}
