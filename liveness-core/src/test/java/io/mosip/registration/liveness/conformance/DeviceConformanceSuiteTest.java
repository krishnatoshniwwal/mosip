package io.mosip.registration.liveness.conformance;

import io.mosip.registration.liveness.conformance.ConformanceReport.Status;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceConformanceSuiteTest {

    private static DeviceConformanceSuite.Options quick() {
        DeviceConformanceSuite.Options o = new DeviceConformanceSuite.Options();
        o.captureMs = 800;
        o.firstFrameTimeoutMs = 1_000;
        o.disconnectTimeoutMs = 1_000;
        return o;
    }

    @Test
    void mockDeviceConforms() {
        MockFaceDevice device = new MockFaceDevice(new SimulatedSubject(System::currentTimeMillis, new Random(1)), 30,
                DeviceLevel.L1);
        DeviceConformanceSuite.Options o = quick();
        o.disconnectAction = device::simulateDisconnect;

        ConformanceReport r = new DeviceConformanceSuite().run(device, o);

        assertTrue(r.passed(), r.toMarkdown());
        assertEquals(Status.WARN, r.check("resolution").status, "the mock's 32x24 frames are below 640x480");
        assertEquals(Status.PASS, r.check("disconnect reported").status);
        assertEquals(Status.PASS, r.check("reconnect").status);
        assertTrue(r.getMeasurements().containsKey("frame rate"));
        assertTrue(r.toMarkdown().contains("| frame order | PASS |"));
    }

    @Test
    void misbehavingAdapterFailsTheRightChecks() {
        ConformanceReport r = new DeviceConformanceSuite().run(new BrokenDevice(), quick());

        assertFalse(r.passed());
        assertEquals(Status.FAIL, r.check("declared formats").status, r.check("declared formats").detail);
        assertEquals(Status.FAIL, r.check("frame order").status);
        assertEquals(Status.FAIL, r.check("well-formed frames").status);
        assertEquals(Status.FAIL, r.check("frame rate").status, r.check("frame rate").detail);
        assertEquals(Status.FAIL, r.check("stop").status);
        assertEquals(Status.SKIP, r.check("disconnect reported").status);
    }

    @Test
    void unavailableDeviceFailsOpenAndSkipsTheRest() {
        MockFaceDevice device = new MockFaceDevice(new SimulatedSubject(System::currentTimeMillis, new Random(2)), 30,
                DeviceLevel.L0);
        device.setAvailable(false);

        ConformanceReport r = new DeviceConformanceSuite().run(device, quick());

        assertEquals(Status.FAIL, r.check("open").status);
        assertTrue(r.check("open").detail.startsWith("UNAVAILABLE"));
        assertEquals(Status.SKIP, r.check("frame rate").status);
    }

    @Test
    void engineBenchmarkReportsLatencyAndFailures() throws Exception {
        MockFaceDevice device = new MockFaceDevice(new SimulatedSubject(System::currentTimeMillis, new Random(3)), 60,
                DeviceLevel.L1);
        List<FaceFrame> frames = EngineBenchmark.capture(device, 10, 2_000);
        assertEquals(10, frames.size());

        SimulatedLivenessEngine engine = new SimulatedLivenessEngine();
        engine.failNext(5 + 3);   // 5 during warm-up, 3 in the timed run
        EngineBenchmark.Result r = EngineBenchmark.run(engine, frames, 5, 50);

        assertEquals(50, r.frames);
        assertEquals(3, r.failures);
        assertTrue(r.p50Ms <= r.p95Ms && r.p95Ms <= r.maxMs);
        assertTrue(r.realTimeAt(15), r.toMarkdown(15));
        assertTrue(r.toMarkdown(15).contains("real-time at 15 fps | yes"));
        assertEquals(47, r.framesWithFace, "the simulated live subject is in every frame that did not fail");
        assertTrue(r.representative());
        assertTrue(r.toMarkdown(15).contains("| frames with one face | 47 of 50 |"));
    }

    /** Declares JPEG but sends truncated GRAY8, repeats sequence numbers, ignores stop, ~3 fps. */
    private static final class BrokenDevice implements FaceCaptureDevice {
        private ScheduledExecutorService timer;
        private long seq;

        @Override
        public DeviceInfo info() {
            return new DeviceInfo("BROKEN-1", "Acme", "Bad Cam", "0", DeviceLevel.L0, "0.1", EnumSet.of(PixelFormat.JPEG));
        }

        @Override
        public DeviceStatus status() {
            return DeviceStatus.READY;
        }

        @Override
        public void open() {
        }

        @Override
        public synchronized void startStream(FrameListener listener) throws DeviceException {
            if (timer == null) {
                timer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "broken-device");
                    t.setDaemon(true);
                    return t;
                });
            }
            timer.scheduleAtFixedRate(() -> listener.onFrame(new FaceFrame("BROKEN-1", (seq++) / 2,
                    System.currentTimeMillis(), 4, 4, PixelFormat.GRAY8, new byte[3], Collections.emptyMap())),
                    0, 300, TimeUnit.MILLISECONDS);
        }

        @Override
        public void stopStream() {
            // deliberately keeps streaming
        }

        @Override
        public synchronized void close() {
            if (timer != null) {
                timer.shutdownNow();
                timer = null;
            }
        }
    }
}
