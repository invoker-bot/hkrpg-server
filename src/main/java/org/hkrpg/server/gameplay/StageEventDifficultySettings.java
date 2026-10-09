package org.hkrpg.server.gameplay;

import com.google.gson.*;
import dev.morphia.annotations.Entity;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import lombok.Getter;

/** Pinned source-2 EventIDs; each actual event owns its preference. NPC/UI remain unverified. */
@Entity(useDiscriminator = false)
public final class StageEventDifficultySettings {
  private static final Gson GSON = new Gson();
  public static final int EVENT = 20003011;
  private static final Set<Integer> SINGLE_EVENTS =
      Set.of(
          20003011, 20004025, 20131015, 20132013, 20212013, 20221216, 20222020, 20231051, 20241202,
          20311050, 20321050, 20331053, 40123003, 20332072, 20411062, 20411070, 20413065, 20413072,
          20423080, 20451080, 20462060, 20462080, 10307030, 20501090, 20501201, 20511080, 20531080,
          20561080);
  private static final Map<Integer, List<Integer>> GROUPS =
      Map.ofEntries(
          Map.entry(20101012, List.of(20101011, 20101012)),
          Map.entry(20111010, List.of(20111009, 20111010)),
          Map.entry(20123009, List.of(20123009, 20123010)),
          Map.entry(20223206, List.of(20223205, 20223206)),
          Map.entry(20223217, List.of(20223217, 20223218)),
          Map.entry(20223222, List.of(20223222, 20223223)),
          Map.entry(20312050, List.of(20312050, 20312051)),
          Map.entry(20332050, List.of(20332050, 20332051)),
          Map.entry(10306051, List.of(10306051, 10306052, 10306053)),
          Map.entry(43001201, List.of(43001201, 43001206)),
          Map.entry(20242065, List.of(20242065, 20242066)),
          Map.entry(20421065, List.of(20421065, 20421066)),
          Map.entry(20421075, List.of(20421075, 20421076)),
          Map.entry(10441051, List.of(10441051, 10441052)));
  private static final Set<Integer> EVENTS = allEvents();

  private static Set<Integer> allEvents() {
    var events = new HashSet<>(SINGLE_EVENTS);
    for (var group : GROUPS.values()) events.addAll(group);
    return Set.copyOf(events);
  }

  private Map<Integer, Choice> choices = new HashMap<>();

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Choice {
    private int event, source, difficulty;
    private String resourceIdentity;

    protected Choice() {}

    private Choice(int event, int difficulty, String identity) {
      this.event = event;
      source = 2;
      this.difficulty = difficulty;
      resourceIdentity = identity;
    }

    boolean matches(String identity) {
      return EVENTS.contains(event)
          && source == 2
          && (difficulty == 0 || difficulty == 1)
          && identity != null
          && identity.equals(resourceIdentity);
    }
  }

  static String identity(int eventId) {
    if (!EVENTS.contains(eventId)) return null;
    int ownerId =
        GROUPS.entrySet().stream()
            .filter(entry -> entry.getValue().contains(eventId))
            .mapToInt(Map.Entry::getKey)
            .findFirst()
            .orElse(eventId);
    var expectedEvents = GROUPS.getOrDefault(ownerId, List.of(eventId));
    try {
      var path =
          Path.of(
              LunarCore.getConfig().getResourceDir(),
              "ExcelOutput",
              "DifficultyAdjustmentStage.json");
      if (!Files.isRegularFile(path) || Files.size(path) > 1024 * 1024) return null;
      var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
      if (rows.size() > 512) return null;
      JsonObject original = null;
      for (var raw : rows) {
        var row = raw.getAsJsonObject();
        var id = row.get("ID");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) return null;
        if (id.getAsBigDecimal().intValueExact() != ownerId) {
          // A second owner naming the same event makes source-2 identity ambiguous.
          var aliases = row.get("EventIDs");
          if (aliases == null || !aliases.isJsonArray()) return null;
          for (var alias : aliases.getAsJsonArray()) {
            if (!alias.isJsonPrimitive() || !alias.getAsJsonPrimitive().isNumber()) return null;
            if (alias.getAsBigDecimal().intValueExact() == eventId) return null;
          }
          continue;
        }
        if (original != null) return null;
        var eventsValue = row.get("EventIDs");
        if (eventsValue == null || !eventsValue.isJsonArray()) return null;
        var events = eventsValue.getAsJsonArray();
        if (events.size() != expectedEvents.size()) return null;
        for (int index = 0; index < events.size(); index++) {
          var alias = events.get(index);
          if (!alias.isJsonPrimitive()
              || !alias.getAsJsonPrimitive().isNumber()
              || alias.getAsBigDecimal().intValueExact() != expectedEvents.get(index)) return null;
        }
        original = row;
      }
      if (original == null) return null;
      var proof = new JsonArray();
      proof.add(original);
      for (int world = 0; world <= 6; world++) {
        var event = GameData.getPlaneEventExcelMap().get(eventId, world);
        var stage = SceneEventBattles.resolveClientEvent(eventId, world);
        if (event == null
            || stage == null
            || stage.getStageType() != StageType.Mainline
            || stage.getId() != eventId * 10 + world) return null;
        proof.add(GSON.toJsonTree(event));
        var image = new JsonObject();
        image.add("config", GSON.toJsonTree(stage));
        var waves = new JsonArray();
        for (var wave : stage.getMonsterWaves()) {
          var ids = new JsonArray();
          for (int id : wave) ids.add(id);
          waves.add(ids);
        }
        image.add("waves", waves);
        proof.add(image);
      }
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(proof.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.io.IOException
        | IllegalArgumentException
        | IllegalStateException
        | ArithmeticException
        | NoSuchAlgorithmException invalid) {
      return null;
    }
  }

  public List<Choice> snapshot() {
    if (choices == null || choices.size() > EVENTS.size())
      throw new IllegalStateException("Invalid saved stage-event difficulty choices");
    if (choices.isEmpty()) return List.of();
    for (var item : choices.entrySet())
      if (item.getKey() == null
          || !EVENTS.contains(item.getKey())
          || item.getValue() == null
          || item.getKey() != item.getValue().event
          || !item.getValue().matches(identity(item.getKey())))
        throw new IllegalStateException("Changed saved stage-event difficulty source");
    return choices.values().stream().sorted(Comparator.comparingInt(Choice::getEvent)).toList();
  }

  private int selected(int eventId) {
    try {
      var saved = snapshot();
      return saved.stream()
          .filter(choice -> choice.event == eventId)
          .mapToInt(Choice::getDifficulty)
          .findFirst()
          .orElse(0);
    } catch (IllegalStateException invalid) {
      return -1;
    }
  }

  static boolean ordinary(Player player) {
    var g = player.getGameplay();
    return player.getScene() != null
        && player.getScene().getPlayer() == player
        && player.getScene().getDimensionId() == 0
        && player.getScene().getPlaneId() == player.getPlaneId()
        && player.getScene().getFloorId() == player.getFloorId()
        && player.getInstance() == null
        && g.getCurrentMissionRaid() == 0
        && !g.getParkourVenue().inside()
        && g.getTrialRun() == null
        && g.getStoryLines().getCurrentId() == 0
        && !g.getStoryLines().isTransitionPending()
        && !g.getRogue().isInside();
  }

  public Retcode update(Player player, int event, int source, int difficulty) {
    synchronized (player) {
      if (!EVENTS.contains(event)
          || source != 2
          || difficulty < 0
          || difficulty > 1
          || player.getWorldLevel() < 1
          || player.getWorldLevel() > 6
          || player.isInBattle()
          || !ordinary(player)) return Retcode.REQ_PARA_INVALID;
      var identity = identity(event);
      if (identity == null) return Retcode.REQ_PARA_INVALID;
      try {
        snapshot();
      } catch (IllegalStateException invalid) {
        return Retcode.PLAYER_DATA_ERROR;
      }
      var previous = choices.get(event);
      if (previous != null && previous.difficulty == difficulty) return Retcode.SUCC;
      choices.put(event, new Choice(event, difficulty, identity));
      try {
        player.save();
      } catch (RuntimeException failed) {
        if (previous == null) choices.remove(event);
        else choices.put(event, previous);
        return Retcode.SYSTEM_BUSY;
      }
      return Retcode.SUCC;
    }
  }

  public record Plan(
      Player owner,
      Scene scene,
      int actualWorld,
      int world,
      int difficulty,
      int plane,
      int floor,
      int entry,
      int event,
      StageExcel stage,
      String identity) {
    public Plan {
      if (owner == null
          || scene == null
          || identity == null
          || stage == null
          || actualWorld < 0
          || actualWorld > 6
          || difficulty < 0
          || difficulty > 1
          || plane <= 0
          || floor <= 0
          || entry <= 0
          || world < 0
          || world != actualWorld - difficulty
          || !EVENTS.contains(event)
          || stage.getId() != event * 10 + world)
        throw new IllegalArgumentException("Invalid stage-event difficulty plan");
    }

    boolean matches(Player player, Battle battle) {
      if (player != owner
          || player.getBattle() != battle
          || battle.getPlayer() != player
          || player.getScene() != scene
          || player.getWorldLevel() != actualWorld
          || player.getPlaneId() != plane
          || player.getFloorId() != floor
          || player.getEntryId() != entry
          || !ordinary(player)
          || !Objects.equals(identity, StageEventDifficultySettings.identity(event))
          || player.getGameplay().getStageEventDifficultySettings().selected(event) < 0
          || (actualWorld == 0
                  ? 0
                  : player.getGameplay().getStageEventDifficultySettings().selected(event))
              != difficulty
          || battle.getStage() != stage
          || SceneEventBattles.resolveClientEvent(event, world) != stage
          || battle.getWaves().size() != stage.getMonsterWaves().size()) return false;
      for (int i = 0; i < battle.getWaves().size(); i++) {
        var wave = battle.getWaves().get(i);
        if (wave.getStage() != stage
            || wave.getCustomLevel() != 0
            || !wave.getMonsters().equals(stage.getMonsterWaves().get(i))) return false;
      }
      return true;
    }
  }

  public static Plan plan(Player player, int event) {
    if (!EVENTS.contains(event) || player.isInBattle() || !ordinary(player)) return null;
    int actual = player.getWorldLevel();
    if (actual < 0 || actual > 6) return null;
    String identity = identity(event);
    int selected = player.getGameplay().getStageEventDifficultySettings().selected(event);
    if (identity == null || selected < 0) return null;
    int difficulty = actual == 0 ? 0 : selected;
    var stage = SceneEventBattles.resolveClientEvent(event, actual - difficulty);
    return stage == null
        ? null
        : new Plan(
            player,
            player.getScene(),
            actual,
            actual - difficulty,
            difficulty,
            player.getPlaneId(),
            player.getFloorId(),
            player.getEntryId(),
            event,
            stage,
            identity);
  }

  public static boolean applies(Player player, int event) {
    var g = player.getGameplay();
    return EVENTS.contains(event)
        && "4.6.0".equals(LunarCore.getConfig().protocol.wireVersion)
        && player.getInstance() == null
        && g.getCurrentMissionRaid() == 0
        && !g.getParkourVenue().inside()
        && g.getTrialRun() == null
        && g.getStoryLines().getCurrentId() == 0
        && !g.getStoryLines().isTransitionPending()
        && !g.getRogue().isInside();
  }

  public static int world(Player player, int event) {
    if (!applies(player, event)) return RaidDifficultySettings.world(player);
    var plan = plan(player, event);
    return plan == null ? -1 : plan.world();
  }

  public static int world(Player player, Battle battle) {
    return battle instanceof StageEventDifficultyBattle adjusted
        ? (adjusted.matches(player) ? adjusted.effectiveWorld() : -1)
        : RaidDifficultySettings.world(player);
  }
}
