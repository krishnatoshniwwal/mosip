package io.mosip.registration.liveness.regclient.ui;

import io.mosip.registration.liveness.device.FaceFrame;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayInputStream;

/** Converts device frames to JavaFX images for the preview. Safe to call off the FX thread. */
public final class FxFrames {

    private FxFrames() {
    }

    /** @return the image, or null for a format the preview does not show (NV21 is Android-only) or a bad frame */
    public static Image toImage(FaceFrame frame) {
        if (frame == null || !frame.isWellFormed()) {
            return null;
        }
        int w = frame.getWidth();
        int h = frame.getHeight();
        byte[] data = frame.getData();
        switch (frame.getFormat()) {
            case JPEG: {
                Image img = new Image(new ByteArrayInputStream(data));
                return img.isError() ? null : img;
            }
            case RGB24:
                return rgb(w, h, data);
            case BGR24: {
                byte[] rgb = new byte[w * h * 3];
                for (int i = 0; i < rgb.length; i += 3) {
                    rgb[i] = data[i + 2];
                    rgb[i + 1] = data[i + 1];
                    rgb[i + 2] = data[i];
                }
                return rgb(w, h, rgb);
            }
            case GRAY8: {
                byte[] rgb = new byte[w * h * 3];
                for (int i = 0, j = 0; i < w * h; i++, j += 3) {
                    rgb[j] = data[i];
                    rgb[j + 1] = data[i];
                    rgb[j + 2] = data[i];
                }
                return rgb(w, h, rgb);
            }
            default:
                return null;
        }
    }

    private static Image rgb(int w, int h, byte[] rgb) {
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getByteRgbInstance(), rgb, 0, w * 3);
        return img;
    }
}
