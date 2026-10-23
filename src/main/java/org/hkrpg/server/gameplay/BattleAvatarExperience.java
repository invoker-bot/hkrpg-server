package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import java.util.*;

/** Ordinary-world monster XP, frozen from server combatants, never from a client XP report. */
public final class BattleAvatarExperience {
  public record Grant(GameAvatar avatar, AvatarExperience.Progress progress) {}

  public static int capture(Battle battle) {
    if (battle.getPlayer().getInstance() != null || battle.getLineup().isExtraLineup()) return 0;
    var seen = Collections.newSetFromMap(new IdentityHashMap<EntityMonster, Boolean>());
    int total = 0;
    for (var monster : battle.getNpcMonsters()) {
      var scene = monster.getScene();
      if (!seen.add(monster) || !battle.hasMazeSkillTarget(monster) || monster.isFarmElement()
          || scene == null || scene.getStoryLineId() != 0
          || scene.getPlaneType() != PlaneType.Town && scene.getPlaneType() != PlaneType.Maze) continue;
      var row = GameData.getMonsterDropExcelMap().get(monster.getExcel().getId(), monster.getWorldLevel());
      if (row == null) continue;
      int amount = row.getAvatarExpReward();
      if (amount < 0 || amount > Integer.MAX_VALUE - total)
        throw new IllegalStateException("Invalid monster character XP reward");
      total += amount;
    }
    return total;
  }

  /** Null means inconsistent character progression; the battle must remain unsettled. */
  public static List<Grant> prepare(Battle battle) {
    if (battle.getAvatarExperienceReward() == 0) return List.of();
    var player = battle.getPlayer();
    var grants = new ArrayList<Grant>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<GameAvatar, Boolean>());
    for (var avatar : battle.getParticipants().values()) {
      // Use the actual owned object, not a trial with the same ID, or the post-victory party.
      if (!seen.add(avatar) || avatar.getOwner() != player || avatar.getOwnerUid() != player.getUid()
          || player.getAvatars().getAvatars().get(avatar.getAvatarId()) != avatar) continue;
      var progress = AvatarExperience.prepare(avatar, battle.getAvatarExperienceReward());
      if (progress == null) return null;
      if (progress.applied() > 0) grants.add(new Grant(avatar, progress));
    }
    return List.copyOf(grants);
  }

  /** Called once, under the player settlement lock, before story callbacks can replace the party. */
  public static void grant(Battle battle, List<Grant> grants) {
    for (var grant : grants) {
      var avatar = grant.avatar();
      int oldLevel = avatar.getLevel(), oldExp = avatar.getExp();
      avatar.setLevel(grant.progress().level());
      avatar.setExp(grant.progress().exp());
      try { avatar.save(); }
      catch (RuntimeException failure) {
        avatar.setLevel(oldLevel); avatar.setExp(oldExp);
        throw failure;
      }
      battle.getPlayer().sendPacket(new PacketPlayerSyncScNotify(avatar));
      if (avatar.getLevel() > oldLevel) battle.getPlayer().getDailyManager().onAvatarLevelUp();
    }
  }

  private BattleAvatarExperience() {}
}
