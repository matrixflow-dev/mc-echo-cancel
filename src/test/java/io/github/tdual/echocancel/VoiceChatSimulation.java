package io.github.tdual.echocancel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Plays other players' voices through a simulated Simple Voice Chat client, speakers and room,
 * and runs the microphone through {@link EchoCancelBridge} the way Simple Voice Chat calls it.
 *
 * <p>What it copies from Simple Voice Chat 26.3:
 * <ul>
 * <li>A voice's frames reach the client with network jitter, in order (its packet buffer).</li>
 * <li>{@code ClientReceiveSoundEvent} fires when a frame is handed to OpenAL, which is when it
 *     arrives; the bridge only sees it at the next microphone tick.</li>
 * <li>{@code ALSpeakerBase.play}: when the source's queue has run dry, 5 frames of silence are
 *     queued before the frame and playback starts again at that moment (not on a 20 ms
 *     boundary). Otherwise the frame plays right after the previous one.</li>
 * <li>The volume (and distance) is applied by OpenAL after the event, so the reference has none.</li>
 * <li>Voice activation decides from the raw microphone whether a frame is sent at all.</li>
 * </ul>
 */
final class VoiceChatSimulation {

    static final int RATE = 48_000;
    static final int F = EchoCanceller.FRAME;

    /** One other player. */
    static final class Voice {
        final double[] speech;
        final double gain;
        /** Talk for on seconds, pause for off seconds, starting at start seconds. */
        final double start;
        final double on;
        final double off;
        /** Mean of the exponential network jitter in ms. */
        final double jitterMs;
        /** Gain change over time (moving players): gain * (1 - swing/2 + swing/2 * sin). */
        final double swing;

        Voice(long seed, double gain, double start, double on, double off, double jitterMs, double swing, int seconds) {
            this.speech = Signals.speech(RATE * seconds, seed, 3000);
            this.gain = gain;
            this.start = start;
            this.on = on;
            this.off = off;
            this.jitterMs = jitterMs;
            this.swing = swing;
        }

        boolean talking(double t) {
            if (t < start) {
                return false;
            }
            double phase = (t - start) % (on + off);
            return phase < on;
        }
    }

    // Conditions.
    final List<Voice> voices = new ArrayList<>();
    int seconds = 30;
    long seed = 11;
    /** The microphone runs this much fast (clock drift between devices). */
    double driftPpm = 0;
    /** Slow microphone gain change like automatic gain control, in dB (peak). */
    double agcDb = 0;
    /** The player's own speech, or null. */
    double[] near;
    /** Sound device latency in samples (constant). */
    int outputLatency = 1440;
    /** OpenAL starts a source at the next mixing period, up to this many samples later. */
    int alPeriod = 480;
    /** How late the microphone thread wakes up after a frame is complete, up to (samples). */
    int maxTickJitter = 240;

    static long nanos(long samples) {
        return samples * 1_000_000_000L / RATE;
    }

    private long tickJitter(Random rnd) {
        return rnd.nextInt(maxTickJitter + 1);
    }

    /** The room: echo delay from the speakers to the microphone and its gain. */
    int roomDelay = 240;
    double roomGain = 0.5;
    boolean gate;
    boolean suppress = true;
    /** Seconds at the start not measured (learning). */
    double settle = 8;
    /** The microphone is stopped (no ticks at all, as during a microphone test) in [stopFrom, stopTo) seconds. */
    double stopFrom = -1;
    double stopTo = -1;
    /** Only measure in [measureFrom, measureTo) seconds when set. */
    double measureFrom = -1;
    double measureTo = -1;

    // Results.
    double echoIn;
    double echoOut;
    double nearIn;
    double nearOut;
    int nearFrames;
    int nearFramesNotSent;
    int echoFramesSent;
    int echoFrames;

    VoiceChatSimulation run() {
        Random rnd = new Random(seed);
        int n = RATE * seconds;
        double[] speakers = new double[n + RATE];
        List<long[]> arrivals = new ArrayList<>(); // [time, voice, frame start in speech]

        // Network and OpenAL.
        for (int v = 0; v < voices.size(); v++) {
            Voice voice = voices.get(v);
            long lastArrival = 0;
            double playEnd = -1;
            for (int k = 0; (k + 1) * F < n; k++) {
                double sendTime = (double) k * F / RATE;
                if (!voice.talking(sendTime)) {
                    continue;
                }
                double jitter = -Math.log(1 - rnd.nextDouble()) * voice.jitterMs / 1000;
                long arrival = Math.max(lastArrival, Math.round((sendTime + 0.03 + jitter) * RATE));
                lastArrival = arrival;
                arrivals.add(new long[] {arrival, v, (long) k * F});
                // playEnd is when OpenAL has mixed everything queued (its queue is then empty);
                // the sound reaches the speakers outputLatency after mixing.
                double mixAt;
                if (playEnd < arrival) {
                    // Queue ran dry: 5 frames of silence, then this frame, from the next
                    // OpenAL mixing period.
                    mixAt = arrival + 5 * F + rnd.nextInt(alPeriod);
                } else {
                    mixAt = playEnd;
                }
                playEnd = mixAt + F;
                double at = mixAt + outputLatency;
                for (int i = 0; i < F; i++) {
                    int t = (int) at + i;
                    if (t < speakers.length) {
                        double time = (double) t / RATE;
                        double g = voice.gain * (1 - voice.swing / 2 + voice.swing / 2 * Math.sin(2 * Math.PI * time / 7));
                        speakers[t] += g * voice.speech[k * F + i];
                    }
                }
            }
        }
        arrivals.sort((a, b) -> Long.compare(a[0], b[0]));

        // Room and microphone (with drift: the microphone samples the room at t * (1 + drift)).
        double[] echoAtRoom = Signals.fastConvolve(speakers, Signals.room(roomDelay, 2880, roomGain, seed + 1));
        double[] echo = new double[n];
        double[] nearMic = new double[n];
        for (int t = 0; t < n; t++) {
            double src = t * (1 + driftPpm * 1e-6);
            int i = (int) src;
            double frac = src - i;
            double v = i + 1 < echoAtRoom.length ? echoAtRoom[i] * (1 - frac) + echoAtRoom[i + 1] * frac : 0;
            double g = Math.pow(10, agcDb * Math.sin(2 * Math.PI * t / (4.0 * RATE)) / 20);
            echo[t] = v * g;
            nearMic[t] = near != null ? near[t] * g : 0;
        }
        double[] mic = Signals.add(echo, nearMic);

        // Simple Voice Chat client.
        EchoCancelBridge bridge = new EchoCancelBridge(new Direct());
        bridge.setGate(gate);
        bridge.setSuppress(suppress);
        UUID[] ids = new UUID[voices.size()];
        for (int v = 0; v < ids.length; v++) {
            ids[v] = UUID.randomUUID();
        }
        int next = 0;
        int hold = 0;
        int delay = EchoCanceller.DELAY;
        double[] out = new double[n + delay + F];
        boolean[] sent = new boolean[n / F + 2];
        for (int j = 0; j < n / F; j++) {
            long tickTime = (long) (j + 1) * F;
            while (next < arrivals.size() && arrivals.get(next)[0] <= tickTime) {
                long[] a = arrivals.get(next++);
                short[] frame = Signals.frame(voices.get((int) a[1]).speech, (int) (a[2] / F));
                bridge.onPlayed(ids[(int) a[1]], frame, nanos(a[0]));
            }
            // The microphone thread wakes up a little after the frame is complete.
            double tickSeconds = (double) tickTime / RATE;
            if (tickSeconds >= stopFrom && tickSeconds < stopTo) {
                continue; // No microphone frames at all.
            }
            bridge.onTick(nanos(tickTime + tickJitter(rnd)));
            short[] frame = Signals.frame(mic, j);
            // Voice activation on the raw microphone (-50 dBFS), with a short hold.
            hold = EchoCancelBridge.rms(frame) > 100 ? 5 : hold - 1;
            if (hold > 0) {
                short[] o = bridge.onMicrophone(frame);
                if (o != null) {
                    sent[j] = true;
                    for (int i = 0; i < F; i++) {
                        int at = j * F + i - delay;
                        if (at >= 0) {
                            out[at] = o[i];
                        }
                    }
                }
            }
        }

        // Measure, frame by frame on the input's time line.
        int from = (int) ((measureFrom >= 0 ? measureFrom : settle) * RATE);
        int to = measureTo >= 0 ? (int) (measureTo * RATE) : n - 2 * F;
        for (int j = from / F; j < to / F; j++) {
            double e = 0;
            double nr = 0;
            double o = 0;
            for (int i = 0; i < F; i++) {
                int t = j * F + i;
                e += echo[t] * echo[t];
                nr += nearMic[t] * nearMic[t];
                o += out[t] * out[t];
            }
            boolean outSent = o > 0;
            if (nr > F * 300.0 * 300.0) {
                nearFrames++;
                nearIn += nr;
                nearOut += o;
                if (!outSent) {
                    nearFramesNotSent++;
                }
            } else if (nr == 0 && e > F * 100.0 * 100.0) {
                echoFrames++;
                echoIn += e;
                echoOut += o;
                if (outSent) {
                    echoFramesSent++;
                }
            }
        }
        lastDelay = bridge.delay();
        return this;
    }

    int lastDelay;

    /** How much quieter the echo became, counting frames not sent as silent. */
    double echoRemovedDb() {
        return echoOut == 0 ? 99 : Signals.db(echoIn / echoOut);
    }

    /** Level of what was sent while the player talked, against their speech alone. */
    double nearKeptDb() {
        return Signals.db(nearOut / nearIn);
    }

    String summary(String name) {
        return String.format("%s: echo removed %.1f dB (%d of %d echo-only frames sent); "
                        + "near end %+.1f dB, %d of %d near-end frames not sent; delay %d",
                name, echoRemovedDb(), echoFramesSent, echoFrames, nearFrames == 0 ? 0 : nearKeptDb(),
                nearFramesNotSent, nearFrames, lastDelay);
    }
}
