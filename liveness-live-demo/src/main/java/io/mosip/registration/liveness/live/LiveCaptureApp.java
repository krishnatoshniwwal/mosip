package io.mosip.registration.liveness.live;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.audit.AuditEvent;
import io.mosip.registration.liveness.audit.AuditSink;
import io.mosip.registration.liveness.camera.OpenCvCameraDevice;
import io.mosip.registration.liveness.camera.OpenCvLoader;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;
import io.mosip.registration.liveness.service.FaceLivenessService;
import io.mosip.registration.liveness.service.LivenessHandle;
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
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    private final JFrame frame = new JFrame("MOSIP Face Liveness - LIVE Webcam Capture");
    private final CameraPreviewPanel preview = new CameraPreviewPanel();
    private final JComboBox<LivenessWorkflow> workflow = new JComboBox<>(LivenessWorkflow.values());
    private final JButton start = new JButton("Start Live Capture");
    private final JButton retry = new JButton("Retry");
    private final JButton cancel = new JButton("Cancel");
    private final JCheckBox diagnosticMode = new JCheckBox("Diagnostics");
    private final JLabel message = new JLabel("Position face in oval and press Start");
    private final JLabel guidance = new JLabel(" ");
    private final JLabel step = new JLabel(" ");
    private final JLabel countdown = new JLabel(" ");
    private final JLabel attempt = new JLabel(" ");
    private final JLabel tips = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea auditLog = new JTextArea();
    private final JLabel metrics = new JLabel(" ");
    private final JPanel diagnostics = new JPanel(new BorderLayout(0, 6));

    public LiveCaptureApp(LivenessConfig config, String configSource, int cameraIndex) {
        this.config = config;
        this.configSource = configSource;

        OpenCvLoader.ensureLoaded();
        this.device = new OpenCvCameraDevice(cameraIndex, 15);
        this.engine = new OnnxLivenessEngine(Paths.get("models"));

        AuditSink audit = event -> SwingUtilities.invokeLater(() -> appendAudit(event));
        this.service = new FaceLivenessService(config, engine, audit);
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
        int camIndex = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        SwingUtilities.invokeLater(() -> new LiveCaptureApp(config, source, camIndex).show());
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
        diagnostics.setVisible(false);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);
        frame.add(diagnostics, BorderLayout.EAST);

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

        handle = service.start(selected, device, new LivenessListener() {
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

    private void render(LivenessUpdate u) {
        LivenessState state = u.getState();
        message.setText(html(u.getMessage().defaultText()));
        message.setForeground(colorFor(state));
        guidance.setText(u.getGuidance() == null ? " " : u.getGuidance().defaultText());
        boolean inChallenge = state == LivenessState.ACTIVE_CHALLENGE || state == LivenessState.CHALLENGE_FEEDBACK;
        step.setText(inChallenge ? "Challenge " + u.getChallengeNumber() + " of " + u.getChallengeCount() : " ");
        countdown.setText(u.getChallengeRemainingMs() >= 0
                ? "Time left: " + (u.getChallengeRemainingMs() + 999) / 1000 + " s" : " ");
        attempt.setText(state.isTerminal() ? " " : "Attempt " + u.getAttempt() + " of " + u.getMaxAttempts());
        progress.setValue((int) Math.round(u.getProgress() * 100));

        if (state == LivenessState.ATTEMPT_FAILED) {
            StringBuilder sb = new StringBuilder("<html>");
            for (UserMessage tip : u.getTips()) {
                sb.append("• ").append(tip.defaultText()).append("<br>");
            }
            tips.setText(sb.append("<br>Press <b>Retry</b> to try again.</html>").toString());
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

        appendLine("RESULT: " + r.getOutcome()
                + " | Liveness Score: " + (Float.isNaN(r.getPassiveScore()) ? "N/A" : String.format("%.1f", r.getPassiveScore()))
                + (r.getFailureReason() != null ? " | Reason: " + r.getFailureReason() : "")
                + " | Attempts: " + r.getAttempts()
                + " | Challenges: " + r.getChallengesCompleted()
                + " | Duration: " + r.getDurationMs() + "ms");

        metrics.setText("<html><body style='width:380px'>" + service.metrics().snapshot() + "</body></html>");
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
