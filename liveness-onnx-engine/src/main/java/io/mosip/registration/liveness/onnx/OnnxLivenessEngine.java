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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * On-device Liveness and Presentation Attack Detection (PAD) engine.
 * Integrates OpenCV face detection, facial landmark analysis, and anti-spoofing
 * (ONNX model or multi-cue heuristic analysis).
 */
public final class OnnxLivenessEngine implements LivenessEngine {

    private static final Logger LOG = Logger.getLogger(OnnxLivenessEngine.class.getName());

    private final EngineInfo engineInfo;
    private final FaceDetector faceDetector;
    private final FaceLandmarkAnalyzer landmarkAnalyzer;
    private final AntiSpoofAnalyzer antiSpoofAnalyzer;

    public OnnxLivenessEngine() {
        this(null);
    }

    public OnnxLivenessEngine(Path modelDir) {
        this.faceDetector = new FaceDetector(modelDir);
        this.landmarkAnalyzer = new FaceLandmarkAnalyzer(modelDir);
        this.antiSpoofAnalyzer = new AntiSpoofAnalyzer(modelDir);

        String modelVersion = antiSpoofAnalyzer.isUsingModel() ? "onnx-minifasnet" : "heuristic-v1";
        this.engineInfo = new EngineInfo(
                "MOSIP Real-Time Liveness Engine",
                "1.0.0",
                modelVersion,
                EnumSet.allOf(ChallengeType.class),
                true
        );
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

            List<Rect> detectedFaces = faceDetector.detect(bgrMat);
            if (detectedFaces.isEmpty()) {
                return new FrameAnalysis(Collections.emptyList(), 0f, PadResult.notEvaluated());
            }

            List<FaceObservation> observations = new ArrayList<>();
            for (Rect r : detectedFaces) {
                float[] eyes = landmarkAnalyzer.estimateEyeOpenness(bgrMat, r);
                float smile = landmarkAnalyzer.estimateSmile(bgrMat, r);
                float[] pose = landmarkAnalyzer.estimateHeadPose(bgrMat, r);
                int quality = landmarkAnalyzer.estimateQuality(bgrMat, r);

                float cx = (r.x + r.width / 2.0f) / frameW;
                float cy = (r.y + r.height / 2.0f) / frameH;
                float nw = (float) r.width / frameW;
                float nh = (float) r.height / frameH;

                FaceObservation.Builder b = FaceObservation.builder()
                        .center(cx, cy)
                        .size(nw, nh)
                        .quality(quality)
                        .smile(smile)
                        .pose(pose[0], pose[1], pose[2]);

                if (!Float.isNaN(eyes[0]) && !Float.isNaN(eyes[1])) {
                    b.eyes(eyes[0], eyes[1]);
                } else if (!Float.isNaN(eyes[0])) {
                    b.eyes(eyes[0], eyes[0]);
                } else if (!Float.isNaN(eyes[1])) {
                    b.eyes(eyes[1], eyes[1]);
                }

                observations.add(b.build());
            }

            // PAD and passive score evaluated for primary face (when 1 face is present)
            PadResult padResult = PadResult.notEvaluated();
            float livenessScore = 0f;

            if (detectedFaces.size() == 1) {
                Rect primary = detectedFaces.get(0);
                padResult = antiSpoofAnalyzer.analyze(bgrMat, primary);
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
