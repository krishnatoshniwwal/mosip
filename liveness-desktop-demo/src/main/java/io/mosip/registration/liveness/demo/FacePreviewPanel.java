package io.mosip.registration.liveness.demo;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FaceRegion;
import io.mosip.registration.liveness.mock.SimulatedFrameTruth;
import io.mosip.registration.liveness.mock.SimulatedSubject.Presentation;
import io.mosip.registration.liveness.session.LivenessState;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Live preview with the capture-area guide. Real devices deliver camera pixels; the mock device
 * delivers the simulated subject's state, which is drawn as a schematic face. The preview is
 * mirrored like a selfie camera, so "turn left" moves the face towards the left of the screen.
 * The panel stands for the whole frame, and the guide is the policy's {@link CaptureArea}, so a face
 * drawn outside the oval is exactly one the session ignores.
 */
final class FacePreviewPanel extends JPanel {

    private static final Color BACKGROUND = new Color(0x1D2329);
    private static final Color SKIN = new Color(0xE2B48E);
    private static final Color PHOTO_SKIN = new Color(0xD6B8A0);
    private static final Color HAIR = new Color(0x3B2A20);
    private static final Color FEATURE = new Color(0x2B1D16);
    private static final Color NEUTRAL = new Color(0xDDE3E8);
    private static final Color ACTIVE = new Color(0xF5A623);
    private static final Color SUCCESS = new Color(0x2ECC71);
    private static final Color ERROR = new Color(0xE74C3C);

    private volatile SimulatedFrameTruth truth;
    private volatile LivenessState state = LivenessState.NOT_STARTED;
    private volatile ChallengeType challenge;
    private volatile CaptureArea area = LivenessPolicy.builder().build().getCaptureArea();

    FacePreviewPanel() {
        setPreferredSize(new Dimension(480, 360));
        setMinimumSize(new Dimension(480, 360));
        setBackground(BACKGROUND);
    }

    void showFrame(FaceFrame frame) {
        Object t = frame.getMetadata().get(SimulatedFrameTruth.METADATA_KEY);
        truth = t instanceof SimulatedFrameTruth ? (SimulatedFrameTruth) t : null;
        repaint();
    }

    void setCaptureArea(CaptureArea captureArea) {
        area = captureArea;
        repaint();
    }

    void showState(LivenessState newState, ChallengeType currentChallenge) {
        state = newState;
        challenge = currentChallenge;
        if (newState.isTerminal()) {
            truth = null;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);

        SimulatedFrameTruth t = truth;
        if (t == null) {
            g.setColor(new Color(0x8A96A3));
            g.setFont(getFont().deriveFont(Font.PLAIN, 15f));
            centered(g, state.isTerminal() ? "Camera released" : "Camera idle - press Start", w / 2, h / 2);
        } else {
            drawPresentationMedium(g, t.getPresentation(), w, h);
            boolean flat = t.getPresentation() != Presentation.LIVE && t.getPresentation() != Presentation.TWO_FACES;
            for (FaceObservation face : t.getFaces()) {
                drawFace(g, face, w, h, flat);
            }
        }
        drawGuide(g, w, h);
        g.dispose();
    }

    private void drawGuide(Graphics2D g, int w, int h) {
        Color color;
        switch (state) {
            case ACTIVE_CHALLENGE:
                color = ACTIVE;
                break;
            case CHALLENGE_FEEDBACK:
            case HOLD_STILL:
            case PASSED:
                color = SUCCESS;
                break;
            case ATTEMPT_FAILED:
            case FAILED:
                color = ERROR;
                break;
            default:
                color = NEUTRAL;
        }
        CaptureArea a = area;
        double gw = 2 * a.getRadiusX() * w;
        double gh = 2 * a.getRadiusY() * h;
        double ox = (1 - a.getCenterX()) * w;   // mirrored
        double oy = a.getCenterY() * h;
        g.setColor(color);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {10f, 8f}, 0f));
        g.draw(new Ellipse2D.Double(ox - gw / 2, oy - gh / 2, gw, gh));

        ChallengeType c = challenge;
        if (state == LivenessState.ACTIVE_CHALLENGE && c != null) {
            int cx = (int) ox;
            int cy = (int) oy;
            int reach = (int) (gw / 2 + 34);
            switch (c) {
                case TURN_LEFT:
                    arrow(g, cx - reach, cy, -1, 0);
                    break;
                case TURN_RIGHT:
                    arrow(g, cx + reach, cy, 1, 0);
                    break;
                case LOOK_UP:
                    arrow(g, cx, (int) (cy - gh / 2 - 10), 0, -1);
                    break;
                case LOOK_DOWN:
                    arrow(g, cx, (int) (cy + gh / 2 + 10), 0, 1);
                    break;
                default:
                    break;
            }
        }
    }

    private static void arrow(Graphics2D g, int x, int y, int dx, int dy) {
        int len = 18;
        Polygon p = new Polygon();
        p.addPoint(x + dx * len, y + dy * len);
        p.addPoint(x - dx * len + dy * len, y - dy * len + dx * len);
        p.addPoint(x - dx * len - dy * len, y - dy * len - dx * len);
        g.setColor(ACTIVE);
        g.fillPolygon(p);
    }

    private static void drawPresentationMedium(Graphics2D g, Presentation p, int w, int h) {
        double mw = w * 0.42;
        double mh = h * 0.66;
        double x = w / 2.0 - mw / 2;
        double y = h * 0.48 - mh / 2;
        switch (p) {
            case PRINTED_PHOTO: {
                Graphics2D r = (Graphics2D) g.create();
                r.rotate(Math.toRadians(-3), w / 2.0, h / 2.0);
                r.setColor(new Color(0xF4F1EA));
                r.fill(new RoundRectangle2D.Double(x - 10, y - 10, mw + 20, mh + 20, 4, 4));
                r.setColor(new Color(0x9FB4C7));
                r.fill(new RoundRectangle2D.Double(x, y, mw, mh, 2, 2));
                r.dispose();
                break;
            }
            case SCREEN_DISPLAY:
            case VIDEO_REPLAY: {
                g.setColor(new Color(0x0B0D10));
                g.fill(new RoundRectangle2D.Double(x - 16, y - 22, mw + 32, mh + 44, 28, 28));
                g.setColor(p == Presentation.VIDEO_REPLAY ? new Color(0x55707F) : new Color(0x6F8899));
                g.fill(new RoundRectangle2D.Double(x, y, mw, mh, 6, 6));
                g.setColor(new Color(255, 255, 255, 40));
                g.fill(new Polygon(new int[] {(int) x, (int) (x + mw * 0.45), (int) x},
                        new int[] {(int) y, (int) y, (int) (y + mh * 0.5)}, 3));
                break;
            }
            default:
                break;
        }
    }

    private static void drawFace(Graphics2D g, FaceObservation f, int w, int h, boolean flat) {
        double fw = f.getWidth() * w * (1 - Math.min(Math.abs(f.getYaw()), 45) / 45.0 * 0.15);
        double fh = f.getHeight() * h;
        double cx = (1 - f.getCenterX()) * w;   // mirrored
        double cy = f.getCenterY() * h;
        double dx = -f.getYaw() / 45.0 * fw * 0.35;
        double dy = -f.getPitch() / 45.0 * fh * 0.35;

        g.setColor(HAIR);
        g.fill(new Ellipse2D.Double(cx - fw / 2 - 4, cy - fh / 2 - 8, fw + 8, fh * 0.7));
        g.setColor(flat ? PHOTO_SKIN : SKIN);
        g.fill(new Ellipse2D.Double(cx - fw / 2, cy - fh / 2 + fh * 0.08, fw, fh * 0.92));

        float openness = Float.isNaN(f.eyeOpenness()) ? 0.9f : f.eyeOpenness();
        double eyeY = cy - fh * 0.06 + dy;
        double eyeW = fw * 0.17;
        double eyeH = Math.max(2, fh * 0.09 * openness);
        for (int side = -1; side <= 1; side += 2) {
            double ex = cx + side * fw * 0.2 + dx;
            g.setColor(FEATURE);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int) (ex - eyeW / 2), (int) (eyeY - fh * 0.09), (int) (ex + eyeW / 2), (int) (eyeY - fh * 0.1));
            if (openness > 0.25f) {
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(ex - eyeW / 2, eyeY - eyeH / 2, eyeW, eyeH));
                g.setColor(FEATURE);
                double pupil = Math.min(eyeH, eyeW * 0.45);
                g.fill(new Ellipse2D.Double(ex - pupil / 2 + dx * 0.15, eyeY - pupil / 2, pupil, pupil));
            } else {
                g.drawLine((int) (ex - eyeW / 2), (int) eyeY, (int) (ex + eyeW / 2), (int) eyeY);
            }
        }

        g.setColor(FEATURE);
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double noseX = cx + dx * 1.3;
        g.drawLine((int) noseX, (int) (cy + fh * 0.02 + dy), (int) (noseX - 5), (int) (cy + fh * 0.14 + dy));
        g.drawLine((int) (noseX - 5), (int) (cy + fh * 0.14 + dy), (int) (noseX + 3), (int) (cy + fh * 0.15 + dy));

        float smile = Float.isNaN(f.getSmile()) ? 0.1f : f.getSmile();
        double mouthY = cy + fh * 0.27 + dy;
        double mouthW = fw * 0.34 * (1 + smile * 0.25);
        double mx = cx + dx;
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        QuadCurve2D mouth = new QuadCurve2D.Double(mx - mouthW / 2, mouthY - smile * fh * 0.04,
                mx, mouthY + smile * fh * 0.14, mx + mouthW / 2, mouthY - smile * fh * 0.04);
        if (smile > 0.5f) {
            g.setColor(Color.WHITE);
            g.fill(mouth);
            g.setColor(FEATURE);
        }
        g.draw(mouth);

        if (f.getOccludedRegions().contains(FaceRegion.LOWER_FACE)) {
            g.setColor(new Color(0x9CC9E6));
            g.fill(new RoundRectangle2D.Double(cx - fw * 0.42 + dx, cy + fh * 0.04 + dy, fw * 0.84, fh * 0.38,
                    fw * 0.3, fw * 0.3));
            g.setColor(new Color(0x7BAFCF));
            g.setStroke(new BasicStroke(1.5f));
            for (int i = 1; i <= 3; i++) {
                double y = cy + fh * (0.04 + 0.095 * i) + dy;
                g.drawLine((int) (cx - fw * 0.36 + dx), (int) y, (int) (cx + fw * 0.36 + dx), (int) y);
            }
        }
        if (f.getOccludedRegions().contains(FaceRegion.EYES)) {
            g.setColor(new Color(0x111417));
            for (int side = -1; side <= 1; side += 2) {
                double ex = cx + side * fw * 0.2 + dx;
                g.fill(new RoundRectangle2D.Double(ex - fw * 0.14, eyeY - fh * 0.075, fw * 0.28, fh * 0.15,
                        fw * 0.08, fw * 0.08));
            }
            g.setStroke(new BasicStroke(3f));
            g.drawLine((int) (cx - fw * 0.06 + dx), (int) eyeY, (int) (cx + fw * 0.06 + dx), (int) eyeY);
        }
    }

    private static void centered(Graphics2D g, String text, int x, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x - fm.stringWidth(text) / 2, y + fm.getAscent() / 2);
    }
}
