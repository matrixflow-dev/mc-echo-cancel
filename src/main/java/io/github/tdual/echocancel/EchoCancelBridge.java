package io.github.tdual.echocancel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Connects Simple Voice Chat's events to the echo canceller, without depending on its API.
 *
 * <p>Each microphone frame first calls {@link #onTick} (every 20 ms while the microphone is open,
 * sent or not), then {@link #onMicrophone} if the frame is being sent. Microphone samples are
 * counted, and the tick times map wall-clock time onto that count.
 *
 * <p>Each voice that is about to be played arrives as frames on that voice's own thread
 * ({@link #onPlayed}), with the time it arrived. Each frame is written into a far-end time line,
 * in microphone samples, where Simple Voice Chat will play it: right after the voice's previous
 * frame, or, when the voice had run dry, after {@code outputBufferSize} frames of silence (what
 * {@code ALSpeakerBase.play} queues before starting again). What remains unknown is the latency
 * of the sound device and the room. {@link DelayEstimator} measures that from the echo itself,
 * and the far end given to the canceller is taken from that far back.
 */
final class EchoCancelBridge {

    static final int F = EchoCanceller.FRAME;
    static final long FRAME_NANOS = 20_000_000L;
    /** Frames of silence Simple Voice Chat queues when a voice starts again (its default output_buffer_size). */
    static final int START_SILENCE = 5;
    /**
     * Arrivals this close to where a voice runs dry could be either side: Simple Voice Chat may
     * have started it again or not (2 ms). Such a voice is measured again.
     */
    static final int AMBIGUOUS = 96;
    /** Delays measured lately, to find how late OpenAL started the current voice. */
    static final int RECENT_DELAYS = 32;
    /** Delay assumed before the first measurement: the sound device and the room (30 ms). */
    static final int DEFAULT_DELAY = 1440;
    /** The far end is taken this much less far back than the measured delay, so the filter has
     * room in front of the echo (10 ms). */
    static final int MARGIN = 480;
    /** Ticks between delay searches (100 ms). */
    static final int SEARCH_EVERY = 5;
    /** Two searches this close to each other count as the same delay. */
    static final int AGREE = 2;
    /** A search this sure, and this far from the delay in use, is used without waiting for a second one. */
    static final double SURE = 3 * DelayEstimator.CONFIDENCE;
    static final int SURE_JUMP = 96;
    /** A measured change smaller than this is left to the filter. */
    static final int MIN_SHIFT = 2;
    /** After a shift, the canceller relearns the finest detail of the echo path for this long (0.15 s). */
    static final int SETTLE = 7_200;
    /** Microphone samples back from the newest that are still read; older ones are cleared. */
    static final int KEPT = 48_000;
    /** Ticks over which the earliest one anchors time (5 s): the thread only ever wakes up late. */
    static final int ANCHOR_TICKS = 250;
    /** A tick this much later than expected means the microphone was stopped: time is anchored again. */
    static final long GAP_NANOS = 200_000_000L;
    /** A sent frame quieter than this (about -50 dBFS) while the far end plays... */
    static final double GATE_RMS = 100;
    /** ...and with less than this share of the microphone's energy left is only echo. */
    static final double GATE_SHARE = 0.03;

    /** Far end on the microphone's time line (2.8 s, whole frames). Guarded by itself. */
    private final double[] timeline = new double[F * 140];
    /** Where each voice's next frame plays, in microphone samples. Guarded by timeline. */
    private final Map<UUID, Long> playEnd = new HashMap<>();
    /** Wall-clock time of microphone sample 0, estimated from the ticks. Guarded by timeline. */
    private double origin = Double.NaN;
    /**
     * Latest microphone sample at which a voice started playing again, whose delay has not been
     * measured since; -1 when every voice's delay is known. Guarded by timeline.
     */
    private long unconfirmedFrom = 0;
    /** Microphone sample from which the canceller has had time to learn the current alignment. */
    private long settledFrom;

    private final EchoCanceller canceller = new EchoCanceller();
    private final DelayEstimator estimator = new DelayEstimator();
    private final ExecutorService searches;
    private Future<DelayEstimator.Found> search;
    /** Microphone sample at the end of the data the running search looks at. */
    private long searchEnd;

    /** Microphone samples counted so far (end of the current frame). */
    private long micSamples;
    private long lastTick = Long.MIN_VALUE;
    private final double[] anchors = new double[ANCHOR_TICKS];
    private int anchorCount;
    private int delay = DEFAULT_DELAY;
    private final int[] recentDelays = new int[RECENT_DELAYS];
    private int recentCount;
    /**
     * How much later than the earliest measured delay the current one is: OpenAL starts a voice
     * at its next mixing period, so this is how far past the computed end the voice really plays.
     * Guarded by timeline.
     */
    private int startLateness;
    private int candidate = -1;
    private long ticks;

    private short[] farThisTick;
    private short[] alignedThisTick;
    private boolean gate;

    // Counters for the log.
    private long sentFrames;
    private long gatedFrames;
    private double farIn;
    private double farOut;

    /** Searches for the delay on a background thread. */
    EchoCancelBridge() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "echocancel-delay");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }));
    }

    /** @param searches where delay searches run (a direct executor in tests) */
    EchoCancelBridge(ExecutorService searches) {
        this.searches = searches;
    }

    void setSuppress(boolean on) {
        canceller.setSuppress(on);
    }

    void setGate(boolean on) {
        gate = on;
    }

    /** The measured latency of the sound device and room, in samples at 48 kHz. */
    int delay() {
        return delay;
    }

    /**
     * A frame of a voice that is about to be played. Any thread.
     *
     * @param nanos when it arrived ({@link System#nanoTime()})
     */
    void onPlayed(UUID channel, short[] audio, long nanos) {
        if (audio == null || audio.length != F) {
            return; // An empty array marks the end of that voice.
        }
        synchronized (timeline) {
            if (Double.isNaN(origin)) {
                return; // The microphone is not open: nothing to cancel.
            }
            long now = Math.round((nanos - origin) * 48_000 / 1e9);
            Long end = playEnd.get(channel);
            // Simple Voice Chat starts a voice again when OpenAL has played everything queued.
            // OpenAL started this voice startLateness after the end computed here, so that is
            // when its queue runs dry.
            boolean restart;
            boolean unsure;
            if (end == null) {
                restart = true;
                unsure = true;
            } else {
                long dry = end + startLateness;
                restart = now > dry;
                unsure = Math.abs(now - dry) <= AMBIGUOUS;
            }
            long start = restart ? now + START_SILENCE * F : end;
            // A frame far ahead (the microphone is stopped, so its count does not move) would land
            // on samples not yet read back: keep track of where the voice is, but do not write it.
            if (start - micSamples <= timeline.length / 2) {
                for (int i = 0; i < F; i++) {
                    timeline[index(start + i)] += audio[i];
                }
            }
            playEnd.put(channel, start + F);
            if (restart || unsure) {
                // OpenAL may start it a little later than expected, or it may not have started
                // again at all: measure again.
                unconfirmedFrom = Math.max(unconfirmedFrom, Math.min(start, end == null ? start : end) + delay);
            }
        }
    }

    /**
     * Start of a microphone frame. Microphone thread.
     *
     * @param nanos now ({@link System#nanoTime()})
     */
    void onTick(long nanos) {
        if (farThisTick != null) {
            // The previous frame was not sent: only advance the histories.
            estimator.add(null, farThisTick);
            canceller.skip(alignedThisTick);
        }
        micSamples += F;
        updateDelay(); // Before reading this frame's far end, which depends on the delay.
        synchronized (timeline) {
            double anchored = nanos - micSamples * 1e9 / 48_000;
            boolean gap = lastTick != Long.MIN_VALUE && nanos - lastTick > GAP_NANOS;
            if (lastTick == Long.MIN_VALUE || gap) {
                anchorCount = 0;
            }
            if (gap) {
                // The microphone was stopped (a microphone test, for example) while voices went
                // on playing. Time is anchored again: move each voice's end to the new count, and
                // forget the far end written in the old count. Until the delay is measured again,
                // the suppressor works without it.
                long moved = Math.round((origin - anchored) * 48_000 / 1e9);
                playEnd.replaceAll((c, e) -> e + moved);
                java.util.Arrays.fill(timeline, 0);
                unconfirmedFrom = micSamples - F;
            }
            // The earliest tick of the last few seconds: late wake-ups do not move it, and the
            // window follows slow drift between the two clocks.
            anchors[anchorCount++ % ANCHOR_TICKS] = anchored;
            double earliest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < Math.min(anchorCount, ANCHOR_TICKS); i++) {
                earliest = Math.min(earliest, anchors[i]);
            }
            origin = earliest;
            lastTick = nanos;
            farThisTick = read(micSamples - F);
            alignedThisTick = read(micSamples - F - Math.max(0, delay - MARGIN));
            // Samples this old are never read again; clear them for the next turn of the ring.
            long old = micSamples - F - KEPT;
            for (int i = 0; i < F; i++) {
                timeline[index(old + i)] = 0;
            }
        }
    }

    /**
     * A microphone frame that is about to be sent. Microphone thread.
     *
     * @return the frame to send, or null to send nothing (only echo was left)
     */
    short[] onMicrophone(short[] mic) {
        if (mic == null || mic.length != F) {
            return mic;
        }
        if (farThisTick == null) {
            onTick(System.nanoTime()); // Called without a tick: treat it as one.
        }
        estimator.add(mic, farThisTick);
        boolean aligned;
        synchronized (timeline) {
            aligned = (unconfirmedFrom < 0 || micSamples <= unconfirmedFrom) && micSamples >= settledFrom;
        }
        canceller.setAligned(aligned);
        short[] out = canceller.process(mic, alignedThisTick);
        farThisTick = null;
        boolean far = canceller.farActive();
        if (far) {
            farIn += canceller.lastInEnergy();
            farOut += canceller.lastOutEnergy();
        }
        if (gate && far && rms(out) < GATE_RMS
                && canceller.lastOutEnergy() < GATE_SHARE * canceller.lastInEnergy()) {
            gatedFrames++;
            return null;
        }
        sentFrames++;
        return out;
    }

    /**
     * How much quieter the sent audio was than the microphone while others were talking, in dB,
     * since the last call. Includes the player's own speech. NaN when nobody else talked.
     */
    double takeReductionDb() {
        double db = farOut > 0 ? 10 * Math.log10(farIn / farOut) : Double.NaN;
        farIn = 0;
        farOut = 0;
        return db;
    }

    long sentFrames() {
        return sentFrames;
    }

    long gatedFrames() {
        return gatedFrames;
    }

    void close() {
        searches.shutdownNow();
    }

    private int index(long sample) {
        return (int) Math.floorMod(sample, (long) timeline.length);
    }

    private short[] read(long from) {
        short[] out = new short[F];
        for (int i = 0; i < F; i++) {
            double v = timeline[index(from + i)];
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
        }
        return out;
    }

    private void updateDelay() {
        ticks++;
        if (search != null && search.isDone()) {
            DelayEstimator.Found found;
            try {
                found = search.get();
            } catch (Exception e) {
                found = null;
            }
            search = null;
            accept(found);
        }
        if (search == null && ticks % SEARCH_EVERY == 0) {
            DelayEstimator.Search s = estimator.prepare();
            if (s != null) {
                searchEnd = micSamples - F;
                search = searches.submit(s::run);
            }
        }
    }

    private void accept(DelayEstimator.Found found) {
        if (found == null) {
            return;
        }
        int d = found.delay();
        long searchStart = searchEnd - (long) DelayEstimator.WINDOW * 3;
        if (Math.abs(d - delay) < MIN_SHIFT) {
            confirm(searchStart);
            remember(delay);
        }
        boolean agreed = candidate >= 0 && Math.abs(d - candidate) <= AGREE;
        boolean sure = found.confidence() >= SURE && Math.abs(d - delay) >= SURE_JUMP;
        candidate = d;
        if ((agreed || sure) && Math.abs(d - delay) >= MIN_SHIFT) {
            int shift = d - delay;
            delay = d;
            // The canceller has been given everything up to the end of the previous frame.
            long end = micSamples - F;
            int length = canceller.historyLength();
            double[] past = new double[length];
            synchronized (timeline) {
                long from = end - length - Math.max(0, delay - MARGIN);
                for (int i = 0; i < length; i++) {
                    past[i] = timeline[index(from + i)];
                }
            }
            canceller.shift(shift, past);
            synchronized (timeline) {
                settledFrom = micSamples + SETTLE;
            }
            confirm(searchStart);
            remember(delay);
        }
    }

    static double rms(short[] s) {
        double sum = 0;
        for (short v : s) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum / s.length);
    }

    /** The delay is now known for every voice that started playing before from. */
    private void confirm(long from) {
        synchronized (timeline) {
            if (unconfirmedFrom >= 0 && from >= unconfirmedFrom) {
                unconfirmedFrom = -1;
            }
        }
    }

    /** A delay that was measured and is in use: updates how late OpenAL started the current voice. */
    private void remember(int measured) {
        recentDelays[recentCount++ % RECENT_DELAYS] = measured;
        int earliest = Integer.MAX_VALUE;
        for (int i = 0; i < Math.min(recentCount, RECENT_DELAYS); i++) {
            earliest = Math.min(earliest, recentDelays[i]);
        }
        synchronized (timeline) {
            // At most one 20 ms period: anything more is not OpenAL's start.
            startLateness = Math.max(0, Math.min(F, measured - earliest));
        }
    }
}
