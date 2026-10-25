package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.camera.OpenCvCameraDevice;
import io.mosip.registration.liveness.camera.OpenCvLoader;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.tools.DatasetLayout.Presentation;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code record} subcommand: a small window for collecting the PAD evaluation dataset with the webcam.
 * Each press of Record saves one clip of the chosen presentation (live person or an attack) for one
 * pseudonymous subject, in the layout {@code evaluate} reads, and logs it in {@code recordings.csv}.
 * Recording is only enabled after the operator confirms the subject's written consent.
 *
 * <pre>
 * record &lt;dataset-dir&gt; [--device webcam:0] [--seconds 5]
 * </pre>
 */
final class RecordTool {

    private static final int FPS = 15;
    private static final int COUNTDOWN_SECONDS = 3;

    private final DatasetLayout layout;
    private final OpenCvCameraDevice camera;
    private final String deviceSpec;
    private final CaptureArea oval;
    private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clip-saver");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Recording> recording = new AtomicReference<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    private final JFrame frame = new JFrame("Liveness dataset recorder");
    private final Preview preview = new Preview();
    private final JTextField subject = new JTextField("S01", 8);
    private final JComboBox<Presentation> presentation = new JComboBox<>(Presentation.values());
    private final JSpinner seconds;
    private final JCheckBox consent = new JCheckBox("Subject has given written consent for this recording");
    private final JButton record = new JButton("Record");
    private final JTextArea instructions = new JTextArea(5, 30);
    private final JLabel status = new JLabel("Starting camera...");
    private final JLabel counts = new JLabel(" ");

    private volatile int frameWidth;
    private volatile int frameHeight;
    private boolean countingDown;

    /** A clip being written, with when it started and how long it should run. */
    private static final class Recording {
        final ClipWriter writer;
        final String subject;
        final Presentation presentation;
        final long startedAt;
        final long durationMs;
        final LocalDateTime time;

        Recording(ClipWriter writer, String subject, Presentation presentation, long durationMs, LocalDateTime time) {
            this.writer = writer;
            this.subject = subject;
            this.presentation = presentation;
            this.startedAt = System.currentTimeMillis();
            this.durationMs = durationMs;
            this.time = time;
        }
    }

    private RecordTool(DatasetLayout layout, int cameraIndex, String deviceSpec, int defaultSeconds, CaptureArea oval) {
        this.layout = layout;
        this.camera = new OpenCvCameraDevice(cameraIndex, FPS);
        this.deviceSpec = deviceSpec;
        this.oval = oval;
        this.seconds = new JSpinner(new SpinnerNumberModel(defaultSeconds, 2, 30, 1));
    }

    static int run(String[] a) throws Exception {
        if (a.length == 0 || a[0].startsWith("--")) {
            System.err.println("record <dataset-dir> [--device webcam:0] [--seconds 5]");
            return 2;
        }
        String spec = LivenessTools.option(a, "--device", "webcam:0");
        if (!spec.startsWith("webcam:")) {
            System.err.println("record supports webcam:<index> devices only");
            return 2;
        }
        int index = Integer.parseInt(spec.substring("webcam:".length()));
        int secs = Integer.parseInt(LivenessTools.option(a, "--seconds", "5"));
        Path config = Paths.get("config/liveness.properties");
        CaptureArea oval = (Files.isRegularFile(config) ? LivenessConfig.load(config) : LivenessConfig.defaults())
                .policyFor(LivenessWorkflow.RESIDENT_REGISTRATION).getCaptureArea();

        OpenCvLoader.ensureLoaded();
        Path root = Paths.get(a[0]);
        Files.createDirectories(root);
        RecordTool tool = new RecordTool(new DatasetLayout(root), index, spec, secs, oval);
        SwingUtilities.invokeLater(tool::show);
        tool.closed.await();
        return 0;
    }

    private void show() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.add(new JLabel("Subject ID:"));
        top.add(subject);
        top.add(new JLabel("Presentation:"));
        top.add(presentation);
        top.add(new JLabel("Seconds:"));
        top.add(seconds);
        top.add(record);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 12));
        side.setPreferredSize(new Dimension(340, 360));
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        instructions.setFont(status.getFont());
        JLabel privacy = new JLabel("<html><b>Privacy:</b> use pseudonymous IDs (S01, S02...), never names. "
                + "Clips are biometric data: keep the dataset off git and shared drives, and delete it when "
                + "the evaluation is done.</html>");
        for (JComponent c : new JComponent[] {consent, instructions, status, counts, privacy}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            side.add(c);
            side.add(Box.createVerticalStrut(10));
        }
        status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(preview, BorderLayout.CENTER);
        frame.add(side, BorderLayout.EAST);
        JLabel location = new JLabel("  Dataset: " + layout.root());
        location.setBorder(BorderFactory.createEmptyBorder(0, 4, 8, 4));
        frame.add(location, BorderLayout.SOUTH);

        presentation.addActionListener(e -> updateInstructions());
        subject.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { subjectChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { subjectChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { subjectChanged(); }
        });
        consent.addActionListener(e -> updateControls());
        record.addActionListener(e -> startCountdown());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                shutdown();
            }
        });

        updateInstructions();
        updateCounts();
        updateControls();
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        startCamera();
    }

    private void startCamera() {
        try {
            camera.open();
            camera.startStream(new FrameListener() {
                @Override
                public void onFrame(FaceFrame f) {
                    onCameraFrame(f);
                }

                @Override
                public void onStreamError(DeviceException error) {
                    SwingUtilities.invokeLater(() -> fail("Camera error: " + error.getMessage()));
                }

                @Override
                public void onStreamEnded() {
                }
            });
            setStatus("Camera ready", Color.DARK_GRAY);
        } catch (DeviceException e) {
            fail("Camera not available: " + e.getMessage());
        }
    }

    /** Camera thread: preview, and append to the clip being recorded. */
    private void onCameraFrame(FaceFrame f) {
        if (f.getFormat() != PixelFormat.BGR24) {
            return;
        }
        frameWidth = f.getWidth();
        frameHeight = f.getHeight();
        preview.show(f);
        Recording r = recording.get();
        if (r == null) {
            return;
        }
        r.writer.write(f.getData(), f.getWidth(), f.getHeight());
        long elapsed = System.currentTimeMillis() - r.startedAt;
        if (elapsed >= r.durationMs && recording.compareAndSet(r, null)) {
            saver.execute(() -> save(r, elapsed));
        } else {
            long left = (r.durationMs - elapsed + 999) / 1000;
            SwingUtilities.invokeLater(() -> setStatus("Recording... " + left + " s", new Color(0xC0392B)));
        }
    }

    private void startCountdown() {
        String id = subject.getText().trim();
        if (frameWidth == 0) {
            fail("Camera not ready yet");
            return;
        }
        countingDown = true;
        updateControls();
        int[] left = {COUNTDOWN_SECONDS};
        setStatus("Recording starts in " + left[0] + "...", new Color(0xB86E00));
        Timer timer = new Timer(1000, null);
        timer.addActionListener(e -> {
            if (--left[0] > 0) {
                setStatus("Recording starts in " + left[0] + "...", new Color(0xB86E00));
                return;
            }
            timer.stop();
            beginRecording(id);
        });
        timer.start();
    }

    private void beginRecording(String id) {
        Presentation p = (Presentation) presentation.getSelectedItem();
        LocalDateTime now = LocalDateTime.now();
        try {
            ClipWriter writer = new ClipWriter(layout.newClipPath(p, id, now), layout.incompleteDir(),
                    frameWidth, frameHeight, FPS);
            recording.set(new Recording(writer, id, p, (Integer) seconds.getValue() * 1000L, now));
        } catch (Exception e) {
            countingDown = false;
            updateControls();
            fail("Could not start recording: " + e.getMessage());
        }
    }

    /** Saver thread: finish the clip and log it. */
    private void save(Recording r, long elapsedMs) {
        try {
            int frames = r.writer.frames();
            Path clip = r.writer.finish();
            layout.log(clip, r.subject, r.presentation, frames, frameWidth, frameHeight, FPS, elapsedMs / 1000.0,
                    deviceSpec, r.time);
            String saved = "Saved " + layout.root().relativize(clip) + " (" + frames + " frames)";
            SwingUtilities.invokeLater(() -> {
                setStatus(saved, new Color(0x1E8E4E));
                finishedRecording();
            });
        } catch (Exception e) {
            r.writer.close();
            SwingUtilities.invokeLater(() -> {
                fail("Saving failed: " + e.getMessage());
                finishedRecording();
            });
        }
    }

    private void finishedRecording() {
        countingDown = false;
        updateCounts();
        updateControls();
    }

    private void subjectChanged() {
        // Consent is given per person: a new subject ID needs a new confirmation.
        consent.setSelected(false);
        updateControls();
    }

    private void updateControls() {
        boolean idle = !countingDown;
        boolean validId = DatasetLayout.isValidSubjectId(subject.getText().trim());
        record.setEnabled(idle && validId && consent.isSelected());
        subject.setEnabled(idle);
        presentation.setEnabled(idle);
        seconds.setEnabled(idle);
        consent.setEnabled(idle);
        if (idle && !validId) {
            setStatus("Subject ID: letters, digits, - and _ only (max 32)", new Color(0xC0392B));
        }
    }

    private void updateInstructions() {
        instructions.setText(((Presentation) presentation.getSelectedItem()).instructions);
    }

    private void updateCounts() {
        StringBuilder sb = new StringBuilder("<html><b>Clips in dataset</b><br>");
        for (Presentation p : Presentation.values()) {
            int n;
            try {
                n = layout.count(p);
            } catch (Exception e) {
                n = -1;
            }
            sb.append(p.label).append(": ").append(n < 0 ? "?" : Integer.toString(n)).append("<br>");
        }
        counts.setText(sb.append("</html>").toString());
    }

    private void setStatus(String text, Color color) {
        status.setText("<html>" + text + "</html>");
        status.setForeground(color);
    }

    private void fail(String text) {
        setStatus(text, new Color(0xC0392B));
    }

    private void shutdown() {
        Recording r = recording.getAndSet(null);
        if (r != null) {
            r.writer.close();
        }
        camera.stopStream();
        camera.close();
        saver.shutdown();
        closed.countDown();
    }

    /** Mirrored live preview with the same capture oval as the capture screen. */
    private final class Preview extends JPanel {
        private volatile BufferedImage image;

        Preview() {
            setPreferredSize(new Dimension(640, 480));
            setBackground(new Color(0x1D2329));
        }

        void show(FaceFrame f) {
            BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(f.getData(), 0, dst, 0, Math.min(dst.length, f.getData().length));
            image = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            BufferedImage img = image;
            if (img == null) {
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight());
            int w = (int) (img.getWidth() * scale);
            int h = (int) (img.getHeight() * scale);
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;
            g.drawImage(img, x + w, y, -w, h, null); // mirrored, like a selfie camera
            if (oval.isEnabled()) {
                double cx = x + (1 - oval.getCenterX()) * w;
                double cy = y + oval.getCenterY() * h;
                double rx = oval.getRadiusX() * w;
                double ry = oval.getRadiusY() * h;
                g.setColor(recording.get() != null ? new Color(0xE74C3C) : new Color(0xDDE3E8));
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                        new float[] {10f, 8f}, 0f));
                g.draw(new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry));
            }
            g.dispose();
        }
    }
}
