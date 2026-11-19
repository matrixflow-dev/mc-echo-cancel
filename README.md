# Voice Chat Echo Cancel

A Fabric client mod for Minecraft 26.3 that removes the echo of other players' voices from your microphone in [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat), so you can talk without headphones.

Without headphones, your microphone picks up what your speakers play. Everyone else then hears themselves a moment later. This mod learns how the sound from your speakers reaches your microphone and removes it before your voice is sent.

## Install

1. Install Fabric Loader and Simple Voice Chat (2.6 or later) for Minecraft 26.3.
2. Put `echocancel-fabric-<version>.jar` in the `mods` folder next to Simple Voice Chat.
3. Start the game. The log says `Echo cancellation is on`.

Only the players who use speakers need the mod. The server needs nothing.

## How it works

```
other players' voices ─► Simple Voice Chat ─► OpenAL ─► speakers ─► room ─► microphone
        │ ClientReceiveSoundEvent                                              │ ClientSoundEvent
        ▼                                                                      ▼
  far-end time line ──► delay estimator ──► aligned far end ──► linear canceller ──► suppressor ──► sent
```

The design follows the usual one for acoustic echo cancellation (WebRTC's AEC3 has the same stages): measure the delay, subtract a linear estimate of the echo, then turn down what is left.

1. **Far-end time line.** `ClientReceiveSoundEvent` gives each voice just before Simple Voice Chat hands it to OpenAL, at its volume before OpenAL applies volume, distance and direction. The mod notes when each frame arrived and writes it where Simple Voice Chat will play it: right after that voice's previous frame, or 5 frames later when the voice had run dry (Simple Voice Chat queues `output_buffer_size` frames of silence before starting a voice again). Whether a voice ran dry depends on when OpenAL started it, which is learnt from the measured delays; arrivals too close to call are treated as a voice whose delay must be measured again. Arrival times are put on the microphone's own sample count, using the earliest microphone tick (`MergeClientSoundEvent`) of the last 5 s. When the microphone stops for a while (a microphone test, for example), the count is anchored again.
2. **Delay estimator.** What is still unknown is the latency of the sound device, the moment OpenAL actually starts a voice, and the room. Every 100 ms the last 0.25 s of microphone is compared with the far end over delays up to 0.45 s, by phase-weighted cross-correlation (GCC-PHAT) at 16 kHz, refined to one sample at 48 kHz.
3. **Linear canceller.** A partitioned-block frequency-domain adaptive filter (512-sample blocks, 12 partitions: 128 ms of echo path after alignment) learns the echo path and subtracts its estimate. The linear canceller keeps two filters: a background filter that always learns, and a foreground filter that produces the output and takes the background filter's coefficients only while they remove clearly more echo. Talking over the echo therefore does not undo what was learnt. When the measured delay changes, the taps are moved by the same amount, so what was learnt about the room is kept. When subtracting would make a block louder than the microphone, the microphone is used as it is.
4. **Suppressor.** Band by band (512-sample frames, 256-sample hop), a band is turned down when it is not clearly louder than the echo expected there. While the delay is confirmed, the expected echo is what the linear canceller usually leaves. Right after a voice starts again, until its delay has been measured, the canceller may remove little; the expected echo is then the whole echo path gain, which needs no exact delay. The player's own speech then has to be louder than the echo to get through.

Everything is plain Java. There are no native libraries.

The microphone is delayed by 16 ms (768 samples).

## Settings

`config/echocancel.properties` is created on first start.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | true | Remove the echo of other players' voices |
| `suppress_residual_echo` | true | Also turn down, band by band, the echo the linear canceller left |
| `drop_echo_only_frames` | false | Send nothing for a frame that has only quiet echo left. It also drops some frames of your own speech while others talk, so it is off |

While the microphone is open, the log reports every 60 s how much quieter the sent audio was than the microphone while others talked, the measured echo delay, and how many frames were sent.

## Limits

- Only voice chat audio is removed. Game sounds and music from your speakers are not in the far end, so they still reach the microphone. Turn the game volume down if they trigger voice activation.
- One linear canceller is shared by all voices. When several players talk at once at different volumes or from different directions, it cannot model them together, and mostly the suppressor works (about 18 dB in the tests).
- Each time a voice starts again, its delay is measured anew (about half a second). Until then the suppressor works alone, and your own speech gets through only where it is louder than the echo.
- Packets that arrive later than Simple Voice Chat's output buffer (`output_buffer_size`, 5 packets or 100 ms by default) make a voice start again often, with the same effect. The more jitter, the more the result varies (see the table).
- For about one second after the microphone stops and starts again, little echo is removed.
- A voice is given to the mod even when it is out of hearing range, before Simple Voice Chat decides not to play it. Such frames make the suppressor expect echo that is not there.
- Simple Voice Chat's automatic gain control (`automatic_gain_control`) and denoiser (`denoiser`), both on by default, run before this mod and change the microphone in ways a linear canceller cannot model. The tests below simulate neither the denoiser nor real automatic gain control (only a slow ±6 dB gain change). If echo remains, try turning automatic gain control off.
- Simple Voice Chat itself will not add echo cancellation ([issue #904](https://github.com/henkelmax/simple-voice-chat/issues/904)). Its license does not allow modified copies, so this is a separate mod that uses only its public API.

## Build and test

```bash
./gradlew build    # build/libs/echocancel-fabric-<version>.jar
```

The mod uses no Minecraft classes, so it builds without Fabric Loom. It needs JDK 25 to build and runs on Java 21 or later.

`VoiceChatSimulationTest` plays speech-like signals through a simulated Simple Voice Chat client: network jitter, 5 frames of silence before a voice starts again, OpenAL starting a voice up to 10 ms late, 30 ms of device latency, a room with a 60 ms tail, and microphone ticks up to 5 ms late. Other players talk for 3 s and pause for 1 s. The first 8 s are not measured. There is no denoiser. Results:

| Case | Echo removed | Your speech |
|---|---|---|
| One voice, no jitter (30 s) | 32 dB | — |
| One voice, 10 ms jitter, 120 s, 4 seeds | 30 to 36 dB | — |
| One voice, 20 ms jitter, 120 s, 4 seeds | 23 to 32 dB | — |
| One voice, 30 ms jitter, 120 s, 4 seeds | 20 to 31 dB | — |
| Moving player (volume 0.2 to 1.0) | 34 dB | — |
| Microphone gain changing by ±6 dB | 26 dB | — |
| 200 ppm clock drift between microphone and speakers | 33 dB | — |
| Two voices at volumes 1.0 and 0.3 | 18 dB | — |
| From 1 s after the microphone stopped for 1.9 s | 55 dB | — |
| You talk over the echo (about as loud as the echo) | 30 dB | -3.7 dB, no frame lost |
| You talk alone | — | unchanged |

Simulating and processing 20 s of voice chat takes about 0.3 s (`fastEnoughForTheMicrophoneThread`).

## License

MIT
