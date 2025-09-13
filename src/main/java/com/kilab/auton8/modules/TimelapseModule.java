package com.kilab.auton8.modules;

import com.kilab.auton8.meteordummy.EventHandler;
import com.kilab.auton8.meteordummy.Module;
import com.kilab.auton8.meteordummy.TickEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Takes an F2-equivalent screenshot every N seconds while enabled.
 * Fabric / Yarn: 1.21.8 (uses the 5-arg saveScreenshot overload).
 */
public class TimelapseModule extends Module {
    // ===== Settings =====

/*    private final Setting<Double> everySeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("interval-seconds")
        .description("How often to capture a screenshot.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderRange(1.0, 120.0)
        .build());

    private final Setting<String> filenamePrefix = sgGeneral.add(new StringSetting.Builder()
        .name("filename-prefix")
        .description("Prefix for saved screenshot filenames.")
        .defaultValue("auton8_timelapse")
        .build());

    private final Setting<Boolean> toastOnSave = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-toast")
        .description("Show a small chat message each time a shot is saved.")
        .defaultValue(false)
        .build());*/

    final double everySeconds = 5;
    final String filenamePrefix = "auton8_timelapse";
    final boolean toastOnSave = true;

    // ===== Runtime =====
    private long lastShotMs = 0L;

    // Date-time suffix like 2025-09-03_12-34-56-123
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    public TimelapseModule() {
        //super(category, "Timelapse", "Periodically captures screenshots for a travel hyperlapse.");
    }

    @Override
    public void onActivate() {
        lastShotMs = 0L; // fire immediately on enable
    }

    @EventHandler
    private void onTick(TickEvent.Post e) {
        final MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        if (mc.player == null || mc.world == null) return; // only shoot in-world

        long now = System.currentTimeMillis();
        long intervalMs = Math.round(everySeconds * 1000.0);

        if (now - lastShotMs >= intervalMs) {
            lastShotMs = now;
            takeScreenshot(mc);
        }
    }

    private void takeScreenshot(MinecraftClient mc) {
        // Root game directory; ScreenshotRecorder will place files under "screenshots/"
        File gameDir = mc.runDirectory;

        // Compose filename: <prefix>_yyyy-mm-dd_HH-MM-SS-SSS.png
        String ts = LocalDateTime.now().format(TS);
        String fileName = filenamePrefix + "_" + ts + ".png";

        Framebuffer fb = mc.getFramebuffer();

        Consumer<Text> messageSink = toastOnSave ? (text) -> mc.inGameHud.getChatHud()
                .addMessage(text) : (text) -> {
        };

        // Ensure we run on the client thread
        mc.execute(() -> {
            try {
                // Fabric 1.21.8 overload with downscale factor
                ScreenshotRecorder.saveScreenshot(gameDir, fileName, fb, 1, messageSink);
            } catch (Throwable t) {
                if (toastOnSave) {
                    mc.inGameHud.getChatHud()
                            .addMessage(Text.literal("[Auton8] Timelapse: failed to save screenshot."));
                }
            }
        });
    }
}
