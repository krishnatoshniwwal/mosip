package io.mosip.registration.liveness.regclient.demo;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditEventType;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.camera.DeviceSelector;
import io.mosip.registration.liveness.camera.SystemCameraSourceCheck;
import io.mosip.registration.liveness.camera.OpenCvLoader;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.FailureAction;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.DeviceStatus;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.evidence.EvidenceSigner;
import io.mosip.registration.liveness.evidence.EvidenceVerification;
import io.mosip.registration.liveness.evidence.LivenessEvidenceJws;
import io.mosip.registration.liveness.gate.CaptureStep;
import io.mosip.registration.liveness.gate.GateResult;
import io.mosip.registration.liveness.gate.GateStatus;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import io.mosip.registration.liveness.model.ModelSigning;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;
import io.mosip.registration.liveness.regclient.RegClientAuditSink;
import io.mosip.registration.liveness.regclient.RegClientLiveness;
import io.mosip.registration.liveness.regclient.RegClientLivenessConfig;
import io.mosip.registration.liveness.regclient.ui.FxFrames;
import io.mosip.registration.liveness.regclient.ui.LivenessCaptureDialog;
import io.mosip.registration.liveness.session.LivenessResult;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for the three Registration Client screens that use the face: operator login, resident biometrics
 * and supervisor approval. Each opens {@link LivenessCaptureDialog} exactly as the patched client controllers do
 * (see docs/REGCLIENT_INTEGRATION.md); the client's own capture and face match are simulated here.
 *
 * <p>{@code -Dmosip.liveness.device=webcam:0|sbi:auto|sbi:<host>:<port>} uses a real camera or MDS with the
 * ONNX engine; {@code -Dmosip.liveness.sim=true} uses the mock device and simulated person instead.
 */
public final class RegClientDemoApp extends Application {

    private static final long SIMULATED_RCAPTURE_MS = 900;
    private static final long SIMULATED_MATCH_MS = 600;
    private static final long SWAP_LINGERS_MS = 3000;
    private static final String STATION_ID = "10011";

    private final boolean simulated = Boolean.getBoolean("mosip.liveness.sim");
    private final TextArea diagnosticsLog = new TextArea();
    private final Label metrics = new Label();
    private final ComboBox<String> language = new ComboBox<>();

    private RegClientLiveness liveness;
    private TapDevice device;
    private SimulatedSubject subject;
    private final AtomicBoolean networkOnline = new AtomicBoolean(true);
    private ComboBox<Presentation> presentation;
    private ComboBox<CaptureBehaviour> duringCapture;
    private Stage stage;
    /** Stands in for the station's TPM machine key; the server holds the public half. */
    private KeyPair stationKey;

    /** What the simulated person does while the client's rCapture runs, to show what capture binding catches. */
    private enum CaptureBehaviour {
        STAYS("Stays in front of the camera", null),
        PHOTO("Holds up a printed photo", Presentation.PRINTED_PHOTO),
        LEAVES("Walks away", Presentation.NO_FACE),
        SECOND_PERSON("A second person steps in", Presentation.TWO_FACES);

        final String label;
        final Presentation swap;

        CaptureBehaviour(String label, Presentation swap) {
            this.label = label;
            this.swap = swap;
        }
    }

    @Override
    public void start(Stage primary) {
        stage = primary;
        try {
            setUp(loadConfig());
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
            alert.setHeaderText("Face liveness could not start");
            alert.showAndWait();
            Platform.exit();
            return;
        }

        TabPane screens = new TabPane(
                tab("Operator login", operatorLogin()),
                tab("New registration – biometrics", residentBiometrics()),
                tab("Supervisor approval", supervisorApproval()));

        BorderPane root = new BorderPane(screens);
        root.setTop(toolbar());
        root.setRight(diagnostics());
        primary.setScene(new Scene(root, 1040, 600));
        primary.setTitle("MOSIP Registration Client – face liveness integration");
        primary.setOnHidden(e -> {
            liveness.close();
            device.close();
            Platform.exit();
            System.exit(0);
        });
        primary.show();
    }

    private void setUp(LivenessConfig config) throws Exception {
        AuditSink audit = new RegClientAuditSink(this::auditManagerService);
        AuditSink trail = event -> {
            audit.record(event);
            Platform.runLater(() -> {
                diagnosticsLog.appendText("    " + event.getType().code() + " " + event.getDetails() + "\n");
                metrics.setText(liveness.service().metrics().snapshot().toString());
            });
            respondIfSimulated(event);
        };
        LivenessEngine engine;
        FaceCaptureDevice raw;
        if (simulated) {
            subject = new SimulatedSubject(System::currentTimeMillis, new Random());
            subject.setReactionDelayMs(500);
            raw = new MockFaceDevice(subject, 30, DeviceLevel.L1);
            engine = new SimulatedLivenessEngine();
        } else {
            OpenCvLoader.ensureLoaded();
            engine = new OnnxLivenessEngine(OnnxLivenessEngine.defaultModelDir());
            raw = DeviceSelector.create(DeviceSelector.specFromProperty(DeviceSelector.DEFAULT_SPEC), 15);
        }
        device = new TapDevice(raw);
        liveness = new RegClientLiveness(config, engine, trail, null);
        liveness.setConnectivity(networkOnline::get);
        stationKey = ModelSigning.generateKeyPair();
        liveness.setEvidenceSigner(EvidenceSigner.withKey(stationKey.getPrivate(), stationKey.getPublic()), STATION_ID);
        if (!simulated) {
            SystemCameraSourceCheck sourceCheck = new SystemCameraSourceCheck();
            sourceCheck.prefetch();
            liveness.setCameraSourceCheck(sourceCheck);
        }
        diagnosticsLog.appendText("Engine: " + engine.info().describe() + "\nDevice: " + raw.info().describe() + "\n\n");
    }

    /** Stands in for AuditManagerService.audit(AuditEvent.valueOf(event), Components.valueOf(component), ...). */
    private void auditManagerService(String event, String component, String refId, String refIdType) {
        Platform.runLater(() -> diagnosticsLog.appendText(
                "audit(" + event + ", " + component + ", " + refId.substring(0, 8) + "…, " + refIdType + ")\n"));
    }

    /** Sim mode: the simulated person performs each challenge the way a person reads the prompt and acts on it. */
    private void respondIfSimulated(AuditEvent event) {
        if (subject != null && event.getType() == AuditEventType.CHALLENGE_ISSUED) {
            subject.perform(ChallengeType.valueOf(event.getDetails().get("challenge")), 500);
        }
    }

    /** The bundled file stands in for the synced global parameters (ApplicationContext.map()). */
    private static LivenessConfig loadConfig() throws IOException {
        Properties base = new Properties();
        Path file = Paths.get("config/liveness.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                base.load(in);
            }
        }
        Map<String, Object> applicationMap = Collections.emptyMap();
        return RegClientLivenessConfig.fromApplicationMap(applicationMap, base);
    }

    // ---- screens ----

    private Node operatorLogin() {
        TextField user = new TextField("110011");
        Label status = new Label();
        Button login = new Button("Login with face");
        login.setDefaultButton(true);
        login.setOnAction(e -> verify(LivenessWorkflow.OPERATOR_AUTHENTICATION, "Operator login – face", user.getText(),
                simulatedMatch(), status, r -> "Face matched. Logged in as " + user.getText() + "."));
        return form("Login", "The operator's face is checked for liveness, then matched against the operator's "
                        + "enrolled face (AuthenticationService.authValidator).",
                new Node[]{new Label("Username"), user}, login, status);
    }

    private Node residentBiometrics() {
        String rid = "10001100010002" + String.format("%06d", new Random().nextInt(1_000_000));
        ImageView photo = new ImageView();
        photo.setFitWidth(180);
        photo.setFitHeight(135);
        photo.setPreserveRatio(true);
        StackPane tile = new StackPane(new Label("No face captured"), photo);
        tile.setMinSize(190, 145);
        tile.setStyle("-fx-border-color: #CBD2D9; -fx-background-color: #F5F7FA;");
        Label status = new Label();
        Button scan = new Button("Scan face");
        scan.setDefaultButton(true);
        scan.setOnAction(e -> verify(LivenessWorkflow.RESIDENT_REGISTRATION, "Face capture", rid, simulatedRCapture(),
                status, r -> {
                    Image img = FxFrames.toImage(r.getCapture());
                    photo.setImage(img);
                    return "Face captured for " + rid + " after liveness verification.";
                }));
        return form("Biometrics – face", "The resident's face is checked for liveness before the MDS capture "
                        + "(BioService.captureModality) is accepted into the registration packet.",
                new Node[]{new Label("Registration ID"), new Label(rid), new Label("Face"), tile}, scan, status);
    }

    private Node supervisorApproval() {
        TextField user = new TextField("110012");
        Label status = new Label();
        Button approve = new Button("Authenticate with face");
        approve.setDefaultButton(true);
        approve.setOnAction(e -> verify(LivenessWorkflow.SUPERVISOR_AUTHENTICATION, "Supervisor authentication – face",
                user.getText(), simulatedMatch(), status, r -> "Supervisor " + user.getText() + " verified. Packets approved."));
        return form("Supervisor authentication", "Used for packet approval and end-of-day sign-off. The supervisor policy "
                        + "always asks for an active challenge and allows fewer retries.",
                new Node[]{new Label("Supervisor ID"), user}, approve, status);
    }

    private interface Success<T> {
        String describe(GateResult<T> result);
    }

    private <T> void verify(LivenessWorkflow workflow, String title, String subjectKey, CaptureStep<T> capture,
                            Label status, Success<T> success) {
        diagnosticsLog.appendText("\n---- " + workflow + " (" + subjectKey + ") ----\n");
        status.setText("");
        LivenessCaptureDialog.show(stage, title, liveness, workflow, subjectKey, device, capture,
                        LivenessMessages.forLanguage(language.getValue()))
                .thenAccept(r -> Platform.runLater(() -> {
                    diagnosticsLog.appendText("RESULT " + r + "\n");
                    status.setTextFill(r.isAccepted() ? Color.web("#1E8E4E") : Color.web("#C0392B"));
                    status.setText(r.isAccepted() ? success.describe(r) : notAccepted(r));
                    if (workflow == LivenessWorkflow.RESIDENT_REGISTRATION && r.getStatus() != GateStatus.CANCELLED) {
                        packetEvidence(r, subjectKey);
                    }
                }));
    }

    /**
     * What goes into the registration packet next to the face, and what the server does with it on receipt:
     * verify the station's signature and that the evidence names exactly this face.
     */
    private void packetEvidence(GateResult<?> r, String rid) {
        try {
            byte[] face = r.getCapture() instanceof FaceFrame ? ((FaceFrame) r.getCapture()).getData() : null;
            String jws = liveness.issueEvidence(r, face, rid);
            diagnosticsLog.appendText("PACKET liveness evidence (JWS, " + jws.length() + " chars): "
                    + jws.substring(0, Math.min(48, jws.length())) + "…\n");
            EvidenceVerification server = LivenessEvidenceJws.verify(jws, stationKey.getPublic(), face, rid, Instant.now());
            diagnosticsLog.appendText("SERVER check of the packet: " + server + "\n");
            if (server.isAccepted()) {
                EvidenceVerification swapped = LivenessEvidenceJws.verify(jws, stationKey.getPublic(),
                        "another face".getBytes(StandardCharsets.UTF_8), rid, Instant.now());
                diagnosticsLog.appendText("SERVER check if the face were swapped in the packet: " + swapped + "\n");
            }
        } catch (Exception e) {
            diagnosticsLog.appendText("Evidence could not be issued: " + e + "\n");
        }
    }

    /** What the client would do next. This text is the client's, not shown to the person in the dialog. */
    private static String notAccepted(GateResult<?> r) {
        LivenessResult l = r.getLiveness();
        switch (r.getStatus()) {
            case CANCELLED:
                return "Cancelled.";
            case CAPTURE_FAILED:
            case CAPTURE_TIMEOUT:
                return "The face device did not complete the capture.";
            default:
                FailureAction action = l == null ? null : l.getRequiredAction();
                if (action == null) {
                    return "Face verification was not completed.";
                }
                switch (action) {
                    case REQUIRE_SUPERVISOR_OVERRIDE:
                        return "Not verified: send to the supervisor-approved exception flow.";
                    case ALLOW_ALTERNATE_AUTH:
                        return "Not verified: use password or OTP login instead.";
                    default:
                        return "Not verified: face verification is blocked for this person.";
                }
        }
    }

    // ---- simulated client calls ----

    /** Stands in for BioService.captureModality (MDS rCapture): returns a frame from the device. */
    private CaptureStep<FaceFrame> simulatedRCapture() {
        CaptureBehaviour behaviour = behaviour();
        return () -> {
            beginCapture(behaviour);
            Thread.sleep(SIMULATED_RCAPTURE_MS);
            endCapture(behaviour);
            return device.lastFrame();
        };
    }

    /** Stands in for AuthenticationService.authValidator(userId, "FACE", rCapture). */
    private CaptureStep<Boolean> simulatedMatch() {
        CaptureBehaviour behaviour = behaviour();
        return () -> {
            beginCapture(behaviour);
            Thread.sleep(SIMULATED_MATCH_MS);
            endCapture(behaviour);
            return Boolean.TRUE;
        };
    }

    /** Read on the FX thread, when the button is pressed. */
    private CaptureBehaviour behaviour() {
        return subject == null ? CaptureBehaviour.STAYS : duringCapture.getValue();
    }

    private void beginCapture(CaptureBehaviour b) throws InterruptedException {
        if (b.swap != null) {
            Thread.sleep(200);
            subject.setPresentation(b.swap);
        }
    }

    /** The swap stays in front of the camera a while after the capture, as it would in an attack. */
    private void endCapture(CaptureBehaviour b) {
        if (b.swap != null) {
            Platform.runLater(() -> {
                PauseTransition later = new PauseTransition(Duration.millis(SWAP_LINGERS_MS));
                later.setOnFinished(e -> subject.setPresentation(presentation.getValue()));
                later.play();
            });
        }
    }

    // ---- layout ----

    private Node toolbar() {
        language.getItems().addAll(LivenessMessages.LANGUAGES);
        language.setValue(LivenessMessages.LANGUAGES.get(0));
        language.setConverter(new StringConverter<String>() {
            @Override
            public String toString(String code) {
                return code == null ? "" : LivenessMessages.forLanguage(code).displayName();
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        // Stands in for RegistrationAppHealthCheckUtil.isNetworkAvailable(): untick to see the offline screens.
        CheckBox network = new CheckBox("Network online");
        network.setSelected(networkOnline.get());
        network.setOnAction(e -> networkOnline.set(network.isSelected()));
        HBox bar = new HBox(10, new Label("Language:"), language, network);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: #F5F7FA; -fx-border-color: transparent transparent #CBD2D9 transparent;");
        if (simulated) {
            presentation = new ComboBox<>();
            presentation.getItems().addAll(Presentation.values());
            presentation.setValue(Presentation.LIVE);
            presentation.setOnAction(e -> subject.setPresentation(presentation.getValue()));
            duringCapture = new ComboBox<>();
            duringCapture.getItems().addAll(CaptureBehaviour.values());
            duringCapture.setValue(CaptureBehaviour.STAYS);
            duringCapture.setConverter(new StringConverter<CaptureBehaviour>() {
                @Override
                public String toString(CaptureBehaviour b) {
                    return b == null ? "" : b.label;
                }

                @Override
                public CaptureBehaviour fromString(String s) {
                    return null;
                }
            });
            bar.getChildren().addAll(new Label("   Simulated person:"), presentation,
                    new Label("During capture:"), duringCapture);
        }
        return bar;
    }

    private Node diagnostics() {
        CheckBox show = new CheckBox("Diagnostic mode");
        diagnosticsLog.setEditable(false);
        diagnosticsLog.setWrapText(true);
        diagnosticsLog.setFont(Font.font("Monospaced", 11));
        diagnosticsLog.setPrefWidth(380);
        metrics.setWrapText(true);
        metrics.setFont(Font.font(10));
        VBox body = new VBox(6, new Label("Audit trail (no biometric data)"), diagnosticsLog, metrics);
        VBox.setVgrow(diagnosticsLog, Priority.ALWAYS);
        body.visibleProperty().bind(show.selectedProperty());
        body.managedProperty().bind(show.selectedProperty());
        VBox panel = new VBox(8, show, body);
        panel.setPadding(new Insets(12));
        VBox.setVgrow(body, Priority.ALWAYS);
        return panel;
    }

    private static Tab tab(String title, Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private static Node form(String heading, String note, Node[] fields, Button action, Label status) {
        Label h = new Label(heading);
        h.setFont(Font.font(null, FontWeight.BOLD, 18));
        Label n = new Label(note);
        n.setWrapText(true);
        n.setTextFill(Color.web("#52606D"));
        n.setMaxWidth(520);
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            grid.addRow(i / 2, fields[i], fields[i + 1]);
        }
        status.setWrapText(true);
        status.setMaxWidth(520);
        status.setFont(Font.font(null, FontWeight.BOLD, 13));
        VBox box = new VBox(14, h, n, grid, action, status);
        box.setPadding(new Insets(24));
        return box;
    }

    /** Keeps the last frame, so the simulated rCapture can return a picture of whoever is in front of the device. */
    private static final class TapDevice implements FaceCaptureDevice {
        private final FaceCaptureDevice device;
        private final AtomicReference<FaceFrame> last = new AtomicReference<>();

        TapDevice(FaceCaptureDevice device) {
            this.device = device;
        }

        FaceFrame lastFrame() {
            return last.get();
        }

        @Override
        public DeviceInfo info() {
            return device.info();
        }

        @Override
        public DeviceStatus status() {
            return device.status();
        }

        @Override
        public void open() throws DeviceException {
            device.open();
        }

        @Override
        public void startStream(FrameListener listener) throws DeviceException {
            device.startStream(new FrameListener() {
                @Override
                public void onFrame(FaceFrame frame) {
                    last.set(frame);
                    listener.onFrame(frame);
                }

                @Override
                public void onStreamError(DeviceException error) {
                    listener.onStreamError(error);
                }

                @Override
                public void onStreamEnded() {
                    listener.onStreamEnded();
                }
            });
        }

        @Override
        public void stopStream() {
            device.stopStream();
        }

        @Override
        public void close() {
            device.close();
        }
    }
}
