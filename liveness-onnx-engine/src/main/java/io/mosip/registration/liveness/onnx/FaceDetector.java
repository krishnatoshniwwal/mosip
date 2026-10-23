package io.mosip.registration.liveness.onnx;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects faces in a BGR {@link Mat} using one of two strategies:
 *
 * <ol>
 *   <li><b>DNN mode</b> — uses OpenCV's DNN module with a Caffe-based face detection model
 *       ({@code deploy.prototxt} + {@code res10_300x300_ssd_iter_140000.caffemodel}).</li>
 *   <li><b>Haar cascade fallback</b> — when the DNN model files are not available, falls back to
 *       OpenCV's bundled {@code haarcascade_frontalface_alt2.xml}.</li>
 * </ol>
 *
 * <p>Instances are <em>not</em> thread-safe; use one per worker thread.
 */
public final class FaceDetector implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FaceDetector.class.getName());

    /** Minimum confidence for DNN detections. */
    private static final float DNN_CONFIDENCE_THRESHOLD = 0.5f;

    /** DNN input blob size (the SSD model expects 300×300). */
    private static final int DNN_INPUT_SIZE = 300;

    /** Minimum Haar detection size as a fraction of the frame's smaller dimension. */
    private static final double HAAR_MIN_SIZE_FRACTION = 0.15;

    private Net dnnNet;
    private CascadeClassifier haarClassifier;
    private final boolean usingDnn;

    /**
     * Create a new face detector.
     *
     * @param modelDir directory that may contain {@code deploy.prototxt} and
     *                 {@code res10_300x300_ssd_iter_140000.caffemodel}
     */
    public FaceDetector(Path modelDir) {
        Net net = null;
        if (modelDir != null) {
            File prototxt = modelDir.resolve("deploy.prototxt").toFile();
            File caffemodel = modelDir.resolve("res10_300x300_ssd_iter_140000.caffemodel").toFile();
            if (prototxt.isFile() && caffemodel.isFile()) {
                try {
                    net = Dnn.readNetFromCaffe(prototxt.getAbsolutePath(), caffemodel.getAbsolutePath());
                    LOG.info("Face detector: loaded Caffe DNN model from " + modelDir);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to load Caffe DNN model — falling back to Haar cascade", e);
                    net = null;
                }
            }
        }

        if (net != null) {
            this.dnnNet = net;
            this.haarClassifier = null;
            this.usingDnn = true;
        } else {
            this.dnnNet = null;
            this.haarClassifier = loadHaarCascade();
            this.usingDnn = false;
            LOG.warning("Face detector: DNN model not available — using Haar cascade fallback. "
                    + "Detection accuracy will be reduced.");
        }
    }

    /**
     * Detect faces in a BGR image.
     *
     * @param bgrFrame the input image in BGR colour space
     * @return list of face bounding boxes (may be empty, never null)
     */
    public List<Rect> detect(Mat bgrFrame) {
        if (bgrFrame == null || bgrFrame.empty()) {
            return Collections.emptyList();
        }
        if (usingDnn) {
            return detectDnn(bgrFrame);
        } else {
            return detectHaar(bgrFrame);
        }
    }

    /** Whether this detector is using the full DNN model (true) or Haar fallback (false). */
    public boolean isUsingDnn() {
        return usingDnn;
    }

    // ---------------------------------------------------------------------- DNN detection

    private List<Rect> detectDnn(Mat bgrFrame) {
        int frameWidth = bgrFrame.cols();
        int frameHeight = bgrFrame.rows();

        // Create a 4D blob from the frame
        Mat blob = Dnn.blobFromImage(bgrFrame,
                1.0,                                               // scalefactor
                new Size(DNN_INPUT_SIZE, DNN_INPUT_SIZE),          // spatial size
                new Scalar(104.0, 177.0, 123.0),                  // mean subtraction
                false,                                              // swapRB
                false);                                             // crop

        dnnNet.setInput(blob);
        Mat detections = dnnNet.forward();
        blob.release();

        // The output shape is [1, 1, N, 7] where each detection row is:
        // [batchId, classId, confidence, left, top, right, bottom]
        // We need to reshape to access individual detections.
        Mat detectionMat = detections.reshape(1, (int) detections.total() / 7);

        List<Rect> faces = new ArrayList<>();
        for (int i = 0; i < detectionMat.rows(); i++) {
            float confidence = (float) detectionMat.get(i, 2)[0];
            if (confidence < DNN_CONFIDENCE_THRESHOLD) {
                continue;
            }

            int left = (int) (detectionMat.get(i, 3)[0] * frameWidth);
            int top = (int) (detectionMat.get(i, 4)[0] * frameHeight);
            int right = (int) (detectionMat.get(i, 5)[0] * frameWidth);
            int bottom = (int) (detectionMat.get(i, 6)[0] * frameHeight);

            // Clamp to frame boundaries
            left = Math.max(0, left);
            top = Math.max(0, top);
            right = Math.min(frameWidth, right);
            bottom = Math.min(frameHeight, bottom);

            int w = right - left;
            int h = bottom - top;
            if (w > 0 && h > 0) {
                faces.add(new Rect(left, top, w, h));
            }
        }

        detectionMat.release();
        detections.release();
        return faces;
    }

    // ---------------------------------------------------------------------- Haar fallback

    private List<Rect> detectHaar(Mat bgrFrame) {
        if (haarClassifier == null) {
            return Collections.emptyList();
        }

        Mat gray = new Mat();
        Imgproc.cvtColor(bgrFrame, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.equalizeHist(gray, gray);

        int minDim = Math.min(bgrFrame.cols(), bgrFrame.rows());
        int minFaceSize = (int) (minDim * HAAR_MIN_SIZE_FRACTION);

        MatOfRect faceRects = new MatOfRect();
        haarClassifier.detectMultiScale(
                gray,
                faceRects,
                1.1,                                    // scaleFactor
                3,                                       // minNeighbors
                0,                                       // flags
                new Size(minFaceSize, minFaceSize),      // minSize
                new Size()                               // maxSize (unlimited)
        );
        gray.release();

        Rect[] rects = faceRects.toArray();
        faceRects.release();

        List<Rect> result = new ArrayList<>();
        for (Rect r : rects) {
            result.add(r);
        }
        return result;
    }

    // ---------------------------------------------------------------------- Cascade loading

    /**
     * Load the Haar cascade from OpenCV's bundled resources. The xml is extracted from the
     * classpath into a temp file because {@link CascadeClassifier} needs a filesystem path.
     */
    private static CascadeClassifier loadHaarCascade() {
        String resourceName = "/haarcascade_frontalface_alt2.xml";
        InputStream is = FaceDetector.class.getResourceAsStream(resourceName);
        if (is == null) {
            // Try an alternative path that some OpenCV distributions use
            is = FaceDetector.class.getResourceAsStream("/opencv/haarcascade_frontalface_alt2.xml");
        }
        if (is == null) {
            LOG.warning("Haar cascade resource not found on classpath — face detection will be unavailable.");
            return null;
        }

        try {
            Path tempFile = Files.createTempFile("mosip_haar_", ".xml");
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            is.close();

            CascadeClassifier cc = new CascadeClassifier(tempFile.toAbsolutePath().toString());
            if (cc.empty()) {
                LOG.warning("Failed to load Haar cascade from temp file — face detection will be unavailable.");
                return null;
            }
            LOG.info("Face detector: loaded Haar cascade from bundled resource.");
            return cc;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not extract Haar cascade to temp file", e);
            return null;
        }
    }

    @Override
    public void close() {
        if (dnnNet != null) {
            // OpenCV Net does not have an explicit release, but we null the reference
            dnnNet = null;
        }
        haarClassifier = null;
    }
}
