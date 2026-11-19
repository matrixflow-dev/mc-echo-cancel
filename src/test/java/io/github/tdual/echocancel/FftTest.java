package io.github.tdual.echocancel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FftTest {

    @Test
    void matchesTheDefinitionAndInverts() {
        int n = 64;
        Random r = new Random(1);
        double[] re = new double[n];
        double[] im = new double[n];
        for (int i = 0; i < n; i++) {
            re[i] = r.nextGaussian();
            im[i] = r.nextGaussian();
        }
        double[] r0 = re.clone();
        double[] i0 = im.clone();
        Fft fft = new Fft(n);
        fft.forward(re, im);
        for (int k = 0; k < n; k++) {
            double sr = 0;
            double si = 0;
            for (int t = 0; t < n; t++) {
                double a = -2 * Math.PI * k * t / n;
                sr += r0[t] * Math.cos(a) - i0[t] * Math.sin(a);
                si += r0[t] * Math.sin(a) + i0[t] * Math.cos(a);
            }
            assertEquals(sr, re[k], 1e-9);
            assertEquals(si, im[k], 1e-9);
        }
        fft.inverse(re, im);
        for (int i = 0; i < n; i++) {
            assertEquals(r0[i], re[i], 1e-12);
            assertEquals(i0[i], im[i], 1e-12);
        }
    }

    @Test
    void rejectsSizesThatAreNotPowersOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new Fft(960));
    }
}
