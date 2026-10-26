package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceException.Kind;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.device.sbi.JpegInfo;
import io.mosip.registration.liveness.device.sbi.MjpegStreamReader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Face device adapter over the stream the Registration Client already opens through its own MDS integration:
 * {@code bioService.getStream(mdmBioDevice, "FACE_FULL FACE")}. The client has found the device and speaks the
 * right SBI version (0.9.2, 0.9.5 or 1.0), so this adapter only splits the M-JPEG body into frames. The client's
 * {@code Streamer} preview is not used while liveness runs; frames reach the screen through the session instead.
 *
 * <p>An MDS stream cannot be restarted, so every {@link #startStream} (a retry after a disconnect) asks the
 * opener for a new one.
 */
public final class MdsStreamDevice implements FaceCaptureDevice {

    /** Opens a new face stream, e.g. {@code () -> bioService.getStream(mdmBioDevice, "FACE_FULL FACE")}. */
    @FunctionalInterface
    public interface StreamOpener {
        InputStream open() throws Exception;
    }

    private static final int MAX_BOUNDARY_LINE = 200;

    private final DeviceInfo info;
    private final StreamOpener opener;
    private final AtomicLong sequence = new AtomicLong();

    private volatile DeviceStatus status = DeviceStatus.DISCONNECTED;
    private volatile InputStream stream;
    private volatile boolean stopping;

    public MdsStreamDevice(DeviceInfo info, StreamOpener opener) {
        this.info = info;
        this.opener = opener;
    }

    /**
     * From the fields of the client's {@code MdmBioDevice}: {@code getDeviceId()}, {@code getDeviceMake()},
     * {@code getDeviceModel()}, {@code getSerialNumber()}, {@code getCertification()}, {@code getFirmWare()}.
     * A certification other than L1 is treated as L0, the weaker level.
     */
    public static MdsStreamDevice forMds(String deviceId, String make, String model, String serialNo,
                                         String certification, String firmware, StreamOpener opener) {
        DeviceLevel level = "L1".equalsIgnoreCase(certification == null ? "" : certification.trim())
                ? DeviceLevel.L1 : DeviceLevel.L0;
        return new MdsStreamDevice(new DeviceInfo(deviceId, make, model, serialNo, level, firmware,
                EnumSet.of(PixelFormat.JPEG)), opener);
    }

    @Override
    public DeviceInfo info() {
        return info;
    }

    @Override
    public DeviceStatus status() {
        return status;
    }

    /** The client found the device before creating this adapter; failures surface when the stream is opened. */
    @Override
    public synchronized void open() {
        if (status != DeviceStatus.STREAMING) {
            status = DeviceStatus.READY;
        }
    }

    @Override
    public synchronized void startStream(FrameListener listener) throws DeviceException {
        if (status == DeviceStatus.STREAMING) {
            throw new DeviceException(Kind.STREAM_ERROR, "Stream already running", null);
        }
        if (status != DeviceStatus.READY) {
            throw new DeviceException(Kind.UNAVAILABLE, "Device is not open", null);
        }
        InputStream in;
        try {
            in = opener.open();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceException(Kind.CONNECTION_FAILED, "Interrupted while opening the face stream", e);
        } catch (Exception e) {
            throw new DeviceException(Kind.CONNECTION_FAILED, "Could not open the MDS face stream: " + e.getMessage(), e);
        }
        if (in == null) {
            // BioService returns null when the MDS has no streaming face device.
            throw new DeviceException(Kind.UNAVAILABLE, "The MDS returned no face stream", null);
        }
        BufferedInputStream buffered = new BufferedInputStream(in);
        String boundary;
        try {
            boundary = sniffBoundary(buffered);
        } catch (IOException e) {
            closeQuietly(buffered);
            throw new DeviceException(Kind.CONNECTION_FAILED, "The MDS face stream ended at once", e);
        }
        stopping = false;
        stream = buffered;
        status = DeviceStatus.STREAMING;
        MjpegStreamReader reader = new MjpegStreamReader(buffered, boundary);
        Thread pump = new Thread(() -> pump(reader, buffered, listener), "mds-face-stream-" + info.getSerialNo());
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * The body starts with the first part's delimiter ({@code --boundary}) when the MDS sends multipart; otherwise
     * frames are found by their JPEG markers. Nothing is consumed.
     */
    static String sniffBoundary(BufferedInputStream in) throws IOException {
        in.mark(MAX_BOUNDARY_LINE + 2);
        byte[] head = new byte[MAX_BOUNDARY_LINE];
        int n = 0;
        int b = in.read();
        if (b < 0) {
            throw new IOException("empty stream");
        }
        while (b >= 0 && b != '\n' && n < head.length) {
            head[n++] = (byte) b;
            b = in.read();
        }
        in.reset();
        String line = new String(head, 0, n, StandardCharsets.ISO_8859_1).trim();
        if (line.startsWith("--") && line.length() > 2 && line.indexOf(' ') < 0) {
            return line.substring(2);
        }
        return null;
    }

    private void pump(MjpegStreamReader reader, InputStream in, FrameListener listener) {
        try {
            byte[] jpeg;
            while (!stopping && (jpeg = reader.nextFrame()) != null) {
                int[] size = JpegInfo.dimensions(jpeg);
                listener.onFrame(new FaceFrame(info.getDeviceId(), sequence.incrementAndGet(),
                        System.currentTimeMillis(), size == null ? 0 : size[0], size == null ? 0 : size[1],
                        PixelFormat.JPEG, jpeg, Collections.emptyMap()));
            }
            if (!stopping) {
                status = DeviceStatus.DISCONNECTED;
                listener.onStreamEnded();
            }
        } catch (IOException e) {
            if (!stopping) {
                status = DeviceStatus.ERROR;
                listener.onStreamError(new DeviceException(Kind.STREAM_ERROR,
                        "MDS face stream interrupted: " + e.getMessage(), e));
            }
        } finally {
            closeQuietly(in);
        }
    }

    @Override
    public synchronized void stopStream() {
        stopping = true;
        closeQuietly(stream);
        stream = null;
        if (status == DeviceStatus.STREAMING) {
            status = DeviceStatus.READY;
        }
    }

    @Override
    public synchronized void close() {
        stopStream();
        status = DeviceStatus.DISCONNECTED;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
