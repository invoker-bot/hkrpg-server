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
      if (!row.has("disabled") || !row.get("disabled").getAsBoolean())
        result.add(new GameplayDefinitions.Window(panel,module,begin,end));
    }
    return List.copyOf(result);
  }

  private static long integer(JsonObject row,String field) {
    var value=row.get(field);
    if (value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Missing activity integer: "+field);
    return value.getAsBigDecimal().longValueExact();
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
