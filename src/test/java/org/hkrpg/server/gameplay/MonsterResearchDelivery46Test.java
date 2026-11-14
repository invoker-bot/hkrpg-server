package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual mission completion must not bypass the original research material costs. */
class MonsterResearchDelivery46Test {
  static final Gson GSON = ResourceLoader.getGson();
  static final int[] MAINS = {8000170, 8000171, 8000172, 8000173, 8000174, 8000175, 8000177};
  @TempDir Path root;
  String oldRoot;
  JsonObject records;
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final List<ItemExcel> oldItems = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  Actor player;

  static class Actor extends Player {
    final Inventory stock = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap items, boolean hint) {
        grants++;
        var reward = new TreeMap<Integer, Integer>();
        for (var i : items.int2IntEntrySet()) reward.put(i.getIntKey(), i.getIntValue());
        deliveries.add(reward);
        return new GrantResult(failGrant ? List.of() : items.toItemList(), !failGrant);
      }
    };
    int grants, saves, itemSaves, failSaveAt, failItemSaveAt;
    boolean failGrant;
    int plane = 10001, floor = 10001001;
    final List<Map<Integer, Integer>> deliveries = new ArrayList<>();
    @Override public int getUid() { return 194811; }
    @Override public int getPlaneId() { return plane; }
    @Override public int getFloorId() { return floor; }
    @Override public Inventory getInventory() { return stock; }
    @Override public void save() {
      if (saves + 1 == failSaveAt) throw new IllegalStateException("isolated save failure");
      saves++;
    }
    @Override public void sendPacket(BasePacket packet) {}
  }

  static class Material extends GameItem {
    final Actor owner;
    Material(Actor owner, int id, int count) { super(GameData.getItemExcelMap().get(id), count); this.owner = owner; }
    @Override public void save() {
      if (++owner.itemSaves == owner.failItemSaveAt) throw new IllegalStateException("isolated item write failure");
    }
  }

  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  JsonElement source(String name) { return records.getAsJsonObject(name).get("source"); }

  @BeforeEach void prepare() throws Exception {
    oldRoot = LunarCore.getConfig().getResourceDir();
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true); field.set(LunarCore.getConfig(), root.toString());
    try (var in = getClass().getResourceAsStream("/fixtures/material-submit-activity-4.6.json")) {
      assertNotNull(in); byte[] bytes = in.readAllBytes();
      assertEquals("455304ad8bd49e783b91337bd1824d1b6bf843a368f97e1fa40a40577f290d64", sha(bytes));
      var fixture = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      for (var entry : fixture.getAsJsonObject("files").entrySet()) {
        var row = entry.getValue().getAsJsonObject();
        try (var data = new InflaterInputStream(new ByteArrayInputStream(
            Base64.getDecoder().decode(row.get("rawBase64").getAsString())))) {
          bytes = data.readAllBytes(); assertEquals(row.get("sha256").getAsString(), sha(bytes));
          var path = root.resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.write(path, bytes);
        }
      }
    }
    try (var in = getClass().getResourceAsStream("/fixtures/monster-research-missions-4.6.json")) {
      assertNotNull(in); byte[] bytes = in.readAllBytes();
      assertEquals("eadb24458f765fd7a38788336ac6ae0f04270b9a3da6772a697204b34654b125", sha(bytes));
      var fixture = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
      records = fixture.getAsJsonObject("records");
    }
    oldItems.addAll(GameData.getItemExcelMap().values()); GameData.getItemExcelMap().values().clear();
    oldRewards.addAll(GameData.getRewardExcelMap().values()); GameData.getRewardExcelMap().values().clear();
    for (var row : JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/ItemConfig.json"))).getAsJsonArray())
      GameData.getItemExcelMap().add(GSON.fromJson(row, ItemExcel.class));
    for (var row : JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/RewardData.json"))).getAsJsonArray()) {
      var reward = GSON.fromJson(row, RewardExcel.class); reward.onLoad(); GameData.getRewardExcelMap().add(reward);
    }
    oldSubs.putAll(GameplayDefinitions.subs); GameplayDefinitions.subs.clear();
    oldMissions.putAll(GameData.getMissionInfos()); GameData.getMissionInfos().clear();
    for (int main : MAINS) {
      String prefix = "Config/Level/Mission/" + main + "/";
      GameData.getMissionInfos().put(main, GSON.fromJson(source(prefix + "MissionInfo_" + main + ".json"), MissionInfo.class));
      for (var row : source(prefix + "MissionInfo_" + main + ".partial.json").getAsJsonObject().getAsJsonArray("SubMissionList")) {
        var sub = GSON.fromJson(row, MissionInfo.SubMissionInfo.class); GameplayDefinitions.subs.put(sub.getId(), sub);
      }
      int id = main * 100 + (main == 8000177 ? 5 : 4);
      var raw = source(prefix + "Mission_" + id + ".json");
      var task = GSON.fromJson(raw, MissionTaskInfo.class); task.retainSourceDefinition(raw);
      GameplayDefinitions.subs.get(id).setTask(task);
    }
    player = new Actor();
  }

  @AfterEach void restore() throws Exception {
    if (oldRoot == null) return;
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true); field.set(LunarCore.getConfig(), oldRoot);
    GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMissionInfos().clear(); GameData.getMissionInfos().putAll(oldMissions);
    GameData.getItemExcelMap().values().clear(); oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getRewardExcelMap().values().clear(); oldRewards.forEach(GameData.getRewardExcelMap()::add);
  }

  int accepted(int main) {
    int id = main * 100 + (main == 8000177 ? 5 : 4);
    player.getGameplay().getMainMissions().put(main, new QuestState(main));
    player.getGameplay().getSubMissions().put(id, new QuestState(id));
    return id;
  }

  String confirmation(int main, int sub) {
    return main == 8000177 ? "ResearchA_800017705" : "Mission_" + sub;
  }

  MaterialSubmitDefinitions.Submitter stock(int main) {
    var rows = Objects.requireNonNull(MaterialSubmitDefinitions.load(root, 30005)).rows();
    var row = rows.values().stream().filter(item -> item.mission() == main).findFirst().orElseThrow();
    for (var cost : row.costs().entrySet()) player.stock.addItem(new Material(player, cost.getKey(), cost.getValue() + 2), false);
    player.itemSaves = 0;
    return row;
  }

  Map<Integer, Integer> stocks() {
    var result = new TreeMap<Integer, Integer>();
    player.stock.getItems().values().forEach(item -> result.put(item.getItemId(), item.getCount()));
    return result;
  }

  @ParameterizedTest
  @ValueSource(ints = {8000170, 8000171, 8000172, 8000173, 8000174, 8000175, 8000177})
  void missingMaterialsCannotFinishAnAcceptedResearchMission(int main) {
    int id = accepted(main);
    assertEquals(Retcode.ITEM_COST_NOT_ENOUGH, player.getMissionManager().finishTalk(id, confirmation(main, id)));
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(id).getPhase());
    assertEquals(0, player.grants);
  }

  @ParameterizedTest
  @ValueSource(ints = {8000170, 8000171, 8000172, 8000173, 8000174, 8000175, 8000177})
  void originalCostsAndRewardAreAppliedOnceAfterNormalConfirmation(int main) {
    int id = accepted(main); var row = stock(main); var expected = stocks();
    row.costs().forEach((item, count) -> expected.put(item, expected.get(item) - count));
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, confirmation(main, id)));
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(id).getPhase());
    assertEquals(expected, stocks());
    assertEquals(List.of(row.rewards()), player.deliveries);
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, confirmation(main, id)));
    assertEquals(expected, stocks()); assertEquals(1, player.grants);
  }

  @Test void wrongSceneCannotSpendOrComplete() {
    int id = accepted(8000170); stock(8000170); var before = stocks(); player.floor = 10001002;
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH, player.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(before, stocks()); assertEquals(0, player.grants);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(id).getPhase());
  }

  @Test void anotherStoryActionCannotBypassTheDelivery() {
    int id = accepted(8000170); stock(8000170); var before = stocks();
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, player.getMissionManager().finishFromAction(id));
    assertEquals(before, stocks()); assertEquals(0, player.grants);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(id).getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"changed-path", "changed-finish-event", "missing-script"})
  void changedOriginalMissionScriptCannotSpendOrComplete(String change) {
    int id = accepted(8000170); stock(8000170); var before = stocks();
    var sub = GameplayDefinitions.subs.get(id);
    switch (change) {
      case "changed-path" -> sub.setMissionJsonPath("Config/Level/Mission/foreign.json");
      case "changed-finish-event" -> {
        var raw = source("Config/Level/Mission/8000170/Mission_800017004.json").deepCopy().getAsJsonObject();
        raw.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList")
            .get(0).getAsJsonObject().getAsJsonObject("CustomString").addProperty("Value", "OtherFinish");
        sub.getTask().retainSourceDefinition(raw);
      }
      case "missing-script" -> sub.setTask(null);
    }
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(before, stocks()); assertEquals(0, player.grants);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(id).getPhase());
  }

  @Test void failedReservationDoesNotSpendAndCanBeRetried() {
    int id = accepted(8000170); var row = stock(8000170); var before = stocks(); player.failSaveAt = 1;
    assertEquals(Retcode.PLAYER_DATA_ERROR, player.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(before, stocks()); assertEquals(0, player.grants);
    player.failSaveAt = 0;
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
    row.costs().forEach((item, count) -> assertEquals(before.get(item) - count, stocks().get(item)));
    assertEquals(1, player.grants);
  }

  @Test void uncertainRewardDeliveryCannotSpendOrGrantTwice() {
    int id = accepted(8000170); stock(8000170); player.failGrant = true;
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
    var after = stocks(); assertEquals(1, player.grants);
    player.failGrant = false;
    assertEquals(Retcode.SYSTEM_BUSY, player.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(after, stocks()); assertEquals(1, player.grants);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(id).getPhase());
  }

  @Test void missionSaveFailureRetriesWithoutSpendingOrGrantingAgain() {
    int id = accepted(8000170); stock(8000170); player.failSaveAt = 5;
    assertThrows(IllegalStateException.class, () -> player.getMissionManager().finishTalk(id, "Mission_" + id));
    var after = stocks(); assertEquals(1, player.grants);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(id).getPhase());
    player.failSaveAt = 0;
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(after, stocks()); assertEquals(1, player.grants);
  }

  @Test void interruptedMaterialDebitCannotBeReplayedOrRewarded() {
    int id = accepted(8000170); stock(8000170); player.failItemSaveAt = 2;
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
    var after = stocks(); assertEquals(0, player.grants);
    player.failItemSaveAt = 0;
    assertNotEquals(Retcode.SUCC, player.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(after, stocks()); assertEquals(0, player.grants);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(id).getPhase());
  }
}
