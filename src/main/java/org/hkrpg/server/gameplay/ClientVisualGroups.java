package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.scene.Scene;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fingerprint-verified local visuals; server interaction, combat and stateful families stay published. */
public final class ClientVisualGroups {
  private record Source(int index,int floor,int group,int owner,String area,String fingerprint) {}
  private record Catalog(List<Source> rows,Map<String,Source> areas) {}

  private static class Sources {
    static final Catalog VALUE=load();
    private static Catalog load() {
      try(var reader=new InputStreamReader(Objects.requireNonNull(ClientVisualGroups.class.getResourceAsStream(
          "/scene/client-visual-groups-4.6.json")),StandardCharsets.UTF_8)) {
        var document=JsonParser.parseReader(reader).getAsJsonObject();
        if (document.get("schema").getAsInt()!=1
            || !document.get("indexSHA256").getAsString().equals("bcac71ed6783dfd447de127bdcab7c40ae4e21d488c39cba1af6ef6aa801b608"))
          throw new IllegalStateException("Invalid native visual source catalog");
        var rows=new ArrayList<Source>();var areas=new HashMap<String,Source>();
        for(var value:document.getAsJsonArray("groups")) {
          var row=value.getAsJsonArray();
          if(row.size()!=5)throw new IllegalStateException("Invalid native visual source row");
          int floor=row.get(0).getAsInt(),group=row.get(1).getAsInt(),owner=row.get(2).getAsInt();
          String area="LevelArea_P"+(floor/1000)+"_F"+floor+"_G"+group;
          String fingerprint=row.get(4).getAsString();
          if(floor<=0 || group<=0 || owner<0 || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Invalid native visual source identifiers");
          var source=new Source(rows.size()+1,floor,group,owner,area,fingerprint);
          if(areas.put(area,source)!=null)throw new IllegalStateException("Duplicate native visual source");
          rows.add(source);
        }
        if(rows.size()!=8656)throw new IllegalStateException("Incomplete native visual source catalog");
        return new Catalog(List.copyOf(rows),Map.copyOf(areas));
      } catch(java.io.IOException invalid) {throw new IllegalStateException("Cannot read native visual sources",invalid);}
    }
  }

  static void markSource(GroupInfo group, JsonObject raw) {
    var area=raw.get("AreaAnchorName");
    if(area==null || !area.isJsonPrimitive() || !area.getAsJsonPrimitive().isString())return;
    var source=Sources.VALUE.areas().get(area.getAsString());
    if(source!=null && source.fingerprint().equals(TimelineActivityRules.fingerprint(raw)))
      group.setClientVisualSource(source.index());
  }

  public static boolean clientGroup(Scene scene,int id) {
    if(!NativeMissionGroups.supports(scene))return false;
    var group=scene.getResourceGroups().get(id);
    if(group==null || group.getClientVisualSource()<=0 || group.getClientVisualSource()>Sources.VALUE.rows().size())return false;
    var source=Sources.VALUE.rows().get(group.getClientVisualSource()-1);
    return source.floor()==scene.getFloorId() && source.group()==id
        && scene.getPlaneId()==source.floor()/1000 && source.owner()==group.getOwnerMainMissionID();
  }

  private ClientVisualGroups() {}
}
