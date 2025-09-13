package com.kilab.auton8.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kilab.auton8.bridges.*;
import com.kilab.auton8.mqtt.MqttBus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

public final class Auton8Core {
    private final Config cfg;
    private final MqttBus bus;

    private final ChatBridge chatBridge;
    private final BaritoneBridge baritoneBridge;
    private final PlayerBridge playerBridge;
    private final TelemetryBridge telemetryBridge;
    private final ConnectionBridge connectionBridge;
    private final ServerChatBridge serverChatBridge;
    private final HudBridge hudBridge;
    private final LifeBridge lifeBridge;

    public Auton8Core(Config cfg) {
        // Keep a copy so runtime edits are local to this core instance
        this.cfg = cfg.copy();
        this.bus = new MqttBus(this.cfg);

        // Create Baritone first so ChatBridge can forward local "#..." lines to it
        this.baritoneBridge   = new BaritoneBridge(this.cfg, bus);

        // ChatBridge now receives the baritone reference (new ctor)
        this.chatBridge       = new ChatBridge(this.cfg, bus, baritoneBridge);

        this.playerBridge     = new PlayerBridge(this.cfg, bus);
        this.telemetryBridge  = new TelemetryBridge(this.cfg, bus);
        this.connectionBridge = new ConnectionBridge(this.cfg, bus);
        this.serverChatBridge = new ServerChatBridge(this.cfg, bus);
        this.hudBridge        = new HudBridge(bus, this.cfg);
        this.lifeBridge       = new LifeBridge(this.cfg, bus);

        // Fan out commands arriving on /cmd to relevant bridges.
        bus.onMessage(this.cfg.cmdTopic, (topic, json) -> {
            chatBridge.onCommand(json);
            baritoneBridge.onCommand(json);
            telemetryBridge.onCommand(json);
            serverChatBridge.onCommand(json);
        });

        // Listen for connection/session events so we can reset Baritone's plan queue
        bus.onMessage(this.cfg.evtTopic, (topic, json) -> {
            try {
                JsonObject j = JsonParser.parseString(json).getAsJsonObject();

                // Two shapes are emitted by ConnectionBridge:
                // 1) {type:"session_start", ...}
                // 2) {type:"status", value:"connected", reset:true, ...}
                String type  = j.has("type")  ? j.get("type").getAsString() : "";
                String value = j.has("value") ? j.get("value").getAsString() : "";
                boolean reset = j.has("reset") && j.get("reset").getAsBoolean();

                if ("session_start".equals(type) || ("status".equals(type) && "connected".equals(value) && reset)) {
                    baritoneBridge.resetPlanOnSessionStart();
                }
            } catch (Throwable ignored) { /* never break on event parsing */ }
        });
    }

    public void enable() {
        bus.connect();

        // Fresh plan/queue for this runtime stretch
        baritoneBridge.resetPlanOnSessionStart();

        // --- Announce a brand-new session so n8n can hard-reset state ---
        emitSessionStart();
        // Optional: push one snapshot so n8n immediately sees the new session_id on data
        try { requestOneShotTelemetry(); } catch (Throwable ignored) {}

        // --- Hard stop any stale Baritone path (route via cmd topic) ---
        String cancel = "{\"type\":\"baritone_cmd\",\"cmd\":\"#cancel\",\"session_id\":\""
            + (cfg.sessionId == null ? "" : cfg.sessionId)
            + "\",\"ts\":" + System.currentTimeMillis() + "}";
        bus.publish(cfg.cmdTopic, cancel);

        // Bring up bridges
        connectionBridge.enable();
        chatBridge.enable();          // registers chat hooks (needed to catch local "#...")
        baritoneBridge.enable();      // registers tick loop
        playerBridge.enable();
        telemetryBridge.enable();
        serverChatBridge.enable();
        hudBridge.start();
        lifeBridge.enable();
    }

    public void disable() {
        // Stop bridges first
        chatBridge.disable();
        baritoneBridge.disable();
        playerBridge.disable();
        telemetryBridge.disable();
        serverChatBridge.disable();
        hudBridge.stop();
        lifeBridge.disable();

        // Tell n8n the session is ending (sync so it lands before disconnect)
        try { emitSessionEnd(); } catch (Throwable ignored) {}

        bus.close();
    }

    /* =========================
       Session announcement API
       ========================= */

    /** Publish {event:"session_start", session_id, client_id, ts}. */
    public void emitSessionStart() {
        JsonObject o = new JsonObject();
        o.addProperty("event", "session_start");
        if (cfg.clientId != null)  o.addProperty("client_id", cfg.clientId);
        if (cfg.sessionId != null) o.addProperty("session_id", cfg.sessionId);
        o.addProperty("ts", System.currentTimeMillis());
        bus.publish(cfg.evtTopic, o.toString());
    }

    /** Publish {event:"session_end", session_id, client_id, ts} (QoS1 blocking). */
    public void emitSessionEnd() {
        JsonObject o = new JsonObject();
        o.addProperty("event", "session_end");
        if (cfg.clientId != null)  o.addProperty("client_id", cfg.clientId);
        if (cfg.sessionId != null) o.addProperty("session_id", cfg.sessionId);
        o.addProperty("ts", System.currentTimeMillis());
        bus.publishSync(cfg.evtTopic, o.toString(), 750);
    }

    /** Send one minimal telemetry sample so the new session_id shows up in data immediately. */
    public void requestOneShotTelemetry() {
        JsonObject out = new JsonObject();
        out.addProperty("event", "telemetry");
        if (cfg.clientId != null)  out.addProperty("client_id", cfg.clientId);
        if (cfg.sessionId != null) out.addProperty("session_id", cfg.sessionId);
        out.addProperty("ts", System.currentTimeMillis());

        JsonObject obs = new JsonObject();
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) {
                if (mc.world != null) {
                    String dim = mc.world.getRegistryKey().getValue().toString(); // e.g., "minecraft:overworld"
                    obs.addProperty("dimension", dim);
                }
                PlayerEntity p = mc.player;
                if (p != null) {
                    JsonObject coords = new JsonObject();
                    coords.addProperty("x", p.getX());
                    coords.addProperty("y", p.getY());
                    coords.addProperty("z", p.getZ());
                    obs.add("coords", coords);
                    obs.addProperty("health", (double) p.getHealth());
                    obs.addProperty("hunger", p.getHungerManager().getFoodLevel());
                    obs.addProperty("saturation", p.getHungerManager().getSaturationLevel());
                }
            }
        } catch (Throwable ignored) { /* never fail on telemetry */ }

        out.add("obs", obs);
        bus.publish(cfg.evtTopic, out.toString());
    }

    /* =========================
       Live-setting update API
       ========================= */

    /** Enable / disable publishing received chat to MQTT */
    public void setAllowChatRx(boolean v) {
        cfg.allowChatRx = v;
        // if needed: chatBridge.setAllowRx(v);
    }

    /** Enable / disable sending chat from MQTT to the server */
    public void setAllowChatTx(boolean v) {
        cfg.allowChatTx = v;
        // if needed: chatBridge.setAllowTx(v);
    }

    /** Enable / disable telemetry publishing */
    public void setAllowTelemetry(boolean v) {
        cfg.allowTelemetry = v;
        try { telemetryBridge.setAllowTelemetry(v); } catch (Throwable ignored) {}
    }

    /** Enable / disable acceptance of Baritone commands from MQTT */
    public void setAllowBaritone(boolean v) {
        cfg.allowBaritone = v;
        // if needed: baritoneBridge.setEnabled(v);
    }

    /** Update telemetry cadence in milliseconds. Also nudges the bridge if it has an internal scheduler. */
    public void setTelemetryIntervalMs(int ms) {
        if (ms < 50) ms = 50; // basic safety
        cfg.telemetryIntervalMs = ms;
        try { telemetryBridge.setIntervalMs(ms); } catch (Throwable ignored) {}
    }
}
