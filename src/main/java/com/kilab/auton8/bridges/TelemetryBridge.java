package com.kilab.auton8.bridges;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kilab.auton8.core.Config;
import com.kilab.auton8.core.JsonUtils;
import com.kilab.auton8.mqtt.MqttBus;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public final class TelemetryBridge implements Bridge {
    private final Config cfg;
    private final MqttBus bus;

    private boolean ticking = false;
    private long lastSent = 0L;

    // remember last dimension → so we can emit dimension_changed events
    private String lastDimension = null;

    public TelemetryBridge(Config cfg, MqttBus bus) {
        this.cfg = cfg;
        this.bus = bus;
    }

    @Override
    public void enable() {
        if (ticking) return;
        ticking = true;

        // Send one immediately so we have state right away
        publishTelemetryIfAvailable();
        lastSent = System.currentTimeMillis();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!ticking || !cfg.allowTelemetry) return;

            long now = System.currentTimeMillis();
            if (now - lastSent < cfg.telemetryIntervalMs) return;

            publishTelemetryIfAvailable();
            lastSent = now;
        });
    }

    @Override
    public void disable() {
        ticking = false;
    }

    @Override
    public void onCommand(String json) {
        try {
            JsonObject j = JsonParser.parseString(json).getAsJsonObject();
            String type = j.has("type") ? j.get("type").getAsString() : "";
            switch (type) {
                case "get_status" -> publishTelemetryIfAvailable();
                case "get_coords" -> publishCoordsOnly();
                default -> {}
            }
        } catch (Exception ignored) {}
    }

    // ===== Live update hooks (called by Auton8Core) =====

    /** Toggle telemetry emission at runtime. */
    public void setAllowTelemetry(boolean v) {
        cfg.allowTelemetry = v;
        // When re-enabling, push a fresh snapshot soon.
        if (v) lastSent = 0L;
    }

    /** Change emission interval at runtime. Triggers a near-immediate send on next tick. */
    public void setIntervalMs(int ms) {
        if (ms < 50) ms = 50;               // sanity floor
        cfg.telemetryIntervalMs = ms;
        // Make the next tick consider the interval already elapsed.
        lastSent = System.currentTimeMillis() - ms;
    }

    // ===== Internals =====

    private static String normalizeDimension(World world) {
        if (world == null) return "unknown";
        Identifier id = world.getRegistryKey().getValue(); // e.g. minecraft:overworld
        String path = id.getPath();                        // overworld | the_nether | the_end
        return switch (path) {
            case "overworld"   -> "overworld";
            case "the_nether"  -> "nether";
            case "the_end"     -> "end";
            default            -> path; // support modded dimensions
        };
    }

    private void publishTelemetryIfAvailable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        float health = mc.player.getHealth();
        HungerManager hm = mc.player.getHungerManager();
        int hunger = hm.getFoodLevel();
        float saturation = hm.getSaturationLevel();
        String dimension = normalizeDimension(mc.world);

        // dimension change detection
        if (lastDimension == null || !lastDimension.equals(dimension)) {
            lastDimension = dimension;
            bus.publish(cfg.evtTopic, JsonUtils.baseEvent("dimension_changed", dimension));
        }

        // telemetry snapshot
        bus.publish(cfg.evtTopic,
            JsonUtils.telemetry(x, y, z, health, hunger, saturation, dimension));
    }

    private void publishCoordsOnly() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        String dimension = normalizeDimension(mc.world);
        bus.publish(cfg.evtTopic,
            JsonUtils.coords(mc.player.getX(), mc.player.getY(), mc.player.getZ(), dimension));
    }
}
