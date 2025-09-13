package com.kilab.auton8.bridges;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kilab.auton8.core.Config;
import com.kilab.auton8.core.JsonUtils;
import com.kilab.auton8.mqtt.MqttBus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

public final class ServerChatBridge implements Bridge {
    private final Config cfg;
    private final MqttBus bus;

    // simple anti-spam (client-side), tweak if needed
    private static final long MIN_INTERVAL_MS = 300; // 0.3s
    private long lastSendMs = 0L;

    public ServerChatBridge(Config cfg, MqttBus bus) {
        this.cfg = cfg;
        this.bus = bus;
    }

    @Override
    public void enable() {
        // no periodic work needed, but keep pattern consistent
        ClientTickEvents.END_CLIENT_TICK.register(client -> { /* noop */ });
    }

    @Override
    public void disable() { }

    /**
     * Accepted payloads on cfg.cmdTopic:
     *   { "type":"server_chat", "text":"hello there" }
     *   { "type":"server_chat", "text":"/kill" }
     *   { "type":"server_chat", "to":"User", "message":"hi" }   // -> /msg User hi
     * Legacy (also accepted):
     *   { "type":"server_chat", "server_chat":"..." }
     */
    @Override
    public void onCommand(String json) {
        try {
            JsonObject j = JsonParser.parseString(json).getAsJsonObject();
            if (!j.has("type") || !"server_chat".equals(j.get("type").getAsString())) return;

            // Build outbound text
            String text = null;
            if (j.has("text")) {
                text = j.get("text").getAsString();
            } else if (j.has("server_chat")) { // legacy key
                text = j.get("server_chat").getAsString();
            } else if (j.has("to") && j.has("message")) { // helper -> whisper
                String to = j.get("to").getAsString();
                String msg = j.get("message").getAsString();
                text = "/msg " + to + " " + msg;
            }

            if (text == null) {
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("reject", "no_text"));
                return;
            }

            // light validation: strip newlines; keep size sane
            text = text.replace("\n", " ").replace("\r", " ").trim();
            if (text.isEmpty() || text.length() > 256) {
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("reject", "bad_text"));
                return;
            }

            // tiny client-side rate-limit so flows don't spam unintentionally
            long now = System.currentTimeMillis();
            if (now - lastSendMs < MIN_INTERVAL_MS) {
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("reject", "rate_limited"));
                return;
            }
            lastSendMs = now;

            final String send = text;
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player == null || mc.player.networkHandler == null) {
                    bus.publish(cfg.evtTopic, JsonUtils.baseEvent("reject", "no_player"));
                    return;
                }

                String s = send.trim();
                boolean isCommand = s.startsWith("/");

                try {
                    if (isCommand) {
                        // Fabric 1.21.x: commands use sendChatCommand WITHOUT the leading slash
                        mc.player.networkHandler.sendChatCommand(s.substring(1));
                    } else {
                        mc.player.networkHandler.sendChatMessage(s);
                    }
                } catch (Throwable t) {
                    // Fallback: at worst, send as plain chat (will show but might not execute)
                    mc.player.networkHandler.sendChatMessage(s);
                }

                // echo back what we sent (useful for n8n logs)
                JsonObject ack = new JsonObject();
                ack.addProperty("mode", isCommand ? "command" : "chat");
                ack.addProperty("echo", s);
                bus.publish(cfg.evtTopic, JsonUtils.wrap("accepted", ack));
            });

        } catch (Exception e) {
            bus.publish(cfg.evtTopic, JsonUtils.baseEvent("error", e.getClass().getSimpleName()));
        }
    }
}
