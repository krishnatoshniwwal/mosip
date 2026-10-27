package io.mosip.registration.liveness.regclient.ui;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.session.LivenessState;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.UserMessage;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The face capture screen body shared by resident capture, operator login and supervisor authentication: live
 * preview (mirrored, selfie-style) with the capture oval the session enforces, the current instruction, challenge
 * step and countdown, attempts, guidance after a failed attempt, and a note that the check runs on this device.
 * Model scores and PAD details are never shown here.
 *
 * <p>{@link #offerFrame} may be called from any thread; every other method on the FX thread.
 */
public final class LivenessCaptureView extends HBox {

    private static final Color BACKGROUND = Color.web("#1D2329");
    private static final Color GUIDE_NEUTRAL = Color.web("#DDE3E8");
    private static final Color GUIDE_ACTIVE = Color.web("#F5A623");
    private static final Color GUIDE_SUCCESS = Color.web("#2ECC71");
    private static final Color GUIDE_ERROR = Color.web("#E74C3C");
    private static final Color TEXT = Color.web("#1F2933");
    private static final Color MUTED = Color.web("#52606D");
    private static final Color TEXT_ACTIVE = Color.web("#B86E00");
    private static final Color TEXT_SUCCESS = Color.web("#1E8E4E");
    private static final Color TEXT_ERROR = Color.web("#C0392B");

    private final Canvas canvas = new Canvas(480, 360);
    private final Label message = new Label();
    private final Label guidance = new Label();
    private final Label step = new Label();
    private final Label countdown = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label attempt = new Label();
    private final VBox tips = new VBox(4);
    private final Label onDevice = new Label();
    private final Label connectivity = new Label();
    private final VBox status = new VBox(8);

    private final AtomicReference<Image> latest = new AtomicReference<>();
    private final AtomicBoolean drawScheduled = new AtomicBoolean();

    // FX-thread state.
    private LivenessMessages messages = LivenessMessages.english();
    private Image image;
    private double frameAspect = 4.0 / 3.0;
    private CaptureArea area = LivenessPolicy.builder().build().getCaptureArea();
    private LivenessState state = LivenessState.NOT_STARTED;
    private ChallengeType challenge;
    private boolean capturing;
    private boolean previewOn = true;
    /** Null until the client reports connectivity: the badge stays hidden. */
    private Boolean online;

    public LivenessCaptureView() {
        super(16);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_LEFT);

        message.setWrapText(true);
        message.setFont(Font.font(null, FontWeight.BOLD, 20));
        message.setTextFill(TEXT);
        guidance.setWrapText(true);
        guidance.setFont(Font.font(14));
        guidance.setTextFill(MUTED);
        for (Label l : new Label[]{step, countdown, attempt}) {
            l.setTextFill(MUTED);
        }
        progress.setMaxWidth(Double.MAX_VALUE);
        onDevice.setWrapText(true);
        onDevice.setTextFill(MUTED);
        onDevice.setFont(Font.font(11));
        onDevice.setStyle("-fx-border-color: #CBD2D9; -fx-border-radius: 10; -fx-padding: 2 8 2 8;");
        connectivity.setWrapText(true);
        connectivity.setFont(Font.font(11));
        connectivity.setManaged(false);
        connectivity.setVisible(false);

        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        status.getChildren().addAll(message, guidance, step, countdown, progress, attempt, tips, spacer, connectivity, onDevice);
        status.setPrefWidth(300);
        status.setMinWidth(260);
        status.setMaxHeight(Double.MAX_VALUE);
        status.setFillWidth(true);

        getChildren().addAll(canvas, status);
        setMessages(messages);
        redraw();
    }

    /** Language for every text; Arabic lays the status column out right-to-left. The preview is never flipped. */
    public void setMessages(LivenessMessages m) {
        messages = m;
        status.setNodeOrientation(m.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        onDevice.setText(m.ui(LivenessMessages.UI_ON_DEVICE));
        showConnectivity();
    }

    /**
     * Whether the client is online ({@code null}: unknown, badge hidden). Only informs the operator: liveness
     * runs the same checks either way, and offline the badge says so.
     */
    public void setOnline(Boolean isOnline) {
        online = isOnline;
        showConnectivity();
    }

    private void showConnectivity() {
        boolean known = online != null;
        connectivity.setVisible(known);
        connectivity.setManaged(known);
        if (known) {
            connectivity.setText("● " + messages.ui(online ? LivenessMessages.UI_ONLINE : LivenessMessages.UI_OFFLINE));
            connectivity.setTextFill(online ? TEXT_SUCCESS : MUTED);
        }
    }

    /** The oval to draw; must be the capture area of the policy the running session uses. */
    public void setCaptureArea(CaptureArea captureArea) {
        area = captureArea;
        redraw();
    }

    /** Latest frame wins: the FX thread draws at most one pending frame, so a fast device cannot flood it. */
    public void offerFrame(FaceFrame frame) {
        Image img = FxFrames.toImage(frame);
        if (img == null) {
            return;
        }
        latest.set(img);
        if (drawScheduled.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                drawScheduled.set(false);
                Image next = latest.getAndSet(null);
                if (next != null && previewOn) {
                    image = next;
                    frameAspect = next.getWidth() / next.getHeight();
                    redraw();
                }
            });
        }
    }

    public void render(LivenessUpdate u) {
        capturing = false;
        state = u.getState();
        challenge = u.getCurrentChallenge();
        message.setText(messages.text(u.getMessage()));
        message.setTextFill(textColor(state));
        guidance.setText(u.getGuidance() == null ? "" : messages.text(u.getGuidance()));
        boolean inChallenge = state == LivenessState.ACTIVE_CHALLENGE || state == LivenessState.CHALLENGE_FEEDBACK;
        step.setText(inChallenge ? messages.ui(LivenessMessages.UI_CHALLENGE, u.getChallengeNumber(), u.getChallengeCount()) : "");
        countdown.setText(u.getChallengeRemainingMs() >= 0
                ? messages.ui(LivenessMessages.UI_TIME_LEFT, (u.getChallengeRemainingMs() + 999) / 1000) : "");
        attempt.setText(state.isTerminal() ? "" : messages.ui(LivenessMessages.UI_ATTEMPT, u.getAttempt(), u.getMaxAttempts()));
        progress.setProgress(u.getProgress());
        if (state == LivenessState.ATTEMPT_FAILED) {
            showTips(u.getTips(), u.isRetryAvailable());
        } else if (!state.isTerminal()) {
            showTips(Collections.emptyList(), false);
        }
        // After PASSED the stream stays on while the device capture is watched; it stops in showOutcome.
        if (state == LivenessState.FAILED || state == LivenessState.CANCELLED) {
            previewOn = false;
            image = null;
        }
        redraw();
    }

    /** Liveness passed; the device capture is running. */
    public void showCapturing() {
        capturing = true;
        message.setText(messages.text(UserMessage.FEEDBACK_HOLD_STILL));
        message.setTextFill(TEXT_SUCCESS);
        guidance.setText("");
        step.setText("");
        countdown.setText("");
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        showTips(Collections.emptyList(), false);
        redraw();
    }

    /** The final outcome; {@code retryAvailable} adds the "press Retry" hint under the tips. */
    public void showOutcome(UserMessage outcome, List<UserMessage> outcomeTips, boolean success, boolean retryAvailable) {
        capturing = false;
        previewOn = false;
        image = null;
        state = success ? LivenessState.PASSED : LivenessState.FAILED;
        message.setText(messages.text(outcome));
        message.setTextFill(success ? TEXT_SUCCESS : TEXT_ERROR);
        guidance.setText("");
        step.setText("");
        countdown.setText("");
        attempt.setText("");
        progress.setProgress(success ? 1 : 0);
        showTips(outcomeTips, retryAvailable);
        redraw();
    }

    /** Adds a line under the outcome, e.g. how long a lockout lasts. */
    public void showNote(String note) {
        Label l = new Label(note);
        l.setWrapText(true);
        l.setTextFill(TEXT);
        tips.getChildren().add(l);
    }

    /** Ready for a new run: the preview shows frames again. */
    public void reset() {
        previewOn = true;
        capturing = false;
        state = LivenessState.NOT_STARTED;
        challenge = null;
        message.setText("");
        guidance.setText("");
        progress.setProgress(0);
        showTips(Collections.emptyList(), false);
        redraw();
    }

    private void showTips(List<UserMessage> list, boolean pressRetry) {
        tips.getChildren().clear();
        for (UserMessage tip : list) {
            Label l = new Label("• " + messages.text(tip));
            l.setWrapText(true);
            l.setTextFill(TEXT);
            tips.getChildren().add(l);
        }
        if (pressRetry) {
            Label l = new Label(messages.ui(LivenessMessages.UI_PRESS_RETRY));
            l.setWrapText(true);
            l.setTextFill(MUTED);
            tips.getChildren().add(l);
        }
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);

        Image img = image;
        double[] r = fit(img != null ? img.getWidth() / img.getHeight() : frameAspect, w, h);
        if (img != null) {
            // Mirrored, like a mirror: turning left moves the face left on screen.
            g.save();
            g.translate(r[0] + r[2], r[1]);
            g.scale(-1, 1);
            g.drawImage(img, 0, 0, r[2], r[3]);
            g.restore();
        }
        drawGuide(g, r);
    }

    /** Largest rectangle {x, y, w, h} with the image's aspect ratio that fits the canvas, centred. */
    private static double[] fit(double imgAspect, double w, double h) {
        double dw = imgAspect > w / h ? w : h * imgAspect;
        double dh = imgAspect > w / h ? w / imgAspect : h;
        return new double[]{(w - dw) / 2, (h - dh) / 2, dw, dh};
    }

    private void drawGuide(GraphicsContext g, double[] r) {
        CaptureArea a = area;
        if (!a.isEnabled()) {
            return;
        }
        // The capture area is in unmirrored frame coordinates; the preview is mirrored.
        double gw = 2 * a.getRadiusX() * r[2];
        double gh = 2 * a.getRadiusY() * r[3];
        double ox = r[0] + (1 - a.getCenterX()) * r[2];
        double oy = r[1] + a.getCenterY() * r[3];
        g.setStroke(guideColor());
        g.setLineWidth(3);
        g.setLineDashes(10, 8);
        g.strokeOval(ox - gw / 2, oy - gh / 2, gw, gh);
        g.setLineDashes((double[]) null);

        ChallengeType c = challenge;
        if (state == LivenessState.ACTIVE_CHALLENGE && c != null && !capturing) {
            double reach = gw / 2 + 34;
            switch (c) {
                case TURN_LEFT:
                    arrow(g, ox - reach, oy, -1, 0);
                    break;
                case TURN_RIGHT:
                    arrow(g, ox + reach, oy, 1, 0);
                    break;
                case LOOK_UP:
                    arrow(g, ox, oy - gh / 2 - 10, 0, -1);
                    break;
                case LOOK_DOWN:
                    arrow(g, ox, oy + gh / 2 + 10, 0, 1);
                    break;
                default:
                    break;
            }
        }
    }

    private Color guideColor() {
        if (capturing) {
            return GUIDE_SUCCESS;
        }
        switch (state) {
            case ACTIVE_CHALLENGE:
                return GUIDE_ACTIVE;
            case CHALLENGE_FEEDBACK:
            case HOLD_STILL:
            case PASSED:
                return GUIDE_SUCCESS;
            case ATTEMPT_FAILED:
            case FAILED:
                return GUIDE_ERROR;
            default:
                return GUIDE_NEUTRAL;
        }
    }

    private static void arrow(GraphicsContext g, double x, double y, int dx, int dy) {
        double len = 18;
        g.setFill(GUIDE_ACTIVE);
        g.fillPolygon(
                new double[]{x + dx * len, x - dx * len + dy * len, x - dx * len - dy * len},
                new double[]{y + dy * len, y - dy * len + dx * len, y - dy * len - dx * len}, 3);
    }

    private static Color textColor(LivenessState s) {
        switch (s) {
            case ACTIVE_CHALLENGE:
                return TEXT_ACTIVE;
            case CHALLENGE_FEEDBACK:
            case HOLD_STILL:
            case PASSED:
                return TEXT_SUCCESS;
            case ATTEMPT_FAILED:
            case FAILED:
                return TEXT_ERROR;
            default:
                return TEXT;
        }
    }
}
