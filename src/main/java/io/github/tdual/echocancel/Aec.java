package io.github.tdual.echocancel;

import java.util.Arrays;

/**
 * Linear echo canceller for one block of samples at a time.
 *
 * <p>A partitioned-block frequency-domain adaptive filter (overlap-save) learns how the far-end
 * signal (what the speakers play) reaches the microphone, and subtracts its estimate. The far end
 * is given already aligned to the echo by {@link DelayEstimator}, so the filter only has to cover
 * the room and a small margin ({@code block * partitions} samples).
 *
 * <p>Two filters are kept. The background filter always learns at full speed, so it follows a
 * changed echo path quickly, but near-end speech (the player talking over the echo) disturbs it.
 * The foreground filter produces the output; it takes the background filter's coefficients only
 * while those remove clearly more echo.
 *
 * <p>Not thread-safe.
 */
final class Aec {

    /** Step size of the background filter (normalised; stable below 2). */
    private static final double MU = 1.0;
    /** Mean power of a block of far-end samples above which the far end counts as playing. */
    static final double FAR_POWER = 30.0 * 30.0;
    /** Partitions constrained to a linear (not circular) filter per block, round robin. */
    private static final int CONSTRAIN_PER_BLOCK = 4;
    /** Consecutive blocks worse than the microphone after which both filters are reset (0.5 s). */
    private static final int DIVERGED_BLOCKS = 47;

    private final int b;
    private final int n;
    private final int k;
    private final int p;
    private final Fft fft;

    private final double[] history;
    private final double[][] xRe;
    private final double[][] xIm;
    /** Background filter (always learning). */
    private final double[][] bgRe;
    private final double[][] bgIm;
    /** Foreground filter (produces the output). */
    private final double[][] fgRe;
    private final double[][] fgIm;
    private int head;
    private int constrainNext;

    private final double[] re;
    private final double[] im;
    private final double[] yRe;
    private final double[] yIm;
    private final double[] power;
    private final double[] bgError;

    private double bgErrorAvg;
    private double fgErrorAvg;
    private int worseBlocks;

    /**
     * @param block samples per call; the FFT is twice this and must be a power of two
     * @param partitions filter length in blocks
     */
    Aec(int block, int partitions) {
        this.b = block;
        this.n = 2 * block;
        this.k = block + 1;
        this.p = partitions;
        this.fft = new Fft(n);
        history = new double[n];
        xRe = new double[p][k];
        xIm = new double[p][k];
        bgRe = new double[p][k];
        bgIm = new double[p][k];
        fgRe = new double[p][k];
        fgIm = new double[p][k];
        re = new double[n];
        im = new double[n];
        yRe = new double[k];
        yIm = new double[k];
        power = new double[k];
        bgError = new double[block];
    }

    /**
     * Processes one block.
     *
     * @param mic what the microphone captured (block samples)
     * @param far the far end, aligned to the echo in mic (block samples)
     * @param adapt false when mic is not real audio (it then only advances the far-end history)
     * @param out receives the microphone with the linear echo estimate removed (block samples)
     */
    void process(double[] mic, double[] far, boolean adapt, double[] out) {
        double farPower = 0;
        for (int i = 0; i < b; i++) {
            farPower += far[i] * far[i];
        }
        boolean farNow = farPower / b > FAR_POWER;

        // Far-end spectrum of the last two blocks, newest partition at head.
        System.arraycopy(history, b, history, 0, b);
        System.arraycopy(far, 0, history, b, b);
        head = (head + 1) % p;
        System.arraycopy(history, 0, re, 0, n);
        Arrays.fill(im, 0);
        fft.forward(re, im);
        System.arraycopy(re, 0, xRe[head], 0, k);
        System.arraycopy(im, 0, xIm[head], 0, k);

        double micPower = 0;
        for (int i = 0; i < b; i++) {
            micPower += mic[i] * mic[i];
        }

        // Foreground: the output.
        estimate(fgRe, fgIm);
        double errorPower = 0;
        for (int i = 0; i < b; i++) {
            double e = mic[i] - re[b + i];
            out[i] = e;
            errorPower += e * e;
        }

        boolean worse = errorPower > micPower;
        if (adapt) {
            // A single block can be worse than the microphone (at the edges of speech), but a
            // filter that stays worse for a long time has diverged: start over.
            worseBlocks = errorPower > 2 * micPower + b ? worseBlocks + 1 : 0;
            if (worseBlocks > DIVERGED_BLOCKS) {
                reset();
                System.arraycopy(mic, 0, out, 0, b);
                errorPower = micPower;
            }
        }

        if (adapt && farNow) {
            // Background: learns from its own error.
            estimate(bgRe, bgIm);
            double bgErrorPower = 0;
            for (int i = 0; i < b; i++) {
                bgError[i] = mic[i] - re[b + i];
                bgErrorPower += bgError[i] * bgError[i];
            }
            learn(bgError);

            bgErrorAvg = 0.8 * bgErrorAvg + 0.2 * bgErrorPower;
            fgErrorAvg = 0.8 * fgErrorAvg + 0.2 * errorPower;
            if (bgErrorAvg < 0.7 * fgErrorAvg && bgErrorPower < errorPower) {
                // The background removes clearly more echo: use it.
                copy(bgRe, bgIm, fgRe, fgIm);
                fgErrorAvg = bgErrorAvg;
            } else if (bgErrorAvg > 8 * fgErrorAvg) {
                // Near-end speech pulled the background far off: start it again from the foreground.
                copy(fgRe, fgIm, bgRe, bgIm);
                bgErrorAvg = fgErrorAvg;
            }
        }
        if (worse) {
            // Removing the estimate would add echo (the far end is misaligned for now): don't.
            System.arraycopy(mic, 0, out, 0, b);
        }
    }

    /** Samples of far end that {@link #shift} needs: the history the partitions are made from. */
    int historyLength() {
        return (p + 1) * b;
    }

    /**
     * The far end given from now on is {@code samples} earlier in time than before (positive: the
     * echo turned out to come later, so the far end is taken from further back). Moves both
     * filters' taps the other way, so what was learnt about the room is kept, and rebuilds the
     * far-end history from {@code past}: the far end under the new alignment, ending where the
     * last processed block ended ({@link #historyLength} samples).
     */
    void shift(int samples, double[] past) {
        if (samples != 0) {
            shiftTaps(bgRe, bgIm, samples);
            shiftTaps(fgRe, fgIm, samples);
        }
        int end = past.length;
        for (int j = 0; j < p; j++) {
            int q = (head - j + p) % p;
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            System.arraycopy(past, end - (j + 2) * b, re, 0, n);
            fft.forward(re, im);
            System.arraycopy(re, 0, xRe[q], 0, k);
            System.arraycopy(im, 0, xIm[q], 0, k);
        }
        System.arraycopy(past, end - n, history, 0, n);
        worseBlocks = 0;
    }

    /**
     * h'[t] = h[t + samples] over one array of p * b taps: with the far end taken samples further
     * back, the same echo needs a filter that many taps shorter in front.
     */
    private void shiftTaps(double[][] wRe, double[][] wIm, int samples) {
        int taps = p * b;
        double[] h = new double[taps];
        for (int j = 0; j < p; j++) {
            toTime(wRe[j], wIm[j]);
            System.arraycopy(re, 0, h, j * b, b);
        }
        double[] moved = new double[taps];
        for (int t = 0; t < taps; t++) {
            int from = t + samples;
            if (from >= 0 && from < taps) {
                moved[t] = h[from];
            }
        }
        for (int j = 0; j < p; j++) {
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            System.arraycopy(moved, j * b, re, 0, b);
            fft.forward(re, im);
            System.arraycopy(re, 0, wRe[j], 0, k);
            System.arraycopy(im, 0, wIm[j], 0, k);
        }
    }

    /** Echo estimate of filter w into re[b..n). */
    private void estimate(double[][] wRe, double[][] wIm) {
        Arrays.fill(yRe, 0);
        Arrays.fill(yIm, 0);
        for (int j = 0; j < p; j++) {
            int q = (head - j + p) % p;
            double[] wr = wRe[j];
            double[] wi = wIm[j];
            double[] xr = xRe[q];
            double[] xi = xIm[q];
            for (int f = 0; f < k; f++) {
                yRe[f] += wr[f] * xr[f] - wi[f] * xi[f];
                yIm[f] += wr[f] * xi[f] + wi[f] * xr[f];
            }
        }
        toTime(yRe, yIm);
    }

    /** One normalised step of the background filter towards removing error. */
    private void learn(double[] error) {
        // Error spectrum, zero-padded in front (overlap-save).
        Arrays.fill(re, 0, b, 0);
        System.arraycopy(error, 0, re, b, b);
        Arrays.fill(im, 0);
        fft.forward(re, im);

        Arrays.fill(power, 0);
        for (int j = 0; j < p; j++) {
            double[] xr = xRe[j];
            double[] xi = xIm[j];
            for (int f = 0; f < k; f++) {
                power[f] += xr[f] * xr[f] + xi[f] * xi[f];
            }
        }
        double mean = 0;
        for (int f = 0; f < k; f++) {
            mean += power[f];
        }
        double delta = 1e-3 * mean / k + 1e5;

        for (int j = 0; j < p; j++) {
            int q = (head - j + p) % p;
            double[] wr = bgRe[j];
            double[] wi = bgIm[j];
            double[] xr = xRe[q];
            double[] xi = xIm[q];
            for (int f = 0; f < k; f++) {
                double g = MU / (power[f] + delta);
                double er = re[f];
                double ei = im[f];
                wr[f] += g * (xr[f] * er + xi[f] * ei);
                wi[f] += g * (xr[f] * ei - xi[f] * er);
            }
        }
        for (int c = 0; c < CONSTRAIN_PER_BLOCK; c++) {
            constrain(constrainNext);
            constrainNext = (constrainNext + 1) % p;
        }
    }

    /** Keeps background partition j a linear filter of b taps: zero the second half of its impulse response. */
    private void constrain(int j) {
        toTime(bgRe[j], bgIm[j]);
        Arrays.fill(re, b, n, 0);
        Arrays.fill(im, 0);
        fft.forward(re, im);
        System.arraycopy(re, 0, bgRe[j], 0, k);
        System.arraycopy(im, 0, bgIm[j], 0, k);
    }

    /** Inverse FFT of a half spectrum (k bins of a real signal) into re. */
    private void toTime(double[] hr, double[] hi) {
        re[0] = hr[0];
        im[0] = 0;
        re[b] = hr[b];
        im[b] = 0;
        for (int f = 1; f < b; f++) {
            re[f] = hr[f];
            im[f] = hi[f];
            re[n - f] = hr[f];
            im[n - f] = -hi[f];
        }
        fft.inverse(re, im);
    }

    private void copy(double[][] fromRe, double[][] fromIm, double[][] toRe, double[][] toIm) {
        for (int j = 0; j < p; j++) {
            System.arraycopy(fromRe[j], 0, toRe[j], 0, k);
            System.arraycopy(fromIm[j], 0, toIm[j], 0, k);
        }
    }

    private void reset() {
        for (int j = 0; j < p; j++) {
            Arrays.fill(bgRe[j], 0);
            Arrays.fill(bgIm[j], 0);
            Arrays.fill(fgRe[j], 0);
            Arrays.fill(fgIm[j], 0);
        }
        worseBlocks = 0;
        bgErrorAvg = 0;
        fgErrorAvg = 0;
    }
}
