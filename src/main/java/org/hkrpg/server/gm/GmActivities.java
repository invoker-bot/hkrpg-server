package org.hkrpg.server.gm;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.hkrpg.server.gm.GmModels.*;

/** Full-file validation, backup and atomic activity schedule publication; no player writes. */
final class GmActivities {
  private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
  private final Path directory;
  private final List<GmActivityCatalog.Template> catalog;
  private final Clock clock;
  private record Stored(JsonObject root,String original){}
  record ReplayIdentity(String id,String key){}

  GmActivities(Path directory,List<GmActivityCatalog.Template> catalog,Clock clock){this.directory=directory;this.catalog=List.copyOf(catalog);this.clock=clock;}
  long now(){return clock.instant().getEpochSecond();}

  Map<String,ReplayIdentity> instances(){
    synchronized(ActivitySchedules.class){
      var result=new HashMap<String,ReplayIdentity>();
      for(var entry:index(read().root()).entrySet()){
        var row=entry.getValue();
        if(row.has("replayId"))result.put(entry.getKey(),new ReplayIdentity(row.get("replayId").getAsString(),row.get("replayKey").getAsString()));
      }
      return Map.copyOf(result);
    }
  }

  List<ActivityRow> current(){
    synchronized(ActivitySchedules.class){
      var stored=read();var configured=index(stored.root());long now=clock.instant().getEpochSecond();
      return catalog.stream().map(t->{
        var modules=t.modules().stream().map(id->{
          var row=configured.get(t.panelId()+":"+id);boolean present=row!=null;
          boolean disabled=present&&row.has("disabled")&&row.get("disabled").getAsBoolean();
          long begin=present?row.get("beginTime").getAsLong():0,end=present?row.get("endTime").getAsLong():0;
          String state=!present?"unconfigured":disabled?"stopped":now<begin?"scheduled":now>=end?"expired":"active";
          return new ActivityModuleRow(id,t.resident().contains(id),present,disabled,state,begin,end);
        }).toList();
        String state=modules.isEmpty()?"unconfigured":modules.stream().map(m->m.state()+":"+m.beginTime()+":"+m.endTime()).distinct().count()==1
            ? modules.getFirst().state():"mixed";
        boolean manage=t.manageable()&&GameData.getActivityPanelExcelMap().containsKey(t.panelId());
        boolean enable=manage&&!modules.isEmpty()&&modules.stream().allMatch(m->m.configured()&&m.endTime()>now)
            &&modules.stream().anyMatch(ActivityModuleRow::disabled);
        boolean stop=manage&&modules.stream().anyMatch(m->m.configured()&&!m.disabled()&&m.endTime()>now);
        return new ActivityRow(t.id(),t.panelId(),t.name(),t.type(),modules,state,switch(state){
          case "active"->"正在开放";case "scheduled"->"尚未开放";case "expired"->"已到期";case "stopped"->"已关闭";case "mixed"->"模块排期不同";default->"未配置";
        },modules.isEmpty()||state.equals("mixed")?0:modules.getFirst().beginTime(),
            modules.isEmpty()||state.equals("mixed")?0:modules.getFirst().endTime(),manage,enable,stop,t.note(),t.unlockConditions());
      }).toList();
    }
  }

  Result update(Operation operation){
    GmValidation.validate(operation);
    if(!Set.of("activity-open","activity-stop","activity-enable").contains(operation.action()))
      throw new GmError(400,"未知活动排期操作。");
    var template=catalog.stream().filter(t->t.id()==operation.activityId()).findFirst()
        .orElseThrow(()->new GmError(400,"活动不在当前资源目录中。"));
    boolean scoped=operation.activityKey()!=null&&Set.of("activity-stop","activity-enable").contains(operation.action());
    if(scoped){
      var entry=GmActivityHistory.entry(operation.activityKey());
      if(!GmActivityHistory.supportedFamily(entry.family())||entry.activityId()!=template.id()||entry.panelId()!=template.panelId()||!entry.modules().equals(template.modules()))
        throw new GmError(409,"历史期次与当前活动不匹配。");
    }
    if ((!template.manageable()&&!scoped)||!GameData.getActivityPanelExcelMap().containsKey(template.panelId()))
      throw new GmError(409,template.note());
    return change(operation,template,false,null,template.name());
  }

  Result rerun(Operation operation,GmActivityCatalog.Template template,String key,String name){
    GmValidation.validate(operation);
    if(!operation.action().equals("activity-rerun")||!Objects.equals(key,operation.activityKey())||template.modules().isEmpty()
        ||!GameData.getActivityPanelExcelMap().containsKey(template.panelId()))throw new GmError(409,"历史活动资源不可用。");
    return change(operation,template,true,key,name);
  }

  private Result change(Operation operation,GmActivityCatalog.Template template,boolean rerun,String key,String name){
    synchronized(ActivitySchedules.class){
      var stored=read();var root=stored.root().deepCopy();
      if (!root.has("activities"))root.add("activities",new JsonArray());
      var rows=root.getAsJsonArray("activities");var existing=index(root);
      if(!rerun&&operation.activityKey()!=null&&template.modules().stream().anyMatch(module->{
        var row=existing.get(template.panelId()+":"+module);
        return row==null||!row.has("replayKey")||!operation.activityKey().equals(row.get("replayKey").getAsString());
      }))throw new GmError(409,"此入口已经切换到其他活动期次，请刷新后再操作。");
      boolean open=rerun||operation.action().equals("activity-open");
      long now=clock.instant().getEpochSecond();
      long begin=operation.beginTime()==null?now:operation.beginTime();
      long end=operation.endTime()==null?now+(operation.durationDays()==null?30:operation.durationDays())*86400L:operation.endTime();
      if (open&&(begin<0||end<=begin||end<=now||end>0xffff_ffffL||end-begin>365L*86400))
        throw new GmError(400,"开放窗口需在协议时间范围内，结束晚于现在，时长为 1 秒至 365 天。");
      if (!open) {
        if (operation.action().equals("activity-enable")&&template.modules().stream().anyMatch(id->{
          var row=existing.get(template.panelId()+":"+id);return row==null||row.get("endTime").getAsLong()<=now;}))
          throw new GmError(409,"原模块排期缺失或已到期，请设置新的开放时间。");
        if (template.modules().stream().noneMatch(id->existing.containsKey(template.panelId()+":"+id)))
          throw new GmError(409,"活动尚未配置开放窗口。");
      }
      if(rerun){
        if(!root.has("activityReplayRuns"))root.add("activityReplayRuns",new JsonArray());
        if(!root.get("activityReplayRuns").isJsonArray()||root.getAsJsonArray("activityReplayRuns").size()>=10_000)
          throw new GmError(503,"活动复刻档案无效或已达到本服保存上限。");
        var archive=root.getAsJsonArray("activityReplayRuns");
        for(var previous:archive)if(!previous.isJsonObject()||!previous.getAsJsonObject().has("id"))
          throw new GmError(503,"活动复刻档案存在损坏记录。");
        for(var previous:archive)if(operation.requestId().equals(previous.getAsJsonObject().get("id").getAsString()))
          throw new GmError(409,"该复刻周期已存在，请核对原操作记录。");
        for(var previous:existing.values())if(template.modules().contains(previous.get("moduleId").getAsInt()))previous.addProperty("disabled",true);
        var instance=new JsonObject();instance.addProperty("id",operation.requestId());instance.addProperty("key",key);
        instance.addProperty("name",name);instance.addProperty("activityId",template.id());instance.addProperty("panelId",template.panelId());
        instance.addProperty("beginTime",begin);instance.addProperty("endTime",end);archive.add(instance);
      }
      if(operation.action().equals("activity-enable")&&operation.activityKey()!=null)
        for(var previous:existing.values())if(template.modules().contains(previous.get("moduleId").getAsInt())
            &&previous.get("panelId").getAsInt()!=template.panelId())previous.addProperty("disabled",true);
      for (int module:template.modules()) {
        var row=existing.get(template.panelId()+":"+module);
        if (row==null) {
          if (!open)continue;
          row=new JsonObject();row.addProperty("panelId",template.panelId());row.addProperty("moduleId",module);rows.add(row);
        }
        if (open){row.addProperty("beginTime",begin);row.addProperty("endTime",end);}
        if(rerun){row.addProperty("replayId",operation.requestId());row.addProperty("replayKey",key);}
        if (operation.action().equals("activity-stop"))row.addProperty("disabled",true);else row.remove("disabled");
      }
      var candidate=parse(root);
      if (!root.equals(stored.root()))save(root,stored.original(),operation.requestId());
      GameplayDefinitions.replaceActivityWindows(candidate);
      return new Result("completed",List.of((operation.action().equals("activity-stop")?"已关闭 ":operation.action().equals("activity-enable")?"已恢复启用 ":"已设置开放窗口 ")
          +name+" #"+template.id()+"，共 "+template.modules().size()+" 个模块，全服生效。",
          rerun?"本轮采用独立进度与领奖记录；原存档保留。周期 "+operation.requestId():"玩家进度、已领奖记录和其他活动排期保留。请重新打开游戏活动页面。"));
    }
  }

  private Stored read(){
    var path=directory.resolve("Gameplay46.json");
    try {
      if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
        var root=new JsonObject();root.add("activities",new JsonArray());return new Stored(root,JSON.toJson(root)+"\n");
      }
      if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>2_000_000)throw new IOException("Invalid schedule path");
      var original=Files.readString(path,StandardCharsets.UTF_8);var root=JsonParser.parseString(original).getAsJsonObject();parse(root);
      return new Stored(root,original);
    }catch(IOException|RuntimeException e){throw new GmError(503,"当前活动排期不可读取或存在无效/重复模块，未执行修改。");}
  }
  private static List<GameplayDefinitions.Window> parse(JsonObject root){
    try{return ActivitySchedules.parse(root);}catch(RuntimeException e){throw new GmError(503,"活动排期无效，保留上一份运行配置。");}
  }
  private static Map<String,JsonObject> index(JsonObject root){
    var result=new HashMap<String,JsonObject>();
    if(root.has("activities"))for(var raw:root.getAsJsonArray("activities")){
      var row=raw.getAsJsonObject();result.put(row.get("panelId").getAsInt()+":"+row.get("moduleId").getAsInt(),row);
    }
    return result;
  }
  private void save(JsonObject root,String original,String requestId){
    Path temp=null;
    try{
      Files.createDirectories(directory);var backups=directory.resolve("gm-activity-backups");Files.createDirectories(backups);
      if(!Files.isDirectory(backups,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid backup path");
      Files.writeString(backups.resolve("Gameplay46-"+requestId+".json"),original,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
      temp=Files.createTempFile(directory,".gm-activities-",".tmp");
      try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){
        var bytes=ByteBuffer.wrap((JSON.toJson(root)+"\n").getBytes(StandardCharsets.UTF_8));
        while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
      }
      Files.move(temp,directory.resolve("Gameplay46.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temp=null;
    }catch(IOException e){throw new GmError(503,"活动排期备份或保存失败，本次变更未生效。");}
    finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
  }
}
