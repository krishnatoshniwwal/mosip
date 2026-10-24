package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.camera.OpenCvLoader;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.evaluation.PadEvaluation;
import io.mosip.registration.liveness.evaluation.PadMetrics;
import io.mosip.registration.liveness.evaluation.ScoredPresentation;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

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
 * an attack when at least {@code --min-attack-frames} frames are flagged (the session's
 * {@code pad.minAttackFrames}). Fewer than three frames with exactly one face is a non-response.
 */
final class PadEvaluationTool {

    private PadEvaluationTool() {
    }

    static int run(String[] a) throws Exception {
        if (a.length == 0 || a[0].startsWith("--")) {
            System.err.println("evaluate <dataset> [--out DIR] [--passive-threshold 70] [--max-video-frames 60] [--min-attack-frames 2]");
            return 2;
        }
        Path dataset = Paths.get(a[0]);
        Path out = Paths.get(LivenessTools.option(a, "--out", "pad-evaluation"));
        double threshold = Double.parseDouble(LivenessTools.option(a, "--passive-threshold", "70"));
        int maxVideoFrames = Integer.parseInt(LivenessTools.option(a, "--max-video-frames", "60"));
        int minAttackFrames = Integer.parseInt(LivenessTools.option(a, "--min-attack-frames", "2"));

        OpenCvLoader.ensureLoaded();
        try (OnnxLivenessEngine engine = new OnnxLivenessEngine(OnnxLivenessEngine.defaultModelDir())) {
            System.out.println("Evaluating " + dataset + " with " + engine.info().describe());
            Scorer scorer = new Scorer(engine, maxVideoFrames, minAttackFrames);
            PadMetrics metrics = PadEvaluation.run(dataset, scorer::score, System.out::println);
            PadEvaluation.writeReport(metrics, out, threshold, engine.info().describe());
            PadMetrics.OperatingPoint engineVerdict = metrics.engineVerdict();
            PadMetrics.Rate worst = engineVerdict.apcerWorst();
            System.out.printf(Locale.ROOT, "Engine verdict: worst-case APCER %s, BPCER %.2f%%%n",
                    worst == null ? "n/a" : String.format(Locale.ROOT, "%.2f%% (%s)", 100 * worst.value(),
                            engineVerdict.worstSpecies()), 100 * engineVerdict.bpcer.value());
            System.out.println("Report: " + out.resolve("report.md").toAbsolutePath());
        }
        return 0;
    }

    private static final class Scorer {
        private final OnnxLivenessEngine engine;
        private final int maxVideoFrames;
        private final int minAttackFrames;
        private long sequence;

        Scorer(OnnxLivenessEngine engine, int maxVideoFrames, int minAttackFrames) {
            this.engine = engine;
            this.maxVideoFrames = maxVideoFrames;
            this.minAttackFrames = minAttackFrames;
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
                if (analysis.getFaces().size() != 1 || Float.isNaN(analysis.getPad().getBonaFideScore())) {
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
                int attackFrames = 0;
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
                    if (analysis.getFaces().size() == 1 && !Float.isNaN(analysis.getPad().getBonaFideScore())) {
                        scores.add(analysis.getPad().getBonaFideScore());
                        if (analysis.getPad().isAttackDetected()) {
                            attackFrames++;
                        }
                    }
                }
                double perFrame = analysed == 0 ? 0 : ms / analysed;
                if (scores.size() < 3) {
                    return ScoredPresentation.noResponse(id, species, analysed, perFrame);
                }
                Collections.sort(scores);
                return new ScoredPresentation(id, species, scores.get(scores.size() / 2),
                        attackFrames >= minAttackFrames, analysed, perFrame);
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
