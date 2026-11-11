package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NativeRaidGroupBindings46Test {
  private final ClientNpcOwnership46Test.Actor actor=new ClientNpcOwnership46Test.Actor();
  private final Map<Integer,MissionInfo.SubMissionInfo> oldSubs=new HashMap<>(GameplayDefinitions.subs);
  private final Map<String,FloorInfo> oldFloors=new HashMap<>(GameData.getFloorInfos());
  private JsonObject sources;
  @BeforeEach void prepare() throws Exception {
    var fixture=JsonParser.parseReader(new java.io.InputStreamReader(Objects.requireNonNull(
        getClass().getResourceAsStream("/fixtures/native-raid-group-bindings-4.6.json")),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    sources=fixture.getAsJsonObject("groups");
    for(var value:fixture.getAsJsonArray("subs")) {
      var sub=ResourceLoader.getGson().fromJson(value,MissionInfo.SubMissionInfo.class);GameplayDefinitions.subs.put(sub.getId(),sub);
    }
  }
  @AfterEach void restore(){
    GameplayDefinitions.subs.clear();GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getFloorInfos().clear();GameData.getFloorInfos().putAll(oldFloors);
  }
  @ParameterizedTest @CsvSource({"40234001,22,2021902","40401001,16,1040102","43003001,8,2000903"})
  void retiredRaidGroupCannotActivateFromItsParentAlone(int floor,int id,int owner) {
    actor.getGameplay().getMainMissions().put(owner,new QuestState(owner));
    var group=enter(floor,id);
    assertFalse(actor.getMissionManager().shouldLoadGroup(group,actor.scene));
    actor.scene.loadGroup(id);assertFalse(actor.scene.getLoadedGroups().contains(id));
  }
  @Test void legacyRaidGroupKeepsItsExistingRule() {
    actor.wire=ProtocolBridge.forVersion("4.2.0");actor.getGameplay().getMainMissions().put(2021902,new QuestState(2021902));
    var group=enter(40234001,22);assertTrue(actor.getMissionManager().shouldLoadGroup(group,actor.scene));
  }
  @Test void authoredCurrentRaidStepKeepsItsGroupAndUnloadsWhenCancelled() {
    actor.getGameplay().getMainMissions().put(2021902,new QuestState(2021902));
    actor.getGameplay().getSubMissions().put(202190201,new QuestState(202190201));
    var group=enter(40234001,21);assertTrue(actor.getMissionManager().shouldLoadGroup(group,actor.scene));
    actor.getGameplay().getCancelledSubs().add(202190201);
    assertFalse(actor.getMissionManager().shouldLoadGroup(group,actor.scene));
  }
  private GroupInfo enter(int floorId,int id) {
    int plane=floorId/1000;var gson=ResourceLoader.getGson();
    var floor=gson.fromJson("{\"FloorID\":"+floorId+",\"GroupInstanceList\":[{\"ID\":"+id+"}],\"DimensionList\":[{\"GroupIndexList\":[0]}]}",FloorInfo.class);
    var group=gson.fromJson(sources.getAsJsonObject(floorId+":"+id),GroupInfo.class);group.setId(id);floor.registerGroup(0,group);floor.onLoad();
    GameData.getFloorInfos().put("P"+plane+"_F"+floorId,floor);
    var excel=gson.fromJson("{\"PlaneID\":"+plane+",\"PlaneType\":\"Raid\",\"WorldID\":301}",MazePlaneExcel.class);
    actor.scene=new Scene(actor,excel,floorId);return group;
  }
}
