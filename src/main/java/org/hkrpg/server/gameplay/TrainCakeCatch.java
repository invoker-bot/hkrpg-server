package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.random.RandomGenerator;

/** Local box compatibility. Original server-side gacha pools are not in client resources. */
public final class TrainCakeCatch {
  public record Result(Retcode retcode, TrainCakeCatchState state, int cat) {}
  private record Catalog(String source) {}

  // AGPL March7thHoney fe00bdb, TrainCakeCatchManager.OpenBox's initial compatible placements.
  // These are a local compatibility batch, not a claim about the unavailable official gacha weights.
  private static final List<TrainCakeCatchState.Spot> INITIAL = List.of(
      new TrainCakeCatchState.Spot(10, 4), new TrainCakeCatchState.Spot(21, 6),
      new TrainCakeCatchState.Spot(26, 5));
  static List<TrainCakeCatchState.Spot> initialBatch() { return INITIAL; }

  public static long now() { return Math.floorDiv(LunarCore.currentServerTime(), 1000); }

  public static long nextRefresh(long now) {
    var local = Instant.ofEpochSecond(now).atZone(ZoneId.of("Asia/Shanghai"));
    var start = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atTime(4, 0).atZone(local.getZone());
    if (local.isBefore(start)) start = start.minusWeeks(1);
    return start.plusWeeks(1).toEpochSecond();
  }

  private static Catalog catalog() throws Exception {
    var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
    if (Files.isDirectory(root.resolve("4.6"))) root = root.resolve("4.6");
    var digest = MessageDigest.getInstance("SHA-256");
    var tables = new HashMap<String, JsonElement>();
    for (var path : List.of("ExcelOutput/CakeConfig.json", "ExcelOutput/CakeCatchConstValueCommon.json",
        "ExcelOutput/MainMission.json",
        "ExcelOutput/FuncUnlockData.json", "Config/LevelOutput/SharedRuntimeGroup/Groups_P10000_F10000003/LevelGroup_P10000_F10000003_G240.json")) {
      byte[] bytes = Files.readAllBytes(root.resolve(path));
      digest.update(path.getBytes(StandardCharsets.UTF_8));
      digest.update(bytes);
      tables.put(path, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)));
    }
    var cats = new TreeSet<Integer>();
    for (var raw : tables.get("ExcelOutput/CakeConfig.json").getAsJsonArray()) {
      int id = raw.getAsJsonObject().get("ID").getAsInt();
      if (id < 1 || id > 27 || !cats.add(id)) throw new IllegalArgumentException("Cat catalog");
    }
    if (cats.size() != 27) throw new IllegalArgumentException("Cat catalog incomplete");
    var constants = new HashMap<String, JsonObject>();
    for (var raw : tables.get("ExcelOutput/CakeCatchConstValueCommon.json").getAsJsonArray()) {
      var row = raw.getAsJsonObject(); constants.put(row.get("ConstValueName").getAsString(), row.getAsJsonObject("Value"));
    }
    if (constants.get("CatCatch_OpenBox").get("IntValue").getAsInt() != 3
        || constants.get("CatCatch_Week_Max").get("IntValue").getAsInt() != 9
        || constants.get("HideAndSeek_All").get("IntValue").getAsInt() != 7
        || constants.get("EveryCat_MaxQuantity").get("IntValue").getAsInt() != 13
        || constants.get("EveryCat_UnlockPose_01").get("IntValue").getAsInt() != 3
        || constants.get("EveryCat_UnlockPose_02").get("IntValue").getAsInt() != 6
        || constants.get("FreeRange_MaxSlot").get("IntValue").getAsInt() != 3
        || constants.get("CatTree_MaxSlot").get("IntValue").getAsInt() != 4
        || constants.get("Cat_MaxSlot").get("IntValue").getAsInt() != 13
        || constants.get("SocialPlay_Floor_ID").get("IntValue").getAsInt() != 10000003)
      throw new IllegalArgumentException("Box constants changed");
    var box = constants.get("CatBox_GroupInstanceID").getAsJsonArray("ArrayValue");
    if (box.size() != 2 || box.get(0).getAsJsonObject().get("IntValue").getAsInt() != 240
        || box.get(1).getAsJsonObject().get("IntValue").getAsInt() != 300001)
      throw new IllegalArgumentException("Box identity changed");
    boolean unlock = false;
    for (var raw : tables.get("ExcelOutput/FuncUnlockData.json").getAsJsonArray()) {
      var row = raw.getAsJsonObject();
      if (row.get("UnlockID").getAsInt() != 5100) continue;
      var conditions = row.getAsJsonArray("Conditions");
      unlock = conditions.size() == 1
          && conditions.get(0).getAsJsonObject().get("Type").getAsString().equals("FinishSubMission")
          && conditions.get(0).getAsJsonObject().get("Param").getAsString().equals("402080005");
    }
    if (!unlock) throw new IllegalArgumentException("Box unlock changed");
    boolean level = false;
    for (var raw : tables.get("ExcelOutput/MainMission.json").getAsJsonArray()) {
      var row = raw.getAsJsonObject();
      if (row.get("MainMissionID").getAsInt() != 4020800) continue;
      var take = row.getAsJsonArray("TakeParam");
      level = row.get("TakeOperation").getAsString().equals("And") && take.size() == 1
          && take.get(0).getAsJsonObject().get("Type").getAsString().equals("PlayerLevel")
          && take.get(0).getAsJsonObject().get("Value").getAsInt() == 21;
    }
    if (!level) throw new IllegalArgumentException("Introduction gate changed");
    var group = tables.entrySet().stream().filter(e -> e.getKey().contains("SharedRuntimeGroup"))
        .findFirst().orElseThrow().getValue().getAsJsonObject();
    var props = group.getAsJsonArray("PropList");
    if (props.size() != 1 || props.get(0).getAsJsonObject().get("ID").getAsInt() != 300001
        || props.get(0).getAsJsonObject().get("PropID").getAsInt() != 945)
      throw new IllegalArgumentException("Original box placement changed");
    return new Catalog(HexFormat.of().formatHex(digest.digest()));
  }

  static String source() {
    try { return catalog().source(); }
    catch (Exception invalid) { throw new IllegalStateException("Cat box source unavailable", invalid); }
  }

  private static Retcode admission(Player p, boolean socialRoom) {
    var scene = p.getScene();
    if (scene == null || scene.getPlaneId() != 10000 || scene.getFloorId() != 10000003
        || scene.getDimensionId() != (socialRoom ? 11001 : 0) || scene.getStoryLineId() != 0) return Retcode.NOT_IN_SCENE;
    if (p.getBattle() != null || p.getInstance() != null) return Retcode.SYSTEM_BUSY;
    if (scene.getEntities().values().stream().noneMatch(e -> e instanceof EntityProp box
        && box.getGroupId() == 240 && box.getInstId() == 300001 && box.getPropId() == 945))
      return Retcode.SCENE_ENTITY_NOT_EXIST;
    // MainMission 4020800's original TakeParam; do not bypass it for an exposed scene button.
    if (p.getLevel() < 21) return Retcode.PLAYER_LEVEL_NOT_MEET;
    var gate = p.getGameplay().getSubMissions().get(402080005);
    if (gate == null || gate.getPhase() != QuestState.Phase.FINISHED
        && gate.getPhase() != QuestState.Phase.CLOSED) return Retcode.PLOT_NOT_UNLOCK;
    return Retcode.SUCC;
  }

  public static Retcode admissionForDiy(Player player, boolean socialRoom) {
    return admission(player, socialRoom);
  }

  public static TrainCakeCatchState snapshot(Player p) {
    var state = p.getGameplay().getTrainCakeCatch();
    if (state == null || !state.valid()) throw new IllegalStateException("Invalid saved cat box");
    if (state.getRefreshAt() != 0) try {
      if (!state.getSource().equals(catalog().source())) throw new IllegalStateException("Cat box source changed");
    } catch (Exception invalid) { throw new IllegalStateException("Cat box source unavailable"); }
    return state;
  }

  public static int weeklyRemaining(Player p, long now) {
    try {
      var source = catalog();
      var state = p.getGameplay().getTrainCakeCatch();
      if (state == null || !state.valid()
          || state.getRefreshAt() != 0 && !state.getSource().equals(source.source()))
        throw new IllegalStateException("Invalid saved cat box");
      int own = state.getRefreshAt() > now ? state.getCaught().size() : 0;
      int remaining = 9 - own - TrainCakeVisits.weeklyCount(p, now, source.source());
      if (remaining < 0) throw new IllegalStateException("Invalid weekly cat count");
      return remaining;
    } catch (Exception invalid) {
      throw new IllegalStateException("Cat box week source unavailable", invalid);
    }
  }

  public static Result open(Player p, long now) {
    synchronized (p) { return openLocked(p, now, false); }
  }

  /** Caller must first establish that this player is in their own authenticated room. */
  public static Result openInOwnRoom(Player p, long now) {
    synchronized (p) { return openLocked(p, now, true); }
  }

  private static Result openLocked(Player p, long now, boolean socialRoom) {
    var state = p.getGameplay().getTrainCakeCatch();
    var code = admission(p, socialRoom);
    if (code != Retcode.SUCC) return new Result(code, state, 0);
    try {
      if (state == null || !state.valid()) return new Result(Retcode.PLAYER_DATA_ERROR, state, 0);
      var source = catalog();
      if (state.getRefreshAt() != 0 && !state.getSource().equals(source.source()))
        return new Result(Retcode.PLAYER_DATA_ERROR, state, 0);
      // A visitor may already have opened this room, including while the owner was offline.
      // The durable room batch is authoritative; never redraw or save a stale owner document.
      var room = TrainCakeRoomStore.forPlayer(p).open(p, now);
      if (state.getRefreshAt() == room.refreshAt()) return new Result(Retcode.SUCC, state, 0);
      var next = new TrainCakeCatchState(source.source(), room.refreshAt(), room.spots(), Set.of(), state.getCounts());
      return commit(p, state, next, 0);
    } catch (com.mongodb.MongoException failed) {
      org.hkrpg.server.GameDiagnostics.saveFailed(p, "train-cake-room", 0, 0, failed);
      return new Result(Retcode.SERVER_INTERNAL_ERROR, state, 0);
    } catch (Exception invalid) {
      return new Result(Retcode.PLAYER_DATA_ERROR, state, 0);
    }
  }

  public static Result search(Player p, int cat, long now) {
    synchronized (p) { return searchLocked(p, cat, now, false); }
  }

  /** Caller must first establish that this player is in their own authenticated room. */
  public static Result searchInOwnRoom(Player p, int cat, long now) {
    synchronized (p) { return searchLocked(p, cat, now, true); }
  }

  private static Result searchLocked(Player p, int cat, long now, boolean socialRoom) {
    var state = p.getGameplay().getTrainCakeCatch();
    var code = admission(p, socialRoom);
    if (code != Retcode.SUCC) return new Result(code, state, cat);
    try {
      if (state == null || !state.valid()
          || state.getRefreshAt() != 0 && !state.getSource().equals(catalog().source()))
        return new Result(Retcode.PLAYER_DATA_ERROR, state, cat);
      if (state.getRefreshAt() <= now) {
        var room = TrainCakeRoomStore.forPlayer(p).peek(p.getUid(), now);
        if (room == null) return new Result(Retcode.PLAYER_DATA_ERROR, state, cat);
        if (room.spots().stream().noneMatch(spot -> spot.getCat() == cat))
          return new Result(Retcode.REQ_PARA_INVALID, state, cat);
        var adopted = openLocked(p, now, socialRoom);
        if (adopted.retcode() != Retcode.SUCC) return new Result(adopted.retcode(), state, cat);
        state = adopted.state();
      }
      if (state.getSpots().stream().noneMatch(s -> s.getCat() == cat))
        return new Result(Retcode.REQ_PARA_INVALID, state, cat);
      if (state.getCaught().contains(cat)) return new Result(Retcode.SUCC, state, cat);
      if (weeklyRemaining(p, now) <= 0) return new Result(Retcode.INTERACT_TYPE_INVALID, state, cat);
      var counts = new TreeMap<>(state.getCounts());
      int count = counts.getOrDefault(cat, 0);
      if (count + TrainCakeVisits.counts(p).getOrDefault(cat, 0) >= 13)
        return new Result(Retcode.INTERACT_TYPE_INVALID, state, cat);
      counts.put(cat, count + 1);
      var caught = new TreeSet<>(state.getCaught()); caught.add(cat);
      var next = new TrainCakeCatchState(state.getSource(), state.getRefreshAt(), state.getSpots(), caught, counts);
      return commit(p, state, next, cat);
    } catch (com.mongodb.MongoException failed) {
      org.hkrpg.server.GameDiagnostics.saveFailed(p, "train-cake-room", cat, 0, failed);
      return new Result(Retcode.SERVER_INTERNAL_ERROR, state, cat);
    } catch (Exception invalid) {
      return new Result(Retcode.PLAYER_DATA_ERROR, state, cat);
    }
  }

  /** Draw unique cats and unique locations without relying on unavailable official weights. */
  static List<TrainCakeCatchState.Spot> randomBatch(RandomGenerator random) {
    Objects.requireNonNull(random);
    var cats = new ArrayList<Integer>();
    var slots = new ArrayList<Integer>();
    for (int id = 1; id <= 27; id++) cats.add(id);
    for (int slot = 0; slot < 7; slot++) slots.add(slot);
    var result = new ArrayList<TrainCakeCatchState.Spot>();
    for (int i = 0; i < 3; i++) {
      int cat = cats.remove(random.nextInt(cats.size()));
      int slot = slots.remove(random.nextInt(slots.size()));
      result.add(new TrainCakeCatchState.Spot(cat, slot));
    }
    return List.copyOf(result);
  }

  private static Result commit(Player p, TrainCakeCatchState previous, TrainCakeCatchState next, int cat) {
    if (!next.valid()) return new Result(Retcode.PLAYER_DATA_ERROR, previous, cat);
    p.getGameplay().trainCakeCatch(next);
    try { p.save(); return new Result(Retcode.SUCC, next, cat); }
    catch (RuntimeException failed) {
      p.getGameplay().trainCakeCatch(previous);
      org.hkrpg.server.GameDiagnostics.saveFailed(p, "train-cake", cat, 0, failed);
      return new Result(Retcode.SERVER_INTERNAL_ERROR, previous, cat);
    }
  }

  private TrainCakeCatch() {}
}
