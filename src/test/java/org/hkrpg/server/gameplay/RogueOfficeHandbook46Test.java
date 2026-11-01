package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.SceneEntityLoader;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import com.google.protobuf.DynamicMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.*;

/** Original office prop and second-tutorial mission; mission history is an isolated fixture. */
class RogueOfficeHandbook46Test {
  final RogueFirstStoryEntry46Test fixture = new RogueFirstStoryEntry46Test();
  PropExcel previousProp;
  MissionInfo previousMain;
  GroupInfo group;
  final Map<Integer, GameplayDefinitions.RogueUnlock> previousUnlocks = new HashMap<>();
  final Map<Integer, GameplayDefinitions.RogueRule> previousRules = new HashMap<>();

  static class RecordingSession extends RogueFirstStoryEntry46Test.Session {
    final List<BasePacket> sent = new ArrayList<>();
    @Override public void send(BasePacket packet) { sent.add(packet); }
  }

  static class RewardActor extends RogueFirstStoryEntry46Test.Actor {
    int grants;
    final Map<Integer, Integer> received = new HashMap<>();
    final emu.lunarcore.game.inventory.Inventory testInventory =
        new emu.lunarcore.game.inventory.Inventory(this) {
          @Override public GrantResult grantItems(emu.lunarcore.game.inventory.ItemParamMap items, boolean hint) {
            grants++;
            for (var item : items) received.merge(item.getIntKey(), item.getIntValue(), Integer::sum);
            return new GrantResult(List.of(), true);
          }
        };
    @Override public emu.lunarcore.game.inventory.Inventory getInventory() { return testInventory; }
  }

  @BeforeEach void setup() throws Exception {
    fixture.prepare();
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-office-handbook-4.6.json")) {
      var records = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("records");
      var groupPath = "Config/LevelOutput/SharedRuntimeGroup/Groups_P80112_F80112001/LevelGroup_P80112_F80112001_G3.json";
      group = fixture.gson.fromJson(records.get(groupPath), GroupInfo.class);
      group.setId(3);
      fixture.actor.actual.getResourceGroups().put(3, group);
      var definition = fixture.gson.fromJson(
          records.get("Config/Level/Mission/4030002/MissionInfo_4030002.partial.json"), MissionInfo.class);
      for (var child : definition.getSubMissionList())
        if (child.getId() == 403000220 || child.getId() == 403000221 || child.getId() == 403000222)
          GameplayDefinitions.subs.put(child.getId(), child);
      previousMain = GameData.getMissionInfos().put(4030002, fixture.gson.fromJson(
          records.get("Config/Level/Mission/4030002/MissionInfo_4030002.json"), MissionInfo.class));
      for (var raw : records.getAsJsonArray("ExcelOutput/RogueUnlockConfig.json")) {
        var row = fixture.gson.fromJson(raw, GameplayDefinitions.RogueUnlock.class);
        var previous = GameplayDefinitions.rogueUnlocks.put(row.RogueUnlockID(), row);
        if (previous != null) previousUnlocks.put(row.RogueUnlockID(), previous);
      }
      for (var raw : records.getAsJsonArray("ExcelOutput/FinishWayRogue.json")) {
        var row = fixture.gson.fromJson(raw, GameplayDefinitions.RogueRule.class);
        var previous = GameplayDefinitions.rogueRules.put(row.ID(), row);
        if (previous != null) previousRules.put(row.ID(), previous);
      }
      previousProp = GameData.getPropExcelMap().get(1005);
      var row = records.getAsJsonArray("ExcelOutput/MazeProp.json").get(0).getAsJsonObject().deepCopy();
      if (row.get("PropName").isJsonObject()) row.add("PropName", row.getAsJsonObject("PropName").get("Hash"));
      var prop = fixture.gson.fromJson(row, PropExcel.class);
      prop.onLoad();
      GameData.getPropExcelMap().add(prop);
    }
    fixture.actor.getGameplay().getMainMissions().put(4030001, completed(4030001));
    fixture.actor.getGameplay().getMainMissions().put(4030002, new QuestState(4030002));
    fixture.actor.getGameplay().getSubMissions().put(403000220, completed(403000220));
    fixture.actor.getGameplay().getSubMissions().put(403000221, completed(403000221));
    fixture.actor.getGameplay().getSubMissions().put(403000222, new QuestState(403000222));
  }

  static QuestState completed(int id) {
    var q = new QuestState(id);
    assertTrue(q.advance(1, 1, 100));
    return q;
  }

  @AfterEach void cleanup() {
    GameData.getPropExcelMap().keySet().remove(1005);
    if (previousProp != null) GameData.getPropExcelMap().add(previousProp);
    if (previousMain == null) GameData.getMissionInfos().remove(4030002);
    else GameData.getMissionInfos().put(4030002, previousMain);
    for (int id : new int[] {11, 11007, 12001}) GameplayDefinitions.rogueUnlocks.remove(id);
    GameplayDefinitions.rogueUnlocks.putAll(previousUnlocks);
    for (int id : new int[] {10201, 13001, 13101}) GameplayDefinitions.rogueRules.remove(id);
    GameplayDefinitions.rogueRules.putAll(previousRules);
    fixture.restore();
  }

  EntityProp handbook() {
    return fixture.actor.actual.getEntitiesByGroup(EntityProp.class, 3).stream()
        .filter(p -> p.getInstId() == 300003).findFirst().orElseThrow();
  }

  @Test void returnedPlayerCanOpenTheHandbookWithoutAutomaticallyCompletingTheMission() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertEquals(PropState.Open, handbook().getState());
    assertEquals(QuestState.Phase.DOING, fixture.actor.getGameplay().getSubMissions().get(403000222).getPhase());
    assertEquals(QuestState.Phase.DOING, fixture.actor.getGameplay().getMainMissions().get(4030002).getPhase());
  }

  @Test void acceptingTheHandbookObjectiveRefreshesAnAlreadyLoadedDevice() {
    fixture.actor.getGameplay().getSubMissions().remove(403000222);
    fixture.actor.getGameplay().getSubMissions().put(403000221, new QuestState(403000221));
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertEquals(PropState.Closed, handbook().getState());
    fixture.actor.getGameplay().getSubMissions().put(403000221, completed(403000221));
    fixture.actor.getGameplay().getSubMissions().put(403000222, new QuestState(403000222));
    fixture.actor.getMissionManager().refresh();
    assertEquals(PropState.Open, handbook().getState());
    assertEquals(QuestState.Phase.DOING, fixture.actor.getGameplay().getSubMissions().get(403000222).getPhase());
  }

  private static Object field(DynamicMessage message, String name) {
    var descriptor = message.getDescriptorForType().findFieldByName(name);
    assertNotNull(descriptor, name);
    return message.getField(descriptor);
  }

  @Test void acceptingTheObjectiveNotifiesTheNativeClientOfTheCorrectProp() throws Exception {
    var session = new RecordingSession();
    session.actor = fixture.actor;
    fixture.actor.session = session;
    fixture.actor.getGameplay().getSubMissions().remove(403000222);
    fixture.actor.getGameplay().getSubMissions().put(403000221, new QuestState(403000221));
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertTrue(fixture.actor.actual.isLoaded());
    session.sent.clear();
    fixture.actor.getGameplay().getSubMissions().put(403000221, completed(403000221));
    fixture.actor.getGameplay().getSubMissions().put(403000222, new QuestState(403000222));
    fixture.actor.getMissionManager().refresh();
    var protocol = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    int matches = 0;
    for (var packet : session.sent) {
      if (packet.getCmdId() != CmdId.SceneGroupRefreshScNotify) continue;
      var wire = protocol.outbound(packet.getCmdId(), packet.getData().toByteArray());
      var message = protocol.clientSchema().parse("SceneGroupRefreshScNotify", wire.body());
      for (var rawGroup : (List<?>) field(message, "group_refresh_list")) {
        var changed = (DynamicMessage) rawGroup;
        if (!Integer.valueOf(3).equals(field(changed, "group_id"))) continue;
        for (var rawEntity : (List<?>) field(changed, "refresh_entity")) {
          var added = (DynamicMessage) field((DynamicMessage) rawEntity, "add_entity");
          if (!Integer.valueOf(300003).equals(field(added, "inst_id"))) continue;
          assertEquals(PropState.Open.getVal(), field((DynamicMessage) field(added, "prop"), "prop_state"));
          matches++;
        }
      }
    }
    assertEquals(1, matches);
  }

  @Test void handbookCallbackFinishesTheQuestOnlyOnceAndPreservesTheWorldGate() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertFalse(RogueUnlocks.open(fixture.actor, 12001));
    assertNotEquals(Retcode.SUCC, fixture.actor.getMissionManager().finishTalk(403000222, "wrong"));
    assertEquals(QuestState.Phase.DOING, fixture.actor.getMissionManager().sub(403000222).getPhase());
    assertEquals(Retcode.SUCC,
        fixture.actor.getMissionManager().finishTalk(403000222, "Mission_403000222"));
    assertEquals(QuestState.Phase.FINISHED, fixture.actor.getMissionManager().sub(403000222).getPhase());
    assertTrue(fixture.actor.getMissionManager().finished(4030002));
    long time = fixture.actor.getMissionManager().sub(403000222).getFinishTime();
    assertNotEquals(Retcode.SUCC,
        fixture.actor.getMissionManager().finishTalk(403000222, "Mission_403000222"));
    assertEquals(time, fixture.actor.getMissionManager().sub(403000222).getFinishTime());
    assertFalse(RogueUnlocks.open(fixture.actor, 12001), "Main story still gates the first world");
    fixture.actor.getGameplay().getMainMissions().put(1010403, completed(1010403));
    assertTrue(RogueUnlocks.open(fixture.actor, 12001));
  }

  @Test void handbookStateAndCompletionSurviveTwoH2Restarts(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    for (int cycle = 0; cycle < 3; cycle++) {
      var server = new de.bwaldvogel.mongo.MongoServer(
          new de.bwaldvogel.mongo.backend.h2.H2Backend(directory.resolve("player.mv").toString()));
      var address = server.bind();
      try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var codecs = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
            new emu.lunarcore.database.codecs.IntSetCodec(), new emu.lunarcore.database.codecs.IntListCodec(),
            new emu.lunarcore.database.codecs.Int2IntMapCodec());
        var options = dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false)
            .codecProvider(codecs).build();
        var store = dev.morphia.Morphia.createDatastore(client, "rogue_handbook", options);
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class, emu.lunarcore.util.Position.class);
        if (cycle == 0) {
          var document = new emu.lunarcore.game.player.Player();
          MissionTrialProvisioning46Test.set(document, "uid", 19998534);
          MissionTrialProvisioning46Test.set(document, "gameplay", fixture.actor.getGameplay());
          store.save(document);
        } else {
          var document = Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
          MissionTrialProvisioning46Test.set(fixture.actor, "gameplay", document.getGameplay());
          var source = group.getPropList().stream().filter(p -> p.getID() == 300003).findFirst().orElseThrow();
          var restored = new SceneEntityLoader().loadProp(fixture.actor.actual, group, source);
          assertNotNull(restored);
          assertEquals(PropState.Open, restored.getState());
          if (cycle == 1) {
            assertEquals(QuestState.Phase.DOING, fixture.actor.getMissionManager().sub(403000222).getPhase());
            assertEquals(Retcode.SUCC,
                fixture.actor.getMissionManager().finishTalk(403000222, "Mission_403000222"));
            store.save(document);
          } else {
            assertEquals(QuestState.Phase.FINISHED, fixture.actor.getMissionManager().sub(403000222).getPhase());
            assertTrue(fixture.actor.getMissionManager().finished(4030002));
          }
        }
      } finally { server.shutdownNow(); }
    }
  }

  @Test void originalMissionRewardIsGrantedOnceWhenTheHandbookIsCompleted() throws Exception {
    com.google.gson.JsonObject records;
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-office-handbook-4.6.json")) {
      records = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("records");
    }
    var oldMain = GameData.getMainMissionExcelMap().get(4030002);
    var oldReward = GameData.getRewardExcelMap().get(14030002);
    var oldItems = new HashMap<Integer, emu.lunarcore.data.excel.ItemExcel>();
    try {
      var mainRow = records.getAsJsonArray("ExcelOutput/MainMission.json").get(0).getAsJsonObject();
      var minimalMain = new com.google.gson.JsonObject();
      minimalMain.add("MainMissionID", mainRow.get("MainMissionID"));
      minimalMain.add("RewardID", mainRow.get("RewardID"));
      GameData.getMainMissionExcelMap().add(fixture.gson.fromJson(minimalMain, emu.lunarcore.data.excel.MainMissionExcel.class));
      var reward = fixture.gson.fromJson(records.getAsJsonArray("ExcelOutput/RewardData.json").get(0),
          emu.lunarcore.data.excel.RewardExcel.class);
      reward.onLoad();
      GameData.getRewardExcelMap().add(reward);
      for (var raw : records.getAsJsonArray("ExcelOutput/ItemConfig.json")) {
        var row = raw.getAsJsonObject();
        int id = row.get("ID").getAsInt();
        var old = GameData.getItemExcelMap().get(id);
        if (old != null) oldItems.put(id, old);
        var minimal = new com.google.gson.JsonObject();
        minimal.add("ID", row.get("ID"));
        minimal.add("ItemMainType", row.get("ItemMainType"));
        minimal.add("ItemSubType", row.get("ItemSubType"));
        GameData.getItemExcelMap().add(fixture.gson.fromJson(minimal, emu.lunarcore.data.excel.ItemExcel.class));
      }
      var actor = new RewardActor();
      MissionTrialProvisioning46Test.set(actor, "gameplay", fixture.actor.getGameplay());
      var plane = fixture.gson.fromJson("{\"PlaneID\":80112,\"PlaneType\":\"Town\"}",
          emu.lunarcore.data.excel.MazePlaneExcel.class);
      actor.actual = new emu.lunarcore.game.scene.Scene(actor, plane, 80112001);
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(403000222, "Mission_403000222"));
      assertEquals(Map.of(1, 50, 2, 15000, 22, 1000, 212, 3, 222, 3), actor.received);
      assertEquals(1, actor.grants);
      actor.getMissionManager().refresh();
      assertEquals(1, actor.grants);
      assertTrue(actor.getRewardLedger().granted("mission:4030002"));
    } finally {
      GameData.getMainMissionExcelMap().keySet().remove(4030002);
      if (oldMain != null) GameData.getMainMissionExcelMap().add(oldMain);
      GameData.getRewardExcelMap().keySet().remove(14030002);
      if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
      for (int id : new int[] {1, 2, 22, 212, 222}) GameData.getItemExcelMap().keySet().remove(id);
      oldItems.values().forEach(GameData.getItemExcelMap()::add);
    }
  }

  @Test void previouslyCompletedQuestKeepsTheOriginalHandbookUsable() {
    fixture.actor.getGameplay().getMainMissions().put(4030002, completed(4030002));
    fixture.actor.getGameplay().getSubMissions().put(403000222, completed(403000222));
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertEquals(PropState.Open, handbook().getState());
  }

  @Test void missingOrCancelledPrerequisitesKeepTheDeviceLocked() {
    fixture.actor.getGameplay().getSubMissions().remove(403000220);
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertEquals(PropState.Closed, handbook().getState());
    fixture.actor.getGameplay().getSubMissions().put(403000220, completed(403000220));
    fixture.actor.getGameplay().getCancelledSubs().add(403000222);
    fixture.actor.getMissionManager().refresh();
    assertEquals(PropState.Closed, handbook().getState());
  }
}
