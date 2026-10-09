package emu.lunarcore.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.SceneBuff;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MazeBuffLifetimeTest {
  final Gson gson = ResourceLoader.getGson();

  AvatarExcel excel() {
    return gson.fromJson("{\"AvatarID\":199972}", AvatarExcel.class);
  }

  EntityMonster monster() {
    var group = new GroupInfo();
    group.setId(199972);
    return new EntityMonster(
        null,
        new NpcMonsterExcel(),
        group,
        gson.fromJson("{\"ID\":19997201,\"Pos\":{},\"Rot\":{}}", MonsterInfo.class));
  }

  void finite(long before, long after, long expiry, long milliseconds) {
    assertTrue(
        expiry >= before + milliseconds && expiry <= after + milliseconds,
        "Expiration does not use the configured lifetime: " + (expiry - before));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void sourceCasterBuffUsesItsActualFixedLifetime(int index) throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/maze-buff-lifetime-4.6.json")) {
      assertNotNull(input);
      var row =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonArray()
              .get(index)
              .getAsJsonObject();
      var task = row.getAsJsonObject("task");
      var ability = new JsonObject();
      ability.addProperty("Name", row.get("abilityName").getAsString());
      var tasks = new JsonArray();
      tasks.add(task);
      ability.add("OnStart", tasks);
      var abilities = new JsonArray();
      abilities.add(ability);
      var config = new JsonObject();
      config.add("AbilityList", abilities);
      var excel = excel();
      assertTrue(gson.fromJson(config, SkillAbilityInfo.class).parse(excel));
      var avatar = new GameAvatar(excel);
      int id = task.get("ID").getAsInt();
      double seconds =
          task.getAsJsonObject("LifeTime").getAsJsonObject("FixedValue").get("Value").getAsDouble();
      long before = System.currentTimeMillis();
      excel.getMazeSkill().onCast(avatar, MotionInfo.newInstance());
      long after = System.currentTimeMillis();
      assertTrue(avatar.getBuffs().containsKey(id));
      if (seconds == -1) assertEquals(Long.MAX_VALUE, avatar.getBuffs().get(id));
      else finite(before, after, avatar.getBuffs().get(id), Math.round(seconds * 1000));
    }
  }

  @Test
  void permanentAvatarBuffDoesNotImmediatelyExpire() {
    var avatar = new GameAvatar(excel());
    avatar.addBuff(199972, -1);
    assertEquals(Long.MAX_VALUE, avatar.getBuffs().get(199972));
  }

  @Test
  void permanentSceneBuffKeepsTheLifetimeSentinel() {
    var buff = new SceneBuff(199972, 199972, -1);
    assertEquals(Long.MAX_VALUE, buff.getExpiry());
    assertEquals(-1f, buff.toProto().getLifeTime());
    assertFalse(buff.isExpired(System.currentTimeMillis() + 60_000));
  }

  @Test
  void permanentMonsterBuffDoesNotImmediatelyExpire() {
    var buff = monster().addBuff(199972, 199972, -1);
    assertEquals(Long.MAX_VALUE, buff.getExpiry());
    assertFalse(buff.isExpired(System.currentTimeMillis() + 60_000));
  }

  @ParameterizedTest
  @ValueSource(ints = {20, Integer.MAX_VALUE})
  void integerAvatarDurationDoesNotOverflowBeforeConvertingToMilliseconds(int seconds) {
    var avatar = new GameAvatar(excel());
    long before = System.currentTimeMillis();
    avatar.addBuff(199972, seconds);
    long after = System.currentTimeMillis();
    finite(before, after, avatar.getBuffs().get(199972), (long) seconds * 1000);
  }

  @ParameterizedTest
  @ValueSource(ints = {5, Integer.MAX_VALUE})
  void integerSceneDurationDoesNotOverflowBeforeConvertingToMilliseconds(int seconds) {
    long before = System.currentTimeMillis();
    var buff = new SceneBuff(199972, 199972, seconds);
    finite(before, System.currentTimeMillis(), buff.getExpiry(), (long) seconds * 1000);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void onlyOmittedDurationRetainsTheLegacyDefaultWhileUnresolvedDynamicIsSkipped(boolean dynamic) {
    var config =
        "{\"AbilityList\":[{\"Name\":\"ExplicitFixture_MazeSkill\",\"OnStart\":[{\"$type\":\"RPG.GameCore.AddMazeBuff\",\"ID\":199972"
            + (dynamic
                ? ",\"LifeTime\":{\"IsDynamic\":true,\"PostfixExpr\":{\"OpCodes\":\"AQAR\",\"DynamicHashes\":[692437857]}}"
                : "")
            + "}]}]}";
    var excel = excel();
    assertTrue(gson.fromJson(config, SkillAbilityInfo.class).parse(excel));
    var avatar = new GameAvatar(excel());
    long before = System.currentTimeMillis();
    excel.getMazeSkill().onCast(avatar, MotionInfo.newInstance());
    if (dynamic) assertFalse(avatar.getBuffs().containsKey(199972));
    else finite(before, System.currentTimeMillis(), avatar.getBuffs().get(199972), 20_000);
  }

  @ParameterizedTest
  @CsvSource({"1.1,1.1", "30,30", "5,5", "-1,-1"})
  void nativeBuffLifetimeUsesSecondsWhileItsCreationTimeKeepsMilliseconds(
      double seconds, float expected) throws Exception {
    var buff = new SceneBuff(199972, 199972, seconds);
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var nativeBuff =
        bridge
            .clientSchema()
            .parse("BuffInfo", bridge.translate("BuffInfo", buff.toProto().toByteArray(), true));
    assertEquals(
        expected,
        nativeBuff.getField(nativeBuff.getDescriptorForType().findFieldByName("life_time")));
    assertEquals(
        emu.lunarcore.LunarCore.convertToServerTime(buff.getCreateTime()),
        nativeBuff.getField(nativeBuff.getDescriptorForType().findFieldByName("add_time_ms")));
    assertTrue(nativeBuff.getUnknownFields().asMap().isEmpty());
    assertEquals(
        seconds == -1 ? -1f : (float) (seconds * 1000),
        buff.getDuration(),
        "Existing internal duration getter retains milliseconds");
  }

  @Test
  void legacyBuffLifetimeAlsoUsesSecondsAndPreservesTheIntegerConstructor() {
    var buff = new SceneBuff(199972, 199972, 5);
    assertEquals(5f, buff.toProto().getLifeTime());
    assertEquals(5000f, buff.getDuration());
    assertEquals(buff.getCreateTime() + 5000, buff.getExpiry());
  }
}
