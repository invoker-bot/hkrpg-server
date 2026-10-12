package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Original MazeSkill rows quote the native OnUse request; no guessed avatar-ID formula. */
public final class MazeSkillCosts {
  private static final String HIMEKO_ABILITY =
      "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_HimekoNova_00_Ability.json";
  // Complete original blobs at TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b.
  private static final Map<String, String> MANUAL_SOURCE_PINS = Map.of(
      HIMEKO_ABILITY, "ac339d288f866ec7ecac96b90e6bf0a494e5098ec5b635570ace5ddd7f4da6c3",
      "ExcelOutput/AvatarConfig.json", "9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e",
      "ExcelOutput/MazeSkill.json", "16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e");
  private static final String CYRENE_ABILITY =
      "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Cyrene_00_Ability.json";
  private static final Map<String, String> CYRENE_SOURCE_PINS = Map.of(
      CYRENE_ABILITY, "b74952ea9bc8545b6a85b2a8f617cd451231fc42388179e31ab1926ed4179777",
      "ExcelOutput/AvatarConfig.json", "9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e",
      "ExcelOutput/MazeSkill.json", "16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e",
      "ExcelOutput/AvatarMazeBuff.json", "31e8a9a6cf52b932435666b657796ea6b00de2d26bf57c1fbd2ccd001afb0ae8",
      "ExcelOutput/AdventurePlayer.json", "aeec485081918f278fe2164ff55242f7eca07258c0296b0144bf801605ccffdd");
  private static final String CASTORICE_ABILITY =
      "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Castorice_00_Ability.json";
  private static final String CASTORICE_PLAYER =
      "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Castorice_00_Config.json";
  private static final Map<String, String> CASTORICE_SOURCE_PINS = Map.of(
      CASTORICE_ABILITY, "866da6c2b57c1ef3c310371fa9a5f169b6c56c1baa60336979c16573be9ecd89",
      CASTORICE_PLAYER, "b35fc7cf48389440eccec5dca1377d0aa36861a65c36ad9de3b3f8f6f48c2caa",
      "ExcelOutput/AvatarConfig.json", "9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e",
      "ExcelOutput/AdventurePlayer.json", "aeec485081918f278fe2164ff55242f7eca07258c0296b0144bf801605ccffdd",
      "ExcelOutput/MazeSkill.json", "16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e",
      "ExcelOutput/AvatarMazeBuff.json", "31e8a9a6cf52b932435666b657796ea6b00de2d26bf57c1fbd2ccd001afb0ae8",
      "ExcelOutput/AvatarSkillConfig.json", "b826b840609df66aa138abb30ce1add32bfad9a220afc0a8b9e4f01aa3fccf60",
      "ExcelOutput/AvatarSkillConfigLD.json", "a8f3b55ef5c2f58c600f7ecd6d016ce05cc687ab9f3374a9d2f8ffb21dd02168");
  // Parsed fixed blobs can be shared; every ingress still reads and checks the full physical bytes.
  private static final Map<String, JsonElement> castoriceParsed = new java.util.concurrent.ConcurrentHashMap<>();
  public record ManualInput(MazeSkill skill, int cost, int originCost, String originModifier) {}
  public record FreeNormalAttack(MazeSkill skill, MazeSkill origin, int originBuff) {}
  public record FreeDirectInput(MazeSkill skill, MazeSkill origin, int originBuff) {}
  public record FreeOut(MazeSkill skill, MazeSkill origin, int originBuff) {}
  private record Row(int relatedSkill, int type, String trigger, int cost) {}
  private static Path previousFile;
  private static byte[] previousHash;
  private static List<Row> previousRows = List.of();

  private MazeSkillCosts() {}

  /** Original1407 Free direct Attack, authorized only by its own actual base release and20s buff. */
  public static Optional<FreeDirectInput> castoriceFreeDirectInput(GameAvatar caster, String name) {
    if (caster == null || caster.getActorId() != 1407 || caster.getExcel() == null
        || !"LocalPlayer_Castorice_MazeSkill_Input".equals(name)) return Optional.empty();
    var origin = caster.getExcel().getMazeSkill();
    var manual = origin == null ? null : origin.manualAbility(name);
    if (origin == null || !"LocalPlayer_Castorice_MazeSkill".equals(origin.getAbilityName())
        || manual == null || manual.getId() != 1407 || manual.getIndex() != 2
        || manual.isProjectileDeclared()) return Optional.empty();
    try {
      var originals = castoriceOriginals();
      if (originals.isEmpty()) return Optional.empty();
      var avatar = row(originals.get("ExcelOutput/AvatarConfig.json"), "AvatarID", 1407);
      int[] skills = avatar.getAsJsonArray("SkillList").asList().stream().mapToInt(MazeSkillCosts::integer).toArray();
      if (!Arrays.equals(skills, caster.getExcel().getSkillList())) return Optional.empty();
      var adventure = row(originals.get("ExcelOutput/AdventurePlayer.json"), "ID", 1407);
      var maze = row(originals.get("ExcelOutput/MazeSkill.json"), "MazeSkillId", 140702);
      if (integer(adventure.get("AvatarID")) != 1407
          || !CASTORICE_PLAYER.equals(adventure.get("PlayerJsonPath").getAsString())
          || integer(maze.get("MazeSkilltype")) != 2 || integer(maze.get("RelatedAvatarSkill")) != 140707
          || !"MazeSkill".equals(maze.get("SkillTriggerKey").getAsString())
          || integer(maze.get("MPCost")) != 1
          || Arrays.stream(skills).noneMatch(id -> id == 140707)) return Optional.empty();
      var player = originals.get(CASTORICE_PLAYER).getAsJsonObject();
      var read = player.getAsJsonObject("DynamicValues").getAsJsonObject("Floats")
          .getAsJsonObject("-1733965089").getAsJsonObject("ReadInfo");
      if (!read.equals(JsonParser.parseString("{\"Type\":\"SkillParam\",\"TriggerKey\":\"SkillMaze\",\"Index\":2}")))
        return Optional.empty();
      var abilityNames = player.getAsJsonArray("AbilityList").asList().stream().map(JsonElement::getAsString).toList();
      if (!abilityNames.contains(name) || !abilityNames.contains(origin.getAbilityName())) return Optional.empty();
      var playerSkills = player.getAsJsonArray("SkillList").asList().stream().map(JsonElement::getAsJsonObject)
          .filter(skill -> "MazeSkill".equals(skill.get("Name").getAsString())).toList();
      if (playerSkills.size() != 1 || !origin.getAbilityName().equals(playerSkills.getFirst().get("EntryAbility").getAsString()))
        return Optional.empty();
      // The original Player omits SkillCostMpPhase. This contract never manufactures a1408 payment.
      var parameters = row(originals.get("ExcelOutput/AvatarSkillConfig.json"), "SkillID", 140707);
      var loaded = emu.lunarcore.data.GameData.getAvatarSkillParameterExcelMap().get(140707, 1);
      if (integer(parameters.get("Level")) != 1 || integer(parameters.get("MaxLevel")) != 1
          || !"SkillMaze".equals(parameters.get("SkillTriggerKey").getAsString())
          || !"Maze".equals(parameters.get("AttackType").getAsString())
          || parameters.getAsJsonArray("ParamList").get(2).getAsJsonObject().get("Value").getAsDouble() != 20
          || loaded == null || loaded.getMaxLevel() != 1 || !"SkillMaze".equals(loaded.getSkillTriggerKey())
          || !"Maze".equals(loaded.getAttackType())
          || !new Gson().toJsonTree(loaded.getParamList()).equals(parameters.getAsJsonArray("ParamList"))
          || originals.get("ExcelOutput/AvatarSkillConfigLD.json").getAsJsonArray().asList().stream()
              .map(JsonElement::getAsJsonObject).anyMatch(skill -> integer(skill.get("SkillID")) == 140707)) return Optional.empty();
      var buff = row(originals.get("ExcelOutput/AvatarMazeBuff.json"), "ID", 140704);
      var loadedBuff = emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(140704, 1);
      if (!"ADV_StageAbility_Maze_Castorice_Radius".equals(buff.get("ModifierName").getAsString())
          || integer(buff.get("BuffDescParamByAvatarSkillID")) != 140707 || buff.has("UseType")
          || loadedBuff == null || !buff.get("ModifierName").getAsString().equals(loadedBuff.getModifierName())
          || loadedBuff.getUseType() != null
          || !new Gson().toJsonTree(loadedBuff.getParamList()).equals(buff.getAsJsonArray("ParamList"))) return Optional.empty();
      var battleBuff = row(originals.get("ExcelOutput/AvatarMazeBuff.json"), "ID", 140701);
      var loadedBattleBuff = emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(140701, 1);
      if (loadedBattleBuff == null || !"ADV_StageAbility_Maze_Castorice_Attack".equals(loadedBattleBuff.getModifierName())
          || !battleBuff.get("UseType").getAsString().equals(loadedBattleBuff.getUseType())
          || !battleBuff.get("InBattleBindingType").getAsString().equals(loadedBattleBuff.getInBattleBindingType())
          || !battleBuff.get("InBattleBindingKey").getAsString().equals(loadedBattleBuff.getInBattleBindingKey())
          || !new Gson().toJsonTree(loadedBattleBuff.getParamList()).equals(battleBuff.getAsJsonArray("ParamList")))
        return Optional.empty();
      var config = originals.get(CASTORICE_ABILITY).getAsJsonObject();
      var base = config.getAsJsonArray("AbilityList").get(1).getAsJsonObject();
      var grant = base.getAsJsonArray("OnStart").get(0).getAsJsonObject();
      var wait = config.getAsJsonObject("GlobalModifiers").getAsJsonObject("ADV_StageAbility_Maze_Castorice_Radius")
          .getAsJsonArray("ModifierTaskList").get(0).getAsJsonObject();
      if (!origin.getAbilityName().equals(base.get("Name").getAsString())
          || !"RPG.GameCore.AddMazeBuff".equals(grant.get("$type").getAsString())
          || integer(grant.get("ID")) != 140704 || !grant.get("LifeTime").equals(wait.get("MaxWaitTime")))
        return Optional.empty();
      var ability = config.getAsJsonArray("AbilityList").get(3).getAsJsonObject();
      var attack = ability.getAsJsonArray("OnStart").get(14).getAsJsonObject();
      var remove = ability.getAsJsonArray("OnStart").get(18).getAsJsonObject();
      if (!name.equals(ability.get("Name").getAsString()) || !"Free".equals(ability.get("ManualTriggerType").getAsString())
          || !"RPG.GameCore.AdventureTriggerAttack".equals(attack.get("$type").getAsString())
          || attack.has("CostMP") || attack.has("CostMpPhaseType")
          || integer(attack.getAsJsonArray("OnBattle").get(4).getAsJsonObject().get("ID")) != 140701
          || !"RPG.GameCore.RemoveMazeBuff".equals(remove.get("$type").getAsString())
          || integer(remove.get("ID")) != 140704) return Optional.empty();
      return Optional.of(new FreeDirectInput(manual, origin, 140704));
    } catch (RuntimeException unsupported) {
      return Optional.empty();
    }
  }

  /** Same full original base contract; Out only removes the source-owned radius, without Attack. */
  public static Optional<FreeOut> castoriceFreeOut(GameAvatar caster, String name) {
    if (!"LocalPlayer_Castorice_MazeSkill_Out".equals(name)) return Optional.empty();
    var input = castoriceFreeDirectInput(caster, "LocalPlayer_Castorice_MazeSkill_Input");
    if (input.isEmpty()) return Optional.empty();
    var source = input.get();
    var cleanup = source.origin().manualAbility(name);
    if (cleanup == null || cleanup.getId() != 1407 || cleanup.getIndex() != 2
        || cleanup.isProjectileDeclared() || cleanup.isTriggerBattle()
        || !cleanup.getAttackActions().isEmpty() || cleanup.getCastActions().size() != 1
        || !(cleanup.getCastActions().getFirst() instanceof emu.lunarcore.game.battle.skills.MazeSkillRemoveBuff))
      return Optional.empty();
    return Optional.of(new FreeOut(cleanup, source.origin(), source.originBuff()));
  }

  private static Map<String, JsonElement> castoriceOriginals() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var originals = new HashMap<String, JsonElement>();
      for (var pin : CASTORICE_SOURCE_PINS.entrySet()) {
        var file = root.resolve(pin.getKey());
        // Only this new contract accepts its complete10.83MB AvatarSkillConfig blob.
        long limit = pin.getKey().equals("ExcelOutput/AvatarSkillConfig.json") ? 16_000_000 : 4_000_000;
        if (!Files.isRegularFile(file) || Files.size(file) > limit
            || !file.toRealPath().startsWith(root.toRealPath())) return Map.of();
        byte[] raw = Files.readAllBytes(file), canonical = new byte[raw.length];
        int count = 0;
        for (int i = 0; i < raw.length; i++) {
          if (raw[i] == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') continue;
          canonical[count++] = raw[i];
        }
        canonical = Arrays.copyOf(canonical, count);
        if (!pin.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))))
          return Map.of();
        var cached = castoriceParsed.get(pin.getValue());
        if (cached == null) {
          cached = JsonParser.parseString(new String(canonical, java.nio.charset.StandardCharsets.UTF_8));
          castoriceParsed.putIfAbsent(pin.getValue(), cached);
        }
        originals.put(pin.getKey(), cached);
      }
      return originals;
    } catch (Exception unavailable) {
      return Map.of();
    }
  }

  /** Finite original Free NormalAtk02: its ordinary MazeSkill row has zero MP cost. */
  public static Optional<FreeNormalAttack> cyreneFreeNormalAttack(GameAvatar caster, String name) {
    if (caster == null || caster.getActorId() != 1415 || caster.getExcel() == null
        || !"LocalPlayer_Cyrene_00_NormalAtk02".equals(name)) return Optional.empty();
    var normal = caster.getExcel().getMazeAttack();
    var origin = caster.getExcel().getMazeSkill();
    var manual = normal == null ? null : normal.manualAbility(name);
    if (origin == null || !"LocalPlayer_Cyrene_00_MazeSkill".equals(origin.getAbilityName())
        || normal == null || !"LocalPlayer_Cyrene_00_NormalAtk01".equals(normal.getAbilityName())
        || manual == null || manual.getId() != 1415 || manual.getIndex() != 1
        || !manual.isProjectileDeclared()) return Optional.empty();
    try {
      var originals = originalSources(CYRENE_SOURCE_PINS);
      if (originals.isEmpty()) return Optional.empty();
      var avatar = row(originals.get("ExcelOutput/AvatarConfig.json"), "AvatarID", 1415);
      int[] skills = avatar.getAsJsonArray("SkillList").asList().stream().mapToInt(MazeSkillCosts::integer).toArray();
      if (!Arrays.equals(skills, caster.getExcel().getSkillList())) return Optional.empty();
      var player = row(originals.get("ExcelOutput/AdventurePlayer.json"), "ID", 1415);
      var maze = row(originals.get("ExcelOutput/MazeSkill.json"), "MazeSkillId", 141501);
      if (integer(player.get("AvatarID")) != 1415 || integer(maze.get("MazeSkilltype")) != 1
          || !"NormalAtk".equals(maze.get("SkillTriggerKey").getAsString())
          || !Arrays.stream(skills).anyMatch(id -> id == integer(maze.get("RelatedAvatarSkill")))
          || (maze.has("MPCost") && integer(maze.get("MPCost")) != 0)) return Optional.empty();
      var buff = row(originals.get("ExcelOutput/AvatarMazeBuff.json"), "ID", 141501);
      if (!"ADV_StageAbility_Maze_Cyrene".equals(buff.get("ModifierName").getAsString())) return Optional.empty();
      var config = originals.get(CYRENE_ABILITY).getAsJsonObject();
      var base = config.getAsJsonArray("AbilityList").get(2).getAsJsonObject();
      var grant = base.getAsJsonArray("OnStart").get(11).getAsJsonObject();
      if (!origin.getAbilityName().equals(base.get("Name").getAsString())
          || !"RPG.GameCore.AddMazeBuff".equals(grant.get("$type").getAsString())
          || integer(grant.get("ID")) != 141501
          || !"RPG.GameCore.AdventureSubmitSkill".equals(base.getAsJsonArray("OnStart")
              .get(12).getAsJsonObject().get("$type").getAsString())) return Optional.empty();
      var ability = config.getAsJsonArray("AbilityList").get(1).getAsJsonObject();
      if (!name.equals(ability.get("Name").getAsString())
          || !"Free".equals(ability.get("ManualTriggerType").getAsString())) return Optional.empty();
      var branch = ability.getAsJsonArray("OnStart").get(1).getAsJsonObject();
      JsonObject expectedAttack = null;
      for (var key : List.of("SuccessTaskList", "FailedTaskList")) {
        var projectile = branch.getAsJsonArray(key).get(7).getAsJsonObject();
        var attack = projectile.getAsJsonArray("OnProjectileLifetimeFinish").get(0).getAsJsonObject();
        if (projectile.has("OnProjectileHit") || attack.has("CostMP") || attack.has("CostMpPhaseType")
            || !"RPG.GameCore.AdventureTriggerAttack".equals(attack.get("$type").getAsString())) return Optional.empty();
        var contract = attack.deepCopy();
        contract.remove("HitConfig"); // Only the client hit animation differs between branches.
        if (expectedAttack != null && !expectedAttack.equals(contract)) return Optional.empty();
        expectedAttack = contract;
      }
      return Optional.of(new FreeNormalAttack(manual, origin, 141501));
    } catch (RuntimeException unsupported) {
      return Optional.empty();
    }
  }

  private static JsonObject row(JsonElement table, String id, int value) {
    var matches = table.getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject)
        .filter(item -> integer(item.get(id)) == value).toList();
    if (matches.size() != 1) throw new IllegalArgumentException("Original row missing or ambiguous");
    return matches.getFirst();
  }

  /** Only the complete, source-linked 1510 Free Input is supported by this finite path. */
  public static Optional<ManualInput> manualInput(GameAvatar caster, String name) {
    if (caster == null || caster.getActorId() != 1510 || caster.getExcel() == null
        || !"LocalPlayer_HimekoNova_MazeSkill_Input".equals(name)) return Optional.empty();
    var origin = caster.getExcel().getMazeSkill();
    if (origin == null || !"LocalPlayer_HimekoNova_MazeSkill".equals(origin.getAbilityName())) return Optional.empty();
    var manual = origin.manualAbility(name);
    if (manual == null || manual.getId() != 1510 || manual.getIndex() != 2
        || !manual.isProjectileDeclared()) return Optional.empty();
    try {
      var originals = manualOriginals();
      if (originals.isEmpty()) return Optional.empty();
      var avatar = originals.get("ExcelOutput/AvatarConfig.json").getAsJsonArray().asList().stream()
          .map(JsonElement::getAsJsonObject).filter(row -> integer(row.get("AvatarID")) == 1510)
          .findFirst().orElseThrow();
      int[] skills = avatar.getAsJsonArray("SkillList").asList().stream().mapToInt(MazeSkillCosts::integer).toArray();
      if (!Arrays.equals(skills, caster.getExcel().getSkillList())) return Optional.empty();
      var ability = originals.get(HIMEKO_ABILITY).getAsJsonObject().getAsJsonArray("AbilityList")
          .get(2).getAsJsonObject();
      if (!name.equals(ability.get("Name").getAsString())
          || !"Free".equals(ability.get("ManualTriggerType").getAsString())) return Optional.empty();
      var projectile = ability.getAsJsonArray("OnStart").get(11).getAsJsonObject();
      int cost = 0;
      for (var event : List.of("OnProjectileHit", "OnProjectileLifetimeFinish")) {
        var attack = projectile.getAsJsonArray(event).get(1).getAsJsonObject();
        int amount = integer(attack.get("CostMP"));
        if (!"RPG.GameCore.AdventureTriggerAttack".equals(attack.get("$type").getAsString())
            || !"OnFirstHitEnemy".equals(attack.get("CostMpPhaseType").getAsString())
            || amount <= 0 || (cost != 0 && cost != amount)) return Optional.empty();
        cost = amount;
      }
      var originCost = onUse(caster, origin);
      if (originCost.isEmpty()) return Optional.empty();
      return Optional.of(new ManualInput(manual, cost, originCost.getAsInt(),
          "ADV_StageAbility_Maze_HimekoNova_AttackRadius"));
    } catch (RuntimeException unsupported) {
      return Optional.empty();
    }
  }

  private static Map<String, JsonElement> manualOriginals() {
    return originalSources(MANUAL_SOURCE_PINS);
  }

  private static Map<String, JsonElement> originalSources(Map<String, String> pins) {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var originals = new HashMap<String, JsonElement>();
      for (var pin : pins.entrySet()) {
        var file = root.resolve(pin.getKey());
        if (!Files.isRegularFile(file) || Files.size(file) > 4_000_000
            || !file.toRealPath().startsWith(root.toRealPath())) return Map.of();
        // The checkout may use CRLF; only line-ending bytes differ from the fixed Git blob.
        byte[] raw = Files.readAllBytes(file);
        byte[] canonical = new byte[raw.length];
        int count = 0;
        for (int i = 0; i < raw.length; i++) {
          if (raw[i] == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') continue;
          canonical[count++] = raw[i];
        }
        canonical = Arrays.copyOf(canonical, count);
        if (!pin.getValue().equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))))
          return Map.of();
        originals.put(pin.getKey(), JsonParser.parseString(new String(canonical, java.nio.charset.StandardCharsets.UTF_8)));
      }
      return originals;
    } catch (Exception unavailable) {
      return Map.of();
    }
  }

  public static OptionalInt onUse(GameAvatar caster, MazeSkill skill) {
    if (caster == null || skill == null || caster.getExcel() == null
        || caster.getActorId() != skill.getId() || caster.getExcel().getSkillList() == null
        || skill != caster.getExcel().getMazeSkill()) return OptionalInt.empty();
    var related = new HashSet<Integer>();
    for (int id : caster.getExcel().getSkillList()) related.add(id);
    var matches = rows().stream()
        .filter(row -> row.type == 2 && row.trigger.equals("MazeSkill") && related.contains(row.relatedSkill))
        .toList();
    // A missing, ambiguous or zero-cost definition cannot manufacture an OnUse payment.
    return matches.size() == 1 && matches.getFirst().cost > 0
        ? OptionalInt.of(matches.getFirst().cost) : OptionalInt.empty();
  }

  private static synchronized List<Row> rows() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var file = root.resolve("ExcelOutput/MazeSkill.json");
      if (!Files.isRegularFile(file) || Files.size(file) > 4_000_000
          || !file.toRealPath().startsWith(root.toRealPath())) return List.of();
      byte[] bytes = Files.readAllBytes(file);
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      if (file.equals(previousFile) && Arrays.equals(hash, previousHash)) return previousRows;
      var array = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
      var result = new ArrayList<Row>();
      var ids = new HashSet<Integer>();
      for (var entry : array) {
        var value = entry.getAsJsonObject();
        int id = integer(value.get("MazeSkillId")), related = integer(value.get("RelatedAvatarSkill"));
        int type = integer(value.get("MazeSkilltype"));
        var trigger = value.get("SkillTriggerKey");
        int cost = value.has("MPCost") ? integer(value.get("MPCost")) : 0;
        if (id <= 0 || related <= 0 || !ids.add(id) || type < 1 || type > 2 || cost < 0
            || trigger == null || !trigger.isJsonPrimitive() || !trigger.getAsJsonPrimitive().isString())
          return List.of();
        result.add(new Row(related, type, trigger.getAsString(), cost));
      }
      previousFile = file;
      previousHash = hash;
      previousRows = List.copyOf(result);
      return previousRows;
    } catch (Exception unavailable) {
      return List.of();
    }
  }

  private static int integer(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("MazeSkill numeric source field missing");
    return value.getAsBigDecimal().intValueExact();
  }
}
