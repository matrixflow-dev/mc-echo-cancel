package io.github.tdual.echocancel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DelayEstimatorTest {

    static final int RATE = 48_000;

    private static int estimate(double[] mic, double[] far) {
        DelayEstimator d = new DelayEstimator();
        int frames = mic.length / EchoCanceller.FRAME;
        for (int i = 0; i < frames; i++) {
            d.add(Signals.frame(mic, i), Signals.frame(far, i));
        }
        DelayEstimator.Search s = d.prepare();
        if (s == null) {
            return -2;
        }
        DelayEstimator.Found f = s.run();
        return f == null ? -1 : f.delay();
    }

    @Test
    void findsTheDelayToTheSample() {
        double[] far = Signals.speech(RATE * 2, 1, 3000);
        for (int delay : new int[] {1000, 4801, 7213, 15000}) {
            double[] mic = Signals.add(Signals.convolve(far, Signals.room(delay, 2880, 0.4, 2)),
                    Signals.scale(Signals.speech(far.length, 8, 1), 20));
            int found = estimate(mic, far);
            // The room's strongest early reflection is within a few samples of its start.
            assertTrue(Math.abs(found - delay) <= 6, "delay " + delay + " found " + found);
        }
    }

    @Test
    void findsTheDelayWhileThePlayerTalks() {
        double[] far = Signals.speech(RATE * 2, 1, 3000);
        double[] near = Signals.speech(far.length, 5, 2000);
        double[] mic = Signals.add(Signals.convolve(far, Signals.room(6000, 2880, 0.4, 2)), near);
        int found = estimate(mic, far);
        assertTrue(Math.abs(found - 6000) <= 6, "found " + found);
    }

    @Test
    void findsNothingWhenThereIsNoEcho() {
        double[] far = Signals.speech(RATE * 2, 1, 3000);
        double[] mic = Signals.speech(far.length, 5, 2000);
        int found = estimate(mic, far);
        assertTrue(found < 0, "found " + found); // -1: no clear peak, -2: not worth searching

    }

    @Test
    void doesNotSearchWithoutEnoughMicrophone() {
        DelayEstimator d = new DelayEstimator();
        double[] far = Signals.speech(RATE, 1, 3000);
        for (int i = 0; i < far.length / EchoCanceller.FRAME; i++) {
            d.add(i % 10 == 0 ? null : new short[EchoCanceller.FRAME], Signals.frame(far, i));
        }
        assertEquals(null, d.prepare());
        assertNotNull(far);
    }
}
