package org.hkrpg.server.gameplay;

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
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

class ClientVisualGroups46Test {
  private static final int FLOOR=10301001, PLANE=10301, MAIN=1033803;
  private final Gson gson=ResourceLoader.getGson();
  private final Map<Integer,PropExcel> oldProps=new HashMap<>();
  private final Map<Integer,NpcExcel> oldNpcs=new HashMap<>();
  private final Map<Integer,MissionInfo.SubMissionInfo> oldSubs=new HashMap<>();
  private final ClientNpcOwnership46Test.Actor actor=new ClientNpcOwnership46Test.Actor() {
    @Override public int getFloorId(){return FLOOR;}
    @Override public int getPlaneId(){return PLANE;}
    @Override public emu.lunarcore.game.player.lineup.PlayerLineup getCurrentLineup(){return teams.getLineups()[0];}
    @Override public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id){return null;}
  };
  private JsonObject source;
  private FloorInfo oldFloor;
  private FloorInfo oldForeignFloor;

  @BeforeEach void prepare() throws Exception {
    try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/client-visual-groups-4.6.json")),StandardCharsets.UTF_8)) {
      var fixture=JsonParser.parseReader(reader).getAsJsonObject();source=fixture.getAsJsonObject("groups");
      for(var raw:fixture.getAsJsonArray("props")) {
        var prop=gson.fromJson(raw,PropExcel.class);prop.onLoad();oldProps.put(prop.getId(),GameData.getPropExcelMap().get(prop.getId()));GameData.getPropExcelMap().add(prop);
      }
      for(var raw:fixture.getAsJsonArray("npcs")) {
        var npc=gson.fromJson(raw,NpcExcel.class);oldNpcs.put(npc.getId(),GameData.getNpcExcelMap().get(npc.getId()));GameData.getNpcExcelMap().add(npc);
      }
      for(var raw:fixture.getAsJsonArray("subs")) {
        var sub=gson.fromJson(raw,MissionInfo.SubMissionInfo.class);oldSubs.put(sub.getId(),GameplayDefinitions.subs.put(sub.getId(),sub));
      }
    }
    oldFloor=GameData.getFloorInfos().get("P10301_F10301001");
    oldForeignFloor=GameData.getFloorInfos().get("P20551_F20551001");
    actor.getGameplay().getMainMissions().put(MAIN,new QuestState(MAIN));
    var place=new StoryLineState.Place(PLANE,FLOOR,0,0,new emu.lunarcore.util.Position(),new emu.lunarcore.util.Position());
    actor.getGameplay().getStoryLines().begin(MAIN,place,place);
    actor.getGameplay().getStoryLines().arrived();
  }

  @AfterEach void restore() {
    if(oldFloor==null)GameData.getFloorInfos().remove("P10301_F10301001");else GameData.getFloorInfos().put("P10301_F10301001",oldFloor);
    if(oldForeignFloor==null)GameData.getFloorInfos().remove("P20551_F20551001");else GameData.getFloorInfos().put("P20551_F20551001",oldForeignFloor);
    oldProps.forEach((id,row)->{GameData.getPropExcelMap().keySet().remove(id);if(row!=null)GameData.getPropExcelMap().add(row);});
    oldNpcs.forEach((id,row)->{GameData.getNpcExcelMap().keySet().remove(id);if(row!=null)GameData.getNpcExcelMap().add(row);});
    oldSubs.forEach((id,row)->{if(row==null)GameplayDefinitions.subs.remove(id);else GameplayDefinitions.subs.put(id,row);});
  }

  @Test void parentMissionAloneDoesNotActivateDormantOrRetiredGroups() {
    enter();
    for(int group:List.of(648,649,650,660))assertFalse(actor.scene.getLoadedGroups().contains(group),"premature group "+group);
    assertEquals(QuestState.Phase.DOING,actor.getGameplay().getMainMissions().get(MAIN).getPhase());
  }

  @Test void exactCurrentSubMissionActivatesOnlyItsAuthoredGroup() {
    actor.getGameplay().getSubMissions().put(103380302,new QuestState(103380302));enter();
    assertTrue(actor.scene.getLoadedGroups().contains(649));
    for(int group:List.of(648,650,660))assertFalse(actor.scene.getLoadedGroups().contains(group));
    actor.scene.loadGroup(648);
    assertFalse(actor.scene.getLoadedGroups().contains(648));
  }

  @Test void localVisualsKeepLogicalPropsAndNeverPublishFullOrIncrementalCopies() throws Exception {
    actor.getGameplay().getSubMissions().put(103380319,new QuestState(103380319));enter();
    var props=actor.scene.getEntitiesByGroup(EntityProp.class,660);assertFalse(props.isEmpty());
    assertFalse(nativeGroups().contains(660));
    for(var prop:props)for(boolean deleting:List.of(false,true)) {
      var frame=new PacketSceneGroupRefreshScNotify(actor.scene,deleting?null:prop,deleting?prop:null).build();
      assertTrue(((List<?>)field(nativePacket("SceneGroupRefreshScNotify",frame),"group_refresh_list")).isEmpty());
    }
    var gates=(DynamicMessage)field(sceneInfo(),"scene_mission_info");
    assertTrue(((List<?>)field(gates,"sub_mission_status_list")).stream().map(DynamicMessage.class::cast)
        .anyMatch(m->field(m,"id").equals(103380319)&&field(m,"status").toString().equals("MissionStatus_MissionDoing")));
    actor.getGameplay().getSubMissions().put(103380319,ClientNpcOwnership46Test.completed(103380319));
    actor.scene.refreshMissionGroups();
    assertFalse(actor.scene.getLoadedGroups().contains(660));assertTrue(actor.scene.getEntitiesByGroup(EntityProp.class,660).isEmpty());
  }

  @Test void explicitServerAndOperatorChangesRetainTheirPublication() throws Exception {
    actor.getGameplay().getSubMissions().put(103380319,new QuestState(103380319));
    source.getAsJsonObject("10301:660").addProperty("LoadSide","Server");enter();
    assertTrue(nativeGroups().contains(660));
    source.getAsJsonObject("10301:660").remove("LoadSide");
    source.getAsJsonObject("10301:660").getAsJsonArray("PropList").get(0).getAsJsonObject().addProperty("PosX",12.5);enter();
    assertTrue(nativeGroups().contains(660));
  }

  @Test void legacySessionKeepsTheExistingLoadingPolicy() {
    actor.wire=ProtocolBridge.forVersion("4.2.0");enter();
    assertTrue(actor.scene.getLoadedGroups().contains(648));
    assertFalse(actor.scene.getEntitiesByGroup(EntityProp.class,660).isEmpty());
  }

  @Test void localVisualSourcesDoNotInventUntakenParentRecords() throws Exception {
    actor.getGameplay().getMainMissions().clear();enter();
    var gates=(DynamicMessage)field(sceneInfo(),"scene_mission_info");
    assertFalse(((List<?>)field(gates,"unfinished_main_mission_id_list")).contains(MAIN));
  }

  @Test void cancelledStepCannotActivateItsVisuals() {
    actor.getGameplay().getSubMissions().put(103380319,new QuestState(103380319));
    actor.getGameplay().getCancelledSubs().add(103380319);enter();
    assertFalse(actor.scene.getLoadedGroups().contains(660));
  }

  @Test void foreignFloorCopyOfAHoyoGroupCannotUseAnotherMapsActiveStep() {
    actor.getGameplay().getMainMissions().put(1054528,new QuestState(1054528));
    actor.getGameplay().getSubMissions().put(105452802,new QuestState(105452802));
    var place=new StoryLineState.Place(20551,20551001,0,0,new emu.lunarcore.util.Position(),new emu.lunarcore.util.Position());
    actor.getGameplay().getStoryLines().begin(0,place,place);actor.getGameplay().getStoryLines().arrived();
    var floor=gson.fromJson("{\"FloorID\":20551001,\"GroupInstanceList\":[{\"ID\":86}],\"DimensionList\":[{\"GroupIndexList\":[0]}]}",FloorInfo.class);
    var group=gson.fromJson(source.getAsJsonObject("20551:86"),GroupInfo.class);group.setId(86);floor.registerGroup(0,group);floor.onLoad();
    GameData.getFloorInfos().put("P20551_F20551001",floor);
    var plane=gson.fromJson("{\"PlaneID\":20551,\"PlaneType\":\"Maze\",\"WorldID\":501}",MazePlaneExcel.class);
    actor.scene=new Scene(actor,plane,20551001);
    assertFalse(actor.getMissionManager().shouldLoadGroup(group,actor.scene));
    assertFalse(actor.scene.getLoadedGroups().contains(86));
  }

  @Test void partialExistingGateSnapshotKeepsOtherMissionsAndAddsTheSavedBinding() throws Exception {
    actor.getGameplay().getSubMissions().put(103380319,new QuestState(103380319));enter();
    var scene=sceneInfo();var gateField=scene.getDescriptorForType().findFieldByName("scene_mission_info");
    var gates=((DynamicMessage)scene.getField(gateField)).toBuilder();
    var list=gates.getDescriptorForType().findFieldByName("sub_mission_status_list");gates.clearField(list);
    var mains=gates.getDescriptorForType().findFieldByName("finished_main_mission_id_list");gates.addRepeatedField(mains,987654321);
    scene=scene.toBuilder().setField(gateField,gates.build()).build();
    var type=Class.forName("org.hkrpg.server.protocol.ClientNpcPublication46");
    var project=type.getDeclaredMethod("sceneInfo",DynamicMessage.class,Scene.class);project.setAccessible(true);
    var result=(DynamicMessage)project.invoke(null,scene,actor.scene);
    var after=(DynamicMessage)result.getField(gateField);
    assertTrue(((List<?>)field(after,"finished_main_mission_id_list")).contains(987654321));
    assertTrue(((List<?>)after.getField(list)).stream().map(DynamicMessage.class::cast).anyMatch(m->field(m,"id").equals(103380319)));
  }

  private void enter() {
    var floor=gson.fromJson("""
      {"FloorID":10301001,"GroupInstanceList":[{"ID":648},{"ID":649},{"ID":650},{"ID":660}],
       "DimensionList":[{"GroupIndexList":[0,1,2,3]}]}
      """,FloorInfo.class);
    for(int index:floor.getReferencedGroupIndices()) {
      int id=floor.getSimpleGroupList().get(index).getID();var group=gson.fromJson(source.getAsJsonObject("10301:"+id),GroupInfo.class);group.setId(id);floor.registerGroup(index,group);
    }
    floor.onLoad();GameData.getFloorInfos().put("P10301_F10301001",floor);
    var plane=gson.fromJson("{\"PlaneID\":10301,\"PlaneType\":\"Town\",\"WorldID\":401}",MazePlaneExcel.class);
    actor.scene=new Scene(actor,plane,FLOOR);
  }

  private DynamicMessage sceneInfo() throws Exception {
    var core=GetCurSceneInfoScRsp.newInstance().setScene(actor.scene.toProto());
    return (DynamicMessage)field(nativePacket("GetCurSceneInfoScRsp",PacketCodec.encode(CmdId.GetCurSceneInfoScRsp,new byte[0],core.toByteArray())),"scene");
  }
  private Set<Integer> nativeGroups() throws Exception {
    var ids=new HashSet<Integer>();for(var row:(List<?>)field(sceneInfo(),"entity_group_list"))ids.add((Integer)field((DynamicMessage)row,"group_id"));return ids;
  }
  private DynamicMessage nativePacket(String name,byte[] bytes) throws Exception {
    var input=Unpooled.wrappedBuffer(actor.wire.outboundFrames(bytes,actor));
    try {var frame=Objects.requireNonNull(PacketCodec.decodeNext(input));assertEquals(actor.wire.clientCommand(name),frame.cmdId());return actor.wire.clientSchema().parse(name,frame.body());}finally{input.release();}
  }
}
