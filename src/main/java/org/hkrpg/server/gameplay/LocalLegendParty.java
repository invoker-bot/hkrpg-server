package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar;
import java.util.*;

/** Frozen battle-only actors; no formal roster, vitals, equipment or temp storage writes. */
public final class LocalLegendParty extends PlayerLineup {
  public record Member(int id, int type) {}

  private final List<Integer> roster;
  private final Map<Integer, GameAvatar> actors;
  private final List<byte[]> snapshots;
  private final int world, gender;

  private LocalLegendParty(Player player, List<GameAvatar> selected) {
    super(player, 0);
    world = player.getWorldLevel();
    gender = player.getGender().getVal();
    roster = selected.stream().map(GameAvatar::getBattleAvatarId).toList();
    var map = new HashMap<Integer, GameAvatar>();
    var bytes = new ArrayList<byte[]>();
    for (int i = 0; i < selected.size(); i++) {
      var actor = selected.get(i);
      map.put(actor.getBattleAvatarId(), actor);
      var proto = actor.toBattleProto(null, i);
      // This bounded entry policy is selected only for the matched source stage setting.
      proto.setHp(actor.getMaxHp());
      proto.getMutableSpBar().setCurSp(actor.getMaxSp() / 2).setMaxSp(actor.getMaxSp());
      bytes.add(proto.toByteArray());
    }
    actors = Map.copyOf(map);
    snapshots = List.copyOf(bytes);
  }

  static LocalLegendParty prepare(
      Player player, LocalLegendCatalog.Level level, List<Member> requested) {
    if (requested == null || requested.isEmpty() || requested.size() > 4 || player.getUid() <= 0)
      return null;
    var ids = new HashSet<Integer>();
    var characters = new HashSet<Integer>();
    var selected = new ArrayList<GameAvatar>();
    for (var member : requested) {
      if (member == null || member.id() <= 0 || !ids.add(member.id())) return null;
      GameAvatar actor;
      if (member.type() == 1) {
        actor = player.getAvatars().getAvatars().get(member.id());
        if (actor == null
            || actor.getAvatarType() != AvatarType.AVATAR_FORMAL_TYPE
            || actor.getAvatarId() != member.id()
            || actor.getBattleAvatarId() != member.id()
            || actor.getOwner() != player
            || actor.getOwnerUid() != player.getUid()
            || actor.getId() == null
            || player.getAvatars().getAvatarObjectIdMap().get(actor.getId()) != actor
            || GameData.getAvatarExcelMap().get(actor.getExcelId()) != actor.getExcel()
            || player.getAvatars().getTempAvatars().containsKey(member.id())) return null;
      } else if (member.type() == 3) {
        if (!level.trials().contains(member.id())) return null;
        var row = player.getTrialManager().specialRow(member.id());
        if (row == null || row.getId() != member.id() || !"TYPE_TRIAL".equals(row.getType()))
          return null;
        actor = player.getTrialManager().buildTrialAvatar(member.id());
        if (!(actor instanceof TrialAvatar trial)
            || trial.getBattleAvatarId() != member.id()
            || trial.getSpecialAvatarExcel() != row
            || row.getEquipmentID() > 0 && trial.getEquipment() == null) return null;
      } else return null;
      var path = GameData.getMultiplePathAvatarExcelMap().get(actor.getActorId());
      int character = path == null ? actor.getActorId() : path.getBaseAvatarID();
      if (character <= 0 || !characters.add(character)) return null;
      selected.add(actor);
    }
    return new LocalLegendParty(player, selected);
  }

  @Override
  public List<Integer> getAvatars() {
    return roster;
  }

  @Override
  public boolean isExtraLineup() {
    return true;
  }

  @Override
  public void save() {
    /* Battle-local view has no lineup document. */
  }

  @Override
  public void replace(List<Integer> ignored) {
    throw new UnsupportedOperationException("Frozen battle party");
  }

  boolean valid() {
    var player = getOwner();
    if (player.getWorldLevel() != world || player.getGender().getVal() != gender) return false;
    for (var actor : actors.values()) {
      if (actor.getOwner() != player || actor.getOwnerUid() != player.getUid()) return false;
      if (!(actor instanceof TrialAvatar)
          && (player.getAvatars().getAvatars().get(actor.getAvatarId()) != actor
              || player.getAvatars().getAvatarObjectIdMap().get(actor.getId()) != actor))
        return false;
    }
    return true;
  }

  List<BattleAvatar> battleActors() {
    try {
      var values = new ArrayList<BattleAvatar>();
      for (var bytes : snapshots) values.add(BattleAvatar.parseFrom(bytes));
      return values;
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
