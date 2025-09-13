package com.kilab.auton8.mqtt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kilab.auton8.core.Config;
import com.kilab.auton8.core.JsonUtils;
import org.eclipse.paho.client.mqttv3.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MqttBus {
    private final Config cfg;
    private MqttAsyncClient client;

    // topic -> handler
    private final ConcurrentHashMap<String, MqttMessageHandler> handlers = new ConcurrentHashMap<>();
    // topic -> qos (for auto re-subscribe)
    private final ConcurrentHashMap<String, Integer> subs = new ConcurrentHashMap<>();

    private volatile boolean announcedOnce = false;

    public MqttBus(Config cfg) {
        this.cfg = cfg;
    }

    /** Register a handler and subscribe now (and on reconnect). */
    public void onMessage(String topic, MqttMessageHandler handler) {
        handlers.put(topic, handler);
        subs.put(topic, 1);
        try {
            if (client != null && client.isConnected()) client.subscribe(topic, 1);
        } catch (Exception ignored) {}
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public void connect() {
        try {
            if (client != null && client.isConnected()) return;

            client = new MqttAsyncClient(cfg.brokerUri, cfg.clientId);

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setKeepAliveInterval(60);
            if (cfg.username != null && !cfg.username.isEmpty()) opts.setUserName(cfg.username);
            if (cfg.password != null && !cfg.password.isEmpty()) opts.setPassword(cfg.password.toCharArray());

            // LWT MUST include session_id so n8n can ignore stale sessions
            byte[] will = JsonUtils
                .baseEvent("status", "offline", cfg.sessionId)
                .getBytes(StandardCharsets.UTF_8);
            opts.setWill(cfg.evtTopic, will, 1, false);

            client.setCallback(new MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    try {
                        // re-subscribe everything
                        for (Map.Entry<String, Integer> e : subs.entrySet()) client.subscribe(e.getKey(), e.getValue());
                        if (!announcedOnce) {
                            publish(cfg.evtTopic, JsonUtils.baseEvent("status", "connected",   cfg.sessionId));
                            announcedOnce = true;
                        } else {
                            publish(cfg.evtTopic, JsonUtils.baseEvent("status", "reconnected", cfg.sessionId));
                        }
                    } catch (Exception ignored) {}
                }

                @Override public void connectionLost(Throwable cause) {
                    publish(cfg.evtTopic, JsonUtils.baseEvent("status", "connection_lost", cfg.sessionId));
                }

                @Override public void messageArrived(String topic, MqttMessage message) {
                    MqttMessageHandler h = handlers.get(topic);
                    if (h != null) {
                        String body = new String(message.getPayload(), StandardCharsets.UTF_8);
                        h.handle(topic, body);
                    }
                }

                @Override public void deliveryComplete(IMqttDeliveryToken token) { }
            });

            client.connect(opts).waitForCompletion(10_000);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Ensure session_id is present at top-level (and inside "message", if used). */
    private String ensureSession(String json) {
        if (cfg.sessionId == null || cfg.sessionId.isBlank()) return json;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return json;
            JsonObject obj = el.getAsJsonObject();

            if (!obj.has("session_id")) obj.addProperty("session_id", cfg.sessionId);
            // If you ever wrap payloads under { message: {...} }
            if (obj.has("message") && obj.get("message").isJsonObject()) {
                JsonObject msg = obj.getAsJsonObject("message");
                if (!msg.has("session_id")) msg.addProperty("session_id", cfg.sessionId);
            }
            return obj.toString();
        } catch (Exception ignore) {
            return json; // don't break publish if JSON parsing fails
        }
    }

    /** Async publish (fire-and-forget). */
    public void publish(String topic, String json) {
        try {
            if (client != null && client.isConnected()) {
                String body = ensureSession(json);
                client.publish(topic, body.getBytes(StandardCharsets.UTF_8), 1, false);
            }
        } catch (Exception ignored) {}
    }

    /** Sync publish: wait for QoS1 delivery (use before disconnect). */
    public void publishSync(String topic, String json, int timeoutMs) {
        try {
            if (client != null && client.isConnected()) {
                String body = ensureSession(json);
                IMqttDeliveryToken tok = client.publish(topic, body.getBytes(StandardCharsets.UTF_8), 1, false);
                if (tok != null) tok.waitForCompletion(Math.max(1, timeoutMs));
            }
        } catch (Exception ignored) {}
    }

    /** Graceful close: allow inflight messages to finish. */
    public void close() {
        try {
            if (client != null) {
                try { if (client.isConnected()) client.disconnect().waitForCompletion(1500); } catch (Exception ignored) {}
                try { client.close(); } catch (Exception ignored) {}
            }
        } finally {
            client = null;
        }
    }
}
