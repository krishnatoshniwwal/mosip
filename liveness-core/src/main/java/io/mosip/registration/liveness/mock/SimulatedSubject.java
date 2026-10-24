package io.mosip.registration.liveness.mock;

import io.mosip.registration.liveness.challenge.ChallengeType;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.engine.AttackType;
import io.mosip.registration.liveness.engine.FaceObservation;
import io.mosip.registration.liveness.engine.FaceRegion;
import io.mosip.registration.liveness.engine.PadResult;
import io.mosip.registration.liveness.session.LivenessListener;
import io.mosip.registration.liveness.session.LivenessResult;
import io.mosip.registration.liveness.session.LivenessState;
import io.mosip.registration.liveness.session.LivenessUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Who or what is in front of the mock camera: a live person who can perform facial actions, or a
 * presentation attack. The numbers it produces are what a real engine would measure, so every
 * branch of the liveness logic can be exercised deterministically (seeded {@link Random}, injected clock).
 */
public final class SimulatedSubject {

    public enum Presentation {
        LIVE,
        PRINTED_PHOTO,
        SCREEN_DISPLAY,
        /** A pre-recorded video of the person that blinks and sways on its own and evades passive PAD. */
        VIDEO_REPLAY,
        NO_FACE,
        TWO_FACES
    }

    public enum ResponseMode {
        FOLLOW_PROMPTS,
        IGNORE_PROMPTS,
        WRONG_ACTION
    }

    private static final float OPEN_EYE = 0.9f;
    private static final float REST_SMILE = 0.1f;
    private static final float TURN_DEGREES = 30f;
    private static final float TILT_DEGREES = 25f;
    private static final long BLINK_MS = 300;
    private static final long GESTURE_MS = 1500;
    private static final long REPLAY_LOOP_MS = 2500;

    private final LongSupplier clock;
    private final Random random;

    private volatile Presentation presentation = Presentation.LIVE;
    private volatile float livenessLevel = 85f;
    private volatile int quality = 80;
    private volatile ResponseMode responseMode = ResponseMode.FOLLOW_PROMPTS;
    private volatile long reactionDelayMs = 500;
    private volatile boolean naturalBlinks = true;
    private volatile float placeX = 0.5f;
    private volatile float placeY = 0.48f;
    private volatile float placeScale = 1f;
    private volatile Set<FaceRegion> occlusion = Collections.emptySet();

    private ChallengeType action;
    private long actionStart;
    private long nextNaturalBlink;

    public SimulatedSubject(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
        this.nextNaturalBlink = clock.getAsLong() + 3000 + random.nextInt(3000);
    }

    public Presentation getPresentation() { return presentation; }
    public void setPresentation(Presentation p) { presentation = p; }
    /** Passive liveness score the engine reports for this (live) face, 0..100. */
    public void setLivenessLevel(float level) { livenessLevel = level; }
    public void setQuality(int q) { quality = q; }
    public ResponseMode getResponseMode() { return responseMode; }
    public void setResponseMode(ResponseMode mode) { responseMode = mode; }
    public void setReactionDelayMs(long ms) { reactionDelayMs = ms; }
    public void setNaturalBlinks(boolean enabled) { naturalBlinks = enabled; }
    /** Where the live face is in the frame (centre, normalised) and how large relative to a normal distance. */
    public void setPlacement(float centerX, float centerY, float scale) {
        placeX = centerX;
        placeY = centerY;
        placeScale = scale;
    }
    /** Regions of the live face covered by a mask, scarf or sunglasses; the engine cannot measure them. */
    public void setOcclusion(Set<FaceRegion> regions) {
        occlusion = regions.isEmpty() ? Collections.emptySet() : EnumSet.copyOf(regions);
    }

    /** Starts a facial action now. Ignored by photos, screens and replays, which cannot respond. */
    public void perform(ChallengeType type) {
        perform(type, 0);
    }

    public synchronized void perform(ChallengeType type, long delayMs) {
        action = type;
        actionStart = clock.getAsLong() + delayMs;
    }

    /** Measurements for the current instant. */
    public synchronized SimulatedFrameTruth sample() {
        long now = clock.getAsLong();
        Presentation p = presentation;
        switch (p) {
            case NO_FACE:
                return new SimulatedFrameTruth(p, Collections.<FaceObservation>emptyList(), Float.NaN,
                        PadResult.notEvaluated());
            case PRINTED_PHOTO:
                return spoof(p, AttackType.PRINTED_PHOTO, 22f);
            case SCREEN_DISPLAY:
                return spoof(p, AttackType.SCREEN_DISPLAY, 28f);
            case VIDEO_REPLAY:
                return replay(p, now);
            case TWO_FACES: {
                List<FaceObservation> faces = new ArrayList<>();
                faces.add(liveFace(now, 0.38f));
                faces.add(FaceObservation.builder().center(0.74f, 0.45f).size(0.22f, 0.3f).quality(60)
                        .eyes(0.85f).smile(0.2f).pose(-8f, 0f, 0f).build());
                return new SimulatedFrameTruth(p, faces, liveScore(), PadResult.bonaFide(liveScore()));
            }
            default: {
                float score = liveScore();
                return new SimulatedFrameTruth(p, Collections.singletonList(liveFace(now, 0.5f)), score,
                        PadResult.bonaFide(score));
            }
        }
    }

    private FaceObservation liveFace(long now, float centerX) {
        float eyes = OPEN_EYE + jitter(0.03f);
        float smile = REST_SMILE + jitter(0.02f);
        float yaw = jitter(2f);
        float pitch = jitter(2f);

        if (naturalBlinks && now >= nextNaturalBlink) {
            eyes *= 1f - envelope(now - nextNaturalBlink, BLINK_MS);
            if (now - nextNaturalBlink > BLINK_MS) {
                nextNaturalBlink = now + 3000 + random.nextInt(3000);
            }
        }
        if (action != null && now >= actionStart) {
            long duration = action == ChallengeType.BLINK ? BLINK_MS : GESTURE_MS;
            float e = envelope(now - actionStart, duration);
            switch (action) {
                case BLINK:
                    eyes *= 1f - e;
                    break;
                case SMILE:
                    smile += 0.8f * e;
                    break;
                case TURN_LEFT:
                    yaw += TURN_DEGREES * e;
                    break;
                case TURN_RIGHT:
                    yaw -= TURN_DEGREES * e;
                    break;
                case LOOK_UP:
                    pitch += TILT_DEGREES * e;
                    break;
                case LOOK_DOWN:
                    pitch -= TILT_DEGREES * e;
                    break;
                default:
                    break;
            }
            if (now - actionStart > duration) {
                action = null;
            }
        }
        Set<FaceRegion> covered = occlusion;
        return FaceObservation.builder()
                .center(centerX + (placeX - 0.5f) - yaw / TURN_DEGREES * 0.03f + jitter(0.004f),
                        placeY - pitch / TILT_DEGREES * 0.03f + jitter(0.004f))
                .size(0.34f * placeScale, 0.46f * placeScale)
                .quality(clamp(quality + Math.round(jitter(4f)), 0, 100))
                .eyes(covered.contains(FaceRegion.EYES) ? Float.NaN : clamp01(eyes))
                .smile(covered.contains(FaceRegion.LOWER_FACE) ? Float.NaN : clamp01(smile))
                .pose(yaw, pitch, jitter(1.5f))
                .occluded(covered)
                .build();
    }

    private SimulatedFrameTruth spoof(Presentation p, AttackType type, float level) {
        float score = clamp(level + jitter(4f), 0f, 100f);
        FaceObservation face = FaceObservation.builder().center(0.5f, 0.48f).size(0.3f, 0.42f)
                .quality(70).eyes(0.85f).smile(REST_SMILE).pose(3f, -2f, 1f).build();
        PadResult pad = random.nextFloat() < 0.8f ? PadResult.attack(type, score) : PadResult.bonaFide(score);
        return new SimulatedFrameTruth(p, Collections.singletonList(face), score, pad);
    }

    private SimulatedFrameTruth replay(Presentation p, long now) {
        long t = Math.floorMod(now, REPLAY_LOOP_MS);
        float eyes = OPEN_EYE * (1f - envelope(t, BLINK_MS));
        float yaw = (float) (4 * Math.sin(2 * Math.PI * now / 4000.0));
        float score = clamp(58f + jitter(4f), 0f, 100f);
        FaceObservation face = FaceObservation.builder().center(0.5f, 0.48f).size(0.33f, 0.45f)
                .quality(75).eyes(eyes).smile(REST_SMILE).pose(yaw, 0f, 0f).build();
        return new SimulatedFrameTruth(p, Collections.singletonList(face), score, PadResult.bonaFide(score));
    }

    /**
     * Wraps a UI listener so the subject reacts to each new challenge prompt according to the
     * {@link ResponseMode}, the way a person reacts to the on-screen instruction.
     */
    public LivenessListener respondingTo(LivenessListener delegate) {
        return new LivenessListener() {
            private String lastPrompt;

            @Override
            public void onUpdate(LivenessUpdate update) {
                if (update.getState() == LivenessState.ACTIVE_CHALLENGE && update.getCurrentChallenge() != null) {
                    String prompt = update.getSessionId() + "/" + update.getAttemptSequence() + "/"
                            + update.getChallengeNumber();
                    if (!prompt.equals(lastPrompt)) {
                        lastPrompt = prompt;
                        respond(update.getCurrentChallenge());
                    }
                }
                delegate.onUpdate(update);
            }

            @Override
            public void onComplete(LivenessResult result) {
                delegate.onComplete(result);
            }

            @Override
            public void onPreviewFrame(FaceFrame frame) {
                delegate.onPreviewFrame(frame);
            }
        };
    }

    private void respond(ChallengeType requested) {
        switch (responseMode) {
            case FOLLOW_PROMPTS:
                perform(requested, reactionDelayMs);
                break;
            case WRONG_ACTION:
                perform(wrongActionFor(requested), reactionDelayMs);
                break;
            default:
                break;
        }
    }

    static ChallengeType wrongActionFor(ChallengeType type) {
        switch (type) {
            case TURN_LEFT:
                return ChallengeType.TURN_RIGHT;
            case TURN_RIGHT:
                return ChallengeType.TURN_LEFT;
            case LOOK_UP:
                return ChallengeType.LOOK_DOWN;
            case LOOK_DOWN:
                return ChallengeType.LOOK_UP;
            case BLINK:
                return ChallengeType.SMILE;
            default:
                return ChallengeType.BLINK;
        }
    }

    /** 0 → 1 over the first quarter, hold, 1 → 0 over the last quarter. */
    private static float envelope(long t, long duration) {
        if (t < 0 || t > duration) {
            return 0f;
        }
        float x = t / (float) duration;
        if (x < 0.25f) {
            return x / 0.25f;
        }
        if (x < 0.75f) {
            return 1f;
        }
        return (1f - x) / 0.25f;
    }

    private float jitter(float amplitude) {
        return (random.nextFloat() * 2f - 1f) * amplitude;
    }

    private float liveScore() {
        return clamp(livenessLevel + jitter(5f), 0f, 100f);
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
