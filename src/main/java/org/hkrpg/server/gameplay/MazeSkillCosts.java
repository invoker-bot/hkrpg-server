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
  public record ManualInput(MazeSkill skill, int cost, int originCost, String originModifier) {}
  public record FreeNormalAttack(MazeSkill skill, MazeSkill origin, int originBuff) {}
  private record Row(int relatedSkill, int type, String trigger, int cost) {}
  private static Path previousFile;
  private static byte[] previousHash;
  private static List<Row> previousRows = List.of();

  private MazeSkillCosts() {}

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
