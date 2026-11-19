package io.github.tdual.echocancel;

import java.util.Random;

/** Test signals at 48 kHz. */
final class Signals {

    private Signals() {}

    /** Speech-like: noise shaped by a few resonances, switched on and off in syllables. */
    static double[] speech(int samples, long seed, double amplitude) {
        Random r = new Random(seed);
        double[] out = new double[samples];
        double[] y1 = new double[3];
        double[] y2 = new double[3];
        double[] freq = {500 + r.nextInt(300), 1200 + r.nextInt(600), 2500 + r.nextInt(800)};
        double envelope = 0;
        int syllable = 0;
        boolean on = true;
        for (int i = 0; i < samples; i++) {
            if (--syllable <= 0) {
                on = r.nextDouble() < 0.7;
                syllable = 4800 + r.nextInt(9600);
                if (r.nextDouble() < 0.3) {
                    freq[0] = 400 + r.nextInt(500);
                    freq[1] = 1000 + r.nextInt(1000);
                }
            }
            envelope += ((on ? 1 : 0) - envelope) * 0.002;
            double x = r.nextGaussian();
            double sum = 0;
            for (int f = 0; f < 3; f++) {
                double w = 2 * Math.PI * freq[f] / 48000;
                double rad = 0.995;
                double y = x + 2 * rad * Math.cos(w) * y1[f] - rad * rad * y2[f];
                y2[f] = y1[f];
                y1[f] = y;
                sum += y;
            }
            out[i] = sum * envelope;
        }
        return scale(out, amplitude);
    }

    /** A room: a delay, the direct sound, then a decaying random tail of reflections. */
    static double[] room(int delay, int tail, double gain, long seed) {
        Random r = new Random(seed);
        double[] h = new double[delay + tail];
        double energy = 0;
        for (int i = 0; i < tail; i++) {
            h[delay + i] = (i == 0 ? 3 : 0.5 * r.nextGaussian()) * Math.exp(-i / (tail / 6.0));
            energy += h[delay + i] * h[delay + i];
        }
        double s = gain / Math.sqrt(energy);
        for (int i = 0; i < tail; i++) {
            h[delay + i] *= s;
        }
        return h;
    }

    static double[] convolve(double[] x, double[] h) {
        double[] y = new double[x.length];
        int first = 0;
        while (first < h.length && h[first] == 0) {
            first++;
        }
        for (int i = 0; i < x.length; i++) {
            double s = 0;
            for (int j = first; j < h.length && j <= i; j++) {
                s += h[j] * x[i - j];
            }
            y[i] = s;
        }
        return y;
    }

    static double[] add(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] + b[i];
        }
        return out;
    }

    static double[] scale(double[] x, double rms) {
        double s = 0;
        for (double v : x) {
            s += v * v;
        }
        double k = rms / Math.sqrt(s / x.length);
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = x[i] * k;
        }
        return out;
    }

    static short[] frame(double[] x, int index) {
        short[] f = new short[EchoCanceller.FRAME];
        for (int i = 0; i < f.length; i++) {
            double v = x[index * f.length + i];
            f[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
        }
        return f;
    }

    static double power(double[] x, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += x[i] * x[i];
        }
        return s / Math.max(1, to - from);
    }

    static double db(double ratio) {
        return 10 * Math.log10(ratio);
    }

    /** Same as convolve, by FFT (overlap-add), for long signals. */
    static double[] fastConvolve(double[] x, double[] h) {
        int size = 1;
        while (size < 2 * h.length) {
            size <<= 1;
        }
        size = Math.max(size, 8192);
        int step = size - h.length + 1;
        Fft fft = new Fft(size);
        double[] hRe = new double[size];
        double[] hIm = new double[size];
        System.arraycopy(h, 0, hRe, 0, h.length);
        fft.forward(hRe, hIm);
        double[] y = new double[x.length];
        double[] re = new double[size];
        double[] im = new double[size];
        for (int start = 0; start < x.length; start += step) {
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            System.arraycopy(x, start, re, 0, Math.min(step, x.length - start));
            fft.forward(re, im);
            for (int k = 0; k < size; k++) {
                double r = re[k] * hRe[k] - im[k] * hIm[k];
                double i = re[k] * hIm[k] + im[k] * hRe[k];
                re[k] = r;
                im[k] = i;
            }
            fft.inverse(re, im);
            for (int k = 0; k < size && start + k < y.length; k++) {
                y[start + k] += re[k];
            }
        }
        return y;
    }

    /** Speech only inside the given [from, to) second ranges, exact silence elsewhere. */
    static double[] speechIn(int samples, long seed, double amplitude, double[][] ranges) {
        double[] s = speech(samples, seed, amplitude);
        double[] out = new double[samples];
        for (double[] r : ranges) {
            int a = (int) (r[0] * 48_000);
            int b = Math.min(samples, (int) (r[1] * 48_000));
            System.arraycopy(s, a, out, a, b - a);
        }
        return out;
    }
}
