package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import java.nio.file.Files;
import java.util.*;

/** Original Out Submit uses the source-proven no-projectile default [1,2] message, with zero targets. */
class MazeCastoriceOut46Test {
  static final String OUT = "LocalPlayer_Castorice_MazeSkill_Out";
  static final int OUT_USE = 91802;
  @TempDir Path root;
  final MazeCastoriceManualAbility46Test original = new MazeCastoriceManualAbility46Test();

  @BeforeEach void setup() throws Exception { original.root = root; original.setup(); }
  @AfterEach void cleanup() throws Exception { original.cleanup(); }

  DynamicMessage out(int use) throws Exception {
    return original.named(use, OUT, 1, new int[] {1, 2});
  }

  @Test void actualBaseThenOriginalSubmitOutClearsOnlyItsOwnSourceWithoutAnotherMpOrBattle() throws Exception {
    original.activate();
    assertEquals(4, original.casts.mp());
    int notices = original.notices();
    assertEquals(0, original.code(out(OUT_USE)));
    assertFalse(original.casts.base.actor.getBuffs().containsKey(140704));
    assertNull(original.casts.session.getPlayer().getBattle());
    assertEquals(4, original.casts.mp());
    assertEquals(notices, original.notices());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), original.code(original.named(
        OUT_USE + 1, MazeCastoriceManualAbility46Test.INPUT, 1, new int[] {1, 2})));
  }

  @Test void cancelledInputCanReachItsOriginalOutWithANewUseIdAndCannotBorrowItsOldRadius() throws Exception {
    original.activate();
    assertEquals(0, original.code(out(OUT_USE)));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), original.code(out(OUT_USE)));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), original.code(out(OUT_USE + 1)));
    assertEquals(4, original.casts.mp());
  }

  @Test void acceptedOldOutCannotBorrowANewActualBaseButANewOutCanClearIt() throws Exception {
    original.activate();
    assertEquals(0, original.code(out(OUT_USE)));
    assertEquals(0, original.code(original.named(MazeCastoriceManualAbility46Test.BASE_USE + 10,
        MazeCastoriceManualAbility46Test.BASE, 1, new int[] {0})));
    assertEquals(3, original.casts.mp());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), original.code(out(OUT_USE)));
    assertTrue(original.casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(0, original.code(out(OUT_USE + 1)));
    assertFalse(original.casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(3, original.casts.mp());
  }

  @Test void noActualBaseReleaseCannotAuthorizeOut() throws Exception {
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), original.code(out(OUT_USE)));
    assertEquals(5, original.casts.mp());
    assertEquals(0, original.notices());
  }

  static int[][] unprovenAttackTags() { return new int[][] {{}, {0}, {1}, {2}, {2, 1}, {1, 1}, {2, 2}, {99}}; }
  @ParameterizedTest @MethodSource("unprovenAttackTags")
  void outUsesOnlyTheProvenDirectSenderTagCombination(int[] tags) throws Exception {
    original.activate();
    long expiry = original.casts.base.actor.getBuffs().get(140704);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), original.code(original.named(OUT_USE, OUT, 1, tags)));
    assertEquals(expiry, original.casts.base.actor.getBuffs().get(140704));
    assertEquals(4, original.casts.mp());
  }

  @Test void originalBaseUseIdCannotMasqueradeAsItsNewOut() throws Exception {
    original.activate();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), original.code(out(MazeCastoriceManualAbility46Test.BASE_USE)));
    assertTrue(original.casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(4, original.casts.mp());
  }

  @Test void existingSourceLinkedInputStillReachesTheActualBattleAsAControl() throws Exception {
    original.activate();
    var target = original.casts.base.monster("[]");
    assertEquals(0, original.code(original.named(MazeCastoriceManualAbility46Test.CHILD_USE,
        MazeCastoriceManualAbility46Test.INPUT, 1, new int[] {1, 2}, target)));
    assertNotNull(original.casts.session.getPlayer().getBattle());
    assertEquals(4, original.casts.mp());
    assertFalse(original.casts.base.actor.getBuffs().containsKey(140704));
  }

  @Test void theWholeOriginalOutLinksTheAbortSubmitAndLeaderCondition() throws Exception {
    var source = original.source;
    assertEquals(OUT, MazeManualAbility46Test.at(source, "/AbilityList/3/OnAbort/0/AbilityName").getAsString());
    assertEquals("MazeSkill", MazeManualAbility46Test.at(source, "/AbilityList/3/OnAbort/0/SkillType").getAsString());
    assertEquals("Free", MazeManualAbility46Test.at(source, "/AbilityList/4/ManualTriggerType").getAsString());
    assertEquals(com.google.gson.JsonParser.parseString("{\"$type\":\"RPG.GameCore.ByCompareTarget\","
        + "\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"},"
        + "\"CompareType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"LatestAdvTeamLeader\"}}"),
        MazeManualAbility46Test.at(source, "/AbilityList/4/OnStart/2/Predicate"));
    assertEquals("RPG.GameCore.AdventureSubmitSkill", MazeManualAbility46Test.at(source,
        "/AbilityList/4/OnStart/2/SuccessTaskList/0/$type").getAsString());
    assertEquals(140704, MazeManualAbility46Test.at(source,
        "/AbilityList/4/OnStart/2/SuccessTaskList/1/ID").getAsInt());
  }

  GameAvatar addOtherOwnedAvatar() throws Exception {
    var player = original.casts.session.getPlayer();
    var excel = ResourceLoader.getGson().fromJson(original.row("AvatarConfig", "AvatarID", 1001), AvatarExcel.class);
    var other = new GameAvatar(excel) { @Override public void save() {} };
    other.setOwner(player);
    var allocator = emu.lunarcore.game.scene.Scene.class.getDeclaredMethod("getNextEntityId");
    allocator.setAccessible(true);
    other.setEntityId((Integer) allocator.invoke(player.getScene()));
    player.getAvatars().getAvatars().put(1001, other);
    player.getCurrentLineup().getAvatars().add(1001);
    player.getScene().getAvatars().put(1001, other);
    player.getScene().getAvatarEntityIds().add(other.getEntityId());
    assertTrue(other.isAlive(player.getCurrentLineup()));
    assertSame(other, player.getScene().getAvatarByEntityId(other.getEntityId()));
    return other;
  }

  @Test void theActualLeaderConditionRejectsThenAllowsTheSameUseWithoutLosingItsOrigin() throws Exception {
    original.activate();
    addOtherOwnedAvatar();
    var lineup = original.casts.session.getPlayer().getCurrentLineup();
    lineup.setLeader(1);
    long expiry = original.casts.base.actor.getBuffs().get(140704);
    assertEquals(5, original.code(out(OUT_USE)));
    assertEquals(expiry, original.casts.base.actor.getBuffs().get(140704));
    assertEquals(4, original.casts.mp());
    lineup.setLeader(0);
    assertEquals(0, original.code(out(OUT_USE)));
    assertFalse(original.casts.base.actor.getBuffs().containsKey(140704));
  }

  @Test void successfulOutRetainsTheCastersAttackBuffAndAnotherAvatarsRadius() throws Exception {
    original.activate();
    var other = addOtherOwnedAvatar();
    other.addBuff(140704, 20); // Explicit wrong-owner marker, no invented original grant.
    original.casts.base.actor.addBuff(140701, 20);
    long otherExpiry = other.getBuffs().get(140704);
    long attackExpiry = original.casts.base.actor.getBuffs().get(140701);
    assertEquals(0, original.code(out(OUT_USE)));
    assertEquals(otherExpiry, other.getBuffs().get(140704));
    assertEquals(attackExpiry, original.casts.base.actor.getBuffs().get(140701));
    assertFalse(original.casts.base.actor.getBuffs().containsKey(140704));
    assertNull(original.casts.session.getPlayer().getBattle());
  }

  @Test void sourceFreeOutStillSucceedsAtZeroMpWithoutAnMpNotification() throws Exception {
    original.activate();
    original.casts.session.getPlayer().getCurrentLineup().setMp(0); // Declared unit boundary only.
    int notices = original.notices();
    assertEquals(0, original.code(out(OUT_USE)));
    assertEquals(0, original.casts.mp());
    assertEquals(notices, original.notices());
  }

  @Test void originalSubmitOutCannotAttackItsTargetAndAnEmptyRetryStillClears() throws Exception {
    original.activate();
    var target = original.casts.base.monster("[]");
    long expiry = original.casts.base.actor.getBuffs().get(140704);
    assertEquals(5, original.code(original.named(OUT_USE, OUT, 1, new int[] {1, 2}, target)));
    assertEquals(expiry, original.casts.base.actor.getBuffs().get(140704));
    assertTrue(target.isAlive());
    assertNull(original.casts.session.getPlayer().getBattle());
    assertEquals(0, original.code(out(OUT_USE)));
    assertEquals(4, original.casts.mp());
  }

  DynamicMessage withAssist(boolean emptyInfo) throws Exception {
    var request = DynamicMessage.newBuilder(original.casts.base.protocol.clientSchema().message("SceneCastSkillCsReq"));
    var type = request.getDescriptorForType();
    request.setField(type.findFieldByName("cast_entity_id"), OUT_USE);
    request.setField(type.findFieldByName("attacked_by_entity_id"), original.casts.base.actor.getEntityId());
    request.setField(type.findFieldByName("maze_ability_str"), OUT);
    request.setField(type.findFieldByName("skill_index"), 1);
    for (int tag : new int[] {1, 2}) request.addRepeatedField(type.findFieldByName("skill_extra_tags"),
        type.findFieldByName("skill_extra_tags").getEnumType().findValueByNumber(tag));
    if (emptyInfo) {
      var field = type.findFieldByName("assist_monster_entity_info");
      request.addRepeatedField(field, DynamicMessage.getDefaultInstance(field.getMessageType()));
    } else request.addRepeatedField(type.findFieldByName("assist_monster_entity_id_list"),
        original.casts.base.monster("[]").getEntityId());
    return original.casts.dispatch("SceneCastSkillCsReq", request.build());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void nativeSubmitHasNoAssistsOrInventedEmptyAssistInfo(boolean emptyInfo) throws Exception {
    original.activate();
    long expiry = original.casts.base.actor.getBuffs().get(140704);
    assertEquals(5, original.code(withAssist(emptyInfo)));
    assertEquals(expiry, original.casts.base.actor.getBuffs().get(140704));
    assertNull(original.casts.session.getPlayer().getBattle());
    assertEquals(0, original.code(out(OUT_USE)));
  }

  @ParameterizedTest @ValueSource(ints = {0, -1, MazeCastoriceManualAbility46Test.BASE_USE})
  void outNeedsItsOwnNonzeroNonSentinelNonBaseUseId(int use) throws Exception {
    original.activate();
    long expiry = original.casts.base.actor.getBuffs().get(140704);
    assertEquals(5, original.code(out(use)));
    assertEquals(expiry, original.casts.base.actor.getBuffs().get(140704));
    assertEquals(0, original.code(out(OUT_USE)));
  }

  @Test void aWrongSlotCannotPoisonTheCorrectOutUseId() throws Exception {
    original.activate();
    assertEquals(5, original.code(original.named(OUT_USE, OUT, 0, new int[] {1, 2})));
    assertTrue(original.casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(0, original.code(out(OUT_USE)));
  }

  @ParameterizedTest @ValueSource(strings = {"expired", "refreshed", "removed", "unheld"})
  void anExpiredRefreshedRemovedOrUnheldRadiusCannotAuthorizeTheOut(String condition) throws Exception {
    original.activate();
    switch (condition) {
      case "expired" -> original.casts.base.actor.getBuffs().put(140704, System.currentTimeMillis() - 1);
      case "refreshed" -> original.casts.base.actor.getBuffs().put(140704,
          original.casts.base.actor.getBuffs().get(140704) + 5000);
      case "removed" -> original.casts.base.actor.getBuffs().remove(140704);
      case "unheld" -> original.casts.session.getPlayer().getCurrentLineup().getAvatars().clear();
    }
    assertEquals(5, original.code(out(OUT_USE)));
    assertEquals(4, original.casts.mp());
    assertNull(original.casts.session.getPlayer().getBattle());
  }

  @Test void aNewSessionCannotBorrowTheActualActorOrStillPresentRadius() throws Exception {
    original.activate();
    original.casts.session = original.casts.new Session();
    original.casts.session.getPlayer().setSession(original.casts.session);
    assertEquals(5, original.code(out(OUT_USE)));
    assertTrue(original.casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(4, original.casts.mp());
  }

  @Test void aNewSceneWithTheSameFloorAndActorCannotBorrowTheOutOrigin() throws Exception {
    original.activate();
    var player = original.casts.session.getPlayer();
    var old = player.getScene();
    var next = new emu.lunarcore.game.scene.Scene(player, old.getExcel(), old.getFloorId(), old.getDimensionId());
    next.getAvatars().put(1407, original.casts.base.actor);
    next.getAvatarEntityIds().add(original.casts.base.actor.getEntityId());
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("scene");
    field.setAccessible(true);
    field.set(player, next);
    assertSame(original.casts.base.actor, next.getAvatarByEntityId(original.casts.base.actor.getEntityId()));
    assertEquals(5, original.code(out(OUT_USE)));
    assertTrue(original.casts.base.actor.getBuffs().containsKey(140704));
    assertEquals(4, original.casts.mp());
  }

  @ParameterizedTest @ValueSource(strings = {
      MazeCastoriceManualAbility46Test.ABILITY,
      "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Castorice_00_Config.json",
      "ExcelOutput/AvatarConfig.json", "ExcelOutput/AdventurePlayer.json",
      "ExcelOutput/MazeSkill.json", "ExcelOutput/AvatarMazeBuff.json",
      "ExcelOutput/AvatarSkillConfig.json", "ExcelOutput/AvatarSkillConfigLD.json"})
  void aMissingOrChangedWholeOriginalCannotAuthorizeOutAndRestorationAllowsTheSameId(String path) throws Exception {
    original.activate();
    byte[] raw = Files.readAllBytes(root.resolve(path));
    long expiry = original.casts.base.actor.getBuffs().get(140704);
    Files.delete(root.resolve(path));
    assertEquals(5, original.code(out(OUT_USE)));
    assertEquals(expiry, original.casts.base.actor.getBuffs().get(140704));
    byte[] changed = Arrays.copyOf(raw, raw.length + 1);
    changed[changed.length - 1] = ' ';
    Files.write(root.resolve(path), changed);
    assertEquals(5, original.code(out(OUT_USE)));
    assertEquals(expiry, original.casts.base.actor.getBuffs().get(140704));
    Files.write(root.resolve(path), raw);
    assertEquals(0, original.code(out(OUT_USE)));
    assertEquals(4, original.casts.mp());
  }

  @Test void anActualCasterCleanupExceptionRetainsTheSourceForTheSameOutIdRetry() throws Exception {
    var player = original.casts.session.getPlayer();
    var before = original.casts.base.actor;
    boolean[] fail = {true};
    var actor = new GameAvatar(before.getExcel()) {
      @Override public void save() {}
      @Override public boolean removeBuff(int id) {
        if (id == 140704 && fail[0]) throw new IllegalStateException("Owned cleanup fault");
        return super.removeBuff(id);
      }
    };
    actor.setOwner(player);
    actor.setEntityId(before.getEntityId());
    player.getAvatars().getAvatars().put(1407, actor);
    player.getScene().getAvatars().put(1407, actor);
    original.casts.base.actor = actor;
    original.activate();
    long expiry = actor.getBuffs().get(140704);
    assertEquals(Retcode.FAIL.getVal(), original.code(out(OUT_USE)));
    assertEquals(expiry, actor.getBuffs().get(140704));
    assertEquals(4, original.casts.mp());
    fail[0] = false;
    assertEquals(0, original.code(out(OUT_USE)));
    assertFalse(actor.getBuffs().containsKey(140704));
    assertEquals(4, original.casts.mp());
  }
}
