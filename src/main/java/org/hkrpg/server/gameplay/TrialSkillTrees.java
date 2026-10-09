package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.AvatarSkillTreeExcel;
import emu.lunarcore.data.excel.TrialActorExcel;
import emu.lunarcore.game.trial.TrialAvatar;
import java.util.*;

/** Resource enhancement, actual custom presets and legacy resource eligibility. */
public final class TrialSkillTrees {
  private static AvatarSkillTreeExcel row(AvatarExcel actor, int point) {
    var row =
        actor instanceof TrialActorExcel
            ? GameData.getTrialActorSkillTreeExcelMap().get(point, 1)
            : GameData.getAvatarSkillTreeExcelMap().get(point, 1);
    return row != null && row.getAvatarID() == actor.getAvatarID() ? row : null;
  }

  public static boolean supports(AvatarExcel actor, int enhancement) {
    if (enhancement < 0) return false;
    if (enhancement == 0) return true;
    for (int point : actor.getSkillTreeIds()) {
      var row = row(actor, point);
      if (row != null
          && row.getEnhancedID() == enhancement
          && row.getAnchorId() > 0
          && row.getMaxLevel() > 0
          && GameData.getAvatarSkilltree(actor.getAvatarID(), enhancement, row.getAnchorId(), 1)
              == row) return true;
    }
    return false;
  }

  public static void initialize(TrialAvatar avatar) {
    var actor = avatar.getExcel();
    int enhancement = avatar.getSpecialAvatarExcel().getEnhancedID();
    if (!supports(actor, enhancement))
      throw new IllegalArgumentException("Missing trial skill variant");
    avatar.getData().setEnhanceId(enhancement);
    avatar.getSkillTree().clear();
    var plan = avatar.getSkillPlan();
    var candidates = new LinkedHashMap<Integer, AvatarSkillTreeExcel>();
    var levels = new HashMap<Integer, Integer>();
    for (int point : actor.getSkillTreeIds()) {
      var row = row(actor, point);
      if (row == null
          || row.getEnhancedID() != enhancement
          || row.getAnchorId() <= 0
          || row.getMaxLevel() <= 0) continue;
      int requested = plan.requested(row);
      if (requested < 0 || requested > row.getMaxLevel())
        throw new IllegalArgumentException("Trial preset level exceeds its resource");
      int selected = 0;
      for (int level = 1; level <= requested; level++) {
        var next =
            actor instanceof TrialActorExcel
                ? GameData.getTrialActorSkillTreeExcelMap().get(point, level)
                : GameData.getAvatarSkillTreeExcelMap().get(point, level);
        if (next == null
            || next.getAvatarID() != actor.getAvatarID()
            || next.getEnhancedID() != enhancement
            || next.getAnchorId() != row.getAnchorId()
            || next.getMaxLevel() != row.getMaxLevel()
            || next.getAvatarLevelLimit() < 0
            || next.getAvatarPromotionLimit() < 0
            || plan.characterLimits(row.getAnchorId(), level, next.isDefaultUnlock())
                && (next.getAvatarLevelLimit() > avatar.getLevel()
                    || next.getAvatarPromotionLimit() > avatar.getPromotion())
            || GameData.getAvatarSkilltree(
                    actor.getAvatarID(), enhancement, row.getAnchorId(), level)
                != next) break;
        selected = level;
      }
      if (selected > 0) {
        candidates.put(point, row);
        levels.put(point, selected);
      }
      if (plan.custom()
          && requested > 0
          && !plan.characterLimits(row.getAnchorId(), requested, row.isDefaultUnlock())
          && selected != requested)
        throw new IllegalArgumentException("Missing exact trial preset skill level");
    }
    if (plan.custom()) {
      candidates.forEach(
          (point, row) ->
              avatar.getSkillTree().put(row.getAnchorId(), levels.get(point).intValue()));
      return;
    }
    // Publish roots first; missing parents and dependency cycles never become unlocked.
    var unlocked = new HashSet<Integer>();
    boolean changed;
    do {
      changed = false;
      for (var entry : candidates.entrySet()) {
        if (unlocked.contains(entry.getKey())) continue;
        var parents = entry.getValue().getPrePoint();
        if (parents != null && !unlocked.containsAll(parents)) continue;
        unlocked.add(entry.getKey());
        avatar
            .getSkillTree()
            .put(entry.getValue().getAnchorId(), levels.get(entry.getKey()).intValue());
        changed = true;
      }
    } while (changed);
  }

  /** Restore the actual entry-time levels; do not recalculate a party from newly added nodes. */
  public static void restore(TrialAvatar avatar, Map<Integer, Integer> skills) {
    if (skills == null) throw new IllegalStateException("Missing frozen trial skills");
    var points = new HashSet<Integer>();
    var rows = new ArrayList<AvatarSkillTreeExcel>();
    var plan = avatar.getSkillPlan();
    for (var entry : skills.entrySet()) {
      if (entry.getKey() == null
          || entry.getValue() == null
          || entry.getKey() <= 0
          || entry.getValue() <= 0) throw new IllegalStateException("Invalid frozen trial skill");
      var row =
          GameData.getAvatarSkilltree(
              avatar.getActorId(), avatar.getEnhanceId(), entry.getKey(), entry.getValue());
      var exact =
          row == null
              ? null
              : avatar.getExcel() instanceof TrialActorExcel
                  ? GameData.getTrialActorSkillTreeExcelMap()
                      .get(row.getPointID(), entry.getValue())
                  : GameData.getAvatarSkillTreeExcelMap().get(row.getPointID(), entry.getValue());
      if (row == null
          || row != exact
          || row.getAvatarID() != avatar.getActorId()
          || row.getEnhancedID() != avatar.getEnhanceId()
          || row.getAnchorId() != entry.getKey()
          || row.getLevel() != entry.getValue()
          || row.getMaxLevel() < entry.getValue()
          || row.getAvatarLevelLimit() < 0
          || row.getAvatarPromotionLimit() < 0
          || plan.characterLimits(entry.getKey(), entry.getValue(), row.isDefaultUnlock())
              && (row.getAvatarLevelLimit() > avatar.getLevel()
                  || row.getAvatarPromotionLimit() > avatar.getPromotion()))
        throw new IllegalStateException("Unavailable frozen trial skill");
      var first = row(avatar.getExcel(), row.getPointID());
      if (first == null || plan.requested(first) < entry.getValue())
        throw new IllegalStateException("Frozen trial skill does not belong to its preset");
      rows.add(row);
      points.add(row.getPointID());
      for (int level = 1; level <= entry.getValue(); level++) {
        var previous =
            avatar.getExcel() instanceof TrialActorExcel
                ? GameData.getTrialActorSkillTreeExcelMap().get(row.getPointID(), level)
                : GameData.getAvatarSkillTreeExcelMap().get(row.getPointID(), level);
        if (previous == null
            || previous.getAvatarID() != avatar.getActorId()
            || previous.getEnhancedID() != avatar.getEnhanceId()
            || previous.getAnchorId() != entry.getKey()
            || previous.getMaxLevel() != row.getMaxLevel()
            || previous.getAvatarLevelLimit() < 0
            || previous.getAvatarPromotionLimit() < 0
            || plan.characterLimits(entry.getKey(), level, previous.isDefaultUnlock())
                && (previous.getAvatarLevelLimit() > avatar.getLevel()
                    || previous.getAvatarPromotionLimit() > avatar.getPromotion())
            || GameData.getAvatarSkilltree(
                    avatar.getActorId(), avatar.getEnhanceId(), entry.getKey(), level)
                != previous)
          throw new IllegalStateException("Unavailable intermediate frozen trial skill");
      }
    }
    if (!plan.custom()) {
      for (var row : rows)
        if (row.getPrePoint() != null && !points.containsAll(row.getPrePoint()))
          throw new IllegalStateException("Unavailable frozen trial skill prerequisite");
      var unlocked = new HashSet<Integer>();
      boolean changed;
      do {
        changed = false;
        for (var row : rows) {
          if (unlocked.contains(row.getPointID())) continue;
          if (row.getPrePoint() != null && !unlocked.containsAll(row.getPrePoint())) continue;
          unlocked.add(row.getPointID());
          changed = true;
        }
      } while (changed);
      if (!unlocked.equals(points))
        throw new IllegalStateException("Cyclic frozen trial skill prerequisites");
    }
    avatar.getSkillTree().clear();
    skills.forEach(
        (anchor, level) -> avatar.getSkillTree().put(anchor.intValue(), level.intValue()));
  }

  private TrialSkillTrees() {}
}
