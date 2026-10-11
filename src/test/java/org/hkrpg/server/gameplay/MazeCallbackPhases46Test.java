package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.BattleStage;
import emu.lunarcore.game.battle.skills.*;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original actor/ability records through the actual native handler and battle service. */
class MazeCallbackPhases46Test {
  @TempDir Path root;
  final MazeAttackResults46Test base = new MazeAttackResults46Test();
  final Map<Integer, AvatarExcel> previous = new HashMap<>();
  JsonObject proof;

  @BeforeEach
  void setup() throws Exception {
    base.root = root;
    base.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/maze-callback-phases-4.6.json")) {
      assertNotNull(input);
      proof = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", proof.get("sourceCommit").getAsString());
  }

  @AfterEach
  void cleanup() throws Exception {
    base.cleanup();
    for (var entry : previous.entrySet()) {
      GameData.getAvatarExcelMap().keySet().remove(entry.getKey());
      if (entry.getValue() != null) GameData.getAvatarExcelMap().add(entry.getValue());
    }
  }

  JsonObject record(int avatar) {
    return proof.getAsJsonArray("records").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(value -> value.get("avatarID").getAsInt() == avatar).findFirst().orElseThrow();
  }

  MazeSkill actor(int id) {
    var record = record(id);
    previous.putIfAbsent(id, GameData.getAvatarExcelMap().get(id));
    var excel = ResourceLoader.getGson().fromJson(record.getAsJsonObject("avatar"), AvatarExcel.class);
    var config = new JsonObject();
    var abilities = new JsonArray();
    abilities.add(record.getAsJsonObject("ability"));
    config.add("AbilityList", abilities);
    assertTrue(ResourceLoader.getGson().fromJson(config, SkillAbilityInfo.class).parse(excel));
    GameData.getAvatarExcelMap().add(excel);
    var player = base.branch.base.base.player;
    int entityId = base.actor.getEntityId();
    base.actor = new GameAvatar(excel) { @Override public void save() {} };
    base.actor.setOwner(player);
    base.actor.setEntityId(entityId);
    player.getAvatars().getAvatars().put(id, base.actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(id);
    player.getCurrentLineup().setMp(5);
    player.getScene().getAvatars().clear();
    player.getScene().getAvatars().put(id, base.actor);
    player.getScene().getAvatarEntityIds().clear();
    player.getScene().getAvatarEntityIds().add(entityId);
    return id == 1310 ? excel.getMazeSkill() : excel.getMazeAttack();
  }

  DynamicMessage dispatch(int index, int[] tags, EntityMonster... targets) throws Exception {
    var session = base.new Session();
    var request = DynamicMessage.newBuilder(base.protocol.clientSchema().message("SceneCastSkillCsReq"));
    var type = request.getDescriptorForType();
    request.setField(type.findFieldByName("cast_entity_id"), base.actor.getEntityId());
    request.setField(type.findFieldByName("attacked_by_entity_id"), base.actor.getEntityId());
    request.setField(type.findFieldByName("skill_index"), index);
    for (var target : targets) request.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), target.getEntityId());
    var field = type.findFieldByName("skill_extra_tags");
    for (int tag : tags) request.addRepeatedField(field, field.getEnumType().findValueByNumberCreatingIfUnknown(tag));
    assertTrue(VersionedPacketHandler.handle(session, base.protocol.clientCommand("SceneCastSkillCsReq"), request.build().toByteArray()));
    assertEquals(1, session.responses.size());
    return session.responses.getFirst();
  }

  @Test
  void frozenNativeTagFieldAndEnumsMatchTheVerifiedPacketWriter() {
    var type = base.protocol.clientSchema().message("SceneCastSkillCsReq");
    assertEquals(1460, base.protocol.clientCommand("SceneCastSkillCsReq"));
    var field = type.findFieldByName("skill_extra_tags");
    assertEquals(11, field.getNumber());
    assertTrue(field.isRepeated());
    assertEquals(1, field.getEnumType().findValueByName("SCENE_CAST_SKILL_PROJECTILE_HIT").getNumber());
    assertEquals(2, field.getEnumType().findValueByName("SCENE_CAST_SKILL_PROJECTILE_LIFETIME_FINISH").getNumber());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void original8007ProjectileEventActuallyStartsAndSettlesItsOwnBattle(int tag) throws Exception {
    var skill = actor(8007);
    assertEquals("LocalPlayer_PlayerBoy_30_NormalAtk01", skill.getAbilityName());
    var target = base.monster("[]");
    int id = target.getEntityId();
    var response = dispatch(0, new int[] {tag}, target);
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    MazeAttackResults46Test.outcome(MazeAttackResults46Test.rows(response).getFirst(), id, 1);
    var player = base.branch.base.base.player;
    var battle = player.getBattle();
    assertNotNull(battle);
    assertEquals(List.of(target), battle.getNpcMonsters());
    assertSame(battle, base.service.finishBattle(player, battle.getId(), battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
    assertNull(player.getBattle());
    assertFalse(target.isAlive());
  }

  MazeSkill phaseMarkers() {
    actor(1009);
    // Explicit original Asta projectile node, with two controlled test leaves.
    var projectile = record(1009).getAsJsonObject("ability").getAsJsonArray("OnStart").get(1)
        .getAsJsonObject().getAsJsonArray("SuccessTaskList").get(7).getAsJsonObject().deepCopy();
    assertEquals("RPG.GameCore.AdventureFireProjectile", projectile.get("$type").getAsString());
    assertEquals("Static", projectile.getAsJsonObject("Projectile").get("Behavior").getAsString());
    assertFalse(projectile.getAsJsonObject("Projectile").has("$type"), "Concrete original ProjectileData");
    var hit = new JsonArray();
    hit.add(base.branch.base.base.fixture.add(199998101, 20));
    var end = new JsonArray();
    end.add(base.branch.base.base.fixture.add(199998102, 20));
    projectile.add("OnProjectileHit", hit);
    projectile.add("OnProjectileLifetimeFinish", end);
    return base.branch.base.base.fixture.parse(base.actor.getExcel(),
        "LocalPlayer_Asta_NormalAtk01", projectile);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void hitAndLifetimeFinishRunOnlyTheirOwnActionsThroughTheNativeEntry(int tag) throws Exception {
    phaseMarkers();
    var response = dispatch(0, new int[] {tag}, base.monster("[]"));
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertEquals(tag == 1, base.actor.getBuffs().containsKey(199998101));
    assertEquals(tag == 2, base.actor.getBuffs().containsKey(199998102));
  }

  @Test
  void anUntaggedRequestDoesNotPretendAProjectileHitOrLifetimeExpiryOccurred() throws Exception {
    phaseMarkers();
    var response = dispatch(0, new int[] {}, base.monster("[]"));
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertFalse(base.actor.getBuffs().containsKey(199998101));
    assertFalse(base.actor.getBuffs().containsKey(199998102));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void duplicateReverseOrUnknownTagsCannotApplyEffects(int kind) throws Exception {
    phaseMarkers();
    // The new frozen constructor/Writer proof establishes canonical [1,2] as native default.
    int[] tags = kind == 0 ? new int[] {1, 1} : kind == 1 ? new int[] {2, 1} : new int[] {99};
    var response = dispatch(0, tags, base.monster("[]"));
    assertNotEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertFalse(base.actor.getBuffs().containsKey(199998101));
    assertFalse(base.actor.getBuffs().containsKey(199998102));
    assertNull(base.branch.base.base.player.getBattle());
  }

  @Test
  void aMeleeSkillWithoutAProjectileDeclarationCannotClaimAProjectileEvent() throws Exception {
    base.skill(false, true);
    var response = dispatch(0, new int[] {2}, base.monster("[]"));
    assertNotEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(base.branch.base.base.player.getBattle());
  }

  @Test
  void ordinaryUntaggedAttackKeepsItsCurrentBehavior() throws Exception {
    base.skill(false, true);
    var target = base.monster("[]");
    var response = dispatch(0, new int[] {}, target);
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    MazeAttackResults46Test.outcome(MazeAttackResults46Test.rows(response).getFirst(), target.getEntityId(), 1);
  }

  @Test
  void original1310OnBattleDoesNotRunWhenNoBattleIsAdmitted() throws Exception {
    actor(1310);
    var target = base.branch.base.assembled("Minion", "[]");
    target.setCustomStage((BattleStage) null);
    target.setEventId(199996001);
    var response = dispatch(1, new int[] {}, target);
    assertNotEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(base.branch.base.base.player.getBattle());
    assertFalse(base.actor.getBuffs().containsKey(1000112));
    assertTrue(target.getTempBuffs() == null || target.getTempBuffs().isEmpty());
  }

  @Test
  void onBattleRunsAfterAttackAndStillBindsActualWavesAndTheCasterSnapshot() throws Exception {
    var skill = actor(1310);
    var target = base.monster("[]");
    var observed = new boolean[1];
    skill.getAttackActions().add(new MazeSkillAction() {
      @Override public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
        observed[0] = true;
        assertFalse(caster.getBuffs().containsKey(1000112), "OnBattle was flattened into OnAttack");
        assertTrue(target.getTempBuffs() == null || target.getTempBuffs().isEmpty());
      }
    });
    var response = dispatch(1, new int[] {}, target);
    assertTrue(observed[0]);
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    var battle = base.branch.base.base.player.getBattle();
    assertNotNull(battle);
    int waves = battle.getWaves().size();
    int mask = (int) ((1L << waves) - 1);
    assertEquals(mask, battle.getBuffs().get(131001).getWaveFlag());
    assertEquals(mask, battle.getBuffs().get(131002).getWaveFlag());
    assertTrue(target.getTempBuffs() == null || target.getTempBuffs().isEmpty());
    boolean captured = false;
    for (var buff : battle.toProto().getBuffList()) if (buff.getId() == 1000112) captured = true;
    assertTrue(captured);
  }
}
