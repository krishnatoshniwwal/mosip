package io.mosip.registration.liveness.android.camera;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FaceCaptureDevice} implementation for Android registration clients using CameraX.
 *
 * <p>Receives frames from CameraX's {@code ImageAnalysis.Analyzer}, converts or validates them
 * in {@link PixelFormat#NV21} format, and forwards them to the active {@link FrameListener}.
 *
 * <p>Supports configuration of lens facing (front-facing camera for resident capture / operator login),
 * target resolution (e.g. 640x480), the analysis frame rate, and device status reporting.
 *
 * <p>Frames are delivered upright: the camera's {@code rotationDegrees} (front cameras commonly report 270°) is
 * applied with {@link Nv21Rotator}, swapping width and height for 90° and 270°. They are not mirrored, so the
 * engine sees the face as the camera does, the same as from a desktop webcam; only the on-screen preview is
 * mirrored. The camera's own value is kept as {@code sensorRotationDegrees} metadata, and {@code rotationDegrees}
 * is the rotation still to apply (0, unless the frame was malformed and passed on as it came).
 */
public class CameraXFaceCaptureDevice implements FaceCaptureDevice {

    public enum LensFacing {
        FRONT,
        BACK,
        EXTERNAL
    }

    /** Default cap on frames handed to liveness analysis, to save battery and avoid thermal throttling. */
    public static final int DEFAULT_MAX_ANALYSIS_FPS = 10;

    private final String deviceId;
    private final LensFacing lensFacing;
    private final DeviceInfo info;
    private final long minFrameIntervalMs;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final AtomicInteger corruptFrames = new AtomicInteger();
    private final AtomicBoolean cameraPermissionGranted = new AtomicBoolean(true);

    private volatile DeviceStatus status = DeviceStatus.DISCONNECTED;
    private FrameListener activeListener;
    /** Capture timestamp of the last frame delivered in this stream. */
    private long lastDeliveredMs = Long.MIN_VALUE;

    public CameraXFaceCaptureDevice() {
        this("ANDROID-CAM-FRONT", LensFacing.FRONT);
    }

    public CameraXFaceCaptureDevice(String deviceId, LensFacing lensFacing) {
        this(deviceId, lensFacing, DEFAULT_MAX_ANALYSIS_FPS);
    }

    /**
     * @param maxAnalysisFps most frames per second passed on for analysis; 0 or less passes every frame. Frames
     *                       above the rate are dropped here, before analysis, so every frame that reaches the
     *                       liveness engine is analysed itself. The on-screen preview is CameraX's own
     *                       {@code Preview} use case and is not affected.
     */
    public CameraXFaceCaptureDevice(String deviceId, LensFacing lensFacing, int maxAnalysisFps) {
        this.deviceId = deviceId;
        this.lensFacing = lensFacing;
        // 10% below the nominal interval absorbs camera timestamp jitter: a 30 fps camera at a 10 fps cap then
        // passes every third frame instead of every fourth.
        this.minFrameIntervalMs = maxAnalysisFps > 0 ? 900L / maxAnalysisFps : 0;
        this.info = new DeviceInfo(
                deviceId,
                "Android",
                "CameraX-" + lensFacing.name(),
                "CAM-001",
                DeviceLevel.L0,
                "1.3.2",
                Collections.unmodifiableSet(EnumSet.of(PixelFormat.NV21))
        );
    }

    @Override
    public DeviceInfo info() {
        return info;
    }

    @Override
    public synchronized DeviceStatus status() {
        return status;
    }

    public LensFacing getLensFacing() {
        return lensFacing;
    }

    /**
     * Sets whether camera hardware permission has been granted by the user at the OS level.
     */
    public void setCameraPermissionGranted(boolean granted) {
        this.cameraPermissionGranted.set(granted);
    }

    @Override
    public synchronized void open() throws DeviceException {
        if (!cameraPermissionGranted.get()) {
            status = DeviceStatus.ERROR;
            throw new DeviceException(
                    DeviceException.Kind.CAMERA_UNAVAILABLE,
                    "Android camera permission not granted",
                    null
            );
        }
        if (status == DeviceStatus.DISCONNECTED || status == DeviceStatus.ERROR) {
            status = DeviceStatus.READY;
        }
    }

    @Override
    public synchronized void startStream(FrameListener listener) throws DeviceException {
        if (status != DeviceStatus.READY && status != DeviceStatus.STREAMING) {
            throw new DeviceException(
                    DeviceException.Kind.CONNECTION_FAILED,
                    "Cannot start stream while device status is " + status,
                    null
            );
        }
        this.activeListener = listener;
        this.status = DeviceStatus.STREAMING;
        this.lastDeliveredMs = Long.MIN_VALUE;
    }

    /** Frames dropped by the analysis rate limit since this device was created. */
    public long droppedFrames() {
        return droppedFrames.get();
    }

    /**
     * Entry point called by CameraX {@code ImageAnalysis.Analyzer} when a frame is ready.
     *
     * @param nv21Data        raw NV21 byte buffer
     * @param width           image width
     * @param height          image height
     * @param rotationDegrees clockwise rotation that turns the frame upright, from {@code ImageInfo} (0, 90, 180, 270)
     * @param timestampMs     capture timestamp
     */
    public void onFrameAvailable(byte[] nv21Data, int width, int height, int rotationDegrees, long timestampMs) {
        FrameListener listener;
        synchronized (this) {
            if (status != DeviceStatus.STREAMING) {
                return;
            }
            listener = activeListener;
            // A timestamp going backwards means the camera restarted: deliver, and measure from here.
            boolean tooSoon = minFrameIntervalMs > 0 && lastDeliveredMs != Long.MIN_VALUE
                    && timestampMs >= lastDeliveredMs && timestampMs - lastDeliveredMs < minFrameIntervalMs;
            if (listener != null && tooSoon) {
                droppedFrames.incrementAndGet();
                return;
            }
            if (listener != null) {
                lastDeliveredMs = timestampMs;
            }
        }
        if (listener == null) {
            return;
        }

        byte[] payload = nv21Data;
        if (corruptFrames.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            payload = new byte[Math.max(0, nv21Data.length / 2)];
        }

        int outWidth = width;
        int outHeight = height;
        int rotationLeft = rotationDegrees;
        if (canRotate(payload, width, height, rotationDegrees)) {
            payload = Nv21Rotator.rotate(payload, width, height, rotationDegrees, false);
            outWidth = Nv21Rotator.rotatedWidth(width, height, rotationDegrees);
            outHeight = Nv21Rotator.rotatedHeight(width, height, rotationDegrees);
            rotationLeft = 0;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("rotationDegrees", rotationLeft);
        metadata.put("sensorRotationDegrees", rotationDegrees);
        metadata.put("lensFacing", lensFacing.name());

        FaceFrame frame = new FaceFrame(
                deviceId,
                sequence.incrementAndGet(),
                timestampMs,
                outWidth,
                outHeight,
                PixelFormat.NV21,
                payload,
                metadata
        );

        listener.onFrame(frame);
    }

    /** A malformed frame is passed on unrotated, so the engine rejects it as malformed rather than this failing. */
    private static boolean canRotate(byte[] nv21, int width, int height, int rotationDegrees) {
        return rotationDegrees % 360 != 0 && rotationDegrees % 90 == 0
                && width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0
                && nv21 != null && nv21.length >= width * height * 3 / 2;
    }

    /**
     * Injects corrupt (truncated) frames for testing fault recovery.
     */
    public void injectCorruptFrames(int count) {
        corruptFrames.set(count);
    }

    /**
     * Simulates camera stream interruption (e.g. app backgrounded or camera hijacked).
     */
    public void simulateDisconnect() {
        FrameListener listener;
        synchronized (this) {
            if (status != DeviceStatus.STREAMING) {
                return;
            }
            listener = activeListener;
            activeListener = null;
            status = DeviceStatus.DISCONNECTED;
        }
        if (listener != null) {
            listener.onStreamEnded();
        }
    }

    @Override
    public synchronized void stopStream() {
        activeListener = null;
        if (status == DeviceStatus.STREAMING) {
            status = DeviceStatus.READY;
        }
    }

    @Override
    public synchronized void close() {
        stopStream();
        status = DeviceStatus.DISCONNECTED;
    }
}
