package io.mosip.registration.liveness.onnx;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Eye openness and smile from two MediaPipe models (Apache-2.0), run unchanged through OpenCV's TFLite
 * reader, following MediaPipe's own pipelines:
 *
 * <ul>
 *   <li><b>Face Mesh</b> ({@value #MESH_FILE}, 468 landmarks): the face box rotated so the eyes are
 *       level, enlarged 1.5x into a square, 192x192 RGB in 0..1. Gives the <b>smile</b> (mouth width
 *       relative to the outer eye corners, plus how far the mouth corners rise above the lip centre),
 *       head <b>pitch</b> (depth tilt of the forehead-to-chin line) and the eye corners for the iris
 *       model.</li>
 *   <li><b>Iris</b> ({@value #IRIS_FILE}): each eye cropped around its corners, levelled, enlarged 2.3x,
 *       64x64 RGB in 0..1, one eye mirrored. Its eyelid contour gives the eye aspect ratio (EAR),
 *       mapped to <b>openness</b>. The face mesh's own eye points follow a standard eye shape and do not
 *       close with the eyelids, so they cannot detect a blink; the iris contour does.</li>
 * </ul>
 *
 * Challenges are judged against each person's own baseline, so these mappings only need the right
 * direction and range. The constants come from a small set of photos (open: EAR 0.25-0.36, closed:
 * 0.03-0.13; neutral smile 0.20-0.23, smiling 0.94-1.00); tune them from the diagnostics readout or
 * recorded sessions.
 *
 * <p>Instances are not thread-safe.
 */
final class FaceMeshAnalyzer implements AutoCloseable {

    static final String MESH_FILE = "face_landmark.tflite";
    static final String IRIS_FILE = "iris_landmark.tflite";
    static final int MESH_SIZE = 192;
    static final int IRIS_SIZE = 64;
    private static final int MESH_LANDMARKS = 468;
    private static final int IRIS_CONTOUR = 71;
    private static final double MESH_ROI_SCALE = 1.5;
    private static final double EYE_ROI_SCALE = 2.3;
    private static final double PRESENCE_THRESHOLD = 0.5;

    // Face mesh indices. 33/133 bound the eye on the left of the unmirrored image (the subject's right eye),
    // 362/263 the other one; MediaPipe mirrors the second eye's crop for the iris model.
    static final int RIGHT_EYE_OUTER = 33;
    static final int RIGHT_EYE_INNER = 133;
    static final int LEFT_EYE_INNER = 362;
    static final int LEFT_EYE_OUTER = 263;
    static final int MOUTH_RIGHT = 61;
    static final int MOUTH_LEFT = 291;
    static final int LIP_UPPER = 13;
    static final int LIP_LOWER = 14;
    static final int FOREHEAD = 10;
    static final int CHIN = 152;

    /**
     * Iris eye contour: 0..8 run along the lower lid from corner to corner, 9..15 along the upper lid
     * (MediaPipe maps them onto face mesh points 33,7,163,144,145,153,154,155,133 and 246,161,160,159,158,157,173).
     */
    static final int LID_CORNER_A = 0;
    static final int LID_CORNER_B = 8;
    static final int[][] LID_PAIRS = {{11, 3}, {12, 4}, {13, 5}};

    /** Iris-contour EAR mapped to openness 0 and 1. */
    static final double EAR_CLOSED = 0.05;
    static final double EAR_OPEN = 0.25;
    /** Mouth width / outer-eye-corner distance: about 0.55 at rest, 0.73+ when smiling. */
    static final double WIDTH_REST = 0.50;
    static final double WIDTH_RANGE = 0.20;
    /** Corner lift / outer-eye-corner distance: about 0 at rest, 0.06-0.11 when smiling. */
    static final double LIFT_REST = -0.02;
    static final double LIFT_RANGE = 0.10;
    /**
     * Forehead-to-chin depth tilt (degrees) of a face looking straight at the camera, subtracted so a
     * frontal face reads about 0, as the capture's frontal-frame check expects. From MediaPipe's canonical
     * face model: forehead (y 8.26, z 4.48) and chin (y -9.40, z 4.26), so the chin sits 0.22 units behind.
     */
    static final double PITCH_NEUTRAL = -0.7;

    private static final Logger LOG = Logger.getLogger(FaceMeshAnalyzer.class.getName());

    private final Net mesh;
    private final List<String> meshOutputs;
    /** Null without the iris model: eye openness is then unknown and blink is not offered. */
    private final Net iris;
    private final List<String> irisOutputs;
    private final List<InferenceTarget.Choice> acceleration = new ArrayList<>();

    /** One face's measurements; NaN fields mean the value could not be measured on this frame. */
    static final class Measurement {
        final float leftEye;
        final float rightEye;
        final float smile;
        /** Degrees, positive = looking up. */
        final float pitch;
        final String readout;

        Measurement(float leftEye, float rightEye, float smile, float pitch, String readout) {
            this.leftEye = leftEye;
            this.rightEye = rightEye;
            this.smile = smile;
            this.pitch = pitch;
            this.readout = readout;
        }

        static Measurement unavailable(String why) {
            return new Measurement(Float.NaN, Float.NaN, Float.NaN, Float.NaN, why);
        }
    }

    private FaceMeshAnalyzer(Net mesh, Net iris) {
        this.mesh = mesh;
        this.meshOutputs = mesh.getUnconnectedOutLayersNames();
        this.iris = iris;
        this.irisOutputs = iris == null ? null : iris.getUnconnectedOutLayersNames();
    }

    /** @return the analyzer, or null without a usable face mesh model (blink and smile then stay off) */
    static FaceMeshAnalyzer loadIfPresent(Path modelDir) {
        List<InferenceTarget.Choice> choices = new ArrayList<>();
        Net mesh = load(modelDir.resolve(MESH_FILE), MESH_SIZE, choices);
        if (mesh == null) {
            return null;
        }
        Net iris = load(modelDir.resolve(IRIS_FILE), IRIS_SIZE, choices);
        if (iris == null) {
            LOG.info("Iris model " + IRIS_FILE + " not available: eye openness (blink) is disabled");
        }
        FaceMeshAnalyzer analyzer = new FaceMeshAnalyzer(mesh, iris);
        analyzer.acceleration.addAll(choices);
        return analyzer;
    }

    /** Which target each loaded model runs on, and the timings behind the choice. */
    List<InferenceTarget.Choice> acceleration() {
        return acceleration;
    }

    private static Net load(Path model, int inputSize, List<InferenceTarget.Choice> choices) {
        if (!Files.isRegularFile(model)) {
            return null;
        }
        try {
            String file = model.toAbsolutePath().toString();
            InferenceTarget.Choice choice = InferenceTarget.choose(model.getFileName().toString(),
                    Arrays.asList(InferenceTarget.OPENCL, InferenceTarget.OPENCL_FP16),
                    t -> InferenceTarget.netTrial(Dnn.readNetFromTFLite(file), t, inputSize),
                    FaceDetector.ACCEPTED_DIFFERENCE);
            Net net = Dnn.readNetFromTFLite(file);
            if (net.empty()) {
                LOG.warning("Model could not be read: " + model);
                return null;
            }
            net.setPreferableBackend(choice.target.dnnBackend());
            net.setPreferableTarget(choice.target.dnnTarget);
            choices.add(choice);
            LOG.info("Loaded " + model + " (" + choice + ")");
            return net;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Model could not be loaded: " + model, e);
            return null;
        }
    }

    /** Whether eye openness is measured (iris model present), so blink challenges can be offered. */
    boolean measuresEyes() {
        return iris != null;
    }

    Measurement measure(Mat bgrFrame, DetectedFace face) {
        Point[] roi = roiCorners(face);
        float[] presence = {Float.NEGATIVE_INFINITY}; // a missing face flag means "no face"
        float[] points = run(mesh, meshOutputs, crop(bgrFrame, roi, MESH_SIZE, false), MESH_LANDMARKS * 3, presence);
        if (points == null || !(sigmoid(presence[0]) >= PRESENCE_THRESHOLD)) {
            return Measurement.unavailable(String.format(Locale.ROOT, "mesh: no face (presence %.2f)", sigmoid(presence[0])));
        }
        double width = mouthWidthRatio(points);
        double lift = cornerLift(points);
        float smile = smileScore(width, lift);
        float pitch = pitchDegrees(points);

        float leftEye = Float.NaN;
        float rightEye = Float.NaN;
        String eyes = "eyes: no iris model";
        if (iris != null) {
            double earRight = eyeAspectRatio(bgrFrame, toFrame(roi, points, RIGHT_EYE_OUTER),
                    toFrame(roi, points, RIGHT_EYE_INNER), false);
            double earLeft = eyeAspectRatio(bgrFrame, toFrame(roi, points, LEFT_EYE_INNER),
                    toFrame(roi, points, LEFT_EYE_OUTER), true);
            leftEye = openness(earLeft);
            rightEye = openness(earRight);
            eyes = String.format(Locale.ROOT, "eyelid EAR L %.3f R %.3f -> eyes %.2f/%.2f", earLeft, earRight, leftEye, rightEye);
        }
        String readout = String.format(Locale.ROOT, "mesh: %s | mouth width %.3f lift %.3f -> smile %.2f | pitch %.1f",
                eyes, width, lift, smile, pitch);
        return new Measurement(leftEye, rightEye, smile, pitch, readout);
    }

    /** EAR of one eye from the iris model's eyelid contour; NaN if the model gives no contour. */
    private double eyeAspectRatio(Mat bgrFrame, Point start, Point end, boolean mirror) {
        float[] contour = run(iris, irisOutputs, crop(bgrFrame, eyeRoiCorners(start, end), IRIS_SIZE, mirror),
                IRIS_CONTOUR * 3, null);
        return contour == null ? Double.NaN : lidAspectRatio(contour);
    }

    /** Warps the rotated square {@code corners} (top-left, top-right, bottom-left) to size x size, optionally mirrored. */
    private static Mat crop(Mat bgrFrame, Point[] corners, int size, boolean mirror) {
        Point[] dst = mirror
                ? new Point[] {new Point(size, 0), new Point(0, 0), new Point(size, size)}
                : new Point[] {new Point(0, 0), new Point(size, 0), new Point(0, size)};
        Mat transform = Imgproc.getAffineTransform(new MatOfPoint2f(corners), new MatOfPoint2f(dst));
        Mat out = new Mat();
        Imgproc.warpAffine(bgrFrame, out, transform, new Size(size, size), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
        transform.release();
        return out;
    }

    /**
     * Runs a model on a BGR crop (converted to RGB, 0..1). @return the output with {@code expected} values,
     * or null; a 1-value output (the mesh's face flag logit) is copied to {@code scalar} if given
     */
    private static float[] run(Net net, List<String> outputs, Mat bgrCrop, int expected, float[] scalar) {
        Mat blob = Dnn.blobFromImage(bgrCrop, 1 / 255.0, bgrCrop.size(), new Scalar(0, 0, 0), true, false);
        List<Mat> results = new ArrayList<>();
        try {
            net.setInput(blob);
            net.forward(results, outputs);
            float[] values = null;
            for (Mat out : results) {
                if (out.total() == expected) {
                    values = new float[expected];
                    out.reshape(1, 1).get(0, 0, values);
                } else if (out.total() == 1 && scalar != null) {
                    out.reshape(1, 1).get(0, 0, scalar);
                }
            }
            return values;
        } finally {
            blob.release();
            bgrCrop.release();
            for (Mat m : results) {
                m.release();
            }
        }
    }

    /**
     * Source-image positions of the mesh crop's top-left, top-right and bottom-left corners: the face box
     * centre, rotated by the eye line, side 1.5x the longer box edge (MediaPipe's detection-to-ROI step).
     */
    static Point[] roiCorners(DetectedFace face) {
        Point l = face.getEyeA().x <= face.getEyeB().x ? face.getEyeA() : face.getEyeB();
        Point r = l == face.getEyeA() ? face.getEyeB() : face.getEyeA();
        Rect box = face.getBox();
        return rotatedSquare(box.x + box.width / 2.0, box.y + box.height / 2.0,
                MESH_ROI_SCALE * Math.max(box.width, box.height), Math.atan2(r.y - l.y, r.x - l.x));
    }

    /** Eye crop corners: centred between the eye corners, levelled along them, side 2.3x their extent. */
    static Point[] eyeRoiCorners(Point start, Point end) {
        double side = EYE_ROI_SCALE * Math.max(Math.abs(end.x - start.x), Math.abs(end.y - start.y));
        return rotatedSquare((start.x + end.x) / 2, (start.y + end.y) / 2, side,
                Math.atan2(end.y - start.y, end.x - start.x));
    }

    private static Point[] rotatedSquare(double cx, double cy, double side, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double[][] unit = {{-0.5, -0.5}, {0.5, -0.5}, {-0.5, 0.5}};
        Point[] corners = new Point[3];
        for (int i = 0; i < 3; i++) {
            double x = unit[i][0] * side;
            double y = unit[i][1] * side;
            corners[i] = new Point(cx + x * cos - y * sin, cy + x * sin + y * cos);
        }
        return corners;
    }

    /** Mesh landmark {@code index} (192x192 crop pixels) back in source-image coordinates. */
    static Point toFrame(Point[] roi, float[] pts, int index) {
        double u = pts[3 * index] / MESH_SIZE;
        double v = pts[3 * index + 1] / MESH_SIZE;
        return new Point(roi[0].x + u * (roi[1].x - roi[0].x) + v * (roi[2].x - roi[0].x),
                roi[0].y + u * (roi[1].y - roi[0].y) + v * (roi[2].y - roi[0].y));
    }

    /** Mean lid opening over three upper/lower point pairs, divided by the corner-to-corner width. */
    static double lidAspectRatio(float[] contour) {
        double width = dist(contour, LID_CORNER_A, LID_CORNER_B);
        if (width < 1e-6) {
            return Double.NaN;
        }
        double opening = 0;
        for (int[] pair : LID_PAIRS) {
            opening += dist(contour, pair[0], pair[1]);
        }
        return opening / (LID_PAIRS.length * width);
    }

    static float openness(double ear) {
        return Double.isNaN(ear) ? Float.NaN : (float) clamp01((ear - EAR_CLOSED) / (EAR_OPEN - EAR_CLOSED));
    }

    static double mouthWidthRatio(float[] pts) {
        double eyes = dist(pts, RIGHT_EYE_OUTER, LEFT_EYE_OUTER);
        return eyes < 1e-6 ? Double.NaN : dist(pts, MOUTH_RIGHT, MOUTH_LEFT) / eyes;
    }

    /** Positive when the mouth corners are above the lip centre (the mesh crop is eye-levelled, y grows downwards). */
    static double cornerLift(float[] pts) {
        double eyes = dist(pts, RIGHT_EYE_OUTER, LEFT_EYE_OUTER);
        if (eyes < 1e-6) {
            return Double.NaN;
        }
        double lipCentreY = (pts[3 * LIP_UPPER + 1] + pts[3 * LIP_LOWER + 1]) / 2.0;
        double cornersY = (pts[3 * MOUTH_RIGHT + 1] + pts[3 * MOUTH_LEFT + 1]) / 2.0;
        return (lipCentreY - cornersY) / eyes;
    }

    static float smileScore(double widthRatio, double lift) {
        if (Double.isNaN(widthRatio) || Double.isNaN(lift)) {
            return Float.NaN;
        }
        double width = clamp01((widthRatio - WIDTH_REST) / WIDTH_RANGE);
        double corners = clamp01((lift - LIFT_REST) / LIFT_RANGE);
        return (float) (0.7 * width + 0.3 * corners);
    }

    /**
     * Head pitch from the forehead-to-chin line of the eye-levelled mesh crop: looking up brings the chin
     * towards the camera (MediaPipe z decreases towards the camera), tilting the line in depth. Unlike the
     * nose's position between YuNet's eye and mouth points, this does not depend on face proportions.
     *
     * @return degrees, positive = looking up, or NaN if the points are degenerate
     */
    static float pitchDegrees(float[] pts) {
        double dy = pts[3 * CHIN + 1] - pts[3 * FOREHEAD + 1];
        double dz = pts[3 * CHIN + 2] - pts[3 * FOREHEAD + 2];
        if (dy < 1e-6) {
            return Float.NaN;
        }
        return (float) (Math.toDegrees(Math.atan2(-dz, dy)) - PITCH_NEUTRAL);
    }

    private static double sigmoid(float logit) {
        return 1 / (1 + Math.exp(-logit));
    }

    private static double dist(float[] pts, int i, int j) {
        return Math.hypot(pts[3 * i] - pts[3 * j], pts[3 * i + 1] - pts[3 * j + 1]);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    @Override
    public void close() {
        // OpenCV releases the networks with their native objects.
    }
}
