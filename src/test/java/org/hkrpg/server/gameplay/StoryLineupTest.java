package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.Test;

class StoryLineupTest {
  static class Actor extends GameAvatar {
    final int base;

    Actor(int id) {
      base = id;
    }

    public int getAvatarId() {
      return base;
    }

    public void save() {}

    public SceneEntityInfo toSceneEntityProto() {
      return SceneEntityInfo.newInstance().setEntityId(getEntityId());
    }
  }

  static class Director extends Player {
    final Map<Integer, GameAvatar> actors =
        Map.of(199961, new Actor(199961), 199962, new Actor(199962));
    final PlayerLineup team = new TestTeam(this);
    final LineupManager teams = new TestTeams(this);
    Scene scene;
    final List<emu.lunarcore.server.packet.BasePacket> packets = new ArrayList<>();

    Director() {
      team.getAvatars().add(199961);
    }

    public PlayerLineup getCurrentLineup() {
      return team;
    }

    public LineupManager getLineupManager() {
      return teams;
    }

    public GameAvatar getAvatarById(int id) {
      return actors.get(id);
    }

    public void save() {}

    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
      packets.add(packet);
    }

    public Scene getScene() {
      if (scene == null)
        scene =
            new Scene(
                this,
                new Gson()
                    .fromJson("{\"PlaneID\":199963,\"PlaneType\":\"Town\"}", MazePlaneExcel.class),
                199963001);
      return scene;
    }
  }

  static class TestTeam extends PlayerLineup {
    TestTeam(Player player) {
      super(player, 0);
    }

    public void save() {}

    public int getMp() {
      return 5;
    }
  }

  static class TestTeams extends LineupManager {
    TestTeams(Player player) {
      super(player);
    }

    public PlayerLineup getCurrentLineup() {
      return ((Director) getPlayer()).team;
    }
  }

  @Test
  void resourceLineupActionChangesLeaderAndActualSceneActorsOnce() throws Exception {
    var player = new Director();
    var scene = player.getScene();
    int removedEntity = player.actors.get(199961).getEntityId();
    var sub =
        new Gson()
            .fromJson(
                "{\"ID\":19996301,\"FinishActionList\":[{\"FinishActionType\":\"ChangeLineup\","
                    + "\"FinishActionPara\":[199962,0,0,0,199962]}]}",
                MissionInfo.SubMissionInfo.class);
    assertEquals(Retcode.SUCC, new StoryActions(player).execute(sub));
    assertEquals(List.of(199962), player.team.getAvatars());
    assertEquals(0, player.team.getLeader());
    assertEquals(Set.of(199962), scene.getAvatars().keySet());
    assertEquals(0, player.actors.get(199961).getEntityId());
    int entity = player.actors.get(199962).getEntityId();
    assertTrue(entity > 0);
    var refresh =
        (emu.lunarcore.proto.SceneGroupRefreshScNotifyOuterClass.SceneGroupRefreshScNotify)
            player.packets.getFirst().getData();
    var entities = refresh.getGroupRefreshInfo().get(0).getRefreshEntity();
    assertEquals(removedEntity, entities.get(1).getDelEntity());
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var wire = bridge.outbound(player.packets.getFirst().getCmdId(), refresh.toByteArray());
    var nativeRefresh = bridge.clientSchema().parse("SceneGroupRefreshScNotify", wire.body());
    var groups =
        (List<?>)
            nativeRefresh.getField(
                nativeRefresh.getDescriptorForType().findFieldByName("group_refresh_list"));
    var group = (com.google.protobuf.DynamicMessage) groups.getFirst();
    var changes =
        (List<?>) group.getField(group.getDescriptorForType().findFieldByName("refresh_entity"));
    var deleted = (com.google.protobuf.DynamicMessage) changes.get(1);
    assertEquals(
        removedEntity,
        deleted.getField(deleted.getDescriptorForType().findFieldByName("delete_entity")));
    refresh.getMutableGroupRefreshInfo().get(0).setGroupRefreshTypeValue(4);
    assertThrows(
        org.hkrpg.server.protocol.ProtocolException.class,
        () -> bridge.outbound(player.packets.getFirst().getCmdId(), refresh.toByteArray()));
    assertEquals(Retcode.SUCC, new StoryActions(player).execute(sub));
    assertEquals(entity, player.actors.get(199962).getEntityId());
  }

  @Test
  void unownedStoryCharactersAndInvalidLeadersRejectTheWholeSequenceBeforeVariableChanges() {
    for (String parameters :
        List.of("[199963,0,0,0,199963]", "[199961,0,0,0,199962]", "[199961,199961,0,0,199961]")) {
      var player = new Director();
      var sub =
          new Gson()
              .fromJson(
                  "{\"ID\":19996302,\"MainMissionID\":199963,\"FinishActionList\":["
                      + "{\"FinishActionType\":\"SetCustomValue\",\"FinishActionParaString\":[\"Before\",\"1\"]},"
                      + "{\"FinishActionType\":\"ChangeLineup\",\"FinishActionPara\":"
                      + parameters
                      + "}]}",
                  MissionInfo.SubMissionInfo.class);
      assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, new StoryActions(player).execute(sub));
      assertEquals(List.of(199961), player.team.getAvatars());
      assertTrue(player.getGameplay().getStoryValues().isEmpty());
      assertTrue(player.getGameplay().getStoryActions().isEmpty());
      assertNull(player.getAvatarById(199963));
    }
  }
}
