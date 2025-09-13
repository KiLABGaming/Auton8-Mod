package com.kilab.auton8.bridges;

import com.kilab.auton8.core.Config;
import com.kilab.auton8.mqtt.MqttBus;
import com.kilab.auton8.mqtt.MqttMessageHandler;

import java.util.concurrent.atomic.AtomicBoolean;

public final class HudBridge {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private final MqttBus bus;
    private final String hudTopic;

    public HudBridge(MqttBus bus, Config cfg) {
        this.bus = bus;
        this.hudTopic = cfg.hudTopicOrDefault(); // "mc/<clientId>/hud" by default
    }

    public void start() {
        // Register the HUD element only once per client session.
        if (REGISTERED.compareAndSet(false, true)) {
            //Hud.get().register(Auton8Hud.INFO);
        }

        // Subscribe to HUD updates (JSON → in-memory snapshot)
        bus.onMessage(hudTopic, (String topic, String payload) -> {
            //HudStatusStore.updateFromJson(payload)
        });
    }

    public void stop() {
        // Meteor HUD doesn't expose unregister; safe to leave registered.
    }
}
