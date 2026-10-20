package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.SkillAbilityInfo;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.battle.BattleService;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.game.*;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.SessionState;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original Kafka, ability and recovery prop; request placement and unstarted server are fixtures.
 */
class BaseZoneRecoveryProp46Test {
  @TempDir Path root;
  final OpeningPlayer46Test opening = new OpeningPlayer46Test();
  final SceneEventBattle46Test server = new SceneEventBattle46Test();
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  PropExcel oldProp;
  Scene scene;
  EntityProp bottle;
  Session session;

  @BeforeEach
  void setup() throws Exception {
    opening.root = root;
    opening.prepare();
    server.temp = root;
    server.load();
    var service = GameServer.class.getDeclaredField("battleService");
    service.setAccessible(true);
    service.set(server.shell, new BattleService(null));
    var fixture = opening.base.fixture("base-zone-recovery-prop-4.6.json");
    assertEquals(
        "724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    var records = fixture.getAsJsonObject("records");
    var gson = ResourceLoader.getGson();
    oldProp = GameData.getPropExcelMap().get(100025);
    var prop =
        gson.fromJson(records.getAsJsonArray("ExcelOutput/MazeProp.json").get(0), PropExcel.class);
    prop.onLoad();
    assertTrue(prop.isRecoverMp());
    GameData.getPropExcelMap().add(prop);
    var group =
        gson.fromJson(
            records
                .getAsJsonArray(
                    "Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G91.json")
                .get(0),
            GroupInfo.class);
    group.setId(91);
    var ability =
        gson.fromJson(
            records
                .getAsJsonArray(
                    "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Kafka_00_Ability.json")
                .get(0),
            SkillAbilityInfo.class);
    ability.parse(GameData.getTrialActorExcelMap().get(7005));
    for (int id : List.of(100010102, 100010100, 100010104, 100010103)) opening.finishFixture(id);
    var player = opening.base.player;
    player.getMissionManager().refresh();
    scene = player.getScene();
    scene.syncLineup();
    assertEquals(7005, player.getCurrentLeaderAvatar().getActorId());
    assertEquals(
        "LocalPlayer_Kafka_NormalAtk01",
        player.getCurrentLeaderAvatar().getExcel().getMazeAttack().getAbilityName());
    // Isolate the source bottle after the first battle; scene group visibility has separate tests.
    bottle = new EntityProp(scene, prop, group, group.getPropList().getFirst());
    scene.addEntity(bottle);
    player.getCurrentLineup().setMp(0);
    player.packets.clear();
    session = new Session();
    player.setSession(session);
  }

  @AfterEach
  void cleanup() throws Exception {
    GameData.getPropExcelMap().keySet().remove(100025);
    if (oldProp != null) GameData.getPropExcelMap().add(oldProp);
    server.restore();
    opening.restore();
  }

  final class Session extends GameSession {
    final List<DynamicMessage> responses = new ArrayList<>();

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    @Override
    public emu.lunarcore.game.player.Player getPlayer() {
      return opening.base.player;
    }

    @Override
    public GameServer getServer() {
      return server.shell;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return protocol;
    }

    @Override
    public void send(BasePacket packet) {}

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      try {
        assertEquals("SceneCastSkillScRsp", name);
        responses.add(protocol.clientSchema().parse(name, bytes));
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }
  }

  DynamicMessage.Builder request(String location, int target) {
    var type = protocol.clientSchema().message("SceneCastSkillCsReq");
    var req = DynamicMessage.newBuilder(type);
    int avatar = opening.base.player.getCurrentLeaderAvatar().getEntityId();
    req.setField(type.findFieldByName("cast_entity_id"), 199990001);
    req.setField(type.findFieldByName("attacked_by_entity_id"), avatar);
    req.setField(type.findFieldByName("maze_ability_str"), "LocalPlayer_Kafka_NormalAtk01");
    if (location.equals("direct")) {
      req.setField(type.findFieldByName("cast_entity_id"), avatar);
      req.setField(type.findFieldByName("attacked_by_entity_id"), target);
    } else if (location.equals("assistInfo")) {
      var field = type.findFieldByName("assist_monster_entity_info");
      var info = DynamicMessage.newBuilder(field.getMessageType());
      info.addRepeatedField(info.getDescriptorForType().findFieldByName("entity_id_list"), target);
      req.addRepeatedField(field, info.build());
    } else {
      req.addRepeatedField(type.findFieldByName(location), target);
    }
    return req;
  }

  DynamicMessage dispatch(DynamicMessage.Builder request) throws Exception {
    int before = session.responses.size();
    assertTrue(
        VersionedPacketHandler.handle(
            session, protocol.clientCommand("SceneCastSkillCsReq"), request.build().toByteArray()));
    assertEquals(before + 1, session.responses.size());
    return session.responses.getLast();
  }

  int retcode(DynamicMessage response) {
    return (int) response.getField(response.getDescriptorForType().findFieldByName("retcode"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "hit_target_entity_id_list",
        "assist_monster_entity_id_list",
        "assistInfo",
        "direct"
      })
  void originalPurpleBottleBreaksAndRestoresTechniqueWithoutEnteringBattle(String location)
      throws Exception {
    int id = bottle.getEntityId();
    assertEquals(0, retcode(dispatch(request(location, id))));
    assertFalse(scene.getEntities().containsKey(id));
    assertEquals(2, opening.base.player.getCurrentLineup().getMp());
    assertNull(opening.base.player.getBattle());
    assertEquals(List.of(1001005), opening.base.player.getCurrentLineup().getAvatars());
    // A removed entity cannot generate a second recovery.
    assertNotEquals(0, retcode(dispatch(request(location, id))));
    assertEquals(2, opening.base.player.getCurrentLineup().getMp());
  }

  @Test
  void duplicateBottleAcrossAllTargetListsRecoversOnlyOnce() throws Exception {
    int id = bottle.getEntityId();
    var req = request("assist_monster_entity_id_list", id);
    req.addRepeatedField(
        req.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), id);
    req.mergeFrom(request("assistInfo", id).build());
    assertEquals(0, retcode(dispatch(req)));
    assertEquals(2, opening.base.player.getCurrentLineup().getMp());
    assertFalse(scene.getEntities().containsKey(id));
  }

  @Test
  void capturedEmptyAbilityAndCombinedCallbackShapeAlsoAcceptsTheRecoveryBottle() throws Exception {
    // Live UseID 138 carried an empty ability, [HIT, FINISH] and an assist prop.
    // Use the original purple recovery prop here; the native probe also covers observed prop
    // 100027.
    int id = bottle.getEntityId();
    var req = request("assist_monster_entity_id_list", id);
    var type = req.getDescriptorForType();
    req.setField(type.findFieldByName("cast_entity_id"), 138);
    req.clearField(type.findFieldByName("maze_ability_str"));
    var tags = type.findFieldByName("skill_extra_tags");
    req.addRepeatedField(
        tags, tags.getEnumType().findValueByName("SCENE_CAST_SKILL_PROJECTILE_HIT"));
    req.addRepeatedField(
        tags, tags.getEnumType().findValueByName("SCENE_CAST_SKILL_PROJECTILE_LIFETIME_FINISH"));
    assertEquals(0, retcode(dispatch(req)));
    assertFalse(scene.getEntities().containsKey(id));
    assertEquals(2, opening.base.player.getCurrentLineup().getMp());
    assertNull(opening.base.player.getBattle());
  }

  @Test
  void recoveryPublishesTheMissionPartysNewTechniquePoints() throws Exception {
    assertEquals(0, retcode(dispatch(request("hit_target_entity_id_list", bottle.getEntityId()))));
    var updates = new ArrayList<DynamicMessage>();
    for (var bytes : opening.base.player.packets) {
      var buffer = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
      try {
        var frame = PacketCodec.decodeNext(buffer);
        if (frame.cmdId() == CmdId.SyncLineupNotify) {
          var converted = protocol.outbound(frame.cmdId(), frame.body());
          updates.add(protocol.clientSchema().parse("SyncLineupNotify", converted.body()));
        }
      } finally {
        buffer.release();
      }
    }
    assertEquals(1, updates.size(), "The client must receive the recovered mission-party points");
    var update = updates.getFirst();
    var team =
        (DynamicMessage) update.getField(update.getDescriptorForType().findFieldByName("lineup"));
    assertEquals(2, team.getField(team.getDescriptorForType().findFieldByName("mp")));
    assertTrue(update.getUnknownFields().asMap().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"assist_monster_entity_id_list", "assistInfo", "direct"})
  void nonDestructiblePropCannotUseAnyRecoveryTargetField(String location) throws Exception {
    var excel =
        ResourceLoader.getGson()
            .fromJson("{\"ID\":100025,\"PropType\":\"PROP_TREASURE_CHEST\"}", PropExcel.class);
    var chest = new EntityProp(scene, excel, null, null);
    scene.addEntity(chest);
    assertNotEquals(0, retcode(dispatch(request(location, chest.getEntityId()))));
    assertSame(chest, scene.getEntities().get(chest.getEntityId()));
    assertEquals(0, opening.base.player.getCurrentLineup().getMp());
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "avatar", "lockedSkill", "wrongAbility"})
  void invalidRequestCannotDestroyBottleOrGrantRecovery(String invalid) throws Exception {
    int id = bottle.getEntityId();
    var req = request("assist_monster_entity_id_list", id);
    var type = req.getDescriptorForType();
    switch (invalid) {
      case "unknown" ->
          req.addRepeatedField(type.findFieldByName("assist_monster_entity_id_list"), 199998765);
      case "avatar" ->
          req.addRepeatedField(
              type.findFieldByName("assist_monster_entity_id_list"),
              opening.base.player.getCurrentLeaderAvatar().getEntityId());
      case "lockedSkill" -> {
        req.setField(type.findFieldByName("skill_index"), 1);
        req.setField(type.findFieldByName("maze_ability_str"), "LocalPlayer_Kafka_MazeSkill");
      }
      case "wrongAbility" ->
          req.setField(type.findFieldByName("maze_ability_str"), "NotTheActorsAbility");
      default -> fail(invalid);
    }
    assertNotEquals(0, retcode(dispatch(req)));
    assertSame(bottle, scene.getEntities().get(id));
    assertEquals(0, opening.base.player.getCurrentLineup().getMp());
    assertNull(opening.base.player.getBattle());
    assertTrue(opening.base.player.packets.isEmpty());
  }
}
