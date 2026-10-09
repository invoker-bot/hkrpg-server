package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.util.Position;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Task leaves are executed explicitly; source conditions and ability variants are not inferred. */
class MazeCasterBuffRemovalTest {
  static final int BUFF = 122001;
  final Gson gson = ResourceLoader.getGson();
  @TempDir Path root;
  StoryLineLifecycleTest sceneFixture;

  @AfterEach
  void cleanup() throws Exception {
    if (sceneFixture != null) sceneFixture.restore();
  }

  AvatarExcel excel() {
    return gson.fromJson("{\"AvatarID\":199975}", AvatarExcel.class);
  }

  JsonObject remove(int id) {
    return JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.RemoveMazeBuff\",\"ID\":"
                + id
                + ",\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}}")
        .getAsJsonObject();
  }

  JsonObject add(int id, int seconds) {
    return JsonParser.parseString(
            "{\"$type\":\"RPG.GameCore.AddMazeBuff\",\"ID\":"
                + id
                + ",\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"},"
                + "\"LifeTime\":{\"FixedValue\":{\"Value\":"
                + seconds
                + "}}}")
        .getAsJsonObject();
  }

  MazeSkill parse(AvatarExcel excel, String name, JsonObject... tasks) {
    var config = new JsonObject();
    var ability = new JsonObject();
    ability.addProperty("Name", name);
    var start = new JsonArray();
    for (var task : tasks) start.add(task);
    ability.add("OnStart", start);
    var list = new JsonArray();
    list.add(ability);
    config.add("AbilityList", list);
    assertTrue(gson.fromJson(config, SkillAbilityInfo.class).parse(excel));
    return name.endsWith("MazeSkill") ? excel.getMazeSkill() : excel.getMazeAttack();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void unchangedSourceRemovalLeafClearsOnlyItsCasterAndItsBuff(int index) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/maze-caster-removal-4.6.json")) {
      assertNotNull(in);
      var row =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()
              .get(index)
              .getAsJsonObject();
      var task = row.getAsJsonObject("task");
      int id = task.get("ID").getAsInt();
      var excel = excel();
      var skill = parse(excel, row.get("name").getAsString(), task);
      var caster = new GameAvatar(excel);
      var other = new GameAvatar(excel());
      caster.addBuff(id, -1);
      caster.addBuff(id + 1, -1);
      other.addBuff(id, -1);
      skill.onAttack(caster, List.of(other));
      assertFalse(caster.getBuffs().containsKey(id), "Already active caster buff must be removed");
      assertEquals(Long.MAX_VALUE, caster.getBuffs().get(id + 1));
      assertEquals(Long.MAX_VALUE, other.getBuffs().get(id));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void sourceLookAtStringTargetDoesNotPreventLoadingOtherSkillActions(int index) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/task-string-target-4.6.json")) {
      assertNotNull(in);
      var task =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()
              .get(index)
              .getAsJsonObject()
              .getAsJsonObject("task");
      var excel = excel();
      var skill =
          assertDoesNotThrow(
              () -> parse(excel, "Fixture_MazeSkill", task, remove(BUFF)),
              "A LookAt string selector must not disable the whole maze ability");
      var caster = new GameAvatar(excel);
      caster.addBuff(BUFF, -1);
      skill.onCast(caster, MotionInfo.newInstance());
      assertFalse(caster.getBuffs().containsKey(BUFF));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitCasterRemovalExecutesInCastAndCastHitCallbacks(boolean hit) {
    var excel = excel();
    var skill = parse(excel, hit ? "Fixture_NormalAtk" : "Fixture_MazeSkill", remove(BUFF));
    var caster = new GameAvatar(excel);
    caster.addBuff(BUFF, -1);
    if (hit) skill.onCastHit(caster, List.of());
    else skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(BUFF));
  }

  @ParameterizedTest
  @ValueSource(strings = {"AdvLineup", "ModifierOwnerEntity", "MissingTarget"})
  void unsupportedRemovalTargetDoesNotDeleteAnEarlierCasterAddition(String alias) {
    var removal = remove(BUFF);
    if (alias.equals("MissingTarget")) removal.remove("TargetType");
    else removal.getAsJsonObject("TargetType").addProperty("Alias", alias);
    var excel = excel();
    var skill = parse(excel, "Fixture_MazeSkill", add(BUFF, 20), removal);
    var caster = new GameAvatar(excel);
    skill.onCast(caster, MotionInfo.newInstance());
    assertTrue(
        caster.getBuffs().containsKey(BUFF), "Unknown target must not rewrite known actions");
  }

  @Test
  void additionRemovalAndReadditionExecuteInTheirAuthoredOrder() {
    var excel = excel();
    var skill = parse(excel, "Fixture_MazeSkill", add(BUFF, 6), remove(BUFF), add(BUFF, 30));
    var calls = new ArrayList<Double>();
    var caster =
        new GameAvatar(excel) {
          @Override
          public void addBuff(int id, double seconds) {
            calls.add(seconds);
            super.addBuff(id, seconds);
          }
        };
    long before = System.currentTimeMillis();
    skill.onCast(caster, MotionInfo.newInstance());
    assertEquals(List.of(6d, 30d), calls, "Removal must run after the first addition");
    long expiry = caster.getBuffs().get(BUFF);
    assertTrue(expiry >= before + 30_000 && expiry <= System.currentTimeMillis() + 30_000);
  }

  static class Capture extends StoryLineLifecycleTest.Director {
    final List<BasePacket> sent = new ArrayList<>();

    @Override
    public void sendPacket(BasePacket packet) {
      sent.add(packet);
    }
  }

  Capture owner(boolean trial) throws Exception {
    sceneFixture = new StoryLineLifecycleTest();
    sceneFixture.root = root;
    sceneFixture.prepare();
    var owner = new Capture();
    int actor = StoryLineLifecycleTest.ACTOR;
    var avatar = new GameAvatar(GameData.getAvatarExcelMap().get(actor));
    avatar.setOwner(owner);
    owner.getAvatars().getAvatars().put(actor, avatar);
    owner.getCurrentLineup().getAvatars().add(actor);
    owner.getGameplay().getMainMissions().put(actor, new QuestState(actor));
    owner
        .getGameplay()
        .getSubMissions()
        .put(StoryLineLifecycleTest.START, new QuestState(StoryLineLifecycleTest.START));
    assertTrue(
        owner.loadScene(
            StoryLineLifecycleTest.PLANE,
            StoryLineLifecycleTest.FLOOR,
            StoryLineLifecycleTest.ENTRY,
            new Position(),
            new Position(),
            false));
    if (trial) assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, sceneFixture.enter(owner));
    owner.sent.clear();
    return owner;
  }

  DynamicMessage change(BasePacket packet) throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var bytes = Unpooled.wrappedBuffer(bridge.outboundFrames(packet.build()));
    try {
      var frame = PacketCodec.decodeNext(bytes);
      assertEquals(bridge.clientCommand("SyncEntityBuffChangeListScNotify"), frame.cmdId());
      var notify = bridge.clientSchema().parse("SyncEntityBuffChangeListScNotify", frame.body());
      var rows =
          (List<?>)
              notify.getField(
                  notify.getDescriptorForType().findFieldByName("entity_buff_change_list"));
      assertEquals(1, rows.size());
      return (DynamicMessage) rows.getFirst();
    } finally {
      bytes.release();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualFormalAndTrialSceneActorsNotifyRemovalOnce(boolean trial) throws Exception {
    var owner = owner(trial);
    int id = trial ? StoryLineLifecycleTest.SPECIAL : StoryLineLifecycleTest.ACTOR;
    var caster = owner.getAvatarById(id);
    assertSame(caster, owner.getScene().getAvatarByEntityId(caster.getEntityId()));
    var formal = owner.getAvatars().getAvatarById(StoryLineLifecycleTest.ACTOR);
    formal.addBuff(BUFF, -1);
    caster.addBuff(BUFF, -1);
    caster.addBuff(BUFF + 1, -1);
    var skill = parse(excel(), "Fixture_MazeSkill", remove(BUFF));
    skill.onCast(caster, MotionInfo.newInstance());
    skill.onCast(caster, MotionInfo.newInstance());
    assertFalse(caster.getBuffs().containsKey(BUFF));
    assertTrue(caster.getBuffs().containsKey(BUFF + 1));
    if (trial) assertTrue(formal.getBuffs().containsKey(BUFF));
    assertEquals(1, owner.sent.size(), "Repeat removal must not emit a second notification");
    var change = change(owner.sent.getFirst());
    assertEquals(
        caster.getEntityId(),
        change.getField(change.getDescriptorForType().findFieldByName("entity_id")));
    assertEquals(
        BUFF, change.getField(change.getDescriptorForType().findFieldByName("remove_buff_id")));
    assertFalse(change.hasField(change.getDescriptorForType().findFieldByName("buff_change_info")));
  }

  @Test
  void missingBuffDoesNotEmitRemovalNotification() throws Exception {
    var owner = owner(false);
    var caster = owner.getCurrentLeaderAvatar();
    parse(excel(), "Fixture_MazeSkill", remove(BUFF)).onCast(caster, MotionInfo.newInstance());
    assertTrue(owner.sent.isEmpty());
  }

  @Test
  void detachedAvatarCannotNotifyAnotherCurrentSceneActor() throws Exception {
    var owner = owner(false);
    var current = owner.getCurrentLeaderAvatar();
    current.addBuff(BUFF, -1);
    var detached = new GameAvatar(current.getExcel());
    detached.setOwner(owner);
    detached.setEntityId(current.getEntityId());
    detached.addBuff(BUFF, -1);
    parse(excel(), "Fixture_MazeSkill", remove(BUFF)).onCast(detached, MotionInfo.newInstance());
    assertFalse(detached.getBuffs().containsKey(BUFF));
    assertTrue(current.getBuffs().containsKey(BUFF));
    assertTrue(owner.sent.isEmpty());
  }
}
