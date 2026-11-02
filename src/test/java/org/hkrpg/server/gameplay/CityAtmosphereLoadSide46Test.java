package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.BasePacket;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;

/** Original city groups; no seeded mission completion is required for the ambient-role defect. */
class CityAtmosphereLoadSide46Test {
  static final String FLOOR = "P10101_F10101001";
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer,GroupInfo> groups = new HashMap<>();
  final Map<Integer,JsonObject> original = new HashMap<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final List<NpcExcel> oldNpcs = new ArrayList<>();
  FloorInfo floor,oldFloor;
  MazePlaneExcel plane;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    int saves;
    Actor() {teams.getCurrentLineup().getAvatars().add(0);}
    public LineupManager getLineupManager() {return teams;}
    public Scene getScene() {return scene;}
    public int getPlaneId() {return 10101;}
    public int getFloorId() {return 10101001;}
    public void save() {saves++;}
    public void sendPacket(BasePacket packet) {packets.add(packet);}
  }

  @BeforeEach void prepare() throws Exception {
    oldFloor=GameData.getFloorInfos().get(FLOOR);
    oldProps.addAll(GameData.getPropExcelMap().values());
    oldNpcs.addAll(GameData.getNpcExcelMap().values());
    try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/city-atmosphere-load-side-4.6.json")),StandardCharsets.UTF_8)) {
      var records=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
      for(var entry:records.entrySet())for(var raw:entry.getValue().getAsJsonArray()) {
        var row=raw.getAsJsonObject();String path=entry.getKey();
        if(path.contains("/SharedRuntimeGroup/")) {
          int id=Integer.parseInt(path.replaceAll(".*_G([0-9]+)\\.json","$1"));
          var group=gson.fromJson(row,GroupInfo.class);group.setId(id);groups.put(id,group);original.put(id,row);
        } else switch(path) {
          case "Config/LevelOutput/RuntimeFloor/P10101_F10101001.json" -> floor=gson.fromJson(row,FloorInfo.class);
          case "ExcelOutput/MazePlane.json" -> plane=gson.fromJson(row,MazePlaneExcel.class);
          case "ExcelOutput/MazeProp.json" -> {var prop=gson.fromJson(row,PropExcel.class);prop.onLoad();GameData.getPropExcelMap().add(prop);}
          case "ExcelOutput/NPCData.json" -> GameData.getNpcExcelMap().add(gson.fromJson(row,NpcExcel.class));
          default -> fail("Unexpected original source: "+path);
        }
      }
    }
    var definitions=floor.getSimpleGroupList();
    for(int i=0;i<definitions.size();i++) {
      var group=groups.get(definitions.get(i).getID());if(group!=null)floor.registerGroup(i,group);
    }
    floor.onLoad();GameData.getFloorInfos().put(FLOOR,floor);player=new Actor();
  }

  @AfterEach void restore() {
    if(oldFloor==null)GameData.getFloorInfos().remove(FLOOR);else GameData.getFloorInfos().put(FLOOR,oldFloor);
    GameData.getPropExcelMap().values().clear();oldProps.forEach(GameData.getPropExcelMap()::add);
    GameData.getNpcExcelMap().values().clear();oldNpcs.forEach(GameData.getNpcExcelMap()::add);
  }
  Scene scene() {player.scene=new Scene(player,plane,10101001);return player.scene;}
  void noCrowdChat(Scene scene) {
    assertFalse(scene.getLoadedGroups().contains(120),"Inactive client CrowdChat group registered on server");
    assertTrue(scene.getEntities().values().stream().noneMatch(e->e.getGroupId()==120));
  }

  @Test void dormantNativeAtmosphereWithOmittedSideMeansClient() {
    assertFalse(original.get(120).has("LoadSide"));
    assertEquals(GroupInfo.GroupLoadSide.Client,groups.get(120).getLoadSide());
    assertEquals(GroupInfo.GroupLoadSide.Server,groups.get(164).getLoadSide(),
        "Active legacy atmosphere admission remains outside this dormant-group correction");
    assertEquals(GroupInfo.GroupLoadSide.Server,groups.get(2).getLoadSide());
  }

  @Test void originalDormantAtmosphereAcrossLaterMapsKeepsItsNativeClientRole() throws Exception {
    try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/city-atmosphere-load-side-4.6.json")),StandardCharsets.UTF_8)) {
      var rows=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("crossMapDormantGroups");
      assertEquals(11,rows.size());
      for(var value:rows) {
        var row=value.getAsJsonObject();var raw=row.getAsJsonObject("record");
        var group=gson.fromJson(raw,GroupInfo.class);
        assertEquals(GroupInfo.GroupLoadSide.Client,group.getLoadSide(),row.get("path").getAsString());
        assertFalse(player.getMissionManager().shouldLoadGroup(group));
        assertEquals(raw.getAsJsonArray("PropList").size(),group.getPropList().size());
        assertEquals(raw.getAsJsonArray("NPCList").size(),group.getNPCList().size());
      }
    }
  }
  @Test void initialSceneDoesNotSendTheNineMissingBubbleChatGraphs() {
    var scene=scene();noCrowdChat(scene);
    assertEquals(9,groups.get(120).getPropList().size());
    assertTrue(scene.getEntities().values().stream().anyMatch(e->e.getGroupId()==2),"Explicit server shop NPCs must remain");
    assertTrue(scene.getEntities().values().stream().anyMatch(e->e.getGroupId()==13),"Explicit server investigation props must remain");
    assertEquals(0,player.saves);
  }
  @Test void explicitLoadAndMissionAdmissionDoNotBypassTheClientRole() {
    var scene=scene();
    assertFalse(player.getMissionManager().shouldLoadGroup(groups.get(120),scene));
    scene.loadGroup(120);noCrowdChat(scene);
  }
  @Test void nativeSceneContainsNoClientCrowdChatEntities() throws Exception {
    var scene=scene();var bridge=ProtocolBridge.forVersion("4.6.0");
    var nativeScene=bridge.clientSchema().parse("SceneInfo",bridge.translate("SceneInfo",scene.toProto().toByteArray(),true));
    for(var raw:(List<?>)nativeScene.getField(nativeScene.getDescriptorForType().findFieldByName("entity_group_list"))) {
      var group=(DynamicMessage)raw;
      assertNotEquals(120,group.getField(group.getDescriptorForType().findFieldByName("group_id")));
    }
  }
  @Test void explicitServerAtmosphereAndLegacyDefaultsRemainDistinct() {
    var raw=original.get(120).deepCopy();raw.addProperty("LoadSide","Server");
    assertEquals(GroupInfo.GroupLoadSide.Server,gson.fromJson(raw,GroupInfo.class).getLoadSide());
    raw.addProperty("LoadSide","Client");
    assertEquals(GroupInfo.GroupLoadSide.Client,gson.fromJson(raw,GroupInfo.class).getLoadSide());
    assertEquals(GroupInfo.GroupLoadSide.Server,gson.fromJson("{}",GroupInfo.class).getLoadSide());
    assertEquals(GroupInfo.GroupLoadSide.Server,new Gson().fromJson(original.get(120),GroupInfo.class).getLoadSide());
  }
  @Test void normalAndMissionDefaultsAreOutsideThisAtmosphereCorrection() {
    for(String category:List.of("Normal","Mission")) {
      var raw=original.get(120).deepCopy();raw.addProperty("Category",category);
      assertEquals(GroupInfo.GroupLoadSide.Server,gson.fromJson(raw,GroupInfo.class).getLoadSide());
    }
  }
  @Test void originalDormantFalseAndActiveTrueRemainDistinct() {
    var raw=original.get(120).deepCopy();raw.addProperty("LoadOnInitial",false);
    assertEquals(GroupInfo.GroupLoadSide.Client,gson.fromJson(raw,GroupInfo.class).getLoadSide());
    raw.addProperty("LoadOnInitial",true);
    assertEquals(GroupInfo.GroupLoadSide.Server,gson.fromJson(raw,GroupInfo.class).getLoadSide());
    assertTrue(original.get(164).get("LoadOnInitial").getAsBoolean());
    var scene=scene();
    assertTrue(scene.getLoadedGroups().contains(164),"Existing active crowd interaction must remain");
  }
  @Test void conditionControlledAtmosphereKeepsItsExistingMissionAdmission() {
    for(String field:List.of("LoadCondition","UnloadCondition","AtmosphereCondition")) {
      var raw=original.get(120).deepCopy();raw.add(field,new JsonObject());
      assertEquals(GroupInfo.GroupLoadSide.Server,gson.fromJson(raw,GroupInfo.class).getLoadSide(),
          "Conditional gameplay remains outside the unconditional dormant-group correction: "+field);
    }
  }
  @Test void refreshAndSceneReconstructionKeepClientGroupsOffTheWire() {
    var first=scene();first.refreshMissionGroups();noCrowdChat(first);
    var second=scene();noCrowdChat(second);assertEquals(0,player.saves);
  }
}
