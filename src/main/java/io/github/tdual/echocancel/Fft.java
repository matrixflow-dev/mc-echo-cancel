package io.github.tdual.echocancel;

/**
 * In-place radix-2 complex FFT of one fixed size. Not thread-safe; one instance per user.
 */
final class Fft {

    private final int n;
    private final double[] cos;
    private final double[] sin;
    private final int[] reversed;

    Fft(int n) {
        if (Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        }
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / n);
            sin[i] = Math.sin(2 * Math.PI * i / n);
        }
        reversed = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            reversed[i] = Integer.reverse(i) >>> (32 - bits);
        }
    }

    int size() {
        return n;
    }

    /** Forward transform (e^-i). */
    void forward(double[] re, double[] im) {
        transform(re, im, -1);
    }

    /** Inverse transform, scaled by 1/n so that inverse(forward(x)) == x. */
    void inverse(double[] re, double[] im) {
        transform(re, im, 1);
        double scale = 1.0 / n;
        for (int i = 0; i < n; i++) {
            re[i] *= scale;
            im[i] *= scale;
        }
    }

    private void transform(double[] re, double[] im, int sign) {
        for (int i = 0; i < n; i++) {
            int j = reversed[i];
            if (j > i) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            int step = n / len;
            for (int start = 0; start < n; start += len) {
                for (int k = 0; k < half; k++) {
                    double wr = cos[k * step];
                    double wi = sign * sin[k * step];
                    int a = start + k;
                    int b = a + half;
                    double tr = re[b] * wr - im[b] * wi;
                    double ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}
