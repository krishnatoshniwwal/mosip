package io.mosip.registration.liveness.demo;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.FaceRegion;
import io.mosip.registration.liveness.i18n.LivenessMessages;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import io.mosip.registration.liveness.mock.SimulatedSubject.ResponseMode;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.service.LivenessHandle;
import io.mosip.registration.liveness.session.CameraConsent;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessState;
import io.mosip.registration.liveness.session.LivenessUpdate;
import io.mosip.registration.liveness.session.UserMessage;

import javax.swing.AbstractAction;
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
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * Reference face capture / authentication screen. Runs the real liveness service against the mock
 * device and simulated engine, so every UI state (passive check, challenges, PAD failure, retry,
 * retry limit, device errors) can be shown without hardware.
 *
 * <p>Usage: {@code java -cp core:demo io.mosip.registration.liveness.demo.LivenessDemoApp [liveness.properties]}
 */
public final class LivenessDemoApp {

    private static final Color TEXT = new Color(0x1F2933);
    private static final Color MUTED = new Color(0x52606D);
    private static final Color ACTIVE = new Color(0xB86E00);
    private static final Color SUCCESS = new Color(0x1E8E4E);
    private static final Color ERROR = new Color(0xC0392B);

    private enum Scenario {
        LIVE("Live person", Presentation.LIVE, 85),
        LIVE_LOW("Live person, low passive score", Presentation.LIVE, 58),
        PHOTO("Attack: printed photo", Presentation.PRINTED_PHOTO, 0),
        SCREEN("Attack: photo on a screen", Presentation.SCREEN_DISPLAY, 0),
        REPLAY("Attack: video replay (evades passive PAD)", Presentation.VIDEO_REPLAY, 0),
        NOBODY("Nobody in front of the camera", Presentation.NO_FACE, 0),
        TWO("Two people in view", Presentation.TWO_FACES, 85),
        OFF_CENTER("Live person, beside the oval", 0.82f, 0.48f, 1f, Collections.emptySet()),
        FAR("Live person, too far from the camera", 0.5f, 0.48f, 0.45f, Collections.emptySet()),
        MASK("Live person wearing a mask", 0.5f, 0.48f, 1f, EnumSet.of(FaceRegion.LOWER_FACE)),
        SUNGLASSES("Live person wearing sunglasses", 0.5f, 0.48f, 1f, EnumSet.of(FaceRegion.EYES));

        final String label;
        final Presentation presentation;
        final float livenessLevel;
        final float x;
        final float y;
        final float scale;
        final Set<FaceRegion> occlusion;

        Scenario(String label, Presentation presentation, float livenessLevel) {
            this(label, presentation, livenessLevel, 0.5f, 0.48f, 1f, Collections.emptySet());
        }

        Scenario(String label, float x, float y, float scale, Set<FaceRegion> occlusion) {
            this(label, Presentation.LIVE, 85, x, y, scale, occlusion);
        }

        Scenario(String label, Presentation presentation, float livenessLevel, float x, float y, float scale,
                 Set<FaceRegion> occlusion) {
            this.label = label;
            this.presentation = presentation;
            this.livenessLevel = livenessLevel;
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.occlusion = occlusion;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final LivenessConfig config;
    private final String configSource;
    private final SimulatedSubject subject = new SimulatedSubject(System::currentTimeMillis, new Random());
    private final MockFaceDevice device = new MockFaceDevice(subject, 15, DeviceLevel.L1);
    private final FaceLivenessService service;
    private LivenessHandle handle;

    private final JFrame frame = new JFrame("Registration Client - Face Liveness (reference UI)");
    private final FacePreviewPanel preview = new FacePreviewPanel();
    private final JComboBox<LivenessWorkflow> workflow = new JComboBox<>(LivenessWorkflow.values());
    private final JButton start = new JButton("Start");
    private final JButton retry = new JButton("Retry");
    private final JButton cancel = new JButton("Cancel");
    private final JCheckBox diagnosticMode = new JCheckBox("Diagnostic mode");
    private final JComboBox<String> language = new JComboBox<>(LivenessMessages.LANGUAGES.toArray(new String[0]));
    private LivenessMessages messages = LivenessMessages.english();
    private final JLabel message = new JLabel("Press Start to capture the face");
    private final JLabel guidance = new JLabel(" ");
    private final JLabel step = new JLabel(" ");
    private final JLabel countdown = new JLabel(" ");
    private final JLabel attempt = new JLabel(" ");
    private final JLabel tips = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea auditLog = new JTextArea();
    private final JLabel metrics = new JLabel(" ");
    private final JPanel diagnostics = new JPanel(new BorderLayout(0, 6));

    private LivenessDemoApp(LivenessConfig config, String configSource) {
        this.config = config;
        this.configSource = configSource;
        AuditSink audit = event -> SwingUtilities.invokeLater(() -> appendAudit(event));
        this.service = new FaceLivenessService(config, new SimulatedLivenessEngine(), audit);
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
        SwingUtilities.invokeLater(() -> new LivenessDemoApp(config, source).show());
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
                BorderFactory.createEmptyBorder(0, 0, 8, 12), BorderFactory.createTitledBorder("Diagnostics (audit trail)")));
        auditLog.setEditable(false);
        auditLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        auditLog.setLineWrap(true);
        auditLog.setWrapStyleWord(true);
        auditLog.append("Configuration: " + configSource + "\n");
        JScrollPane auditScroll = new JScrollPane(auditLog);
        auditScroll.setPreferredSize(new Dimension(440, 300));
        diagnostics.add(auditScroll, BorderLayout.CENTER);
        metrics.setFont(metrics.getFont().deriveFont(11f));
        diagnostics.add(metrics, BorderLayout.SOUTH);
        diagnostics.setVisible(false);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);
        frame.add(diagnostics, BorderLayout.EAST);
        frame.add(simulatorPanel(), BorderLayout.SOUTH);

        start.addActionListener(e -> startSession());
        retry.addActionListener(e -> {
            retry.setEnabled(false);
            handle.retry();
        });
        cancel.addActionListener(e -> handle.cancel());
        applyLanguage();
        diagnosticMode.addActionListener(e -> {
            diagnostics.setVisible(diagnosticMode.isSelected());
            frame.pack();
        });
        workflow.addActionListener(e -> appendLine("Policy for " + workflow.getSelectedItem() + ": "
                + config.policyFor((LivenessWorkflow) workflow.getSelectedItem()).summary()));
        bindKeys(frame.getRootPane());

        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (handle != null) {
                    handle.cancel();
                }
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
        for (JLabel c : new JLabel[] {message, guidance, step, countdown, attempt, tips}) {
            c.setComponentOrientation(orientation);
            c.setHorizontalAlignment(JLabel.LEADING);
        }
    }

    private JComponent statusPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(340, 360));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));

        message.setFont(message.getFont().deriveFont(Font.BOLD, 22f));
        message.setForeground(TEXT);
        guidance.setFont(guidance.getFont().deriveFont(Font.PLAIN, 15f));
        guidance.setForeground(MUTED);
        step.setForeground(MUTED);
        countdown.setForeground(MUTED);
        attempt.setForeground(MUTED);
        tips.setForeground(TEXT);
        progress.setStringPainted(false);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));

        for (JComponent c : new JComponent[] {message, guidance, step, countdown, progress, attempt, tips}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(c);
            panel.add(Box.createVerticalStrut(c == message ? 10 : 6));
        }
        return panel;
    }

    private JComponent simulatorPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12),
                BorderFactory.createTitledBorder("Device simulator (development only - not part of the product UI)")));

        JComboBox<Scenario> scenario = new JComboBox<>(Scenario.values());
        scenario.addActionListener(e -> {
            Scenario s = (Scenario) scenario.getSelectedItem();
            subject.setPresentation(s.presentation);
            subject.setPlacement(s.x, s.y, s.scale);
            subject.setOcclusion(s.occlusion);
            if (s.livenessLevel > 0) {
                subject.setLivenessLevel(s.livenessLevel);
            }
        });
        panel.add(new JLabel("In front of camera:"));
        panel.add(scenario);

        JComboBox<String> response = new JComboBox<>(new String[] {
                "Follows prompts", "Ignores prompts", "Does the wrong action", "Manual (use buttons / keys)"});
        response.addActionListener(e -> {
            int i = response.getSelectedIndex();
            subject.setResponseMode(i == 1 || i == 3 ? ResponseMode.IGNORE_PROMPTS
                    : i == 2 ? ResponseMode.WRONG_ACTION : ResponseMode.FOLLOW_PROMPTS);
        });
        panel.add(new JLabel("Subject:"));
        panel.add(response);

        String[][] actions = {{"Blink (B)", "BLINK"}, {"Smile (S)", "SMILE"}, {"Left (←)", "TURN_LEFT"},
                {"Right (→)", "TURN_RIGHT"}, {"Up (↑)", "LOOK_UP"}, {"Down (↓)", "LOOK_DOWN"}};
        for (String[] a : actions) {
            JButton b = new JButton(a[0]);
            b.setFocusable(false);
            b.addActionListener(e -> subject.perform(ChallengeType.valueOf(a[1])));
            panel.add(b);
        }

        JCheckBox connected = new JCheckBox("Device connected", true);
        connected.addActionListener(e -> {
            device.setAvailable(connected.isSelected());
            if (!connected.isSelected()) {
                device.simulateDisconnect();
            }
        });
        panel.add(connected);
        JButton corrupt = new JButton("Send corrupt frames");
        corrupt.addActionListener(e -> device.injectCorruptFrames(8));
        panel.add(corrupt);
        panel.setPreferredSize(new Dimension(820, 110));
        return panel;
    }

    private void bindKeys(JComponent root) {
        Object[][] keys = {{"B", ChallengeType.BLINK}, {"S", ChallengeType.SMILE}, {"LEFT", ChallengeType.TURN_LEFT},
                {"RIGHT", ChallengeType.TURN_RIGHT}, {"UP", ChallengeType.LOOK_UP}, {"DOWN", ChallengeType.LOOK_DOWN}};
        for (Object[] k : keys) {
            ChallengeType type = (ChallengeType) k[1];
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke((String) k[0]), type);
            root.getActionMap().put(type, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    subject.perform(type);
                }
            });
        }
    }

    private void startSession() {
        LivenessWorkflow selected = (LivenessWorkflow) workflow.getSelectedItem();
        appendLine("---- " + label(selected) + " ----");
        start.setEnabled(false);
        workflow.setEnabled(false);
        cancel.setEnabled(true);
        tips.setText(" ");
        preview.setCaptureArea(config.policyFor(selected).getCaptureArea());
        CameraConsent consent = askCameraConsent();
        appendLine("Camera permission: " + (consent.isGranted() ? "allowed" : "not allowed - camera stays off"));
        handle = service.start(selected, consent, device, subject.respondingTo(new LivenessListener() {
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
        }));
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
    }

    private void finish(LivenessResult r) {
        start.setEnabled(true);
        workflow.setEnabled(true);
        retry.setEnabled(false);
        cancel.setEnabled(false);
        tips.setText(r.getLockedUntil() > 0 ? lockedUntilText(r.getLockedUntil()) : " ");
        appendLine("RESULT " + r.getOutcome()
                + (r.getFailureReason() != null ? " reason=" + r.getFailureReason() : "")
                + (r.getRequiredAction() != null ? " requiredAction=" + r.getRequiredAction() : "")
                + " attempts=" + r.getAttempts() + " active=" + r.isActiveVerificationPerformed()
                + " challenges=" + r.getChallengesCompleted() + " duration=" + r.getDurationMs() + "ms");
        metrics.setText("<html><body style='width:400px'>" + service.metrics().snapshot() + "</body></html>");
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
                return "Resident registration - face capture";
            case OPERATOR_AUTHENTICATION:
                return "Operator login";
            default:
                return "Supervisor authentication";
        }
    }

    private static String html(String text) {
        return "<html>" + text + "</html>";
    }
}
