package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar;
import java.util.*;

/** Formal owned roster, frozen per battle; full HP/half SP never writes ordinary vitals. */
final class BoxingClubParty extends PlayerLineup {
  private final List<Integer> roster;
  private final List<GameAvatar> actors;
  private final List<byte[]> original, snapshots;
  private final int world, gender;

  private BoxingClubParty(Player p, List<Integer> roster, List<GameAvatar> actors) {
    super(p, 0);
    this.roster = List.copyOf(roster);
    this.actors = List.copyOf(actors);
    world = p.getWorldLevel();
    gender = p.getGender().getVal();
    original = new ArrayList<>();
    snapshots = new ArrayList<>();
    for (int i = 0; i < actors.size(); i++) {
      var actor = actors.get(i);
      var b = actor.toBattleProto(null, i);
      original.add(b.toByteArray());
      b.setHp(actor.getMaxHp());
      b.getMutableSpBar().setCurSp(actor.getMaxSp() / 2).setMaxSp(actor.getMaxSp());
      snapshots.add(b.toByteArray());
    }
  }

  static BoxingClubParty prepare(Player p, List<Integer> ids) {
    if (p.getUid() <= 0
        || ids == null
        || ids.isEmpty()
        || ids.size() > 4
        || ids.stream().anyMatch(id -> id == null || id <= 0)
        || new HashSet<>(ids).size() != ids.size()) return null;
    var actors = new ArrayList<GameAvatar>();
    var characters = new HashSet<Integer>();
    for (int id : ids) {
      var a = p.getAvatars().getAvatars().get(id);
      if (a == null
          || a.getAvatarType() != AvatarType.AVATAR_FORMAL_TYPE
          || a.getAvatarId() != id
          || a.getBattleAvatarId() != id
          || a.getOwner() != p
          || a.getOwnerUid() != p.getUid()
          || a.getId() == null
          || p.getAvatars().getAvatarObjectIdMap().get(a.getId()) != a
          || p.getAvatars().getTempAvatars().containsKey(id)
          || GameData.getAvatarExcelMap().get(a.getExcelId()) != a.getExcel()) return null;
      var path = GameData.getMultiplePathAvatarExcelMap().get(a.getActorId());
      int character = path == null ? a.getActorId() : path.getBaseAvatarID();
      if (character <= 0 || !characters.add(character)) return null;
      actors.add(a);
    }
    return new BoxingClubParty(p, ids, actors);
  }

  public List<Integer> getAvatars() {
    return roster;
  }

  public boolean isExtraLineup() {
    return true;
  }

  public void save() {}

  public void replace(List<Integer> ignored) {
    throw new UnsupportedOperationException("Frozen boxing party");
  }

  boolean valid() {
    var p = getOwner();
    if (p.getWorldLevel() != world || p.getGender().getVal() != gender) return false;
    for (int i = 0; i < actors.size(); i++) {
      var a = actors.get(i);
      if (p.getAvatars().getAvatars().get(roster.get(i)) != a
          || p.getAvatars().getAvatarObjectIdMap().get(a.getId()) != a
          || a.getOwner() != p
          || a.getOwnerUid() != p.getUid()
          || !Arrays.equals(original.get(i), a.toBattleProto(null, i).toByteArray())) return false;
    }
    return true;
  }

  List<BattleAvatar> battleActors() {
    try {
      var result = new ArrayList<BattleAvatar>();
      for (var b : snapshots) result.add(BattleAvatar.parseFrom(b));
      return List.copyOf(result);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
