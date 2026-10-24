package io.mosip.registration.liveness.live;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.session.LivenessState;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A double-buffered panel that displays real camera frames from a {@link FaceFrame}
 * and overlays a face guide oval whose colour tracks the current liveness state.
 *
 * <p>BGR24 frames are converted directly into a {@link BufferedImage}; JPEG frames
 * are decoded via {@link ImageIO}. The image is mirrored horizontally to give a
 * natural selfie-style view.
 *
 * <p>The guide oval is the policy's {@link CaptureArea}, mapped onto the displayed (letterboxed,
 * mirrored) image, so the oval on screen is exactly the region the liveness session enforces.
 */
final class CameraPreviewPanel extends JPanel {

    private static final Logger LOG = Logger.getLogger(CameraPreviewPanel.class.getName());

    private static final Color BACKGROUND = new Color(0x1D2329);
    private static final Color NEUTRAL = new Color(0xDDE3E8);
    private static final Color ACTIVE = new Color(0xF5A623);
    private static final Color SUCCESS = new Color(0x2ECC71);
    private static final Color ERROR = new Color(0xE74C3C);

    private volatile BufferedImage currentImage;
    private volatile LivenessState state = LivenessState.NOT_STARTED;
    private volatile ChallengeType challenge;
    private volatile CaptureArea area = LivenessPolicy.builder().build().getCaptureArea();
    /** Aspect ratio of the last camera frame, used to place the oval before the first frame arrives. */
    private volatile double frameAspect = 4.0 / 3.0;

    CameraPreviewPanel() {
        setPreferredSize(new Dimension(480, 360));
        setMinimumSize(new Dimension(480, 360));
        setBackground(BACKGROUND);
        setDoubleBuffered(true);
    }

    /**
     * Accepts a new camera frame for display. Converts the raw pixel data to
     * a {@link BufferedImage} and schedules a repaint.
     */
    void showFrame(FaceFrame frame) {
        BufferedImage img = toImage(frame);
        if (img != null) {
            currentImage = img;
            frameAspect = (double) img.getWidth() / img.getHeight();
            repaint();
        }
    }

    /** The oval to draw; must be the capture area of the policy the running session uses. */
    void setCaptureArea(CaptureArea captureArea) {
        area = captureArea;
        repaint();
    }

    /**
     * Updates the overlay state so the guide oval and directional arrows
     * reflect the current liveness phase.
     */
    void showState(LivenessState newState, ChallengeType currentChallenge) {
        state = newState;
        challenge = currentChallenge;
        if (newState.isTerminal()) {
            currentImage = null;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int w = getWidth();
        int h = getHeight();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);

        BufferedImage img = currentImage;
        Rectangle2D imageArea = fit(img != null ? (double) img.getWidth() / img.getHeight() : frameAspect, w, h);
        if (img != null) {
            drawMirroredImage(g, img, imageArea);
        } else {
            g.setColor(new Color(0x8A96A3));
            g.setFont(getFont().deriveFont(Font.PLAIN, 15f));
            centered(g, state.isTerminal() ? "Camera released" : "Camera idle \u2013 press Start", w / 2, h / 2);
        }

        drawGuide(g, imageArea);
        g.dispose();
    }

    // ---- image conversion ----

    private BufferedImage toImage(FaceFrame frame) {
        if (frame.getFormat() == PixelFormat.BGR24) {
            BufferedImage img = new BufferedImage(
                    frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            byte[] data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(frame.getData(), 0, data, 0,
                    Math.min(frame.getData().length, data.length));
            return img;
        }
        if (frame.getFormat() == PixelFormat.JPEG) {
            try {
                return ImageIO.read(new ByteArrayInputStream(frame.getData()));
            } catch (IOException e) {
                LOG.log(Level.FINE, "Failed to decode JPEG frame", e);
                return null;
            }
        }
        // Unsupported format; skip rendering this frame.
        return null;
    }

    // ---- mirrored drawing ----

    /** Largest rectangle with the image's aspect ratio that fits the panel, centred (letterboxed). */
    private static Rectangle2D fit(double imgAspect, int panelW, int panelH) {
        double panelAspect = (double) panelW / panelH;
        double drawW;
        double drawH;
        if (imgAspect > panelAspect) {
            drawW = panelW;
            drawH = panelW / imgAspect;
        } else {
            drawH = panelH;
            drawW = panelH * imgAspect;
        }
        return new Rectangle2D.Double((panelW - drawW) / 2, (panelH - drawH) / 2, drawW, drawH);
    }

    /** Draws the camera image into {@code r}, mirrored horizontally for a selfie-style view. */
    private static void drawMirroredImage(Graphics2D g, BufferedImage img, Rectangle2D r) {
        // Mirror horizontally: translate to the right edge, then scale X by -1.
        AffineTransform saved = g.getTransform();
        g.translate(r.getMaxX(), r.getY());
        g.scale(-1.0, 1.0);
        g.drawImage(img, 0, 0, (int) Math.round(r.getWidth()), (int) Math.round(r.getHeight()), null);
        g.setTransform(saved);
    }

    // ---- guide overlay ----

    private void drawGuide(Graphics2D g, Rectangle2D image) {
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

        // Capture area is in unmirrored frame coordinates; the preview is mirrored.
        CaptureArea a = area;
        double gw = 2 * a.getRadiusX() * image.getWidth();
        double gh = 2 * a.getRadiusY() * image.getHeight();
        double ox = image.getX() + (1 - a.getCenterX()) * image.getWidth();
        double oy = image.getY() + a.getCenterY() * image.getHeight();
        g.setColor(color);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{10f, 8f}, 0f));
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
        int[] xPoints = {x + dx * len, x - dx * len + dy * len, x - dx * len - dy * len};
        int[] yPoints = {y + dy * len, y - dy * len + dx * len, y - dy * len - dx * len};
        g.setColor(ACTIVE);
        g.fillPolygon(xPoints, yPoints, 3);
    }

    private static void centered(Graphics2D g, String text, int x, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x - fm.stringWidth(text) / 2, y + fm.getAscent() / 2);
    }
}
