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
  private record Row(int relatedSkill, int type, String trigger, int cost) {}
  private static Path previousFile;
  private static byte[] previousHash;
  private static List<Row> previousRows = List.of();

  private MazeSkillCosts() {}

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
