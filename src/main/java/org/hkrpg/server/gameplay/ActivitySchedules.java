package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Shared parsing and atomic publication for operator activity windows. */
public final class ActivitySchedules {
  public static List<GameplayDefinitions.Window> parse(JsonObject root) {
    var result=new ArrayList<GameplayDefinitions.Window>();
    var seen=new HashSet<String>();
    var replays=new HashMap<String,String>();
    var panels=new HashMap<Integer,String>();
    if (!root.has("activities")) return List.of();
    var rows=root.getAsJsonArray("activities");
    if (rows.size()>10_000) throw new IllegalArgumentException("Too many activity windows");
    for (var raw : rows) {
      var row=raw.getAsJsonObject();
      int panel=Math.toIntExact(integer(row,"panelId")),module=Math.toIntExact(integer(row,"moduleId"));
      long begin=integer(row,"beginTime"),end=integer(row,"endTime");
      if (panel<=0 || module<=0 || begin<0 || end<=begin || !seen.add(panel+":"+module))
        throw new IllegalArgumentException("Invalid or duplicate activity window");
      if (row.has("disabled") && (!row.get("disabled").isJsonPrimitive()
          || !row.getAsJsonPrimitive("disabled").isBoolean())) throw new IllegalArgumentException("Invalid activity disabled flag");
      String replayId=null,replayKey=null;
      if(row.has("replayId")||row.has("replayKey")){
        replayId=string(row,"replayId");replayKey=string(row,"replayKey");
        if(!UUID.fromString(replayId).toString().equals(replayId)||!replayKey.matches("[1-4]\\.[0-9]-[a-z0-9][a-z0-9-]{0,95}"))
          throw new IllegalArgumentException("Invalid activity replay identity");
        var previous=replays.putIfAbsent(replayId,replayKey);
        if(previous!=null&&!previous.equals(replayKey))throw new IllegalArgumentException("Conflicting activity replay identity");
      }
      if (!row.has("disabled") || !row.get("disabled").getAsBoolean()){
        String identity=replayId==null?"legacy":replayId+":"+replayKey;
        String previous=panels.putIfAbsent(panel,identity);
        if(previous!=null&&!previous.equals(identity))throw new IllegalArgumentException("Mixed activity panel replay identity");
        result.add(new GameplayDefinitions.Window(panel,module,begin,end,replayId,replayKey));
      }
    }
    return List.copyOf(result);
  }

  private static long integer(JsonObject row,String field) {
    var value=row.get(field);
    if (value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Missing activity integer: "+field);
    return value.getAsBigDecimal().longValueExact();
  }

  private static String string(JsonObject row,String field){
    var value=row.get(field);
    if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Missing activity string: "+field);
    return value.getAsString();
  }

  public static synchronized void load(Path path) throws IOException {
    try {
      boolean exists=Files.exists(path,LinkOption.NOFOLLOW_LINKS);
      if(exists&&(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>2_000_000))
        throw new IOException("Invalid Gameplay46.json path or size");
      var root=exists ? JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
      GameplayDefinitions.replaceActivityWindows(parse(root));
    } catch (RuntimeException e) {throw new IOException("Invalid Gameplay46.json activity windows",e);}
  }
  private ActivitySchedules() {}
}
