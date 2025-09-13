package com.kilab.auton8.bridges;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kilab.auton8.core.Config;
import com.kilab.auton8.core.JsonUtils;
import com.kilab.auton8.mqtt.MqttBus;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class BaritoneBridge implements Bridge {
    private final Config cfg;
    private final MqttBus bus;

    private String state = "IDLE";
    private String lastCmd = null;
    private long lastCmdStartMs = 0L;
    private String lastCmdOutcome = "pending";
    private String lastReason = "none";

    private int retries = 0;
    private int cooldownTicks = 0;

    private BlockPos target = null;
    private String targetKey = null;
    private double distanceRemaining = -1.0;

    private final Deque<Vec3d> posRing = new ArrayDeque<>();
    private long lastPublishMs = 0L;

    private boolean awaitingAccept = false;
    private long acceptDeadlineMs = 0L;
    private boolean acceptedEmitted = false;
    private long movingSinceMs = 0L;

    private boolean goalEmittedForThisTarget = false;
    private boolean withinGoalNow = false;
    private long withinGoalSinceMs = 0L;
    private long lastGoalEmitMs = 0L;

    private long lastHorizontalMoveMs;

    private static final int PUBLISH_INTERVAL_MS = 950;
    private static final int RING_MAX = 20;
    private static final int COOLDOWN_TICKS = 20 * 8;
    private static final int MAX_RETRIES = 3;

    private static final double GOAL_EPS_XZ = 3.0;
    private static final long GOAL_STAY_MS = 1_200;
    private static final long GOAL_REEMIT_DEBOUNCE = 10_000;

    private static final double MOVING_SPEED_MPS = 0.4;
    private static final double HORIZ_MOVE_EPS_SPEED = 0.05;

    private static final long ACCEPT_WINDOW_MS = 15_000;
    private static final long ACCEPT_SUSTAIN_MS = 1_500;

    private static final long STUCK_IDLE_MS = 20_000;

    private static final long NONGOTO_QUIET_MS = 600;
    private static final long PROCESS_SETTLE_MS = 1500;

    private enum StepType { GOTO, PATH, WAIT, BUILD, SEL, CMD, MACRO }

    private static final class Step {
        final StepType type;
        final String cmd;
        final long timeoutMs;
        final int maxRetries;
        int retriesTried = 0;
        Step(StepType type, String cmd, long timeoutMs, int maxRetries) {
            this.type = type; this.cmd = cmd; this.timeoutMs = timeoutMs; this.maxRetries = Math.max(0, maxRetries);
        }
    }

    private final Deque<Step> planQueue = new ArrayDeque<>();
    private String currentPlanId = null;
    private int currentIndex = -1;
    private boolean planPaused = false;
    private long stepDeadlineMs = 0L;
    private String planOnFail = "continue";
    private int planMaxRetriesPerStep = 0;

    private final BaritoneFacade baritone = new BaritoneFacade();

    public BaritoneBridge(Config cfg, MqttBus bus) {
        this.cfg = cfg;
        this.bus = bus;
        this.lastHorizontalMoveMs = System.currentTimeMillis();
    }

    public void resetPlanOnSessionStart() { resetPlan(); }

    @Override
    public void enable() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (cooldownTicks > 0) cooldownTicks--;
                samplePos(client);
                baritone.tick();
                updateHeuristicState(client);
                maybeDetectGoalOrStuck();
                planTick();
                maybePublishSnapshot();
            } catch (Exception e) {
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("error", e.getClass().getSimpleName()));
            }
        });
    }

    @Override public void disable() { }

    public void onLocalBaritoneCommand(String raw) {
        if (!cfg.allowBaritone || raw == null || !raw.startsWith("#")) return;
        String low = raw.toLowerCase().trim();
        BlockPos newTarget = parseGoto(low);
        StepType t = inferType(low);
        armNewCommand(raw, newTarget, expectsMovement(t));
        if (low.equals("#cancel") || low.equals("#stop")) {
            clearCurrentGoalContext();
            state = "IDLE";
        }
    }

    @Override
    public void onCommand(String json) {
        if (!cfg.allowBaritone) return;
        try {
            JsonObject j = JsonParser.parseString(json).getAsJsonObject();
            String type = j.has("type") ? j.get("type").getAsString() : "";
            if ("baritone_cmd".equals(type)) { handleSingleCmd(j); return; }
            if ("baritone_plan".equals(type)) { handlePlan(j); return; }
            if ("baritone_ctrl".equals(type)) { handlePlanCtrl(j); return; }
        } catch (Exception e) {
            bus.publish(cfg.evtTopic, JsonUtils.baseEvent("error", "plan_parse_" + e.getClass().getSimpleName()));
        }
    }



    private void handleSingleCmd(JsonObject j) {
        String cmd = j.has("cmd") ? j.get("cmd").getAsString() : null;
        if (cmd == null || !cmd.startsWith("#") || cmd.length() > 120) {
            bus.publish(cfg.evtTopic, JsonUtils.baseEvent("cmd_reject","bad_cmd"));
            return;
        }
        final String low = cmd.toLowerCase().trim();
        final BlockPos newTarget = parseGoto(low);
        final StepType t = inferType(low);
        final String send = cmd;

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player == null || mc.player.networkHandler == null) {
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("cmd_reject","no_player"));
                return;
            }
            ChatBridge.SUPPRESS_LOCAL_BARITONE_HOOK.set(Boolean.TRUE);
            try { mc.player.networkHandler.sendChatMessage(send); }
            finally { ChatBridge.SUPPRESS_LOCAL_BARITONE_HOOK.set(Boolean.FALSE); }
            armNewCommand(send, newTarget, expectsMovement(t));
            if (send.equalsIgnoreCase("#path")) retries++;
            if (send.equalsIgnoreCase("#cancel") || send.equalsIgnoreCase("#stop")) { clearCurrentGoalContext(); state = "IDLE"; }
            bus.publish(cfg.evtTopic, JsonUtils.baseEvent("accepted", send));
        });
    }

    private void handlePlan(JsonObject j) {
        resetPlan();
        currentPlanId = j.has("plan_id") ? j.get("plan_id").getAsString() : UUID.randomUUID().toString();
        planOnFail = j.has("policy") && j.getAsJsonObject("policy").has("onFail") ? j.getAsJsonObject("policy").get("onFail").getAsString() : "continue";
        planMaxRetriesPerStep = j.has("policy") && j.getAsJsonObject("policy").has("maxRetriesPerStep") ? j.getAsJsonObject("policy").get("maxRetriesPerStep").getAsInt() : 0;

        long defaultTimeout = 180_000;
        if (j.has("steps")) {
            for (JsonElement el : j.getAsJsonArray("steps")) {
                JsonObject s = el.getAsJsonObject();
                String c = s.get("cmd").getAsString();
                long t = s.has("timeoutMs") ? s.get("timeoutMs").getAsLong() : defaultTimeout;
                int r = s.has("maxRetries") ? Math.max(0, s.get("maxRetries").getAsInt()) : -1;
                StepType st = s.has("type") ? parseType(s.get("type").getAsString()) : inferType(c.toLowerCase().trim());
                planQueue.addLast(new Step(st, c, t, r));
            }
        }
        emitPlanEvent("plan_started", planMeta());
        tryStartNextStep();
    }

    private void handlePlanCtrl(JsonObject j) {
        String action = j.has("action") ? j.get("action").getAsString() : "";
        switch (action) {
            case "pause"  -> planPaused = true;
            case "resume" -> { planPaused = false; tryStartNextStep(); }
            case "skip"   -> { cancelCurrentBaritoneIfAny(); advanceAfter("skipped"); }
            case "cancel" -> { cancelCurrentBaritoneIfAny(); finishPlan("aborted"); }
            case "clear"  -> { cancelCurrentBaritoneIfAny(); resetPlan(); }
            default -> { }
        }
    }

    private void armNewCommand(String send, BlockPos newTarget, boolean expectMovement) {
        lastCmd = send;
        lastCmdStartMs = System.currentTimeMillis();
        lastCmdOutcome = "pending";
        lastReason = "none";
        distanceRemaining = -1.0;
        if (newTarget != null) { target = newTarget; targetKey = target.getX() + ":" + target.getY() + ":" + target.getZ(); }
        else { target = null; targetKey = null; }
        awaitingAccept = expectMovement;
        acceptedEmitted = false;
        acceptDeadlineMs = expectMovement ? (lastCmdStartMs + ACCEPT_WINDOW_MS) : 0L;
        movingSinceMs = 0L;
        goalEmittedForThisTarget = false;
        withinGoalNow = false;
        withinGoalSinceMs = 0L;
    }

    private static BlockPos parseGoto(String low) {
        if (!low.startsWith("#goto")) return null;
        String[] parts = low.split("\\s+");
        if (parts.length < 4) return null;
        try {
            int x = (int)Math.round(Double.parseDouble(parts[1]));
            int y = (int)Math.round(Double.parseDouble(parts[2]));
            int z = (int)Math.round(Double.parseDouble(parts[3]));
            return new BlockPos(x, y, z);
        } catch (NumberFormatException ignored) { return null; }
    }

    private static StepType parseType(String s) {
        return switch (s.toLowerCase()) {
            case "goto" -> StepType.GOTO;
            case "wait" -> StepType.WAIT;
            case "build"-> StepType.BUILD;
            case "sel"  -> StepType.SEL;
            case "path" -> StepType.PATH;
            case "cmd"  -> StepType.CMD;
            case "macro"-> StepType.MACRO;
            default     -> StepType.CMD;
        };
    }

    private static StepType inferType(String lowCmd) {
        if (lowCmd.startsWith("#goto"))  return StepType.GOTO;
        if (lowCmd.startsWith("#wait"))  return StepType.WAIT;
        if (lowCmd.startsWith("#build")) return StepType.BUILD;
        if (lowCmd.startsWith("#sel"))   return StepType.SEL;
        if (lowCmd.equals("#path"))      return StepType.PATH;
        return StepType.CMD;
    }

    private static boolean expectsMovement(StepType t) {
        return t == StepType.GOTO || t == StepType.PATH;
    }

    private void samplePos(MinecraftClient mc) {
        if (mc.player == null) return;
        Vec3d p = mc.player.getPos();
        posRing.addLast(p);
        while (posRing.size() > RING_MAX) posRing.removeFirst();
    }

    private double avgHorizontalSpeedMps() {
        if (posRing.size() < 2) return 0.0;
        Vec3d first = posRing.getFirst(), last = posRing.getLast();
        double dx = last.x - first.x;
        double dz = last.z - first.z;
        double distXZ = Math.hypot(dx, dz);
        double secs = Math.max(1.0, (posRing.size() - 1) / 20.0);
        return distXZ / secs;
    }

    private void updateHeuristicState(MinecraftClient mc) {
        if (target != null && mc.player != null) {
            Vec3d p = mc.player.getPos();
            double dx = (target.getX() + 0.5) - p.x;
            double dz = (target.getZ() + 0.5) - p.z;
            distanceRemaining = Math.hypot(dx, dz);
        } else {
            distanceRemaining = -1.0;
        }
        boolean baritonePathing = baritone.isPathing();
        double speed = avgHorizontalSpeedMps();
        long now = System.currentTimeMillis();
        if (speed >= HORIZ_MOVE_EPS_SPEED) lastHorizontalMoveMs = now;
        boolean movingFastEnough = baritonePathing || speed >= MOVING_SPEED_MPS;

        if (awaitingAccept && movingFastEnough) {
            if (movingSinceMs == 0L) movingSinceMs = now;
            if (!acceptedEmitted && (baritonePathing || now - movingSinceMs >= ACCEPT_SUSTAIN_MS)) {
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("cmd_accepted", lastCmd == null ? "" : lastCmd));
                awaitingAccept = false;
                acceptedEmitted = true;
                state = "PATHING";
            }
        } else {
            movingSinceMs = 0L;
        }

        if (awaitingAccept && now > acceptDeadlineMs && !acceptedEmitted) {
            bus.publish(cfg.evtTopic, JsonUtils.baseEvent("cmd_reject", "timeout_no_pathing"));
            awaitingAccept = false;
        }

        if (baritonePathing || speed >= MOVING_SPEED_MPS) state = "PATHING";
        else if ("STUCK".equals(state)) { }
        else state = "IDLE";
    }

    private void maybeDetectGoalOrStuck() {
        long now = System.currentTimeMillis();
        if (target != null && distanceRemaining >= 0) {
            boolean within = distanceRemaining <= GOAL_EPS_XZ;
            if (within && !withinGoalNow) { withinGoalNow = true; withinGoalSinceMs = now; }
            else if (!within) { withinGoalNow = false; withinGoalSinceMs = 0L; }
            if (withinGoalNow && !goalEmittedForThisTarget && (now - withinGoalSinceMs) >= GOAL_STAY_MS && (now - lastGoalEmitMs) >= GOAL_REEMIT_DEBOUNCE) {
                lastCmdOutcome = "success";
                lastReason = "goal_reached";
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("goal_reached", target.toShortString()));
                goalEmittedForThisTarget = true;
                lastGoalEmitMs = now;
                clearCurrentGoalContext();
                retries = 0;
                state = "IDLE";
                return;
            }
        }

        long sinceHorizMove = now - lastHorizontalMoveMs;
        if (sinceHorizMove >= STUCK_IDLE_MS) {
            state = "STUCK";
            lastReason = "stuck";
            bus.publish(cfg.evtTopic, JsonUtils.baseEvent("stuck_detected", String.valueOf((int)Math.round(distanceRemaining))));
            if (cooldownTicks == 0 && lastCmd != null && (lastCmd.toLowerCase().startsWith("#goto") || lastCmd.equalsIgnoreCase("#path")) && retries < MAX_RETRIES) {
                sendClientChatTyped(inferType(lastCmd.toLowerCase()), "#path");
                retries++;
                cooldownTicks = COOLDOWN_TICKS;
                awaitingAccept = true;
                acceptedEmitted = false;
                acceptDeadlineMs = System.currentTimeMillis() + ACCEPT_WINDOW_MS;
                movingSinceMs = 0L;
            } else if (retries >= MAX_RETRIES) {
                lastCmdOutcome = "fail";
            }
            lastHorizontalMoveMs = now;
        }
    }

    private void clearCurrentGoalContext() {
        target = null;
        targetKey = null;
        distanceRemaining = -1.0;
        awaitingAccept = false;
        acceptedEmitted = false;
        acceptDeadlineMs = 0L;
        movingSinceMs = 0L;
        lastCmd = null;
        lastCmdStartMs = 0L;
        withinGoalNow = false;
        withinGoalSinceMs = 0L;
        goalEmittedForThisTarget = false;
    }

    private void planTick() {
        if (currentPlanId == null) return;
        Step cur = planQueue.peekFirst();
        if (cur == null) { finishPlan("success"); return; }
        long now = System.currentTimeMillis();

        if (cur.type == StepType.WAIT) {
            if (stepDeadlineMs == 0L) stepDeadlineMs = now + Math.max(0, parseWaitMs(cur.cmd));
            if (now >= stepDeadlineMs) advanceAfter("wait_done");
            return;
        }

        if (cur.timeoutMs > 0 && stepDeadlineMs > 0 && now > stepDeadlineMs) {
            int cap = (cur.maxRetries >= 0 ? cur.maxRetries : planMaxRetriesPerStep);
            if (cur.retriesTried < cap) {
                cur.retriesTried++;
                sendClientChatTyped(cur.type, cur.cmd);
                stepDeadlineMs = (cur.timeoutMs > 0) ? now + cur.timeoutMs : 0L;
                return;
            }
            emitPlanEvent("plan_step_finished", stepMeta("timeout"));
            if ("abort".equalsIgnoreCase(planOnFail)) { finishPlan("partial"); }
            else { cancelCurrentBaritoneIfAny(); advanceAfter("timeout"); }
            return;
        }

        boolean isMovement = (cur.type == StepType.GOTO || cur.type == StepType.PATH);
        boolean gotoDone = isMovement && !awaitingAccept && "IDLE".equals(state) && "goal_reached".equals(lastReason);

        boolean nonGotoDone;
        if (cur.type == StepType.BUILD || (cur.type == StepType.SEL && cur.cmd.toLowerCase().contains("cleararea"))) {
            boolean builderActive = baritone.isBuilderActive();
            long sinceAnyProcess = System.currentTimeMillis() - baritone.getLastProcessSeenMs();
            nonGotoDone = !builderActive && sinceAnyProcess >= PROCESS_SETTLE_MS && !awaitingAccept && !"PATHING".equals(state);
        } else {
            nonGotoDone = !isMovement && (System.currentTimeMillis() - lastCmdStartMs) >= NONGOTO_QUIET_MS && !"PATHING".equals(state) && !awaitingAccept;
        }

        if (gotoDone || nonGotoDone) {
            advanceAfter(gotoDone ? "goal_reached" : "done");
            return;
        }

        if (!planPaused && stepDeadlineMs == 0L && "IDLE".equals(state) && lastCmd == null) {
            tryStartNextStep();
        }
    }

    private long parseWaitMs(String cmd) {
        String[] p = cmd.split("\\s+");
        if (p.length >= 2) try { return Long.parseLong(p[1]); } catch (Exception ignored) {}
        return 0L;
    }

    private void tryStartNextStep() {
        if (planPaused || planQueue.isEmpty()) return;
        if (!"IDLE".equals(state) || awaitingAccept) return;

        Step step = planQueue.peekFirst();
        currentIndex++;
        emitPlanEvent("plan_step_started", stepMeta("start"));

        if (step.type == StepType.WAIT) {
            stepDeadlineMs = 0L;
            return;
        }

        sendClientChatTyped(step.type, step.cmd);
        stepDeadlineMs = (step.timeoutMs > 0) ? System.currentTimeMillis() + step.timeoutMs : 0L;
    }

    private void advanceAfter(String reason) {
        Step done = planQueue.pollFirst();
        if (done != null) emitPlanEvent("plan_step_finished", stepMeta(reason));
        stepDeadlineMs = 0L;
        if (planQueue.isEmpty()) finishPlan("success");
        else tryStartNextStep();
    }

    private void finishPlan(String status) {
        JsonObject meta = planMeta();
        meta.addProperty("status", status);
        emitPlanEvent("plan_finished", meta);
        resetPlan();
    }

    private void cancelCurrentBaritoneIfAny() {
        if (!"IDLE".equals(state)) sendClientChatTyped(StepType.CMD, "#cancel");
    }

    private void resetPlan() {
        planQueue.clear();
        currentPlanId = null;
        currentIndex = -1;
        planPaused = false;
        stepDeadlineMs = 0L;
        planOnFail = "continue";
        planMaxRetriesPerStep = 0;
    }

    private JsonObject stepMeta(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("plan_id", currentPlanId);
        o.addProperty("index", currentIndex);
        Step cur = planQueue.peekFirst();
        o.addProperty("cmd", cur != null ? cur.cmd : "");
        o.addProperty("reason", reason);
        return o;
    }

    private JsonObject planMeta() {
        JsonObject o = new JsonObject();
        o.addProperty("plan_id", currentPlanId);
        o.addProperty("size", planQueue.size());
        o.addProperty("index", currentIndex);
        return o;
    }

    private void emitPlanEvent(String name, JsonObject payload) {
        bus.publish(cfg.evtTopic, JsonUtils.wrap(name, payload));
    }

    private void maybePublishSnapshot() {
        long now = System.currentTimeMillis();
        if (now - lastPublishMs < PUBLISH_INTERVAL_MS) return;
        lastPublishMs = now;

        double speed = avgHorizontalSpeedMps();
        long elapsedSec = lastCmdStartMs == 0 ? 0 : Math.max(0, (now - lastCmdStartMs) / 1000);

        JsonObject snap = new JsonObject();
        snap.addProperty("ts", now / 1000);
        snap.addProperty("state", state);
        if (lastCmd != null) snap.addProperty("lastCmd", lastCmd);
        snap.addProperty("lastCmdOutcome", lastCmdOutcome);
        snap.addProperty("reason", lastReason);
        snap.addProperty("elapsedSec", elapsedSec);
        snap.addProperty("retries", retries);
        snap.addProperty("cooldownSec", cooldownTicks / 20);
        snap.addProperty("speedAvg", Math.round(speed * 100.0) / 100.0);
        snap.addProperty("distanceRemaining", distanceRemaining);
        snap.addProperty("awaitingAccept", awaitingAccept);
        snap.addProperty("acceptedEmitted", acceptedEmitted);
        snap.addProperty("movingSinceMs", movingSinceMs);
        snap.addProperty("withinGoalNow", withinGoalNow);
        snap.addProperty("withinGoalForMs", withinGoalNow ? (now - withinGoalSinceMs) : 0);
        snap.addProperty("lastGoalEmitMsAgo", lastGoalEmitMs == 0 ? -1 : (now - lastGoalEmitMs));

        if (target != null) {
            JsonObject t = new JsonObject();
            t.addProperty("x", target.getX());
            t.addProperty("y", target.getY());
            t.addProperty("z", target.getZ());
            t.addProperty("key", targetKey);
            snap.add("target", t);
        }

        snap.addProperty("planId", currentPlanId == null ? "" : currentPlanId);
        snap.addProperty("planPaused", planPaused);
        snap.addProperty("planIndex", currentIndex);
        snap.addProperty("planRemaining", Math.max(0, planQueue.size()));
        snap.addProperty("planOnFail", planOnFail);
        snap.addProperty("planMaxRetriesPerStep", planMaxRetriesPerStep);
        snap.addProperty("stepDeadlineMs", stepDeadlineMs);

        snap.addProperty("api_pathing", baritone.isPathing());
        snap.addProperty("api_builderActive", baritone.isBuilderActive());
        snap.addProperty("api_lastProcessSeenMsAgo", baritone.getLastProcessSeenMs() == 0L ? -1 : (now - baritone.getLastProcessSeenMs()));

        String topic = cfg.baritoneStateTopicOrDefault();
        bus.publish(topic, JsonUtils.wrap("baritone_state", snap));
    }

    private void sendClientChatTyped(StepType type, String msg) {
        BlockPos tgt = (type == StepType.GOTO) ? parseGoto(msg.toLowerCase()) : null;
        boolean expectMove = expectsMovement(type);
        sendClientChat(msg, tgt, expectMove);
    }

    private void sendClientChat(String msg) {
        StepType t = inferType(msg.toLowerCase());
        BlockPos tgt = (t == StepType.GOTO) ? parseGoto(msg.toLowerCase()) : null;
        sendClientChat(msg, tgt, expectsMovement(t));
    }

    private void sendClientChat(String msg, BlockPos newTarget, boolean expectMovement) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null && mc.player.networkHandler != null) {
                ChatBridge.SUPPRESS_LOCAL_BARITONE_HOOK.set(Boolean.TRUE);
                try { mc.player.networkHandler.sendChatMessage(msg); }
                finally { ChatBridge.SUPPRESS_LOCAL_BARITONE_HOOK.set(Boolean.FALSE); }
                bus.publish(cfg.evtTopic, JsonUtils.baseEvent("accepted", msg));
                armNewCommand(msg, newTarget, expectMovement);
            }
        });
    }

    private static final class BaritoneFacade {
        private Object provider;
        private Object baritone;
        private Method mGetProvider;
        private Method mGetPrimary;
        private Method mGetPathing;
        private Method mIsPathing;
        private Method mGetBuilder;
        private Method mBuilderActive;
        private Method mGetMine;
        private Method mMineActive;

        private long lastProcessSeenMs = 0L;
        private boolean initTried = false;
        private boolean initOk = false;

        boolean isPathing() {
            ensureInit();
            if (!initOk) return false;
            try {
                Object pathing = (mGetPathing != null && baritone != null) ? mGetPathing.invoke(baritone) : null;
                if (pathing != null && mIsPathing != null) {
                    Object b = mIsPathing.invoke(pathing);
                    return (b instanceof Boolean) && (Boolean) b;
                }
            } catch (Throwable ignored) {}
            return false;
        }

        boolean isBuilderActive() {
            ensureInit();
            boolean active = false;
            try {
                if (baritone != null && mGetBuilder != null && mBuilderActive != null) {
                    Object builder = mGetBuilder.invoke(baritone);
                    if (builder != null) {
                        Object b = mBuilderActive.invoke(builder);
                        active = (b instanceof Boolean) && (Boolean) b;
                    }
                }
            } catch (Throwable ignored) {}
            if (active) lastProcessSeenMs = System.currentTimeMillis();
            return active;
        }

        boolean isAnyProcessActive() {
            ensureInit();
            boolean active = false;
            active |= isBuilderActive();
            try {
                if (baritone != null && mGetMine != null && mMineActive != null) {
                    Object mine = mGetMine.invoke(baritone);
                    if (mine != null) {
                        Object b = mMineActive.invoke(mine);
                        active |= (b instanceof Boolean) && (Boolean) b;
                    }
                }
            } catch (Throwable ignored) {}
            if (active) lastProcessSeenMs = System.currentTimeMillis();
            return active;
        }

        long getLastProcessSeenMs() { return lastProcessSeenMs; }

        void tick() { if (isAnyProcessActive()) lastProcessSeenMs = System.currentTimeMillis(); }

        private void ensureInit() {
            if (initTried) return;
            initTried = true;
            try {
                Class<?> cAPI = Class.forName("baritone.api.BaritoneAPI");
                Class<?> cProv = Class.forName("baritone.api.IBaritoneProvider");
                Class<?> cBar  = Class.forName("baritone.api.IBaritone");

                mGetProvider = cAPI.getMethod("getProvider");
                provider = mGetProvider.invoke(null);
                if (provider == null) return;

                mGetPrimary = cProv.getMethod("getPrimaryBaritone");
                baritone = mGetPrimary.invoke(provider);
                if (baritone == null) return;

                try {
                    mGetPathing = cBar.getMethod("getPathingBehavior");
                    Class<?> cPath = Class.forName("baritone.api.behavior.IPathingBehavior");
                    mIsPathing = cPath.getMethod("isPathing");
                } catch (Throwable ignored) {}

                try {
                    mGetBuilder = cBar.getMethod("getBuilderProcess");
                    Class<?> cBuilder = Class.forName("baritone.api.process.IBuilderProcess");
                    mBuilderActive = cBuilder.getMethod("isActive");
                } catch (Throwable ignored) {}

                try {
                    mGetMine = cBar.getMethod("getMineProcess");
                    Class<?> cMine = Class.forName("baritone.api.process.IMineProcess");
                    mMineActive = cMine.getMethod("isActive");
                } catch (Throwable ignored) {}

                initOk = true;
            } catch (Throwable ignored) { initOk = false; }
        }
    }
}
