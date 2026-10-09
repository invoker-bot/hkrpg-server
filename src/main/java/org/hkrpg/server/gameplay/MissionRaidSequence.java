package org.hkrpg.server.gameplay;

import com.google.gson.*;
import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import java.util.*;
import lombok.Getter;

/** A frozen authored chain; a saved ordinal alone cannot authorize its next main mission. */
@Entity(useDiscriminator = false)
@Getter
public final class MissionRaidSequence {
  private static final Gson GSON = new Gson();
  private List<Integer> mains = new ArrayList<>();
  private String source;
  private int stage;

  protected MissionRaidSequence() {}

  static boolean required(GameplayDefinitions.Raid row) {
    return row != null && row.MainMissionIDList() != null
        && (row.MainMissionIDList().size() > 1 || RaidEntryRecovery.none(row));
  }

  MissionRaidSequence(GameplayDefinitions.Raid row) {
    if (!supported(row)) throw new IllegalArgumentException("Unsupported raid mission sequence");
    mains.addAll(row.MainMissionIDList());
    source = signature(row);
  }

  static boolean supported(GameplayDefinitions.Raid row) {
    var ids = row == null ? null : row.MainMissionIDList();
    if (ids == null
        || ids.size() < (RaidEntryRecovery.none(row) ? 1 : 2)
        || ids.size() > 64
        || ids.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(ids).size() != ids.size()) return false;
    for (int i = 0; i < ids.size(); i++) {
      int main = ids.get(i);
      var config = GameData.getMainMissionExcelMap().get(main);
      var take =
          new MainMissionExcel.Gate(
              i == 0 ? "Manual" : "MultiSequence", i == 0 ? 0 : ids.get(i - 1));
      if (config == null
          || config.getId() != main
          || !config.isInRaid()
          || !"And".equals(config.getTakeOperation())
          || !List.of(take).equals(config.getTakeParam())
          || !"And".equals(config.getBeginOperation())
          || !List.of(new MainMissionExcel.Gate("Auto", 0)).equals(config.getBeginParam())
          || !sourceSupported(main)) return false;
    }
    return true;
  }

  static boolean sourceSupported(int main) {
    var info = GameData.getMissionInfos().get(main);
    if (info == null
        || info.getMainMissionID() != main
        || info.getSubMissionList() == null
        || info.getSubMissionList().length == 0) return false;
    var subs = new HashSet<Integer>();
    for (var sub : info.getSubMissionList()) {
      if (sub == null
          || sub.getId() <= 0
          || sub.getMainMissionID() != main
          || GameplayDefinitions.subs.get(sub.getId()) != sub
          || !subs.add(sub.getId())) return false;
    }
    return endpoints(info.getStartSubMissionList(), subs)
        && endpoints(info.getFinishSubMissionList(), subs);
  }

  private static boolean endpoints(int[] ids, Set<Integer> subs) {
    return ids != null
        && ids.length > 0
        && Arrays.stream(ids).distinct().count() == ids.length
        && Arrays.stream(ids).allMatch(subs::contains);
  }

  static String signature(GameplayDefinitions.Raid row) {
    var result = new JsonObject();
    result.add("raid", GSON.toJsonTree(row));
    if (!"SkipUI".equals(row.EnterType()) || RaidEntryRecovery.none(row))
      result.add("originalEntrance", RaidEntranceSources.fingerprint(row));
    var definitions = new JsonArray();
    for (int main : row.MainMissionIDList()) {
      var value = new JsonObject();
      value.add("excel", GSON.toJsonTree(GameData.getMainMissionExcelMap().get(main)));
      var info = GameData.getMissionInfos().get(main);
      var root = GSON.toJsonTree(info).getAsJsonObject();
      root.remove("SubMissionList");
      value.add("root", root);
      var subs = new JsonArray();
      Arrays.stream(info.getSubMissionList())
          .sorted(Comparator.comparingInt(MissionInfo.SubMissionInfo::getId))
          .forEach(
              sub -> {
                var definition = GSON.toJsonTree(sub).getAsJsonObject();
                definition.addProperty(
                    "originalTask",
                    sub.getTask() == null ? "" : sub.getTask().getSourceDefinition());
                subs.add(definition);
              });
      value.add("subs", subs);
      definitions.add(value);
    }
    result.add("mains", definitions);
    return result.toString();
  }

  boolean matches(GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    return supported(row)
        && mains != null
        && mains.equals(row.MainMissionIDList())
        && stage >= 0
        && stage < mains.size()
        && run.getMainMission() == mains.get(stage)
        && source != null
        && source.equals(signature(row));
  }

  static boolean finished(Player player, GameplayState.MissionRaidRun run, int main) {
    var info = GameData.getMissionInfos().get(main);
    return MissionStateRecords.mainCompleted(player, main)
        && info != null
        && info.getFinishSubMissionList() != null
        && info.getFinishSubMissionList().length > 0
        && Arrays.stream(info.getFinishSubMissionList())
            .allMatch(
                id -> {
                  var sub = GameplayDefinitions.subs.get(id);
                  return sub != null
                      && sub.getMainMissionID() == main
                      && proof(player, run, main, id, new HashSet<>());
                });
  }

  private static boolean proof(
      Player player, GameplayState.MissionRaidRun run, int main, int id, Set<Integer> visiting) {
    var sub = GameplayDefinitions.subs.get(id);
    if (sub == null
        || sub.getMainMissionID() != main
        || !MissionStateRecords.subCompleted(player, id)
        || !visiting.add(id)) return false;
    try {
      if ("EnterRaidScene".equals(sub.getFinishType())
          && (!transferTask(sub)
              || sub.getParamInt1() != run.getRaidId()
              || !Objects.equals(run.getTransferredSubs().get(id), sub.getParamInt2())))
        return false;
      if ("Auto".equals(sub.getTakeType())) return true;
      var gates = sub.getTakeParamIntList();
      if (gates == null || gates.length == 0) return false;
      return switch (Objects.toString(sub.getTakeType(), "")) {
        case "AnySequence" ->
            Arrays.stream(gates)
                .anyMatch(gate -> gate != null && proof(player, run, main, gate, visiting));
        case "MultiSequence" ->
            Arrays.stream(gates)
                .allMatch(gate -> gate != null && proof(player, run, main, gate, visiting));
        default -> false;
      };
    } finally {
      visiting.remove(id);
    }
  }

  boolean consistent(
      Player player, GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    if (!matches(run, row)) return false;
    for (int i = 0; i < mains.size(); i++) {
      int main = mains.get(i);
      if (player.getGameplay().getCancelledMissions().contains(main)) return false;
      if (i < stage && !finished(player, run, main)) return false;
      if (i == stage
          && player.getGameplay().getMainMissions().containsKey(main)
          && !MissionStateRecords.mainAccepted(player, main)) return false;
      if (i > stage
          && (player.getGameplay().getMainMissions().containsKey(main)
              || Arrays.stream(GameData.getMissionInfos().get(main).getSubMissionList())
                  .anyMatch(sub -> player.getGameplay().getSubMissions().containsKey(sub.getId()))))
        return false;
    }
    return true;
  }

  boolean completed(Player player, GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    return consistent(player, run, row)
        && stage == mains.size() - 1
        && mains.stream().allMatch(main -> finished(player, run, main));
  }

  int next() {
    return stage + 1 < mains.size() ? mains.get(stage + 1) : 0;
  }

  void advance() {
    if (next() == 0) throw new IllegalStateException("No next raid mission stage");
    stage++;
  }

  /** Bare original transfer tasks only; mixed or unknown graphs need separate interpretation. */
  static boolean transferTask(MissionInfo.SubMissionInfo sub) {
    try {
      if (sub.getTask() == null || sub.getTask().getSourceDefinition().isBlank()) return false;
      var root = JsonParser.parseString(sub.getTask().getSourceDefinition()).getAsJsonObject();
      if (!Set.of("Type", "OnInitSequece", "OnStartSequece").equals(root.keySet())
          || !"Mission".equals(root.get("Type").getAsString())
          || !root.getAsJsonArray("OnInitSequece").isEmpty()) return false;
      var sequences = root.getAsJsonArray("OnStartSequece");
      if (sequences.size() != 1) return false;
      var sequence = sequences.get(0).getAsJsonObject();
      if (!Set.of("TaskList").equals(sequence.keySet())) return false;
      var tasks = sequence.getAsJsonArray("TaskList");
      if (tasks.size() != 1) return false;
      var task = tasks.get(0).getAsJsonObject();
      return Set.of("$type").equals(task.keySet())
          && "RPG.GameCore.RaidSceneTransfer".equals(task.get("$type").getAsString());
    } catch (IllegalStateException | IllegalArgumentException | NullPointerException ex) {
      return false;
    }
  }
}
