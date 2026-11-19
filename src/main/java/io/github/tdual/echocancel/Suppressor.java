package io.github.tdual.echocancel;

import java.util.Arrays;

/**
 * Residual echo suppressor: turns down, band by band, the echo that the linear canceller left.
 *
 * <p>It works on 512-sample frames with a 256-sample hop (square-root Hann windows, so the output
 * is the input when no band is turned down), which adds 256 samples of delay. For each band it
 * keeps the far end's recent peak power, decaying over about 100 ms, so it does not depend on the
 * exact delay. It turns a band down when the signal in that band is not clearly louder than the
 * echo expected there, so bands where the player is talking stay up.
 *
 * <p>The expected echo comes from one of two learnt gains. While the far end is known to be
 * aligned, the linear canceller works, and the gain from the far-end peak to what the canceller
 * leaves is used. Right after a voice starts again its delay is not known yet and the canceller
 * may remove little; then the gain from the far-end peak to the microphone itself (the echo path
 * gain) is used, scaled by how much the canceller removed in that band.
 *
 * <p>Not thread-safe.
 */
final class Suppressor {

    static final int FRAME = 512;
    static final int HOP = 256;
    private static final int BINS = FRAME / 2 + 1;
    /** Far-end peak decays to 1/e in this many hops (about 100 ms). */
    private static final double PEAK_DECAY = Math.exp(-1.0 / 19);
    /** Strongest attenuation (-30 dB) while aligned, and (-40 dB) while not. */
    private static final double MIN_GAIN = 0.03;
    private static final double MIN_GAIN_UNALIGNED = 0.01;
    /** Removes this many times the estimated echo, for safety; more while not aligned. */
    private static final double OVER_SUPPRESS = 3;
    private static final double OVER_SUPPRESS_UNALIGNED = 5;
    /** Hops the suppression stays on after the far end stops (the echo tail): 160 ms. */
    private static final int HANG_HOPS = 30;
    /** Far-end power per frame (sum over bins) that counts as playing. */
    private static final double FAR_POWER = Aec.FAR_POWER * FRAME * FRAME / 4;

    private final Fft fft = new Fft(FRAME);
    private final double[] window = new double[FRAME];
    private final double[] inE = new double[FRAME];
    private final double[] inX = new double[FRAME];
    private final double[] overlap = new double[FRAME];
    private final double[] eRe = new double[FRAME];
    private final double[] eIm = new double[FRAME];
    private final double[] xRe = new double[FRAME];
    private final double[] xIm = new double[FRAME];
    private final double[] farPeak = new double[BINS];
    private final double[] echoGain = new double[BINS];
    private final double[] pathGain = new double[BINS];
    private final double[] inD = new double[FRAME];
    private final double[] dRe = new double[FRAME];
    private final double[] dIm = new double[FRAME];
    private final double[] gain = new double[BINS];
    private final double[] target = new double[BINS];
    private int hang;
    private boolean enabled = true;

    Suppressor() {
        for (int i = 0; i < FRAME; i++) {
            window[i] = Math.sqrt(0.5 * (1 - Math.cos(2 * Math.PI * i / FRAME)));
        }
        Arrays.fill(echoGain, 0.5);
        Arrays.fill(pathGain, 0.5);
        Arrays.fill(gain, 1);
    }

    void setEnabled(boolean on) {
        enabled = on;
    }

    /** True while the far end plays, or did a moment ago. */
    boolean farActive() {
        return hang > 0;
    }

    /**
     * @param error the canceller's output (HOP samples)
     * @param mic the microphone before the canceller (HOP samples)
     * @param far the far end (HOP samples)
     * @param aligned true when far is known to be aligned to the echo, so the canceller works
     * @param real false when error is not real microphone audio (nothing is learnt then)
     * @param out receives the output, HOP samples behind the input
     */
    void process(double[] error, double[] mic, double[] far, boolean aligned, boolean real, double[] out) {
        System.arraycopy(inE, HOP, inE, 0, FRAME - HOP);
        System.arraycopy(error, 0, inE, FRAME - HOP, HOP);
        System.arraycopy(inD, HOP, inD, 0, FRAME - HOP);
        System.arraycopy(mic, 0, inD, FRAME - HOP, HOP);
        System.arraycopy(inX, HOP, inX, 0, FRAME - HOP);
        System.arraycopy(far, 0, inX, FRAME - HOP, HOP);
        for (int i = 0; i < FRAME; i++) {
            eRe[i] = inE[i] * window[i];
            xRe[i] = inX[i] * window[i];
            dRe[i] = inD[i] * window[i];
        }
        Arrays.fill(eIm, 0);
        Arrays.fill(xIm, 0);
        Arrays.fill(dIm, 0);
        fft.forward(eRe, eIm);
        fft.forward(xRe, xIm);
        fft.forward(dRe, dIm);

        double farSum = 0;
        for (int k = 0; k < BINS; k++) {
            double x = xRe[k] * xRe[k] + xIm[k] * xIm[k];
            farPeak[k] = Math.max(x, farPeak[k] * PEAK_DECAY);
            farSum += x;
        }
        hang = farSum > FAR_POWER ? HANG_HOPS : Math.max(0, hang - 1);

        for (int k = 0; k < BINS; k++) {
            double e = eRe[k] * eRe[k] + eIm[k] * eIm[k];
            double d = dRe[k] * dRe[k] + dIm[k] * dIm[k];
            if (real && farSum > FAR_POWER && farPeak[k] > 0) {
                // Learn the echo levels from frames that look like echo only. A frame far louder
                // than expected is probably the player talking; learn from it only very slowly.
                pathGain[k] = learn(pathGain[k], d / farPeak[k]);
                if (aligned) {
                    echoGain[k] = learn(echoGain[k], e / farPeak[k]);
                }
            }
            double expected = aligned
                    ? echoGain[k] * farPeak[k]
                    : pathGain[k] * farPeak[k] * Math.min(1, e / (d + 1e-9));
            double over = aligned ? OVER_SUPPRESS : OVER_SUPPRESS_UNALIGNED;
            double floor = aligned ? MIN_GAIN : MIN_GAIN_UNALIGNED;
            target[k] = hang > 0 && enabled
                    ? Math.max(floor, Math.min(1, 1 - over * expected / (e + 1e-9)))
                    : 1;
        }
        for (int k = 0; k < BINS; k++) {
            // Smooth over neighbouring bands, then over time: turn down at once, back up gently.
            double t = (target[Math.max(0, k - 1)] + target[k] + target[Math.min(BINS - 1, k + 1)]) / 3;
            t = Math.min(t, target[k] * 1.5);
            gain[k] = t < gain[k] ? t : gain[k] + 0.4 * (t - gain[k]);
            double g = gain[k];
            eRe[k] *= g;
            eIm[k] *= g;
            if (k > 0 && k < FRAME / 2) {
                eRe[FRAME - k] = eRe[k];
                eIm[FRAME - k] = -eIm[k];
            }
        }
        eIm[0] = 0;
        eIm[FRAME / 2] = 0;
        fft.inverse(eRe, eIm);
        if (!real) {
            Arrays.fill(eRe, 0);
        }
        for (int i = 0; i < FRAME; i++) {
            overlap[i] += eRe[i] * window[i];
        }
        System.arraycopy(overlap, 0, out, 0, HOP);
        System.arraycopy(overlap, HOP, overlap, 0, FRAME - HOP);
        Arrays.fill(overlap, FRAME - HOP, FRAME, 0);
    }

    private static double learn(double gain, double ratio) {
        double rate = ratio < 8 * gain ? 0.05 : 0.002;
        return Math.max(1e-7, Math.min(4, gain + rate * (ratio - gain)));
    }
}
