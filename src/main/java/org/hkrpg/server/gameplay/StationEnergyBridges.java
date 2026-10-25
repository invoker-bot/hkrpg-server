package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** The two original station consoles select mutually exclusive orange/blue repulsion bridges. */
public final class StationEnergyBridges {
  private static final String ROOT = "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_";
  private static final String GRAPH = ROOT + "Console02_EnergyBridge_01.json";
  private static final Set<Integer> CONTROLS = Set.of(300002,300015);
  private static final Set<Integer> PARTS = Set.of(300002,300015,300003,300004);

  public static boolean controller(PropInfo info) {
    return info != null && info.getPropID() == 100014 && GRAPH.equals(info.getInitLevelGraph());
  }

  static boolean applies(EntityProp prop) {
    return prop != null && prop.getScene().getPlaneId() == 20003
        && prop.getScene().getFloorId() == 20003001 && prop.getGroupId() == 4 && PARTS.contains(prop.getInstId());
  }

  private static boolean binding(PropInfo info, String key, String expected) {
    if (info.getValueSource() == null || info.getValueSource().getValues() == null) return false;
    try {
      var values=info.getValueSource().getValues().stream()
          .filter(v->v != null && v.has("Key") && key.equals(v.get("Key").getAsString())).toList();
      return values.size()==1 && values.getFirst().has("Value")
          && expected.equals(values.getFirst().get("Value").getAsString());
    } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException invalid) {
      return false;
    }
  }

  private static Map<Integer,EntityProp> actors(Scene scene) {
    var group=scene.getResourceGroups().get(4);
    if (group == null || !scene.getLoadedGroups().contains(4)) return Map.of();
    var result=new HashMap<Integer,EntityProp>();
    for (int id:PARTS) {
      var definitions=group.getPropList().stream().filter(p->p.getID()==id).toList();
      var entities=scene.getEntitiesByGroup(EntityProp.class,4).stream().filter(p->p.getInstId()==id).toList();
      if (definitions.size()!=1 || entities.size()!=1) return Map.of();
      var info=definitions.getFirst(); var entity=entities.getFirst();
      if (info.isIsDelete() || info.isIsClientOnly() || !info.isLoadOnInitial()
          || entity.getScene()!=scene || entity.getPropInfo()!=info || entity.getPropId()!=info.getPropID()) return Map.of();
      if (CONTROLS.contains(id)) {
        if (!controller(info) || info.getState()!=PropState.Closed
            || !binding(info,"FlipBridge01","4,300003") || !binding(info,"FlipBridge02","4,300004")
            || !binding(info,"Controller02","4,"+(id==300002?300015:300002))) return Map.of();
      } else if (info.getPropID()!=(id==300003?100011:100012)
          || ! (ROOT+"EnergyBridge_01.json").equals(info.getInitLevelGraph())
          || info.getState()!=(id==300003?PropState.Open:PropState.Closed)) return Map.of();
      if (!entity.getExcel().getPropStateList().containsAll(List.of(PropState.Open,PropState.Closed))) return Map.of();
      result.put(id,entity);
    }
    return result;
  }

  static Retcode validate(EntityProp prop,int interaction) {
    if (!applies(prop)) return Retcode.SUCC;
    if (!CONTROLS.contains(prop.getInstId()) || interaction!=1010 && interaction!=1100)
      return Retcode.INTERACT_TYPE_INVALID;
    if (!StoryDoors.world(prop.getScene()) || actors(prop.getScene()).size()!=4)
      return Retcode.MISSION_SCENE_NOT_MATCH;
    return Retcode.SUCC;
  }

  static void transitions(EntityProp prop,int interaction,Map<EntityProp,PropState> targets) {
    if (!applies(prop)) return;
    var actors=actors(prop.getScene());
    if (actors.size()!=4) throw new IllegalStateException("Repulsion bridge binding changed");
    var state=GameData.getInteractExcelMap().get(interaction).getTargetState();
    for (int id:CONTROLS) targets.put(actors.get(id),state);
    targets.put(actors.get(300003),state==PropState.Open?PropState.Closed:PropState.Open);
    targets.put(actors.get(300004),state);
  }

  private StationEnergyBridges() {}
}
