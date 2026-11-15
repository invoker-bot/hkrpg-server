package org.hkrpg.server;

import emu.lunarcore.LunarCore;
import emu.lunarcore.data.config.MonsterInfo;
import emu.lunarcore.data.config.NpcInfo;
import emu.lunarcore.data.config.ObjectInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdIdUtils;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hkrpg.server.protocol.ProtocolException;
import org.slf4j.event.Level;

/** Stable gameplay and protocol metadata. Packet bodies and exception messages stay out of logs. */
public final class GameDiagnostics {
  private enum Topic { UNHANDLED, WIRE, HANDLER, SCENE, ACTION, PROGRESS, TICK }

  private static final EnumMap<Topic, Window> WINDOWS = new EnumMap<>(Topic.class);
  static {
    for (var topic : Topic.values()) WINDOWS.put(topic, new Window(96));
  }

  static final long INTERVAL = TimeUnit.MINUTES.toNanos(1);

  record Summary(long repeated, long overflow, List<String> examples) {}

  static final class Window {
    private final int capacity;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private long start;
    private long overflow;

    Window(int capacity) { this.capacity = capacity; }

    boolean admit(String key, long now) {
      var repeats = counts.get(key);
      if (repeats != null) {
        counts.put(key, repeats + 1);
        return false;
      }
      if (counts.size() >= capacity) {
        overflow++;
        return false;
      }
      if (counts.isEmpty()) start = now;
      counts.put(key, 0L);
      return true;
    }

    Summary drain(long now, boolean force) {
      if (counts.isEmpty() || !force && now - start < INTERVAL) return null;
      long repeated = counts.values().stream().mapToLong(Long::longValue).sum();
      var examples = counts.entrySet().stream().filter(row -> row.getValue() > 0)
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .limit(6).map(row -> row.getKey() + " x" + row.getValue()).toList();
      var summary = repeated == 0 && overflow == 0 ? null : new Summary(repeated, overflow, examples);
      counts.clear();
      overflow = 0;
      return summary;
    }
  }

  private static void record(Topic topic, Level level, String key, Supplier<String> message) {
    var window = WINDOWS.get(topic);
    long now = System.nanoTime();
    Summary summary;
    boolean first;
    synchronized (window) {
      summary = window.drain(now, false);
      first = window.admit(key, now);
    }
    logSummary(topic, summary);
    if (first) LunarCore.getLogger().atLevel(level).log("{} at={}", message.get(), Instant.now());
  }

  public static void flush(boolean stopping) {
    long now = System.nanoTime();
    WINDOWS.forEach((topic, window) -> {
      Summary summary;
      synchronized (window) { summary = window.drain(now, stopping); }
      logSummary(topic, summary);
    });
  }

  /** SDK requests may contain credentials; record only a fixed rejection category. */
  public static void sdkShopRejected(String reason) {
    record(Topic.ACTION, Level.WARN, "sdk-shop:" + reason,
        () -> "Local SDK shop rejected: reason=" + reason);
  }

  public static void missionScriptUnavailable(Player player, int main, int sub, int performance) {
    record(Topic.PROGRESS,Level.WARN,"unavailable-script:"+main+":"+sub,
        ()->"Mission script skipped: reason=missing-optional-performance main="+main
            +" sub="+sub+" performance="+performance+context(player.getSession(),player.getScene()));
  }

  public static void missionPropState(Player player, int sub, int group, int instance, int state, String reason) {
    record(Topic.PROGRESS, "applied".equals(reason) ? Level.INFO : Level.WARN,
        "mission-prop:"+sub+":"+reason,
        ()->"Mission prop state: sub="+sub+" group="+group+" instance="+instance
            +" targetState="+state+" reason="+reason+context(player.getSession(),player.getScene()));
  }

  /** Stage is an authored constant; no transaction keys or exception messages are logged. */
  public static void saveFailed(Player player,String stage,int subject,int receiptHash,Throwable failure) {
    record(Topic.PROGRESS,Level.WARN,"save:"+stage+":"+subject+":"+receiptHash,
        ()->"Gameplay save failed: stage="+stage+" subject="+subject+" receiptHash="+receiptHash
            +" error="+failure.getClass().getSimpleName()+context(player.getSession(),player.getScene()));
  }

  public static void rewardDeliveryInterrupted(Player player,int reward,int receiptHash) {
    record(Topic.PROGRESS,Level.WARN,"reward-delivery:"+reward+":"+receiptHash,
        ()->"Reward delivery incomplete: reward="+reward+" receiptHash="+receiptHash
            +" reservation=pending"+context(player.getSession(),player.getScene()));
  }

  public static void researchSubmitRejected(Player player,int sub,String reason) {
    record(Topic.PROGRESS,Level.WARN,"research-submit:"+sub+":"+reason,
        ()->"Research material submission rejected: sub="+sub+" reason="+reason
            +context(player.getSession(),player.getScene()));
  }

  public static void storyActionInterrupted(Player player,int sub,int index,String stage,Throwable failure) {
    record(Topic.PROGRESS,Level.WARN,"story-action:"+sub+":"+index+":"+stage,
        ()->"Story action interrupted: sub="+sub+" index="+index+" stage="+stage
            +(failure == null ? "" : " error="+failure.getClass().getSimpleName())
            +context(player.getSession(),player.getScene()));
  }

  public static void questRewardInterrupted(Player player,int quest,int reward,String reason) {
    record(Topic.PROGRESS,Level.WARN,"quest-reward:"+quest+":"+reason,
        ()->"Quest reward incomplete: quest="+quest+" reward="+reward+" reason="+reason
            +" reservation=claiming"+context(player.getSession(),player.getScene()));
  }

  public static void anchorTriggerSkipped(int plane,int floor,int group,int instance) {
    String place=" plane="+plane+" floor="+floor+" group="+group+" instance="+instance;
    record(Topic.SCENE,Level.WARN,"anchor-trigger:"+place,
        ()->"Anchor trigger skipped: reason=unsupported-volume"+place);
  }

  public static void anchorActivated(Scene scene,int group,int instance,int mapping) {
    String place=" plane="+scene.getPlaneId()+" floor="+scene.getFloorId()
        +" group="+group+" instance="+instance+" mapping="+mapping;
    record(Topic.PROGRESS,Level.INFO,"anchor-activated:"+place,
        ()->"Space anchor activated:"+place+context(scene.getPlayer().getSession(),scene));
  }

  private static void logSummary(Topic topic, Summary summary) {
    if (summary == null) return;
    LunarCore.getLogger().info("Diagnostic repeats: topic={} repeated={} overLimit={} examples={} at={}",
        topic, summary.repeated(), summary.overflow(), summary.examples(), Instant.now());
  }

  private static Scene scene(GameSession session) {
    var player = session == null ? null : session.getPlayer();
    return player == null ? null : player.getScene();
  }

  private static String location(Scene scene) {
    return scene == null ? "floor=0" : "plane=" + scene.getPlaneId() + " floor=" + scene.getFloorId()
        + " entry=" + scene.getEntryId() + " dimension=" + scene.getDimensionId();
  }

  private static String context(GameSession session, Scene scene) {
    var player = session == null ? null : session.getPlayer();
    String place = scene == null && player != null
        ? "plane=" + player.getPlaneId() + " floor=" + player.getFloorId() + " entry=" + player.getEntryId()
        : location(scene);
    return " session=" + (session == null ? 0 : session.getDiagnosticId())
        + " state=" + (session == null ? "unknown" : session.getState()) + " " + place;
  }

  private static String wireVersion(GameSession session) {
    return session == null ? "unknown" : session.getProtocol().version();
  }

  private static String commandName(GameSession session, int command, boolean core) {
    if (command < 0) return "unknown";
    return core || session == null ? CmdIdUtils.getCmdIdName(command)
        : session.getProtocol().inboundName(command);
  }

  public static void unhandled(GameSession session, int command, boolean core) {
    String where = core ? "core" : "inbound";
    String version = wireVersion(session);
    String name = commandName(session, command, core);
    record(Topic.UNHANDLED, Level.WARN, version + "/" + where + "/" + command + "/" + name,
        () -> "Unhandled " + version + " " + where + " command: " + command + " name=" + name
            + context(session, scene(session)));
  }

  public static void incompleteFrame(GameSession session, int remaining) {
    String version = wireVersion(session);
    record(Topic.WIRE, Level.WARN, version + "/incomplete-frame",
        () -> "Packet rejected: step=frame reason=incomplete bytesRemaining=" + remaining
            + " wire=" + version + context(session, scene(session)));
  }

  public static void packetFailure(GameSession session, int command, boolean core, String step,
      Throwable failure) {
    String version = wireVersion(session);
    String name = commandName(session, command, core);
    String reason = failure instanceof ProtocolException protocol ? protocol.diagnosticReason() : null;
    boolean wire = failure instanceof ProtocolException || step.equals("frame")
        || step.equals("bridge-inbound") || step.equals("kcp");
    String key = version + "/" + step + "/" + command + "/" + name + "/"
        + (reason == null ? signature(failure) : reason);
    record(wire ? Topic.WIRE : Topic.HANDLER, wire ? Level.WARN : Level.ERROR, key,
        () -> "Packet rejected: wire=" + version + " step=" + step + " command=" + command
            + " name=" + name + (reason == null ? "" : " reason=" + reason)
            + context(session, scene(session)) + " " + stack(failure));
  }

  public static void scenePlacement(Scene scene, int group, String kind, ObjectInfo source,
      Throwable failure) {
    int instance = source == null ? 0 : source.getID();
    int resource = switch (source) {
      case PropInfo prop -> prop.getPropID();
      case MonsterInfo monster -> monster.getNPCMonsterID();
      case NpcInfo npc -> npc.getNPCID();
      case null, default -> 0;
    };
    String reason = failure == null ? "missing-resource" : "load-failed";
    String placement = " group=" + group + " kind=" + kind + " instance=" + instance
        + " resource=" + resource;
    String key = location(scene) + placement + " " + reason + "/" + signature(failure);
    record(Topic.SCENE, Level.WARN, key,
        () -> "Scene placement skipped: reason=" + reason + placement
            + context(scene.getPlayer().getSession(), scene)
            + (failure == null ? "" : " " + stack(failure)));
  }

  /** Identifiers and reasons must come from numeric fields, enums or authored server code. */
  public static void missionGroupRejected(Scene scene,int group,int owner) {
    String place=" plane="+scene.getPlaneId()+" floor="+scene.getFloorId()
        +" dimension="+scene.getDimensionId()+" storyline="+scene.getStoryLineId()
        +" group="+group+" main="+owner;
    record(Topic.SCENE,Level.INFO,"inactive-mission-group"+place,
        ()->"Scene placement skipped: reason=inactive-mission-group"+place);
  }

  public static void dormantDebugGroup(Scene scene, int group) {
    String place = " plane=" + scene.getPlaneId() + " floor=" + scene.getFloorId()
        + " dimension=" + scene.getDimensionId() + " group=" + group;
    record(Topic.SCENE, Level.INFO, "dormant-debug-group" + place,
        () -> "Scene placement skipped: reason=dormant-debug-group" + place);
  }

  /** Identifiers and reasons must come from numeric fields, enums or authored server code. */
  public static void unavailableRaid(Player player, int plane, int floor, int group, int raid) {
    String place = " plane=" + plane + " floor=" + floor + " group=" + group + " raid=" + raid;
    record(Topic.SCENE, Level.WARN, "missing-raid" + place,
        () -> "Scene placement skipped: reason=missing-raid" + place
            + " session=" + (player.getSession() == null ? 0 : player.getSession().getDiagnosticId()));
  }

  /** Identifiers and reasons must come from numeric fields, enums or authored server code. */
  public static void outcome(GameSession session, String command, int subject, Retcode result,
      String reason, String details) {
    var scene = scene(session);
    boolean success = result == Retcode.SUCC;
    String code = result == null ? "unknown" : Integer.toString(result.getVal());
    String key = command + "/" + subject + "/" + reason + "/" + code + " " + location(scene);
    record(success ? Topic.PROGRESS : Topic.ACTION, success ? Level.INFO : Level.WARN, key,
        () -> "Gameplay result: command=" + command + " subject=" + subject + " retcode=" + code
            + " result=" + (result == null ? "unknown" : result.name()) + " reason=" + reason
            + context(session, scene) + (details.isEmpty() ? "" : " " + bounded(details)));
  }

  public static void tickFailure(Player player, Throwable failure) {
    var scene = player.getScene();
    record(Topic.TICK, Level.ERROR, location(scene) + "/" + signature(failure),
        () -> "Player tick error:" + context(player.getSession(), scene) + " " + stack(failure));
  }

  private static String signature(Throwable failure) {
    if (failure == null) return "";
    var frames = failure.getStackTrace();
    return failure.getClass().getSimpleName() + (frames.length == 0 ? "" : "@" + frames[0])
        + (frames.length < 2 ? "" : "<" + frames[1]);
  }

  private static String stack(Throwable failure) {
    var text = new StringBuilder("exception=");
    for (int cause = 0; cause < 2 && failure != null; cause++, failure = failure.getCause()) {
      if (cause > 0) text.append(" causedBy=");
      text.append(failure.getClass().getName());
      var frames = failure.getStackTrace();
      for (int i = 0; i < Math.min(frames.length, cause == 0 ? 6 : 3); i++)
        text.append(" <- ").append(frames[i]);
    }
    return text.toString();
  }

  private static String bounded(String details) {
    return details.length() <= 512 ? details : details.substring(0, 512) + "...";
  }

  private GameDiagnostics() {}
}
