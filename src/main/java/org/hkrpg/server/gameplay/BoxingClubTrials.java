package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** First-season source trials plus their independently scheduled availability. */
final class BoxingClubTrials {
  private static final Map<String, String> ORIGINAL =
      Map.ofEntries(
          Map.entry(
              "3051204/0", "d18d62d1225aee31143538c4ee533c0bead119901e8d664b4d57edcbb038331a"),
          Map.entry(
              "3051204/1", "5e099f09bd75330aee876aace7e368bb7649d50b9882125600fb1562834b6878"),
          Map.entry(
              "3051204/2", "3096ef2c1e8f7e8d74e79ab0695289bc454c43a2ff5105efc2a530bf664709ff"),
          Map.entry(
              "3051204/3", "ffa57dbfd2f074f2f6b84803186a65c3d3aa7d94eff02d498f9e1438865f3a8a"),
          Map.entry(
              "3051204/4", "64b77c32bfce653db00620b14371ee668ab973da06c946b117a30a0132205076"),
          Map.entry(
              "3051204/5", "eb61a0bb4413f4f7016bde776271e129f30e4143b2c38b5d6b07a881dd10d840"),
          Map.entry(
              "3051204/6", "010282f04c8b65134313b642e880914026c9e3a193d5d6bad2bc9cba2fdab704"),
          Map.entry(
              "3051103/0", "3deeab971a5f92b940053144c8f0b099c67ea6764204be784750a8272b292124"),
          Map.entry(
              "3051103/1", "53e5b862b9b3c4b18e73f9eb421e84e2cdaa37c9cc54e28a094c29afba36ab2f"),
          Map.entry(
              "3051103/2", "0bbeb995883e37bb3275eb5e1cfa44caefb6620c030087edadcf9d6f2693e77c"),
          Map.entry(
              "3051103/3", "04a99acc57d75a353335378e2b5603747ce60cdfdb80ce75a4b2bc6b0085c7b5"),
          Map.entry(
              "3051103/4", "df2fd0b9b021239c4c68b53f140d8f9ab507181186f7659680b50c609ff17935"),
          Map.entry(
              "3051103/5", "e4085103e2ab757a155372ef53dbe878d8985778e0929ece79bf07eb2047343f"),
          Map.entry(
              "3051103/6", "545ef360cef2c0b34e21ed0664d18ab4a286f5ef0b5dbdb5d1f9d9bad8c8be4f"),
          Map.entry(
              "3051104/0", "484687fae8d31dc9c06b7c58fa4a88e61e59a87f7c3436d2574efc59f341bd35"),
          Map.entry(
              "3051104/1", "b2eb1c1543e6fc49787c65379a96f43dd0e46e20348205424ac41a7210cbdf30"),
          Map.entry(
              "3051104/2", "b3cc433c4fe04a9f185f7eea660ca291aca7dcbe6d8acf45ab2bb08b2a243d8c"),
          Map.entry(
              "3051104/3", "9a006fc6de6fec9114323b32887a6b1e97a7c419360d494e2d46ba94b6b7bd32"),
          Map.entry(
              "3051104/4", "bfc0518b448b20d25aa78cf99759932774ca03b6338aa3c839bf00b27ae8104b"),
          Map.entry(
              "3051104/5", "5233effb90e09156fc691c00becc63b548539d7cdd792cede98deebeb202307a"),
          Map.entry(
              "3051104/6", "ce6854d17fda70d18a2d69d047354c2ba5e862addacf93264553915c485c7d45"),
          Map.entry(
              "3051003/0", "32abe7f1ee8605f3e1214b6bfe73aef9211797df13661850421ad3c7b47c60fc"),
          Map.entry(
              "3051003/1", "86520e946338dfa3f8e83d222afc296371223539713dd6caf071c22c9a191596"),
          Map.entry(
              "3051003/2", "0524229c9ee3fb7c1cc74a22ec86af93ed1adf1c5d3fb6bf22187f7b2b9acf85"),
          Map.entry(
              "3051003/3", "1b3d4103bd228dd591581dc69055b7d9bc29856d01b0f7fddfe380939fae84ce"),
          Map.entry(
              "3051003/4", "141e309c7ab37d5431d8931601a7fc0b52e6be0fbe9b690788366d89a8f3a6ce"),
          Map.entry(
              "3051003/5", "8329aad4bcfcb73ebeb808041815d0fe32d409b9dda161fa2606b18b02f5804e"),
          Map.entry(
              "3051003/6", "5d368b8aa2c7e1df96ba4c757f9a25caa3a7ba1f7126edc8084938372ea4cede"),
          Map.entry(
              "3051109/0", "3b513d446297fe71fff1fb2d4fecae294f5bc265364f9f64005964302cc7a3d1"),
          Map.entry(
              "3051109/1", "c64a29664e3c2960eb257d59e7400dbc8dc5a6aa9474c4d5fd022b0dba80020b"),
          Map.entry(
              "3051109/2", "61dd771996cad4a07999a15df9ae5a091d589dafad9ab6637efc06a3997af705"),
          Map.entry(
              "3051109/3", "5e222a9a4baacf2ce98bdda2083c1d7899b0e83b9360175c0f23d1e35d94806c"),
          Map.entry(
              "3051109/4", "3cf37ca8956bdeef8d610ada1e14d035fe270e31456fec23d060562704497f80"),
          Map.entry(
              "3051109/5", "aa1c0143afd74294359c3610746ff5436b2eb5f63bcbaf823502c5ce4820f788"),
          Map.entry(
              "3051109/6", "9f379222b0e139dd37b735a70e8f81ed9148984652bce9f27c1591c6c86be816"));
  private static final Map<String, String> PRESETS =
      Map.ofEntries(
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_01.json",
              "254409515cf6d126e69d0926729ad14fdd7575243190fb188161e86a99a12c81"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_1.json",
              "4f96842dabca72838c5118ee3d61247d4608f7106a0fab770562401ebd1aefdf"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_2.json",
              "9cce82d97bddafc09acb3a129bbc693675a2b4a344972056be00860ca05587f9"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_3.json",
              "e4feccd27fc5af78ce7a2d7de058db4c58ee1bd6c9cf0908011a5a30aebd991b"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_4.json",
              "8f80c7758340a1f878a47708b331e36e41f80c40df80a57c513f4ac652332215"),
          Map.entry(
              "Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_5.json",
              "58ca205ada499257c4dc24674cbc95e487eda3d5d0affa8554e27509178c200a"));
  private static BoxingClubTrials cached;
  private final Path root;
  private final String fileHash;
  private final Map<String, SpecialAvatarExcel> rows = new HashMap<>();
  private final Map<Integer, Set<Integer>> allowed = new HashMap<>();
  private final Map<Integer, Integer> modules = new HashMap<>();

  private BoxingClubTrials(Path root) throws Exception {
    this.root = root;
    Path file = root.resolve("ExcelOutput/SpecialAvatar.json");
    if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024 * 1024)
      throw new IllegalArgumentException();
    fileHash = hash(Files.readString(file).replace("\r\n", "\n"));
    try (var reader = new JsonReader(Files.newBufferedReader(file))) {
      reader.beginArray();
      while (reader.hasNext()) {
        var raw = JsonParser.parseReader(reader).getAsJsonObject();
        String key =
            raw.get("SpecialAvatarID").getAsString()
                + "/"
                + (raw.has("WorldLevel") ? raw.get("WorldLevel").getAsString() : "0");
        if (ORIGINAL.containsKey(key)) {
          if (!ORIGINAL.get(key).equals(hash(canonical(raw).toString())))
            throw new IllegalArgumentException("Changed original boxing trial");
          if (rows.putIfAbsent(
                  key, ResourceLoader.getGson().fromJson(raw, SpecialAvatarExcel.class))
              != null) throw new IllegalArgumentException("Duplicate trial");
        }
      }
      reader.endArray();
    }
    if (!rows.keySet().equals(ORIGINAL.keySet()))
      throw new IllegalArgumentException("Missing trial");
    var challenges =
        JsonParser.parseString(
                Files.readString(root.resolve("ExcelOutput/BoxingClubChallenge.json")))
            .getAsJsonArray();
    for (var raw : challenges) {
      var row = raw.getAsJsonObject();
      if (!"First".equals(row.get("Type").getAsString())) continue;
      int id = row.get("ChallengeID").getAsInt();
      var ids = new HashSet<Integer>();
      for (var trial : row.getAsJsonArray("SpecialAvatarIDList")) ids.add(trial.getAsInt());
      if (ids.isEmpty() || allowed.putIfAbsent(id, Set.copyOf(ids)) != null)
        throw new IllegalArgumentException();
      modules.put(id, row.get("SpecialAvatarActivityModule").getAsInt());
    }
    if (allowed.size() != 5 || !sourcesValid()) throw new IllegalArgumentException();
  }

  static synchronized BoxingClubTrials load() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      if (BoxingClubCatalog.load(root) == null) return null;
      if (cached != null && cached.root.equals(root) && cached.sourcesValid()) return cached;
      cached = new BoxingClubTrials(root);
      return cached;
    } catch (Exception invalid) {
      return null;
    }
  }

  boolean sourcesValid() {
    try {
      if (BoxingClubCatalog.load(root) == null
          || !fileHash.equals(
              hash(
                  Files.readString(root.resolve("ExcelOutput/SpecialAvatar.json"))
                      .replace("\r\n", "\n")))) return false;
      for (var entry : PRESETS.entrySet()) {
        var file = root.resolve(entry.getKey());
        if (!Files.isRegularFile(file)
            || Files.size(file) > 128 * 1024
            || !entry.getValue().equals(hash(Files.readString(file).replace("\r\n", "\n"))))
          return false;
      }
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  boolean available(Player p, int challenge, Instant now) {
    if (now == null || !allowed.containsKey(challenge) || !ActivityAccess.unlocked(p, 50000))
      return false;
    int module = modules.get(challenge);
    long time = now.getEpochSecond();
    return GameplayDefinitions.windows.stream()
            .filter(
                w ->
                    w.panelId() == 50000
                        && w.moduleId() == module
                        && w.beginTime() > 0
                        && w.endTime() > w.beginTime()
                        && w.beginTime() <= time
                        && time < w.endTime())
            .count()
        == 1;
  }

  SpecialAvatarExcel row(Player p, int challenge, int trial) {
    if (!allowed.getOrDefault(challenge, Set.of()).contains(trial)
        || p.getWorldLevel() < 0
        || p.getWorldLevel() > 6) return null;
    var expected = rows.get(trial + "/" + p.getWorldLevel());
    var live = p.getTrialManager().specialRow(trial);
    return expected != null
            && expected.isIsUseWorldLevel()
            && "TYPE_TRIAL".equals(expected.getType())
            && BoxingClubRules.equal(expected, live)
        ? live
        : null;
  }

  static boolean declared(int challenge, int id) {
    var source = load();
    return source != null && source.allowed.getOrDefault(challenge, Set.of()).contains(id);
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var result = new JsonObject();
      new TreeSet<>(value.getAsJsonObject().keySet())
          .forEach(k -> result.add(k, canonical(value.getAsJsonObject().get(k))));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      value.getAsJsonArray().forEach(v -> result.add(canonical(v)));
      return result;
    }
    return value;
  }

  private static String hash(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
}
