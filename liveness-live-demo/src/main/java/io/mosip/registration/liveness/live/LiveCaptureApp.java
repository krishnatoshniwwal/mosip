package io.mosip.registration.liveness.live;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.camera.DeviceSelector;
import io.mosip.registration.liveness.camera.SystemCameraSourceCheck;
import io.mosip.registration.liveness.camera.OpenCvLoader;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.LivenessEngineException;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.model.ModelIntegrityException;
import io.mosip.registration.liveness.model.ModelPackageVerifier;
import io.mosip.registration.liveness.model.ModelRepository;
import io.mosip.registration.liveness.model.ModelSigning;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.service.LivenessHandle;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessState;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.UserMessage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;

/**
 * Live Face Liveness & Presentation Attack Detection Desktop Application.
 * Uses real webcam video stream via OpenCV and on-device liveness/PAD evaluation.
 */
public final class LiveCaptureApp {

    private static final Color TEXT = new Color(0x1F2933);
    private static final Color MUTED = new Color(0x52606D);
    private static final Color ACTIVE = new Color(0xB86E00);
    private static final Color SUCCESS = new Color(0x1E8E4E);
    private static final Color ERROR = new Color(0xC0392B);

    private final LivenessConfig config;
    private final String configSource;
    private final FaceCaptureDevice device;
    private final OnnxLivenessEngine engine;
    private final FaceLivenessService service;
    private LivenessHandle handle;

    private final JFrame frame = new JFrame("MOSIP Face Liveness - Live Capture");
    private final CameraPreviewPanel preview = new CameraPreviewPanel();
    private final JComboBox<LivenessWorkflow> workflow = new JComboBox<>(LivenessWorkflow.values());
    private final JButton start = new JButton("Start Live Capture");
    private final JButton retry = new JButton("Retry");
    private final JButton cancel = new JButton("Cancel");
    private final JCheckBox diagnosticMode = new JCheckBox("Diagnostics");
    private final JComboBox<String> language = new JComboBox<>(LivenessMessages.LANGUAGES.toArray(new String[0]));
    private LivenessMessages messages = LivenessMessages.english();
    private final JLabel message = new JLabel("Position face in oval and press Start");
    private final JLabel guidance = new JLabel(" ");
    private final JLabel step = new JLabel(" ");
    private final JLabel countdown = new JLabel(" ");
    private final JLabel attempt = new JLabel(" ");
    private final JLabel tips = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea auditLog = new JTextArea();
    private final JLabel metrics = new JLabel(" ");
    private final JTextArea engineReadout = new JTextArea(4, 40);
    private final JPanel diagnostics = new JPanel(new BorderLayout(0, 6));

    /**
     * @param deviceSpec {@code webcam:<index>}, {@code sbi:auto} or {@code sbi:<host>:<port>[:<subId>]} (see {@link DeviceSelector})
     * @throws LivenessEngineException if the liveness models cannot be loaded
     * @throws DeviceException         if the SBI face device cannot be found or is not ready
     */
    public LiveCaptureApp(LivenessConfig config, String configSource, String deviceSpec)
            throws LivenessEngineException, DeviceException {
        this.config = config;
        this.configSource = configSource;

        OpenCvLoader.ensureLoaded();
        this.engine = createEngine();
        this.device = DeviceSelector.create(deviceSpec, 15);

        AuditSink audit = event -> SwingUtilities.invokeLater(() -> appendAudit(event));
        this.service = new FaceLivenessService(config, engine, audit);
        // Refuses a station with a software camera (deepfake injection) before the webcam is opened.
        SystemCameraSourceCheck sourceCheck = new SystemCameraSourceCheck();
        sourceCheck.prefetch();
        this.service.setCameraSourceCheck(sourceCheck);
    }

    /**
     * With {@code -Dmosip.liveness.modelRepo=<dir> -Dmosip.liveness.trustedKey=<public.pem>} the engine runs
     * only on a verified, signed model package; otherwise it loads the development {@code models/} folder.
     */
    private static OnnxLivenessEngine createEngine() throws LivenessEngineException {
        String repo = System.getProperty("mosip.liveness.modelRepo");
        if (repo == null) {
            return new OnnxLivenessEngine(OnnxLivenessEngine.defaultModelDir());
        }
        try {
            ModelPackageVerifier verifier = new ModelPackageVerifier(Collections.singletonList(
                    ModelSigning.readPublicKey(Paths.get(System.getProperty("mosip.liveness.trustedKey", "trusted-model-key.pem")))),
                    OnnxLivenessEngine.ENGINE_VERSION);
            return new OnnxLivenessEngine(new ModelRepository(Paths.get(repo), verifier).activate());
        } catch (ModelIntegrityException | IOException | GeneralSecurityException e) {
            throw new LivenessEngineException("Liveness model package could not be verified: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        Path path = Paths.get(args.length > 0 ? args[0] : "config/liveness.properties");
        LivenessConfig config;
        String source;
        if (Files.isRegularFile(path)) {
            config = LivenessConfig.load(path);
            source = path.toAbsolutePath().toString();
        } else {
            config = LivenessConfig.defaults();
            source = "built-in defaults";
        }
        // Second argument: a device spec, or a bare webcam index as before.
        String argSpec = args.length > 1 ? (args[1].matches("\\d+") ? "webcam:" + args[1] : args[1])
                : DeviceSelector.DEFAULT_SPEC;
        String deviceSpec = DeviceSelector.specFromProperty(argSpec);
        SwingUtilities.invokeLater(() -> {
            try {
                new LiveCaptureApp(config, source, deviceSpec).show();
            } catch (LivenessEngineException e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Face liveness engine unavailable",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            } catch (DeviceException | IllegalArgumentException e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Face capture device unavailable",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    private void show() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.add(new JLabel("Workflow:"));
        workflow.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                return super.getListCellRendererComponent(list, label((LivenessWorkflow) value), index, selected, focus);
            }
        });
        top.add(workflow);
        top.add(start);
        top.add(retry);
        top.add(cancel);
        top.add(Box.createHorizontalStrut(16));
        top.add(new JLabel("Language:"));
        language.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                String name = LivenessMessages.forLanguage((String) value).displayName();
                return super.getListCellRendererComponent(list, name, index, selected, focus);
            }
        });
        language.addActionListener(e -> applyLanguage());
        top.add(language);
        top.add(diagnosticMode);
        retry.setEnabled(false);
        cancel.setEnabled(false);

        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        center.add(preview, BorderLayout.CENTER);
        center.add(statusPanel(), BorderLayout.EAST);

        diagnostics.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 8, 12), BorderFactory.createTitledBorder("Diagnostics (Live Audit Trail)")));
        auditLog.setEditable(false);
        auditLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        auditLog.setLineWrap(true);
        auditLog.setWrapStyleWord(true);
        auditLog.append("Live Engine: " + engine.info().describe() + "\n");
        auditLog.append("Camera Device: " + device.info().describe() + "\n");
        auditLog.append("Configuration: " + configSource + "\n");
        JScrollPane auditScroll = new JScrollPane(auditLog);
        auditScroll.setPreferredSize(new Dimension(420, 300));
        diagnostics.add(auditScroll, BorderLayout.CENTER);
        metrics.setFont(metrics.getFont().deriveFont(11f));
        diagnostics.add(metrics, BorderLayout.SOUTH);
        engineReadout.setEditable(false);
        engineReadout.setLineWrap(true);
        engineReadout.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        engineReadout.setBorder(BorderFactory.createTitledBorder("Engine readout (last frame)"));
        diagnostics.add(engineReadout, BorderLayout.NORTH);
        diagnostics.setVisible(false);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);
        frame.add(diagnostics, BorderLayout.EAST);

        applyLanguage();
        preview.setCaptureArea(config.policyFor((LivenessWorkflow) workflow.getSelectedItem()).getCaptureArea());
        workflow.addActionListener(e -> preview.setCaptureArea(
                config.policyFor((LivenessWorkflow) workflow.getSelectedItem()).getCaptureArea()));
        start.addActionListener(e -> startSession());
        retry.addActionListener(e -> {
            retry.setEnabled(false);
            if (handle != null) {
                handle.retry();
            }
        });
        cancel.addActionListener(e -> {
            if (handle != null) {
                handle.cancel();
            }
        });
        diagnosticMode.addActionListener(e -> {
            diagnostics.setVisible(diagnosticMode.isSelected());
            frame.pack();
        });

        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (handle != null) {
                    handle.cancel();
                }
                device.close();
                engine.close();
                System.exit(0);
            }
        });
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void applyLanguage() {
        messages = LivenessMessages.forLanguage((String) language.getSelectedItem());
        start.setText(messages.ui(LivenessMessages.UI_START));
        retry.setText(messages.ui(LivenessMessages.UI_RETRY));
        cancel.setText(messages.ui(LivenessMessages.UI_CANCEL));
        ComponentOrientation orientation = messages.isRightToLeft()
                ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT;
        for (JLabel c : new JLabel[]{message, guidance, step, countdown, attempt, tips}) {
            c.setComponentOrientation(orientation);
            c.setHorizontalAlignment(JLabel.LEADING);
        }
    }

    private JComponent statusPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(340, 360));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));

        message.setFont(message.getFont().deriveFont(Font.BOLD, 20f));
        message.setForeground(TEXT);
        guidance.setFont(guidance.getFont().deriveFont(Font.PLAIN, 14f));
        guidance.setForeground(MUTED);
        step.setForeground(MUTED);
        countdown.setForeground(MUTED);
        attempt.setForeground(MUTED);
        tips.setForeground(TEXT);
        progress.setStringPainted(false);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));

        for (JComponent c : new JComponent[]{message, guidance, step, countdown, progress, attempt, tips}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(c);
            panel.add(Box.createVerticalStrut(c == message ? 10 : 6));
        }
        return panel;
    }

    private void startSession() {
        LivenessWorkflow selected = (LivenessWorkflow) workflow.getSelectedItem();
        appendLine("---- Starting Live Capture: " + label(selected) + " ----");
        start.setEnabled(false);
        workflow.setEnabled(false);
        cancel.setEnabled(true);
        tips.setText(" ");
        preview.setCaptureArea(config.policyFor(selected).getCaptureArea());
        CameraConsent consent = askCameraConsent();
        appendLine("Camera permission: " + (consent.isGranted() ? "allowed" : "not allowed - camera stays off"));

        handle = service.start(selected, consent, device, new LivenessListener() {
            @Override
            public void onUpdate(LivenessUpdate update) {
                SwingUtilities.invokeLater(() -> render(update));
            }

            @Override
            public void onComplete(LivenessResult result) {
                SwingUtilities.invokeLater(() -> finish(result));
            }

            @Override
            public void onPreviewFrame(FaceFrame f) {
                preview.showFrame(f);
            }
        });
    }

    /**
     * Asks before the camera is turned on, on every Start. Closing the dialog counts as "Don't allow", and
     * "Don't allow" is the default button, so consent is always an explicit choice.
     */
    private CameraConsent askCameraConsent() {
        Object[] options = {messages.ui(LivenessMessages.UI_ALLOW), messages.ui(LivenessMessages.UI_DENY)};
        JOptionPane pane = new JOptionPane("<html><body style='width:360px'>"
                + messages.ui(LivenessMessages.UI_CONSENT_TEXT) + "</body></html>",
                JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[1]);
        pane.setComponentOrientation(messages.isRightToLeft()
                ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
        JDialog dialog = pane.createDialog(frame, messages.ui(LivenessMessages.UI_CONSENT_TITLE));
        dialog.setVisible(true);
        dialog.dispose();
        boolean granted = options[0].equals(pane.getValue());
        long now = System.currentTimeMillis();
        return granted
                ? CameraConsent.granted(LivenessMessages.CONSENT_NOTICE_VERSION, messages.language(), now)
                : CameraConsent.declined(LivenessMessages.CONSENT_NOTICE_VERSION, messages.language(), now);
    }

    private void render(LivenessUpdate u) {
        LivenessState state = u.getState();
        message.setText(html(messages.text(u.getMessage())));
        message.setForeground(colorFor(state));
        guidance.setText(u.getGuidance() == null ? " " : messages.text(u.getGuidance()));
        boolean inChallenge = state == LivenessState.ACTIVE_CHALLENGE || state == LivenessState.CHALLENGE_FEEDBACK;
        step.setText(inChallenge
                ? messages.ui(LivenessMessages.UI_CHALLENGE, u.getChallengeNumber(), u.getChallengeCount()) : " ");
        countdown.setText(u.getChallengeRemainingMs() >= 0
                ? messages.ui(LivenessMessages.UI_TIME_LEFT, (u.getChallengeRemainingMs() + 999) / 1000) : " ");
        attempt.setText(state.isTerminal() ? " "
                : messages.ui(LivenessMessages.UI_ATTEMPT, u.getAttempt(), u.getMaxAttempts()));
        progress.setValue((int) Math.round(u.getProgress() * 100));

        if (state == LivenessState.ATTEMPT_FAILED) {
            StringBuilder sb = new StringBuilder("<html>");
            for (UserMessage tip : u.getTips()) {
                sb.append("• ").append(messages.text(tip)).append("<br>");
            }
            tips.setText(sb.append("<br>").append(messages.ui(LivenessMessages.UI_PRESS_RETRY)).append("</html>").toString());
        } else if (!state.isTerminal()) {
            tips.setText(" ");
        }

        retry.setEnabled(u.isRetryAvailable());
        preview.showState(state, u.getCurrentChallenge());
        if (diagnosticMode.isSelected()) {
            engineReadout.setText(engine.lastDiagnostics());
        }
    }

    private void finish(LivenessResult r) {
        start.setEnabled(true);
        workflow.setEnabled(true);
        retry.setEnabled(false);
        cancel.setEnabled(false);
        tips.setText(r.getLockedUntil() > 0 ? lockedUntilText(r.getLockedUntil()) : " ");
        appendLine("Engine readout at end: " + engine.lastDiagnostics().replace('\n', ' '));

        appendLine("RESULT: " + r.getOutcome()
                + " | Liveness Score: " + (Float.isNaN(r.getPassiveScore()) ? "N/A" : String.format("%.1f", r.getPassiveScore()))
                + (r.getFailureReason() != null ? " | Reason: " + r.getFailureReason() : "")
                + " | Attempts: " + r.getAttempts()
                + " | Challenges: " + r.getChallengesCompleted()
                + " | Duration: " + r.getDurationMs() + "ms");

        metrics.setText("<html><body style='width:380px'>" + service.metrics().snapshot() + "</body></html>");
    }

    private String lockedUntilText(long until) {
        String time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(messages.locale())
                .format(Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()));
        return messages.ui(LivenessMessages.UI_LOCKED_UNTIL, time);
    }

    private void appendAudit(AuditEvent e) {
        appendLine(e.getType().code() + " " + e.getType() + " #" + e.getAttempt()
                + (e.getDetails().isEmpty() ? "" : " " + e.getDetails()));
    }

    private void appendLine(String line) {
        auditLog.append(line + "\n");
        auditLog.setCaretPosition(auditLog.getDocument().getLength());
    }

    private static Color colorFor(LivenessState state) {
        switch (state) {
            case ACTIVE_CHALLENGE:
                return ACTIVE;
            case CHALLENGE_FEEDBACK:
            case HOLD_STILL:
            case PASSED:
                return SUCCESS;
            case ATTEMPT_FAILED:
            case FAILED:
                return ERROR;
            default:
                return TEXT;
        }
    }

    private static String label(LivenessWorkflow w) {
        switch (w) {
            case RESIDENT_REGISTRATION:
                return "Resident Registration";
            case OPERATOR_AUTHENTICATION:
                return "Operator Login";
            default:
                return "Supervisor Auth";
        }
    }

    private static String html(String text) {
        return "<html>" + text + "</html>";
    }
}
