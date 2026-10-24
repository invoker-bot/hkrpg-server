package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Authored station controls: source bindings, mission gates, atomic receipts and reconstruction. */
class StoryMechanisms46Test {
  final StoryDoors46Test f = new StoryDoors46Test();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  JsonObject records;
  int planeId = 20002;
  FloorInfo floor;
  Actor player;

  static class Actor extends StoryDoors46Test.Actor {
    int planeId = 20002;
    final GameItem key = ResourceLoader.getGson().fromJson("{\"itemId\":140037,\"count\":1}", GameItem.class);
    boolean hasKey;
    final Inventory inventory = new Inventory(this) {
      public GameItem getMaterialByItemId(int id) { return hasKey && id == 140037 ? key : null; }
    };
    public Inventory getInventory() { return inventory; }
    public int getPlaneId() { return planeId; }
    public int getFloorId() { return planeId * 1000 + 1; }
    public int getEntryId() { return planeId * 100 + 1; }
  }

  @BeforeEach void prepare() throws Exception {
    f.prepare();
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/story-mechanisms-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (int id : new int[] {20001, 20002, 20003}) {
      String key = "P" + id + "_F" + (id * 1000 + 1);
      oldFloors.put(key, GameData.getFloorInfos().get(key));
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
      if (row.has("PropName") && row.get("PropName").isJsonObject())
        row.add("PropName", row.getAsJsonObject("PropName").get("Hash"));
      var prop = f.gson.fromJson(row, PropExcel.class);
      prop.onLoad(); GameData.getPropExcelMap().add(prop);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var row = f.gson.fromJson(raw, InteractExcel.class);
      oldInteractions.put(row.getId(), GameData.getInteractExcelMap().get(row.getId()));
      GameData.getInteractExcelMap().add(row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MainMission.json"))
      GameData.getMainMissionExcelMap().add(f.gson.fromJson(raw, MainMissionExcel.class));
    for (var e : records.entrySet()) if (e.getKey().endsWith(".partial.json"))
      for (var raw : e.getValue().getAsJsonObject().getAsJsonArray("SubMissionList")) {
        var sub = f.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
        var path = raw.getAsJsonObject().get("MissionJsonPath");
        if (path != null && records.has(path.getAsString())) {
          var script = records.get(path.getAsString());
          var task = f.gson.fromJson(script, MissionTaskInfo.class);
          task.retainSourceDefinition(script); sub.setTask(task);
        }
        GameplayDefinitions.subs.put(sub.getId(), sub);
      }
    player = new Actor(); f.player = player;
  }

  @AfterEach void cleanup() {
    oldFloors.forEach((key, old) -> {
      if (old == null) GameData.getFloorInfos().remove(key); else GameData.getFloorInfos().put(key, old);
    });
    oldInteractions.forEach((id, old) -> {
      GameData.getInteractExcelMap().keySet().remove(id);
      if (old != null) GameData.getInteractExcelMap().add(old);
    });
    f.cleanup();
  }

  void definitions() {
    player.planeId = planeId;
    String key = "P" + planeId + "_F" + player.getFloorId();
    floor = f.gson.fromJson(records.get("Config/LevelOutput/RuntimeFloor/" + key + ".json"), FloorInfo.class);
    for (var entry : records.entrySet()) if (entry.getKey().contains("/Groups_" + key + "/")) {
      int id = Integer.parseInt(entry.getKey().replaceAll(".*_G([0-9]+)\\.json", "$1"));
      var group = f.gson.fromJson(entry.getValue(), GroupInfo.class);
      group.setId(id); group.getNPCList().clear(); group.getMonsterList().clear();
      floor.getGroups().put(id, group);
    }
    floor.onLoad(); GameData.getFloorInfos().put(key, floor);
  }

  void scene() {
    definitions(); construct();
  }
  void construct() {
    var plane = records.getAsJsonArray("ExcelOutput/MazePlane.json").asList().stream()
        .map(x -> f.gson.fromJson(x, MazePlaneExcel.class)).filter(x -> x.getId() == planeId).findFirst().orElseThrow();
    player.scene = new Scene(player, plane, player.getFloorId());
    player.scene.setEntryId(player.getEntryId());
  }
  EntityProp prop(int group, int instance) {
    return player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .filter(p -> p.getInstId() == instance).findFirst().orElseThrow();
  }
  PropInfo info(int group, int instance) {
    return floor.getGroups().get(group).getPropList().stream().filter(p -> p.getID() == instance).findFirst().orElseThrow();
  }
  Retcode use(int group, int instance, int config) {
    return ScenePropTransitions.interact(player, config, prop(group, instance).getEntityId()).retcode();
  }
  void ready(int objective) {
    var goal = GameplayDefinitions.subs.get(objective);
    planeId = goal.getLevelPlaneID();
    player.getGameplay().getMainMissions().put(goal.getMainMissionID(), new QuestState(goal.getMainMissionID()));
    for (int id : goal.getTakeParamIntList()) f.completed(id);
    f.accepted(objective); scene();
  }
  void reload() throws Exception {
    var durable = f.gson.fromJson(f.gson.toJson(player.getGameplay()), GameplayState.class);
    boolean key = player.hasKey;
    player = new Actor(); player.hasKey = key; f.player = player;
    var field = Player.class.getDeclaredField("gameplay"); field.setAccessible(true); field.set(player, durable);
    scene();
  }

  @ParameterizedTest @CsvSource({"100020114,3,300010,300012", "100020115,6,300002,300001"})
  void bridgeObjectiveEnablesOnlyItsSourcePairAndSurvivesReload(int objective, int group, int control, int bridge) throws Exception {
    ready(objective);
    assertEquals(PropState.Closed, prop(group, control).getState());
    assertEquals(PropState.Closed, prop(group, bridge).getState());
    assertEquals(Retcode.SUCC, use(group, control, 1010));
    assertEquals(PropState.Open, prop(group, bridge).getState());
    assertEquals(PropState.Closed, prop(6, 300006).getState(), "Unrelated bridge must not be opened");
    assertTrue(MissionStateRecords.subCompleted(player, objective));
    var saved = f.gson.toJson(player.getGameplay().getScenePropStates());
    reload();
    assertEquals(PropState.Open, prop(group, control).getState());
    assertEquals(PropState.Open, prop(group, bridge).getState());
    assertNotEquals(Retcode.SUCC, use(group, control, 1010));
    assertEquals(saved, f.gson.toJson(player.getGameplay().getScenePropStates()));
  }

  @Test void bridgeUsesExactBindingEvenWhenConsoleAlsoHasTheOldCommonFlag() {
    ready(100020115); info(6, 300002).setCommonConsole(true);
    assertEquals(Retcode.SUCC, use(6, 300002, 1010));
    assertEquals(PropState.Open, prop(6, 300001).getState());
    assertEquals(PropState.Closed, prop(6, 300006).getState(), "Second platform in the same group stays closed");
  }

  @Test void originallyAvailableBridgeHasTheChap00LinkAndDoesNotNeedAMission() throws Exception {
    scene(); assertEquals(Retcode.SUCC, use(6, 300009, 1010));
    assertEquals(PropState.Open, prop(6, 300006).getState());
    assertEquals(PropState.Closed, prop(6, 300001).getState());
    reload(); assertEquals(PropState.Open, prop(6, 300006).getState());
  }

  @Test void supportingZoneConsoleRecognizesTransientBarrierCleanupWithoutSkippingTheDoor() throws Exception {
    ready(100040103);
    assertEquals(PropState.Closed, prop(1, 300005).getState());
    assertEquals(Retcode.SUCC, use(1, 300005, 1010));
    assertTrue(MissionStateRecords.subCompleted(player, 100040103));
    reload(); assertEquals(PropState.Open, prop(1, 300005).getState());
  }

  @Test void passiveDoorObjectiveObservesRealPairReceiptButDoesNotEnableTheDoor() throws Exception {
    ready(100041001);
    assertEquals(PropState.Locked, prop(1, 300005).getState());
    assertFalse(StoryDoors.satisfied(player, GameplayDefinitions.subs.get(100041001)));
    ready(100040103); assertEquals(Retcode.SUCC, use(1, 300005, 1010));
    reload();
    assertTrue(MissionStateRecords.subCompleted(player, 100041001)
        || StoryDoors.satisfied(player, GameplayDefinitions.subs.get(100041001)));
  }

  @ParameterizedTest @ValueSource(strings={"unaccepted","cancelled","wrong-main","extra-effect",
      "different-barrier","persistent-barrier","ordered-hint","duplicate-enable","wrong-binding","missing-target"})
  void bridgeCannotBypassUnknownEffectsOrMissingPrerequisites(String variant) {
    ready(100020114);
    var goal = GameplayDefinitions.subs.get(100020114);
    var script = JsonParser.parseString(goal.getTask().getSourceDefinition()).getAsJsonObject();
    var sequences = script.getAsJsonArray("OnStartSequece");
    switch (variant) {
      case "unaccepted" -> player.getGameplay().getSubMissions().remove(100020114);
      case "cancelled" -> player.getGameplay().getCancelledSubs().add(100020114);
      case "wrong-main" -> player.getGameplay().getMainMissions().remove(1000202);
      case "extra-effect" -> sequences.get(0).getAsJsonObject().getAsJsonArray("TaskList")
          .add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.GiveItem\"}"));
      case "different-barrier" -> sequences.get(2).getAsJsonObject().getAsJsonArray("TaskList").get(1)
          .getAsJsonObject().getAsJsonObject("ID").getAsJsonObject("FixedValue").addProperty("Value",300023);
      case "persistent-barrier" -> sequences.get(2).getAsJsonObject().getAsJsonArray("TaskList")
          .remove(1);
      case "ordered-hint" -> sequences.get(1).getAsJsonObject().addProperty("Order",1);
      case "duplicate-enable" -> sequences.add(sequences.get(0).deepCopy());
      case "wrong-binding" -> info(3,300010).setValueSource(null);
      case "missing-target" -> player.scene.removeEntity(prop(3,300012));
    }
    var task=f.gson.fromJson(script,MissionTaskInfo.class); task.retainSourceDefinition(script); goal.setTask(task);
    StoryDoors.reconcile(player.scene);
    assertNotEquals(Retcode.SUCC,use(3,300010,1010));
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void failedBridgeSaveRollsBackBothStatesAndTheMission() {
    ready(100020114); var before=f.gson.toJson(player.getGameplay());
    player.failSave=true; player.packets.clear();
    assertEquals(Retcode.SYSTEM_BUSY,use(3,300010,1010));
    assertEquals(before,f.gson.toJson(player.getGameplay()));
    assertEquals(PropState.Closed,prop(3,300010).getState());
    assertEquals(PropState.Closed,prop(3,300012).getState());
    assertTrue(player.packets.isEmpty());
  }

  @Test void interruptedBridgeMissionCallbackRecoversFromBothSavedStates() throws Exception {
    ready(100020114); assertEquals(Retcode.SUCC,use(3,300010,1010));
    f.accepted(100020114); reload(); player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player,100020114));
  }

  @ParameterizedTest @CsvSource({"20002,3,300005","20002,3,300008","20002,3,300018","20002,3,300038",
      "20003,1,300010","20003,1,300002","20003,1,300023"})
  void everyKeyDoorChecksOwnershipOpensItsExactDoorAndPreservesTheKey(int plane,int group,int control) throws Exception {
    planeId=plane;scene();
    assertEquals(Retcode.ITEM_NOT_ENOUGH,use(group,control,3016));
    assertNotEquals(Retcode.SUCC,use(group,control,1010),"A free interaction cannot bypass the key");
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
    player.hasKey=true;
    var binding=info(group,control).getSharedValueByKey("Maze_Prop_Chap01_Door").split(",");
    int targetGroup=Integer.parseInt(binding[0]),target=Integer.parseInt(binding[1]);
    assertEquals(Retcode.SUCC,use(group,control,3016));
    assertEquals(1,player.key.getCount(),"CheckItem is ownership, not consumption");
    assertEquals(PropState.Open,prop(targetGroup,target).getState());
    reload();assertEquals(PropState.Open,prop(targetGroup,target).getState());
    assertNotEquals(Retcode.SUCC,use(group,control,3016));
  }

  @Test void unrelatedControllerCannotUseTheKeyCostInteraction() {
    ready(100020114); player.hasKey=true;
    assertNotEquals(Retcode.SUCC,use(3,300010,3016));
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void keyDoorSaveFailureKeepsDoorClosedAndKeyOwned() {
    scene();player.hasKey=true;player.failSave=true;
    assertEquals(Retcode.SYSTEM_BUSY,use(3,300005,3016));
    assertEquals(PropState.Closed,prop(3,300003).getState());
    assertEquals(PropState.Closed,prop(3,300005).getState());
    assertEquals(1,player.key.getCount());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void elevatorUnlockTravelAndRepeatableConsoleStateSurviveReload() throws Exception {
    scene();
    assertEquals(Retcode.ITEM_NOT_ENOUGH,use(8,300001,3200));
    assertNotEquals(Retcode.SUCC,use(8,300003,114150),"Cannot move a locked source-bound lift");
    player.hasKey=true;assertEquals(Retcode.SUCC,use(8,300001,3200));
    assertEquals(PropState.Closed,prop(8,300001).getState());
    assertEquals(PropState.Elevator1,prop(8,300003).getState(),"Unlock must not move the elevator");
    assertEquals(Retcode.SUCC,use(8,300001,1010));
    assertEquals(Retcode.SUCC,use(8,300003,114150));
    assertEquals(Retcode.SUCC,use(8,300001,1100));
    reload();assertEquals(PropState.Elevator2,prop(8,300003).getState());
    assertEquals(PropState.Closed,prop(8,300001).getState());
    assertEquals(Retcode.SUCC,use(8,300002,3200));
    assertEquals(Retcode.SUCC,use(8,300002,1010));
    assertEquals(Retcode.SUCC,use(8,300003,115140));
    assertEquals(Retcode.SUCC,use(8,300002,1100));
    assertEquals(1,player.key.getCount());
    reload();assertEquals(PropState.Elevator1,prop(8,300003).getState());
  }

  @Test void anAuthoredElevatorOnTheSecondFloorIsNotForcedToTheFirstFloor() {
    definitions();info(8,300003).setState(PropState.Elevator2);construct();
    assertEquals(PropState.Elevator2,prop(8,300003).getState());
  }

  @Test void aLegacyLiftWithoutAValidInitialFloorStillUsesItsSupportedFirstFloor() {
    definitions();info(8,300003).setState(PropState.Closed);construct();
    assertEquals(PropState.Elevator1,prop(8,300003).getState());
  }

  @Test void emptyParallelSequencesDoNotBlockThePrivateLiftAndOpenDoesNotLatchForever() throws Exception {
    ready(200010402);
    assertEquals(PropState.Closed,prop(24,300001).getState());
    assertNotNull(info(24,300001).getSharedValueByKey("Anchor"));
    assertEquals(Retcode.SUCC,use(24,300001,1010));
    assertTrue(MissionStateRecords.subCompleted(player,200010402));
    assertEquals(Retcode.SUCC,use(24,300001,1100));
    reload();assertEquals(PropState.Closed,prop(24,300001).getState());
    assertEquals(Retcode.SUCC,use(24,300001,1010));
    reload();assertEquals(PropState.Open,prop(24,300001).getState());
    assertEquals(Retcode.SUCC,use(24,300001,1100),"Authored OnStart resets an interrupted use");
    assertEquals(PropState.Closed,prop(24,300002).getState());
    assertEquals(Retcode.SUCC,use(24,300002,1010));
    assertEquals(Retcode.SUCC,use(24,300002,1100));
  }

  @Test void directTargetRequestsCannotOpenOrRecloseAKeyDoor() {
    scene(); assertNotEquals(Retcode.SUCC,use(3,300003,1010));
    player.hasKey=true; assertEquals(Retcode.SUCC,use(3,300005,3016));
    assertNotEquals(Retcode.SUCC,use(3,300003,1100));
    assertEquals(PropState.Open,prop(3,300003).getState());
  }

  @ParameterizedTest @ValueSource(strings={"consume","wrong-item","wrong-count","empty","wrong-state"})
  void alteredKeyCostsAreNotSilentlyWaived(String variant) {
    scene(); player.hasKey=true;
    var raw=records.getAsJsonArray("ExcelOutput/InteractConfig.json").asList().stream()
        .filter(x->x.getAsJsonObject().get("InteractID").getAsInt()==3016).findFirst().orElseThrow().getAsJsonObject().deepCopy();
    var item=raw.getAsJsonArray("ItemCostList").get(0).getAsJsonObject();
    switch(variant) {
      case "consume" -> raw.addProperty("InteractCostType","CostItem");
      case "wrong-item" -> item.addProperty("ItemID",140038);
      case "wrong-count" -> item.addProperty("ItemNum",2);
      case "empty" -> raw.getAsJsonArray("ItemCostList").remove(0);
      case "wrong-state" -> raw.addProperty("TargetState","Locked");
    }
    GameData.getInteractExcelMap().add(f.gson.fromJson(raw,InteractExcel.class));
    assertNotEquals(Retcode.SUCC,use(3,300005,3016));
    assertEquals(1,player.key.getCount());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test void aSingleOldConsoleReceiptCannotOpenAnUnrelatedBridgeOnReload() throws Exception {
    scene(); assertEquals(Retcode.SUCC,use(6,300009,1010));
    var receipt=player.getGameplay().getScenePropStates().get(PropTimelines.key(player.scene,6,300009));
    player.getGameplay().scenePropStates(ScenePropState.empty().with(List.of(receipt)));
    reload(); assertEquals(PropState.Closed,prop(6,300006).getState());
    assertEquals(PropState.Closed,prop(6,300009).getState());
    assertEquals(Retcode.SUCC,use(6,300009,1010));
  }

  @Test void aMissingMissionScriptIsNotMistakenForAPassiveObserver() {
    ready(100040103); assertEquals(Retcode.SUCC,use(1,300005,1010));
    f.accepted(100040103); GameplayDefinitions.subs.get(100040103).setTask(null);
    assertFalse(StoryDoors.satisfied(player,GameplayDefinitions.subs.get(100040103)));
  }

  @Test void bridgeAndLiftReceiptsSurviveTwoActualPlayerDatabaseRoundTrips() throws Exception {
    ready(100020114); assertEquals(Retcode.SUCC,use(3,300010,1010));
    player.hasKey=true; assertEquals(Retcode.SUCC,use(8,300001,3200));
    assertEquals(Retcode.SUCC,use(8,300001,1010));
    assertEquals(Retcode.SUCC,use(8,300003,114150));
    assertEquals(Retcode.SUCC,use(8,300001,1100));
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort())) {
      var store=dev.morphia.Morphia.createDatastore(client,"station-controls");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay"); store.getMapper().map(Player.class,QuestState.class);
      var saved=new Player(); var uid=Player.class.getDeclaredField("uid");uid.setAccessible(true);uid.setInt(saved,123456786);
      var gameplay=Player.class.getDeclaredField("gameplay");gameplay.setAccessible(true);gameplay.set(saved,player.getGameplay());
      store.save(saved);
      for(int i=0;i<2;i++) {
        var loaded=Objects.requireNonNull(store.find(Player.class).first());
        player=new Actor(); f.player=player; gameplay.set(player,loaded.getGameplay()); scene();
        assertEquals(PropState.Open,prop(3,300012).getState());
        assertEquals(PropState.Closed,prop(6,300001).getState());
        assertEquals(PropState.Closed,prop(8,300001).getState());
        assertEquals(PropState.Elevator2,prop(8,300003).getState());
        assertTrue(MissionStateRecords.subCompleted(player,100020114));
        store.save(loaded);
      }
    } finally { server.shutdownNow(); }
  }
}
