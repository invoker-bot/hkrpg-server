package org.hkrpg.server.gm;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;

/** Published event editions are distinct from raw resource rows and from rerun instances. */
final class GmActivityHistory {
  record Entry(String key,String version,String phase,int phaseIndex,String name,
      List<String> categories,List<String> tags,String historicalStart,String historicalEnd,
      String sourceUrl,int activityId,int panelId,List<Integer> modules,String family,String resourceStatus,String mappingEvidence) {}
  private static final List<Entry> ENTRIES=read();
  static List<Entry> entries(){return ENTRIES;}
  static boolean supportedFamily(String family){return Set.of("login","material-submit").contains(family);}
  static Entry entry(String key){return ENTRIES.stream().filter(e->e.key().equals(key)).findFirst()
      .orElseThrow(()->new GmError(400,"历史活动不在 1.0–4.6 档案中。"));}
  static List<String> versions(){return ENTRIES.stream().map(Entry::version).distinct().sorted(Comparator
      .comparingInt((String v)->Integer.parseInt(v.split("\\.")[0])).thenComparingInt(v->Integer.parseInt(v.split("\\.")[1]))).toList();}

  static List<HistoricalActivityRow> current(GmActivities manager){
    synchronized(org.hkrpg.server.gameplay.ActivitySchedules.class){
    var current=manager.current().stream().collect(java.util.stream.Collectors.toMap(ActivityRow::id,r->r));
    var instances=manager.instances();long now=manager.now();
    return ENTRIES.stream().map(e->{
      var live=current.get(e.activityId());
      boolean ready=supportedFamily(e.family())&&live!=null&&emu.lunarcore.data.GameData.getActivityPanelExcelMap().containsKey(e.panelId())&&e.panelId()==live.panelId()
          &&e.modules().equals(live.modules().stream().map(ActivityModuleRow::id).toList());
      var identities=e.modules().stream().map(id->instances.get(e.panelId()+":"+id)).distinct().toList();
      var instance=identities.size()==1?identities.getFirst():null;
      boolean own=instance!=null&&e.key().equals(instance.key());
      String state=own?live.state():"unconfigured";
      String note=e.family().equals("excluded")?e.mappingEvidence():ready?"每次复刻建立独立活动进度和领奖记录，保留原存档。相同模块的原排期会被替换。"
          :e.activityId()==0?e.mappingEvidence():"该活动的进入、完成与独立周期仍需接通，尚不能一键复刻。";
      return new HistoricalActivityRow(e.key(),e.version(),e.phase(),e.phaseIndex(),e.name(),e.categories(),e.tags(),
          e.activityId(),e.panelId(),e.modules(),e.family(),e.resourceStatus(),e.historicalStart(),e.historicalEnd(),e.sourceUrl(),
          state,own?live.status():"未配置本期复刻",own?live.beginTime():0,own?live.endTime():0,
          own?instance.id():"",ready,ready&&own&&live.modules().stream().anyMatch(m->m.configured()&&!m.disabled()&&m.endTime()>now),
          ready&&own&&live.modules().stream().allMatch(m->m.configured()&&m.endTime()>now)&&live.modules().stream().anyMatch(ActivityModuleRow::disabled),note);
    }).toList();
    }
  }

  static Result rerun(GmActivities manager,Operation operation,List<GmActivityCatalog.Template> catalog){
    GmValidation.validate(operation);var event=entry(operation.activityKey());
    if(!supportedFamily(event.family()))throw new GmError(409,"该活动尚未通过实际进入、完成和独立周期校验。");
    var target=catalog.stream().filter(t->t.id()==event.activityId()).findFirst()
        .orElseThrow(()->new GmError(409,"当前资源缺少此历史活动。"));
    if(target.panelId()!=event.panelId()||!target.modules().equals(event.modules()))
      throw new GmError(409,"当前资源已变化，历史面板/模块对应不符。");
    // A known shared login module is replaced as one unit and progresses in the new namespace.
    return manager.rerun(operation,target,event.key(),event.name());
  }

  private static List<Entry> read(){
    try(var stream=GmActivityHistory.class.getResourceAsStream("/gm/activity-history.json")){
      if(stream==null)throw new IOException("Missing historical activity archive");
      var root=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
      if(root.get("schemaVersion").getAsInt()!=1)throw new IOException("Unknown activity archive");
      var rows=new Gson().fromJson(root.get("events"),Entry[].class);var keys=new HashSet<String>();
      for(var row:rows)if(row==null||row.key()==null||!keys.add(row.key())||row.name()==null||row.modules()==null
          ||row.categories()==null||row.tags()==null||row.sourceUrl()==null||row.phaseIndex()<1)
        throw new IOException("Invalid historical activity identity");
      return List.of(rows);
    }catch(IOException|RuntimeException e){throw new IllegalStateException("Invalid historical activity archive",e);}
  }
  private GmActivityHistory(){}
}
