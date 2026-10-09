package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.GameConstants;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerGender;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import java.nio.file.*;
import java.util.*;

/** Resource-declared raid parties and their verified finite team constraints. */
public final class RaidTeams {
  public record Selection(int id, boolean trial) {}

  public record Plan(
      String type,
      List<Integer> trials,
      Map<Integer, Integer> requirements,
      List<Integer> replaceRules,
      int captain,
      Map<Integer, Integer> levels,
      Map<Integer, Integer> counts,
      Map<Integer, Integer> paths) {
    public Plan {
      trials = List.copyOf(trials);
      requirements = Map.copyOf(requirements);
      replaceRules = List.copyOf(replaceRules);
      levels = Map.copyOf(levels);
      counts = Map.copyOf(counts);
      paths = Map.copyOf(paths);
    }

    public Plan(String type, List<Integer> trials, Map<Integer, Integer> requirements) {
      this(type, trials, requirements, List.of(), 0, Map.of(), Map.of(), Map.of());
    }
  }

  public static Plan plan(GameplayDefinitions.Raid row) {
    if (row == null
        || row.TrialAvatarList() == null
        || row.TeamLimitIDList() == null
        || row.LockCaptainAvatarID() < 0
        || row.LockCaptain() != (row.LockCaptainAvatarID() > 0)) return null;
    boolean ordinary = "Player".equals(row.TeamType());
    if (!ids(row.TeamLimitIDList())) return null;
    if (ordinary) {
      if (!row.TrialAvatarList().isEmpty()) return null;
    } else if (!("TrialOrPlayer".equals(row.TeamType()) || "TrialOnly".equals(row.TeamType()))
        || "TrialOnly".equals(row.TeamType())
            && (row.TrialAvatarList().isEmpty() || row.TrialAvatarList().size() > 4)
        || !ids(row.TrialAvatarList())) return null;
    var required = new HashMap<Integer, Integer>();
    var replaceRules = new ArrayList<Integer>();
    var levels = new HashMap<Integer, Integer>();
    var counts = new HashMap<Integer, Integer>();
    var paths = new HashMap<Integer, Integer>();
    if (!row.TeamLimitIDList().isEmpty()) {
      try {
        var source =
            JsonParser.parseString(
                Files.readString(
                    Path.of(
                        LunarCore.getConfig().getResourceDir(),
                        "ExcelOutput",
                        "TeamLimitCondition.json")));
        if (!source.isJsonArray()) return null;
        var rules = new HashMap<Integer, JsonObject>();
        for (var value : source.getAsJsonArray()) {
          if (!value.isJsonObject()) return null;
          var rule = value.getAsJsonObject();
          int id = integer(rule, "ID");
          if (id <= 0 || rules.putIfAbsent(id, rule) != null) return null;
        }
        for (int id : row.TeamLimitIDList()) {
          var rule = rules.get(id);
          if (rule == null
              || !Set.of(
                      "ID",
                      "LimitType",
                      "ParamInt1",
                      "ParamInt2",
                      "ParamInt3",
                      "ParamIntList",
                      "LimitDesc")
                  .containsAll(rule.keySet())
              || !rule.has("LimitType")
              || !rule.get("LimitType").isJsonPrimitive()
              || !rule.getAsJsonPrimitive("LimitType").isString()
              || integer(rule, "ParamInt2") != 0
              || integer(rule, "ParamInt3") != 0
              || !rule.has("ParamIntList")
              || !rule.get("ParamIntList").isJsonArray()
              || !rule.getAsJsonArray("ParamIntList").isEmpty()) return null;
          String type = rule.get("LimitType").getAsString();
          int actor = integer(rule, "ParamInt1");
          if ("IncludeAvatar".equals(type) && actor > 0) required.put(id, actor);
          else if ("TeamLevel".equals(type) && actor > 0) levels.put(id, actor);
          else if ("IncludeMultiPathAvatar".equals(type) && actor > 0) paths.put(id, actor);
          else if ("TeamAvatarMinCount".equals(type) && actor > 0 && actor <= 4)
            counts.put(id, actor);
          else if ("ForbidReplace".equals(type) && actor == 0) replaceRules.add(id);
          else return null;
        }
      } catch (java.io.IOException
          | IllegalArgumentException
          | IllegalStateException
          | ArithmeticException invalid) {
        return null;
      }
    }
    return new Plan(
        row.TeamType(),
        row.TrialAvatarList(),
        required,
        replaceRules,
        row.LockCaptainAvatarID(),
        levels,
        counts,
        paths);
  }

  private static boolean ids(List<Integer> ids) {
    return ids.stream().allMatch(id -> id != null && id > 0)
        && new HashSet<>(ids).size() == ids.size();
  }

  public static boolean frozenParty(Plan plan) {
    return plan != null && (plan.captain() > 0 || !plan.replaceRules().isEmpty());
  }

  public static boolean snapshotRequired(Plan plan) {
    return !"Player".equals(plan.type())
        || frozenParty(plan)
        || !plan.requirements().isEmpty()
        || !plan.levels().isEmpty()
        || !plan.counts().isEmpty()
        || !plan.paths().isEmpty();
  }

  public static boolean membersAllowed(Plan plan, Collection<? extends GameAvatar> members) {
    if (plan == null || members == null || members.isEmpty() || members.size() > 4) return false;
    var actors = new HashSet<Integer>();
    var paths = new HashSet<Integer>();
    for (var member : members) {
      if (member == null) return false;
      actors.add(roleIdentity(member));
      if (!plan.paths().isEmpty()) paths.add(activePath(member));
      // The client checks the minimum member level, rather than an average or the leader alone.
      if (plan.levels().values().stream().anyMatch(level -> member.getLevel() < level))
        return false;
    }
    return actors.containsAll(plan.requirements().values())
        && paths.containsAll(plan.paths().values())
        && plan.counts().values().stream().allMatch(count -> members.size() >= count);
  }

  static int roleIdentity(GameAvatar member) {
    int actor = member.getActorId();
    if (member.getAvatarType() != AvatarType.AVATAR_TRIAL_TYPE) return actor;
    var row = GameData.getMultiplePathAvatarExcelMap().get(actor);
    if (row == null) return actor >= 8001 && actor <= 8010 ? 0 : actor;
    if (row.getAvatarID() != actor
        || row.getBaseAvatarID() <= 0
        || member.getExcel() == null
        || member.getExcel().getAvatarID() != actor) return 0;
    return row.getBaseAvatarID();
  }

  private static int activePath(GameAvatar member) {
    var excel = member.getExcel();
    if (excel == null) return 0;
    int current = excel.getAvatarID();
    var row = GameData.getMultiplePathAvatarExcelMap().get(current);
    if (row == null || row.getAvatarID() != current || row.getBaseAvatarID() <= 0) return 0;
    if (member.getAvatarType() == AvatarType.AVATAR_FORMAL_TYPE) {
      if (member.getAvatarId() != row.getBaseAvatarID()) return 0;
      if (current != row.getBaseAvatarID()) {
        var active = member.getMultiPath();
        if (active == null
            || active.getAvatar() != member
            || active.getOwner() != member.getOwner()
            || active.getExcel() != excel
            || active.getExcelId() != current) return 0;
      }
    } else if (member.getAvatarType() != AvatarType.AVATAR_TRIAL_TYPE
        || member.getActorId() != current) return 0;
    if (row.getBaseAvatarID() == GameConstants.TRAILBLAZER_AVATAR_ID) {
      if (member.getOwner() == null
          || row.getGender() != member.getOwner().getGender()
          || !(row.getGender() == PlayerGender.GENDER_MAN
              || row.getGender() == PlayerGender.GENDER_WOMAN)) return 0;
      if (row.getGender() == PlayerGender.GENDER_WOMAN) {
        var male = GameData.getMultiplePathAvatarExcelMap().get(current - 1);
        if (male == null
            || male.getAvatarID() != current - 1
            || male.getBaseAvatarID() != row.getBaseAvatarID()
            || male.getGender() != PlayerGender.GENDER_MAN) return 0;
        return current - 1;
      }
    }
    return current;
  }

  private static int integer(JsonObject row, String key) {
    if (!row.has(key)) return 0;
    var value = row.get(key);
    if (!value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("0|[1-9][0-9]*"))
      throw new IllegalArgumentException("Invalid team rule integer");
    return value.getAsBigDecimal().intValueExact();
  }

  public static boolean allowed(Player player, GameplayDefinitions.Raid row) {
    return allowed(player, plan(row));
  }

  public static boolean allowed(Player player, Plan plan) {
    if (plan == null) return false;
    if (player.getMissionTrialManager().inside())
      return player.getMissionTrialManager().allowed(plan);
    if (plan != null && player.getRaidTrialManager().inside())
      return player.getRaidTrialManager().allowed(plan);
    if ("TrialOnly".equals(plan.type()) || !plan.replaceRules().isEmpty() || plan.captain() != 0)
      return false;
    var lineup = player.getCurrentLineup();
    if (plan == null || lineup == null || lineup.isExtraLineup()) return false;
    var roster = lineup.getAvatars();
    if (roster.isEmpty()
        || roster.size() > 4
        || new HashSet<>(roster).size() != roster.size()
        || lineup.getLeader() < 0
        || lineup.getLeader() >= roster.size()) return false;
    var members = new ArrayList<GameAvatar>();
    for (int id : roster) {
      var avatar = player.getAvatarById(id);
      if (avatar == null
          || avatar.getAvatarId() != id
          || avatar.getAvatarType() != AvatarType.AVATAR_FORMAL_TYPE
          || player.getAvatars().getAvatars().get(id) != avatar) return false;
      members.add(avatar);
    }
    return membersAllowed(plan, members);
  }

  public static boolean matches(GameplayState.MissionRaidRun run, GameplayDefinitions.Raid row) {
    var plan = plan(row);
    if (run == null || plan == null) return false;
    var saved = run.getFormalTeamPlan();
    if (saved == null) return !snapshotRequired(plan);
    try {
      return plan.type().equals(saved.getType())
          && plan.trials().equals(saved.getTrials())
          && plan.requirements().equals(saved.getRequirements())
          && plan.replaceRules().equals(saved.getReplaceRules())
          && plan.captain() == saved.getCaptain()
          && plan.levels().equals(saved.getLevels())
          && plan.counts().equals(saved.getCounts())
          && plan.paths().equals(saved.getPaths());
    } catch (NullPointerException | IllegalArgumentException corrupt) {
      return false;
    }
  }

  private RaidTeams() {}
}
