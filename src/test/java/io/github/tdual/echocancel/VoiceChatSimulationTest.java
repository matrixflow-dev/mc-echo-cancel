package io.github.tdual.echocancel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The conditions an adversarial review found to break a canceller that trusts a fixed delay:
 * restarts of playback at random moments, network jitter, several voices at different volumes,
 * moving players, automatic gain control and clock drift.
 */
class VoiceChatSimulationTest {

    private static VoiceChatSimulation oneVoice(double jitterMs) {
        VoiceChatSimulation s = new VoiceChatSimulation();
        s.voices.add(new VoiceChatSimulation.Voice(1, 1.0, 0.5, 3, 1, jitterMs, 0, s.seconds));
        return s;
    }

    private static void check(VoiceChatSimulation s, String name, double minRemoved) {
        s.run();
        System.out.println(s.summary(name));
        assertTrue(s.echoRemovedDb() >= minRemoved, name + ": echo removed only " + s.echoRemovedDb() + " dB");
    }

    @Test
    void oneVoiceTalkingInBursts() {
        check(oneVoice(0), "bursts, no jitter", 30);
    }

    @Test
    void networkJitter() {
        check(oneVoice(10), "bursts, 10 ms jitter", 30);
        // Packets late beyond the 100 ms playback buffer make the voice start again often.
        check(oneVoice(30), "bursts, 30 ms jitter", 12);
    }

    @Test
    void twoVoicesAtDifferentVolumes() {
        VoiceChatSimulation s = new VoiceChatSimulation();
        s.voices.add(new VoiceChatSimulation.Voice(1, 1.0, 0.5, 3, 1, 10, 0, s.seconds));
        s.voices.add(new VoiceChatSimulation.Voice(2, 0.3, 1.7, 2.5, 1.5, 10, 0, s.seconds));
        // One filter cannot follow two voices with different delays and volumes: mostly suppression.
        check(s, "two voices, 1.0 and 0.3", 15);
    }

    @Test
    void movingPlayer() {
        VoiceChatSimulation s = new VoiceChatSimulation();
        s.voices.add(new VoiceChatSimulation.Voice(1, 1.0, 0.5, 3, 1, 10, 0.8, s.seconds));
        check(s, "volume 0.2-1.0 (moving)", 30);
    }

    @Test
    void automaticGainControl() {
        VoiceChatSimulation s = oneVoice(10);
        s.agcDb = 6;
        check(s, "microphone gain +-6 dB", 22);
    }

    @Test
    void clockDrift() {
        VoiceChatSimulation s = oneVoice(10);
        s.driftPpm = 200;
        check(s, "200 ppm drift", 30);
    }

    @Test
    void playerTalksOverOthers() {
        VoiceChatSimulation s = oneVoice(10);
        s.near = Signals.speechIn(VoiceChatSimulation.RATE * s.seconds, 5, 1500,
                new double[][] {{10, 12.5}, {16, 18}, {22.3, 25}});
        check(s, "double talk", 25);
        double notSent = (double) s.nearFramesNotSent / s.nearFrames;
        assertTrue(s.nearKeptDb() > -6, "near end at " + s.nearKeptDb() + " dB");
        assertTrue(s.nearFramesNotSent == 0, "near-end frames not sent: " + notSent);
    }

    @Test
    void quietPlayerTalksOverOthers() {
        VoiceChatSimulation s = oneVoice(10);
        // Still louder at the microphone than the echo (about 1100), as a player near it is.
        s.near = Signals.speechIn(VoiceChatSimulation.RATE * s.seconds, 5, 800,
                new double[][] {{10, 12.5}, {16, 18}, {22.3, 25}});
        s.run();
        System.out.println(s.summary("double talk, quiet player"));
        double notSent = (double) s.nearFramesNotSent / Math.max(1, s.nearFrames);
        assertTrue(s.nearFramesNotSent == 0, "near-end frames not sent: " + notSent);
        assertTrue(s.nearKeptDb() > -6, "near end at " + s.nearKeptDb() + " dB");
    }

    @Test
    void droppingEchoOnlyFramesCostsNearEndFrames() {
        VoiceChatSimulation s = oneVoice(10);
        s.gate = true;
        s.near = Signals.speechIn(VoiceChatSimulation.RATE * s.seconds, 5, 1500,
                new double[][] {{10, 12.5}, {16, 18}, {22.3, 25}});
        s.run();
        System.out.println(s.summary("double talk, echo-only frames dropped"));
        assertTrue(s.echoRemovedDb() >= 25);
    }

    @Test
    void playerAloneIsNotTouched() {
        VoiceChatSimulation s = new VoiceChatSimulation();
        s.near = Signals.speechIn(VoiceChatSimulation.RATE * s.seconds, 5, 1500, new double[][] {{9, 20}});
        s.run();
        System.out.println(s.summary("player alone"));
        assertTrue(Math.abs(s.nearKeptDb()) < 0.5, "near end at " + s.nearKeptDb() + " dB");
        assertTrue(s.nearFramesNotSent == 0, "frames not sent: " + s.nearFramesNotSent);
    }

    /** Two minutes, several random seeds: the spread, not one lucky run. */
    @Test
    void longRunsWithDifferentSeeds() {
        double[][] cases = {{10, 28}, {20, 20}, {30, 17}};
        for (double[] c : cases) {
            StringBuilder line = new StringBuilder(String.format("120 s, %.0f ms jitter, seeds 1-4:", c[0]));
            for (long seed = 1; seed <= 4; seed++) {
                VoiceChatSimulation s = new VoiceChatSimulation();
                s.seconds = 120;
                s.seed = seed;
                s.voices.add(new VoiceChatSimulation.Voice(seed, 1.0, 0.5, 3, 1, c[0], 0, s.seconds));
                s.run();
                line.append(String.format(" %.1f dB", s.echoRemovedDb()));
                assertTrue(s.echoRemovedDb() >= c[1], line + " (below " + c[1] + ")");
            }
            System.out.println(line);
        }
    }

    @Test
    void recoversAfterTheMicrophoneStops() {
        VoiceChatSimulation s = new VoiceChatSimulation();
        s.voices.add(new VoiceChatSimulation.Voice(1, 1.0, 0.5, 30, 0, 10, 0, s.seconds));
        s.stopFrom = 12;
        s.stopTo = 13.9;
        s.measureFrom = 14.9;
        s.measureTo = 20;
        s.run();
        System.out.println(s.summary("1 s after a 1.9 s microphone stop"));
        assertTrue(s.echoRemovedDb() >= 35, "echo removed only " + s.echoRemovedDb() + " dB");
    }

    @Test
    void fastEnoughForTheMicrophoneThread() {
        VoiceChatSimulation s = oneVoice(10);
        s.seconds = 20;
        s.run(); // warm up
        long t0 = System.nanoTime();
        s.run();
        double seconds = (System.nanoTime() - t0) / 1e9;
        // Includes simulating the room; the mod's own share is smaller.
        System.out.printf("20 s of voice chat simulated and processed in %.2f s%n", seconds);
        assertTrue(seconds < 4, "took " + seconds + " s");
    }
}
