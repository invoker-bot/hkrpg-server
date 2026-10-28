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

  static final class WritableTeam extends emu.lunarcore.game.player.lineup.PlayerLineup {
    int saves;
    boolean fail, failPlayerAfterWrite;
    WritableTeam(emu.lunarcore.game.player.Player player) { super(player, 0); }
    @Override public void save() {
      if (fail) throw new IllegalStateException("Injected lineup save failure");
      saves++;
      if (failPlayerAfterWrite) ((MissionTrialProvisioning46Test.Actor) getOwner()).failSave = true;
    }
  }

  WritableTeam writableTeam() {
    var old = f.player.getLineupManager().getLineups()[0];
    var replacement = new WritableTeam(f.player);
    replacement.getAvatars().addAll(old.getAvatars());
    replacement.setLeader(old.getLeader());
    f.player.getLineupManager().getLineups()[0] = replacement;
    return replacement;
  }

  void ownHimeko() {
    var actor = new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(1003));
    actor.setOwner(f.player);
    f.player.getAvatars().getAvatars().put(1003, actor);
  }

  void endStationTrial() {
    f.player.getGameplay().getMainMissions().put(1000401, new QuestState(1000401));
    complete(100040125);
  }

  void reachPostBattleUnbind(boolean finished) {
    complete(100030100);
    f.player.getGameplay().getMainMissions().get(1000300).advance(1, 1, 100);
    f.player.getGameplay().getMainMissions().put(1000400, new QuestState(1000400));
    complete(100040111);
    if (finished) complete(100040112);
    else f.player.getGameplay().getSubMissions().put(100040112, new QuestState(100040112));
  }

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

  @Test void capturedPresetSaveAcceptsFixedPathAndSparseTrialSlots() throws Exception {
    var paths = GameData.getMultiplePathAvatarExcelMap();
    var old1 = paths.get(8001);
    var old2 = paths.get(8002);
    try {
      for (int id : List.of(8001, 8002)) {
        var excel = f.gson.fromJson("{\"AvatarID\":" + id + "}", AvatarExcel.class);
        GameData.getAvatarExcelMap().add(excel);
        paths.add(f.gson.fromJson("{\"AvatarID\":" + id
            + ",\"BaseAvatarID\":8001,\"Gender\":\""
            + (id == 8001 ? "GENDER_MAN" : "GENDER_WOMAN") + "\"}", MultiplePathAvatarExcel.class));
        f.player.getAvatars().getMultiPaths().put(id,
            new emu.lunarcore.game.avatar.AvatarMultiPath(f.player, excel));
      }
      MissionTrialProvisioning46Test.set(f.player, "gender", emu.lunarcore.game.player.PlayerGender.GENDER_WOMAN);
      var hero = new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(8001));
      hero.setOwner(f.player);
      hero.setMultiPath(f.player.getAvatars().getMultiPathById(8002));
      f.player.getAvatars().getAvatars().put(8001, hero);
      f.player.getCurAvatarPaths().put(8001, 8002);
      var normal = f.player.getLineupManager().getLineups()[0];
      normal.getAvatars().clear();
      normal.getAvatars().add(8001);
      assertTrue(f.player.getMissionTrialManager().initializeStation());
      assertEquals(List.of(8001, HIMEKO), f.player.getCurrentLineup().getAvatars());

      // Exact native request captured from UID 10001, including slots 0 and 3 and leader 3.
      var session = new StoryLineWireTest.NativeSession(f.player);
      byte[] request = java.util.HexFormat.of().parseHex("3a0528c23e50083a0828bbda3d480350014803");
      assertTrue(org.hkrpg.server.protocol.VersionedPacketHandler.handle(session, 704, request));
      assertEquals(719, session.command);
      var response = com.google.protobuf.UnknownFieldSet.parseFrom(session.payload);
      assertFalse(response.hasField(9), "Saving the current station party must succeed");
      assertEquals(List.of(8001, HIMEKO), f.player.getCurrentLineup().getAvatars());
      assertEquals(1, f.player.getCurrentLineup().getLeader());
      assertEquals(List.of(8001), normal.getAvatars(), "The trial must not become a permanent avatar");
    } finally {
      paths.keySet().remove(8001);
      paths.keySet().remove(8002);
      if (old1 != null) paths.add(old1);
      if (old2 != null) paths.add(old2);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unowned", "duplicate-role", "unknown-trial", "wrong-slot", "wrong-leader", "wrong-index", "assist"})
  void invalidStationEditsCannotChangeTheTrialOrFormalTeam(String variant) throws Exception {
    ownHimeko();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    var before = f.gson.toJson(f.player.getGameplay());
    var session = new StoryLineWireTest.NativeSession(f.player);
    var req = com.google.protobuf.DynamicMessage.newBuilder(session.getProtocol().clientSchema().message("ReplaceLineupCsReq"));
    var rows = req.getDescriptorForType().findFieldByName("lineup_slot_list");
    var formal = com.google.protobuf.DynamicMessage.newBuilder(rows.getMessageType());
    var trial = com.google.protobuf.DynamicMessage.newBuilder(rows.getMessageType());
    var id = formal.getDescriptorForType().findFieldByName("id");
    var slot = formal.getDescriptorForType().findFieldByName("slot");
    var type = formal.getDescriptorForType().findFieldByName("avatar_type");
    formal.setField(id, variant.equals("unowned") ? 999999 : variant.equals("duplicate-role") ? 1003 : 199986);
    formal.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
    if (variant.equals("assist")) formal.setField(formal.getDescriptorForType().findFieldByName("assist_uid"), 12345);
    trial.setField(id, variant.equals("unknown-trial") ? 1011004 : HIMEKO);
    trial.setField(slot, variant.equals("wrong-slot") ? 4 : 3);
    trial.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
    req.addRepeatedField(rows, formal.build()).addRepeatedField(rows, trial.build());
    req.setField(req.getDescriptorForType().findFieldByName("leader_slot"), variant.equals("wrong-leader") ? 2 : 3);
    if (variant.equals("wrong-index")) req.setField(req.getDescriptorForType().findFieldByName("index"), 1);
    assertTrue(org.hkrpg.server.protocol.VersionedPacketHandler.handle(session, 704, req.build().toByteArray()));
    var result = com.google.protobuf.UnknownFieldSet.parseFrom(session.payload);
    assertTrue(result.hasField(9), variant);
    assertEquals(before, f.gson.toJson(f.player.getGameplay()), variant);
    assertEquals(List.of(199986), f.player.getLineupManager().getLineups()[0].getAvatars());
  }

  @Test void editedStationPartyAndOwnedPresetSurviveRelogAndTrialRetirement() throws Exception {
    ownHimeko();
    var team = writableTeam();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    var originalTrial = f.player.getAvatarById(HIMEKO);
    var code = f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, code);
    assertSame(originalTrial, f.player.getAvatarById(HIMEKO), "Editing must not recreate the active trial actor");
    assertEquals(List.of(199986), team.getAvatars(), "No formal document changes during the plot trial");
    assertEquals(0, team.saves);
    assertEquals(List.of(199986, 1003), f.player.getGameplay().getMissionTrials().getParty().getStationPreset());
    int saves = f.player.saves;
    assertEquals(code, f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    assertEquals(saves, f.player.saves, "Retry must not save or grant twice");

    f.player = f.reload(f.player.getGameplay());
    ownHimeko();
    team = writableTeam();
    f.player.getMissionTrialManager().restoreOnLogin();
    assertEquals(List.of(199986, HIMEKO), f.player.getCurrentLineup().getAvatars());
    assertEquals(1, f.player.getCurrentLineup().getLeader());
    endStationTrial();
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986, 1003), team.getAvatars());
    assertEquals(1, team.getLeader());
    assertEquals(1, team.saves);
    assertNull(f.player.getAvatarById(HIMEKO));
    assertSame(f.player.getAvatars().getAvatars().get(1003), f.player.getAvatarById(1003));
  }

  @Test void unownedTrialIsNeverConvertedIntoAnOwnedPreset() {
    var team = writableTeam();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    assertNull(f.player.getGameplay().getMissionTrials().getParty().getStationPreset());
    endStationTrial();
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986), team.getAvatars());
    assertEquals(0, team.saves);
    assertFalse(f.player.getAvatars().getAvatars().containsKey(1003));
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void postBattleUnbindRestoresOwnedPresetBeforeFindingHimeko(boolean finished) {
    ownHimeko();
    var owned = f.player.getAvatars().getAvatars().get(1003);
    owned.setLevel(17);
    owned.setRank(1);
    var team = writableTeam();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    reachPostBattleUnbind(finished);
    assertDoesNotThrow(() -> f.player.getCurrentLineup(),
        "The trial roster remains readable until its retirement is durably reconciled");
    assertTrue(f.player.getMissionTrialManager().reconcile(),
        "The original unbind script must stop publishing the story trial before finding Himeko");
    assertFalse(f.player.getMissionTrialManager().inside());
    assertNull(f.player.getAvatarById(HIMEKO));
    assertSame(owned, f.player.getAvatarById(1003));
    assertEquals(17, owned.getLevel());
    assertEquals(1, owned.getRank());
    assertEquals(List.of(199986, 1003), team.getAvatars());
    assertEquals(1, team.getLeader());
    assertEquals(1, team.saves);
    assertFalse(f.player.getMissionTrialManager().reconcile());
    assertFalse(f.player.getMissionTrialManager().initializeStation());
    assertEquals(finished ? QuestState.Phase.FINISHED : QuestState.Phase.DOING,
        f.player.getMissionManager().sub(100040112).getPhase());
  }

  @Test void reconnectRetiresAnAlreadyUnboundLegacyTrialAndPersistsTheOwnedPreset() {
    ownHimeko();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    reachPostBattleUnbind(true);
    place(10001, 10001001);
    f.player = f.reload(f.player.getGameplay());
    ownHimeko();
    var restored = writableTeam();
    f.player.getMissionTrialManager().restoreOnLogin();
    assertFalse(f.player.getMissionTrialManager().inside());
    assertNull(f.player.getAvatarById(HIMEKO));
    assertEquals(List.of(199986, 1003), restored.getAvatars());
    assertEquals(1, restored.getLeader());
    assertEquals(1, restored.saves);
    f.player.getMissionTrialManager().restoreOnLogin();
    assertEquals(1, restored.saves);
  }

  @Test void unboundUnownedTrialLeavesWithoutGrantingAFormalHimeko() {
    var team = writableTeam();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    reachPostBattleUnbind(false);
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986), team.getAvatars());
    assertNull(f.player.getAvatarById(HIMEKO));
    assertFalse(f.player.getAvatars().getAvatars().containsKey(1003));
    assertEquals(0, team.saves);
  }

  @Test void failedPostBattleUnbindKeepsTheTrialUntilTheOwnedPresetCanBeSaved() {
    ownHimeko();
    var team = writableTeam();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    reachPostBattleUnbind(false);
    team.fail = true;
    assertThrows(IllegalStateException.class, () -> f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986), team.getAvatars());
    assertTrue(f.player.getGameplay().getMissionTrials().getParty().isStationFinalizing());
    assertNotNull(f.player.getAvatarById(HIMEKO));
    team.fail = false;
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986, 1003), team.getAvatars());
    assertNull(f.player.getAvatarById(HIMEKO));
    assertEquals(1, team.saves);
    assertFalse(f.player.getMissionTrialManager().reconcile());
    assertEquals(1, team.saves);
  }

  @Test void failedPresetWritesKeepARecoverableJournalAndDoNotDuplicateTheUpdate() {
    ownHimeko();
    var team = writableTeam();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    var before = f.gson.toJson(f.player.getGameplay());
    f.player.failSave = true;
    assertEquals(emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY,
        f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    assertEquals(before, f.gson.toJson(f.player.getGameplay()));
    f.player.failSave = false;
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    endStationTrial();
    team.fail = true;
    assertThrows(IllegalStateException.class, () -> f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986), team.getAvatars());
    assertTrue(f.player.getGameplay().getMissionTrials().getParty().isStationFinalizing());
    team.fail = false;
    team.failPlayerAfterWrite = true;
    assertThrows(IllegalStateException.class, () -> f.player.getMissionTrialManager().reconcile());
    assertEquals(List.of(199986, 1003), team.getAvatars());
    assertEquals(1, team.saves);
    assertTrue(f.player.getGameplay().getMissionTrials().getParty().isStationFinalizing());
    f.player.failSave = false;
    team.failPlayerAfterWrite = false;
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertEquals(1, team.saves, "Retry of a completed lineup write must be idempotent");
    assertEquals(List.of(199986, 1003), f.player.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void restartFinishesTheJournalBeforeOrAfterTheSeparateLineupWrite(boolean lineupAlreadyWritten)
      throws Exception {
    ownHimeko();
    var team = writableTeam();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        f.player.getMissionTrialManager().replaceStation(List.of(199986, HIMEKO), 1));
    endStationTrial();
    team.fail = true;
    assertThrows(IllegalStateException.class, () -> f.player.getMissionTrialManager().reconcile());
    var saved = f.gson.fromJson(f.gson.toJson(f.player.getGameplay()), GameplayState.class);

    f.player = f.reload(saved);
    ownHimeko();
    var restored = writableTeam();
    if (lineupAlreadyWritten) {
      restored.getAvatars().add(1003);
      restored.setLeader(1);
    }
    f.player.getMissionTrialManager().restoreOnLogin();
    assertFalse(f.player.getMissionTrialManager().inside());
    assertEquals(List.of(199986, 1003), restored.getAvatars());
    assertEquals(1, restored.getLeader());
    assertEquals(lineupAlreadyWritten ? 0 : 1, restored.saves);
  }

  @Test void trialSurvivesMainCompletionAndSupportZoneReconnectsUntilPostBattleUnbind() {
    f.player.getMissionManager().refresh();
    assertNotNull(f.player.getAvatarById(HIMEKO));
    f.player.getAvatarById(HIMEKO).setCurrentHp(f.player.getCurrentLineup(), 4321);
    f.player.getAvatarById(HIMEKO).setCurrentSp(f.player.getCurrentLineup(), 2345);
    complete(100030100);
    f.player.getGameplay().getMainMissions().get(1000300).advance(1,1,100);
    f.player.getGameplay().getMainMissions().put(1000400,new QuestState(1000400));
    place(20003,20003001);
    assertFalse(f.player.getMissionTrialManager().reconcile(), "A task boundary must not retire Himeko");
    for (int i=0;i<2;i++) {
      f.player = f.reload(f.player.getGameplay());
      f.player.getMissionTrialManager().restoreOnLogin();
      assertInstanceOf(TrialAvatar.class,f.player.getAvatarById(HIMEKO));
      assertEquals(4321,f.player.getAvatarById(HIMEKO).getCurrentHp(f.player.getCurrentLineup()));
      assertEquals(2345,f.player.getAvatarById(HIMEKO).getCurrentSp(f.player.getCurrentLineup()));
    }
    complete(100040111);
    f.player.getGameplay().getSubMissions().put(100040112,new QuestState(100040112));
    assertTrue(f.player.getMissionTrialManager().reconcile());
    assertNull(f.player.getAvatarById(HIMEKO));
    place(10001,10001001);
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
    ownHimeko();
    assertTrue(f.player.getMissionTrialManager().initializeStation());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        f.player.getMissionTrialManager().replaceStation(List.of(199986,HIMEKO),1));
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
        f.player=f.reload(loaded.getGameplay()); ownHimeko(); f.player.getMissionTrialManager().restoreOnLogin();
        assertEquals(5432,f.player.getAvatarById(HIMEKO).getCurrentHp(f.player.getCurrentLineup()));
        assertEquals(List.of(199986,HIMEKO),f.player.getCurrentLineup().getAvatars());
        assertEquals(1,f.player.getCurrentLineup().getLeader());
        assertEquals(List.of(199986,1003),f.player.getGameplay().getMissionTrials().getParty().getStationPreset());
        assertFalse(f.player.getMissionTrialManager().initializeStation()); store.save(loaded);
      }
    } finally { server.shutdownNow(); }
  }
}
