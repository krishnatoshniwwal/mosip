package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SbiFaceStreamDeviceTest {

    private static final DeviceInfo INFO = new DeviceInfo("SBI-FACE-1", "Vendor", "FaceCam", "SN123",
            DeviceLevel.L1, "2.0", EnumSet.of(PixelFormat.JPEG));

    private ServerSocket server;

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void streamsMjpegFramesFromSbiStreamEndpoint() throws Exception {
        byte[] jpeg = fakeJpeg(320, 240);
        AtomicReference<String> request = new AtomicReference<>();
        startFakeSbi(out -> {
            write(out, "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n");
            for (int i = 0; i < 3; i++) {
                write(out, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n");
                out.write(jpeg);
                write(out, "\r\n");
            }
        }, request);

        SbiFaceStreamDevice device = new SbiFaceStreamDevice("127.0.0.1", server.getLocalPort(), "1", INFO);
        CollectingListener listener = new CollectingListener();
        device.open();
        device.startStream(listener);

        assertTrue(listener.ended.await(5, TimeUnit.SECONDS), "stream end should be reported");
        assertEquals(3, listener.frames.size());
        FaceFrame frame = listener.frames.get(0);
        assertEquals(PixelFormat.JPEG, frame.getFormat());
        assertEquals(320, frame.getWidth());
        assertEquals(240, frame.getHeight());
        assertTrue(frame.isWellFormed());
        assertArrayEquals(jpeg, frame.getData());
        assertTrue(request.get().startsWith("STREAM /stream HTTP/1.1"), request.get());
        assertTrue(request.get().contains("\"serialNo\":\"SN123\""), request.get());
        assertTrue(request.get().contains("\"deviceSubId\":\"1\""), request.get());
    }

    @Test
    void rejectedStreamRequestIsAConnectionFailure() throws Exception {
        startFakeSbi(out -> write(out, "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n"),
                new AtomicReference<>());
        SbiFaceStreamDevice device = new SbiFaceStreamDevice("127.0.0.1", server.getLocalPort(), "1", INFO);
        device.open();

        DeviceException e = assertThrows(DeviceException.class, () -> device.startStream(new CollectingListener()));
        assertEquals(DeviceException.Kind.CONNECTION_FAILED, e.getKind());
    }

    @Test
    void unreachableServiceIsUnavailable() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        SbiFaceStreamDevice device = new SbiFaceStreamDevice("127.0.0.1", freePort, "1", INFO, 500, 500);
        DeviceException e = assertThrows(DeviceException.class, device::open);
        assertEquals(DeviceException.Kind.UNAVAILABLE, e.getKind());
    }

    @Test
    void readerHandlesChunkedBodiesAndPartsWithoutContentLength() throws Exception {
        byte[] jpeg = fakeJpeg(64, 48);
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(ascii("--b\r\nContent-Type: image/jpeg\r\n\r\n"));
        multipart.write(jpeg);
        multipart.write(ascii("\r\n--b--\r\n"));
        byte[] body = multipart.toByteArray();

        ByteArrayOutputStream chunked = new ByteArrayOutputStream();
        int split = body.length / 2;
        chunked.write(ascii(Integer.toHexString(split) + "\r\n"));
        chunked.write(body, 0, split);
        chunked.write(ascii("\r\n" + Integer.toHexString(body.length - split) + ";ext=1\r\n"));
        chunked.write(body, split, body.length - split);
        chunked.write(ascii("\r\n0\r\n\r\n"));

        MjpegStreamReader reader = new MjpegStreamReader(
                new ChunkedInputStream(new ByteArrayInputStream(chunked.toByteArray())), "b");
        assertArrayEquals(jpeg, reader.nextFrame());
        assertNull(reader.nextFrame());
    }

    @Test
    void jpegDimensionsAreReadFromTheFrameHeader() {
        assertArrayEquals(new int[] {640, 480}, JpegInfo.dimensions(fakeJpeg(640, 480)));
        assertNull(JpegInfo.dimensions(new byte[] {1, 2, 3, 4}));
    }

    @Test
    void boundaryIsParsedFromContentType() {
        assertEquals("frame", SbiFaceStreamDevice.boundaryOf("multipart/x-mixed-replace; boundary=frame"));
        assertEquals("--x", SbiFaceStreamDevice.boundaryOf("multipart/x-mixed-replace;boundary=\"--x\""));
        assertNull(SbiFaceStreamDevice.boundaryOf("image/jpeg"));
    }

    // --- fake SBI service --------------------------------------------------------------------

    private interface Responder {
        void respond(OutputStream out) throws IOException;
    }

    private void startFakeSbi(Responder responder, AtomicReference<String> request) throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    InputStream in = new BufferedInputStream(client.getInputStream());
                    String requestLine = MjpegStreamReader.readLine(in);
                    if (requestLine == null) {
                        continue; // open() probe
                    }
                    Map<String, String> headers = MjpegStreamReader.readHeaders(in);
                    byte[] body = new byte[Integer.parseInt(headers.getOrDefault("content-length", "0"))];
                    int off = 0;
                    while (off < body.length) {
                        off += in.read(body, off, body.length - off);
                    }
                    request.set(requestLine + "\n" + new String(body, StandardCharsets.UTF_8));
                    OutputStream out = client.getOutputStream();
                    responder.respond(out);
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-sbi");
        t.setDaemon(true);
        t.start();
    }

    /** Minimal JPEG: SOI, SOF0 with the given size, EOI. */
    static byte[] fakeJpeg(int width, int height) {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x4A, 0x46,
                (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08,
                (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width,
                0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
                (byte) 0xFF, (byte) 0xD9
        };
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(ascii(s));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final class CollectingListener implements FrameListener {
        final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);

        @Override
        public void onFrame(FaceFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onStreamError(DeviceException error) {
            ended.countDown();
        }

        @Override
        public void onStreamEnded() {
            ended.countDown();
        }
    }
}
