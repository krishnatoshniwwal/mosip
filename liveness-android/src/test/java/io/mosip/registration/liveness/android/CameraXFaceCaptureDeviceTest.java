package io.mosip.registration.liveness.android;

import io.mosip.registration.liveness.android.camera.CameraXFaceCaptureDevice;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
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
        device.onFrameAvailable(nv21, w, h, 270, 1000L);

        FaceFrame frame = delivered.get();
        assertNotNull(frame);
        assertEquals(PixelFormat.NV21, frame.getFormat());
        assertEquals(w, frame.getWidth());
        assertEquals(h, frame.getHeight());
        assertTrue(frame.isWellFormed());
        assertEquals(270, frame.getMetadata().get("rotationDegrees"));
        assertEquals("FRONT", frame.getMetadata().get("lensFacing"));

        device.simulateDisconnect();
        assertTrue(ended.get());
        assertEquals(DeviceStatus.DISCONNECTED, device.status());

        device.close();
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
        device.onFrameAvailable(valid, 32, 24, 0, 500L);

        FaceFrame frame = delivered.get();
        assertNotNull(frame);
        assertFalse(frame.isWellFormed(), "Injected frame must be malformed");

        device.close();
    }
}
