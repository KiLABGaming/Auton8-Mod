package com.kilab.auton8.core;

public class Config {
    // MQTT
    public String brokerUri;
    public String clientId;
    public String username;
    public String password;
    public String cmdTopic;
    public String evtTopic;

    // Session — new run identifier (set on module enable)
    public String sessionId;   // e.g., UUID string

    // HUD topic (optional override)
    public String hudTopic;  // e.g. "mc/kilab-pc1/hud"

    // Where BaritoneBridge publishes snapshots
    public String stateTopicBaritone;

    // Scopes
    public boolean allowChatRx;
    public boolean allowChatTx;
    public boolean allowTelemetry;
    public boolean allowBaritone;

    // Telemetry
    public int telemetryIntervalMs = 5000;

    public Config copy() {
        Config c = new Config();
        c.brokerUri = brokerUri;
        c.clientId = clientId;
        c.username = username;
        c.password = password;
        c.cmdTopic = cmdTopic;
        c.evtTopic = evtTopic;

        c.sessionId = sessionId;                 // NEW: copy session id

        c.hudTopic = hudTopic;
        c.stateTopicBaritone = stateTopicBaritone;

        c.allowChatRx = allowChatRx;
        c.allowChatTx = allowChatTx;
        c.allowTelemetry = allowTelemetry;
        c.allowBaritone = allowBaritone;

        c.telemetryIntervalMs = telemetryIntervalMs;
        return c;
    }

    // Defaults
    public String hudTopicOrDefault() {
        return (hudTopic != null && !hudTopic.isBlank())
            ? hudTopic
            : "mc/" + clientId + "/hud";
    }

    public String baritoneStateTopicOrDefault() {
        return (stateTopicBaritone != null && !stateTopicBaritone.isBlank())
            ? stateTopicBaritone
            : (evtTopic + "/baritone_state");
    }
}
