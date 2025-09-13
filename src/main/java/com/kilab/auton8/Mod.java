package com.kilab.auton8;

import com.kilab.auton8.modules.MqttLinkModule;
import com.kilab.auton8.modules.TimelapseModule;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

public class Mod implements ModInitializer {
    @Override
    public void onInitialize() {

        var m1 = new MqttLinkModule();
        var m2 = new TimelapseModule();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (!(client.currentScreen instanceof TitleScreen)) return;

            if (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().world != null) {

            }

        });
    }
}
