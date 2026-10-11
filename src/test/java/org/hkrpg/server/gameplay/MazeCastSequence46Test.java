package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.BattleStage;
import emu.lunarcore.game.battle.skills.*;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.game.*;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Fixed original records, real native ingress and BattleService; no simulated cast ledger. */
class MazeCastSequence46Test {
  @TempDir Path root;
  final MazeCallbackPhases46Test phases = new MazeCallbackPhases46Test();
  final MazeAttackResults46Test base = phases.base;
  JsonObject proof;
  String previousResources;
  Session session;
  final int[] observed = new int[4]; // release, hit, expiry, ordinary attack

  @BeforeEach
  void setup() throws Exception {
    phases.root = root;
    phases.setup();
    try (var in = getClass().getResourceAsStream("/fixtures/maze-cast-sequence-4.6.json")) {
      assertNotNull(in);
      proof = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", proof.get("sourceCommit").getAsString());
    var rows = new JsonArray();
    var avatars = new JsonArray();
    for (var record : proof.getAsJsonArray("records")) {
      var value = record.getAsJsonObject();
      avatars.add(value.get("avatar"));
      value.getAsJsonArray("mazeRows").forEach(rows::add);
      var path = root.resolve(value.get("abilityFile").getAsString());
      Files.createDirectories(path.getParent());
      var config = new JsonObject();
      config.add("AbilityList", value.get("abilities"));
      Files.writeString(path, config.toString());
    }
    Files.writeString(root.resolve("ExcelOutput/MazeSkill.json"), rows.toString());
    Files.writeString(root.resolve("ExcelOutput/AvatarConfig.json"), avatars.toString());
    previousResources = LunarCore.getConfig().resourceDir;
    LunarCore.getConfig().resourceDir = root.toString();
    session = new Session();
    base.branch.base.base.player.setSession(session);
  }

  @AfterEach
  void restore() throws Exception {
    LunarCore.getConfig().resourceDir = previousResources;
    phases.cleanup();
  }

  JsonObject record(int id) {
    return proof.getAsJsonArray("records").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(value -> value.get("avatarID").getAsInt() == id).findFirst().orElseThrow();
  }

  MazeSkill actor(int id) {
    var row = record(id);
    phases.previous.putIfAbsent(id, GameData.getAvatarExcelMap().get(id));
    var excel = ResourceLoader.getGson().fromJson(row.get("avatar"), AvatarExcel.class);
    var config = new JsonObject();
    config.add("AbilityList", row.get("abilities"));
    assertTrue(ResourceLoader.getGson().fromJson(config, SkillAbilityInfo.class).parse(excel));
    assertNotNull(excel.getMazeSkill());
    GameData.getAvatarExcelMap().add(excel);
    var player = session.getPlayer();
    int entity = base.actor.getEntityId();
    base.actor = new GameAvatar(excel) { @Override public void save() {} };
    base.actor.setOwner(player);
    base.actor.setEntityId(entity);
    player.getAvatars().getAvatars().put(id, base.actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(id);
    player.getCurrentLineup().setMp(5);
    player.getScene().getAvatars().clear();
    player.getScene().getAvatars().put(id, base.actor);
    player.getScene().getAvatarEntityIds().clear();
    player.getScene().getAvatarEntityIds().add(entity);
    return excel.getMazeSkill();
  }

  final class Session extends GameSession {
    final Map<String, List<DynamicMessage>> messages = new HashMap<>();
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public emu.lunarcore.game.player.Player getPlayer() { return base.branch.base.base.player; }
    @Override public GameServer getServer() { return base.shell; }
    @Override public ProtocolBridge getProtocol() { return base.protocol; }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try {
        messages.computeIfAbsent(name, ignored -> new ArrayList<>()).add(getProtocol().clientSchema().parse(name, bytes));
      } catch (Exception error) { throw new AssertionError(error); }
    }
    @Override public void send(BasePacket packet) {
      if (packet.getCmdId() == CmdId.SceneCastSkillMpUpdateScNotify) {
        var converted = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
        sendClientPacket("SceneCastSkillMpUpdateScNotify", converted.body());
      }
    }
  }

  static int number(DynamicMessage message, String name) {
    return MazeAttackResults46Test.number(message, name);
  }

  int mp() { return session.getPlayer().getCurrentLineup().getMp(); }

  DynamicMessage request(String name, int use, int caster, int index, int[] tags, EntityMonster... targets) throws Exception {
    var request = DynamicMessage.newBuilder(base.protocol.clientSchema().message(name));
    var type = request.getDescriptorForType();
    request.setField(Objects.requireNonNull(type.findFieldByName("cast_entity_id")), use);
    request.setField(Objects.requireNonNull(type.findFieldByName("attacked_by_entity_id")), caster);
    request.setField(Objects.requireNonNull(type.findFieldByName("skill_index")), index);
    if (name.equals("SceneCastSkillCsReq")) {
      for (var target : targets) request.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), target.getEntityId());
      var field = type.findFieldByName("skill_extra_tags");
      for (int tag : tags) request.addRepeatedField(field, field.getEnumType().findValueByNumberCreatingIfUnknown(tag));
    }
    return dispatch(name, request.build());
  }

  DynamicMessage dispatch(String name, DynamicMessage request) throws Exception {
    String reply = name.replace("CsReq", "ScRsp");
    var messages = session.messages.computeIfAbsent(reply, ignored -> new ArrayList<>());
    int before = messages.size();
    assertTrue(VersionedPacketHandler.handle(session, base.protocol.clientCommand(name), request.toByteArray()),
        "Native " + name + " must be handled without incompatible legacy field translation");
    assertEquals(before + 1, messages.size(), "One request has exactly one final reply");
    return messages.getLast();
  }

  DynamicMessage cast(int use, int index, int[] tags, EntityMonster... targets) throws Exception {
    return request("SceneCastSkillCsReq", use, base.actor.getEntityId(), index, tags, targets);
  }

  DynamicMessage cost(int use) throws Exception {
    return request("SceneCastSkillCostMpCsReq", use, base.actor.getEntityId(), 1, new int[] {});
  }

  MazeSkillAction counter(int index) {
    return new MazeSkillAction() {
      @Override public void onCast(GameAvatar caster, MotionInfo motion) { observed[index]++; }
      @Override public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) { observed[index]++; }
      @Override public void onCastHit(GameAvatar caster, List<? extends GameEntity> targets) { observed[index]++; }
    };
  }

  MazeSkill controlledProjectileTechnique() {
    actor(1009);
    var original = record(1009).getAsJsonArray("abilities").get(1).getAsJsonObject();
    var projectile = original.getAsJsonArray("OnStart").get(0).getAsJsonObject()
        .getAsJsonArray("SuccessTaskList").get(8).getAsJsonObject();
    assertEquals("RPG.GameCore.AdventureFireProjectile", projectile.get("$type").getAsString());
    var skill = base.branch.base.base.fixture.parse(base.actor.getExcel(), "LocalPlayer_Asta_MazeSkill", projectile);
    assertTrue(skill.isProjectileDeclared(), "The original concrete projectile declaration is retained");
    // Controlled effect leaves replace neither wire provenance nor the full source technique.
    skill.getCastActions().clear();
    skill.getCastActions().add(counter(0));
    skill.getAttackActions().clear();
    skill.getAttackActions().add(new MazeSkillProjectileCallbacks(skill, List.of(counter(1)), List.of(counter(2))));
    skill.setTriggerBattle(false);
    return skill;
  }

  @Test
  void frozenWritersReallyCarryTheSameUintUseIdOnBothRequests() {
    for (var name : List.of("SceneCastSkillCsReq", "SceneCastSkillCostMpCsReq")) {
      var field = base.protocol.clientSchema().message(name).findFieldByName("cast_entity_id");
      assertEquals(13, field.getNumber());
      assertEquals(com.google.protobuf.Descriptors.FieldDescriptor.Type.UINT32, field.getType());
    }
    assertEquals(1408, base.protocol.clientCommand("SceneCastSkillCostMpCsReq"));
    assertEquals(7, base.protocol.clientSchema().message("SceneCastSkillCostMpCsReq").findFieldByName("skill_index").getNumber());
    assertEquals(2, record(1510).getAsJsonArray("mazeRows").get(1).getAsJsonObject().get("MPCost").getAsInt());
  }

  @Test
  void nativeDefaultCombinedTagsAdmitOneOrdinaryBattleAndOneAttack() throws Exception {
    var skill = base.skill(false, true);
    skill.getAttackActions().add(counter(3));
    var target = base.monster("[]");
    var response = cast(72131, 0, new int[] {1, 2}, target);
    assertEquals(0, number(response, "retcode"));
    assertEquals(72131, number(response, "cast_entity_id"), "UseID is not the avatar entity");
    assertEquals(1, observed[3]);
    assertEquals(1, MazeAttackResults46Test.rows(response).size());
    MazeAttackResults46Test.outcome(MazeAttackResults46Test.rows(response).getFirst(), target.getEntityId(), 1);
    assertEquals(List.of(target), session.getPlayer().getBattle().getNpcMonsters());
    assertEquals(5, mp());
  }

  @Test
  void canonicalCombinedTagsExecuteEachSelectedProjectileLeafOnce() throws Exception {
    controlledProjectileTechnique();
    var response = cast(72132, 1, new int[] {1, 2}, base.monster("[]"));
    assertEquals(0, number(response, "retcode"));
    assertArrayEquals(new int[] {1, 1, 1, 0}, observed);
    assertNull(session.getPlayer().getBattle());
    assertEquals(4, mp());
  }

  @ParameterizedTest
  @CsvSource({"1,1", "2,1", "0,1", "99,2"})
  void duplicateReverseAndUnknownCombinationsDoNotSpendOrRunActions(int first, int second) throws Exception {
    controlledProjectileTechnique();
    var response = cast(72133, 1, new int[] {first, second}, base.monster("[]"));
    assertNotEquals(0, number(response, "retcode"));
    assertArrayEquals(new int[4], observed);
    assertEquals(5, mp());
    assertNull(session.getPlayer().getBattle());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void independentEventTagsKeepTheirOwnCallback(int tag) throws Exception {
    controlledProjectileTechnique();
    var response = cast(72134, 1, new int[] {tag}, base.monster("[]"));
    assertEquals(0, number(response, "retcode"));
    assertEquals(1, observed[0]);
    assertEquals(tag == 1 ? 1 : 0, observed[1]);
    assertEquals(tag == 2 ? 1 : 0, observed[2]);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryEmptyAndExplicitNoneKeepTheDirectPath(boolean explicit) throws Exception {
    controlledProjectileTechnique();
    var response = cast(72135, 1, explicit ? new int[] {0} : new int[] {}, base.monster("[]"));
    assertEquals(0, number(response, "retcode"));
    assertArrayEquals(new int[] {1, 0, 0, 0}, observed);
  }

  @ParameterizedTest
  @ValueSource(ints = {1009, 1510})
  void nativeOnUsePrepaymentUsesTheActualOriginalCostAndRepliesWithItsUseId(int actor) throws Exception {
    actor(actor);
    int amount = actor == 1009 ? 1 : 2;
    var response = cost(72201);
    assertEquals(0, number(response, "retcode"));
    assertEquals(72201, number(response, "cast_entity_id"));
    assertEquals(5 - amount, mp());
    var notifications = session.messages.get("SceneCastSkillMpUpdateScNotify");
    assertNotNull(notifications);
    assertEquals(1, notifications.size());
    assertEquals(72201, number(notifications.getFirst(), "cast_entity_id"));
    assertEquals(5 - amount, number(notifications.getFirst(), "mp"));
  }

  @Test
  void samePrepaidUseIdSpansNoneHitAndLifetimeWithoutRepeatedCostOrReleaseEffects() throws Exception {
    controlledProjectileTechnique();
    assertEquals(0, number(cost(72202), "retcode"));
    assertArrayEquals(new int[4], observed, "1408 pays; it does not invent a skill event");
    for (int tag : new int[] {0, 1, 2}) {
      var response = cast(72202, 1, new int[] {tag}, base.monster("[]"));
      assertEquals(0, number(response, "retcode"));
      assertEquals(4, mp(), "All stages refer to the already paid original UseID");
    }
    assertArrayEquals(new int[] {1, 1, 1, 0}, observed);
    assertEquals(0, number(cost(72202), "retcode"));
    assertEquals(4, mp(), "An application replay of the payment must not spend again");
  }

  @Test
  void consecutiveCastsOfTheSameSkillHaveDistinctPaymentsAndReleaseEffects() throws Exception {
    controlledProjectileTechnique();
    for (int use : new int[] {72203, 72204}) {
      assertEquals(0, number(cost(use), "retcode"));
      assertEquals(0, number(cast(use, 1, new int[] {0}, base.monster("[]")), "retcode"));
    }
    assertEquals(3, mp());
    assertArrayEquals(new int[] {2, 0, 0, 0}, observed);
  }

  @Test
  void illegalStageBeforePaymentDoesNotBindOrPoisonTheLaterValidUse() throws Exception {
    controlledProjectileTechnique();
    assertNotEquals(0, number(cast(72205, 1, new int[] {99}, base.monster("[]")), "retcode"));
    assertEquals(5, mp());
    assertArrayEquals(new int[4], observed);
    assertEquals(0, number(cost(72205), "retcode"));
    assertEquals(0, number(cast(72205, 1, new int[] {1}, base.monster("[]")), "retcode"));
    assertEquals(4, mp());
    assertArrayEquals(new int[] {1, 1, 0, 0}, observed);
  }

  @Test
  void multipleLegitimateHitsOfOneAbilityAreNotSilencedAsOneWholePhase() throws Exception {
    controlledProjectileTechnique();
    assertEquals(0, number(cost(72206), "retcode"));
    var first = base.monster("[]");
    var second = base.monster("[]");
    assertNotEquals(first.getEntityId(), second.getEntityId());
    assertEquals(0, number(cast(72206, 1, new int[] {1}, first), "retcode"));
    assertEquals(0, number(cast(72206, 1, new int[] {1}, second), "retcode"));
    assertEquals(4, mp());
    assertArrayEquals(new int[] {1, 2, 0, 0}, observed);
  }

  @Test
  void anOlderProjectileRetainsItsOwnPrepaymentAfterAnotherUseStarts() throws Exception {
    controlledProjectileTechnique();
    assertEquals(0, number(cost(72207), "retcode"));
    assertEquals(0, number(cast(72207, 1, new int[] {0}, base.monster("[]")), "retcode"));
    assertEquals(0, number(cost(72208), "retcode"));
    assertEquals(0, number(cast(72208, 1, new int[] {0}, base.monster("[]")), "retcode"));
    assertEquals(0, number(cast(72207, 1, new int[] {2}, base.monster("[]")), "retcode"));
    assertEquals(3, mp());
    assertArrayEquals(new int[] {2, 0, 1, 0}, observed);
  }

  @Test
  void failedBattleAdmissionAndItsRetryKeepTheAlreadyPaidRelease() throws Exception {
    var skill = actor(1009);
    skill.getCastActions().clear();
    skill.getCastActions().add(counter(0));
    skill.getAttackActions().clear();
    skill.setTriggerBattle(true);
    assertEquals(0, number(cost(72209), "retcode"));
    var bad = base.monster("[]");
    bad.setCustomStage((BattleStage) null);
    bad.setEventId(199996001);
    assertNotEquals(0, number(cast(72209, 1, new int[] {}, bad), "retcode"));
    assertNull(session.getPlayer().getBattle());
    assertEquals(4, mp());
    assertEquals(1, observed[0]);
    var good = base.monster("[]");
    assertEquals(0, number(cast(72209, 1, new int[] {}, good), "retcode"));
    assertEquals(4, mp());
    assertEquals(1, observed[0]);
    assertEquals(List.of(good), session.getPlayer().getBattle().getNpcMonsters());
  }

  @Test
  void insufficientPaymentCanBeRetriedWithTheSameIdWithoutAnUnpaidReceipt() throws Exception {
    controlledProjectileTechnique();
    session.getPlayer().getCurrentLineup().setMp(0);
    assertNotEquals(0, number(cost(72210), "retcode"));
    assertEquals(0, mp());
    session.getPlayer().getCurrentLineup().setMp(1);
    assertEquals(0, number(cost(72210), "retcode"));
    assertEquals(0, mp());
    assertEquals(0, number(cast(72210, 1, new int[] {1}, base.monster("[]")), "retcode"));
    assertEquals(0, mp(), "A prepaid cast remains valid at zero MP");
    assertEquals(1, observed[0]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -2, Integer.MAX_VALUE})
  void prepaymentIdentityCannotBeReassignedToAnotherSkillOrCaster(int otherCaster) throws Exception {
    controlledProjectileTechnique();
    assertEquals(0, number(cost(72211), "retcode"));
    int caster = otherCaster == 0 ? base.actor.getEntityId() : otherCaster;
    int index = otherCaster == 0 ? 0 : 1;
    var response = request("SceneCastSkillCsReq", 72211, caster, index, new int[] {}, base.monster("[]"));
    assertNotEquals(0, number(response, "retcode"));
    assertEquals(4, mp());
    assertArrayEquals(new int[4], observed);
    assertEquals(0, number(cast(72211, 1, new int[] {1}, base.monster("[]")), "retcode"));
    assertEquals(4, mp());
  }

  @Test
  void nativeUnsignedUseIdsAboveSignedIntMaxRemainDistinctAndUsable() throws Exception {
    controlledProjectileTechnique();
    for (int use : new int[] {Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 2}) {
      assertEquals(0, number(cost(use), "retcode"));
      var response = cast(use, 1, new int[] {0}, base.monster("[]"));
      assertEquals(0, number(response, "retcode"));
      assertEquals(use, number(response, "cast_entity_id"));
    }
    assertEquals(3, mp());
    assertEquals(2, observed[0]);
  }

  @Test
  void anotherValidLivingSceneCasterCannotSpendTheFirstCastersReceipt() throws Exception {
    controlledProjectileTechnique();
    var row = record(1510);
    phases.previous.putIfAbsent(1510, GameData.getAvatarExcelMap().get(1510));
    var excel = ResourceLoader.getGson().fromJson(row.get("avatar"), AvatarExcel.class);
    var config = new JsonObject();
    config.add("AbilityList", row.get("abilities"));
    assertTrue(ResourceLoader.getGson().fromJson(config, SkillAbilityInfo.class).parse(excel));
    GameData.getAvatarExcelMap().add(excel);
    var other = new GameAvatar(excel) { @Override public void save() {} };
    other.setOwner(session.getPlayer());
    // Use the actual scene allocator: actor.id + 1 collides with the next spawned NPC.
    var allocator = emu.lunarcore.game.scene.Scene.class.getDeclaredMethod("getNextEntityId");
    allocator.setAccessible(true);
    other.setEntityId((Integer) allocator.invoke(session.getPlayer().getScene()));
    var player = session.getPlayer();
    player.getAvatars().getAvatars().put(1510, other);
    player.getCurrentLineup().getAvatars().add(1510);
    player.getScene().getAvatars().put(1510, other);
    player.getScene().getAvatarEntityIds().add(other.getEntityId());
    assertTrue(other.isAlive(player.getCurrentLineup()));
    assertEquals(0, number(cost(72213), "retcode"));
    var response = request("SceneCastSkillCostMpCsReq", 72213, other.getEntityId(), 1, new int[] {});
    assertNotEquals(0, number(response, "retcode"));
    assertEquals(4, mp());
    var target = base.monster("[]");
    assertNotEquals(other.getEntityId(), target.getEntityId(), "Distinct living actors and NPCs have distinct actual IDs");
    assertFalse(player.getScene().getAvatarEntityIds().contains(target.getEntityId()));
    assertEquals(0, number(cast(72213, 1, new int[] {1}, target), "retcode"));
    assertEquals(4, mp());
    assertArrayEquals(new int[] {1, 1, 0, 0}, observed);
  }

  @Test
  void aNewSessionDoesNotBorrowTheOldClientUseIdReceipt() throws Exception {
    controlledProjectileTechnique();
    assertEquals(0, number(cost(72212), "retcode"));
    session = new Session();
    // Player.setSession only sets an empty field. This explicitly represents a replaced
    // active connection; calling that setter again would leave the old connection bound.
    var ownerSession = emu.lunarcore.game.player.Player.class.getDeclaredField("session");
    ownerSession.setAccessible(true);
    ownerSession.set(session.getPlayer(), session);
    assertSame(session, session.getPlayer().getSession());
    assertEquals(0, number(cost(72212), "retcode"));
    assertEquals(3, mp(), "A new client process may restart its native UseID counter");
  }
}
