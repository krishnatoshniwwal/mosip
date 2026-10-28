package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.EngineInfo;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;
import io.mosip.registration.liveness.engine.PadResult;
import io.mosip.registration.liveness.model.ModelRepository;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * On-device liveness and PAD engine: YuNet face detection with five landmarks, landmark-based head
 * pose, face quality, occlusion (sunglasses, mask, scarf), and anti-spoofing: the MiniFASNet models
 * (or a heuristic fallback) combined with screen/photo replay cues (moiré, lighting uniformity,
 * bezel edges, see {@link ReplayAttackDetector}).
 *
 * <p>Only challenges the engine can genuinely measure are advertised in {@link #info()}. Head turns
 * come from the five YuNet landmarks. Smile and look up/down (pitch from the mesh's 3D forehead-to-chin
 * line) come from the MediaPipe Face Mesh model and blink from the MediaPipe Iris model
 * ({@link FaceMeshAnalyzer}); each is offered only when its model is present, and otherwise reported as
 * unknown (NaN).
 */
public final class OnnxLivenessEngine implements LivenessEngine {

    /** System property overriding the model directory (default: {@code models} in the working directory). */
    public static final String MODEL_DIR_PROPERTY = "mosip.liveness.models";
    /** Engine version, checked against a model package's {@code engine.minVersion}. */
    public static final String ENGINE_VERSION = "1.2.0";

    private static final Logger LOG = Logger.getLogger(OnnxLivenessEngine.class.getName());
    private final EngineInfo engineInfo;
    private final FaceDetector faceDetector;
    private final FaceLandmarkAnalyzer landmarkAnalyzer;
    /** Null when the face mesh model is absent: blink and smile are then not offered. */
    private final FaceMeshAnalyzer faceMesh;
    private final AntiSpoofAnalyzer antiSpoofAnalyzer;
    private final ReplayAttackDetector replayDetector = new ReplayAttackDetector();
    private final OcclusionAnalyzer occlusionAnalyzer = new OcclusionAnalyzer();
    private final String accelerationReport;
    private volatile String lastDiagnostics = "no frame analysed yet";

    /**
     * Development use: loads models from a plain directory without verifying them. Production clients
     * use {@link #OnnxLivenessEngine(ModelRepository.ActiveModel)}.
     *
     * @param modelDir directory holding the model files
     * @throws LivenessEngineException if the face detection model is missing or unusable
     */
    public OnnxLivenessEngine(Path modelDir) throws LivenessEngineException {
        this(modelDir, "unverified-dir");
        LOG.warning("Liveness models loaded from an unverified directory " + modelDir
                + "; use a signed model package in production");
    }

    /**
     * Loads the models of a signed package that {@link ModelRepository#activate()} has just verified.
     * The package identity goes into {@code EngineInfo.modelVersion} and so into every session's audit.
     */
    public OnnxLivenessEngine(ModelRepository.ActiveModel model) throws LivenessEngineException {
        this(model.getDirectory(), "pkg " + model.getManifest().label());
    }

    private OnnxLivenessEngine(Path modelDir, String source) throws LivenessEngineException {
        this.faceDetector = new FaceDetector(modelDir);
        this.landmarkAnalyzer = new FaceLandmarkAnalyzer(modelDir);
        this.antiSpoofAnalyzer = new AntiSpoofAnalyzer(modelDir);
        this.faceMesh = FaceMeshAnalyzer.loadIfPresent(modelDir);

        Set<ChallengeType> measurable = EnumSet.of(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT);
        if (faceMesh != null) {
            measurable.add(ChallengeType.SMILE);
            measurable.add(ChallengeType.LOOK_UP);
            measurable.add(ChallengeType.LOOK_DOWN);
            if (faceMesh.measuresEyes()) {
                measurable.add(ChallengeType.BLINK);
            }
        } else {
            LOG.info("Face mesh model " + FaceMeshAnalyzer.MESH_FILE + " not found in " + modelDir
                    + ": blink, smile and look up/down challenges are disabled");
        }
        String modelVersion = "yunet-2023mar+" + (antiSpoofAnalyzer.isUsingModel() ? "minifasnet" : "heuristic-pad")
                + (faceMesh != null ? "+facemesh" : "") + (faceMesh != null && faceMesh.measuresEyes() ? "+iris" : "")
                + "+replay-cues+occlusion (" + source + ")";
        this.engineInfo = new EngineInfo("MOSIP Real-Time Liveness Engine", ENGINE_VERSION, modelVersion,
                measurable, true);

        StringBuilder accel = new StringBuilder(faceDetector.acceleration().toString());
        if (faceMesh != null) {
            for (InferenceTarget.Choice c : faceMesh.acceleration()) {
                accel.append("; ").append(c);
            }
        }
        accel.append("; minifasnet: onnxruntime cpu");
        this.accelerationReport = accel.toString();
        LOG.info("Inference targets (" + InferenceTarget.PROPERTY + "=" + InferenceTarget.setting() + "): "
                + accelerationReport);
    }

    /**
     * Where each model runs and the per-target timings measured at start, e.g.
     * {@code "yunet: cpu 14.7 ms, opencl 33.2 ms, opencl_fp16 14.9 ms -> cpu; ..."}. For diagnostics and reports.
     */
    public String accelerationReport() {
        return accelerationReport;
    }

    /** Model directory from {@value #MODEL_DIR_PROPERTY}, or {@code models} in the working directory. */
    public static Path defaultModelDir() {
        return Paths.get(System.getProperty(MODEL_DIR_PROPERTY, "models")).toAbsolutePath();
    }

    /**
     * Measurements behind the last frame's PAD and occlusion decisions (numbers only, no image data),
     * for the diagnostic mode of the capture screen. Never show this to the person being verified.
     */
    public String lastDiagnostics() {
        return lastDiagnostics;
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
                lastDiagnostics = "no face detected";
                return new FrameAnalysis(Collections.emptyList(), 0f, PadResult.notEvaluated());
            }

            List<FaceObservation> observations = new ArrayList<>();
            StringBuilder diagnostics = new StringBuilder();
            for (DetectedFace face : detectedFaces) {
                OcclusionAnalyzer.Result occlusion = occlusionAnalyzer.measure(bgrMat, face);
                if (diagnostics.length() == 0) {
                    diagnostics.append("occlusion: ").append(occlusion.measurements);
                }
                Rect r = face.getBox();
                float[] pose = HeadPoseEstimator.estimate(face);
                FaceObservation.Builder observation = FaceObservation.builder()
                        .center((r.x + r.width / 2.0f) / frameW, (r.y + r.height / 2.0f) / frameH)
                        .size((float) r.width / frameW, (float) r.height / frameH)
                        .quality(landmarkAnalyzer.estimateQuality(bgrMat, r))
                        .pose(pose[0], pose[1], pose[2])
                        .occluded(occlusion.regions);
                // Eyes and smile only matter for a single face (a second face fails or pauses the session).
                if (faceMesh != null && detectedFaces.size() == 1) {
                    FaceMeshAnalyzer.Measurement mesh = faceMesh.measure(bgrMat, face);
                    observation.eyes(mesh.leftEye, mesh.rightEye).smile(mesh.smile);
                    if (!Float.isNaN(mesh.pitch)) {
                        observation.pose(pose[0], mesh.pitch, pose[2]);
                    }
                    diagnostics.append("\n").append(mesh.readout);
                }
                observations.add(observation.build());
            }

            // PAD and passive score are evaluated for the primary face (when exactly one is present)
            PadResult padResult = PadResult.notEvaluated();
            float livenessScore = 0f;

            if (detectedFaces.size() == 1) {
                Rect box = detectedFaces.get(0).getBox();
                padResult = antiSpoofAnalyzer.analyze(bgrMat, box);
                if (!Float.isNaN(padResult.getBonaFideScore())) {
                    ReplayAttackDetector.Assessment replay = replayDetector.analyze(bgrMat, box);
                    float modelScore = padResult.getBonaFideScore();
                    padResult = ReplayAttackDetector.combine(padResult, replay);
                    diagnostics.append("\nreplay: ").append(replay)
                            .append(String.format(Locale.ROOT, "\nPAD: model %.1f -> combined %.1f%s", modelScore,
                                    padResult.getBonaFideScore(), padResult.isAttackDetected() ? " ATTACK" : ""));
                }
                livenessScore = antiSpoofAnalyzer.livenessScore(padResult);
            }

            if (detectedFaces.size() > 1) {
                diagnostics.append("\n").append(detectedFaces.size()).append(" faces");
            }
            lastDiagnostics = diagnostics.toString();
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
        if (faceMesh != null) {
            faceMesh.close();
        }
    }
}
