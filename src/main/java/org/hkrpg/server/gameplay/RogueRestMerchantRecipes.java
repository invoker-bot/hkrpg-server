package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit server shop policy for the original G22 actor; never rewrites its enhancement graph. */
public final class RogueRestMerchantRecipes {
  static final String RULE = "hkrpg-first-world-rest-curio-v1";
  static final String COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b";
  static final String GROUP = "Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G22.json";
  static final String DIALOGUE = "Config/Level/RogueDialogue/RogueDialogueEvent/Act/Act403810002.json";
  static final String GROUP_SHA = "50aeab6d84c933dacdbac2ce2dac505448199170045cc43e72f66ce21ea4de59";
  static final String DIALOGUE_SHA = "fe0ecd2145033bfbc886a0d40cc0c21d6b1677deae00f0b59d22942eb6e21361";
  private static final Map<String, Integer> IDS = Map.ofEntries(
      Map.entry("area",110), Map.entry("map",101), Map.entry("site",6), Map.entry("room",306),
      Map.entry("entry",8000103), Map.entry("plane",80001), Map.entry("floor",80001003),
      Map.entry("group",22), Map.entry("npc",1013), Map.entry("instance",400001));
  private static final Map<String, String> SOURCE = Map.of("sourceCommit",COMMIT,"groupPath",GROUP,
      "groupNormalizedSHA256",GROUP_SHA,"dialoguePath",DIALOGUE,"dialogueNormalizedSHA256",DIALOGUE_SHA);
  public record Rule(int basePrice, int stockLimit) {}
  private record Loaded(Path root, Rule rule, GroupInfo group) {}
  private static volatile Loaded current;

  public static void load(Path resources, Path config) {
    current = null;
    if (!Files.isRegularFile(config)) return;
    try {
      if (Files.size(config) > 16384) throw new IllegalArgumentException("Oversized merchant recipe");
      var data = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
      if (!data.keySet().equals(Set.of("schema","enabled","ruleId","fragmentBasePrice","stockLimit","binding"))
          || integer(data,"schema") != 1 || !string(data,"ruleId").equals(RULE))
        throw new IllegalArgumentException("Unknown merchant recipe");
      var enabled = data.get("enabled");
      if (!enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
        throw new IllegalArgumentException("Expected a boolean merchant switch");
      int price = integer(data,"fragmentBasePrice"), limit = integer(data,"stockLimit");
      // The supported clock can add one quarter; advertised prices must remain in the shop range.
      if (price < 1 || price > 800000 || limit < 1 || limit > 3)
        throw new IllegalArgumentException("Invalid server merchant price or stock limit");
      var binding = data.getAsJsonObject("binding");
      var fields = new HashSet<>(IDS.keySet()); fields.addAll(SOURCE.keySet());
      if (binding == null || !binding.keySet().equals(fields))
        throw new IllegalArgumentException("Unknown merchant binding fields");
      for (var id : IDS.entrySet()) if (integer(binding,id.getKey()) != id.getValue())
        throw new IllegalArgumentException("Unknown merchant location");
      for (var source : SOURCE.entrySet()) if (!string(binding,source.getKey()).equals(source.getValue()))
        throw new IllegalArgumentException("Unknown merchant source");
      if (!enabled.getAsBoolean()) return;
      var root = resources.toAbsolutePath().normalize();
      if (!sourcesMatch(root)) return;
      var group = ResourceLoader.getGson().fromJson(Files.readString(root.resolve(GROUP),StandardCharsets.UTF_8),GroupInfo.class);
      group.setId(22);
      // Compare the complete group after the same existing post-load normalization as the scene.
      // This private one-group floor is only a normalization context; it is never registered as game data.
      var normalizer = ResourceLoader.getGson().fromJson(
          "{\"FloorID\":80001003,\"GroupInstanceList\":[{\"ID\":22}],\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[0]}]}",
          FloorInfo.class);
      normalizer.registerGroup(0,group);
      normalizer.onLoad();
      current = new Loaded(root,new Rule(price,limit),group);
    } catch (Exception malformed) {
      throw new IllegalStateException("Invalid RogueRestMerchants.json or merchant source",malformed);
    }
  }

  private static int integer(JsonObject data,String key) {
    var value = data.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected integer merchant field");
    return value.getAsBigDecimal().intValueExact();
  }

  private static String string(JsonObject data,String key) {
    var value = data.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected string merchant field");
    return value.getAsString();
  }

  static boolean sourcesMatch(Path root) {
    try {
      for (var source : Map.of(GROUP,GROUP_SHA,DIALOGUE,DIALOGUE_SHA).entrySet()) {
        var file = root.resolve(source.getKey()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > 1_000_000) return false;
        var normalized = Files.readString(file,StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
        if (!sha.equals(source.getValue())) return false;
      }
      return true;
    } catch (Exception unreadable) { return false; }
  }

  static Rule rule(Player player,EntityNpc npc,GroupInfo group,GameplayDefinitions.Room room,MapEntranceExcel entry) {
    var loaded = current;
    var run = player.getGameplay().getRogue();
    if (loaded == null || !sourcesMatch(loaded.root())
        || run.getAreaId() != 110 || run.getMapId() != 101 || run.getSiteId() != 6 || run.getRoomId() != 306
        || !room.equals(new GameplayDefinitions.Room(306,5,8000103,22,Map.of(22,0)))
        || entry.getId() != 8000103 || entry.getPlaneID() != 80001 || entry.getFloorID() != 80001003
        || group.getId() != 22 || npc.getGroupId() != 22 || npc.getNpcId() != 1013 || npc.getInstId() != 400001
        || !ResourceLoader.getGson().toJsonTree(group).equals(ResourceLoader.getGson().toJsonTree(loaded.group()))) return null;
    return loaded.rule();
  }

  private RogueRestMerchantRecipes() {}
}
