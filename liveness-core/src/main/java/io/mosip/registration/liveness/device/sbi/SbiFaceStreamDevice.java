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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
 */
public final class SbiFaceStreamDevice implements FaceCaptureDevice {

    private static final String CRLF = "\r\n";

    private final String host;
    private final int port;
    private final String deviceSubId;
    private final DeviceInfo info;
    private final int connectTimeoutMs;
    private final int frameTimeoutMs;
    private final AtomicLong sequence = new AtomicLong();

    private volatile DeviceStatus status = DeviceStatus.DISCONNECTED;
    private volatile Socket socket;
    private volatile boolean stopping;

    public SbiFaceStreamDevice(String host, int port, String deviceSubId, DeviceInfo info) {
        this(host, port, deviceSubId, info, 3_000, 5_000);
    }

    /**
     * @param frameTimeoutMs longest gap between stream bytes before the stream is treated as broken
     */
    public SbiFaceStreamDevice(String host, int port, String deviceSubId, DeviceInfo info,
                               int connectTimeoutMs, int frameTimeoutMs) {
        this.host = host;
        this.port = port;
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
        Socket s = new Socket();
        MjpegStreamReader reader;
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(frameTimeoutMs);
            sendStreamRequest(s.getOutputStream());

            InputStream in = new BufferedInputStream(s.getInputStream());
            String statusLine = MjpegStreamReader.readLine(in);
            Map<String, String> headers = MjpegStreamReader.readHeaders(in);
            if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] 200( .*)?")) {
                throw new DeviceException(Kind.CONNECTION_FAILED, "SBI stream request rejected: " + statusLine, null);
            }
            InputStream body = "chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))
                    ? new ChunkedInputStream(in) : in;
            reader = new MjpegStreamReader(body, boundaryOf(headers.get("content-type")));
        } catch (IOException e) {
            closeQuietly(s);
            throw new DeviceException(Kind.CONNECTION_FAILED, "Could not open SBI stream: " + e.getMessage(), e);
        } catch (DeviceException e) {
            closeQuietly(s);
            throw e;
        }
        socket = s;
        status = DeviceStatus.STREAMING;
        Thread pump = new Thread(() -> pump(reader, listener), "sbi-face-stream-" + info.getSerialNo());
        pump.setDaemon(true);
        pump.start();
    }

    private void sendStreamRequest(OutputStream out) throws IOException {
        byte[] body = ("{\"serialNo\":\"" + json(info.getSerialNo()) + "\",\"deviceSubId\":\"" + json(deviceSubId)
                + "\",\"timeout\":\"" + frameTimeoutMs + "\"}").getBytes(StandardCharsets.UTF_8);
        String head = "STREAM /stream HTTP/1.1" + CRLF
                + "Host: " + host + ":" + port + CRLF
                + "Content-Type: application/json" + CRLF
                + "Content-Length: " + body.length + CRLF
                + "Connection: close" + CRLF + CRLF;
        out.write(MjpegStreamReader.ascii(head));
        out.write(body);
        out.flush();
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

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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
