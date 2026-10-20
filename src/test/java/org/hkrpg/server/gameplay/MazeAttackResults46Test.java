package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.battle.skills.*;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.*;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Original instant-kill branch and stages; scene, actor placement and reports are fixtures. */
class MazeAttackResults46Test {
  @TempDir Path root;
  final MazeInstantKillBranchTest branch = new MazeInstantKillBranchTest();
  final SceneEventBattle46Test source = new SceneEventBattle46Test();
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final BattleService service = new BattleService(null) {
    @Override protected void grantDrops(Battle battle) {}
  };
  GameServer shell;
  AvatarExcel oldAvatar;
  GameAvatar actor;

  @BeforeEach
  void setup() throws Exception {
    branch.root = root;
    branch.prepare();
    source.temp = root;
    source.load();
    shell = source.shell;
    var serviceField = GameServer.class.getDeclaredField("battleService");
    serviceField.setAccessible(true);
    serviceField.set(shell, service);
    oldAvatar = GameData.getAvatarExcelMap().get(1001);
    JsonObject avatarRow;
    try (var input = getClass().getResourceAsStream("/fixtures/daily-weakness-entry-4.6.json")) {
      assertNotNull(input);
      var proof = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
          .getAsJsonObject();
      assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b",
          proof.get("sourceCommit").getAsString());
      avatarRow = proof.getAsJsonArray("records").asList().stream()
          .map(JsonElement::getAsJsonObject)
          .filter(row -> row.get("kind").getAsString().equals("avatar"))
          .map(row -> row.getAsJsonObject("row"))
          .filter(row -> row.get("AvatarID").getAsInt() == 1001).findFirst().orElseThrow();
    }
    var excel = branch.base.base.fixture.gson.fromJson(avatarRow, AvatarExcel.class);
    GameData.getAvatarExcelMap().add(excel);
    var player = branch.base.base.player;
    actor = new GameAvatar(excel) {
      @Override public void save() {}
    };
    actor.setOwner(player);
    actor.setEntityId(player.getCurrentLeaderAvatar().getEntityId());
    player.getAvatars().getAvatars().put(1001, actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(1001);
    player.getScene().getAvatars().clear();
    player.getScene().getAvatars().put(1001, actor);
    player.getScene().getAvatarEntityIds().clear();
    player.getScene().getAvatarEntityIds().add(actor.getEntityId());
    player.sent.clear();
  }

  @AfterEach
  void cleanup() throws Exception {
    source.restore();
    branch.restore();
    GameData.getAvatarExcelMap().keySet().remove(1001);
    if (oldAvatar != null) GameData.getAvatarExcelMap().add(oldAvatar);
  }

  EntityMonster monster(String tags) {
    var target = branch.base.assembled("Minion", tags);
    target.setCustomStage(SceneEventBattle46Test.STAGE);
    return target;
  }

  MazeSkill skill(boolean instantKill, boolean battle) {
    var result = instantKill ? branch.parse(branch.originalBranch()) : new MazeSkill(actor.getExcel(), 1);
    result.setTriggerBattle(battle);
    actor.getExcel().setMazeAttack(result);
    return result;
  }

  final class Session extends GameSession {
    final List<DynamicMessage> responses = new ArrayList<>();
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public emu.lunarcore.game.player.Player getPlayer() { return branch.base.base.player; }
    @Override public GameServer getServer() { return shell; }
    @Override public ProtocolBridge getProtocol() { return protocol; }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try {
        if (name.equals("SceneCastSkillScRsp")) responses.add(protocol.clientSchema().parse(name, bytes));
      } catch (Exception error) { throw new AssertionError(error); }
    }
    @Override public void send(BasePacket packet) {}
  }

  DynamicMessage dispatch(int[] hits, int[] assists) throws Exception {
    return dispatch(actor.getEntityId(), actor.getEntityId(), hits, assists);
  }

  DynamicMessage dispatch(int cast, int attacked, int[] hits, int[] assists) throws Exception {
    var player = branch.base.base.player;
    int before = player.sent.size();
    var session = new Session();
    var request = DynamicMessage.newBuilder(protocol.clientSchema().message("SceneCastSkillCsReq"));
    request.setField(request.getDescriptorForType().findFieldByName("cast_entity_id"), cast);
    request.setField(request.getDescriptorForType().findFieldByName("attacked_by_entity_id"), attacked);
    for (int id : hits) request.addRepeatedField(request.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), id);
    for (int id : assists) request.addRepeatedField(request.getDescriptorForType().findFieldByName("assist_monster_entity_id_list"), id);
    assertTrue(VersionedPacketHandler.handle(session, protocol.clientCommand("SceneCastSkillCsReq"), request.build().toByteArray()));
    // Capture the original inherited reply as well as the dedicated native reply after the fix.
    for (var packet : player.sent.subList(before, player.sent.size())) {
      if (packet.getCmdId() == CmdId.SceneCastSkillScRsp) {
        var converted = protocol.outbound(packet.getCmdId(), packet.getData().toByteArray());
        session.sendClientPacket("SceneCastSkillScRsp", converted.body());
      }
    }
    assertEquals(1, session.responses.size(), "A cast must have one final response");
    return session.responses.getFirst();
  }

  static int number(DynamicMessage message, String name) {
    return (int) message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  static List<DynamicMessage> rows(DynamicMessage response) {
    return ((List<?>) response.getField(response.getDescriptorForType().findFieldByName("monster_battle_info")))
        .stream().map(DynamicMessage.class::cast).toList();
  }

  static void outcome(DynamicMessage row, int id, int kind) {
    assertEquals(id, number(row, "target_monster_entity_id"));
    assertEquals(kind, ((EnumValueDescriptor) row.getField(row.getDescriptorForType()
        .findFieldByName("monster_battle_type"))).getNumber());
  }

  @Test
  void frozenNativeFieldsAndOutcomeNamesMatchTheSourceProtocol() {
    var reply = protocol.clientSchema().message("SceneCastSkillScRsp");
    var list = reply.findFieldByName("monster_battle_info");
    assertEquals(1416, protocol.clientCommand("SceneCastSkillScRsp"));
    assertEquals(13, list.getNumber());
    assertEquals(5, list.getMessageType().findFieldByName("target_monster_entity_id").getNumber());
    var kind = list.getMessageType().findFieldByName("monster_battle_type");
    assertEquals(11, kind.getNumber());
    assertEquals(1, kind.getEnumType().findValueByName("MONSTER_BATTLE_TYPE_TRIGGER_BATTLE").getNumber());
    assertEquals(3, kind.getEnumType().findValueByName("MONSTER_BATTLE_TYPE_DIRECT_DIE_SKIP_BATTLE").getNumber());
    assertEquals(4, kind.getEnumType().findValueByName("MONSTER_BATTLE_TYPE_NO_BATTLE").getNumber());
  }

  @Test
  void ordinaryAttackReportsItsActualMonsterAndKeepsTheBattleForSettlement() throws Exception {
    skill(false, true);
    var target = monster("[]");
    int id = target.getEntityId();
    var response = dispatch(new int[] {id, id}, new int[] {});
    assertEquals(0, number(response, "retcode"));
    assertEquals(actor.getEntityId(), number(response, "cast_entity_id"));
    assertEquals(1, rows(response).size());
    outcome(rows(response).getFirst(), id, 1);
    var battle = branch.base.base.player.getBattle();
    assertNotNull(battle);
    assertEquals(List.of(target), battle.getNpcMonsters());
    assertTrue(response.hasField(response.getDescriptorForType().findFieldByName("battle_info")));
    assertNull(service.finishBattle(branch.base.base.player, battle.getId() + 1, battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
    assertSame(battle, branch.base.base.player.getBattle());
    assertTrue(target.isAlive());
    assertSame(battle, service.finishBattle(branch.base.base.player, battle.getId(), battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
    assertFalse(target.isAlive());
    assertNull(service.finishBattle(branch.base.base.player, battle.getId(), battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void originalDirectDefeatReportsTheFrozenPositiveIdentityAndDoesNotCreateBattle(boolean battle) throws Exception {
    skill(true, battle);
    var target = monster("[]");
    int id = target.getEntityId();
    var response = dispatch(new int[] {id, id}, new int[] {});
    assertEquals(1, rows(response).size());
    outcome(rows(response).getFirst(), id, 3);
    assertEquals(0, target.getEntityId());
    assertNull(branch.base.base.player.getBattle());
    assertEquals(1, branch.base.base.drops);
    assertEquals(1, branch.base.base.deaths);
    var repeated = dispatch(new int[] {id}, new int[] {});
    assertNotEquals(0, number(repeated, "retcode"));
    assertTrue(rows(repeated).isEmpty());
    assertEquals(1, branch.base.base.drops);
    assertEquals(1, branch.base.base.deaths);
  }

  @Test
  void mixedOriginalDefeatAndResistantMonsterOnlyLoadTheSurvivorWaves() throws Exception {
    skill(true, true);
    var dead = monster("[]");
    var alive = monster("[\"ResistInstantKill\"]");
    int deadId = dead.getEntityId(), aliveId = alive.getEntityId();
    var response = dispatch(new int[] {deadId, aliveId}, new int[] {});
    var battle = branch.base.base.player.getBattle();
    assertNotNull(battle);
    assertEquals(List.of(alive), battle.getNpcMonsters(), "An already defeated scene monster must not reappear in battle");
    assertEquals(GameData.getStageExcelMap().get(SceneEventBattle46Test.STAGE).getMonsterWaves().size(), battle.getWaves().size());
    assertEquals(2, rows(response).size());
    outcome(rows(response).get(0), deadId, 3);
    outcome(rows(response).get(1), aliveId, 1);
    assertEquals(1, branch.base.base.drops);
  }

  @Test
  void nonBattleHitAcknowledgesTheLiveMonsterWithoutPretendingToDefeatIt() throws Exception {
    skill(false, false);
    var target = monster("[]");
    int id = target.getEntityId();
    var response = dispatch(new int[] {id}, new int[] {});
    assertEquals(1, rows(response).size());
    outcome(rows(response).getFirst(), id, 4);
    assertTrue(target.isAlive());
    assertNull(branch.base.base.player.getBattle());
    assertEquals(0, branch.base.base.drops);
  }

  @Test
  void assistAndDuplicateHitsAreAcknowledgedOnceInTheActualEncounter() throws Exception {
    skill(false, true);
    var hit = monster("[]");
    var assist = monster("[]");
    int hitId = hit.getEntityId(), assistId = assist.getEntityId();
    var response = dispatch(new int[] {hitId}, new int[] {assistId, hitId, assistId});
    assertEquals(2, rows(response).size());
    outcome(rows(response).get(0), hitId, 1);
    outcome(rows(response).get(1), assistId, 1);
    assertEquals(2, branch.base.base.player.getBattle().getNpcMonsters().size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void propAssistDoesNotDropTheMonsterEncounter(boolean assistOnly) throws Exception {
    skill(false, true);
    var target = monster("[]");
    var player = branch.base.base.player;
    emu.lunarcore.data.excel.PropExcel excel;
    try (var input = getClass().getResourceAsStream("/fixtures/base-zone-recovery-prop-4.6.json")) {
      var fixture = JsonParser.parseReader(
          new InputStreamReader(Objects.requireNonNull(input), StandardCharsets.UTF_8)).getAsJsonObject();
      excel = emu.lunarcore.data.ResourceLoader.getGson().fromJson(fixture
          .getAsJsonObject("records").getAsJsonArray("ExcelOutput/MazeProp.json").get(0),
          emu.lunarcore.data.excel.PropExcel.class);
    }
    excel.onLoad();
    // Placement is isolated; the recovery definition is the complete original row.
    var prop = new emu.lunarcore.game.scene.entity.EntityProp(player.getScene(), excel, null, null);
    player.getScene().addEntity(prop);
    int propId = prop.getEntityId(), monsterId = target.getEntityId();
    player.getCurrentLineup().setMp(0);
    var response = dispatch(assistOnly ? new int[] {} : new int[] {monsterId},
        new int[] {propId, monsterId});
    assertEquals(0, number(response, "retcode"));
    assertFalse(player.getScene().getEntities().containsKey(propId));
    assertEquals(2, player.getCurrentLineup().getMp());
    assertNotNull(player.getBattle());
    assertEquals(List.of(target), player.getBattle().getNpcMonsters());
    assertEquals(1, rows(response).size());
    outcome(rows(response).getFirst(), monsterId, 1);
  }

  @Test
  void unknownTargetRejectsWithoutPublishingAnOutcomeOrStartingABattle() throws Exception {
    skill(true, true);
    var target = monster("[]");
    var response = dispatch(new int[] {target.getEntityId(), 199996001}, new int[] {});
    assertNotEquals(0, number(response, "retcode"));
    assertTrue(rows(response).isEmpty());
    assertTrue(target.isAlive());
    assertNull(branch.base.base.player.getBattle());
    assertEquals(0, branch.base.base.drops);
    assertEquals(0, branch.base.base.deaths);
  }

  @Test
  void missingStageCannotPublishAPretendBattleOutcome() throws Exception {
    skill(false, true);
    var target = monster("[]");
    target.setCustomStage((BattleStage) null);
    target.setEventId(199996001);
    var response = dispatch(new int[] {target.getEntityId()}, new int[] {});
    assertNotEquals(0, number(response, "retcode"));
    assertTrue(rows(response).isEmpty());
    assertNull(branch.base.base.player.getBattle());
  }

  @Test
  void unknownAssistRejectsBeforeTheOriginalInstantKillCanExecute() throws Exception {
    skill(true, true);
    var target = monster("[]");
    var response = dispatch(new int[] {target.getEntityId()}, new int[] {199996001});
    assertNotEquals(0, number(response, "retcode"));
    assertTrue(rows(response).isEmpty());
    assertTrue(target.isAlive());
    assertNull(branch.base.base.player.getBattle());
    assertEquals(0, branch.base.base.drops);
    assertEquals(0, branch.base.base.deaths);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void administrativeRemovalIsNeitherADirectDefeatNorABattleParticipant(boolean battle) throws Exception {
    var removed = monster("[]");
    var alive = monster("[]");
    int removedId = removed.getEntityId(), aliveId = alive.getEntityId();
    // Explicit administrative callback fixture; source death actions use defeatMonster.
    skill(false, battle).getAttackActions().add(new MazeSkillAction() {
      @Override public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
        caster.getOwner().getScene().removeEntity(removed);
      }
      @Override public void onCastHit(GameAvatar caster, List<? extends GameEntity> targets) {
        onAttack(caster, targets);
      }
    });
    var response = dispatch(new int[] {removedId, aliveId}, new int[] {});
    assertEquals(0, number(response, "retcode"));
    assertEquals(2, rows(response).size());
    outcome(rows(response).get(0), removedId, 4);
    outcome(rows(response).get(1), aliveId, battle ? 1 : 4);
    assertEquals(0, removed.getEntityId());
    assertTrue(alive.isAlive());
    if (battle) assertEquals(List.of(alive), branch.base.base.player.getBattle().getNpcMonsters());
    else assertNull(branch.base.base.player.getBattle());
    assertEquals(0, branch.base.base.drops);
    assertEquals(0, branch.base.base.deaths);
  }

  @Test
  void assistantsDoNotPretendToJoinAnEncounterWhenEveryPrimaryWasActuallyDefeated() throws Exception {
    skill(true, true);
    var dead = monster("[]");
    var assist = monster("[]");
    int deadId = dead.getEntityId(), assistId = assist.getEntityId();
    var response = dispatch(new int[] {deadId}, new int[] {assistId});
    assertEquals(2, rows(response).size());
    outcome(rows(response).get(0), deadId, 3);
    outcome(rows(response).get(1), assistId, 4);
    assertNull(branch.base.base.player.getBattle());
    assertTrue(assist.isAlive());
    assertEquals(1, branch.base.base.drops);
    assertEquals(1, branch.base.base.deaths);
  }

  @ParameterizedTest
  @EnumSource(value = BattleEndStatus.class, names = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void acknowledgedBattleKeepsItsMonsterAliveWhenTheAcceptedResultIsNotVictory(BattleEndStatus status) throws Exception {
    skill(false, true);
    var target = monster("[]");
    int id = target.getEntityId();
    var response = dispatch(new int[] {id}, new int[] {});
    outcome(rows(response).getFirst(), id, 1);
    var player = branch.base.base.player;
    var battle = player.getBattle();
    assertSame(battle, service.finishBattle(player, battle.getId(), battle.getStage().getId(),
        status, BattleStatistics.newInstance()));
    assertTrue(target.isAlive());
    assertSame(target, player.getScene().getEntityById(id));
    assertNull(player.getBattle());
    assertEquals(0, branch.base.base.drops);
    assertEquals(0, branch.base.base.deaths);
  }

  @Test
  void anotherCastAfterDirectDefeatReportsOnlyItsOwnActualTarget() throws Exception {
    skill(true, true);
    var dead = monster("[]");
    int deadId = dead.getEntityId();
    outcome(rows(dispatch(new int[] {deadId}, new int[] {})).getFirst(), deadId, 3);
    skill(false, false);
    var next = monster("[]");
    int nextId = next.getEntityId();
    var response = dispatch(new int[] {nextId}, new int[] {});
    assertEquals(1, rows(response).size());
    outcome(rows(response).getFirst(), nextId, 4);
    assertTrue(next.isAlive());
    assertNull(branch.base.base.player.getBattle());
    assertEquals(1, branch.base.base.drops);
  }

  @Test
  void actualMonsterAmbushReportsItsOwnEncounterTarget() throws Exception {
    var monster = monster("[]");
    int id = monster.getEntityId();
    var response = dispatch(id, id, new int[] {actor.getEntityId()}, new int[] {});
    assertEquals(0, number(response, "retcode"));
    assertEquals(id, number(response, "cast_entity_id"));
    assertEquals(1, rows(response).size());
    outcome(rows(response).getFirst(), id, 1);
    assertEquals(List.of(monster), branch.base.base.player.getBattle().getNpcMonsters());
  }

  @Test
  void monsterThatDoesNotHitAnAvatarCannotPublishAPretendEncounter() throws Exception {
    var monster = monster("[]");
    int id = monster.getEntityId();
    var response = dispatch(id, id, new int[] {}, new int[] {});
    assertEquals(0, number(response, "retcode"));
    assertTrue(rows(response).isEmpty());
    assertTrue(monster.isAlive());
    assertNull(branch.base.base.player.getBattle());
  }
}
