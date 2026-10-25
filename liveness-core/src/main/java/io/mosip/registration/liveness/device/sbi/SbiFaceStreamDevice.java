package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceException.Kind;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Face device adapter for a MOSIP SBI (MDS) service on the local machine. Opens the SBI
 * {@code STREAM /stream} endpoint and delivers the M-JPEG preview as {@link PixelFormat#JPEG} frames.
 *
 * <p>{@code STREAM} is a custom HTTP method that {@link java.net.HttpURLConnection} refuses, so the
 * request is written on a plain socket. The SBI service listens on 127.0.0.1 only.
 *
 * <p>The request names the device both ways: {@code deviceId} (from discovery; SBI 0.9.5, used by MOSIP
 * 1.2 devices and the MOSIP mock SBI) and {@code serialNo} (SBI 1.0). It sends no {@code timeout}: in SBI
 * that limits the total stream duration, and the stream must run until the session stops it. Use
 * {@link SbiDiscovery} to find the device ID.
 */
public final class SbiFaceStreamDevice implements FaceCaptureDevice {

    /** Stream requests answered "busy" or "not ready" are retried this many times in total, this far apart. */
    static final int BUSY_ATTEMPTS = 6;
    static final long BUSY_RETRY_MS = 250;

    private final String host;
    private final int port;
    private final String deviceId;
    private final String deviceSubId;
    private final DeviceInfo info;
    private final int connectTimeoutMs;
    private final int frameTimeoutMs;
    private final AtomicLong sequence = new AtomicLong();

    private volatile DeviceStatus status = DeviceStatus.DISCONNECTED;
    private volatile Socket socket;
    private volatile boolean stopping;

    /**
     * @param deviceId    the SBI device ID reported by discovery
     * @param deviceSubId the sub-ID to stream (a face device usually has one)
     */
    public SbiFaceStreamDevice(String host, int port, String deviceId, String deviceSubId, DeviceInfo info) {
        this(host, port, deviceId, deviceSubId, info, 3_000, 5_000);
    }

    /**
     * @param frameTimeoutMs longest gap between stream bytes before the stream is treated as broken
     */
    public SbiFaceStreamDevice(String host, int port, String deviceId, String deviceSubId, DeviceInfo info,
                               int connectTimeoutMs, int frameTimeoutMs) {
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.deviceSubId = deviceSubId;
        this.info = info;
        this.connectTimeoutMs = connectTimeoutMs;
        this.frameTimeoutMs = frameTimeoutMs;
    }

    @Override
    public DeviceInfo info() {
        return info;
    }

    @Override
    public DeviceStatus status() {
        return status;
    }

    @Override
    public synchronized void open() throws DeviceException {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        } catch (IOException e) {
            status = DeviceStatus.DISCONNECTED;
            throw new DeviceException(Kind.UNAVAILABLE, "SBI service not reachable at " + host + ":" + port, e);
        }
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
        stopping = false;
        Socket s;
        MjpegStreamReader reader;
        for (int attempt = 1; ; attempt++) {
            s = new Socket();
            try {
                reader = requestStream(s);
                break;
            } catch (IOException e) {
                closeQuietly(s);
                throw new DeviceException(Kind.CONNECTION_FAILED, "Could not open SBI stream: " + e.getMessage(), e);
            } catch (DeviceException e) {
                closeQuietly(s);
                // A device service only notices a closed stream on its next frame, so it can report "busy"
                // for a moment after stopStream(); a quick restart (Retry) must not fail because of that.
                if (e.getKind() != Kind.CAMERA_UNAVAILABLE || attempt >= BUSY_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(BUSY_RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        socket = s;
        status = DeviceStatus.STREAMING;
        MjpegStreamReader frames = reader;
        Thread pump = new Thread(() -> pump(frames, listener), "sbi-face-stream-" + info.getSerialNo());
        pump.setDaemon(true);
        pump.start();
    }

    /** Sends {@code STREAM /stream} and reads the response head. @return a reader positioned at the first frame */
    private MjpegStreamReader requestStream(Socket s) throws IOException, DeviceException {
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setSoTimeout(frameTimeoutMs);
        SbiHttp.writeRequest(s.getOutputStream(), "STREAM", "/stream", host, port,
                "{\"deviceId\":\"" + SbiHttp.json(deviceId) + "\",\"serialNo\":\"" + SbiHttp.json(info.getSerialNo())
                        + "\",\"deviceSubId\":\"" + SbiHttp.json(deviceSubId) + "\"}");

        InputStream in = new BufferedInputStream(s.getInputStream());
        String statusLine = MjpegStreamReader.readLine(in);
        Map<String, String> headers = MjpegStreamReader.readHeaders(in);
        if (SbiHttp.statusCode(statusLine) != 200) {
            throw new DeviceException(Kind.CONNECTION_FAILED, "SBI stream request rejected: " + statusLine, null);
        }
        if (!headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            // SBI answers a refused stream with HTTP 200 and a JSON error instead of video.
            throw sbiError(SbiHttp.readBody(in, headers));
        }
        InputStream body = "chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))
                ? new ChunkedInputStream(in) : in;
        return new MjpegStreamReader(body, boundaryOf(headers.get("content-type")));
    }

    /** Turns an SBI error body ({"errorCode","errorInfo"}, possibly nested in a list) into a DeviceException. */
    static DeviceException sbiError(String body) {
        String code = "?";
        String detail = body.trim();
        try {
            Object json = MiniJson.parse(body.trim());
            Object c = MiniJson.find(json, "errorCode");
            Object i = MiniJson.find(json, "errorInfo");
            code = c == null ? "?" : c.toString();
            detail = i == null ? "" : i.toString();
        } catch (IllegalArgumentException ignored) {
            // not JSON: report the raw text
        }
        if (detail.length() > 200) {
            detail = detail.substring(0, 200);
        }
        // 110 = not ready, 111 = busy: the device exists but cannot stream right now.
        Kind kind = "110".equals(code) || "111".equals(code) ? Kind.CAMERA_UNAVAILABLE : Kind.CONNECTION_FAILED;
        return new DeviceException(kind,
                "SBI refused the stream (error " + code + (detail.isEmpty() ? "" : ": " + detail) + ")", null);
    }

    private void pump(MjpegStreamReader reader, FrameListener listener) {
        Socket s = socket;
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
                listener.onStreamError(new DeviceException(Kind.STREAM_ERROR, "SBI stream interrupted: " + e.getMessage(), e));
            }
        } finally {
            closeQuietly(s);
        }
    }

    @Override
    public synchronized void stopStream() {
        stopping = true;
        closeQuietly(socket);
        socket = null;
        if (status == DeviceStatus.STREAMING) {
            status = DeviceStatus.READY;
        }
    }

    @Override
    public synchronized void close() {
        stopStream();
        status = DeviceStatus.DISCONNECTED;
    }

    static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String param : contentType.split(";")) {
            String p = param.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String value = p.substring("boundary=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
