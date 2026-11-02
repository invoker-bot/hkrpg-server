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

  GmActivities(Path directory,List<GmActivityCatalog.Template> catalog,Clock clock){this.directory=directory;this.catalog=List.copyOf(catalog);this.clock=clock;}

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
    if (!template.manageable()||!GameData.getActivityPanelExcelMap().containsKey(template.panelId()))
      throw new GmError(409,template.note());
    synchronized(ActivitySchedules.class){
      var stored=read();var root=stored.root().deepCopy();
      if (!root.has("activities"))root.add("activities",new JsonArray());
      var rows=root.getAsJsonArray("activities");var existing=index(root);
      long now=clock.instant().getEpochSecond();
      long begin=operation.beginTime()==null?now:operation.beginTime();
      long end=operation.endTime()==null?now+(operation.durationDays()==null?30:operation.durationDays())*86400L:operation.endTime();
      if (operation.action().equals("activity-open")&&(begin<0||end<=begin||end<=now||end>0xffff_ffffL||end-begin>365L*86400))
        throw new GmError(400,"开放窗口需在协议时间范围内，结束晚于现在，时长为 1 秒至 365 天。");
      if (!operation.action().equals("activity-open")) {
        if (operation.action().equals("activity-enable")&&template.modules().stream().anyMatch(id->{
          var row=existing.get(template.panelId()+":"+id);return row==null||row.get("endTime").getAsLong()<=now;}))
          throw new GmError(409,"原模块排期缺失或已到期，请设置新的开放时间。");
        if (template.modules().stream().noneMatch(id->existing.containsKey(template.panelId()+":"+id)))
          throw new GmError(409,"活动尚未配置开放窗口。");
      }
      for (int module:template.modules()) {
        var row=existing.get(template.panelId()+":"+module);
        if (row==null) {
          if (!operation.action().equals("activity-open"))continue;
          row=new JsonObject();row.addProperty("panelId",template.panelId());row.addProperty("moduleId",module);rows.add(row);
        }
        if (operation.action().equals("activity-open")){row.addProperty("beginTime",begin);row.addProperty("endTime",end);}
        if (operation.action().equals("activity-stop"))row.addProperty("disabled",true);else row.remove("disabled");
      }
      var candidate=parse(root);
      if (!root.equals(stored.root()))save(root,stored.original(),operation.requestId());
      GameplayDefinitions.replaceActivityWindows(candidate);
      return new Result("completed",List.of((operation.action().equals("activity-stop")?"已关闭 ":operation.action().equals("activity-enable")?"已恢复启用 ":"已设置开放窗口 ")
          +template.name()+" #"+template.id()+"，共 "+template.modules().size()+" 个模块，全服生效。",
          "玩家进度、已领奖记录和其他活动排期保留。请重新打开游戏活动页面。"));
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
