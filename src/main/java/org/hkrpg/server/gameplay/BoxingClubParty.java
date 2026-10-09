package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar;
import java.time.Instant;
import java.util.*;

/** Formal and original source trial actors; full HP/half SP never writes ordinary vitals. */
final class BoxingClubParty extends PlayerLineup {
  private final List<Integer> roster;
  private final List<GameAvatar> actors;
  private final List<byte[]> original, snapshots;
  private final int world, gender;
  private final int challenge;
  private final List<BoxingClubManager.Member> members;

  private BoxingClubParty(
      Player p, int challenge, List<BoxingClubManager.Member> members, List<GameAvatar> actors) {
    super(p, 0);
    this.challenge = challenge;
    this.members = List.copyOf(members);
    this.roster = members.stream().map(BoxingClubManager.Member::id).toList();
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
    return ids == null
        ? null
        : prepare(
            p,
            0,
            ids.stream()
                .map(id -> id == null ? null : new BoxingClubManager.Member(id, 1))
                .toList(),
            Instant.now());
  }

  static BoxingClubParty prepare(
      Player p, int challenge, List<BoxingClubManager.Member> members, Instant now) {
    if (p.getUid() <= 0
        || members == null
        || members.isEmpty()
        || members.size() > 4
        || members.stream()
            .anyMatch(m -> m == null || m.id() <= 0 || m.type() != 1 && m.type() != 3)
        || members.stream().map(BoxingClubManager.Member::id).distinct().count() != members.size())
      return null;
    var actors = new ArrayList<GameAvatar>();
    var characters = new HashSet<Integer>();
    for (var member : members) {
      int id = member.id();
      GameAvatar a;
      if (member.type() == 1) {
        a = p.getAvatars().getAvatars().get(id);
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
      } else {
        if (!trialAvailable(p, challenge, now) || p.getAvatars().getTempAvatars().containsKey(id))
          return null;
        var row = trialRow(p, challenge, id);
        if (row == null) return null;
        a = p.getTrialManager().buildTrialAvatar(id);
        if (!(a instanceof TrialAvatar trial)
            || trial.getSpecialAvatarExcel() != row
            || a.getBattleAvatarId() != id
            || a.getOwner() != p
            || a.getOwnerUid() != p.getUid()
            || GameData.getAvatarExcelMap().get(a.getExcelId()) != a.getExcel()
            || row.getEquipmentID() > 0 && trial.getEquipment() == null) return null;
      }
      var path = GameData.getMultiplePathAvatarExcelMap().get(a.getActorId());
      int character = path == null ? a.getActorId() : path.getBaseAvatarID();
      if (character <= 0 || !characters.add(character)) return null;
      actors.add(a);
    }
    return new BoxingClubParty(p, challenge, members, actors);
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
      if (a instanceof TrialAvatar trial) {
        if (!trialAvailable(p, challenge, Instant.now())
            || trialRow(p, challenge, roster.get(i)) != trial.getSpecialAvatarExcel()
            || p.getAvatars().getTempAvatars().containsKey(roster.get(i))) return false;
        var fresh = p.getTrialManager().buildTrialAvatar(roster.get(i));
        if (fresh == null
            || !Arrays.equals(original.get(i), fresh.toBattleProto(null, i).toByteArray()))
          return false;
      } else if (p.getAvatars().getAvatars().get(roster.get(i)) != a
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

  private static boolean trialAvailable(Player p, int challenge, Instant now) {
    if (challenge >= 6) {
      var source = BoxingClubResonanceTrials.load();
      return source != null && source.available(p, challenge, now);
    }
    var source = BoxingClubTrials.load();
    return source != null && source.available(p, challenge, now);
  }

  private static emu.lunarcore.data.excel.SpecialAvatarExcel trialRow(
      Player p, int challenge, int id) {
    if (challenge >= 6) {
      var source = BoxingClubResonanceTrials.load();
      return source == null ? null : source.row(p, challenge, id);
    }
    var source = BoxingClubTrials.load();
    return source == null ? null : source.row(p, challenge, id);
  }
}
