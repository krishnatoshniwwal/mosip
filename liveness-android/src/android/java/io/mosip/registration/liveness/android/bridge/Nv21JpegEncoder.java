package io.mosip.registration.liveness.android.bridge;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Encodes the verified frame (upright NV21 from the camera adapter) as the JPEG handed back to Flutter. */
public final class Nv21JpegEncoder implements FlutterLivenessBridge.FrameEncoder {

    private static final int QUALITY = 92;

    @Override
    public byte[] toJpeg(FaceFrame frame) {
        if (frame.getFormat() == PixelFormat.JPEG) {
            return frame.getData();
        }
        byte[] nv21;
        if (frame.getFormat() == PixelFormat.NV21) {
            nv21 = frame.getData();
        } else if (frame.getFormat() == PixelFormat.GRAY8) {
            // Simulated device: the grey plane is the luma; neutral chroma (128) keeps it grey.
            int pixels = frame.getWidth() * frame.getHeight();
            nv21 = new byte[pixels * 3 / 2];
            System.arraycopy(frame.getData(), 0, nv21, 0, pixels);
            Arrays.fill(nv21, pixels, nv21.length, (byte) 128);
        } else {
            throw new IllegalArgumentException("Cannot encode " + frame.getFormat() + " frames");
        }
        YuvImage image = new YuvImage(nv21, ImageFormat.NV21, frame.getWidth(), frame.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (!image.compressToJpeg(new Rect(0, 0, frame.getWidth(), frame.getHeight()), QUALITY, out)) {
            throw new IllegalStateException("JPEG compression failed");
        }
        return out.toByteArray();
    }
}
