package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Exact source names, isolated action markers, and native dispatch stopped before battle. */
class MazeSkillBindingTest {
  static final String ATTACK = "LocalPlayer_Feixiao_00_NormalAtk01";
  static final String TECHNIQUE = "LocalPlayer_Acheron_MazeSkill";
  static final int BUFF = 199978;
  @TempDir Path root;
  final Gson gson = ResourceLoader.getGson();
  TrialMazeSkillLockTest fixture;

  @AfterEach
  void cleanup() throws Exception {
    if (fixture != null) fixture.cleanup();
  }

  JsonObject ability(String name, String marker) {
    var a = new JsonObject();
    a.addProperty("Name", name);
    a.add(
        "OnStart",
        JsonParser.parseString(
            "[{\"$type\":\"RPG.GameCore.AddAdventureModifier\",\"ModifierName\":\""
                + marker
                + "\"}]"));
    return a;
  }

  void parse(AvatarExcel excel, JsonObject... abilities) {
    var config = new JsonObject();
    var list = new JsonArray();
    for (var a : abilities) list.add(a);
    config.add("AbilityList", list);
    assertTrue(gson.fromJson(config, SkillAbilityInfo.class).parse(excel));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void sourceAuxiliaryOrSecondAttackCannotOverwriteThePrimaryAttack(int index) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/normal-attack-binding-4.6.json")) {
      assertNotNull(in);
      var row =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()
              .get(index)
              .getAsJsonObject();
      var names = row.getAsJsonArray("abilities");
      var excel = gson.fromJson("{\"AvatarID\":199978}", AvatarExcel.class);
      parse(
          excel,
          ability(names.get(0).getAsJsonObject().get("name").getAsString(), "PrimaryMarker"),
          ability(names.get(1).getAsJsonObject().get("name").getAsString(), "SecondaryMarker"));
      assertTrue(
          excel.getMazeAttack().hasAdventureModifier("PrimaryMarker"),
          "Default normal attack was overwritten by a helper or second attack");
      assertFalse(excel.getMazeAttack().hasAdventureModifier("SecondaryMarker"));
    }
  }

  emu.lunarcore.game.player.Player setup(boolean locked) throws Exception {
    fixture = new TrialMazeSkillLockTest();
    fixture.root = root;
    fixture.setup();
    var excel = GameData.getAvatarExcelMap().get(StoryLineLifecycleTest.ACTOR);
    var tech = ability(TECHNIQUE, "TechniqueMarker");
    tech.add(
        "OnStart",
        JsonParser.parseString(
            "[{\"$type\":\"RPG.GameCore.AddMazeBuff\",\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"},\"ID\":"
                + BUFF
                + ",\"LifeTime\":{\"FixedValue\":{\"Value\":-1}}}]"));
    parse(excel, ability(ATTACK, "AttackMarker"), tech);
    return fixture.started(locked);
  }

  TrialMazeSkillLockTest.Capture request(
      emu.lunarcore.game.player.Player owner, int index, String name) throws Exception {
    var capture = new TrialMazeSkillLockTest.Capture(owner);
    int caster = owner.getCurrentLeaderAvatar().getEntityId();
    var req =
        DynamicMessage.newBuilder(
            fixture.fixture.bridge.clientSchema().message("SceneCastSkillCsReq"));
    req.setField(req.getDescriptorForType().findFieldByName("cast_entity_id"), caster);
    req.setField(req.getDescriptorForType().findFieldByName("attacked_by_entity_id"), caster);
    req.setField(req.getDescriptorForType().findFieldByName("skill_index"), index);
    var nameField = req.getDescriptorForType().findFieldByName("maze_ability_str");
    assertEquals(15, nameField.getNumber());
    req.setField(nameField, name);
    try {
      assertTrue(
          VersionedPacketHandler.handle(
              capture,
              fixture.fixture.bridge.clientCommand("SceneCastSkillCsReq"),
              req.build().toByteArray()));
    } catch (TrialMazeSkillLockTest.AttackBoundary expected) {
      assertTrue(capture.attackAttempted);
    }
    return capture;
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void exactDeclaredPrimaryAbilityNameReachesTheSelectedSkill(int index) throws Exception {
    var owner = setup(false);
    var capture = request(owner, index, index == 0 ? ATTACK : TECHNIQUE);
    assertTrue(capture.attackAttempted, "Known primary ability string must not be rejected");
    assertEquals(index == 1 ? 4 : 5, owner.getCurrentLineup().getMp());
    assertEquals(index == 1, owner.getCurrentLeaderAvatar().getBuffs().containsKey(BUFF));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void omittedAbilityNamePreservesExistingDispatch(int index) throws Exception {
    var owner = setup(false);
    assertTrue(request(owner, index, "").attackAttempted);
    assertEquals(index == 1 ? 4 : 5, owner.getCurrentLineup().getMp());
  }

  @ParameterizedTest
  @CsvSource({
    "0,LocalPlayer_Acheron_MazeSkill",
    "1,Unknown_MazeSkill",
    "0,LocalPlayer_Feixiao_00_NormalAtk01_RemoveMazebuff"
  })
  void mismatchedUnknownOrUnimplementedVariantRejectsBeforeCostAndEffects(int index, String name)
      throws Exception {
    var owner = setup(false);
    var capture = request(owner, index, name);
    assertFalse(capture.attackAttempted);
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), StoryLineWireTest.number(capture.response, "retcode"));
    assertEquals(5, owner.getCurrentLineup().getMp());
    assertFalse(owner.getCurrentLeaderAvatar().getBuffs().containsKey(BUFF));
    assertTrue(capture.packets.isEmpty());
  }

  @Test
  void namedTechniqueKeepsTheFrozenTrialLock() throws Exception {
    var owner = setup(true);
    var capture = request(owner, 1, TECHNIQUE);
    assertFalse(capture.attackAttempted);
    assertEquals(5, owner.getCurrentLineup().getMp());
    assertFalse(owner.getCurrentLeaderAvatar().getBuffs().containsKey(BUFF));
  }
}
