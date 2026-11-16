package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.ClientNpcOwnership46Test.completed;
import static org.hkrpg.server.gameplay.ClientNpcOwnership46Test.field;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.proto.GetCurSceneInfoScRspOuterClass.GetCurSceneInfoScRsp;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketEnterSceneByServerScNotify;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Seven original 4.6 wanted posters and their independent task and scene lifecycles. */
class WantedPosterGroups46Test {
  private static final int PLANE=10101, FLOOR=10101001, MAIN=4040109;
  private final Gson gson=ResourceLoader.getGson();
  private final Map<Integer,PropExcel> oldProps=new HashMap<>();
  private final Map<Integer,MissionInfo.SubMissionInfo> oldSubs=new HashMap<>();
  private final ClientNpcOwnership46Test.Actor actor=new ClientNpcOwnership46Test.Actor() {
    @Override public int getFloorId(){return FLOOR;}
    @Override public int getPlaneId(){return PLANE;}
    @Override public int getEntryId(){return 1010101;}
    @Override public emu.lunarcore.game.player.lineup.PlayerLineup getCurrentLineup(){return teams.getLineups()[0];}
    @Override public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id){return null;}
    @Override public void save() { }
  };
  private JsonObject groups;
  private FloorInfo oldFloor;

  @BeforeEach void prepare() throws Exception {
    try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/wanted-poster-groups-4.6.json")),StandardCharsets.UTF_8)) {
      var fixture=JsonParser.parseReader(reader).getAsJsonObject();
      groups=fixture.getAsJsonObject("groups");
      for(var entry:fixture.getAsJsonArray("props")) {
        var raw=entry.getAsJsonObject().deepCopy();
        raw.addProperty("PropName",raw.getAsJsonObject("PropName").get("Hash").getAsLong());
        var prop=gson.fromJson(raw,PropExcel.class);prop.onLoad();
        oldProps.put(prop.getId(),GameData.getPropExcelMap().get(prop.getId()));
        GameData.getPropExcelMap().add(prop);
      }
      for(var raw:fixture.getAsJsonArray("subs")) {
        var sub=gson.fromJson(raw,MissionInfo.SubMissionInfo.class);
        oldSubs.put(sub.getId(),GameplayDefinitions.subs.put(sub.getId(),sub));
      }
    }
    oldFloor=GameData.getFloorInfos().get("P10101_F10101001");
    actor.getGameplay().getMainMissions().put(MAIN,new QuestState(MAIN));
    for(int id=404010901;id<=404010907;id++)actor.getGameplay().getSubMissions().put(id,new QuestState(id));
  }

  @AfterEach void restore() {
    if(oldFloor==null)GameData.getFloorInfos().remove("P10101_F10101001");
    else GameData.getFloorInfos().put("P10101_F10101001",oldFloor);
    oldProps.forEach((id,row)->{GameData.getPropExcelMap().keySet().remove(id);if(row!=null)GameData.getPropExcelMap().add(row);});
    oldSubs.forEach((id,row)->{if(row==null)GameplayDefinitions.subs.remove(id);else GameplayDefinitions.subs.put(id,row);});
  }

  @ParameterizedTest
  @ValueSource(ints={147,148,149,150,151,152,153})
  void nativeSceneUsesClientOwnedPosterAndKeepsServerLogic(int group) throws Exception {
    enter();
    assertTrue(actor.scene.getLoadedGroups().contains(group));
    var props=actor.scene.getEntitiesByGroup(EntityProp.class,group);
    assertEquals(1,props.size(),"The server still needs the logical mission object");
    var entered=nativePacket("EnterSceneByServerScNotify",new PacketEnterSceneByServerScNotify(actor).build());
    assertFalse(publishes((DynamicMessage)field(entered,"scene"),group),"Client-owned posters must not be published twice");
    assertFalse(publishes(sceneInfo(),group));
    for(var packet:List.of(new PacketSceneGroupRefreshScNotify(actor.scene,props.getFirst(),null),
        new PacketSceneGroupRefreshScNotify(actor.scene,null,props.getFirst()),
        new PacketSceneGroupRefreshScNotify(actor.scene,group))) {
      var refresh=nativePacket("SceneGroupRefreshScNotify",packet.build());
      assertTrue(((List<?>)field(refresh,"group_refresh_list")).isEmpty());
    }
    var gates=(DynamicMessage)field(sceneInfo(),"scene_mission_info");
    assertTrue(((List<?>)field(gates,"unfinished_main_mission_id_list")).contains(MAIN));
    assertEquals("MissionStatus_MissionDoing",status(gates,404010901+group-147));
  }

  @ParameterizedTest
  @ValueSource(ints={147,148,149,150,151,152,153})
  void savedCompletionRetiresOnlyItsOwnPosterAndIsProjectedOnReentry(int group) throws Exception {
    int completedId=404010901+group-147;
    enter();
    actor.getGameplay().getSubMissions().put(completedId,completed(completedId));
    actor.scene.refreshMissionGroups();
    assertFalse(actor.scene.getLoadedGroups().contains(group));
    assertTrue(actor.scene.getEntitiesByGroup(EntityProp.class,group).isEmpty());
    for(int other=147;other<=153;other++)if(other!=group)assertTrue(actor.scene.getLoadedGroups().contains(other));
    var gates=(DynamicMessage)field(sceneInfo(),"scene_mission_info");
    assertEquals("MissionStatus_MissionFinish",status(gates,completedId));
    assertEquals(QuestState.Phase.DOING,actor.getGameplay().getMainMissions().get(MAIN).getPhase());
    enter();
    assertFalse(actor.scene.getLoadedGroups().contains(group));
    assertEquals("MissionStatus_MissionFinish",status((DynamicMessage)field(sceneInfo(),"scene_mission_info"),completedId));
  }

  @Test void changedOrExplicitServerSourceRetainsExistingPublication() throws Exception {
    groups.getAsJsonObject("147").addProperty("LoadSide","Server");
    enter();assertTrue(publishes(sceneInfo(),147));
    groups.getAsJsonObject("147").remove("LoadSide");
    groups.getAsJsonObject("147").getAsJsonArray("PropList").get(0).getAsJsonObject().addProperty("PosX",-50);
    enter();assertTrue(publishes(sceneInfo(),147));
  }

  @Test void aDifferentGroupIdCannotReuseAnotherPostersVerifiedSource() throws Exception {
    groups.add("148",groups.getAsJsonObject("147").deepCopy());
    enter();
    assertTrue(publishes(sceneInfo(),148));
    assertFalse(publishes(sceneInfo(),147));
  }

  @Test void sceneProjectionCannotInventAnUntakenParentOrItsObjectives() throws Exception {
    actor.getGameplay().getMainMissions().clear();
    enter();
    var gates=(DynamicMessage)field(sceneInfo(),"scene_mission_info");
    assertFalse(((List<?>)field(gates,"unfinished_main_mission_id_list")).contains(MAIN));
    assertTrue(((List<?>)field(gates,"sub_mission_status_list")).isEmpty());
  }

  @Test void legacyProtocolStillPublishesItsServerEntity() throws Exception {
    actor.wire=ProtocolBridge.forVersion("4.2.0");
    enter();
    assertFalse(actor.scene.getEntitiesByGroup(EntityProp.class,147).isEmpty());
    assertTrue(java.util.stream.StreamSupport.stream(actor.scene.toProto().getEntityGroupList().spliterator(),false)
        .anyMatch(g->g.getGroupId()==147));
  }

  private void enter() {
    var floor=gson.fromJson("""
      {"FloorID":10101001,"GroupInstanceList":[{"ID":147},{"ID":148},{"ID":149},{"ID":150},
       {"ID":151},{"ID":152},{"ID":153}],"DimensionList":[{"GroupIndexList":[0,1,2,3,4,5,6]}]}
      """,FloorInfo.class);
    for(int index:floor.getReferencedGroupIndices()) {
      int id=floor.getSimpleGroupList().get(index).getID();
      var group=gson.fromJson(groups.getAsJsonObject(Integer.toString(id)),GroupInfo.class);
      group.setId(id);floor.registerGroup(index,group);
    }
    floor.onLoad();GameData.getFloorInfos().put("P10101_F10101001",floor);
    var plane=gson.fromJson("{\"PlaneID\":10101,\"PlaneType\":\"Town\",\"WorldID\":101}",MazePlaneExcel.class);
    actor.scene=new Scene(actor,plane,FLOOR);actor.scene.setEntryId(actor.getEntryId());
  }

  private DynamicMessage sceneInfo() throws Exception {
    var core=GetCurSceneInfoScRsp.newInstance().setScene(actor.scene.toProto());
    return (DynamicMessage)field(nativePacket("GetCurSceneInfoScRsp",PacketCodec.encode(
        CmdId.GetCurSceneInfoScRsp,new byte[0],core.toByteArray())),"scene");
  }
  private DynamicMessage nativePacket(String name,byte[] bytes) throws Exception {
    var input=Unpooled.wrappedBuffer(actor.wire.outboundFrames(bytes,actor));
    try {var frame=Objects.requireNonNull(PacketCodec.decodeNext(input));
      assertEquals(actor.wire.clientCommand(name),frame.cmdId());
      return actor.wire.clientSchema().parse(name,frame.body());
    }finally{input.release();}
  }
  private static boolean publishes(DynamicMessage scene,int id) {
    return ((List<?>)field(scene,"entity_group_list")).stream().map(DynamicMessage.class::cast)
        .anyMatch(row->field(row,"group_id").equals(id));
  }
  private static String status(DynamicMessage gates,int id) {
    return ((List<?>)field(gates,"sub_mission_status_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->field(row,"id").equals(id)).map(row->field(row,"status").toString()).findFirst().orElse("");
  }
}
