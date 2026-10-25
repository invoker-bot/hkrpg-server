package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.trial.TrialAvatar;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Talk scripts lack role/binding parameters: the approved mapping remains explicit. */
class StationTrial46Test {
  @TempDir Path root;
  final MissionTrialProvisioning46Test f = new MissionTrialProvisioning46Test();
  static final int HIMEKO = 1011003;

  @BeforeEach void prepare() throws Exception {
    f.root = root;
    f.prepare();
    var records = f.fixture("station-trial-4.6.json").getAsJsonObject("records");
    for (int id : List.of(100030101, 100030100, 100040112, 100040125)) {
      var raw = records.getAsJsonObject("" + id);
      var sub = f.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      var source = records.get(raw.get("MissionJsonPath").getAsString());
      var task = f.gson.fromJson(source, MissionTaskInfo.class);
      task.retainSourceDefinition(source);
      sub.setTask(task);
      GameplayDefinitions.subs.put(id, sub);
    }
    for (var row : records.getAsJsonArray("SpecialAvatar"))
      GameData.getSpecialAvatarExcelMap().add(f.gson.fromJson(row, SpecialAvatarExcel.class));
    for (var row : records.getAsJsonArray("AvatarConfig"))
      GameData.getAvatarExcelMap().add(ResourceLoader.getGson().fromJson(row, AvatarExcel.class));
    for (var row : records.getAsJsonArray("ItemConfigEquipment"))
      GameData.getItemExcelMap().add(ResourceLoader.getGson().fromJson(row, ItemExcel.class));
    for (var row : records.getAsJsonArray("EquipmentConfig")) {
      var excel = f.gson.fromJson(row, EquipmentExcel.class);
      GameData.getEquipExcelMap().add(excel); excel.onLoad();
    }
    for (var row : records.getAsJsonArray("AvatarSkillTreeConfig")) {
      var excel = f.gson.fromJson(row, AvatarSkillTreeExcel.class);
      GameData.getAvatarSkillTreeExcelMap().add(excel); excel.onLoad();
    }
    f.player.getGameplay().getMainMissions().put(1000300, new QuestState(1000300));
    complete(100030101);
    f.player.getGameplay().getSubMissions().put(100030100, new QuestState(100030100));
    place(10001, 10001001);
    assertNotNull(f.player.getTrialManager().buildTrialAvatar(HIMEKO), "Real trial resources must load");
  }

  @AfterEach void restore() throws Exception { f.restore(); }

  void place(int plane, int floor) {
    MissionTrialProvisioning46Test.set(f.player, "planeId", plane);
    MissionTrialProvisioning46Test.set(f.player, "floorId", floor);
    var row = f.gson.fromJson("{\"PlaneID\":"+plane+",\"PlaneType\":\"Town\",\"WorldID\":100}", MazePlaneExcel.class);
    MissionTrialProvisioning46Test.set(f.player, "scene", new Scene(f.player, row, floor));
  }

  void complete(int id) {
    var state = new QuestState(id); state.advance(1, 1, 100);
    f.player.getGameplay().getSubMissions().put(id, state);
  }

  @Test void acceptedTalkAddsLevelEightTrialWithoutCompletingDialogueOrGrantingFormalAvatar() {
    f.player.getMissionManager().refresh();
    assertInstanceOf(TrialAvatar.class, f.player.getAvatarById(HIMEKO));
    assertEquals(8, f.player.getAvatarById(HIMEKO).getLevel());
    assertEquals(List.of(199986, HIMEKO), f.player.getCurrentLineup().getAvatars());
    assertEquals(List.of(199986), f.player.getLineupManager().getLineups()[0].getAvatars());
    assertFalse(f.player.getAvatars().getAvatars().containsKey(1003));
    assertEquals(QuestState.Phase.DOING, f.player.getMissionManager().sub(100030100).getPhase());
    String before = f.gson.toJson(f.player.getGameplay());
    f.player.getMissionManager().refresh();
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
  }

  @Test void trialSurvivesMainCompletionSupportZoneWakingAndTwoReconnectsThenLeavesOnce() {
    f.player.getMissionManager().refresh();
    assertNotNull(f.player.getAvatarById(HIMEKO));
    f.player.getAvatarById(HIMEKO).setCurrentHp(f.player.getCurrentLineup(), 4321);
    f.player.getAvatarById(HIMEKO).setCurrentSp(f.player.getCurrentLineup(), 2345);
    complete(100030100);
    f.player.getGameplay().getMainMissions().get(1000300).advance(1,1,100);
    f.player.getGameplay().getMainMissions().put(1000400,new QuestState(1000400));
    place(20003,20003001);
    assertFalse(f.player.getMissionTrialManager().reconcile(), "A task boundary must not retire Himeko");
    complete(100040112);
    place(10001,10001001);
    for (int i=0;i<2;i++) {
      f.player = f.reload(f.player.getGameplay());
      f.player.getMissionTrialManager().restoreOnLogin();
      assertInstanceOf(TrialAvatar.class,f.player.getAvatarById(HIMEKO));
      assertEquals(4321,f.player.getAvatarById(HIMEKO).getCurrentHp(f.player.getCurrentLineup()));
      assertEquals(2345,f.player.getAvatarById(HIMEKO).getCurrentSp(f.player.getCurrentLineup()));
    }
    f.player.getGameplay().getMainMissions().put(1000401,new QuestState(1000401));
    complete(100040125);
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertNull(f.player.getAvatarById(HIMEKO));
    f.player = f.reload(f.player.getGameplay());
    f.player.getMissionManager().refresh();
    assertNull(f.player.getAvatarById(HIMEKO));
    assertEquals(List.of(199986),f.player.getCurrentLineup().getAvatars());
  }

  @Test void failedCreationDoesNotConsumeTheOneTimeReceiptOrPublishTheActor() {
    f.player.failSave = true;
    assertThrows(IllegalStateException.class,()->f.player.getMissionManager().refresh());
    assertNull(f.player.getAvatarById(HIMEKO));
    assertFalse(f.player.getMissionTrialManager().inside());
    f.player.failSave = false;
    f.player.getMissionManager().refresh();
    assertNotNull(f.player.getAvatarById(HIMEKO));
  }

  @ParameterizedTest @ValueSource(strings={"unaccepted","predecessor","cancel","wrongFloor","changedScript","alreadyLeft"})
  void outOfOrderOrChangedSourcesCannotCreateTheTrial(String error) {
    switch(error) {
      case "unaccepted" -> f.player.getGameplay().getSubMissions().remove(100030100);
      case "predecessor" -> f.player.getGameplay().getSubMissions().put(100030101,new QuestState(100030101));
      case "cancel" -> f.player.getGameplay().getCancelledMissions().add(1000300);
      case "wrongFloor" -> place(20002,20002001);
      case "alreadyLeft" -> { f.player.getGameplay().getMainMissions().put(1000401,new QuestState(1000401)); complete(100040125); }
      case "changedScript" -> GameplayDefinitions.subs.get(100030100).getTask().retainSourceDefinition(new JsonObject());
    }
    // Direct initialization avoids refresh legitimately accepting an eligible absent objective.
    assertFalse(f.player.getMissionTrialManager().initializeStation());
    assertNull(f.player.getAvatarById(HIMEKO));
    assertEquals(List.of(199986),f.player.getCurrentLineup().getAvatars());
  }

  @Test void failedRetirementRetainsActorsAndAFullFormalPartyIsRestoredUnchanged() throws Exception {
    var formalTeam=f.player.getLineupManager().getLineups()[0];
    for(int id:List.of(199987,199988,199989)) {
      var excel=new AvatarExcel(); MissionTrialProvisioning46Test.setField(excel,AvatarExcel.class,"AvatarID",id);
      GameData.getAvatarExcelMap().add(excel);
      var actor=new emu.lunarcore.game.avatar.GameAvatar(excel);actor.setOwner(f.player);
      f.player.getAvatars().getAvatars().put(id,actor);formalTeam.getAvatars().add(id);
    }
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    assertEquals(List.of(199986,199987,199988,HIMEKO),f.player.getCurrentLineup().getAvatars());
    var actor=f.player.getAvatarById(HIMEKO);
    f.player.getGameplay().getMainMissions().put(1000401,new QuestState(1000401));
    f.player.getGameplay().getSubMissions().put(100040125,new QuestState(100040125));
    f.player.failSave=true;
    assertThrows(IllegalStateException.class,()->f.player.getMissionTrialManager().reconcile());
    assertSame(actor,f.player.getAvatarById(HIMEKO));
    assertTrue(f.player.getMissionTrialManager().inside());
    f.player.failSave=false;
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986,199987,199988,199989),f.player.getCurrentLineup().getAvatars());
    assertFalse(f.player.getMissionTrialManager().initializeStation());
  }

  @Test void frozenCompatibilityReceiptActorsAndVitalsSurviveRealMorphiaRoundTrips() throws Exception {
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    f.player.getAvatarById(HIMEKO).setCurrentHp(f.player.getCurrentLineup(),5432);
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort())) {
      var store=dev.morphia.Morphia.createDatastore(client,"station-trial");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class,QuestState.class,emu.lunarcore.util.Position.class);
      var document=new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document,"uid",199985);
      MissionTrialProvisioning46Test.set(document,"gameplay",f.player.getGameplay()); store.save(document);
      for(int i=0;i<2;i++) {
        var loaded=Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        f.player=f.reload(loaded.getGameplay()); f.player.getMissionTrialManager().restoreOnLogin();
        assertEquals(5432,f.player.getAvatarById(HIMEKO).getCurrentHp(f.player.getCurrentLineup()));
        assertEquals(List.of(199986,HIMEKO),f.player.getCurrentLineup().getAvatars());
        assertFalse(f.player.getMissionTrialManager().initializeStation()); store.save(loaded);
      }
    } finally { server.shutdownNow(); }
  }
}
