package io.mosip.registration.liveness.regclient;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class MdsStreamDeviceTest {

    private static final DeviceInfo INFO = new DeviceInfo("face-1", "Vendor", "Cam", "SN1", DeviceLevel.L1, "1.0",
            EnumSet.of(PixelFormat.JPEG));

    @Test
    void splitsAMultipartMdsStreamIntoJpegFrames() throws Exception {
        byte[] body = multipart("mjpeg-boundary", jpeg(640, 480), jpeg(640, 480), jpeg(320, 240));
        Collector frames = new Collector();
        MdsStreamDevice device = new MdsStreamDevice(INFO, () -> new ByteArrayInputStream(body));

        device.open();
        device.startStream(frames);

        assertTrue(frames.ended.await(5, TimeUnit.SECONDS));
        assertEquals(3, frames.frames.size());
        FaceFrame last = frames.frames.get(2);
        assertEquals(PixelFormat.JPEG, last.getFormat());
        assertEquals(320, last.getWidth());
        assertEquals(240, last.getHeight());
        assertEquals("face-1", last.getDeviceId());
        assertArrayEquals(jpeg(320, 240), last.getData());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
    }

    @Test
    void readsBareJpegsWhenTheMdsSendsNoBoundary() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(jpeg(640, 480));
        body.write(jpeg(640, 480));
        Collector frames = new Collector();
        MdsStreamDevice device = new MdsStreamDevice(INFO, () -> new ByteArrayInputStream(body.toByteArray()));

        device.open();
        device.startStream(frames);

        assertTrue(frames.ended.await(5, TimeUnit.SECONDS));
        assertEquals(2, frames.frames.size());
    }

    @Test
    void openerFailuresBecomeDeviceErrors() {
        MdsStreamDevice failing = new MdsStreamDevice(INFO, () -> {
            throw new IOException("MDS not reachable");
        });
        failing.open();
        DeviceException e = assertThrows(DeviceException.class, () -> failing.startStream(new Collector()));
        assertEquals(DeviceException.Kind.CONNECTION_FAILED, e.getKind());

        MdsStreamDevice none = new MdsStreamDevice(INFO, () -> null);
        none.open();
        assertEquals(DeviceException.Kind.UNAVAILABLE,
                assertThrows(DeviceException.class, () -> none.startStream(new Collector())).getKind());
    }

    @Test
    void stopIsSilentAndAStartAfterItOpensANewStream() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MdsStreamDevice device = new MdsStreamDevice(INFO, () -> {
            opened.incrementAndGet();
            return new EndlessStream(multipart("b", jpeg(64, 48)));
        });
        Collector frames = new Collector();
        device.open();
        device.startStream(frames);
        assertTrue(frames.first.await(5, TimeUnit.SECONDS));

        device.stopStream();
        assertEquals(DeviceStatus.READY, device.status());
        device.startStream(frames);
        device.close();

        assertEquals(2, opened.get());
        assertEquals(1, frames.ended.getCount(), "a deliberate stop is not reported as the stream ending");
        assertEquals(0, frames.errors.get());
    }

    @Test
    void boundarySniffingConsumesNothing() throws Exception {
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(
                "--frame\r\nContent-Type: image/jpeg\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("frame", MdsStreamDevice.sniffBoundary(in));
        assertEquals('-', in.read());

        assertNull(MdsStreamDevice.sniffBoundary(new BufferedInputStream(new ByteArrayInputStream(jpeg(8, 8)))));
        assertThrows(IOException.class, () -> MdsStreamDevice.sniffBoundary(
                new BufferedInputStream(new ByteArrayInputStream(new byte[0]))));
    }

    @Test
    void certificationLevelComesFromTheMdsDeviceAndDefaultsToTheWeakerOne() {
        MdsStreamDevice l1 = MdsStreamDevice.forMds("d1", "Make", "Model", "SN", " l1 ", "1.0", () -> null);
        MdsStreamDevice unknown = MdsStreamDevice.forMds("d2", "Make", "Model", "SN", null, "1.0", () -> null);

        assertEquals(DeviceLevel.L1, l1.info().getLevel());
        assertEquals("d1", l1.info().getDeviceId());
        assertEquals(DeviceLevel.L0, unknown.info().getLevel());
    }

    /** The smallest JPEG header JpegInfo can size: SOI, SOF0 with the dimensions, EOI. */
    static byte[] jpeg(int w, int h) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0, 11, 8,
                (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 1, 1, 0x11, 0, (byte) 0xFF, (byte) 0xD9};
    }

    static byte[] multipart(String boundary, byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(("--" + boundary + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + part.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(part);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        return out.toByteArray();
    }

    /** Repeats one chunk until closed, like a live MDS stream. */
    private static final class EndlessStream extends InputStream {
        private final byte[] chunk;
        private int pos;
        private volatile boolean closed;

        EndlessStream(byte[] chunk) {
            this.chunk = chunk;
        }

        @Override
        public int read() throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            if (pos == chunk.length) {
                pos = 0;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            return chunk[pos++] & 0xFF;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class Collector implements FrameListener {
        final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger();

        @Override
        public void onFrame(FaceFrame frame) {
            frames.add(frame);
            first.countDown();
        }

        @Override
        public void onStreamError(DeviceException error) {
            errors.incrementAndGet();
        }

        @Override
        public void onStreamEnded() {
            ended.countDown();
        }
    }
}
