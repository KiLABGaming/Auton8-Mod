package com.kilab.auton8.bridges;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kilab.auton8.core.Config;
import com.kilab.auton8.core.JsonUtils;
import com.kilab.auton8.mqtt.MqttBus;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatBridge implements Bridge {
    private final Config cfg;
    private final MqttBus bus;
    private final BaritoneBridge baritone;

    // Suppresses the local "#..." hook when WE (the mod) are the ones sending chat.
    public static final ThreadLocal<Boolean> SUPPRESS_LOCAL_BARITONE_HOOK =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    private boolean sendHook = false;
    private boolean recvHooks = false;

    private static final class Deduper {
        private final Map<String, Long> seen;
        private final int max;
        Deduper(int maxEntries) {
            this.max = maxEntries;
            this.seen = new LinkedHashMap<>(maxEntries, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
                    return size() > max;
                }
            };
        }
        boolean seenRecently(String key, long windowMs) {
            long now = System.currentTimeMillis();
            Long t = seen.get(key);
            if (t != null && (now - t) <= windowMs) return true;
            seen.put(key, now);
            return false;
        }
    }
    private final Deduper dedupe = new Deduper(256);
    private static final long DEDUPE_WINDOW_MS = 1500;

    private static final Pattern VANILLA = Pattern.compile("^<(.{1,32}?)>\\s(.*)$");
    private static final Pattern COLON   = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{2,16})[:>]\\s(.*)$");
    private static final Pattern ARROW   = Pattern.compile("^(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{2,16})\\s»\\s(.*)$");

    public ChatBridge(Config cfg, MqttBus bus, BaritoneBridge baritone) {
        this.cfg = cfg;
        this.bus = bus;
        this.baritone = baritone;
    }

    private static boolean isSingleplayer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return false;
        boolean integrated = mc.isIntegratedServerRunning();
        boolean noEntry = (mc.getCurrentServerEntry() == null);
        return integrated || noEntry;
    }
    private static boolean isMultiplayer() { return !isSingleplayer(); }

    private static String localUsername() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (mc != null && mc.getSession() != null) ? mc.getSession().getUsername() : "unknown";
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String noFmt = s.replaceAll("\\u00A7[0-9A-FK-ORa-fk-or]", "");
        return noFmt.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private void publishChat(String from, String msg, String source) {
        String f = (from == null ? "unknown" : from);
        String key = f.toLowerCase() + "|" + normalize(msg);
        if (dedupe.seenRecently(key, DEDUPE_WINDOW_MS)) return;
        bus.publish(cfg.evtTopic, JsonUtils.chatEvent(f, msg, source));
    }

    @Override
    public void enable() {
        if (!sendHook) {
            sendHook = true;
            ClientSendMessageEvents.CHAT.register(content -> {
                if (content == null || content.isBlank()) return;

                // If it's a Baritone line ("#..."), notify BaritoneBridge — unless we're in a programmatic send.
                if (content.startsWith("#")) {
                    if (!Boolean.TRUE.equals(SUPPRESS_LOCAL_BARITONE_HOOK.get()) && baritone != null) {
                        try { baritone.onLocalBaritoneCommand(content); } catch (Throwable ignored) {}
                    }
                    // No extra logs here; avoid spam.
                    return; // never mirror "#..." as plain chat
                }

                // Mirror plain chat only in SP (MP receive hooks cover it)
                if (cfg.allowChatTx && isSingleplayer()) {
                    publishChat(localUsername(), content, "client");
                }
            });
        }

        if (!recvHooks) {
            recvHooks = true;

            ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
                if (!cfg.allowChatRx) return;
                if (!isMultiplayer()) return;
                String text = message.getString();
                if (text == null || text.isBlank()) return;
                String from = (sender != null) ? sender.getName() : "unknown";
                publishChat(from, text, "server_chat");
            });

            ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
                if (!cfg.allowChatRx) return;
                if (!isMultiplayer()) return;
                if (overlay) return;

                String raw = message.getString();
                if (raw == null || raw.isBlank()) return;

                String from = null, msg = null;

                Matcher m = VANILLA.matcher(raw);
                if (m.matches()) { from = m.group(1); msg = m.group(2); }
                else {
                    Matcher m2 = COLON.matcher(raw);
                    if (m2.matches()) { from = m2.group(1); msg = m2.group(2); }
                    else {
                        Matcher m3 = ARROW.matcher(raw);
                        if (m3.matches()) { from = m3.group(1); msg = m3.group(2); }
                    }
                }

                if (msg != null) publishChat(from != null ? from : "unknown", msg, "server_sys");
                else publishChat("unknown", raw, "server_sys_raw");
            });
        }
    }

    @Override public void disable() { }

    @Override
    public void onCommand(String json) {
        try {
            JsonObject j = JsonParser.parseString(json).getAsJsonObject();
            String type = j.has("type") ? j.get("type").getAsString() : "";
            if ("say".equals(type) && cfg.allowChatTx) {
                String msgOut = j.has("msg") ? j.get("msg").getAsString() : null;
                if (msgOut == null || msgOut.isBlank()) return;
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    if (mc.player == null || mc.player.networkHandler == null) return;
                    // Suppress our own hook when sending programmatically.
                    SUPPRESS_LOCAL_BARITONE_HOOK.set(Boolean.TRUE);
                    try {
                        mc.player.networkHandler.sendChatMessage(msgOut);
                    } finally {
                        SUPPRESS_LOCAL_BARITONE_HOOK.set(Boolean.FALSE);
                    }
                    bus.publish(cfg.evtTopic, JsonUtils.baseEvent("said", msgOut));
                });
            }
        } catch (Exception ignored) {}
    }
}
