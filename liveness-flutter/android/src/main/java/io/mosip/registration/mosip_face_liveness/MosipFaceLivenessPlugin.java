package io.mosip.registration.mosip_face_liveness;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.mosip.registration.liveness.android.bridge.FlutterLivenessBridge;
import io.mosip.registration.liveness.android.bridge.Nv21JpegEncoder;
import io.mosip.registration.liveness.android.camera.CameraXBinder;
import io.mosip.registration.liveness.android.camera.CameraXFaceCaptureDevice;
import io.mosip.registration.liveness.android.engine.AndroidLivenessEngines;
import io.mosip.registration.liveness.android.engine.AndroidModelStore;
import io.mosip.registration.liveness.android.engine.AndroidModelUpdater;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.audit.LivenessMetrics;
import io.mosip.registration.liveness.challenge.ChallengeSelector;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.model.ModelSigning;
import io.mosip.registration.liveness.model.ModelSync;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.session.AttemptRateLimiter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Face liveness for the MOSIP Android Registration Client's Flutter layer. Runs the same liveness core and engine
 * as the desktop client; {@link FlutterLivenessBridge} carries sessions over the channels.
 *
 * <ul>
 *   <li><b>{@value #CHANNEL}</b> (methods): {@code initialize}, {@code labels}, {@code hasCameraPermission},
 *       {@code requestCameraPermission}, {@code start}, {@code retry}, {@code cancel}, {@code getStatus},
 *       {@code metrics}, {@code syncModels}, {@code simulate}, {@code dispose}</li>
 *   <li><b>{@value #EVENTS}</b>: session updates, the final result, audit events (no biometric data), model sync
 *       results and camera errors, each a map with an {@code eventType}</li>
 *   <li><b>{@value #PREVIEW_VIEW}</b>: platform view with the live camera preview</li>
 * </ul>
 */
public class MosipFaceLivenessPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler,
        EventChannel.StreamHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {

    static final String CHANNEL = "mosip_face_liveness";
    static final String EVENTS = "mosip_face_liveness/events";
    static final String PREVIEW_VIEW = "mosip_face_liveness/preview";

    private static final Logger LOG = Logger.getLogger(MosipFaceLivenessPlugin.class.getName());
    private static final int CAMERA_PERMISSION_REQUEST = 0x4D46;
    private static final long DEFAULT_SYNC_INTERVAL_MINUTES = 360;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liveness-engine-loader");
        t.setDaemon(true);
        return t;
    });

    private Context context;
    private MethodChannel methods;
    private EventChannel events;
    private ActivityPluginBinding activityBinding;
    private EventChannel.EventSink sink;
    private MethodChannel.Result pendingPermission;

    // Main-thread state, set by initialize.
    private LivenessEngine engine;
    private FaceLivenessService service;
    private AndroidModelUpdater updater;
    private FlutterLivenessBridge bridge;
    private CameraXFaceCaptureDevice camera;
    private SimulatedSubject subject;
    private CameraXBinder binder;
    private PreviewView preview;

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        methods = new MethodChannel(binding.getBinaryMessenger(), CHANNEL);
        methods.setMethodCallHandler(this);
        events = new EventChannel(binding.getBinaryMessenger(), EVENTS);
        events.setStreamHandler(this);
        binding.getPlatformViewRegistry().registerViewFactory(PREVIEW_VIEW, new CameraPreviewFactory(this));
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        methods.setMethodCallHandler(null);
        events.setStreamHandler(null);
        release();
        loader.shutdown();
    }

    // ---- methods ----

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        switch (call.method) {
            case "initialize":
                initialize(call, result);
                break;
            case "labels": {
                LivenessMessages messages = LivenessMessages.forLanguage(call.argument("language"));
                Map<String, Object> labels = new HashMap<>(FlutterLivenessBridge.uiLabels(messages));
                labels.put("rtl", messages.isRightToLeft());
                labels.put("language", messages.language());
                labels.put("consentNoticeVersion", LivenessMessages.CONSENT_NOTICE_VERSION);
                result.success(labels);
                break;
            }
            case "hasCameraPermission":
                result.success(hasCameraPermission());
                break;
            case "requestCameraPermission":
                requestCameraPermission(result);
                break;
            case "start":
                if (bridge == null) {
                    result.error("NOT_INITIALIZED", "Call initialize first", null);
                    return;
                }
                if (camera != null) {
                    camera.setCameraPermissionGranted(hasCameraPermission());
                }
                keepScreenOn(true);
                bridge.onMethodCall("start", call.arguments(), adapt(result));
                break;
            case "retry":
            case "getStatus":
                if (bridge == null) {
                    result.error("NOT_INITIALIZED", "Call initialize first", null);
                    return;
                }
                bridge.onMethodCall(call.method, Collections.emptyMap(), adapt(result));
                break;
            case "cancel":
                keepScreenOn(false);
                if (bridge == null) {
                    result.success(Collections.singletonMap("status", "IDLE"));
                    return;
                }
                bridge.onMethodCall("cancel", Collections.emptyMap(), adapt(result));
                break;
            case "metrics":
                result.success(service == null ? null : metrics(service.metrics().snapshot()));
                break;
            case "syncModels":
                syncModels(result);
                break;
            case "simulate":
                simulate(call, result);
                break;
            case "dispose":
                release();
                result.success(null);
                break;
            default:
                result.notImplemented();
        }
    }

    /**
     * Arguments: {@code mode} ("camera" or "simulated"), {@code config} (the client's
     * {@code mosip.registration.liveness.*} settings as strings; defaults when absent), {@code trustedKeys} (PEM
     * public keys: run only on a signed model package), {@code lensFacing} ("FRONT" or "BACK"),
     * {@code maxAnalysisFps}, {@code modelUpdateUrl} (an https update server publishing signed packages; needs
     * {@code trustedKeys}) and {@code modelSyncIntervalMinutes} (default 360).
     */
    private void initialize(MethodCall call, MethodChannel.Result result) {
        if (bridge != null) {
            result.error("ALREADY_INITIALIZED", "Liveness is already initialised; call dispose first", null);
            return;
        }
        boolean simulated = "simulated".equals(call.argument("mode"));
        Map<String, String> settings = call.argument("config");
        List<String> trustedKeys = call.argument("trustedKeys");
        String lens = call.argument("lensFacing");
        Integer fps = call.argument("maxAnalysisFps");
        String updateUrl = call.argument("modelUpdateUrl");
        Integer syncMinutes = call.argument("modelSyncIntervalMinutes");
        if (updateUrl != null && (simulated || trustedKeys == null || trustedKeys.isEmpty())) {
            result.error("INVALID_ARGUMENTS", "modelUpdateUrl needs trustedKeys and the camera mode: updates are "
                    + "accepted only as packages signed with a trusted key", null);
            return;
        }
        loader.execute(() -> {
            try {
                LivenessConfig config = settings == null ? LivenessConfig.defaults() : toConfig(settings);
                FaceCaptureDevice device;
                LivenessEngine built;
                SimulatedSubject simSubject = null;
                CameraXFaceCaptureDevice cam = null;
                if (simulated) {
                    simSubject = new SimulatedSubject(System::currentTimeMillis, new Random());
                    simSubject.setReactionDelayMs(500);
                    device = new MockFaceDevice(simSubject, 15, DeviceLevel.L1);
                    built = new SimulatedLivenessEngine();
                } else {
                    CameraXFaceCaptureDevice.LensFacing facing = "BACK".equals(lens)
                            ? CameraXFaceCaptureDevice.LensFacing.BACK : CameraXFaceCaptureDevice.LensFacing.FRONT;
                    cam = new CameraXFaceCaptureDevice("ANDROID-CAM-" + facing, facing,
                            fps == null ? CameraXFaceCaptureDevice.DEFAULT_MAX_ANALYSIS_FPS : fps);
                    device = cam;
                    built = trustedKeys == null || trustedKeys.isEmpty()
                            ? AndroidLivenessEngines.createUnverified(context)
                            : AndroidLivenessEngines.createVerified(context, readKeys(trustedKeys));
                }
                SimulatedSubject finalSubject = simSubject;
                // Lockouts survive app restarts, as on desktop; the file holds hashed keys only.
                Path attempts = context.getNoBackupFilesDir().toPath().resolve("liveness-attempts.dat");
                FaceLivenessService newService = new FaceLivenessService(config, built,
                        event -> audit(event, finalSubject), new LivenessMetrics(), new ChallengeSelector(),
                        System::currentTimeMillis, new AttemptRateLimiter(attempts));
                FlutterLivenessBridge newBridge = new FlutterLivenessBridge(newService, device, config);
                AndroidModelUpdater newUpdater = null;
                if (updateUrl != null) {
                    ModelSync sync = AndroidModelStore.modelSync(context, URI.create(updateUrl), readKeys(trustedKeys));
                    newUpdater = new AndroidModelUpdater(context, sync, this::modelSyncEvent);
                    newUpdater.start(syncMinutes == null ? DEFAULT_SYNC_INTERVAL_MINUTES : syncMinutes);
                }
                newBridge.setFrameEncoder(new Nv21JpegEncoder());
                newBridge.setEventSink(new MainThreadSink());
                Map<String, Object> info = new HashMap<>();
                info.put("mode", simulated ? "simulated" : "camera");
                info.put("engine", built.info().describe());
                List<String> challenges = new ArrayList<>();
                for (ChallengeType c : built.info().getSupportedChallenges()) {
                    challenges.add(c.name());
                }
                info.put("challenges", challenges);
                info.put("modelSync", newUpdater != null);
                CameraXFaceCaptureDevice finalCam = cam;
                AndroidModelUpdater finalUpdater = newUpdater;
                main.post(() -> {
                    engine = built;
                    service = newService;
                    updater = finalUpdater;
                    bridge = newBridge;
                    camera = finalCam;
                    subject = finalSubject;
                    if (preview != null) {
                        bindCamera();
                    }
                    result.success(info);
                });
            } catch (Exception | LinkageError e) {
                LOG.log(Level.SEVERE, "Face liveness could not be initialised", e);
                main.post(() -> result.error("ENGINE_UNAVAILABLE", String.valueOf(e.getMessage()), null));
            }
        });
    }

    private static LivenessConfig toConfig(Map<String, String> settings) {
        Properties props = new Properties();
        props.putAll(settings);
        return LivenessConfig.fromProperties(props);
    }

    private List<PublicKey> readKeys(List<String> pems) throws Exception {
        List<PublicKey> keys = new ArrayList<>();
        Path tmp = Files.createTempFile(context.getCacheDir().toPath(), "trusted", ".pem");
        try {
            for (String pem : pems) {
                Files.write(tmp, pem.getBytes(StandardCharsets.US_ASCII));
                keys.add(ModelSigning.readPublicKey(tmp));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return keys;
    }

    /**
     * Liveness performance on this device since initialize: average analysis time per frame, challenge completion
     * time, retry and failure rates. Counts only, no biometric data.
     */
    private static Map<String, Object> metrics(LivenessMetrics.Snapshot m) {
        Map<String, Object> map = new HashMap<>();
        map.put("sessions", m.sessions);
        map.put("attempts", m.attempts);
        map.put("passed", m.passed);
        map.put("passedPassiveOnly", m.passedPassiveOnly);
        map.put("failed", m.failed);
        map.put("cancelled", m.cancelled);
        map.put("padDetections", m.padDetections);
        map.put("injectionDetections", m.injectionDetections);
        map.put("challengesCompleted", m.challengesCompleted);
        map.put("framesProcessed", m.framesProcessed);
        map.put("framesDropped", m.framesDropped);
        map.put("avgFrameProcessingMs", m.avgFrameProcessingMs);
        map.put("avgChallengeCompletionMs", m.avgChallengeCompletionMs);
        map.put("retryRate", m.retryRate());
        map.put("failureRate", m.failureRate());
        return map;
    }

    /** Checks the update server now (if online); the result also goes out as a {@code modelSync} event. */
    private void syncModels(MethodChannel.Result result) {
        AndroidModelUpdater current = updater;
        if (current == null) {
            result.error("NO_MODEL_SYNC", "Model sync was not configured (modelUpdateUrl)", null);
            return;
        }
        loader.execute(() -> {
            ModelSync.Result r = current.syncIfOnline();
            main.post(() -> result.success(r == null
                    ? Collections.singletonMap("status", "SKIPPED_OFFLINE") : syncMap(r)));
        });
    }

    private void modelSyncEvent(ModelSync.Result r) {
        Map<String, Object> map = syncMap(r);
        map.put("eventType", "modelSync");
        main.post(() -> {
            if (sink != null) {
                sink.success(map);
            }
        });
    }

    private static Map<String, Object> syncMap(ModelSync.Result r) {
        Map<String, Object> map = new HashMap<>();
        map.put("status", r.getStatus().name());
        map.put("detail", r.getDetail());
        if (r.getOffered() != null) {
            map.put("offered", r.getOffered().label());
        }
        return map;
    }

    /** Sends audit events to Flutter for a diagnostics view; in simulated mode the person follows each prompt. */
    private void audit(AuditEvent event, SimulatedSubject simSubject) {
        if (simSubject != null && event.getType() == AuditEventType.CHALLENGE_ISSUED) {
            simSubject.perform(ChallengeType.valueOf(event.getDetails().get("challenge")), 500);
        }
        Map<String, Object> map = new HashMap<>();
        map.put("eventType", "audit");
        map.put("code", event.getType().code());
        map.put("type", event.getType().name());
        map.put("attempt", event.getAttempt());
        map.put("details", new HashMap<>(event.getDetails()));
        main.post(() -> {
            if (sink != null) {
                sink.success(map);
            }
        });
    }

    private void simulate(MethodCall call, MethodChannel.Result result) {
        if (subject == null) {
            result.error("NOT_SIMULATED", "simulate is only available in simulated mode", null);
            return;
        }
        try {
            subject.setPresentation(SimulatedSubject.Presentation.valueOf(call.argument("presentation")));
            result.success(null);
        } catch (IllegalArgumentException | NullPointerException e) {
            result.error("INVALID_PRESENTATION", "Unknown presentation: " + call.argument("presentation"), null);
        }
    }

    private void release() {
        keepScreenOn(false);
        if (bridge != null) {
            bridge.onMethodCall("cancel", Collections.emptyMap(), new FlutterLivenessBridge.MethodResult() {
                @Override public void success(Object r) { }
                @Override public void error(String c, String m, Object d) { }
                @Override public void notImplemented() { }
            });
        }
        if (binder != null) {
            binder.close();
            binder = null;
        }
        if (updater != null) {
            updater.close();
            updater = null;
        }
        if (engine != null) {
            LivenessEngine closing = engine;
            loader.execute(closing::close);
        }
        engine = null;
        service = null;
        bridge = null;
        camera = null;
        subject = null;
    }

    // ---- camera preview and permission ----

    void previewCreated(PreviewView view) {
        preview = view;
        bindCamera();
    }

    void previewDisposed(PreviewView view) {
        if (preview == view) {
            preview = null;
            if (binder != null) {
                binder.close();
                binder = null;
            }
        }
    }

    /** Starts the camera once there is a preview, a camera device, an activity and the camera permission. */
    private void bindCamera() {
        Activity activity = activityBinding == null ? null : activityBinding.getActivity();
        if (camera == null || preview == null || binder != null || !(activity instanceof LifecycleOwner)
                || !hasCameraPermission()) {
            return;
        }
        binder = new CameraXBinder(context, camera);
        binder.bind((LifecycleOwner) activity, preview, () -> { }, e -> {
            if (sink != null) {
                Map<String, Object> error = new HashMap<>();
                error.put("eventType", "cameraError");
                error.put("message", String.valueOf(e.getMessage()));
                sink.success(error);
            }
        });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission(MethodChannel.Result result) {
        if (hasCameraPermission()) {
            result.success(true);
            return;
        }
        Activity activity = activityBinding == null ? null : activityBinding.getActivity();
        if (activity == null) {
            result.error("NO_ACTIVITY", "The camera permission can only be requested from a visible screen", null);
            return;
        }
        if (pendingPermission != null) {
            pendingPermission.success(false);
        }
        pendingPermission = result;
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != CAMERA_PERMISSION_REQUEST || pendingPermission == null) {
            return false;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        pendingPermission.success(granted);
        pendingPermission = null;
        if (granted) {
            bindCamera();
        }
        return true;
    }

    /** The screen stays on while a session runs, so it does not dim in the middle of a challenge. */
    private void keepScreenOn(boolean on) {
        Activity activity = activityBinding == null ? null : activityBinding.getActivity();
        if (activity == null) {
            return;
        }
        if (on) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---- events ----

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        sink = events;
    }

    @Override
    public void onCancel(Object arguments) {
        sink = null;
    }

    /** The bridge calls from liveness worker threads; Flutter channels must be used on the main thread. */
    private final class MainThreadSink implements FlutterLivenessBridge.EventSink {
        @Override
        public void success(Object event) {
            main.post(() -> {
                if (event instanceof Map && "complete".equals(((Map<?, ?>) event).get("eventType"))) {
                    keepScreenOn(false);
                }
                if (sink != null) {
                    sink.success(event);
                }
            });
        }

        @Override
        public void error(String code, String message, Object details) {
            main.post(() -> {
                if (sink != null) {
                    sink.error(code, message, details);
                }
            });
        }

        @Override
        public void endOfStream() {
            main.post(() -> {
                if (sink != null) {
                    sink.endOfStream();
                }
            });
        }
    }

    private static FlutterLivenessBridge.MethodResult adapt(MethodChannel.Result result) {
        return new FlutterLivenessBridge.MethodResult() {
            @Override
            public void success(Object r) {
                result.success(r);
            }

            @Override
            public void error(String code, String message, Object details) {
                result.error(code, message, details);
            }

            @Override
            public void notImplemented() {
                result.notImplemented();
            }
        };
    }

    // ---- activity ----

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        activityBinding = binding;
        binding.addRequestPermissionsResultListener(this);
        bindCamera();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityBinding != null) {
            activityBinding.removeRequestPermissionsResultListener(this);
        }
        keepScreenOn(false);
        activityBinding = null;
        if (binder != null) {
            binder.close();
            binder = null;
        }
    }
}
