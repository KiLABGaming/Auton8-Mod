package com.kilab.auton8.core;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import static net.fabricmc.fabric.impl.resource.loader.ModResourcePackUtil.GSON;

public final class JsonUtils {
    private JsonUtils() {}

    public static long nowSec() { return System.currentTimeMillis() / 1000; }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\""); // simple but fine here
    }

    /** "server" tag is either server address or "singleplayer" */
    public static String serverTag() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo info = mc.getCurrentServerEntry();
        if (info == null) return "singleplayer";
        String addr = info.address;
        return (addr == null || addr.isBlank()) ? "unknown" : esc(addr);
    }

    /* ----------------------------
     * Wrapper helpers
     * ---------------------------- */

    /** Wrap a payload with { type, data, server, ts } — legacy envelope. */
    public static String wrap(String type, JsonObject data) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.add("data", data);
        obj.addProperty("server", serverTag());
        obj.addProperty("ts", nowSec());
        return obj.toString();
    }

    /** Directly serialize an already-built event object; injects server/ts if absent. */
    public static String wrap(JsonObject o) {
        if (!o.has("server")) o.addProperty("server", serverTag());
        if (!o.has("ts"))     o.addProperty("ts", nowSec());
        return GSON.toJson(o);
    }

    /* ----------------------------
     * Base / status events
     * ---------------------------- */

    /** NEW: JsonObject base event. */
    public static JsonObject baseEventObj(String event, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("event", event);
        o.addProperty("detail", detail);
        o.addProperty("server", serverTag());
        o.addProperty("ts", nowSec());
        return o;
    }

    /** NEW: JsonObject base event with session id. */
    public static JsonObject baseEventObj(String event, String detail, String sessionId) {
        JsonObject o = baseEventObj(event, detail);
        o.addProperty("session_id", sessionId);
        return o;
    }

    /** String base event (kept for backward compatibility). */
    public static String baseEvent(String event, String detail) {
        return "{\"event\":\"" + esc(event) + "\",\"detail\":\"" + esc(detail) + "\",\"server\":\""
            + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /** String base event with session id (kept). */
    public static String baseEvent(String event, String detail, String sessionId) {
        return "{\"event\":\"" + esc(event) + "\",\"detail\":\"" + esc(detail) + "\","
            + "\"session_id\":\"" + esc(sessionId) + "\","
            + "\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /** Convenience marker for fresh enables (string form, kept). */
    public static String resetEvent(String sessionId) {
        return "{\"event\":\"status\",\"detail\":\"connected\",\"reset\":true,"
            + "\"session_id\":\"" + esc(sessionId) + "\","
            + "\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /* ----------------------------
     * Chat events
     * ---------------------------- */

    /** NEW: chat event as object. */
    public static JsonObject chatEventObj(String player, String msg, String source) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "chat");
        o.addProperty("player", player);
        o.addProperty("msg", msg);
        o.addProperty("source", source);
        o.addProperty("server", serverTag());
        o.addProperty("ts", nowSec());
        return o;
    }

    /** NEW: chat event as object with session id. */
    public static JsonObject chatEventObj(String player, String msg, String source, String sessionId) {
        JsonObject o = chatEventObj(player, msg, source);
        o.addProperty("session_id", sessionId);
        return o;
    }

    /** String chat (kept). */
    public static String chatEvent(String player, String msg, String source) {
        return "{\"event\":\"chat\",\"player\":\"" + esc(player) + "\",\"msg\":\"" + esc(msg) + "\"," +
            "\"source\":\"" + esc(source) + "\",\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /** String chat with session id (kept). */
    public static String chatEvent(String player, String msg, String source, String sessionId) {
        return "{\"event\":\"chat\",\"player\":\"" + esc(player) + "\",\"msg\":\"" + esc(msg) + "\"," +
            "\"source\":\"" + esc(source) + "\",\"session_id\":\"" + esc(sessionId) + "\"," +
            "\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /* ----------------------------
     * Player proximity / danger
     * ---------------------------- */

    private static JsonObject playerBase(String ev, String name, String uuid,
                                         double x, double y, double z, double dist) {
        JsonObject o = new JsonObject();
        o.addProperty("event", ev);
        o.addProperty("name", name);
        o.addProperty("uuid", uuid);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("dist", dist);
        o.addProperty("server", serverTag());
        o.addProperty("ts", nowSec());
        return o;
    }

    public static String playerSpotted(String name, String uuid, double x, double y, double z, double dist) {
        return wrap(playerBase("player_spotted", name, uuid, x, y, z, dist));
    }
    public static String playerSpotted(String name, String uuid, double x, double y, double z, double dist, String sessionId) {
        JsonObject o = playerBase("player_spotted", name, uuid, x, y, z, dist);
        o.addProperty("session_id", sessionId);
        return wrap(o);
    }

    public static String playerLeftRadius(String name, String uuid, double x, double y, double z, double dist) {
        return wrap(playerBase("player_left_radius", name, uuid, x, y, z, dist));
    }
    public static String playerLeftRadius(String name, String uuid, double x, double y, double z, double dist, String sessionId) {
        JsonObject o = playerBase("player_left_radius", name, uuid, x, y, z, dist);
        o.addProperty("session_id", sessionId);
        return wrap(o);
    }

    public static String playerDangerEnter(String name, String uuid, double x, double y, double z, double dist) {
        return wrap(playerBase("player_danger_enter", name, uuid, x, y, z, dist));
    }
    public static String playerDangerEnter(String name, String uuid, double x, double y, double z, double dist, String sessionId) {
        JsonObject o = playerBase("player_danger_enter", name, uuid, x, y, z, dist);
        o.addProperty("session_id", sessionId);
        return wrap(o);
    }

    public static String playerDangerLeft(String name, String uuid, double x, double y, double z, double dist) {
        return wrap(playerBase("player_danger_left", name, uuid, x, y, z, dist));
    }
    public static String playerDangerLeft(String name, String uuid, double x, double y, double z, double dist, String sessionId) {
        JsonObject o = playerBase("player_danger_left", name, uuid, x, y, z, dist);
        o.addProperty("session_id", sessionId);
        return wrap(o);
    }

    /* ----------------------------
     * Telemetry + Coords
     * ---------------------------- */

    /** Original telemetry (kept for backward compat). */
    public static String telemetry(double x, double y, double z, float health, int hunger, float saturation) {
        return telemetry(x, y, z, health, hunger, saturation, (String) null);
    }

    /** Telemetry with dimension (legacy string). */
    public static String telemetry(double x, double y, double z,
                                   float health, int hunger, float saturation,
                                   String dimension) {
        String dimField = (dimension == null || dimension.isBlank())
            ? ""
            : ",\"dimension\":\"" + esc(dimension) + "\"";
        return "{\"event\":\"telemetry\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "," +
            "\"health\":" + health + ",\"hunger\":" + hunger + ",\"saturation\":" + saturation + dimField + "," +
            "\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /** Telemetry with dimension + session id (string). */
    public static String telemetry(double x, double y, double z,
                                   float health, int hunger, float saturation,
                                   String dimension, String sessionId) {
        String dimField = (dimension == null || dimension.isBlank())
            ? ""
            : ",\"dimension\":\"" + esc(dimension) + "\"";
        return "{\"event\":\"telemetry\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "," +
            "\"health\":" + health + ",\"hunger\":" + hunger + ",\"saturation\":" + saturation + dimField + "," +
            "\"session_id\":\"" + esc(sessionId) + "\"," +
            "\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /** Original coords (kept). */
    public static String coords(double x, double y, double z) { return coords(x, y, z, (String) null); }

    /** Coords with dimension (legacy string). */
    public static String coords(double x, double y, double z, String dimension) {
        String dimField = (dimension == null || dimension.isBlank())
            ? ""
            : ",\"dimension\":\"" + esc(dimension) + "\"";
        return "{\"event\":\"coords\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + dimField + "," +
            "\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /** Coords with dimension + session id (string). */
    public static String coords(double x, double y, double z, String dimension, String sessionId) {
        String dimField = (dimension == null || dimension.isBlank())
            ? ""
            : ",\"dimension\":\"" + esc(dimension) + "\"";
        return "{\"event\":\"coords\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + dimField + "," +
            "\"session_id\":\"" + esc(sessionId) + "\"," +
            "\"server\":\"" + serverTag() + "\",\"ts\":" + nowSec() + "}";
    }

    /* ----------------------------
     * Life events
     * ---------------------------- */

    public static String life(String status, String world, double x, double y, double z, String cause) {
        JsonObject o = lifeObj(status, world, x, y, z, cause, null);
        return wrap(o);
    }

    public static String life(String status, String world, double x, double y, double z, String cause, String sessionId) {
        JsonObject o = lifeObj(status, world, x, y, z, cause, sessionId);
        return wrap(o);
    }

    /** NEW: object form used by the string helpers above. */
    private static JsonObject lifeObj(String status, String world, double x, double y, double z, String cause, String sessionId) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "life");
        o.addProperty("status", status);
        if (world != null) o.addProperty("world", world);

        JsonObject c = new JsonObject();
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("z", z);
        o.add("coords", c);

        if (cause != null && !cause.isBlank()) o.addProperty("cause", cause);
        if (sessionId != null && !sessionId.isBlank()) o.addProperty("session_id", sessionId);
        // server/ts added by wrap(JsonObject)
        return o;
    }
}
