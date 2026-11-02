package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.LivenessWorkflow;
import io.mosip.registration.liveness.camera.OpenCvLoader;
import io.mosip.registration.liveness.config.CaptureArea;
import io.mosip.registration.liveness.config.LivenessConfig;
import io.mosip.registration.liveness.config.LivenessPolicy;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.evaluation.PadEvaluation;
import io.mosip.registration.liveness.evaluation.PadMetrics;
import io.mosip.registration.liveness.evaluation.ScoredPresentation;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;
import io.mosip.registration.liveness.session.PadAttackWindow;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code evaluate} subcommand: scores every image and video of a labelled dataset with the on-device
 * engine and writes the ISO/IEC 30107-3 report (see {@link PadEvaluation} for the layout).
 *
 * <p>An image is one presentation. A video is one presentation scored like a liveness session: up to
 * {@code --max-video-frames} frames spread over the clip, score = median bona fide score, and flagged as
 * an attack when {@code --min-attack-frames} of any {@code --attack-window} consecutive counted frames are flagged
 * (defaults: the configuration's {@code pad.minAttackFrames} and {@code pad.attackWindow}), as in the app.
 *
 * <p>Only frames the session would judge count: exactly one face, inside the capture oval of the configuration
 * ({@code --config}, default {@code config/liveness.properties}). A frame with two faces (a photo held up with
 * someone behind it) or with the face beside the oval gets guidance in the app, never a verdict, so here it is
 * not scored either. Fewer than three counted frames is a non-response, which is never an acceptance.
 * {@code --capture-area off} counts any single face, for datasets of cropped face images.
 */
final class PadEvaluationTool {

    private PadEvaluationTool() {
    }

    static int run(String[] a) throws Exception {
        if (a.length == 0 || a[0].startsWith("--")) {
            System.err.println("evaluate <dataset> [--out DIR] [--config FILE] [--passive-threshold N] [--max-video-frames 60] "
                    + "[--min-attack-frames N] [--attack-window N] [--capture-area on|off]");
            return 2;
        }
        Path dataset = Paths.get(a[0]);
        Path out = Paths.get(LivenessTools.option(a, "--out", "pad-evaluation"));
        Path configFile = Paths.get(LivenessTools.option(a, "--config", "config/liveness.properties"));
        LivenessPolicy policy = (Files.isRegularFile(configFile) ? LivenessConfig.load(configFile)
                : LivenessConfig.defaults()).policyFor(LivenessWorkflow.RESIDENT_REGISTRATION);
        double threshold = Double.parseDouble(LivenessTools.option(a, "--passive-threshold",
                Integer.toString(policy.getPassiveThreshold())));
        int maxVideoFrames = Integer.parseInt(LivenessTools.option(a, "--max-video-frames", "60"));
        int minAttackFrames = Integer.parseInt(LivenessTools.option(a, "--min-attack-frames",
                Integer.toString(policy.getPadMinAttackFrames())));
        int attackWindow = Integer.parseInt(LivenessTools.option(a, "--attack-window",
                Integer.toString(policy.getPadAttackWindow())));
        boolean useArea = !"off".equalsIgnoreCase(LivenessTools.option(a, "--capture-area", "on"));
        CaptureArea area = useArea ? policy.getCaptureArea() : null;

        OpenCvLoader.ensureLoaded();
        try (OnnxLivenessEngine engine = new OnnxLivenessEngine(OnnxLivenessEngine.defaultModelDir())) {
            String counted = area == null ? "frames counted: any single face"
                    : "frames counted: one face inside the capture oval (" + configFile + ")";
            System.out.println("Evaluating " + dataset + " with " + engine.info().describe() + "; " + counted);
            Scorer scorer = new Scorer(engine, maxVideoFrames, minAttackFrames, attackWindow, area);
            PadMetrics metrics = PadEvaluation.run(dataset, scorer::score, System.out::println);
            PadEvaluation.writeReport(metrics, out, threshold, engine.info().describe() + "; " + counted);
            PadMetrics.OperatingPoint engineVerdict = metrics.engineVerdict();
            PadMetrics.Rate worst = engineVerdict.apcerWorst();
            System.out.printf(Locale.ROOT, "Engine verdict: worst-case APCER %s, BPCER %.2f%%%n",
                    worst == null ? "n/a" : String.format(Locale.ROOT, "%.2f%% (%s)", 100 * worst.value(),
                            engineVerdict.worstSpecies()), 100 * engineVerdict.bpcer.value());
            System.out.println("Report: " + out.resolve("report.md").toAbsolutePath());
        }
        return 0;
    }

    /**
     * Whether a frame is one the session would judge: exactly one face, with a PAD score, inside the capture oval
     * ({@code area} null: anywhere).
     */
    static boolean counts(FrameAnalysis analysis, CaptureArea area) {
        return analysis.getFaces().size() == 1 && !Float.isNaN(analysis.getPad().getBonaFideScore())
                && (area == null || area.locate(analysis.getFaces().get(0)) == CaptureArea.Position.INSIDE);
    }

    private static final class Scorer {
        private final OnnxLivenessEngine engine;
        private final int maxVideoFrames;
        private final int minAttackFrames;
        private final int attackWindow;
        /** Null: any single face counts. */
        private final CaptureArea area;
        private long sequence;

        Scorer(OnnxLivenessEngine engine, int maxVideoFrames, int minAttackFrames, int attackWindow,
               CaptureArea area) {
            this.engine = engine;
            this.maxVideoFrames = maxVideoFrames;
            this.minAttackFrames = minAttackFrames;
            this.attackWindow = attackWindow;
            this.area = area;
        }

        ScoredPresentation score(Path file, String id, String species) throws Exception {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            String ext = name.substring(name.lastIndexOf('.') + 1);
            return PadEvaluation.VIDEO_EXTENSIONS.contains(ext) ? video(file, id, species) : image(file, id, species);
        }

        private ScoredPresentation image(Path file, String id, String species) throws Exception {
            Mat bgr = Imgcodecs.imread(file.toString(), Imgcodecs.IMREAD_COLOR);
            try {
                if (bgr.empty()) {
                    throw new IllegalStateException("unreadable image");
                }
                long t0 = System.nanoTime();
                FrameAnalysis analysis = engine.analyze(frame(bgr));
                double ms = (System.nanoTime() - t0) / 1e6;
                if (!counts(analysis, area)) {
                    return ScoredPresentation.noResponse(id, species, 1, ms);
                }
                return new ScoredPresentation(id, species, analysis.getPad().getBonaFideScore(),
                        analysis.getPad().isAttackDetected(), 1, ms);
            } finally {
                bgr.release();
            }
        }

        private ScoredPresentation video(Path file, String id, String species) throws Exception {
            VideoCapture capture = new VideoCapture(file.toString());
            Mat bgr = new Mat();
            try {
                if (!capture.isOpened()) {
                    throw new IllegalStateException("unreadable video");
                }
                double total = capture.get(Videoio.CAP_PROP_FRAME_COUNT);
                int step = total > maxVideoFrames ? (int) Math.floor(total / maxVideoFrames) : 1;
                List<Float> scores = new ArrayList<>();
                PadAttackWindow window = new PadAttackWindow(attackWindow, minAttackFrames);
                boolean attack = false;
                int analysed = 0;
                double ms = 0;
                for (int i = 0; analysed < maxVideoFrames && capture.read(bgr); i++) {
                    if (i % step != 0) {
                        continue;
                    }
                    long t0 = System.nanoTime();
                    FrameAnalysis analysis = engine.analyze(frame(bgr));
                    ms += (System.nanoTime() - t0) / 1e6;
                    analysed++;
                    if (counts(analysis, area)) {
                        scores.add(analysis.getPad().getBonaFideScore());
                        attack |= window.add(analysis.getPad().isAttackDetected());
                    }
                }
                double perFrame = analysed == 0 ? 0 : ms / analysed;
                if (scores.size() < 3) {
                    return ScoredPresentation.noResponse(id, species, analysed, perFrame);
                }
                Collections.sort(scores);
                return new ScoredPresentation(id, species, scores.get(scores.size() / 2),
                        attack, analysed, perFrame);
            } finally {
                bgr.release();
                capture.release();
            }
        }

        private FaceFrame frame(Mat bgr) {
            Mat continuous = bgr.isContinuous() && bgr.type() == CvType.CV_8UC3 ? bgr : bgr.clone();
            byte[] data = new byte[(int) (continuous.total() * continuous.channels())];
            continuous.get(0, 0, data);
            if (continuous != bgr) {
                continuous.release();
            }
            return new FaceFrame("evaluation", ++sequence, System.currentTimeMillis(), bgr.cols(), bgr.rows(),
                    PixelFormat.BGR24, data, Collections.emptyMap());
        }
    }
}
