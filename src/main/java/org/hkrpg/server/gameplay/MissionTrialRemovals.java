package org.hkrpg.server.gameplay;

import com.google.gson.*;
import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import java.util.*;
import lombok.Getter;

/** Complete DestroyTrialPlayer / self-performance / CreateNPC graphs in ordinary mission scenes. */
public final class MissionTrialRemovals {
  private static final Gson GSON = new Gson();

  record Source(
      int sub, int main, int plane, int floor, int avatar, int group, int npc, String signature) {}

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Receipt {
    private String source;
    private String openingSource;
    private Map<Integer, String> creations = new HashMap<>();

    protected Receipt() {}

    Receipt(Source source, MissionTrialState.Party party) {
      this.source = source.signature();
      if (source.avatar() == OpeningPlayer.KAFKA) openingSource = party.getOpeningSource();
      party.getSources().forEach((id, signature) -> {
        var creation = TrialAvatarMissionConditions.source(GameplayDefinitions.subs.get(id));
        if (creation != null && creation.targets().contains(source.avatar()))
          creations.put(id, signature);
      });
    }
  }

  private static boolean instruction(JsonObject row, String name, Set<String> keys) {
    return row.keySet().equals(keys)
        && row.get("$type").isJsonPrimitive()
        && row.getAsJsonPrimitive("$type").isString()
        && name.equals(row.get("$type").getAsString());
  }

  private static int constant(JsonElement value) {
    var object = value.getAsJsonObject();
    if (!object.keySet().equals(Set.of("IsDynamic", "FixedValue"))
        || !object.get("IsDynamic").isJsonPrimitive()
        || !object.getAsJsonPrimitive("IsDynamic").isBoolean()
        || object.get("IsDynamic").getAsBoolean()) return 0;
    var fixed = object.getAsJsonObject("FixedValue");
    return fixed.keySet().equals(Set.of("Value"))
            && fixed.get("Value").isJsonPrimitive()
            && fixed.getAsJsonPrimitive("Value").isNumber()
        ? fixed.get("Value").getAsBigDecimal().intValueExact()
        : 0;
  }

  private static JsonArray tasks(JsonElement sequence) {
    var object = sequence.getAsJsonObject();
    return object.keySet().equals(Set.of("TaskList")) ? object.getAsJsonArray("TaskList") : null;
  }

  static Source source(Player player, MissionInfo.SubMissionInfo c) {
    return source(player, player.getScene(), c);
  }

  private static Source source(Player player, Scene scene, MissionInfo.SubMissionInfo c) {
    try {
      if (c == null
          || GameplayDefinitions.subs.get(c.getId()) != c
          || !"DelTrialAvatar".equals(c.getFinishType())
          || !"Equal".equals(c.getParamType())
          || c.getId() <= 0
          || c.getMainMissionID() <= 0
          || c.getParamInt1() <= 0
          || c.getParamInt2() != 0
          || c.getParamInt3() != 0
          || !c.getParamStr1().isBlank()
          || c.getParamIntList().length != 0
          || !c.getParamItemList().isEmpty()
          || c.getProgress() != 1
          || c.isBackTrack()
          || c.getFinishActionList().length != 0
          || c.getCustomValueList().length != 0
          || c.getCustomValueReward().length != 0
          || !c.getGroupIDList().isEmpty()
          || !c.getRequiredNPCSeriesIDList().isEmpty()
          || c.getMazePlaneID() != 0
          || c.getMazeFloorID() != 0
          || c.getStoryLineID() != 0
          || !c.getStoryLineIDList().isEmpty()
          || !c.getIgnoreVerseParamList().isEmpty()
          || c.getTask() == null
          || c.getTask().getSourceDefinition() == null
          || c.getTask().getSourceDefinition().length() > 8192
          || scene == null
          || scene.getPlayer() != player
          || scene.getPlaneId() != c.getLevelPlaneID()
          || scene.getFloorId() != c.getLevelFloorID()
          || scene.getDimensionId() != 0
          || scene.getStoryLineId() != 0
          || scene.getFloorInfo() == null
          || !Set.of(PlaneType.Town, PlaneType.Maze, PlaneType.Train)
              .contains(scene.getPlaneType())) return null;
      var root = JsonParser.parseString(c.getTask().getSourceDefinition()).getAsJsonObject();
      if (!root.keySet().equals(Set.of("Type", "OnInitSequece", "OnStartSequece"))
          || !root.get("Type").isJsonPrimitive()
          || !root.getAsJsonPrimitive("Type").isString()
          || !"Mission".equals(root.get("Type").getAsString())
          || !root.getAsJsonArray("OnInitSequece").isEmpty()) return null;
      var start = root.getAsJsonArray("OnStartSequece");
      if (OpeningPlayer.removal(c)) {
        var signature = new JsonObject();
        signature.add("sub", GSON.toJsonTree(c));
        signature.add("wholeTask", root);
        return new Source(c.getId(), c.getMainMissionID(), c.getLevelPlaneID(), c.getLevelFloorID(),
            c.getParamInt1(), 0, 0, signature.toString());
      }
      if (start.size() != 2) return null;
      var remove = tasks(start.get(0));
      var spawn = tasks(start.get(1));
      if (remove == null
          || spawn == null
          || remove.size() != 2
          || spawn.size() != 1
          || !instruction(
              remove.get(0).getAsJsonObject(), "RPG.GameCore.DestroyTrialPlayer", Set.of("$type"))
          || !instruction(
              remove.get(1).getAsJsonObject(),
              "RPG.GameCore.FinishPerformanceMission",
              Set.of("$type", "Key"))
          || !remove.get(1).getAsJsonObject().get("Key").isJsonPrimitive()
          || !remove.get(1).getAsJsonObject().getAsJsonPrimitive("Key").isString()
          || !("Mission_" + c.getId())
              .equals(remove.get(1).getAsJsonObject().get("Key").getAsString())
          || !instruction(
              spawn.get(0).getAsJsonObject(),
              "RPG.GameCore.CreateNPC",
              Set.of("$type", "GroupID", "GroupNpcID"))) return null;
      var npcTask = spawn.get(0).getAsJsonObject();
      int groupId = constant(npcTask.get("GroupID")), npcId = constant(npcTask.get("GroupNpcID"));
      var group = scene.getResourceGroups().get(groupId);
      if (groupId <= 0
          || npcId <= 0
          || group == null
          || group.getId() != groupId
          || group.getOwnerMainMissionID() != c.getMainMissionID()
          || group.getCategory() != GroupInfo.GroupCategory.Mission
          || group.getLoadSide() != GroupInfo.GroupLoadSide.Server
          || !scene.getFloorInfo().isGroupInDimension(groupId, 0)
          || group.getNPCList() == null) return null;
      var matches =
          group.getNPCList().stream().filter(n -> n != null && n.getID() == npcId).toList();
      if (matches.size() != 1) return null;
      var npc = matches.getFirst();
      if (npc.isIsDelete()
          || npc.isIsClientOnly()
          || npc.isLoadOnInitial()
          || npc.getNPCID() <= 0
          || GameData.getNpcExcelMap().get(npc.getNPCID()) == null) return null;
      var signature = new JsonObject();
      signature.add("sub", GSON.toJsonTree(c));
      signature.add("wholeTask", root);
      signature.add("group", GSON.toJsonTree(group));
      signature.add("npcExcel", GSON.toJsonTree(GameData.getNpcExcelMap().get(npc.getNPCID())));
      return new Source(
          c.getId(),
          c.getMainMissionID(),
          c.getLevelPlaneID(),
          c.getLevelFloorID(),
          c.getParamInt1(),
          groupId,
          npcId,
          signature.toString());
    } catch (JsonParseException
        | IllegalArgumentException
        | IllegalStateException
        | NullPointerException
        | ArithmeticException invalid) {
      return null;
    }
  }

  static boolean context(Player player, Source source) {
    return missionContext(player, source)
        && player.getPlaneId() == source.plane()
        && player.getFloorId() == source.floor();
  }

  private static boolean missionContext(Player player, Source source) {
    return source != null
        && !player.isInBattle()
        && player.getInstance() == null
        && player.getGameplay().getCurrentMissionRaid() == 0
        && player.getGameplay().getTrialRun() == null
        && !player.getGameplay().getRogue().isInside()
        && !player.getGameplay().getParkourVenue().inside()
        && player.getGameplay().getStoryLines().getCurrentId() == 0
        && !player.getGameplay().getStoryLines().isTransitionPending()
        && MissionStateRecords.mainDoing(player, source.main())
        && !player.getGameplay().getCancelledMissions().contains(source.main())
        && !player.getGameplay().getCancelledSubs().contains(source.sub());
  }

  private static boolean matches(Player player, Source source, Receipt receipt) {
    return matches(player, source, receipt, null);
  }

  private static boolean matches(Player player, Source source, Receipt receipt, Scene destination) {
    var state = player.getGameplay().getMissionTrials();
    boolean validContext =
        destination == null
            ? context(player, source)
            : missionContext(player, source)
                && destination.getPlayer() == player
                && destination.getPlaneId() == source.plane()
                && destination.getFloorId() == source.floor();
    if (!validContext
        || receipt == null
        || !source.signature().equals(receipt.getSource())
        || receipt.getCreations() == null
        || receipt.getCreations().isEmpty() && receipt.getOpeningSource() == null
        || receipt.getCreations().size() > 64) return false;
    if (receipt.getOpeningSource() != null) {
      if (source.sub() != OpeningPlayer.REMOVE || source.avatar() != OpeningPlayer.KAFKA
          || !receipt.getCreations().isEmpty()
          || !receipt.getOpeningSource().equals(OpeningPlayer.source())
          || !receipt.getOpeningSource().equals(state.getOpeningSource())
          || !MissionStateRecords.subAccepted(player, OpeningPlayer.INTRO)
          || !MissionStateRecords.subCompleted(player, 100010112)
          || !player.hasCreatedCharacter()) return false;
    }
    for (var entry : receipt.getCreations().entrySet()) {
      var creation =
          TrialAvatarMissionConditions.source(GameplayDefinitions.subs.get(entry.getKey()));
      if (creation == null
          || creation.main() != source.main()
          || creation.plane() != source.plane()
          || creation.floor() != source.floor()
          || !creation.targets().contains(source.avatar())
          || !creation.signature().equals(entry.getValue())
          || !entry.getValue().equals(state.getExecuted().get(entry.getKey()))
          || !MissionStateRecords.subCompleted(player, entry.getKey())
          || player.getGameplay().getCancelledSubs().contains(entry.getKey())) return false;
    }
    return true;
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo c) {
    synchronized (player) {
      var source = source(player, c);
      return source != null
          && player.getGameplay().getMissionTrials().getRemovals() != null
          && matches(
              player,
              source,
              player.getGameplay().getMissionTrials().getRemovals().get(source.sub()))
          && player.getAvatarById(source.avatar()) == null
          && !player.getAvatars().getTempAvatars().containsKey(source.avatar());
    }
  }

  /** Script-only NPCs can be restored only from a matched completed execution receipt. */
  public static boolean spawns(Scene scene, GroupInfo group, NpcInfo npc) {
    if (scene == null || group == null || npc == null) return false;
    var player = scene.getPlayer();
    if (player == null) return false;
    synchronized (player) {
      if (scene.getFloorInfo() == null
          || scene.getResourceGroups().get(group.getId()) != group
          || group.getNPCList() == null
          || !group.getNPCList().contains(npc)) return false;
      var receipts = player.getGameplay().getMissionTrials().getRemovals();
      if (receipts == null || receipts.size() > 4096) return false;
      for (var entry : receipts.entrySet()) {
        var c = GameplayDefinitions.subs.get(entry.getKey());
        var source = source(player, scene, c);
        if (source != null
            && source.group() == group.getId()
            && source.npc() == npc.getID()
            && MissionStateRecords.subCompleted(player, source.sub())
            && matches(player, source, entry.getValue(), scene)
            && player.getAvatarById(source.avatar()) == null) return true;
      }
      return false;
    }
  }

  private MissionTrialRemovals() {}
}
