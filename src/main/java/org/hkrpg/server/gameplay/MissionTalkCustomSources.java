package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;
import org.hkrpg.server.GameDiagnostics;

/** Literal client assignments are evidence for a talk value, never an automatic completion. */
final class MissionTalkCustomSources {
  private static final Gson GSON = ResourceLoader.getGson();
  // The NPC's original D performance is client-side and is not installed in server resources.
  // Its three assignments and this complete typed objective were checked against the pinned 4.6 source.
  private static final MissionInfo.SubMissionInfo SHUTUP = GSON.fromJson(
      """
      {"ID":802711102,"MainMissionID":8027111,"LevelPlaneID":10000,"LevelFloorID":10000003,
       "TakeType":"AnySequence","TakeParamIntList":[802711101,802711117],"TakeParamStrList":[],
       "FinishType":"Talk","ParamType":"Equal","ParamStr1":"Mission_802711102",
       "ParamIntList":[],"ParamItemList":[],"FinishActionList":[{"FinishActionType":"SetFloorSavedValue",
       "FinishActionPara":[],"FinishActionParaString":["10000","10000003","Connection_MuteStairs","0"]}],
       "Progress":1,"CustomValueList":[],"CustomValueReward":[
       {"Name":"OldValue_0","Value":1,"RewardID":2001383},
       {"Name":"OldValue_0","Value":2,"RewardID":2001384}],"GroupIDList":[172],
       "RequiredNPCSeriesIDList":[],"StoryLineIDList":[],"IgnoreVerseParamList":[]}
      """, MissionInfo.SubMissionInfo.class);
  private static final JsonElement SHUTUP_SHAPE = GSON.toJsonTree(SHUTUP);
  private static final MissionInfo.SubMissionInfo PHOTO = GSON.fromJson(
      """
      {"ID":802720103,"MainMissionID":8027201,"LevelPlaneID":10301,"LevelFloorID":10301001,
       "TakeType":"AnySequence","TakeParamIntList":[802720102],"FinishType":"Talk",
       "ParamType":"Equal","ParamStr1":"Mission_802720103","ParamIntList":[],"ParamItemList":[],
       "FinishActionList":[],"Progress":1,"CustomValueList":[],"CustomValueReward":[
       {"Name":"OldValue_0","Value":1,"RewardID":2001365},
       {"Name":"OldValue_0","Value":2,"RewardID":2001366}],"GroupIDList":[596],
       "RequiredNPCSeriesIDList":[],"StoryLineIDList":[],"IgnoreVerseParamList":[]}
      """, MissionInfo.SubMissionInfo.class);
  private static final JsonElement PHOTO_SHAPE = GSON.toJsonTree(PHOTO);
  private static final Map<MissionInfo.SubMissionInfo, Entry> CACHE = new IdentityHashMap<>();
  private static Path resourceRoot;

  private record Source(Path path, String type, String sha256) {}
  private record Entry(MissionInfo main, int floor, List<Integer> groups, String script,
      String definitions, Map<Integer, Set<Integer>> values) {}

  static synchronized void reset(Path root) {
    resourceRoot = root == null ? null : root.toAbsolutePath().normalize();
    CACHE.clear();
  }

  static synchronized boolean allows(Player player, MissionInfo.SubMissionInfo sub,
      MissionInfo main, MissionInfo.MissionCustomValueInfo definition, int value) {
    if (originalShutup(sub, main, definition, value)) return true;
    if (originalPhoto(sub, main, definition, value)) return true;
    if (resourceRoot == null) return false;
    var entry = CACHE.get(sub);
    var groups = sub.getGroupIDList() == null ? List.<Integer>of() : new ArrayList<>(sub.getGroupIDList());
    String definitions = GSON.toJson(main.getMissionCustomValueList());
    if (entry == null || entry.main() != main || entry.floor() != sub.getLevelFloorID()
        || !entry.groups().equals(groups) || !Objects.equals(entry.script(), sub.getMissionJsonPath())
        || !entry.definitions().equals(definitions)) {
      entry = new Entry(main, sub.getLevelFloorID(), groups, sub.getMissionJsonPath(), definitions,
          collect(player, sub, main));
      CACHE.put(sub, entry);
    }
    return entry.values().getOrDefault(definition.getIndex(), Set.of()).contains(value);
  }

  static boolean requiresInitialShutupChoice(MissionInfo.SubMissionInfo sub, MissionInfo main) {
    if (main == null || main.getMissionCustomValueList() == null) return false;
    return Arrays.stream(main.getMissionCustomValueList()).filter(Objects::nonNull)
        .anyMatch(definition -> originalShutup(sub, main, definition, 0));
  }

  private static boolean originalShutup(MissionInfo.SubMissionInfo sub, MissionInfo main,
      MissionInfo.MissionCustomValueInfo definition, int value) {
    return sub.getId() == 802711102 && main.getMainMissionID() == 8027111
        && SHUTUP_SHAPE.equals(GSON.toJsonTree(sub))
        && Objects.equals(definition.getIndex(), 0) && "OldValue_0".equals(definition.getName())
        && "Former".equals(definition.getType()) && !definition.isRange()
        && Arrays.equals(definition.getValidValueParamList(), new Integer[] {0, 1, 2})
        && value >= 0 && value <= 2;
  }

  private static boolean originalPhoto(MissionInfo.SubMissionInfo sub, MissionInfo main,
      MissionInfo.MissionCustomValueInfo definition, int value) {
    return sub.getId() == 802720103 && main.getMainMissionID() == 8027201
        && PHOTO_SHAPE.equals(GSON.toJsonTree(sub))
        && Objects.equals(definition.getIndex(), 0) && "OldValue_0".equals(definition.getName())
        && "Former".equals(definition.getType()) && !definition.isRange()
        && Arrays.equals(definition.getValidValueParamList(), new Integer[] {1, 2, 3})
        && (value == 1 || value == 2);
  }

  private static Map<Integer, Set<Integer>> collect(Player player, MissionInfo.SubMissionInfo sub,
      MissionInfo main) {
    var paths = new LinkedHashMap<Path, Source>();
    Path missionDir = resourceRoot.resolve("Config/Level/Mission/" + main.getMainMissionID()).normalize();
    String ownScript = sub.getMissionJsonPath();
    if (ownScript != null && !ownScript.isBlank()) {
      Path path = resourceRoot.resolve(ownScript).normalize();
      if (path.startsWith(missionDir)) paths.put(path, new Source(path, "Mission", null));
    }
    if (sub.getLevelFloorID() > 0 && sub.getGroupIDList() != null) {
      for (Integer group : sub.getGroupIDList()) {
        if (group == null || group <= 0) continue;
        int floor = sub.getLevelFloorID();
        Path path = resourceRoot.resolve("Config/Level/GroupGraph/F" + floor
            + "/Group_F" + floor + "_G" + group + ".json");
        paths.put(path, new Source(path, "Group", null));
      }
    }
    Path actDir = missionDir.resolve("Act");
    if (Files.isDirectory(actDir)) {
      try (Stream<Path> files = Files.list(actDir)) {
        files.filter(path -> path.getFileName().toString().matches("Act[0-9]+\\.json"))
            .sorted().forEach(path -> paths.put(path, new Source(path, null, null)));
      } catch (IOException e) {
        invalid(player, sub.getId());
      }
    }
    if (sub.getId() == 200120105 && main.getMainMissionID() == 2001201
        && sub.getLevelFloorID() == 10101003) {
      Path path = resourceRoot.resolve("Config/Level/Mission/2001101/Act/Act200110112.json");
      paths.put(path, new Source(path, null,
          "adbe40cdd0979a7b46a2f5829564dbbe3763a0aa0fe5851b5c0aa185c180a4be"));
    }
    if (sub.getId() == 434014702 && main.getMainMissionID() == 4340147
        && sub.getLevelFloorID() == 20412001) {
      Path path = resourceRoot.resolve("Config/Level/Mission/4340145/Act/Act434014507.json");
      paths.put(path, new Source(path, null,
          "bdf434f0ffd30406f473f3104139d86b0e3b81c78563cb982f1377e20e0bb121"));
    }
    var values = new HashMap<Integer, Set<Integer>>();
    for (var source : paths.values()) {
      Path path = source.path();
      if (!Files.isRegularFile(path)) continue;
      try {
        if (Files.size(path) > 8 * 1024 * 1024) {
          invalid(player, sub.getId());
          continue;
        }
        byte[] raw = Files.readAllBytes(path);
        if (source.sha256() != null && !source.sha256().equals(sha256(raw))) {
          invalid(player, sub.getId());
          continue;
        }
        var document = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
        if (!document.isJsonObject() || source.type() != null
            && (!document.getAsJsonObject().has("Type")
                || !document.getAsJsonObject().get("Type").isJsonPrimitive()
                || !source.type().equals(document.getAsJsonObject().get("Type").getAsString()))) {
          invalid(player, sub.getId());
          continue;
        }
        collectNodes(document, sub, main, values);
      } catch (IOException | JsonParseException | IllegalStateException e) {
        invalid(player, sub.getId());
      }
    }
    return values;
  }

  private static String sha256(byte[] raw) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
    catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }

  private static void collectNodes(JsonElement element, MissionInfo.SubMissionInfo sub,
      MissionInfo main, Map<Integer, Set<Integer>> values) {
    if (element.isJsonArray()) {
      for (var child : element.getAsJsonArray()) collectNodes(child, sub, main, values);
      return;
    }
    if (!element.isJsonObject()) return;
    var object = element.getAsJsonObject();
    if (object.has("$type") && object.get("$type").isJsonPrimitive()
        && "RPG.GameCore.SetMissionCustomValue".equals(object.get("$type").getAsString())
        && Objects.equals(integer(object.get("SubmissionID")), sub.getId())
        && object.has("MissionCustomValue") && object.get("MissionCustomValue").isJsonObject()) {
      var variable = object.getAsJsonObject("MissionCustomValue");
      Integer index = variable.has("Index") ? integer(variable.get("Index")) : 0;
      Integer value = object.has("CustomValue") ? integer(object.get("CustomValue")) : 0;
      if (index != null && value != null && index >= 0 && value >= 0
          && variable.has("Name") && variable.get("Name").isJsonPrimitive()
          && variable.get("Name").getAsJsonPrimitive().isString()
          && variable.has("ValidValueParamList") && variable.get("ValidValueParamList").isJsonArray()
          && main.getMissionCustomValueList() != null) {
        var definitions = Arrays.stream(main.getMissionCustomValueList())
            .filter(Objects::nonNull)
            .filter(d -> Objects.equals(d.getIndex(), index)
                && variable.get("Name").getAsString().equals(d.getName())
                && "Former".equals(d.getType()))
            .toList();
        var list = variable.getAsJsonArray("ValidValueParamList");
        var declared = new ArrayList<Integer>();
        for (var item : list) declared.add(integer(item));
        boolean validRange = !variable.has("isRange") || variable.get("isRange").isJsonPrimitive()
            && variable.get("isRange").getAsJsonPrimitive().isBoolean();
        boolean range = validRange && variable.has("isRange") && variable.get("isRange").getAsBoolean();
        if (validRange && definitions.size() == 1 && definitions.getFirst().getValidValueParamList() != null
            && definitions.getFirst().isRange() == range && !declared.contains(null)
            && declared.equals(Arrays.asList(definitions.getFirst().getValidValueParamList()))
            && (range ? declared.size() == 2 && value >= declared.get(0) && value <= declared.get(1)
                : declared.contains(value)))
          values.computeIfAbsent(index, ignored -> new HashSet<>()).add(value);
      }
    }
    for (var child : object.entrySet()) collectNodes(child.getValue(), sub, main, values);
  }

  private static Integer integer(JsonElement element) {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
      return null;
    try { return element.getAsBigDecimal().intValueExact(); }
    catch (ArithmeticException | NumberFormatException e) { return null; }
  }

  private static void invalid(Player player, int sub) {
    GameDiagnostics.outcome(player.getSession(), "MissionTalkCustomValue", sub,
        Retcode.MISSION_CUSTOM_VALUE_NOT_VALID, "invalid-source", "");
  }

  private MissionTalkCustomSources() {}
}
