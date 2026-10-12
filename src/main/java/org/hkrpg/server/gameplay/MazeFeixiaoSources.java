package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Fixed1220 base/normal-abort source, not a general modifier, movement or summon engine. */
public final class MazeFeixiaoSources {
  public static final String BASE = "LocalPlayer_Feixiao_00_MazeSkill";
  public static final String NORMAL = "LocalPlayer_Feixiao_00_NormalAtk01";
  public static final String CLEANUP = "LocalPlayer_Feixiao_00_NormalAtk01_RemoveMazebuff";
  private static final String ABILITY = "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Feixiao_00_Ability.json";
  private static final String PLAYER = "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Feixiao_00_Config.json";
  private static final Map<String, String> PINS = Map.of(
      "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Feixiao_00_Ability.json", "2bbe9891e359bb39a334c8e0c4f4e38b5c5c8fe851f0fba448d31f664b4d5653",
      "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Feixiao_00_Config.json", "cb5720a3c1f2684d32622d76b6704d883bdff83ccfecf26418948314ab0f4bd4",
      "ExcelOutput/AvatarConfig.json", "9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e",
      "ExcelOutput/AdventurePlayer.json", "aeec485081918f278fe2164ff55242f7eca07258c0296b0144bf801605ccffdd",
      "ExcelOutput/MazeSkill.json", "16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e",
      "ExcelOutput/AvatarMazeBuff.json", "31e8a9a6cf52b932435666b657796ea6b00de2d26bf57c1fbd2ccd001afb0ae8",
      "ExcelOutput/AvatarSkillConfig.json", "b826b840609df66aa138abb30ce1add32bfad9a220afc0a8b9e4f01aa3fccf60",
      "ExcelOutput/AvatarSkillConfigLD.json", "a8f3b55ef5c2f58c600f7ecd6d016ce05cc687ab9f3374a9d2f8ffb21dd02168"
      );
  private static final Map<String, JsonElement> parsed = new java.util.concurrent.ConcurrentHashMap<>();
  public record Cleanup(MazeSkill skill, MazeSkill origin, int originBuff) {}
  private MazeFeixiaoSources() {}

  /** This exact single-hash task is source-bound to SkillMaze parameter0, not a20s fallback. */
  public static OptionalDouble originLifetime(GameAvatar caster, MazeSkill skill) {
    if (caster == null || skill == null || caster.getActorId() != 1220
        || caster.getExcel() == null || caster.getExcel().getMazeSkill() != skill
        || !BASE.equals(skill.getAbilityName())) return OptionalDouble.empty();
    try {
      var originals = originals();
      if (originals.isEmpty() || !validLoaded(caster, originals)) return OptionalDouble.empty();
      return OptionalDouble.of(row(originals, "AvatarSkillConfig", "SkillID", 122007)
          .getAsJsonArray("ParamList").get(0).getAsJsonObject().get("Value").getAsDouble());
    } catch (RuntimeException unsupported) { return OptionalDouble.empty(); }
  }

  /** Only the full original no-projectile cleanup is admitted at the native normal slot. */
  public static Optional<Cleanup> abortCleanup(GameAvatar caster, String name) {
    if (caster == null || caster.getActorId() != 1220 || caster.getExcel() == null || !CLEANUP.equals(name))
      return Optional.empty();
    var origin = caster.getExcel().getMazeSkill();
    var normal = caster.getExcel().getMazeAttack();
    var manual = normal == null ? null : normal.manualAbility(name);
    if (origin == null || normal == null || !BASE.equals(origin.getAbilityName())
        || !NORMAL.equals(normal.getAbilityName()) || manual == null || manual.getIndex() != 1
        || manual.getId() != 1220 || manual.isProjectileDeclared() || manual.isTriggerBattle()
        || !manual.getAttackActions().isEmpty() || manual.getCastActions().size() != 1
        || !(manual.getCastActions().getFirst() instanceof MazeSkillRemoveBuff remove)
        || remove.getBuffId() != 122001) return Optional.empty();
    try {
      var originals = originals();
      if (originals.isEmpty() || !validLoaded(caster, originals)) return Optional.empty();
      var config = originals.get(ABILITY).getAsJsonObject();
      var trigger = config.getAsJsonArray("AbilityList").get(0).getAsJsonObject()
          .getAsJsonArray("OnAbort").get(0);
      if (!trigger.equals(JsonParser.parseString("{\"$type\":\"RPG.GameCore.AdventureTriggerTargetAbility\","
          + "\"AbilityName\":\"" + CLEANUP + "\",\"AbilityTargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"}}")))
        return Optional.empty();
      var cleanup = config.getAsJsonArray("AbilityList").get(1).getAsJsonObject();
      if (!CLEANUP.equals(cleanup.get("Name").getAsString()) || !"Free".equals(cleanup.get("ManualTriggerType").getAsString())
          || !cleanup.getAsJsonArray("OnStart").equals(JsonParser.parseString("[{\"$type\":\"RPG.GameCore.RemoveMazeBuff\","
              + "\"TargetType\":{\"$type\":\"RPG.GameCore.TargetAlias\",\"Alias\":\"Caster\"},\"ID\":122001},"
              + "{\"$type\":\"RPG.GameCore.AdventureSubmitSkill\"}]"))) return Optional.empty();
      return Optional.of(new Cleanup(manual, origin, 122001));
    } catch (RuntimeException unsupported) { return Optional.empty(); }
  }

  private static boolean validLoaded(GameAvatar caster, Map<String, JsonElement> originals) {
    var avatar = row(originals, "AvatarConfig", "AvatarID", 1220);
    int[] skills = avatar.getAsJsonArray("SkillList").asList().stream().mapToInt(v -> v.getAsInt()).toArray();
    if (!Arrays.equals(skills, caster.getExcel().getSkillList())) return false;
    var player = row(originals, "AdventurePlayer", "ID", 1220);
    if (player.get("AvatarID").getAsInt() != 1220 || !PLAYER.equals(player.get("PlayerJsonPath").getAsString())) return false;
    var skill = row(originals, "MazeSkill", "MazeSkillId", 122002);
    if (skill.get("RelatedAvatarSkill").getAsInt() != 122007 || skill.get("MazeSkilltype").getAsInt() != 2
        || skill.get("MPCost").getAsInt() != 1 || !"MazeSkill".equals(skill.get("SkillTriggerKey").getAsString())) return false;
    var local = originals.get(PLAYER).getAsJsonObject();
    var declared = local.getAsJsonArray("SkillList").get(1).getAsJsonObject();
    if (!"MazeSkill".equals(declared.get("Name").getAsString()) || !BASE.equals(declared.get("EntryAbility").getAsString())
        || !declared.get("SubmitSkillWhenCast").getAsBoolean() || declared.has("SkillCostMpPhase")) return false;
    if (!local.getAsJsonObject("DynamicValues").getAsJsonObject("Floats").getAsJsonObject("692437857")
        .get("ReadInfo").equals(JsonParser.parseString("{\"Type\":\"SkillParam\",\"TriggerKey\":\"SkillMaze\",\"Index\":0}"))) return false;
    var source = row(originals, "AvatarSkillConfig", "SkillID", 122007);
    var loaded = GameData.getAvatarSkillParameterExcelMap().get(122007, 1);
    if (loaded == null || loaded.getLevel() != 1 || loaded.getMaxLevel() != 1 || !"Maze".equals(loaded.getAttackType())
        || !"SkillMaze".equals(loaded.getSkillTriggerKey()) || !source.getAsJsonArray("ParamList").asList().equals(loaded.getParamList())) return false;
    if (originals.get("ExcelOutput/AvatarSkillConfigLD.json").getAsJsonArray().asList().stream()
        .map(JsonElement::getAsJsonObject).anyMatch(r -> r.get("SkillID").getAsInt() == 122007)) return false;
    var buff = GameData.getMazeBuffExcelMap().get(122001, 1);
    if (buff == null || !"ADV_StageAbility_Maze_Feixiao".equals(buff.getModifierName())
        || !"Avatar_Feixiao_00_SkillMazeSP".equals(buff.getInBattleBindingKey())
        || !"CharacterAbility".equals(buff.getInBattleBindingType()) || buff.getUseType() != null
        || buff.getLvMax() != 1 || !"Character".equals(buff.getMazeBuffType())
        || buff.getParamList() == null || !buff.getParamList().isEmpty()) return false;
    var grant = originals.get(ABILITY).getAsJsonObject().getAsJsonArray("AbilityList").get(2).getAsJsonObject()
        .getAsJsonArray("OnStart").get(5).getAsJsonObject();
    return grant.get("ID").getAsInt() == 122001 && grant.get("LifeTime").equals(JsonParser.parseString(
        "{\"IsDynamic\":true,\"PostfixExpr\":{\"OpCodes\":\"AQAR\",\"FixedValues\":[],\"DynamicHashes\":[692437857]}}"));
  }

  private static JsonObject row(Map<String, JsonElement> originals, String table, String id, int value) {
    var matches = originals.get("ExcelOutput/" + table + ".json").getAsJsonArray().asList().stream()
        .map(JsonElement::getAsJsonObject).filter(r -> r.get(id).getAsInt() == value).toList();
    if (matches.size() != 1) throw new IllegalArgumentException("Original row missing or ambiguous");
    return matches.getFirst();
  }

  private static Map<String, JsonElement> originals() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize().toRealPath();
      var result = new HashMap<String, JsonElement>();
      for (var pin : PINS.entrySet()) {
        var path = root.resolve(pin.getKey());
        if (!Files.isRegularFile(path) || Files.size(path) > 16_000_000 || !path.toRealPath().startsWith(root)) return Map.of();
        byte[] raw = Files.readAllBytes(path);
        byte[] canonical = new byte[raw.length];
        int length = 0;
        for (int i = 0; i < raw.length; i++) {
          if (raw[i] == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') continue;
          canonical[length++] = raw[i];
        }
        canonical = Arrays.copyOf(canonical, length);
        if (!pin.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)))) return Map.of();
        final byte[] verified = canonical;
        result.put(pin.getKey(), parsed.computeIfAbsent(pin.getValue(), ignored -> JsonParser.parseString(new String(verified, StandardCharsets.UTF_8))));
      }
      return result;
    } catch (Exception unavailable) { return Map.of(); }
  }
}
