package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Utility to convert a {@link FaceFrame} (any supported {@link PixelFormat}) into an OpenCV
 * {@link Mat} in BGR colour space, which is what the OpenCV DNN module and most OpenCV functions
 * expect.
 *
 * <p>This class is stateless and all methods are static.
 */
public final class FrameDecoder {

    private FrameDecoder() {
        // utility class — no instances
    }

    /**
     * Convert a {@link FaceFrame} to a BGR {@link Mat}.
     *
     * @param frame the face frame to decode
     * @return a new {@link Mat} in BGR format; caller is responsible for releasing via
     *         {@link Mat#release()}
     * @throws IllegalArgumentException if the frame format is unsupported or the data is malformed
     */
    public static Mat decode(FaceFrame frame) {
        PixelFormat format = frame.getFormat();
        byte[] data = frame.getData();
        int w = frame.getWidth();
        int h = frame.getHeight();

        switch (format) {
            case BGR24:
                return decodeBgr24(data, w, h);
            case RGB24:
                return decodeRgb24(data, w, h);
            case GRAY8:
                return decodeGray8(data, w, h);
            case JPEG:
                return decodeJpeg(data);
            case NV21:
                return decodeNv21(data, w, h);
            default:
                throw new IllegalArgumentException("Unsupported pixel format: " + format);
        }
    }

    private static Mat decodeBgr24(byte[] data, int w, int h) {
        Mat mat = new Mat(h, w, CvType.CV_8UC3);
        mat.put(0, 0, data);
        return mat;
    }

    private static Mat decodeRgb24(byte[] data, int w, int h) {
        Mat rgb = new Mat(h, w, CvType.CV_8UC3);
        rgb.put(0, 0, data);
        Mat bgr = new Mat();
        Imgproc.cvtColor(rgb, bgr, Imgproc.COLOR_RGB2BGR);
        rgb.release();
        return bgr;
    }

    private static Mat decodeGray8(byte[] data, int w, int h) {
        Mat gray = new Mat(h, w, CvType.CV_8UC1);
        gray.put(0, 0, data);
        Mat bgr = new Mat();
        Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR);
        gray.release();
        return bgr;
    }

    private static Mat decodeJpeg(byte[] data) {
        MatOfByte mob = new MatOfByte(data);
        Mat decoded = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
        mob.release();
        if (decoded.empty()) {
            decoded.release();
            throw new IllegalArgumentException("Failed to decode JPEG frame");
        }
        return decoded;
    }

    private static Mat decodeNv21(byte[] data, int w, int h) {
        // NV21 (YUV 4:2:0 semi-planar) has h*3/2 rows of width w, single channel
        Mat yuv = new Mat(h * 3 / 2, w, CvType.CV_8UC1);
        yuv.put(0, 0, data);
        Mat bgr = new Mat();
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21);
        yuv.release();
        return bgr;
    }
}
