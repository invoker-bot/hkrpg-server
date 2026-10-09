package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original HP predicate evaluated explicitly; no global modifier listener is inferred. */
class MazeHpPredicateTest {
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  JsonObject source;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    try (var in = getClass().getResourceAsStream("/fixtures/maze-hp-predicate-4.6.json")) {
      assertNotNull(in);
      source =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonObject("task");
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.cleanup();
  }

  JsonObject predicate() {
    return source.getAsJsonObject("Predicate").deepCopy();
  }

  JsonObject compare(String type, JsonElement threshold) {
    var p = predicate();
    p.addProperty("CompareType", type);
    if (threshold == null) p.remove("CompareValue");
    else p.add("CompareValue", threshold);
    return p;
  }

  JsonObject branch(JsonObject predicate) {
    var t = new JsonObject();
    t.addProperty("$type", "RPG.GameCore.PredicateTaskList");
    t.add("Predicate", predicate);
    var yes = new JsonArray();
    yes.add(fixture.add(130401, -1));
    t.add("SuccessTaskList", yes);
    var no = new JsonArray();
    no.add(fixture.add(130404, -1));
    t.add("FailedTaskList", no);
    return t;
  }

  void selected(GameAvatar caster, boolean yes) {
    assertEquals(yes, caster.getBuffs().containsKey(130401));
    assertEquals(!yes, caster.getBuffs().containsKey(130404));
  }

  void empty(GameAvatar caster) {
    assertFalse(caster.getBuffs().containsKey(130401));
    assertFalse(caster.getBuffs().containsKey(130404));
  }

  @ParameterizedTest
  @CsvSource({"0,false", "1,true", "5000,true", "10000,true"})
  void originalCasterHpPredicateReadsTheActualPartyBar(int hp, boolean positive) throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.setCurrentHp(owner.getCurrentLineup(), hp);
    var condition = MazeBuffConditions.compileBranch(predicate());
    assertTrue(condition.isPresent());
    assertEquals(java.util.Optional.of(positive), condition.orElseThrow().apply(caster));
    assertEquals(hp, caster.getCurrentHp(owner.getCurrentLineup()));
  }

  @ParameterizedTest
  @CsvSource({
    "Greater,0.5,false",
    "Greater,0.49,true",
    "GreaterEqual,0.5,true",
    "GreaterEqual,0.51,false",
    "NotEqual,0.5,false",
    "NotEqual,0.51,true",
    "Equal,0.5,true",
    "Equal,0.51,false",
    "LessEqual,0.5,true",
    "LessEqual,0.49,false",
    "Less,0.5,false",
    "Less,0.51,true"
  })
  void comparisonBranchesUseTheNormalizedCurrentCasterRatio(
      String operation, float threshold, boolean yes) throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.setCurrentHp(owner.getCurrentLineup(), 5000);
    fixture
        .parse(
            fixture.excel(),
            "HpFixture_MazeSkill",
            branch(compare(operation, new JsonPrimitive(threshold))))
        .onCast(caster, MotionInfo.newInstance());
    selected(caster, yes);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void inverseAppliesOnlyToAnAvailableHpValue(int hp) throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.setCurrentHp(owner.getCurrentLineup(), hp);
    var p = predicate();
    p.addProperty("Inverse", true);
    fixture
        .parse(fixture.excel(), "HpFixture_MazeSkill", branch(p))
        .onCast(caster, MotionInfo.newInstance());
    selected(caster, hp == 0);
  }

  @Test
  void reusedConditionRechecksLifeAfterDamageAndRecovery() throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    var compiled = MazeBuffConditions.compileBranch(predicate());
    assertTrue(compiled.isPresent());
    var condition = compiled.orElseThrow();
    for (int hp : new int[] {10000, 0, 2500, 0}) {
      caster.setCurrentHp(owner.getCurrentLineup(), hp);
      assertEquals(java.util.Optional.of(hp > 0), condition.apply(caster));
    }
  }

  @ParameterizedTest
  @CsvSource({"0,10000", "5000,0"})
  void storyPartyHpCannotBorrowTheOrdinaryFormalActorsLife(int storyHp, int formalHp)
      throws Exception {
    var owner = fixture.owner(true);
    var caster = owner.getCurrentLeaderAvatar();
    var formal = owner.getAvatars().getAvatarById(StoryLineLifecycleTest.ACTOR);
    formal.setCurrentHp(owner.getLineupManager().getLineups()[0], formalHp);
    caster.setCurrentHp(owner.getCurrentLineup(), storyHp);
    var condition = MazeBuffConditions.compileBranch(predicate());
    assertTrue(condition.isPresent());
    assertEquals(java.util.Optional.of(storyHp > 0), condition.orElseThrow().apply(caster));
    assertEquals(formalHp, formal.getCurrentHp(owner.getLineupManager().getLineups()[0]));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void castHitAndAttackCallbacksSelectFromTheCurrentHp(int callback) throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.setCurrentHp(owner.getCurrentLineup(), 5000);
    var skill =
        fixture.parse(
            fixture.excel(),
            callback == 0 ? "HpFixture_MazeSkill" : "HpFixture_NormalAtk01",
            branch(predicate()));
    if (callback == 0) skill.onCast(caster, MotionInfo.newInstance());
    else if (callback == 1) skill.onCastHit(caster, List.of());
    else skill.onAttack(caster, List.of());
    selected(caster, true);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing-owner", "detached", "removed-member", "negative-hp", "excess-hp"})
  void unavailableIdentityAndMalformedSavedLifeSelectNeitherBranchEvenWithInverse(String kind)
      throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    switch (kind) {
      case "missing-owner" -> caster = new GameAvatar(caster.getExcel());
      case "detached" -> {
        var copy = new GameAvatar(caster.getExcel());
        copy.setOwner(owner);
        caster = copy;
      }
      case "removed-member" -> owner.getCurrentLineup().getAvatars().clear();
      default -> {
        var field = GameAvatar.class.getDeclaredField("currentHp");
        field.setAccessible(true);
        field.setInt(caster, kind.equals("negative-hp") ? -1 : 10001);
      }
    }
    var p = predicate();
    p.addProperty("Inverse", true);
    fixture
        .parse(fixture.excel(), "HpFixture_MazeSkill", branch(p))
        .onCast(caster, MotionInfo.newInstance());
    empty(caster);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "other-target",
        "extra-selector",
        "missing-target",
        "unknown-compare",
        "missing-compare",
        "string-value",
        "dynamic-value",
        "null-value",
        "nonfinite-value",
        "unknown-field"
      })
  void unsupportedSourceShapesDoNotExecuteEitherBranch(String kind) throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    var p = predicate();
    switch (kind) {
      case "other-target" -> p.getAsJsonObject("TargetType").addProperty("Alias", "AdvLineup");
      case "extra-selector" -> p.getAsJsonObject("TargetType").addProperty("Index", 0);
      case "missing-target" -> p.remove("TargetType");
      case "unknown-compare" -> p.addProperty("CompareType", "Unknown");
      case "missing-compare" -> p.remove("CompareType");
      case "string-value" -> p.addProperty("CompareValue", "0");
      case "dynamic-value" -> p.add("CompareValue", JsonParser.parseString("{\"IsDynamic\":true}"));
      case "null-value" -> p.add("CompareValue", JsonNull.INSTANCE);
      case "nonfinite-value" -> p.add("CompareValue", JsonParser.parseString("1e1000"));
      case "unknown-field" -> p.addProperty("UnsupportedRule", true);
    }
    fixture
        .parse(fixture.excel(), "HpFixture_MazeSkill", branch(p))
        .onCast(caster, MotionInfo.newInstance());
    empty(caster);
  }

  @Test
  void mixedHpMpAndBuffTreeRequiresAllValuesAndUsesOnePartySnapshot() throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.setCurrentHp(owner.getCurrentLineup(), 5000);
    caster.addBuff(150601, -1);
    owner.getCurrentLineup().setMp(2);
    var p = new JsonObject();
    p.addProperty("$type", "RPG.GameCore.ByAnd");
    var list = new JsonArray();
    list.add(compare("Equal", new JsonPrimitive(.5)));
    list.add(
        JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.AdventureByCompareMP\",\"CompareType\":\"Equal\",\"CompareValue\":2}"));
    list.add(
        JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.ByCompareCarryMazebuff\",\"BuffID\":150601,\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}}"));
    p.add("PredicateList", list);
    var condition = MazeBuffConditions.compileBranch(p);
    assertTrue(condition.isPresent());
    assertEquals(java.util.Optional.of(true), condition.orElseThrow().apply(caster));
    owner.getCurrentLineup().setMp(1);
    assertEquals(java.util.Optional.of(false), condition.orElseThrow().apply(caster));
    owner.getCurrentLineup().setMp(-1);
    assertEquals(java.util.Optional.empty(), condition.orElseThrow().apply(caster));
  }

  @Test
  void fractionalComparisonUsesFloatRatherThanIntegerOrDoubleThreshold() throws Exception {
    var owner = fixture.owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    caster.setCurrentHp(owner.getCurrentLineup(), 5000);
    var condition =
        MazeBuffConditions.compileBranch(compare("Equal", new JsonPrimitive(.500000003)));
    assertTrue(condition.isPresent());
    assertEquals(java.util.Optional.of(true), condition.orElseThrow().apply(caster));
  }

  @ParameterizedTest
  @CsvSource({"false,0", "false,5000", "true,0", "true,5000"})
  void actualPlayerAndAvatarMorphiaReadsRestoreFormalAndIndependentStoryLifeTwice(
      boolean story, int hp) throws Exception {
    var owner = fixture.owner(story);
    var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(owner, 199972);
    var formal = owner.getAvatars().getAvatarById(StoryLineLifecycleTest.ACTOR);
    formal.setOwner(owner);
    formal.setCurrentHp(owner.getLineupManager().getLineups()[0], story ? 10000 - hp : hp);
    owner.getCurrentLeaderAvatar().setCurrentHp(owner.getCurrentLineup(), hp);
    var condition = MazeBuffConditions.compileBranch(predicate()).orElseThrow();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var options =
          dev.morphia.mapping.MapperOptions.builder()
              .storeEmpties(true)
              .storeNulls(false)
              .codecProvider(
                  org.bson.codecs.configuration.CodecRegistries.fromCodecs(
                      new emu.lunarcore.database.codecs.IntSetCodec(),
                      new emu.lunarcore.database.codecs.IntListCodec(),
                      new emu.lunarcore.database.codecs.Int2IntMapCodec()))
              .build();
      var store = dev.morphia.Morphia.createDatastore(client, "maze-hp-predicate", options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class,
              GameAvatar.class,
              emu.lunarcore.game.avatar.AvatarData.class);
      var document = new emu.lunarcore.game.player.Player();
      uid.setInt(document, owner.getUid());
      var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(document, owner.getGameplay());
      store.save(document);
      store.save(formal);
      var playerRows = store.getDatabase().getCollection("players");
      var avatarRows = store.getDatabase().getCollection("avatars");
      String playerBefore = java.util.Objects.requireNonNull(playerRows.find().first()).toJson();
      String avatarBefore = java.util.Objects.requireNonNull(avatarRows.find().first()).toJson();
      for (int i = 0; i < 2; i++) {
        var restoredPlayer =
            java.util.Objects.requireNonNull(
                store.find(emu.lunarcore.game.player.Player.class).first());
        var restoredAvatar = java.util.Objects.requireNonNull(store.find(GameAvatar.class).first());
        assertEquals(owner.getUid(), restoredPlayer.getUid());
        assertEquals(owner.getUid(), restoredAvatar.getOwnerUid());
        restoredAvatar.setOwner(owner);
        owner.getAvatars().getAvatars().put(restoredAvatar.getAvatarId(), restoredAvatar);
        state.set(owner, restoredPlayer.getGameplay());
        assertEquals(
            java.util.Optional.of(hp > 0), condition.apply(owner.getCurrentLeaderAvatar()));
        assertEquals(hp, owner.getCurrentLeaderAvatar().getCurrentHp(owner.getCurrentLineup()));
        assertEquals(
            playerBefore, java.util.Objects.requireNonNull(playerRows.find().first()).toJson());
        assertEquals(
            avatarBefore, java.util.Objects.requireNonNull(avatarRows.find().first()).toJson());
      }
    } finally {
      server.shutdownNow();
    }
  }
}
