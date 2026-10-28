package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.engine.LivenessEngineException;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.objdetect.FaceDetectorYN;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Detects faces and five landmarks (eyes, nose tip, mouth corners) with OpenCV's YuNet model
 * ({@value #MODEL_FILE}, MIT licence, from the OpenCV model zoo). Runs fully offline.
 *
 * <p>There is deliberately no fallback detector: if the model is missing, construction fails with
 * a clear message instead of the engine silently reporting "no face" forever.
 *
 * <p>Instances are <em>not</em> thread-safe; use one per worker thread.
 */
public final class FaceDetector implements AutoCloseable {

    public static final String MODEL_FILE = "face_detection_yunet_2023mar.onnx";

    private static final Logger LOG = Logger.getLogger(FaceDetector.class.getName());

    /** Minimum detection confidence. YuNet's own default is 0.9; 0.8 keeps faces at an angle. */
    private static final float SCORE_THRESHOLD = 0.8f;
    private static final float NMS_THRESHOLD = 0.3f;
    private static final int TOP_K = 50;
    private static final int RESULT_COLUMNS = 15;

    /** Largest GPU-vs-CPU output difference accepted, relative to the largest output (about 0.5 %). */
    static final double ACCEPTED_DIFFERENCE = 0.005;

    private final FaceDetectorYN yunet;
    private final InferenceTarget.Choice acceleration;
    private Size inputSize;

    /**
     * @param modelDir directory containing {@value #MODEL_FILE}
     * @throws LivenessEngineException if the model is missing or cannot be loaded
     */
    public FaceDetector(Path modelDir) throws LivenessEngineException {
        Path model = modelDir.resolve(MODEL_FILE).toAbsolutePath();
        if (!Files.isRegularFile(model)) {
            throw new LivenessEngineException("Face detection model not found: " + model
                    + ". Run build.ps1 or build.sh once to download it.");
        }
        // Compared on YuNet's raw outputs: the detector's final face list would be empty on a synthetic input.
        acceleration = InferenceTarget.choose("yunet", Arrays.asList(InferenceTarget.OPENCL, InferenceTarget.OPENCL_FP16),
                t -> InferenceTarget.netTrial(Dnn.readNetFromONNX(model.toString()), t, 320), ACCEPTED_DIFFERENCE);
        InferenceTarget target = acceleration.target;
        try {
            inputSize = new Size(320, 320);
            yunet = FaceDetectorYN.create(model.toString(), "", inputSize, SCORE_THRESHOLD, NMS_THRESHOLD, TOP_K,
                    target.dnnBackend(), target.dnnTarget);
        } catch (RuntimeException e) {
            throw new LivenessEngineException("Could not load face detection model " + model, e);
        }
        LOG.info("Face detector: loaded YuNet model from " + model + " (" + acceleration + ")");
    }

    /** Which target YuNet runs on, and the timings behind the choice. */
    InferenceTarget.Choice acceleration() {
        return acceleration;
    }

    /**
     * @param bgrFrame image in BGR colour order
     * @return detected faces, highest confidence first (may be empty, never null)
     */
    public List<DetectedFace> detect(Mat bgrFrame) {
        if (bgrFrame == null || bgrFrame.empty()) {
            return Collections.emptyList();
        }
        Size size = bgrFrame.size();
        if (!size.equals(inputSize)) {
            yunet.setInputSize(size);
            inputSize = size;
        }
        Mat results = new Mat();
        try {
            yunet.detect(bgrFrame, results);
            List<DetectedFace> faces = new ArrayList<>();
            float[] row = new float[RESULT_COLUMNS];
            for (int i = 0; i < results.rows(); i++) {
                results.get(i, 0, row);
                Rect box = clamp(row[0], row[1], row[2], row[3], bgrFrame.cols(), bgrFrame.rows());
                if (box == null) {
                    continue;
                }
                faces.add(new DetectedFace(box,
                        new Point(row[4], row[5]), new Point(row[6], row[7]), new Point(row[8], row[9]),
                        new Point(row[10], row[11]), new Point(row[12], row[13]), row[14]));
            }
            return faces;
        } finally {
            results.release();
        }
    }

    private static Rect clamp(float x, float y, float w, float h, int frameW, int frameH) {
        int left = Math.max(0, Math.round(x));
        int top = Math.max(0, Math.round(y));
        int right = Math.min(frameW, Math.round(x + w));
        int bottom = Math.min(frameH, Math.round(y + h));
        return right > left && bottom > top ? new Rect(left, top, right - left, bottom - top) : null;
    }

    @Override
    public void close() {
        // FaceDetectorYN is released by its finalizer/cleaner; nothing to free explicitly.
    }
}
