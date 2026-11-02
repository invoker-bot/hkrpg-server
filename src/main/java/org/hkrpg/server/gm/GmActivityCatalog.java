package org.hkrpg.server.gm;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Only explicit ActivityConfig panel/module relations establish manageable activities. */
final class GmActivityCatalog {
  record Template(int id,int panelId,String name,String type,List<Integer> modules,
      Set<Integer> resident,boolean manageable,String note,String unlockConditions) {}

  static List<Template> load(Path resources) throws IOException {
    var panels=new HashMap<Integer,JsonObject>();
    for (var raw : rows(resources,"ActivityPanel")) {
      var row=raw.getAsJsonObject();panels.put(positive(row,"PanelID"),row);
    }
    var names=new HashMap<String,String>();var hashes=new HashSet<String>();
    for (var row : panels.values()) for (var field : List.of("TitleName","TabName"))
      if (row.has(field)) hashes.add(row.getAsJsonObject(field).get("Hash").getAsString());
    var textMap=resources.resolve("TextMap/TextMapCHS.json");
    if (Files.isRegularFile(textMap)) try (var reader=new JsonReader(Files.newBufferedReader(textMap,StandardCharsets.UTF_8))) {
      reader.beginObject();
      while (reader.hasNext()) {var key=reader.nextName();if(hashes.contains(key))names.put(key,reader.nextString());else reader.skipValue();}
      reader.endObject();
    }
    var templates=new ArrayList<Template>();var ids=new HashSet<Integer>();
    var owners=new HashMap<Integer,Set<Integer>>();
    for (var raw : rows(resources,"ActivityConfig")) {
      var row=raw.getAsJsonObject();int id=positive(row,"ActivityID");
      if (!ids.add(id)) throw new IOException("Duplicate activity ID");
      int panelId=row.has("ActivityPanelID") ? positive(row,"ActivityPanelID") : 0;
      var panel=panels.get(panelId);var resident=modules(row,"ResidentModuleList");
      var event=modules(row,"ActivityModuleIDList");var all=new TreeSet<Integer>(resident);all.addAll(event);
      boolean trial=panel!=null && "UI/Quest/Widget/RoleTrialPanel.prefab".equals(text(panel,"UIPrefab"));
      String name="活动 #"+id;
      if (panel!=null) for (var field : List.of("TitleName","TabName")) {
        if (!panel.has(field)) continue;
        var value=names.get(panel.getAsJsonObject(field).get("Hash").getAsString());
        if (value!=null&&!value.isBlank()) {name=value.replaceAll("<[^>]*>","").replace("\\n"," ");break;}
      }
      boolean manageable=panel!=null&&!all.isEmpty()&&!trial;
      String note=panel==null ? "缺少明确的原始活动面板关联，仅供查询。" : trial ? "角色试用跟随卡池开放，请在卡池管理中调整。"
          : all.isEmpty() ? "没有可核对的活动模块，仅供查询。" : "排期仅控制已实现入口；原始解锁条件、玩家进度和已领奖记录保留。";
      templates.add(new Template(id,panelId,name,panel==null?"unmapped":trial?"trial":event.isEmpty()?"resident":"event",
          List.copyOf(all),Set.copyOf(resident),manageable,note,panel==null?"":text(panel,"UnlockConditions")));
      if (panel!=null) for (int module : all) owners.computeIfAbsent(module,k->new HashSet<>()).add(id);
    }
    // Existing gameplay consumers (including login rewards) gate by module, even across panels.
    return templates.stream().map(t -> t.manageable()&&t.modules().stream().anyMatch(m -> owners.getOrDefault(m,Set.of()).size()>1)
        ? new Template(t.id(),t.panelId(),t.name(),t.type(),t.modules(),t.resident(),false,
            "与其他活动共享模块，现有玩法无法独立启停，仅供查询。",t.unlockConditions()) : t)
        .sorted(Comparator.comparingInt(Template::id)).toList();
  }

  private static Set<Integer> modules(JsonObject row,String field) {
    var result=new TreeSet<Integer>();
    if (row.has(field)) for(var raw:row.getAsJsonArray(field)) {
      int id=raw.getAsBigDecimal().intValueExact();
      if (id<=0||!result.add(id)) throw new IllegalArgumentException("Invalid activity module");
    }
    return result;
  }
  private static String text(JsonObject row,String field){return row.has(field)?row.get(field).getAsString():"";}
  private static int positive(JsonObject row,String field){int id=row.get(field).getAsBigDecimal().intValueExact();if(id<=0)throw new IllegalArgumentException("Invalid activity ID");return id;}
  private static JsonArray rows(Path root,String table) throws IOException {
    var path=root.resolve("ExcelOutput/"+table+".json");
    if (!Files.isRegularFile(path)||Files.size(path)>5_000_000)throw new IOException("Missing or invalid activity table: "+table);
    return JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonArray();
  }
  private GmActivityCatalog(){}
}
