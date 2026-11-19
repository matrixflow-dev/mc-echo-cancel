package io.github.tdual.echocancel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SuppressorTest {

    @Test
    void passesTheMicrophoneUnchangedWithoutFarEnd() {
        Suppressor s = new Suppressor();
        double[] mic = Signals.speech(48_000, 3, 2000);
        double[] far = new double[Suppressor.HOP];
        double[] out = new double[mic.length];
        double[] hop = new double[Suppressor.HOP];
        double[] o = new double[Suppressor.HOP];
        for (int h = 0; h + Suppressor.HOP <= mic.length; h += Suppressor.HOP) {
            System.arraycopy(mic, h, hop, 0, Suppressor.HOP);
            s.process(hop, hop, far, true, true, o);
            System.arraycopy(o, 0, out, h, Suppressor.HOP);
        }
        for (int i = Suppressor.HOP; i < mic.length - Suppressor.HOP; i++) {
            assertEquals(mic[i - Suppressor.HOP], out[i], 1e-6);
        }
    }
}
