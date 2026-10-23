package io.mosip.registration.liveness.onnx;

import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.PadResult;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Passive anti-spoofing with the two MiniFASNet models from Silent-Face-Anti-Spoofing (Apache-2.0),
 * converted to ONNX by {@code tools/convert_minifasnet.py}. Mirrors the upstream {@code test.py}:
 * each model sees the face box enlarged by its own scale (2.7 and 4.0, so it can judge the face's
 * surroundings such as a phone bezel or paper edge), resized to 80x80, BGR, raw 0-255 values. The
 * two softmax outputs are averaged; class 1 is a real face, classes 0 and 2 are attacks.
 */
final class MiniFasNetEnsemble implements AutoCloseable {

    static final String V2_FILE = "minifasnet_v2_2.7_80x80.onnx";
    static final String V1SE_FILE = "minifasnet_v1se_4.0_80x80.onnx";
    private static final int INPUT_SIZE = 80;
    private static final int REAL_CLASS = 1;

    private final List<OrtSession> sessions = new ArrayList<>();
    private final List<Double> scales = new ArrayList<>();
    private final OrtEnvironment env;

    /** @return null if neither model file is present */
    static MiniFasNetEnsemble loadIfPresent(Path modelDir) throws OrtException {
        boolean v2 = Files.isRegularFile(modelDir.resolve(V2_FILE));
        boolean v1se = Files.isRegularFile(modelDir.resolve(V1SE_FILE));
        if (!v2 && !v1se) {
            return null;
        }
        MiniFasNetEnsemble ensemble = new MiniFasNetEnsemble();
        try {
            if (v2) {
                ensemble.add(modelDir.resolve(V2_FILE), 2.7);
            }
            if (v1se) {
                ensemble.add(modelDir.resolve(V1SE_FILE), 4.0);
            }
        } catch (OrtException e) {
            ensemble.close();
            throw e;
        }
        return ensemble;
    }

    private MiniFasNetEnsemble() {
        env = OrtEnvironment.getEnvironment();
    }

    private void add(Path model, double scale) throws OrtException {
        sessions.add(env.createSession(model.toString(), new OrtSession.SessionOptions()));
        scales.add(scale);
    }

    int modelCount() {
        return sessions.size();
    }

    /** @throws OrtException if inference fails; the caller must not treat that as a bona fide result */
    PadResult analyze(Mat bgrFrame, Rect face) throws OrtException {
        float[] mean = new float[3];
        for (int i = 0; i < sessions.size(); i++) {
            float[] probabilities = run(sessions.get(i), crop(bgrFrame, face, scales.get(i)));
            for (int c = 0; c < 3; c++) {
                mean[c] += probabilities[c] / sessions.size();
            }
        }
        int label = 0;
        for (int c = 1; c < 3; c++) {
            if (mean[c] > mean[label]) {
                label = c;
            }
        }
        float bonaFide = mean[REAL_CLASS] * 100f;
        return label == REAL_CLASS ? PadResult.bonaFide(bonaFide) : PadResult.attack(AttackType.UNKNOWN, bonaFide);
    }

    private float[] run(OrtSession session, float[] chw) throws OrtException {
        String input = session.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw),
                new long[] {1, 3, INPUT_SIZE, INPUT_SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap(input, tensor))) {
            return ((float[][]) result.get(0).getValue())[0];
        }
    }

    /** Enlarged crop, resized to 80x80, as a CHW float array in BGR order with 0-255 values. */
    private static float[] crop(Mat bgrFrame, Rect face, double scale) {
        Rect box = scaledBox(bgrFrame.cols(), bgrFrame.rows(), face, scale);
        Mat region = new Mat(bgrFrame, box);
        Mat resized = new Mat();
        try {
            Imgproc.resize(region, resized, new Size(INPUT_SIZE, INPUT_SIZE));
            byte[] hwc = new byte[INPUT_SIZE * INPUT_SIZE * 3];
            resized.get(0, 0, hwc);
            float[] chw = new float[hwc.length];
            int plane = INPUT_SIZE * INPUT_SIZE;
            for (int p = 0; p < plane; p++) {
                for (int c = 0; c < 3; c++) {
                    chw[c * plane + p] = hwc[p * 3 + c] & 0xFF;
                }
            }
            return chw;
        } finally {
            region.release();
            resized.release();
        }
    }

    /**
     * Port of upstream {@code CropImage._get_new_box}: scale the box about its centre (limited so
     * it fits the frame), shift it back inside the frame, and return the inclusive corners as a Rect.
     */
    static Rect scaledBox(int srcW, int srcH, Rect face, double scale) {
        scale = Math.min((srcH - 1) / (double) face.height, Math.min((srcW - 1) / (double) face.width, scale));
        double newW = face.width * scale;
        double newH = face.height * scale;
        double centerX = face.width / 2.0 + face.x;
        double centerY = face.height / 2.0 + face.y;
        double left = centerX - newW / 2;
        double top = centerY - newH / 2;
        double right = centerX + newW / 2;
        double bottom = centerY + newH / 2;
        if (left < 0) {
            right -= left;
            left = 0;
        }
        if (top < 0) {
            bottom -= top;
            top = 0;
        }
        if (right > srcW - 1) {
            left -= right - srcW + 1;
            right = srcW - 1;
        }
        if (bottom > srcH - 1) {
            top -= bottom - srcH + 1;
            bottom = srcH - 1;
        }
        int x0 = (int) left;
        int y0 = (int) top;
        return new Rect(x0, y0, (int) right - x0 + 1, (int) bottom - y0 + 1);
    }

    @Override
    public void close() {
        for (OrtSession session : sessions) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // closing anyway
            }
        }
        sessions.clear();
    }
}
