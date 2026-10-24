package io.mosip.registration.liveness.evaluation;

/**
 * One presentation (image or video) and what the PAD subsystem made of it. Scores are bona fide
 * scores on the engine's 0..100 scale: higher means more likely a live person.
 */
public final class ScoredPresentation {

    private final String id;
    private final String species;
    private final float score;
    private final boolean engineAttack;
    private final int frames;
    private final double msPerFrame;

    /**
     * @param species      attack species (PAI species, ISO/IEC 30107-1), or null for a bona fide presentation
     * @param score        bona fide score, or NaN when the subsystem gave no response (no face, several faces, error)
     * @param engineAttack whether the engine itself flagged a presentation attack
     * @param frames       frames analysed (1 for an image)
     * @param msPerFrame   mean analysis time per frame
     */
    public ScoredPresentation(String id, String species, float score, boolean engineAttack, int frames, double msPerFrame) {
        this.id = id;
        this.species = species;
        this.score = score;
        this.engineAttack = engineAttack;
        this.frames = frames;
        this.msPerFrame = msPerFrame;
    }

    public static ScoredPresentation noResponse(String id, String species, int frames, double msPerFrame) {
        return new ScoredPresentation(id, species, Float.NaN, false, frames, msPerFrame);
    }

    public String getId() { return id; }
    public String getSpecies() { return species; }
    public boolean isBonaFide() { return species == null; }
    public float getScore() { return score; }
    public boolean isResponse() { return !Float.isNaN(score); }
    public boolean isEngineAttack() { return engineAttack; }
    public int getFrames() { return frames; }
    public double getMsPerFrame() { return msPerFrame; }

    /** Accepted as bona fide at {@code threshold}. A non-response is never an acceptance. */
    public boolean acceptedAt(double threshold) {
        return isResponse() && score >= threshold;
    }

    /** Accepted by the engine's own PAD verdict. */
    public boolean acceptedByEngine() {
        return isResponse() && !engineAttack;
    }
}
