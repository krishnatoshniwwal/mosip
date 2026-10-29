package io.mosip.registration.mosip_face_liveness;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import androidx.camera.view.PreviewView;
import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugin.platform.PlatformViewFactory;

/**
 * The live camera preview as a Flutter platform view ({@code mosip_face_liveness/preview}). CameraX's PreviewView
 * mirrors the front camera like a selfie; Flutter draws the capture oval on top. FIT_CENTER with the 4:3 camera
 * output keeps the whole frame visible, so the normalised oval lines up with what the session enforces.
 */
final class CameraPreviewFactory extends PlatformViewFactory {

    private final MosipFaceLivenessPlugin plugin;

    CameraPreviewFactory(MosipFaceLivenessPlugin plugin) {
        super(StandardMessageCodec.INSTANCE);
        this.plugin = plugin;
    }

    @Override
    public PlatformView create(Context context, int viewId, Object args) {
        PreviewView view = new PreviewView(context);
        // TextureView: composes correctly with the Flutter widgets drawn over it.
        view.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        view.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(Color.parseColor("#1D2329"));
        plugin.previewCreated(view);
        return new PlatformView() {
            @Override
            public View getView() {
                return view;
            }

            @Override
            public void dispose() {
                plugin.previewDisposed(view);
            }
        };
    }
}
