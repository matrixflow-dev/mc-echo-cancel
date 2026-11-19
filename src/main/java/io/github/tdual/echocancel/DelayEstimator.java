package io.github.tdual.echocancel;

/**
 * Finds how much later the far end shows up in the microphone (the echo delay), in samples at
 * 48 kHz.
 *
 * <p>Both signals are low-passed and reduced to 16 kHz. The last {@link #WINDOW} samples of the
 * microphone are compared with the far end over every delay up to {@link #MAX_DELAY} by
 * generalised cross-correlation with phase weighting (GCC-PHAT), which gives a sharp peak at the
 * delay even for speech. The peak is then refined to one sample at 48 kHz by plain correlation.
 *
 * <p>{@link #add} is called from the microphone thread. {@link #prepare} copies what a search
 * needs into a {@link Search}, which can run on another thread.
 */
final class DelayEstimator {

    private static final int DECIMATE = 3;
    /** Microphone samples (16 kHz) compared per search: 0.25 s. */
    static final int WINDOW = 4000;
    /** Longest delay searched, at 16 kHz: 0.45 s. */
    static final int MAX_LAG = 7200;
    /** Longest delay searched, at 48 kHz. */
    static final int MAX_DELAY = MAX_LAG * DECIMATE;
    /** Microphone samples (48 kHz) used to refine the peak: 0.1 s. */
    private static final int REFINE = 4800;
    private static final int REFINE_RANGE = 4;
    private static final int FFT = 16384;
    /** Peak over the RMS of the correlation needed to trust a peak. */
    static final double CONFIDENCE = 8;

    private final Decimator micDecimator = new Decimator();
    private final Decimator farDecimator = new Decimator();
    private final Ring mic16 = new Ring(16384);
    private final Ring far16 = new Ring(16384);
    private final Ring mic48 = new Ring(8192);
    private final Ring far48 = new Ring(32768);
    /** Consecutive real microphone samples at 16 kHz. */
    private int micRun;

    /**
     * One frame of both signals, at the same moment.
     *
     * @param mic the microphone, or null when it was not captured (not sent)
     */
    void add(short[] mic, short[] far) {
        for (int i = 0; i < far.length; i++) {
            double m = mic == null ? 0 : mic[i];
            double f = far[i];
            mic48.put(m);
            far48.put(f);
            double dm = micDecimator.push(m);
            double df = farDecimator.push(f);
            if (micDecimator.ready()) {
                mic16.put(dm);
                far16.put(df);
                micRun = mic == null ? 0 : micRun + 1;
            }
        }
    }

    /** A copy of the data for one search, or null when there is not enough to search. */
    Search prepare() {
        if (micRun < WINDOW) {
            return null;
        }
        double[] m = mic16.last(WINDOW);
        double[] f = far16.last(WINDOW + MAX_LAG);
        if (energy(f) / f.length < Aec.FAR_POWER || energy(m) / m.length < 10) {
            return null; // The far end is quiet, or the microphone hears nothing.
        }
        return new Search(m, f, mic48.last(REFINE), far48.last(REFINE + MAX_DELAY + REFINE_RANGE * 2 + DECIMATE));
    }

    /**
     * A delay found by a search.
     *
     * @param delay samples at 48 kHz
     * @param confidence the correlation peak over its RMS; at least {@link #CONFIDENCE}
     */
    record Found(int delay, double confidence) {}

    /** A delay search over copied data. */
    static final class Search {
        private final double[] mic;
        private final double[] far;
        private final double[] mic48;
        private final double[] far48;

        Search(double[] mic, double[] far, double[] mic48, double[] far48) {
            this.mic = mic;
            this.far = far;
            this.mic48 = mic48;
            this.far48 = far48;
        }

        /** The delay found, or null when no clear echo was found. */
        Found run() {
            Fft fft = new Fft(FFT);
            double[] mRe = new double[FFT];
            double[] mIm = new double[FFT];
            double[] fRe = new double[FFT];
            double[] fIm = new double[FFT];
            System.arraycopy(mic, 0, mRe, 0, mic.length);
            System.arraycopy(far, 0, fRe, 0, far.length);
            fft.forward(mRe, mIm);
            fft.forward(fRe, fIm);
            // cc[s] = sum m[i] f[i + s]: conj(M) * F, phase-weighted.
            for (int k = 0; k < FFT; k++) {
                double re = mRe[k] * fRe[k] + mIm[k] * fIm[k];
                double im = mRe[k] * fIm[k] - mIm[k] * fRe[k];
                double mag = Math.pow(re * re + im * im, 0.4) + 1e-9; // |C|^0.8
                mRe[k] = re / mag;
                mIm[k] = im / mag;
            }
            fft.inverse(mRe, mIm);
            // Microphone sample i is at the same moment as far sample i + MAX_LAG, so delay
            // l pairs it with far sample i + MAX_LAG - l: s = MAX_LAG - l.
            int best = -1;
            double peak = 0;
            double sum = 0;
            for (int s = 0; s <= MAX_LAG; s++) {
                double v = mRe[s];
                sum += v * v;
                if (v > peak) {
                    peak = v;
                    best = s;
                }
            }
            double rms = Math.sqrt(sum / (MAX_LAG + 1));
            if (best < 0 || peak < CONFIDENCE * rms) {
                return null;
            }
            return new Found(refine((MAX_LAG - best) * DECIMATE), peak / rms);
        }

        /** The best delay at 48 kHz near a coarse one. */
        private int refine(int coarse) {
            // far48 ends at the same moment as mic48; far48[j] with j = i + offset - d pairs with mic48[i].
            int offset = far48.length - mic48.length;
            int best = coarse;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int d = Math.max(0, coarse - REFINE_RANGE); d <= Math.min(MAX_DELAY, coarse + REFINE_RANGE); d++) {
                double dot = 0;
                double e = 0;
                for (int i = 0; i < mic48.length; i++) {
                    int j = i + offset - d;
                    if (j < 0) {
                        continue;
                    }
                    dot += mic48[i] * far48[j];
                    e += far48[j] * far48[j];
                }
                double score = e > 0 ? dot / Math.sqrt(e) : 0;
                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
            return best;
        }
    }

    private static double energy(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v * v;
        }
        return s;
    }

    /** Low-pass (7 kHz) and keep every third sample. */
    private static final class Decimator {
        private static final double[] TAPS = taps(31, 7000.0 / 48000);
        private final double[] state = new double[TAPS.length];
        private int pos;
        private int phase;

        double push(double x) {
            state[pos] = x;
            pos = (pos + 1) % state.length;
            phase = (phase + 1) % DECIMATE;
            if (phase != 0) {
                return 0;
            }
            double y = 0;
            for (int i = 0; i < TAPS.length; i++) {
                y += TAPS[i] * state[(pos + i) % state.length];
            }
            return y;
        }

        boolean ready() {
            return phase == 0;
        }

        private static double[] taps(int count, double cutoff) {
            double[] h = new double[count];
            int mid = count / 2;
            double sum = 0;
            for (int i = 0; i < count; i++) {
                int t = i - mid;
                double sinc = t == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * t) / (Math.PI * t);
                double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (count - 1));
                h[i] = sinc * window;
                sum += h[i];
            }
            for (int i = 0; i < count; i++) {
                h[i] /= sum;
            }
            return h;
        }
    }

    /** The most recent samples of a signal. */
    static final class Ring {
        private final double[] data;
        private int next;
        private long count;

        Ring(int size) {
            data = new double[size];
        }

        void put(double v) {
            data[next] = v;
            next = (next + 1) % data.length;
            count++;
        }

        /** The last n samples, oldest first; zeros before the start. */
        double[] last(int n) {
            if (n > data.length) {
                throw new IllegalArgumentException(n + " > " + data.length);
            }
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                long index = count - n + i;
                if (index >= 0) {
                    out[i] = data[(int) (index % data.length)];
                }
            }
            return out;
        }
    }
}
