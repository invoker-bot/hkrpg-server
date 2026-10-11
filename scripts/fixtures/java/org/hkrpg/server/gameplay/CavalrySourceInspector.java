package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Hash the whole original source with the actual runtime canonicalizer; no player mutations. */
public final class CavalrySourceInspector {
  public static void main(String[] args) throws Exception {
    var fixture = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
    var rows = new TreeMap<String, String>();
    var files = new TreeMap<String, String>();
    var differences = new ArrayList<String>();
    var keys = Map.ofEntries(Map.entry("QuestData", "QuestID"), Map.entry("FinishWay", "ID"),
        Map.entry("CycleQuest", "CycleID"), Map.entry("QuestTimeLimitConfig", "QuestID"),
        Map.entry("FuncUnlockData", "UnlockID"), Map.entry("ActivityPanel", "PanelID"),
        Map.entry("ActivityConfig", "ActivityID"), Map.entry("RewardData", "RewardID"),
        Map.entry("ItemConfig", "ID"), Map.entry("MainMission", "MainMissionID"),
        Map.entry("NPCData", "ID"), Map.entry("MazePlane", "PlaneID"), Map.entry("MapEntrance", "ID"));
    for (var entry : fixture.getAsJsonObject("records").entrySet()) {
      String table = Path.of(entry.getKey()).getFileName().toString().replace(".json", "");
      for (var raw : entry.getValue().getAsJsonArray()) {
        var row = raw.getAsJsonObject();
        String key = entry.getKey() + ":" + row.get(keys.get(table)).getAsString();
        String fingerprint = TimelineActivityRules.fingerprint(row);
        if (rows.put(key, fingerprint) != null) throw new IllegalArgumentException("Duplicate original cavalry row");
        if (!fingerprint.equals(fixture.getAsJsonObject("recordFingerprints").get(key).getAsString())) differences.add(key);
      }
    }
    for (String section : List.of("groups", "floors", "headers"))
      for (var entry : fixture.getAsJsonObject(section).entrySet()) {
        String fingerprint = TimelineActivityRules.fingerprint(entry.getValue());
        if (files.put(entry.getKey(), fingerprint) != null) throw new IllegalArgumentException("Duplicate original cavalry file");
        if (!fingerprint.equals(fixture.getAsJsonObject("fileFingerprints").get(entry.getKey()).getAsString())) differences.add(entry.getKey());
      }
    var result = new TreeMap<String, Object>();
    result.put("passed", differences.isEmpty());
    result.put("rows", rows);
    result.put("files", files);
    result.put("checks", rows.size() + files.size());
    result.put("pythonHashDifferences", differences);
    Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    System.out.println(new Gson().toJson(Map.of("passed", differences.isEmpty(), "checks", rows.size() + files.size())));
    if (!differences.isEmpty()) throw new IllegalStateException("Different canonical cavalry source fingerprints");
  }
}
