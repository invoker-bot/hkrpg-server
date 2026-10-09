package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.proto.BattleBuffOuterClass.BattleBuff;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Actual source buff rows, isolated battle entry, and wire ownership; no playable encounter. */
class AvatarBattleBuffSnapshotTest {
  static final int BUFF = 121101, EXTRA = 19998501;
  @TempDir Path root;
  final SceneLineupBuffLifecycleTest fixture = new SceneLineupBuffLifecycleTest();
  List<MazeBuffExcel> oldBuffRows;
  JsonArray sourceRows;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    oldBuffRows = new ArrayList<>(GameData.getMazeBuffExcelMap().values());
    try (var in = getClass().getResourceAsStream("/fixtures/avatar-maze-buff-4.6.json")) {
      assertNotNull(in);
      sourceRows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    for (var value : sourceRows)
      GameData.getMazeBuffExcelMap()
          .add(
              fixture.fixture.gson.fromJson(
                  value.getAsJsonObject().get("row"), MazeBuffExcel.class));
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      fixture.cleanup();
    } finally {
      GameData.getMazeBuffExcelMap().values().clear();
      oldBuffRows.forEach(GameData.getMazeBuffExcelMap()::add);
    }
  }

  Battle battle(MazeCasterBuffRemovalTest.Capture owner, int stageId) {
    return new Battle(
        owner,
        owner.getCurrentLineup(),
        new BattleStage() {
          public int getId() {
            return stageId;
          }

          public StageType getStageType() {
            return StageType.Mainline;
          }

          public List<IntList> getMonsterWaves() {
            return List.of();
          }
        },
        false);
  }

  Battle battle(MazeCasterBuffRemovalTest.Capture owner) {
    return battle(owner, 199985);
  }

  List<BattleBuff> bindings(Battle battle, int id) {
    var rows = new ArrayList<BattleBuff>();
    for (var row : battle.toProto().getBuffList()) if (row.getId() == id) rows.add(row);
    return rows;
  }

  void wireOwner(BattleBuff buff, int slot) throws Exception {
    assertEquals(slot, buff.getOwnerId());
    var targets = new ArrayList<Integer>();
    for (int i = 0; i < buff.getTargetIndexList().length(); i++)
      targets.add(buff.getTargetIndexList().get(i));
    assertEquals(List.of(slot), targets);
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var nativeBuff =
        bridge
            .clientSchema()
            .parse("BattleBuff", bridge.translate("BattleBuff", buff.toByteArray(), true));
    assertEquals(
        slot,
        nativeBuff.getField(nativeBuff.getDescriptorForType().findFieldByName("owner_index")));
    assertEquals(
        List.of(slot),
        nativeBuff.getField(
            nativeBuff.getDescriptorForType().findFieldByName("target_index_list")));
    assertTrue(nativeBuff.getUnknownFields().asMap().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3})
  void sourceDeclaredBattleBuffIsConsumedAtEntryAndNotReusedByTheNextBattle(int sourceIndex)
      throws Exception {
    int id =
        sourceRows.get(sourceIndex).getAsJsonObject().getAsJsonObject("row").get("ID").getAsInt();
    var owner = fixture.fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.addBuff(id, -1);
    var battle = battle(owner);
    owner.sent.clear();
    owner.setBattle(battle);
    assertFalse(caster.getBuffs().containsKey(id), "One-use source buff survived battle entry");
    assertTrue(battle.hasBuff(id));
    assertEquals(1, owner.sent.size());
    var first = bindings(battle, id);
    assertEquals(1, first.size());
    wireOwner(first.getFirst(), 0);
    assertArrayEquals(
        first.getFirst().toByteArray(), bindings(battle, id).getFirst().toByteArray());
    owner.setBattle(battle);
    assertEquals(1, owner.sent.size(), "Repeated entry consumed or notified again");
    owner.setBattle(null);
    var next = battle(owner);
    owner.setBattle(next);
    assertTrue(bindings(next, id).isEmpty(), "Consumed scene buff leaked into a second battle");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void consumptionNotifiesTheActualFormalOrTrialSceneActorOnce(boolean trial) throws Exception {
    var owner = fixture.fixture.owner(trial);
    var caster = owner.getCurrentLeaderAvatar();
    var formal = owner.getAvatars().getAvatarById(StoryLineLifecycleTest.ACTOR);
    if (trial) formal.addBuff(BUFF, -1);
    caster.addBuff(BUFF, -1);
    owner.sent.clear();
    var battle = battle(owner);
    owner.setBattle(battle);
    assertFalse(caster.getBuffs().containsKey(BUFF));
    if (trial) assertEquals(Long.MAX_VALUE, formal.getBuffs().get(BUFF));
    assertEquals(1, owner.sent.size());
    var change = fixture.fixture.change(owner.sent.getFirst());
    assertEquals(
        caster.getEntityId(),
        change.getField(change.getDescriptorForType().findFieldByName("entity_id")));
    assertEquals(
        BUFF, change.getField(change.getDescriptorForType().findFieldByName("remove_buff_id")));
    assertEquals(1, bindings(battle, BUFF).size());
    wireOwner(bindings(battle, BUFF).getFirst(), 0);
  }

  @Test
  void buffsAddedAfterEntryCannotAppearInTheFirstBattleQuery() throws Exception {
    var owner = fixture.fixture.owner(false);
    var battle = battle(owner);
    owner.setBattle(battle);
    owner.getCurrentLeaderAvatar().addBuff(EXTRA, -1);
    assertTrue(bindings(battle, EXTRA).isEmpty(), "A later scene effect changed a started battle");
    assertTrue(owner.getCurrentLeaderAvatar().getBuffs().containsKey(EXTRA));
  }

  @Test
  void removingAnUnconsumedSceneEffectBeforeTheFirstQueryDoesNotEraseTheEntrySnapshot()
      throws Exception {
    var owner = fixture.fixture.owner(false);
    var actor = owner.getCurrentLeaderAvatar();
    actor.addBuff(130801, -1);
    var battle = battle(owner);
    owner.setBattle(battle);
    assertTrue(actor.removeBuff(130801));
    assertEquals(1, bindings(battle, 130801).size(), "First query lost the captured scene effect");
  }

  @Test
  void laterQueriesCannotImportNewSceneBuffsOrChangeCapturedOwnerTargets() throws Exception {
    var owner = fixture.fixture.owner(false);
    owner.getCurrentLeaderAvatar().addBuff(BUFF, -1);
    var battle = battle(owner);
    owner.setBattle(battle);
    byte[] first = bindings(battle, BUFF).getFirst().toByteArray();
    owner.getCurrentLeaderAvatar().addBuff(EXTRA, -1);
    assertTrue(bindings(battle, EXTRA).isEmpty());
    assertArrayEquals(first, bindings(battle, BUFF).getFirst().toByteArray());
  }

  @Test
  void theSameBuffOnTwoSceneActorsKeepsBothIndependentOwners() throws Exception {
    var owner = fixture.fixture.owner(false);
    var first = owner.getCurrentLeaderAvatar();
    var second = fixture.second(owner);
    assertTrue(owner.getLineupManager().joinLineup(0, 1, second.getAvatarId()));
    first.addBuff(BUFF, -1);
    second.addBuff(BUFF, -1);
    var battle = battle(owner);
    owner.setBattle(battle);
    var buffs = bindings(battle, BUFF);
    assertEquals(2, buffs.size(), "An ID-only cache overwrote another caster's binding");
    wireOwner(buffs.get(0), 0);
    wireOwner(buffs.get(1), 1);
    assertFalse(first.getBuffs().containsKey(BUFF));
    assertFalse(second.getBuffs().containsKey(BUFF));
  }

  @Test
  void aSceneBindingCannotOverwriteAnExplicitBattleBuffWithTheSameId() throws Exception {
    var owner = fixture.fixture.owner(false);
    owner.getCurrentLeaderAvatar().addBuff(BUFF, -1);
    var battle = battle(owner);
    var explicit = battle.addBuff(BUFF, -1, 2);
    explicit.addDynamicValue("ExplicitFixture", 7);
    owner.setBattle(battle);
    var buffs = bindings(battle, BUFF);
    assertEquals(2, buffs.size(), "A scene buff erased an explicit battle binding");
    assertEquals(-1, buffs.getFirst().getOwnerId());
    assertEquals(2, buffs.getFirst().getWaveFlag());
    assertEquals(1, buffs.getFirst().getDynamicValues().length());
    wireOwner(buffs.get(1), 0);
    assertSame(explicit, battle.getBuffs().get(BUFF));
  }

  @Test
  void alreadyExpiredBuffIsNeitherBoundNorConsumedByAnUnrelatedBattle() throws Exception {
    var owner = fixture.fixture.owner(false);
    var actor = owner.getCurrentLeaderAvatar();
    actor.getBuffs().put(BUFF, 0);
    var battle = battle(owner);
    owner.sent.clear();
    owner.setBattle(battle);
    assertTrue(bindings(battle, BUFF).isEmpty());
    assertTrue(actor.getBuffs().containsKey(BUFF));
    assertTrue(owner.sent.isEmpty());
  }

  @Test
  void anInvalidStageCannotConsumeTheSceneActorsEffect() throws Exception {
    var owner = fixture.fixture.owner(false);
    var actor = owner.getCurrentLeaderAvatar();
    actor.addBuff(BUFF, -1);
    owner.sent.clear();
    owner.setBattle(battle(owner, 0));
    assertTrue(actor.getBuffs().containsKey(BUFF));
    assertTrue(owner.sent.isEmpty());
  }

  @Test
  void anUnstartedPreviewCannotConsumeOrStoreAnAvatarEffectInTheExplicitBuffMap() throws Exception {
    var owner = fixture.fixture.owner(false);
    var actor = owner.getCurrentLeaderAvatar();
    actor.addBuff(BUFF, -1);
    var preview = battle(owner);
    assertEquals(1, bindings(preview, BUFF).size());
    assertTrue(actor.getBuffs().containsKey(BUFF));
    assertFalse(preview.getBuffs().containsKey(BUFF), "Preview mutated the battle's buff cache");
  }

  @Test
  void aPreviewEffectRemovedBeforeEntryCannotRemainInTheActualBattle() throws Exception {
    var owner = fixture.fixture.owner(false);
    var actor = owner.getCurrentLeaderAvatar();
    actor.addBuff(BUFF, -1);
    var battle = battle(owner);
    assertEquals(1, bindings(battle, BUFF).size());
    assertTrue(actor.removeBuff(BUFF));
    owner.setBattle(battle);
    assertTrue(bindings(battle, BUFF).isEmpty(), "Preview cache leaked an obsolete avatar effect");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing-definition", "missing-use-type", "empty-key", "character-ability"})
  void unverifiedConsumptionDeclarationsKeepTheirSceneState(String variant) throws Exception {
    if (!variant.equals("missing-definition")) {
      var row = sourceRows.get(0).getAsJsonObject().getAsJsonObject("row").deepCopy();
      row.addProperty("ID", EXTRA);
      switch (variant) {
        case "missing-use-type" -> row.remove("UseType");
        case "empty-key" -> row.addProperty("InBattleBindingKey", "");
        case "character-ability" -> row.addProperty("InBattleBindingType", "CharacterAbility");
      }
      GameData.getMazeBuffExcelMap().add(fixture.fixture.gson.fromJson(row, MazeBuffExcel.class));
    }
    var owner = fixture.fixture.owner(false);
    var actor = owner.getCurrentLeaderAvatar();
    actor.addBuff(EXTRA, -1);
    var battle = battle(owner);
    owner.setBattle(battle);
    assertTrue(
        actor.getBuffs().containsKey(EXTRA), "Unknown lifecycle was consumed by a guessed rule");
    assertEquals(1, bindings(battle, EXTRA).size());
  }
}
