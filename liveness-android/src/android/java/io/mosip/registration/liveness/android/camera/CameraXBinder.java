package io.mosip.registration.liveness.android.camera;

import android.content.Context;
import android.util.Size;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects the phone's camera to {@link CameraXFaceCaptureDevice}: a CameraX {@code Preview} for the screen and an
 * {@code ImageAnalysis} whose YUV frames are converted to NV21 and handed to the device. Both use 4:3, so the
 * normalised capture oval lines up with the preview. The camera runs while bound; the device passes frames on only
 * while a liveness session streams, at most at its analysis rate.
 */
public final class CameraXBinder {

    private static final Logger LOG = Logger.getLogger(CameraXBinder.class.getName());
    private static final Size TARGET = new Size(640, 480);

    private final Context context;
    private final CameraXFaceCaptureDevice device;
    private final ExecutorService analysisThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liveness-camerax-analysis");
        t.setDaemon(true);
        return t;
    });
    private ProcessCameraProvider provider;

    public CameraXBinder(Context context, CameraXFaceCaptureDevice device) {
        this.context = context.getApplicationContext();
        this.device = device;
    }

    /**
     * Binds the camera to {@code owner}'s lifecycle (call on the main thread).
     *
     * @param previewView where to show the preview, or null for analysis only
     * @param onError     called on the main thread if the camera cannot be bound (no such camera, in use, ...)
     */
    public void bind(LifecycleOwner owner, PreviewView previewView, Runnable onBound, Consumer<Exception> onError) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                provider = future.get();
                CameraSelector selector = device.getLensFacing() == CameraXFaceCaptureDevice.LensFacing.BACK
                        ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                if (!provider.hasCamera(selector)) {
                    throw new IllegalStateException("This device has no " + device.getLensFacing() + " camera");
                }
                ResolutionSelector resolution = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(TARGET,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();
                analysis.setAnalyzer(analysisThread, this::analyze);
                provider.unbindAll();
                if (previewView != null) {
                    Preview preview = new Preview.Builder().setResolutionSelector(resolution).build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                    provider.bindToLifecycle(owner, selector, preview, analysis);
                } else {
                    provider.bindToLifecycle(owner, selector, analysis);
                }
                onBound.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Camera could not be bound", e);
                onError.accept(e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void analyze(ImageProxy image) {
        try {
            int width = image.getWidth() & ~1;
            int height = image.getHeight() & ~1;
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            // A new array per frame: the session may still hold the previous one while this is converted.
            byte[] nv21 = YuvToNv21Converter.convert(
                    planes[0].getBuffer(), planes[0].getRowStride(),
                    planes[1].getBuffer(), planes[2].getBuffer(),
                    planes[1].getRowStride(), planes[1].getPixelStride(),
                    width, height, new byte[width * height * 3 / 2]);
            device.onFrameAvailable(nv21, width, height, image.getImageInfo().getRotationDegrees(),
                    System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Camera frame skipped", e);
        } finally {
            image.close();
        }
    }

    /** Stops the camera (call on the main thread). */
    public void unbind() {
        if (provider != null) {
            provider.unbindAll();
        }
    }

    /** Unbinds and stops the analysis thread; the binder cannot be used afterwards. */
    public void close() {
        unbind();
        analysisThread.shutdown();
    }
}
