package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Project rule: one owned formal avatar per classic download console; no automatic deployment. */
public final class RogueDownload {
  private static String key(Player p, int group, int instance) {
    var run = p.getGameplay().getRogue();
    return run.getSiteId() + ":" + run.getRoomId() + ":" + group + ":" + instance;
  }

  public static boolean used(Player p, int group, int instance) {
    return p.getGameplay().getRogue().getUsedDownloadConsoles().contains(key(p, group, instance));
  }

  public static Retcode pick(Player p, List<Integer> ids, int entity) {
    synchronized (p) {
      var run = p.getGameplay().getRogue();
      if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
          || !run.isInside()
          || run.getRoster().isEmpty()
          || run.pending()
          || p.isInBattle()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (ids == null
          || ids.size() != 1
          || run.getInitialAvatarSp() < 0
          || run.getInitialAvatarSp() > 10000) return Retcode.REQ_PARA_INVALID;
      if (ids.getFirst() == null) return Retcode.REQ_PARA_INVALID;
      int id = ids.getFirst();
      var avatar = p.getAvatarById(id);
      if (id <= 0
          || avatar == null
          || avatar.getAvatarId() != id
          || !ownedFormal(p, avatar, id)
          || avatar.getAvatarType()
              != emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType.AVATAR_FORMAL_TYPE
          || run.getVitals().containsKey(id)) return Retcode.ROGUE_PICK_AVATAR_INVALID;
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
      var scene = p.getScene();
      if (room == null
          || room.RogueRoomType() != 5
          || entry == null
          || scene == null
          || scene.getPlaneType() != PlaneType.Rogue
          || p.getEntryId() != room.MapEntrance()
          || entry.getPlaneID() != scene.getPlaneId()
          || entry.getFloorID() != scene.getFloorId()
          || scene.getFloorInfo() == null
          || !(scene.getEntityById(entity) instanceof EntityProp prop)
          || prop.getScene() != scene
          || prop.getPropId() != 1001
          || prop.getExcel() != GameData.getPropExcelMap().get(1001)
          || prop.getPropType() != PropType.PROP_ROGUE_OBJECT
          || !prop.getExcel().getPropStateList().contains(PropState.Locked)
          || !Set.of(PropState.Closed, PropState.Open).contains(prop.getState())
          || prop.getInstId() <= 0
          || prop.getPropInfo() == null
          || prop.getPropInfo().getID() != prop.getInstId()
          || prop.getPropInfo().getPropID() != 1001
          || prop.getPropInfo().isIsDelete()
          || prop.getPropInfo().isIsClientOnly()
          || !prop.getPropInfo().isLoadOnInitial()
          || !UniverseEntityLoader.roomGroups(p).contains(prop.getGroupId()))
        return Retcode.SCENE_ENTITY_NOT_EXIST;
      var group = scene.getFloorInfo().getGroups().get(prop.getGroupId());
      if (group == null
          || group.getPropList() == null
          || group.getPropList().stream()
              .noneMatch(
                  info ->
                      info.getID() == prop.getInstId()
                          && info.getPropID() == 1001
                          && !info.isIsDelete()
                          && !info.isIsClientOnly()
                          && info.isLoadOnInitial())) return Retcode.SCENE_ENTITY_NOT_EXIST;
      String key = key(p, prop.getGroupId(), prop.getInstId());
      if (run.getUsedDownloadConsoles().contains(key)) return Retcode.REQ_PARA_INVALID;
      run.getVitals()
          .put(
              id,
              new GameplayState.RogueVitals(
                  avatar.getMaxHp(),
                  (int) ((long) avatar.getMaxSp() * run.getInitialAvatarSp() / 10000)));
      run.getUsedDownloadConsoles().add(key);
      p.save();
      prop.setState(PropState.Locked, p.isOnline());
      return Retcode.SUCC;
    }
  }

  private static boolean ownedFormal(
      Player player, emu.lunarcore.game.avatar.GameAvatar avatar, int id) {
    var excel = avatar.getExcel();
    if (player.getAvatars().getAvatars().get(id) != avatar
        || avatar.getOwner() != player
        || avatar.getOwnerUid() != player.getUid()
        || avatar.getExcelId() != id
        || excel == null
        || excel != GameData.getAvatarExcelMap().get(excel.getId())) return false;
    var path = avatar.getMultiPath();
    if (path == null) return excel.getId() == id;
    var definition = GameData.getMultiplePathAvatarExcelMap().get(path.getExcelId());
    return definition != null
        && definition.getBaseAvatarID() == id
        && path.getExcelId() == excel.getId()
        && path.getExcel() == excel
        && path.getOwner() == player
        && path.getOwnerUid() == player.getUid()
        && path.getAvatar() == avatar
        && player.getAvatars().getMultiPaths().get(path.getExcelId()) == path;
  }

  private RogueDownload() {}
}
