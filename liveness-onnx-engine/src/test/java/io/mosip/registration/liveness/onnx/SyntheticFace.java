package io.mosip.registration.liveness.onnx;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Draws a schematic 640x480 face with OpenCV for detector tests: skin-toned oval, eyes, brows,
 * mouth, sensor noise, and optional side lighting. Landmarks match what YuNet would report.
 */
final class SyntheticFace {

    static final int W = 640;
    static final int H = 480;
    static final Point EYE_L = new Point(285, 215);
    static final Point EYE_R = new Point(355, 215);
    static final Point NOSE = new Point(320, 255);
    static final Point MOUTH_L = new Point(295, 300);
    static final Point MOUTH_R = new Point(345, 300);
    static final Rect FACE_BOX = new Rect(235, 140, 170, 210);
    static final Scalar SKIN = new Scalar(110, 140, 190);

    static {
        nu.pattern.OpenCV.loadLocally();
        try {
            // Its temp-dir cleanup hook runs at JVM exit, after the test launcher's class loader has closed.
            Class.forName("nu.pattern.OpenCV$TemporaryDirectory$2");
        } catch (ClassNotFoundException ignored) {
            // other OpenCV builds name it differently; the warning is cosmetic
        }
    }

    private SyntheticFace() {
    }

    static DetectedFace landmarks() {
        return new DetectedFace(FACE_BOX, EYE_L, EYE_R, NOSE, MOUTH_L, MOUTH_R, 0.95f);
    }

    /** @param sideLit shade the face from 65% to 125% brightness across its width, like a real 3D face lit from one side */
    static Mat face(boolean sideLit, long seed) {
        Mat img = new Mat(H, W, CvType.CV_8UC3, new Scalar(120, 125, 130));
        Imgproc.ellipse(img, new Point(320, 245), new Size(90, 115), 0, 0, 360, SKIN, -1);
        Imgproc.line(img, new Point(268, 198), new Point(302, 196), new Scalar(40, 45, 60), 4);
        Imgproc.line(img, new Point(338, 196), new Point(372, 198), new Scalar(40, 45, 60), 4);
        for (Point eye : new Point[] {EYE_L, EYE_R}) {
            Imgproc.ellipse(img, eye, new Size(14, 7), 0, 0, 360, new Scalar(235, 235, 235), -1);
            Imgproc.circle(img, eye, 5, new Scalar(40, 30, 25), -1);
        }
        Imgproc.line(img, new Point(320, 225), new Point(314, 255), new Scalar(85, 110, 160), 2);
        Imgproc.ellipse(img, new Point(320, 300), new Size(28, 9), 0, 0, 360, new Scalar(90, 90, 170), -1);
        if (sideLit) {
            Mat gain = new Mat(H, W, CvType.CV_32FC3);
            float[] row = new float[W * 3];
            for (int x = 0; x < W; x++) {
                float t = Math.max(0f, Math.min(1f, (x - 230f) / 180f));
                float g = 0.65f + 0.6f * t;
                row[3 * x] = row[3 * x + 1] = row[3 * x + 2] = g;
            }
            for (int y = 0; y < H; y++) {
                gain.put(y, 0, row);
            }
            Mat f = new Mat();
            img.convertTo(f, CvType.CV_32FC3);
            Core.multiply(f, gain, f);
            f.convertTo(img, CvType.CV_8UC3);
            f.release();
            gain.release();
        }
        addNoise(img, 6, seed);
        Imgproc.GaussianBlur(img, img, new Size(3, 3), 0);
        return img;
    }

    static void addNoise(Mat img, double sigma, long seed) {
        Core.setRNGSeed((int) seed);
        Mat noise = new Mat(img.size(), CvType.CV_16SC3);
        Core.randn(noise, 0, sigma);
        Mat wide = new Mat();
        img.convertTo(wide, CvType.CV_16SC3);
        Core.add(wide, noise, wide);
        wide.convertTo(img, CvType.CV_8UC3);
        noise.release();
        wide.release();
    }

    /** Adds a fine diagonal interference pattern over the face, as when re-capturing a screen. */
    static void addMoire(Mat img, double amplitude) {
        Rect r = FACE_BOX;
        Mat region = img.submat(r);
        Mat pattern = new Mat(r.height, r.width, CvType.CV_16SC3);
        short[] row = new short[r.width * 3];
        for (int y = 0; y < r.height; y++) {
            for (int x = 0; x < r.width; x++) {
                short v = (short) Math.round(amplitude * Math.sin(2 * Math.PI * (0.23 * x + 0.11 * y)));
                row[3 * x] = row[3 * x + 1] = row[3 * x + 2] = v;
            }
            pattern.put(y, 0, row);
        }
        Mat wide = new Mat();
        region.convertTo(wide, CvType.CV_16SC3);
        Core.add(wide, pattern, wide);
        wide.convertTo(region, CvType.CV_8UC3);
        pattern.release();
        wide.release();
        region.release();
    }

    /** A phone held up to the camera: black bezel all round the face. */
    static void drawPhoneBezel(Mat img) {
        Imgproc.rectangle(img, new Point(187, 67), new Point(453, 423), new Scalar(15, 15, 15), 14);
    }

    static void drawMask(Mat img) {
        Mat mask = Mat.zeros(img.size(), CvType.CV_8UC1);
        Imgproc.ellipse(mask, new Point(320, 245), new Size(90, 115), 0, 0, 360, new Scalar(255), -1);
        Imgproc.rectangle(mask, new Point(0, 0), new Point(W, 252), new Scalar(0), -1);
        img.setTo(new Scalar(220, 200, 150), mask);
        mask.release();
    }

    static void drawSunglasses(Mat img) {
        for (Point eye : new Point[] {EYE_L, EYE_R}) {
            Imgproc.ellipse(img, eye, new Size(22, 12), 0, 0, 360, new Scalar(25, 25, 25), -1);
        }
        Imgproc.line(img, new Point(307, 213), new Point(333, 213), new Scalar(25, 25, 25), 3);
    }

    /** A full dark beard: not skin-coloured, but heavily textured, unlike fabric. */
    static void drawBeard(Mat img, long seed) {
        Mat region = img.submat(new Rect(250, 262, 140, 95));
        region.setTo(new Scalar(45, 55, 70));
        addNoise(region, 45, seed);
        region.release();
    }

    static double laplacianStd(Mat img) {
        Mat gray = new Mat();
        Mat lap = new Mat();
        MatOfDouble m = new MatOfDouble();
        MatOfDouble s = new MatOfDouble();
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.Laplacian(gray, lap, CvType.CV_64F);
        Core.meanStdDev(lap, m, s);
        return s.get(0, 0)[0];
    }
}
