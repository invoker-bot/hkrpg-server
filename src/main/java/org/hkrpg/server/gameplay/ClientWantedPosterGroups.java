package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.hkrpg.server.GameDiagnostics;

/** Seven source-verified client-owned wanted posters, with server-side mission logic retained. */
public final class ClientWantedPosterGroups {
  private static final int PLANE=10101, FLOOR=10101001, OWNER=4040109;
  private static final String AREA="LevelArea_P10101_F10101001_G";
  // Identity belongs to the loaded resource, not player state or a persisted GroupInfo field.
  private static final Map<GroupInfo,Integer> VERIFIED=Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<Integer,String> SOURCES=Map.of(
      147,"e9c17253f2a1352b1b64746b910fce4d7bec7025f76d0a256a72d87abba0236b",
      148,"ed6e0e6e4ceef96a6a4c19e4577e2100bda1c4c692ce4389bdc7a3afb9742955",
      149,"3b5e8224dd1b0fd424a9e4227ed975c1a3edeeb56bc419e8ed13badb032888c0",
      150,"03b93bd9b7a7a64009e14281769e14d41a2c192a63335ccf9ca3c68a2c57059b",
      151,"73a94f879deae9d07dbd4226af73feb30ed3b17f0bb977f6361ff7c55aa90468",
      152,"8bac4ef8ff404226ce270fe6a1f6f9875a735d645bed74363d9c578f957d10cf",
      153,"84ab8884636b150701fa275dd6ba4ef934f8030e118fc7b55e709ec19331d13d");

  static void markSource(GroupInfo group, JsonObject raw) {
    var area=raw.get("AreaAnchorName");
    if(area==null || !area.isJsonPrimitive() || !area.getAsJsonPrimitive().isString())return;
    for(var entry:SOURCES.entrySet())
      if((AREA+entry.getKey()).equals(area.getAsString())
          && entry.getValue().equals(TimelineActivityRules.fingerprint(raw))) {
        VERIFIED.put(group,entry.getKey());
        return;
      }
  }

  public static boolean clientGroup(Scene scene,int id) {
    if(!SOURCES.containsKey(id) || !NativeMissionGroups.supports(scene)
        || !ClientNpcGroups.supports(scene) || scene.getPlaneId()!=PLANE || scene.getFloorId()!=FLOOR)
      return false;
    var group=scene.getResourceGroups().get(id);
    if(group==null)return false;
    if(!Integer.valueOf(id).equals(VERIFIED.get(group)) || group.getOwnerMainMissionID()!=OWNER) {
      GameDiagnostics.outcome(scene.getPlayer().getSession(),"WantedPosterScene",id,
          Retcode.REQ_PARA_INVALID,"poster-source-unverified","owner="+group.getOwnerMainMissionID());
      return false;
    }
    return true;
  }

  private ClientWantedPosterGroups() {}
}
