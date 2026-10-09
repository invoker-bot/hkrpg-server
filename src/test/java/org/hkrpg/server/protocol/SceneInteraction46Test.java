package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class SceneInteraction46Test {
  static final int PLANE = 199923, FLOOR = 199923001, PROP = 199923, INTERACT = 199923;
  static final int MAIN = 199923, SUB = 1999231;
  final Gson gson = new Gson();
  Gameplay46HandlerTest.Session session;
  PropExcel oldProp;
  InteractExcel oldInteract;

  @BeforeEach
  void setup() {
    oldProp = GameData.getPropExcelMap().get(PROP);
    oldInteract = GameData.getInteractExcelMap().get(INTERACT);
    var prop =
        gson.fromJson(
            "{\"ID\":"
                + PROP
                + ",\"PropType\":\"PROP_ORDINARY\",\"PropStateList\":[\"Closed\",\"Open\"]}",
            PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    interact(false);
    var floor = gson.fromJson("{\"FloorID\":" + FLOOR + "}", FloorInfo.class);
    var group =
        gson.fromJson(
            "{\"GroupName\":\"Interact\",\"LoadSide\":\"Server\",\"PropList\":[{\"ID\":300001,\"PropID\":"
                + PROP
                + "}]}",
            GroupInfo.class);
    group.setId(8);
    floor.getGroups().put(8, group);
    GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, floor);
    var actor =
        new Player() {
          final PlayerLineup team = new PlayerLineup(this, 0);
          Scene current;

          public PlayerLineup getCurrentLineup() {
            if (team.size() == 0) team.getAvatars().add(999999);
            return team;
          }

          public int getPlaneId() {
            return PLANE;
          }

          public int getFloorId() {
            return FLOOR;
          }

          public int getEntryId() {
            return 11;
          }

          public void save() {}

          public void sendPacket(emu.lunarcore.server.packet.BasePacket p) {}

          public Scene getScene() {
            if (current == null)
              current =
                  new Scene(
                      this,
                      gson.fromJson(
                          "{\"PlaneID\":" + PLANE + ",\"PlaneType\":\"Maze\"}",
                          MazePlaneExcel.class),
                      FLOOR);
            return current;
          }
        };
    session =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return actor;
          }
        };
    GameplayDefinitions.subs.put(
        SUB,
        gson.fromJson(
            "{\"ID\":"
                + SUB
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"InteractPropList\",\"ParamType\":\"ListContain\",\"LevelFloorID\":"
                + FLOOR
                + ",\"ParamInt1\":8,\"ParamIntList\":[300001]}",
            MissionInfo.SubMissionInfo.class));
    actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    actor.getMissionManager().refresh();
  }

  void interact(boolean cost) {
    var row =
        gson.fromJson(
            "{\"InteractID\":"
                + INTERACT
                + ",\"TargetState\":\"Open\",\"ItemCostList\":"
                + (cost ? "[{\"ItemID\":140037,\"ItemNum\":1}]" : "[]")
                + "}",
            InteractExcel.class);
    row.onLoad();
    GameData.getInteractExcelMap().add(row);
  }

  @AfterEach
  void cleanup() {
    if (oldProp == null) GameData.getPropExcelMap().keySet().remove(PROP);
    else GameData.getPropExcelMap().add(oldProp);
    if (oldInteract == null) GameData.getInteractExcelMap().keySet().remove(INTERACT);
    else GameData.getInteractExcelMap().add(oldInteract);
    GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
    GameplayDefinitions.subs.remove(SUB);
    GameplayDefinitions.subs.remove(SUB + 1);
  }

  EntityProp prop() {
    return session.getPlayer().getScene().getEntities().values().stream()
        .filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast)
        .findFirst()
        .orElseThrow();
  }

  int request(int entity, int config, long context) throws Exception {
    var req = Gameplay46.builder(session, "InteractPropCsReq");
    Gameplay46.set(req, "prop_entity_id", entity);
    Gameplay46.set(req, "interact_id2", config);
    Gameplay46.set(req, "interact_id", context);
    assertTrue(VersionedPacketHandler.handle(session, 1405, req.build().toByteArray()));
    return Gameplay46.number(session.response, "retcode");
  }

  @Test
  void theNativeConfigFieldAdvancesTheActualInteractionAndSurvivesMapReload() throws Exception {
    var p = prop();
    assertEquals(0, request(p.getEntityId(), INTERACT, 1L << 40));
    assertEquals(PropState.Open, p.getState());
    assertEquals(
        QuestState.Phase.FINISHED, session.getPlayer().getMissionManager().sub(SUB).getPhase());
    assertEquals(p.getEntityId(), Gameplay46.number(session.response, "prop_entity_id"));
    assertEquals(PropState.Open.getVal(), Gameplay46.number(session.response, "prop_state"));
    var reloaded = new Scene(session.getPlayer(), session.getPlayer().getScene().getExcel(), FLOOR);
    var restored =
        reloaded.getEntities().values().stream()
            .filter(EntityProp.class::isInstance)
            .map(EntityProp.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(PropState.Open, restored.getState());
    var other =
        new Scene(session.getPlayer(), session.getPlayer().getScene().getExcel(), FLOOR + 1);
    assertTrue(other.getEntities().isEmpty());
    assertNotEquals(0, request(p.getEntityId(), INTERACT, 1L << 40));
  }

  @Test
  void missingConfigsCostsAndInvalidStatesCannotReportSuccessfulGameplay() throws Exception {
    var p = prop();
    assertEquals(Retcode.SCENE_ENTITY_NOT_EXIST.getVal(), request(123456, INTERACT, 0));
    assertEquals(
        Retcode.INTERACT_CONFIG_NOT_EXIST.getVal(), request(p.getEntityId(), INTERACT + 1, 0));
    assertNotEquals(0, request(p.getEntityId(), 0, INTERACT));
    interact(true);
    assertNotEquals(0, request(p.getEntityId(), INTERACT, 0));
    assertEquals(PropState.Closed, p.getState());
    assertEquals(0, session.getPlayer().getMissionManager().sub(SUB).getProgress());
    interact(false);
    p.setState(PropState.Open, false);
    assertEquals(Retcode.UNSUPPORTED_PROP_STATE.getVal(), request(p.getEntityId(), INTERACT, 0));
    assertEquals(0, session.getPlayer().getMissionManager().sub(SUB).getProgress());
    assertTrue(session.getPlayer().getGameplay().getRewards().isEmpty());
  }

  EntityProp destructible() {
    var excel =
        gson.fromJson(
            "{\"ID\":199922,\"PropType\":\"PROP_DESTRUCT\",\"PropStateList\":[\"Closed\",\"Open\"]}",
            PropExcel.class);
    excel.onLoad();
    var scene = session.getPlayer().getScene();
    var prop =
        new EntityProp(
            scene,
            excel,
            scene.getFloorInfo().getGroups().get(8),
            gson.fromJson("{\"ID\":300002,\"PropID\":199922}", PropInfo.class));
    scene.addEntity(prop);
    GameplayDefinitions.subs.put(
        SUB,
        gson.fromJson(
            "{\"ID\":"
                + SUB
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"PropDestruct\",\"ParamType\":\"Equal\",\"LevelFloorID\":"
                + FLOOR
                + ",\"ParamInt1\":8,\"ParamInt2\":300002}",
            MissionInfo.SubMissionInfo.class));
    return prop;
  }

  @Test
  void nativeRestrictionsDoNotRemoveTheInheritedLegacyChestTransition() throws Exception {
    var scene = session.getPlayer().getScene();
    var excel =
        gson.fromJson(
            "{\"ID\":199922,\"PropType\":\"PROP_TREASURE_CHEST\",\"PropStateList\":[\"ChestClosed\",\"ChestUsed\"]}",
            PropExcel.class);
    excel.onLoad();
    var chest =
        new EntityProp(
            scene,
            excel,
            scene.getFloorInfo().getGroups().get(8),
            gson.fromJson("{\"ID\":300002,\"State\":\"ChestClosed\"}", PropInfo.class));
    chest.setState(PropState.ChestClosed, false);
    scene.addEntity(chest);
    var config =
        gson.fromJson(
            "{\"InteractID\":"
                + INTERACT
                + ",\"SrcState\":\"ChestClosed\",\"TargetState\":\"ChestUsed\"}",
            InteractExcel.class);
    config.onLoad();
    GameData.getInteractExcelMap().add(config);
    assertEquals(
        Retcode.SUCC,
        SceneInteractions.validate(session.getPlayer(), INTERACT, chest.getEntityId()));
    assertEquals(Retcode.INTERACT_TYPE_INVALID.getVal(), request(chest.getEntityId(), INTERACT, 0));
    assertEquals(PropState.ChestClosed, chest.getState());
  }

  @Test
  void ordinaryObjectsCannotBeDestroyedAndOnlyAnActualMatchingDestructionAdvances() {
    var scene = session.getPlayer().getScene();
    var destructible = destructible();
    var ordinary = prop();
    int ordinaryId = ordinary.getEntityId();
    scene.destroyProp(ordinary);
    assertSame(ordinary, scene.getEntityById(ordinaryId));
    assertEquals(0, session.getPlayer().getMissionManager().sub(SUB).getProgress());
    scene.destroyProp(destructible);
    assertEquals(0, destructible.getEntityId());
    assertEquals(1, session.getPlayer().getMissionManager().sub(SUB).getProgress());
    scene.destroyProp(destructible);
    assertEquals(1, session.getPlayer().getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void foreignSceneObjectsAndNewlyUnlockedObjectivesCannotConsumeTheDestruction() {
    var scene = session.getPlayer().getScene();
    var destructible = destructible();
    GameplayDefinitions.subs.put(
        SUB + 1,
        gson.fromJson(
            "{\"ID\":"
                + (SUB + 1)
                + ",\"MainMissionID\":"
                + MAIN
                + ",\"TakeType\":\"MultiSequence\",\"TakeParamIntList\":["
                + SUB
                + "],\"FinishType\":\"PropDestruct\",\"ParamType\":\"Equal\",\"LevelFloorID\":"
                + FLOOR
                + ",\"ParamInt1\":8,\"ParamInt2\":300002}",
            MissionInfo.SubMissionInfo.class));
    var foreign =
        new EntityProp(
            new Scene(session.getPlayer(), scene.getExcel(), FLOOR),
            destructible.getExcel(),
            scene.getFloorInfo().getGroups().get(8),
            destructible.getPropInfo());
    foreign.setEntityId(destructible.getEntityId());
    scene.destroyProp(foreign);
    assertSame(destructible, scene.getEntityById(destructible.getEntityId()));
    assertEquals(0, session.getPlayer().getMissionManager().sub(SUB).getProgress());
    scene.destroyProp(destructible);
    assertEquals(1, session.getPlayer().getMissionManager().sub(SUB).getProgress());
    assertEquals(0, session.getPlayer().getMissionManager().sub(SUB + 1).getProgress());
  }
}
