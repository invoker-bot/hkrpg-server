package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.SummonUnitInfo;
import emu.lunarcore.data.excel.SummonUnitExcel;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.scene.entity.EntitySummonUnit;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.proto.VectorOuterClass.Vector;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Source leaves with isolated execution; their containing conditions and variants are not
 * flattened.
 */
class MazeSummonActionTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  final Map<Integer, SummonUnitExcel> originals = new HashMap<>();
  JsonArray rows;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    try (var in = getClass().getResourceAsStream("/fixtures/summon-action-4.6.json")) {
      assertNotNull(in);
      rows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    for (var entry : originals.entrySet()) {
      if (entry.getValue() == null)
        GameData.getSummonUnitExcelMap().keySet().remove(entry.getKey().intValue());
      else GameData.getSummonUnitExcelMap().add(entry.getValue());
    }
    fixture.cleanup();
  }

  SummonUnitExcel excel(int index) {
    var row = rows.get(index).getAsJsonObject();
    var excel = fixture.gson.fromJson(row.get("row"), SummonUnitExcel.class);
    var config = fixture.gson.fromJson(row.get("config"), SummonUnitInfo.class);
    config.buildMazeSkillActions();
    excel.setInfo(config);
    return excel;
  }

  void install(int id, SummonUnitExcel excel) {
    if (!originals.containsKey(id)) originals.put(id, GameData.getSummonUnitExcelMap().get(id));
    if (excel == null) GameData.getSummonUnitExcelMap().keySet().remove(id);
    else {
      if (excel.getId() != id) {
        var raw = fixture.gson.toJsonTree(excel).getAsJsonObject();
        raw.addProperty("ID", id);
        var clone = fixture.gson.fromJson(raw, SummonUnitExcel.class);
        clone.setInfo(excel.getInfo());
        excel = clone;
      }
      GameData.getSummonUnitExcelMap().add(excel);
    }
  }

  JsonObject task(int index) {
    return rows.get(index).getAsJsonObject().getAsJsonObject("task").deepCopy();
  }

  MazeSkill parse(JsonObject... tasks) {
    return fixture.parse(fixture.excel(), "LeafFixture_MazeSkill", tasks);
  }

  MotionInfo motion() {
    return MotionInfo.newInstance()
        .setPos(Vector.newInstance().setX(1500).setY(3200).setZ(7800))
        .setRot(Vector.newInstance().setY(90000));
  }

  EntitySummonUnit current(MazeCasterBuffRemovalTest.Capture owner) {
    return owner.getScene().getPlayerSummon();
  }

  JsonObject destroy(int id) {
    var task = rows.get(8).getAsJsonObject().getAsJsonObject("task").deepCopy();
    task.getAsJsonObject("SummonUnit").addProperty("SummonUnitID", id);
    return task;
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 4, 5, 6, 7})
  void unchangedServerCreationLeafUsesItsDeclaredUnitAndLiteralDuration(int index)
      throws Exception {
    var owner = fixture.owner(false);
    var excel = excel(index);
    assertFalse(excel.isIsClient());
    install(excel.getId(), excel);
    var skill = parse(task(index));
    skill.onCast(owner.getCurrentLeaderAvatar(), motion());
    assertNotNull(current(owner), "Declared summon task was ignored");
    assertEquals(excel.getId(), current(owner).getExcel().getId());
    assertSame(owner.getCurrentLeaderAvatar(), current(owner).getCaster());
    double seconds =
        task(index)
            .getAsJsonObject("Duration")
            .getAsJsonObject("FixedValue")
            .get("Value")
            .getAsDouble();
    assertEquals((int) (seconds * 1000), current(owner).getDuration());
    assertEquals(
        current(owner).getCreateTime() + (long) (seconds * 1000), current(owner).getExpiry());
    assertFalse(skill.isTriggerBattle());
  }

  @Test
  void unchangedClientOnlyDefinitionDoesNotCreateAServerEntity() throws Exception {
    var owner = fixture.owner(false);
    install(excel(2).getId(), excel(2));
    parse(task(2)).onCast(owner.getCurrentLeaderAvatar(), motion());
    assertNull(current(owner));
    assertTrue(owner.getScene().getEntities(EntitySummonUnit.class).isEmpty());
  }

  @Test
  void aSkillWithoutACreateTaskCannotFabricateAUnitFromItsAvatarId() throws Exception {
    var owner = fixture.owner(false);
    install(fixture.excel().getAvatarID() * 10 + 1, excel(0));
    parse(fixture.remove(121101)).onCast(owner.getCurrentLeaderAvatar(), motion());
    assertNull(current(owner), "Avatar-ID heuristic fabricated an undeclared summon");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anUnresolvedDurationDoesNotInventTwentySecondsOrReplaceAnExistingUnit(boolean missing)
      throws Exception {
    var owner = fixture.owner(false);
    var excel = excel(0);
    install(excel.getId(), excel);
    install(fixture.excel().getAvatarID() * 10 + 1, excel);
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), excel, new Position(), new Position(), -1);
    var existing = current(owner);
    var task = task(0);
    if (missing) task.remove("Duration");
    else
      task.add(
          "Duration", JsonParser.parseString("{\"IsDynamic\":true,\"PostfixExpr\":\"_Duration\"}"));
    owner.sent.clear();
    parse(task).onCast(owner.getCurrentLeaderAvatar(), motion());
    assertSame(
        existing,
        current(owner),
        "Unresolved source duration replaced a live unit with a guessed duration");
    assertTrue(owner.sent.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.5, 1.1, -1})
  void explicitFractionalAndPermanentTaskDurationsKeepSigned46MillisecondValues(double seconds)
      throws Exception {
    var owner = fixture.owner(false);
    install(excel(0).getId(), excel(0));
    var task = task(0);
    task.getAsJsonObject("Duration").getAsJsonObject("FixedValue").addProperty("Value", seconds);
    parse(task).onCast(owner.getCurrentLeaderAvatar(), motion());
    assertNotNull(current(owner));
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var wire =
        bridge
            .clientSchema()
            .parse(
                "SceneSummonUnitInfo",
                bridge.translate(
                    "SceneSummonUnitInfo",
                    current(owner).toSceneEntityProto().getSummonUnit().toByteArray(),
                    true));
    assertEquals(
        seconds == -1 ? -1 : (int) (seconds * 1000),
        wire.getField(wire.getDescriptorForType().findFieldByName("life_time_ms")));
    assertEquals(
        seconds == -1 ? Long.MAX_VALUE : current(owner).getCreateTime() + (long) (seconds * 1000),
        current(owner).getExpiry());
  }

  @Test
  void sourceDestroySelectorRemovesOnlyTheDeclaredUnitOfItsCaster() throws Exception {
    var owner = fixture.owner(false);
    var lifecycle = new SummonUnitLifecycleTest();
    lifecycle.fixture.root = root;
    lifecycle.rows =
        JsonParser.parseReader(
                new InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/summon-unit-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonArray();
    var source = lifecycle.excel(1);
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), source, new Position(), new Position(), -1);
    var old = current(owner);
    int entity = old.getEntityId();
    owner.sent.clear();
    parse(task(8)).onCast(owner.getCurrentLeaderAvatar(), motion());
    assertNull(current(owner), "Actual source destroy action was ignored");
    assertNull(owner.getScene().getEntityById(entity));
    assertEquals(0, old.getEntityId());
    assertEquals(1, owner.sent.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrongId", "otherCaster", "unsupportedSelector"})
  void destroyCannotDeleteAnUnrelatedActiveSummon(String kind) throws Exception {
    var owner = fixture.owner(false);
    owner
        .getScene()
        .summonUnit(owner.getCurrentLeaderAvatar(), excel(0), new Position(), new Position(), -1);
    var existing = current(owner);
    var task = destroy(excel(0).getId());
    var caster = owner.getCurrentLeaderAvatar();
    if (kind.equals("wrongId"))
      task.getAsJsonObject("SummonUnit").addProperty("SummonUnitID", 15061);
    if (kind.equals("otherCaster"))
      caster = new emu.lunarcore.game.avatar.GameAvatar(fixture.excel());
    if (kind.equals("unsupportedSelector"))
      task.getAsJsonObject("SummonUnit")
          .getAsJsonObject("Summoner")
          .addProperty("Alias", "AdvLineup");
    owner.sent.clear();
    parse(task).onCast(caster, motion());
    assertSame(existing, current(owner));
    assertTrue(owner.sent.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void creationAndDestructionFollowTheAuthoredOrder(boolean recreate) throws Exception {
    var owner = fixture.owner(false);
    install(excel(0).getId(), excel(0));
    var create = task(0);
    var destroy = destroy(excel(0).getId());
    var skill = recreate ? parse(create, destroy, create) : parse(create, destroy);
    skill.onCast(owner.getCurrentLeaderAvatar(), motion());
    if (recreate) {
      assertNotNull(current(owner));
      assertEquals(1, owner.getScene().getEntities(EntitySummonUnit.class).size());
      assertEquals(3, owner.sent.size());
    } else {
      assertNull(current(owner));
      assertTrue(owner.getScene().getEntities(EntitySummonUnit.class).isEmpty());
      assertEquals(2, owner.sent.size());
    }
  }

  @Test
  void unrelatedScalarDurationDoesNotBreakLoadingTheSummonTask() throws Exception {
    var owner = fixture.owner(false);
    install(excel(0).getId(), excel(0));
    var lookAt =
        JsonParser.parseString(
                "{\"$type\":\"RPG.GameCore.LookAt\",\"Duration\":0.015,\"TargetType\":\"CustomTarget\"}")
            .getAsJsonObject();
    var skill = assertDoesNotThrow(() -> parse(lookAt, task(0)));
    skill.onCast(owner.getCurrentLeaderAvatar(), motion());
    assertNotNull(current(owner));
  }
}
