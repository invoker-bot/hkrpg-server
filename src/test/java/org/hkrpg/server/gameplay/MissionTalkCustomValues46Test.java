package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.data.excel.RewardExcel;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissionTalkCustomValues46Test {
  private static final int MAIN = 8027111, BEFORE = 802711101, TALK = 802711102,
      NEXT = 802711103, OTHER_START = 802711117;
  private final Gson gson = ResourceLoader.getGson();
  private JsonObject original;
  private JsonObject rawCrossMissionSources;
  private final Map<Integer, emu.lunarcore.data.config.MissionInfo> oldGraphs = new HashMap<>();
  private final Map<Integer, emu.lunarcore.data.config.MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  private final List<MainMissionExcel> oldMains = new ArrayList<>();
  private final List<ItemExcel> oldItems = new ArrayList<>();
  private final List<RewardExcel> oldRewards = new ArrayList<>();
  @TempDir Path root;

  static class Actor extends GameplayProgressTest.ScenePlayer {
    final Map<Integer, Integer> delivered = new HashMap<>();
    boolean failCompletion;
    final Inventory inventory = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap items, boolean hint) {
        for (var item : items) delivered.merge(item.getIntKey(), item.getIntValue(), Integer::sum);
        return new GrantResult(List.of(), true);
      }
    };
    @Override public Inventory getInventory() { return inventory; }
    @Override public void save() {
      var state = getGameplay().getSubMissions().get(TALK);
      if (failCompletion && state != null && state.getPhase() != QuestState.Phase.DOING
          && getGameplay().getStoryValues().containsKey("mission:8027111:OldValue_0"))
        throw new IllegalStateException("fixture completion save failure");
    }
  }

  @BeforeEach void setup() throws Exception {
    oldGraphs.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldItems.addAll(GameData.getItemExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getItemExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    try (var in = getClass().getResourceAsStream("/fixtures/talk-custom-values-4.6.json")) {
      var fixture = JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
      original = fixture.getAsJsonObject("originals");
      rawCrossMissionSources = fixture.getAsJsonObject("rawCrossMissionSources");
    }
    for (var entry : original.entrySet()) {
      String name = entry.getKey();
      if (!name.startsWith("Config/Level/")) continue;
      Path path = root.resolve(name);
      Files.createDirectories(path.getParent());
      if (rawCrossMissionSources.has(name))
        Files.write(path, Base64.getDecoder().decode(rawCrossMissionSources.get(name).getAsString()));
      else Files.writeString(path, gson.toJson(entry.getValue()), StandardCharsets.UTF_8);
    }
    for (var row : original.getAsJsonArray("ExcelOutput/ItemConfig.json"))
      GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
    for (var row : original.getAsJsonArray("ExcelOutput/RewardData.json")) {
      var reward = gson.fromJson(row, RewardExcel.class);
      reward.onLoad();
      GameData.getRewardExcelMap().add(reward);
    }
    load();
  }

  private void load() {
    GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission"));
  }

  @AfterEach void cleanup() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldGraphs);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
  }

  private Actor active(int main, int sub, int floor) {
    var excel = gson.fromJson("{\"MainMissionID\":" + main
        + ",\"TakeParam\":[{\"Type\":\"Manual\"}],\"BeginParam\":[{\"Type\":\"Auto\"}]}", MainMissionExcel.class);
    GameData.getMainMissionExcelMap().add(excel);
    var actor = new Actor();
    actor.scenePlane = floor / 1000;
    actor.sceneFloor = floor;
    actor.getGameplay().getMainMissions().put(main, new QuestState(main));
    actor.getGameplay().getSubMissions().put(sub, new QuestState(sub));
    return actor;
  }

  private Actor atShutup() {
    var actor = active(MAIN, TALK, 10000003);
    var previous = new QuestState(BEFORE);
    previous.advance(1, 1, 1);
    actor.getGameplay().getSubMissions().put(BEFORE, previous);
    var other = new QuestState(OTHER_START);
    other.advance(1, 1, 1);
    actor.getGameplay().getSubMissions().put(OTHER_START, other);
    return actor;
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2})
  void originalShutupChoiceCompletesAndUnlocksTheNextTalk(int choice) {
    var actor = atShutup();
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of(0, choice)));
    assertTrue(MissionStateRecords.subCompleted(actor, TALK));
    assertNotNull(actor.getMissionManager().sub(NEXT));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(NEXT).getPhase());
    assertEquals(choice, actor.getGameplay().getStoryValues().get("mission:8027111:OldValue_0"));
    assertEquals(0, actor.getGameplay().getStoryValues().get("floor:10000003:Connection_MuteStairs"));
    assertEquals(choice == 1 ? Map.of(408636, 1) : choice == 2 ? Map.of(408637, 1) : Map.of(), actor.delivered);
    assertEquals(Retcode.MISSION_NOT_DOING, actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of(0, choice)));
    assertEquals(choice == 1 ? Map.of(408636, 1) : choice == 2 ? Map.of(408637, 1) : Map.of(), actor.delivered);
  }

  @ParameterizedTest @ValueSource(ints = {1, 2, 3})
  void originalGroupScriptDeclaresValuesEvenWithoutRewardOrCustomList(int choice) {
    var actor = active(8027203, 802720301, 20332001);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(802720301, "Mission_802720301", Map.of(0, choice)));
    assertEquals(choice, actor.getGameplay().getStoryValues().get("mission:8027203:OldValue_0"));
  }

  @Test void originalMissionScriptDeclaresANonzeroIndex() {
    var actor = active(2030201, 203020103, 10301001);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(203020103, "Mission_203020103", Map.of(3, 1)));
    assertEquals(1, actor.getGameplay().getStoryValues().get("mission:2030201:OldValue_3"));
  }

  @Test void parentRangeAloneCannotAuthorizeAValueMissingFromTheObjectiveScript() {
    var actor = active(2030201, 203020103, 10301001);
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, actor.getMissionManager().finishTalk(
        203020103, "Mission_203020103", Map.of(3, 0)));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(203020103).getPhase());
  }

  @ParameterizedTest @ValueSource(ints = {1, 2})
  void dialogueAndGroupCanEachSupplyAnOriginalChoice(int choice) {
    var actor = active(8027201, 802720103, 10301001);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(
        802720103, "Mission_802720103", Map.of(0, choice, 1, 3 - choice)));
    assertEquals(choice, actor.getGameplay().getStoryValues().get("mission:8027201:OldValue_0"));
    assertEquals(3 - choice, actor.getGameplay().getStoryValues().get("mission:8027201:OldValue_1"));
  }

  @Test void dialogueChoiceNotActuallyAssignedInThePerformanceIsRejectedAtomically() {
    var actor = active(8027201, 802720103, 10301001);
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, actor.getMissionManager().finishTalk(
        802720103, "Mission_802720103", Map.of(0, 3, 1, 1)));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(802720103).getPhase());
    assertTrue(actor.getGameplay().getStoryValues().isEmpty());
  }

  @ParameterizedTest @ValueSource(ints = {1, 2})
  void originalEarlierMissionPerformanceCanAssignTheFollowingMissionChoice(int choice) {
    var actor = active(2001201, 200120105, 10101003);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(
        200120105, "Mission_200120105", Map.of(0, choice)));
    assertEquals(choice, actor.getGameplay().getStoryValues().get("mission:2001201:OldValue_0"));
  }

  @Test void secondOriginalCrossMissionPerformanceIsAlsoRecognized() {
    var actor = active(4340147, 434014702, 20412001);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(
        434014702, "Mission_434014702", Map.of(0, 1)));
    assertEquals(1, actor.getGameplay().getStoryValues().get("mission:4340147:OldValue_0"));
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 4})
  void originalRangeAssignmentSupportsOnlyLiteralPasswordValues(int choice) {
    var actor = active(1054521, 105452142, 40552001);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(
        105452142, "Mission_105452142", Map.of(2, choice)));
    assertEquals(choice, actor.getGameplay().getStoryValues().get("mission:1054521:PasswordID"));
  }

  @ParameterizedTest @ValueSource(ints = {0, 3, 6})
  void originalRangeAssignmentSupportsOnlyLiteralWaypointValues(int choice) {
    var actor = active(2054002, 205400206, 40504002);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(
        205400206, "Mission_205400206", Map.of(4, choice)));
    assertEquals(choice, actor.getGameplay().getStoryValues().get("mission:2054002:finishwaypoint"));
  }

  @Test void wrongValueIndexTalkOrFloorCannotCompleteOrProduceRewards() {
    var actor = atShutup();
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of(0, 3)));
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of(1, 1)));
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, actor.getMissionManager().finishTalk(TALK, "wrong", Map.of(0, 1)));
    actor.sceneFloor = 10000000;
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH, actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of(0, 1)));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(TALK).getPhase());
    assertFalse(actor.getGameplay().getStoryValues().containsKey("mission:8027111:OldValue_0"));
    assertTrue(actor.delivered.isEmpty());
  }

  @Test void shutupTalkCannotCompleteWhenTheAlwaysInitializedChoiceIsMissing() {
    var actor = atShutup();
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID,
        actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of()));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(TALK).getPhase());
    assertFalse(actor.getGameplay().getMissionRewardPlans().containsKey("submission:" + TALK));
    assertTrue(actor.delivered.isEmpty());
  }

  @Test void missingGroupScriptDoesNotAuthorizeAnUnrelatedValue() throws Exception {
    Files.delete(root.resolve("Config/Level/GroupGraph/F20332001/Group_F20332001_G500.json"));
    load();
    var actor = active(8027203, 802720301, 20332001);
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, actor.getMissionManager().finishTalk(802720301, "Mission_802720301", Map.of(0, 1)));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(802720301).getPhase());
  }

  @Test void failedChoiceSaveRetainsTheObjectiveAndRetryDoesNotDuplicateRewards() {
    var actor = atShutup();
    var accepted = actor.getMissionManager().sub(TALK);
    actor.failCompletion = true;
    assertThrows(IllegalStateException.class,
        () -> actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of(0, 1)));
    assertSame(accepted, actor.getMissionManager().sub(TALK));
    assertEquals(QuestState.Phase.DOING, accepted.getPhase());
    assertFalse(actor.getGameplay().getStoryValues().containsKey("mission:8027111:OldValue_0"));
    assertTrue(actor.delivered.isEmpty());
    actor.failCompletion = false;
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(TALK, "Mission_802711102", Map.of(0, 1)));
    assertEquals(Map.of(408636, 1), actor.delivered);
    actor.getMissionManager().refresh();
    assertEquals(Map.of(408636, 1), actor.delivered);
  }

  @Test void alteredCrossMissionScriptCannotAuthorizeItsOldChoice() throws Exception {
    Path source = root.resolve("Config/Level/Mission/2001101/Act/Act200110112.json");
    Files.writeString(source, Files.readString(source) + " ", StandardCharsets.UTF_8);
    load();
    var actor = active(2001201, 200120105, 10101003);
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, actor.getMissionManager().finishTalk(
        200120105, "Mission_200120105", Map.of(0, 1)));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(200120105).getPhase());
  }

  @Test void cachedGroupEvidenceIsDiscardedIfItsOwnershipChanges() {
    var actor = active(8027203, 802720301, 20332001);
    var sub = GameplayDefinitions.subs.get(802720301);
    var main = GameData.getMissionInfos().get(8027203);
    var definition = main.getMissionCustomValueList()[0];
    assertTrue(MissionTalkCustomSources.allows(actor, sub, main, definition, 1));
    sub.getGroupIDList().clear();
    assertFalse(MissionTalkCustomSources.allows(actor, sub, main, definition, 1));
  }

  @Test void nativeTalkReplyCarriesTheRealChoiceAndRejectsDuplicateIndexes() throws Exception {
    var actor = atShutup();
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var replies = new ArrayList<com.google.protobuf.DynamicMessage>();
    var session = new GameSession(null) {
      @Override public emu.lunarcore.game.player.Player getPlayer() { return actor; }
      @Override public org.hkrpg.server.protocol.ProtocolBridge getProtocol() { return bridge; }
      @Override public void sendClientPacket(String name, byte[] bytes) {
        if (!name.equals("FinishTalkMissionScRsp")) return;
        try { replies.add(bridge.clientSchema().parse(name, bytes)); }
        catch (Exception e) { throw new AssertionError(e); }
      }
    };
    session.setState(SessionState.ACTIVE);
    var type = bridge.clientSchema().message("FinishTalkMissionCsReq");
    var choiceType = bridge.clientSchema().message("MissionCustomValue");
    var choice = com.google.protobuf.DynamicMessage.newBuilder(choiceType)
        .setField(choiceType.findFieldByName("custom_value"), 2).build();
    var request = com.google.protobuf.DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("sub_mission_id"), TALK)
        .setField(type.findFieldByName("talk_str"), "Mission_802711102")
        .addRepeatedField(type.findFieldByName("custom_value_list"), choice);
    int command = bridge.clientCommand("FinishTalkMissionCsReq");
    var duplicate = request.clone().addRepeatedField(type.findFieldByName("custom_value_list"), choice).build();
    assertTrue(org.hkrpg.server.protocol.VersionedPacketHandler.handle(session, command, duplicate.toByteArray()));
    var responseType = replies.getLast().getDescriptorForType();
    assertEquals(Retcode.MISSION_CUSTOM_VALUE_NOT_VALID.getVal(),
        replies.getLast().getField(responseType.findFieldByName("retcode")));
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(TALK).getPhase());
    assertTrue(org.hkrpg.server.protocol.VersionedPacketHandler.handle(session, command, request.build().toByteArray()));
    var response = replies.getLast();
    assertEquals(0, response.getField(responseType.findFieldByName("retcode")));
    assertEquals(List.of(choice), response.getField(responseType.findFieldByName("custom_value_list")));
    assertTrue(MissionStateRecords.subCompleted(actor, TALK));
    assertEquals(Map.of(408637, 1), actor.delivered);
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(NEXT).getPhase());
  }
}
