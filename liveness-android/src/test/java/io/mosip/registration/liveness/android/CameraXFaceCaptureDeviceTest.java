package io.mosip.registration.liveness.android;

import io.mosip.registration.liveness.android.camera.CameraXFaceCaptureDevice;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraXFaceCaptureDeviceTest {

    @Test
    void lifecycleAndFrameDelivery() throws Exception {
        CameraXFaceCaptureDevice device = new CameraXFaceCaptureDevice("CAM-TEST", CameraXFaceCaptureDevice.LensFacing.FRONT);
        assertEquals(DeviceStatus.DISCONNECTED, device.status());
        assertEquals("Android", device.info().getMake());

        device.open();
        assertEquals(DeviceStatus.READY, device.status());

        AtomicReference<FaceFrame> delivered = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean();

        FrameListener listener = new FrameListener() {
            @Override
            public void onFrame(FaceFrame frame) {
                delivered.set(frame);
            }

            @Override
            public void onStreamError(DeviceException error) {
            }

            @Override
            public void onStreamEnded() {
                ended.set(true);
            }
        };

        device.startStream(listener);
        assertEquals(DeviceStatus.STREAMING, device.status());

        // Send a frame
        int w = 640;
        int h = 480;
        byte[] nv21 = new byte[w * h * 3 / 2];
        nv21[0] = 99; // top-left of the sideways sensor image
        device.onFrameAvailable(nv21, w, h, 270, 1000L);

        FaceFrame frame = delivered.get();
        assertNotNull(frame);
        assertEquals(PixelFormat.NV21, frame.getFormat());
        assertEquals(h, frame.getWidth(), "turned upright: portrait");
        assertEquals(w, frame.getHeight());
        assertTrue(frame.isWellFormed());
        assertEquals(99, frame.getData()[(w - 1) * h], "270° clockwise puts the top-left at the bottom-left");
        assertEquals(0, frame.getMetadata().get("rotationDegrees"));
        assertEquals(270, frame.getMetadata().get("sensorRotationDegrees"));
        assertEquals("FRONT", frame.getMetadata().get("lensFacing"));

        device.simulateDisconnect();
        assertTrue(ended.get());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());

        device.close();
    }

    @Test
    void framesAboveTheAnalysisRateAreDroppedBeforeAnalysis() throws Exception {
        CameraXFaceCaptureDevice device = new CameraXFaceCaptureDevice("CAM-TEST",
                CameraXFaceCaptureDevice.LensFacing.FRONT, 10);
        device.open();
        List<Long> delivered = new ArrayList<>();
        device.startStream(new FrameListener() {
            @Override
            public void onFrame(FaceFrame frame) {
                delivered.add(frame.getTimestampMillis());
            }

            @Override
            public void onStreamError(DeviceException error) {}

            @Override
            public void onStreamEnded() {}
        });
        byte[] nv21 = new byte[32 * 24 * 3 / 2];
        for (long t = 1000; t < 2000; t += 33) { // a 30 fps camera for one second
            device.onFrameAvailable(nv21, 32, 24, 0, t);
        }
        assertEquals(Arrays.asList(1000L, 1099L, 1198L, 1297L, 1396L, 1495L, 1594L, 1693L, 1792L, 1891L, 1990L),
                delivered, "every third frame: 10 per second");
        assertEquals(31 - delivered.size(), device.droppedFrames());

        device.onFrameAvailable(nv21, 32, 24, 0, 50L); // camera restarted: timestamps went backwards
        assertEquals(50L, (long) delivered.get(delivered.size() - 1));

        CameraXFaceCaptureDevice unlimited = new CameraXFaceCaptureDevice("CAM-2",
                CameraXFaceCaptureDevice.LensFacing.FRONT, 0);
        unlimited.open();
        AtomicInteger count = new AtomicInteger();
        unlimited.startStream(new FrameListener() {
            @Override
            public void onFrame(FaceFrame frame) {
                count.incrementAndGet();
            }

            @Override
            public void onStreamError(DeviceException error) {}

            @Override
            public void onStreamEnded() {}
        });
        for (long t = 0; t < 10; t++) {
            unlimited.onFrameAvailable(nv21, 32, 24, 0, t);
        }
        assertEquals(10, count.get());
    }

    @Test
    void permissionDeniedThrowsCameraUnavailable() {
        CameraXFaceCaptureDevice device = new CameraXFaceCaptureDevice();
        device.setCameraPermissionGranted(false);

        DeviceException e = assertThrows(DeviceException.class, device::open);
        assertEquals(DeviceException.Kind.CAMERA_UNAVAILABLE, e.getKind());
        assertEquals(DeviceStatus.ERROR, device.status());
    }

    @Test
    void corruptFrameInjectionTruncatesData() throws Exception {
        CameraXFaceCaptureDevice device = new CameraXFaceCaptureDevice();
        device.open();

        AtomicReference<FaceFrame> delivered = new AtomicReference<>();
        device.startStream(new FrameListener() {
            @Override
            public void onFrame(FaceFrame frame) {
                delivered.set(frame);
            }

            @Override
            public void onStreamError(DeviceException error) {}

            @Override
            public void onStreamEnded() {}
        });

        device.injectCorruptFrames(1);
        byte[] valid = new byte[32 * 24 * 3 / 2];
        device.onFrameAvailable(valid, 32, 24, 90, 500L);

        FaceFrame frame = delivered.get();
        assertNotNull(frame);
        assertFalse(frame.isWellFormed(), "Injected frame must be malformed");
        assertEquals(90, frame.getMetadata().get("rotationDegrees"), "a malformed frame is passed on unrotated");

        device.close();
    }
}
