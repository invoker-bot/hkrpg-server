package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real original blockers must leave the client's group registry as well as the entity map. */
class StoryGroupUnload46Test {
  final OpeningSceneGroups46Test base = new OpeningSceneGroups46Test();
  Actor actor;

  static class Actor extends OpeningSceneGroups46Test.Actor {
    final List<DynamicMessage> notifications = new ArrayList<>();

    @Override
    public void sendPacket(BasePacket packet) {
      if (packet.getCmdId() != CmdId.SceneGroupRefreshScNotify) return;
      try {
        var bridge = ProtocolBridge.forVersion("4.6.0");
        notifications.add(
            bridge
                .clientSchema()
                .parse(
                    "SceneGroupRefreshScNotify",
                    bridge.translate(
                        "SceneGroupRefreshScNotify", packet.getData().toByteArray(), true)));
      } catch (Exception failure) {
        throw new AssertionError("Actual group notification cannot reach the 4.6 client", failure);
      }
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    base.prepare();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/story-group-unload-4.6.json"),
            StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
      for (var entry : records.entrySet()) {
        for (var row : entry.getValue().getAsJsonArray()) {
          String path = entry.getKey();
          if (path.contains("/SharedRuntimeGroup/")) {
            int id = Integer.parseInt(path.replaceAll(".*_G([0-9]+)\\.json", "$1"));
            var group = base.gson.fromJson(row, GroupInfo.class);
            group.setId(id);
            base.groups.put(id, group);
            base.floor.getGroups().put(id, group);
          } else if (path.endsWith("MazeProp.json")) {
            var prop = base.gson.fromJson(row, PropExcel.class);
            prop.onLoad();
            GameData.getPropExcelMap().add(prop);
          } else {
            var sub = base.gson.fromJson(row, MissionInfo.SubMissionInfo.class);
            GameplayDefinitions.subs.put(sub.getId(), sub);
          }
        }
      }
    }
    actor = new Actor();
    actor.getGameplay().getMainMissions().put(1000101, new QuestState(1000101));
    base.player = actor;
  }

  @AfterEach
  void restore() {
    base.restore();
  }

  void accept(int id) {
    actor.getGameplay().getSubMissions().put(id, new QuestState(id));
  }

  void finish(int id) {
    accept(id);
    actor.getGameplay().getSubMissions().get(id).advance(1, 1, 100);
  }

  List<DynamicMessage> updates(int groupId) {
    var result = new ArrayList<DynamicMessage>();
    for (var packet : actor.notifications) {
      assertEquals(20001001, base.field(packet, "floor_id"));
      assertTrue(packet.getUnknownFields().asMap().isEmpty());
      for (var item : (List<?>) base.field(packet, "group_refresh_list")) {
        var group = (DynamicMessage) item;
        if ((Integer) base.field(group, "group_id") == groupId) result.add(group);
      }
    }
    return result;
  }

  int refreshType(DynamicMessage group) {
    return ((EnumValueDescriptor) base.field(group, "refresh_type")).getNumber();
  }

  @ParameterizedTest
  @CsvSource({"17,100010111,100010130", "33,100010107,100010122"})
  void completingOriginalGatePublishesOneWholeGroupUnload(int group, int take, int goal) {
    accept(take);
    Scene scene = base.scene();
    assertTrue(scene.getLoadedGroups().contains(group));
    assertFalse(base.entities(scene, group).isEmpty(), "Original barrier must actually be loaded");
    scene.toProto();
    actor.notifications.clear();

    finish(goal);
    scene.refreshMissionGroups();

    assertFalse(scene.getLoadedGroups().contains(group));
    assertTrue(base.entities(scene, group).isEmpty());
    assertEquals(
        1,
        updates(group).stream().filter(x -> refreshType(x) == 2).count(),
        "Deleting server entities leaves the client's barrier group alive without UNLOAD");
    assertEquals(2, refreshType(updates(group).getLast()), "Unload must follow entity removal");
    int count = actor.notifications.size();
    scene.refreshMissionGroups();
    assertEquals(count, actor.notifications.size(), "Already unloaded groups must not replay");
    assertEquals(0, actor.saves, "Scene cleanup must not change player progress or inventory");
  }

  @Test
  void unrelatedOrUnfinishedObjectivesCannotRemoveTheCorridorBarrier() {
    accept(100010111);
    accept(100010130);
    Scene scene = base.scene();
    scene.toProto();
    actor.notifications.clear();
    finish(100010122);
    scene.refreshMissionGroups();
    assertTrue(scene.getLoadedGroups().contains(17));
    assertFalse(base.entities(scene, 17).isEmpty());
    assertTrue(updates(17).isEmpty());
  }

  @Test
  void completedBarrierDoesNotRespawnAfterStateReload() {
    finish(100010111);
    finish(100010130);
    var saved = base.gson.toJson(actor.getGameplay());
    var restored = base.gson.fromJson(saved, GameplayState.class);
    actor.getGameplay().getSubMissions().clear();
    actor.getGameplay().getSubMissions().putAll(restored.getSubMissions());
    Scene scene = base.scene();
    assertFalse(scene.getLoadedGroups().contains(17));
    assertTrue(base.entities(scene, 17).isEmpty());
    assertFalse(
        actor.getGameplay().getSubMissions().containsKey(100010113),
        "Visibility restoration cannot complete or accept the next battle");
  }

  @Test
  void groupWithNoRemainingServerEntitiesStillNeedsClientUnload() {
    accept(100010111);
    Scene scene = base.scene();
    scene.toProto();
    for (var entity : base.entities(scene, 17)) scene.removeEntity(entity);
    assertTrue(scene.getLoadedGroups().contains(17));
    actor.notifications.clear();
    finish(100010130);
    scene.refreshMissionGroups();
    assertEquals(
        1,
        updates(17).stream().filter(x -> refreshType(x) == 2).count(),
        "Client-created effects and blockers outlive the last server entity");
  }
}
