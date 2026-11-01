package org.hkrpg.server.gameplay;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import java.util.Collection;
import java.util.Set;

/** Client-created actor events, kept separate from events the server asks the client to spawn. */
public final class AvatarBattleEvents {
  public static Set<Integer> capture(Player player, Collection<GameAvatar> participants) {
    // Fixed 4.6 BattleEventConfig: 11203 is Jing Yuan's TeamNeutral/DummyCharacter,
    // with AvatarIconTeam/1204Shadow.png and the two BattleEventAbility_JingYuan passives.
    // Trial special IDs must resolve to this actor; owning him outside the party is insufficient.
    boolean jingYuan = participants.stream().anyMatch(avatar -> avatar.getOwner() == player
        && avatar.getExcel() != null && avatar.getExcel().getAvatarID() == 1204
        && avatar.getActorId() == 1204);
    return jingYuan ? Set.of(11203) : Set.of();
  }

  private AvatarBattleEvents() {}
}
