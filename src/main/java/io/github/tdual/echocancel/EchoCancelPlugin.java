package io.github.tdual.echocancel;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple Voice Chat plugin (client side): learns what the speakers play and removes it from the
 * microphone before the microphone is sent. Settings are in config/echocancel.properties.
 */
public final class EchoCancelPlugin implements VoicechatPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("echocancel");
    /** How often the log reports how well the echo is removed, in microphone frames (60 s while the microphone is open). */
    private static final int REPORT_FRAMES = 3000;

    private final EchoCancelBridge bridge = new EchoCancelBridge();
    private boolean enabled = true;
    private int ticks;

    @Override
    public String getPluginId() {
        return "echocancel";
    }

    @Override
    public void initialize(VoicechatApi api) {
        Properties p = loadConfig(Path.of("config", "echocancel.properties"));
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
        bridge.setSuppress(Boolean.parseBoolean(p.getProperty("suppress_residual_echo", "true")));
        bridge.setGate(Boolean.parseBoolean(p.getProperty("drop_echo_only_frames", "false")));
        LOG.info("Echo cancellation is {}", enabled ? "on" : "off (config/echocancel.properties)");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, e -> played(e));
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, e -> played(e));
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, e -> played(e));
        registration.registerEvent(MergeClientSoundEvent.class, this::onTick);
        registration.registerEvent(ClientSoundEvent.class, this::onMicrophone);
    }

    private void played(ClientReceiveSoundEvent e) {
        if (enabled) {
            bridge.onPlayed(e.getId(), e.getRawAudio(), System.nanoTime());
        }
    }

    private void onTick(MergeClientSoundEvent e) {
        if (!enabled) {
            return;
        }
        bridge.onTick(System.nanoTime());
        if (++ticks % REPORT_FRAMES == 0) {
            double db = bridge.takeReductionDb();
            LOG.info("While others talked, sent audio was {} quieter than the microphone; echo delay {} ms;"
                    + " frames sent {}, not sent (only echo) {}",
                    Double.isNaN(db) ? "(nobody talked)" : String.format("%.1f dB", db),
                    bridge.delay() / 48, bridge.sentFrames(), bridge.gatedFrames());
        }
    }

    private void onMicrophone(ClientSoundEvent e) {
        if (!enabled) {
            return;
        }
        short[] out = bridge.onMicrophone(e.getRawAudio());
        if (out == null) {
            e.cancel();
        } else {
            e.setRawAudio(out);
        }
    }

    /** Reads the settings, writing the defaults first when the file does not exist. */
    static Properties loadConfig(Path file) {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("suppress_residual_echo", "true");
        p.setProperty("drop_echo_only_frames", "false");
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            } else {
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) {
                    p.store(out, "Voice Chat Echo Cancel. enabled: remove the echo of other players' voices."
                            + " suppress_residual_echo: also turn down what is left."
                            + " drop_echo_only_frames: send nothing while only echo is left.");
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not read {}: {}", file, e.getMessage());
        }
        return p;
    }
}
