package io.mosip.registration.liveness.regclient.ui;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.gate.CaptureStep;
import io.mosip.registration.liveness.gate.GateHandle;
import io.mosip.registration.liveness.gate.GateListener;
import io.mosip.registration.liveness.gate.GateResult;
import io.mosip.registration.liveness.gate.GateStatus;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.regclient.RegClientLiveness;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.FailureReason;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.UserMessage;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Modal face capture window for the Registration Client. It asks for camera permission, runs liveness on the
 * device stream, runs the screen's own capture step (MDS rCapture, face authentication) only once liveness has
 * passed, and closes with the result. Resident capture, operator login and supervisor authentication all open it
 * the same way; only the workflow, title and capture step differ.
 *
 * <pre>
 * LivenessCaptureDialog.show(stage, "Face capture", liveness, LivenessWorkflow.RESIDENT_REGISTRATION, rid, device,
 *         () -&gt; bioService.captureModality(request), messages)
 *     .thenAccept(r -&gt; Platform.runLater(() -&gt; { if (r.isAccepted()) save(r.getCapture()); }));
 * </pre>
 */
public final class LivenessCaptureDialog<T> {

    private static final long CLOSE_AFTER_SUCCESS_MS = 1200;

    private final RegClientLiveness liveness;
    private final LivenessWorkflow workflow;
    private final String subjectKey;
    private final FaceCaptureDevice device;
    private final CaptureStep<T> capture;
    private final LivenessMessages messages;
    private final LivenessPolicy policy;
    private final CompletableFuture<GateResult<T>> closed = new CompletableFuture<>();

    private final Stage stage = new Stage();
    private final LivenessCaptureView view = new LivenessCaptureView();
    private final VBox consentPane = new VBox(14);
    private final Button retry = new Button();
    private final Button cancel = new Button();

    // FX-thread state.
    private GateHandle<T> handle;
    private GateResult<T> last;
    private CameraConsent consent;
    private int runs;
    private boolean running;
    private boolean closeRequested;
    private boolean gateRetryAvailable;

    private LivenessCaptureDialog(Window owner, String title, RegClientLiveness liveness, LivenessWorkflow workflow,
                                  String subjectKey, FaceCaptureDevice device, CaptureStep<T> capture,
                                  LivenessMessages messages) {
        this.liveness = liveness;
        this.workflow = workflow;
        this.subjectKey = subjectKey;
        this.device = device;
        this.capture = capture;
        this.messages = messages;
        this.policy = liveness.config().policyFor(workflow);

        view.setMessages(messages);
        view.setCaptureArea(policy.getCaptureArea());
        buildConsentPane();

        retry.setText(messages.ui(LivenessMessages.UI_RETRY));
        retry.setDisable(true);
        retry.setOnAction(e -> onRetry());
        cancel.setText(messages.ui(LivenessMessages.UI_CANCEL));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> onCancelOrClose());
        HBox buttons = new HBox(8, retry, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(0, 12, 12, 12));
        buttons.setNodeOrientation(messages.isRightToLeft()
                ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);

        BorderPane root = new BorderPane(new StackPane(view, consentPane));
        root.setBottom(buttons);
        root.setStyle("-fx-background-color: white;");
        stage.setScene(new Scene(root));
        stage.setTitle(title);
        stage.setResizable(false);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setOnCloseRequest(e -> {
            if (running || consentPane.isVisible()) {
                e.consume();
                onCancelOrClose();
            }
        });
        stage.setOnHidden(e -> closed.complete(last));
    }

    /**
     * Opens the window (call on the FX thread). The future completes when the window closes, with the last result:
     * use the capture only if {@link GateResult#isAccepted()}; otherwise follow the liveness result's required
     * action. Closing at the permission question counts as not allowing the camera, so the result is never null.
     *
     * @param device a device adapter for this capture, e.g. an {@code MdsStreamDevice} over {@code bioService.getStream}
     */
    public static <T> CompletableFuture<GateResult<T>> show(Window owner, String title, RegClientLiveness liveness,
                                                           LivenessWorkflow workflow, String subjectKey,
                                                           FaceCaptureDevice device, CaptureStep<T> capture,
                                                           LivenessMessages messages) {
        LivenessCaptureDialog<T> dialog = new LivenessCaptureDialog<>(owner, title, liveness, workflow, subjectKey,
                device, capture, messages);
        dialog.stage.show();
        dialog.askConsentOrStart();
        return dialog.closed;
    }

    private void buildConsentPane() {
        Label title = new Label(messages.ui(LivenessMessages.UI_CONSENT_TITLE));
        title.setFont(Font.font(null, FontWeight.BOLD, 18));
        Label text = new Label(messages.ui(LivenessMessages.UI_CONSENT_TEXT));
        text.setWrapText(true);
        text.setMaxWidth(460);
        Button allow = new Button(messages.ui(LivenessMessages.UI_ALLOW));
        Button deny = new Button(messages.ui(LivenessMessages.UI_DENY));
        // "Don't allow" is the default: consent must be an explicit choice.
        deny.setDefaultButton(true);
        allow.setOnAction(e -> answerConsent(true));
        deny.setOnAction(e -> answerConsent(false));
        HBox choices = new HBox(8, deny, allow);
        choices.setAlignment(Pos.CENTER_RIGHT);
        consentPane.getChildren().addAll(title, text, choices);
        consentPane.setPadding(new Insets(28));
        consentPane.setMaxSize(520, Region.USE_PREF_SIZE);
        consentPane.setStyle("-fx-background-color: white; -fx-border-color: #CBD2D9; -fx-border-radius: 6;"
                + " -fx-background-radius: 6;");
        consentPane.setNodeOrientation(messages.isRightToLeft()
                ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        consentPane.setVisible(false);
    }

    /** Asks before the camera is turned on, and again on a retry once the answer is older than the policy allows. */
    private void askConsentOrStart() {
        boolean fresh = consent != null && consent.isGranted()
                && System.currentTimeMillis() - consent.getAnsweredAt() <= policy.getConsentMaxAgeMs();
        if (policy.isEnabled() && policy.isConsentRequired() && !fresh) {
            view.setDisable(true);
            consentPane.setVisible(true);
            retry.setDisable(true);
        } else {
            startRun();
        }
    }

    private void answerConsent(boolean granted) {
        long now = System.currentTimeMillis();
        consent = granted
                ? CameraConsent.granted(LivenessMessages.CONSENT_NOTICE_VERSION, messages.language(), now)
                : CameraConsent.declined(LivenessMessages.CONSENT_NOTICE_VERSION, messages.language(), now);
        consentPane.setVisible(false);
        view.setDisable(false);
        // A refusal still goes through the session: it is audited and ends without opening the camera.
        startRun();
    }

    private void startRun() {
        runs++;
        running = true;
        gateRetryAvailable = false;
        retry.setDisable(true);
        cancel.setText(messages.ui(LivenessMessages.UI_CANCEL));
        view.reset();
        handle = liveness.verifyThenCapture(workflow, subjectKey, consent, device, capture, new GateListener<T>() {
            @Override
            public void onUpdate(LivenessUpdate update) {
                Platform.runLater(() -> {
                    view.render(update);
                    retry.setDisable(!update.isRetryAvailable());
                });
            }

            @Override
            public void onPreviewFrame(FaceFrame frame) {
                view.offerFrame(frame);
            }

            @Override
            public void onCaptureStarted() {
                Platform.runLater(() -> {
                    view.showCapturing();
                    retry.setDisable(true);
                });
            }

            @Override
            public void onGateComplete(GateResult<T> result) {
                Platform.runLater(() -> finish(result));
            }
        });
    }

    private void finish(GateResult<T> r) {
        last = r;
        running = false;
        handle = null;
        cancel.setText(messages.ui(LivenessMessages.UI_CLOSE));
        if (closeRequested) {
            stage.close();
            return;
        }
        if (r.isAccepted()) {
            retry.setDisable(true);
            view.showOutcome(UserMessage.SUCCESS, Collections.emptyList(), true, false);
            PauseTransition pause = new PauseTransition(Duration.millis(CLOSE_AFTER_SUCCESS_MS));
            pause.setOnFinished(e -> stage.close());
            pause.play();
            return;
        }
        switch (r.getStatus()) {
            case BINDING_BROKEN:
            case CAPTURE_FAILED:
            case CAPTURE_TIMEOUT:
                // Liveness passed, but the capture could not be tied to it: a new run starts liveness again.
                gateRetryAvailable = runs <= policy.getMaxRetries();
                view.showOutcome(r.getMessage(), r.getStatus() == GateStatus.BINDING_BROKEN
                                ? Arrays.asList(UserMessage.GUIDE_LOOK_AT_CAMERA, UserMessage.GUIDE_FACE_IN_AREA)
                                : Arrays.asList(UserMessage.GUIDE_CHECK_DEVICE),
                        false, gateRetryAvailable);
                if (!gateRetryAvailable) {
                    view.showNote(messages.text(finalMessage(policy.getRetryExhaustedAction())));
                }
                retry.setDisable(!gateRetryAvailable);
                break;
            case LIVENESS_NOT_PASSED:
                // The session's last update already shows the failure and what happens next; add the lockout end.
                retry.setDisable(true);
                LivenessResult lr = r.getLiveness();
                if (lr != null && lr.getLockedUntil() > 0) {
                    String time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(messages.locale())
                            .format(Instant.ofEpochMilli(lr.getLockedUntil()).atZone(ZoneId.systemDefault()));
                    view.showNote(messages.ui(LivenessMessages.UI_LOCKED_UNTIL, time));
                }
                if (lr != null && lr.getFailureReason() == FailureReason.CONSENT_DECLINED) {
                    view.showOutcome(UserMessage.FAIL_CONSENT, Collections.emptyList(), false, false);
                }
                break;
            default:
                view.showOutcome(r.getMessage(), Collections.emptyList(), false, false);
                retry.setDisable(true);
        }
    }

    private void onRetry() {
        retry.setDisable(true);
        if (running && handle != null) {
            handle.retry();
        } else if (gateRetryAvailable) {
            askConsentOrStart();
        }
    }

    private void onCancelOrClose() {
        if (running && handle != null) {
            closeRequested = true;
            cancel.setDisable(true);
            handle.cancel();
        } else if (consentPane.isVisible()) {
            answerConsent(false);
            closeRequested = true;
        } else {
            stage.close();
        }
    }

    static UserMessage finalMessage(FailureAction action) {
        switch (action) {
            case REQUIRE_SUPERVISOR_OVERRIDE:
                return UserMessage.FINAL_SUPERVISOR_OVERRIDE;
            case ALLOW_ALTERNATE_AUTH:
                return UserMessage.FINAL_ALTERNATE_AUTH;
            default:
                return UserMessage.FINAL_BLOCKED;
        }
    }
}
