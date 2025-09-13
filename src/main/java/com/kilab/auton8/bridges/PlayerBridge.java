package com.kilab.auton8.bridges;

import com.kilab.auton8.core.Config;
import com.kilab.auton8.core.JsonUtils;
import com.kilab.auton8.mqtt.MqttBus;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.*;

public final class PlayerBridge implements Bridge {
    private final Config cfg;
    private final MqttBus bus;

    /** players inside 128 radius (with hysteresis) last tick */
    private final Set<UUID> inside = new HashSet<>();
    /** players inside 15-block danger zone (with hysteresis) last tick */
    private final Set<UUID> dangerInside = new HashSet<>();

    /** last known snapshot so we can emit "left" (including unload/vanish) */
    private static final class Snap {
        final String name; final double x, y, z, dist;
        Snap(String n, double x, double y, double z, double d) {
            this.name = n; this.x = x; this.y = y; this.z = z; this.dist = d;
        }
    }
    private final Map<UUID, Snap> last = new HashMap<>();

    private boolean ticking = false;

    // ---- outer render radius (enter/exit with hysteresis)
    private static final double RADIUS_ENTER = 128.0;
    private static final double EXIT_FACTOR = 1.12;                 // ~+12% to avoid edge flicker
    private static final double RADIUS_EXIT  = RADIUS_ENTER * EXIT_FACTOR;
    private static final double R2_ENTER = RADIUS_ENTER * RADIUS_ENTER;
    private static final double R2_EXIT  = RADIUS_EXIT  * RADIUS_EXIT;

    // ---- inner “danger” radius (15 blocks) + hysteresis
    private static final double DANGER_ENTER = 15.0;
    private static final double DANGER_EXIT  = DANGER_ENTER * 1.15; // ~17.25
    private static final double D2_DANGER_ENTER = DANGER_ENTER * DANGER_ENTER;
    private static final double D2_DANGER_EXIT  = DANGER_EXIT  * DANGER_EXIT;

    public PlayerBridge(Config cfg, MqttBus bus) {
        this.cfg = cfg;
        this.bus = bus;
    }

    @Override public void enable() {
        if (ticking) return;
        ticking = true;

        // reset state on world (re)connect/disconnect
        Runnable reset = () -> { inside.clear(); dangerInside.clear(); last.clear(); };
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> reset.run());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset.run());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!ticking) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null || mc.player == null) return;

            var me = mc.player;

            // Build next state FIRST (no publishing in this loop)
            Set<UUID> nextInside = new HashSet<>();
            Set<UUID> nextDanger = new HashSet<>();
            Set<UUID> presentNow = new HashSet<>();

            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == me) continue;

                UUID id = p.getUuid();
                presentNow.add(id);

                double dx = p.getX() - me.getX();
                double dy = p.getY() - me.getY();
                double dz = p.getZ() - me.getZ();
                double d2 = dx*dx + dy*dy + dz*dz;
                double dist = Math.sqrt(d2);
                String name = p.getGameProfile().getName();

                // refresh snapshot (used for both enter and leave)
                last.put(id, new Snap(name, p.getX(), p.getY(), p.getZ(), dist));

                // OUTER 128 with hysteresis
                boolean wasInside = inside.contains(id);
                if (d2 <= R2_ENTER || (wasInside && d2 <= R2_EXIT)) {
                    nextInside.add(id);
                }

                // INNER 15 danger with hysteresis
                boolean wasDanger = dangerInside.contains(id);
                if (d2 <= D2_DANGER_ENTER || (wasDanger && d2 <= D2_DANGER_EXIT)) {
                    nextDanger.add(id);
                }
            }

            // ---- Emit diffs AFTER sets are finalized
            // ENTER outer
            for (UUID id : diff(nextInside, inside)) {
                Snap s = last.get(id); if (s != null) {
                    bus.publish(cfg.evtTopic, JsonUtils.playerSpotted(s.name, id.toString(), s.x, s.y, s.z, s.dist));
                }
            }
            // LEAVE outer (includes vanish/unload)
            for (UUID id : diff(inside, nextInside)) {
                Snap s = last.get(id); if (s != null) {
                    bus.publish(cfg.evtTopic, JsonUtils.playerLeftRadius(s.name, id.toString(), s.x, s.y, s.z, s.dist));
                }
            }

            // ENTER danger
            for (UUID id : diff(nextDanger, dangerInside)) {
                Snap s = last.get(id); if (s != null) {
                    bus.publish(cfg.evtTopic, JsonUtils.playerDangerEnter(s.name, id.toString(), s.x, s.y, s.z, s.dist));
                }
            }
            // LEAVE danger (includes vanish/unload)
            for (UUID id : diff(dangerInside, nextDanger)) {
                Snap s = last.get(id); if (s != null) {
                    bus.publish(cfg.evtTopic, JsonUtils.playerDangerLeft(s.name, id.toString(), s.x, s.y, s.z, s.dist));
                }
            }

            // finalize sets
            inside.clear(); inside.addAll(nextInside);
            dangerInside.clear(); dangerInside.addAll(nextDanger);

            // keep snapshots only for currently present players
            last.keySet().retainAll(presentNow);
        });
    }

    @Override public void disable() {
        ticking = false;
        inside.clear();
        dangerInside.clear();
        last.clear();
    }

    // helper: a\b
    private static Set<UUID> diff(Set<UUID> a, Set<UUID> b) {
        Set<UUID> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
