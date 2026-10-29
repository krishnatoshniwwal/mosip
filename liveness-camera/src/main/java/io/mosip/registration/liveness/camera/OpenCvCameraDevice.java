package io.mosip.registration.liveness.camera;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * {@link FaceCaptureDevice} implementation backed by OpenCV's {@link VideoCapture}.
 *
 * <p>Captures BGR24 frames from a local webcam at 640x480 resolution and delivers
 * them to the registered {@link FrameListener} at the configured frame rate.
 *
 * <p>This class is thread-safe. All state transitions are guarded by {@code synchronized}
 * blocks on the instance; {@link #stopStream()} waits for the capture thread outside them.
 */
public class OpenCvCameraDevice implements FaceCaptureDevice {

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    /** Wait before reading again after the backend returned the previous frame unchanged. */
    private static final long REDELIVERY_POLL_MS = 5;
    /** Device IDs of local cameras start with this, followed by the camera index. */
    public static final String DEVICE_ID_PREFIX = "OPENCV-CAM-";

    private final int cameraIndex;
    private final int fps;
    private final String deviceId;
    private final DeviceInfo deviceInfo;

    private final IntFunction<FrameSource> opener;
    private FrameSource capture;
    private volatile DeviceStatus status = DeviceStatus.DISCONNECTED;
    /** The thread that owns the current stream; a capture loop that is no longer this thread must stop. */
    private volatile Thread captureThread;

    /** What the capture loop reads from: a {@link VideoCapture} in production. */
    interface FrameSource {
        boolean isOpened();

        boolean read(Mat into);

        void release();
    }

    /**
     * Creates a camera device with default camera index 0 and 15 fps.
     */
    public OpenCvCameraDevice() {
        this(0, 15);
    }

    /**
     * Creates a camera device targeting the given camera index and frame rate.
     *
     * @param cameraIndex OS camera index (usually 0 for the built-in webcam)
     * @param fps         target frames per second for the capture loop
     */
    public OpenCvCameraDevice(int cameraIndex, int fps) {
        this(cameraIndex, fps, OpenCvCameraDevice::openVideoCapture);
    }

    /** @param opener opens the camera at an index, returning null when it cannot be opened */
    OpenCvCameraDevice(int cameraIndex, int fps, IntFunction<FrameSource> opener) {
        this.cameraIndex = cameraIndex;
        this.fps = fps;
        this.opener = opener;
        this.deviceId = DEVICE_ID_PREFIX + cameraIndex;
        this.deviceInfo = new DeviceInfo(
                deviceId,
                "OpenCV",
                "VideoCapture",
                String.valueOf(cameraIndex),
                DeviceLevel.L0,
                org.opencv.core.Core.VERSION,
                Collections.unmodifiableSet(EnumSet.of(PixelFormat.BGR24))
        );
    }

    @Override
    public DeviceInfo info() {
        return deviceInfo;
    }

    @Override
    public synchronized DeviceStatus status() {
        return status;
    }

    /**
     * Opens the webcam and configures capture resolution to 640x480.
     *
     * @throws DeviceException if the camera cannot be opened
     */
    @Override
    public synchronized void open() throws DeviceException {
        OpenCvLoader.ensureLoaded();

        if (status == DeviceStatus.READY || status == DeviceStatus.STREAMING) {
            return; // already open
        }

        if (capture != null) {
            capture.release();
        }
        capture = opener.apply(cameraIndex);
        if (capture == null) {
            status = DeviceStatus.ERROR;
            throw new DeviceException(
                    DeviceException.Kind.CAMERA_UNAVAILABLE,
                    "Could not open camera at index " + cameraIndex,
                    null
            );
        }

        status = DeviceStatus.READY;
    }

    private static FrameSource openVideoCapture(int cameraIndex) {
        VideoCapture vc = new VideoCapture();
        if (!vc.open(cameraIndex, backend())) {
            vc.release();
            return null;
        }
        vc.set(Videoio.CAP_PROP_FRAME_WIDTH, DEFAULT_WIDTH);
        vc.set(Videoio.CAP_PROP_FRAME_HEIGHT, DEFAULT_HEIGHT);
        return new FrameSource() {
            @Override
            public boolean isOpened() {
                return vc.isOpened();
            }

            @Override
            public boolean read(Mat into) {
                return vc.read(into);
            }

            @Override
            public void release() {
                vc.release();
            }
        };
    }

    /**
     * On Windows, Media Foundation only, unless OpenCV was told to skip it ({@code OPENCV_VIDEOIO_PRIORITY_MSMF=0}).
     * OpenCV would otherwise fall back to DirectShow for an index Media Foundation cannot open, and DirectShow also
     * lists software cameras such as OBS; {@link SystemCameraSourceCheck} relies on this to tell which are reachable.
     */
    static int backend() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows && !"0".equals(System.getenv("OPENCV_VIDEOIO_PRIORITY_MSMF")) ? Videoio.CAP_MSMF : Videoio.CAP_ANY;
    }

    /**
     * Starts delivering frames to the given listener on a background daemon thread.
     *
     * @param listener the callback that receives captured frames
     * @throws DeviceException if the device is not in {@link DeviceStatus#READY} state
     */
    @Override
    public synchronized void startStream(FrameListener listener) throws DeviceException {
        if (status != DeviceStatus.READY) {
            throw new DeviceException(
                    DeviceException.Kind.CONNECTION_FAILED,
                    "Device is not ready; current status: " + status,
                    null
            );
        }

        status = DeviceStatus.STREAMING;

        captureThread = new Thread(() -> captureLoop(listener), "opencv-cam-" + cameraIndex);
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /**
     * Stops the capture thread and waits for it to finish its current frame. Safe to call even when not streaming,
     * and from the listener itself. The listener is not told about a stop it asked for.
     */
    @Override
    public void stopStream() {
        Thread t;
        synchronized (this) {
            t = captureThread;
            captureThread = null;
            if (status == DeviceStatus.STREAMING) {
                status = DeviceStatus.READY;
            }
        }
        // Waited for outside the lock: the capture loop takes it for every read.
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Releases the underlying {@link VideoCapture} and resets the device to disconnected.
     */
    @Override
    public void close() {
        stopStream();
        synchronized (this) {
            if (capture != null) {
                capture.release();
                capture = null;
            }
            status = DeviceStatus.DISCONNECTED;
        }
    }

    // ---- internal capture loop ----

    private void captureLoop(FrameListener listener) {
        long sequence = 0;
        long frameDurationMs = 1000L / fps;
        Mat mat = new Mat();
        Thread self = Thread.currentThread();
        boolean errorReported = false;
        byte[] previous = null;

        try {
            while (captureThread == self && !self.isInterrupted()) {
                long start = System.currentTimeMillis();

                boolean grabbed;
                synchronized (this) {
                    if (captureThread != self) {
                        break;
                    }
                    if (capture == null || !capture.isOpened()) {
                        break;
                    }
                    grabbed = capture.read(mat);
                }
                if (captureThread != self) {
                    break;
                }

                if (!grabbed || mat.empty()) {
                    errorReported = true;
                    listener.onStreamError(new DeviceException(
                            DeviceException.Kind.STREAM_ERROR,
                            "Failed to grab frame from camera " + cameraIndex,
                            null
                    ));
                    break;
                }

                int width = mat.cols();
                int height = mat.rows();
                int channels = mat.channels();
                byte[] data = new byte[width * height * channels];
                mat.get(0, 0, data);

                // Media Foundation returns the last frame again when the camera has not exposed a new one yet
                // (its real rate drops in low light). That copy is not a new frame: passing it on would look
                // like an injected stream's repeats to the session. Poll again shortly instead.
                if (Arrays.equals(data, previous)) {
                    Thread.sleep(REDELIVERY_POLL_MS);
                    continue;
                }
                previous = data;

                FaceFrame frame = new FaceFrame(
                        deviceId,
                        sequence++,
                        System.currentTimeMillis(),
                        width,
                        height,
                        PixelFormat.BGR24,
                        data,
                        Collections.emptyMap()
                );

                listener.onFrame(frame);

                long elapsed = System.currentTimeMillis() - start;
                long sleepMs = frameDurationMs - elapsed;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            mat.release();
            boolean stopRequested;
            synchronized (this) {
                stopRequested = captureThread != self;
                if (!stopRequested) {
                    captureThread = null;
                    if (status == DeviceStatus.STREAMING) {
                        status = DeviceStatus.READY;
                    }
                }
            }
            if (!stopRequested && !errorReported) {
                listener.onStreamEnded();
            }
        }
    }
}
