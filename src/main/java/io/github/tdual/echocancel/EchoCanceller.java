package io.github.tdual.echocancel;

/**
 * Runs the linear canceller ({@link Aec}) and the residual echo suppressor ({@link Suppressor})
 * on Simple Voice Chat frames (960 samples, 20 ms at 48 kHz). The far end must already be aligned
 * to the echo. The canceller works in blocks of 512 and the suppressor adds 256, so the
 * microphone comes out {@link #DELAY} samples (16 ms) late.
 *
 * <p>Not thread-safe: call from the microphone thread only.
 */
final class EchoCanceller {

    static final int FRAME = 960;
    static final int BLOCK = 512;
    /** 12 blocks of 512 at 48 kHz: 128 ms of echo path after alignment. */
    static final int PARTITIONS = 12;
    /** How late the output is. */
    static final int DELAY = BLOCK + Suppressor.HOP;

    private final Aec aec = new Aec(BLOCK, PARTITIONS);
    private final Suppressor suppressor = new Suppressor();

    private final double[] micBlock = new double[BLOCK];
    private final double[] farBlock = new double[BLOCK];
    private final boolean[] realBlock = new boolean[BLOCK];
    private final double[] errorBlock = new double[BLOCK];
    private final double[] hopIn = new double[Suppressor.HOP];
    private final double[] hopFar = new double[Suppressor.HOP];
    private final double[] hopMic = new double[Suppressor.HOP];
    private boolean aligned;
    private final double[] hopOut = new double[Suppressor.HOP];
    /** The microphone delayed by the suppressor, to compare with its output. */
    private final double[] micDelay = new double[Suppressor.HOP];
    private int fill;

    /** Output and the microphone at the same moment, not yet returned. Starts with one block of silence. */
    private final double[] pendingOut = new double[4 * FRAME];
    private final double[] pendingMic = new double[4 * FRAME];
    private int pendingStart;
    private int pendingSize = BLOCK;

    private double lastIn;
    private double lastOut;

    void setSuppress(boolean on) {
        suppressor.setEnabled(on);
    }

    /** Whether the far end is known to be aligned to the echo (see {@link Suppressor}). */
    void setAligned(boolean on) {
        aligned = on;
    }

    /** True while the far end plays, or did a moment ago (the echo tail). */
    boolean farActive() {
        return suppressor.farActive();
    }

    /** Far-end samples {@link #shift} needs. */
    int historyLength() {
        return aec.historyLength() + BLOCK;
    }

    /**
     * The far end will be taken this many samples further back from now on.
     *
     * @param past the far end under the new alignment, ending at the last sample given to
     *     process or skip ({@link #historyLength} samples)
     */
    void shift(int samples, double[] past) {
        int complete = past.length - fill;
        aec.shift(samples, java.util.Arrays.copyOfRange(past, complete - aec.historyLength(), complete));
        System.arraycopy(past, complete, farBlock, 0, fill);
    }

    /** Energy of the microphone over the last frame returned by {@link #process}, at the same moment as the output. */
    double lastInEnergy() {
        return lastIn;
    }

    /** Energy of the last frame returned by {@link #process}. */
    double lastOutEnergy() {
        return lastOut;
    }

    /**
     * @param mic one frame from the microphone
     * @param far the far end aligned to the echo in this frame
     * @return the microphone frame with the echo removed
     */
    short[] process(short[] mic, short[] far) {
        push(mic, far, true);
        short[] out = new short[FRAME];
        lastIn = 0;
        lastOut = 0;
        for (int i = 0; i < FRAME; i++) {
            int at = (pendingStart + i) % pendingOut.length;
            double v = pendingOut[at];
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
            lastOut += v * v;
            lastIn += pendingMic[at] * pendingMic[at];
        }
        drop(FRAME);
        return out;
    }

    /**
     * A frame passed while the microphone was not being sent. Keeps the far-end history in step
     * with time, without learning from the microphone.
     */
    void skip(short[] far) {
        push(null, far, false);
        drop(FRAME);
    }

    private void push(short[] mic, short[] far, boolean real) {
        for (int i = 0; i < FRAME; i++) {
            micBlock[fill] = mic == null ? 0 : mic[i];
            farBlock[fill] = far == null ? 0 : far[i];
            realBlock[fill] = real;
            if (++fill == BLOCK) {
                fill = 0;
                processBlock();
            }
        }
    }

    private void processBlock() {
        boolean allReal = true;
        for (boolean r : realBlock) {
            allReal &= r;
        }
        aec.process(micBlock, farBlock, allReal, errorBlock);
        for (int i = 0; i < BLOCK; i++) {
            if (!realBlock[i]) {
                errorBlock[i] = 0; // Not microphone audio: comes out silent.
            }
        }
        for (int h = 0; h < BLOCK; h += Suppressor.HOP) {
            System.arraycopy(errorBlock, h, hopIn, 0, Suppressor.HOP);
            System.arraycopy(farBlock, h, hopFar, 0, Suppressor.HOP);
            System.arraycopy(micBlock, h, hopMic, 0, Suppressor.HOP);
            suppressor.process(hopIn, hopMic, hopFar, aligned, allReal, hopOut);
            int base = pendingStart + pendingSize;
            for (int j = 0; j < Suppressor.HOP; j++) {
                int at = (base + j) % pendingOut.length;
                pendingOut[at] = hopOut[j];
                pendingMic[at] = micDelay[j];
                micDelay[j] = realBlock[h + j] ? micBlock[h + j] : 0;
            }
            pendingSize += Suppressor.HOP;
        }
    }

    private void drop(int count) {
        pendingStart = (pendingStart + count) % pendingOut.length;
        pendingSize -= count;
    }
}
