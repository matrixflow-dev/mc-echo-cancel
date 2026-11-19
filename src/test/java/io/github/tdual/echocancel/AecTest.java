package io.github.tdual.echocancel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AecTest {

    static final int B = 512;

    /** Removed echo in dB over the last quarter, far taken `lag` samples back from the true alignment at each point. */
    private static double run(Aec aec, double[] mic, double[] far, int from, int to, int lag) {
        double in = 0;
        double out = 0;
        double[] o = new double[B];
        for (int blk = from / B; blk < to / B; blk++) {
            double[] m = java.util.Arrays.copyOfRange(mic, blk * B, blk * B + B);
            double[] f = new double[B];
            for (int i = 0; i < B; i++) {
                int at = blk * B + i - lag;
                f[i] = at >= 0 ? far[at] : 0;
            }
            aec.process(m, f, true, o);
            if (blk >= (to / B) - (to - from) / B / 4) {
                for (int i = 0; i < B; i++) {
                    in += m[i] * m[i];
                    out += o[i] * o[i];
                }
            }
        }
        return Signals.db(in / out);
    }

    @Test
    void keepsWhatItLearntWhenTheAlignmentMoves() {
        int n = 48_000 * 16;
        double[] far = Signals.speech(n, 1, 3000);
        double[] mic = Signals.convolve(far, Signals.room(480, 2880, 0.5, 2));
        Aec aec = new Aec(B, 12);
        double before = run(aec, mic, far, 0, n / 2, 0);
        // The far end now comes 200 samples further back; tell the canceller.
        double[] past = new double[aec.historyLength()];
        int end = (n / 2 / B) * B;
        for (int i = 0; i < past.length; i++) {
            int at = end - past.length + i - 200;
            past[i] = at >= 0 ? far[at] : 0;
        }
        aec.shift(200, past);
        double justAfter = run(aec, mic, far, n / 2, n / 2 + 48_000, 200);
        System.out.printf("before %.1f dB, first second after a 200-sample shift %.1f dB%n", before, justAfter);
        assertTrue(before > 25, "before " + before);
        assertTrue(justAfter > 20, "after " + justAfter);
    }
}
