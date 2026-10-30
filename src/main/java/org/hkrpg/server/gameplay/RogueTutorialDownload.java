package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Map 2's authored Herta download, distinct from ordinary rest-room downloads. */
public final class RogueTutorialDownload {
  public static final int TRIAL = 4000005;
  private static final String RECEIPT = "1:201:4:300002";
  private static final String SCRIPT = "Config/Level/Maze/MazeRogue/Maze_Rogue_Tutorial_PickMachine.json";

  static boolean trialSource(SpecialAvatarExcel row) {
    return row != null && row.getId() == TRIAL && row.getAvatarID() == 1013
        && row.getPlayerID() == 1013 && row.getLevel() == 6 && row.getPromotion() == 0
        && row.getRank() == 0 && row.getEnhancedID() == 0 && row.getWorldLevel() == 0
        && !row.isIsUseWorldLevel() && "TYPE_TRIAL".equals(row.getType())
        && "TYPE_CUSTOM".equals(row.getSkillTreeTemplate())
        && "None".equals(row.getCustomSkillTreeKey())
        && row.getEquipmentID() == 0 && row.getRelicMainValue() == 0
        && row.getRelicSubValue() == 0 && row.getRelicPropertyType() == 0
        && row.getRelicPropertyTypeExtra() == 0 && row.getOverrideProperty() != null
        && row.getOverrideProperty().isEmpty() && row.getAbilityNameList() != null
        && row.getAbilityNameList().isEmpty();
  }

  private static boolean progress(GameplayState.RogueRun run) {
    return run.getAreaId() == 100 && run.getMapId() == 2
        && Map.of(1, 201, 2, 202, 3, 203).equals(run.getRooms())
        && run.getDefeatedRogueMonsters().contains("1:201:80001:80001002:4:200001")
        && run.getSecondStoryBlessingReceipts().getOrDefault(4, 0) == -1;
  }

  public static boolean downloaded(GameplayState.RogueRun run, int id) {
    return id == TRIAL && progress(run) && run.getUsedDownloadConsoles().contains(RECEIPT)
        && run.getDownloadedTrials().containsKey(id) && run.getVitals().containsKey(id);
  }

  static boolean machine(Scene scene, GroupInfo group, PropInfo info) {
    if (scene == null || scene.getPlaneType() != PlaneType.Rogue || scene.getPlaneId() != 80001
        || scene.getFloorId() != 80001002
        || scene.getDimensionId() != 0 || scene.getFloorInfo() == null || group == null
        || group.getId() != 4 || scene.getResourceGroups().get(4) != group
        || info == null || info.getID() != 300002 || info.getPropID() != 1001
        || info.isIsDelete() || info.isIsClientOnly() || !info.isLoadOnInitial()
        || info.getState() != PropState.Locked || !SCRIPT.equals(info.getInitLevelGraph())
        || group.getPropList().stream().filter(p -> p.getID() == 300002).count() != 1) return false;
    var run = scene.getPlayer().getGameplay().getRogue();
    var excel = GameData.getPropExcelMap().get(1001);
    return run.isInside() && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        && run.getSiteId() == 1 && run.getRoomId() == 201
        && RogueSecondStoryEntry.room(run, GameplayDefinitions.rooms.get(201))
        && excel != null && excel.getPropStateList().containsAll(Set.of(PropState.Open, PropState.Closed, PropState.Locked));
  }

  static PropState state(Scene scene, GroupInfo group, PropInfo info) {
    if (!machine(scene, group, info)) return null;
    var run = scene.getPlayer().getGameplay().getRogue();
    if (!RogueTutorialCombat.restoresTrigger(scene, 4, 300001)) return PropState.Locked;
    if (downloaded(run, TRIAL)) return PropState.Open;
    return progress(run) ? PropState.Closed : PropState.Locked;
  }

  static boolean barrier(Scene scene, GroupInfo group, PropInfo info) {
    if (scene == null || info == null || group == null || group.getId() != 19 || info.getID() != 300005
        || !RogueTutorialCombat.retainsBarrierSource(scene.getFloorInfo(), group, info)) return false;
    var source = scene.getResourceGroups().get(4);
    return source != null && source.getPropList().stream().anyMatch(p -> machine(scene, source, p))
        && RogueTutorialCombat.restoresTrigger(scene, 4, 300001)
        && downloaded(scene.getPlayer().getGameplay().getRogue(), TRIAL);
  }

  static void sync(Player player) {
    var scene = player.getScene();
    if (scene == null) return;
    for (var prop : scene.getEntities(EntityProp.class)) {
      var group = scene.getResourceGroups().get(prop.getGroupId());
      var state = state(scene, group, prop.getPropInfo());
      if (state != null) prop.setState(state);
      else if (prop.getPropId() == 100006 && barrier(scene, group, prop.getPropInfo()))
        prop.setState(PropState.Open);
    }
    // A durable receipt can replay this idempotent event after a disconnect/save failure.
    if (downloaded(player.getGameplay().getRogue(), TRIAL)
        && MissionStateRecords.doing(player.getGameplay().getSubMissions().get(403000207), 403000207, 1))
      player.getMissionManager().onPropState(80001002, 4, 300002, PropState.Open.getVal());
  }

  public static Retcode pick(Player player, List<Integer> ids, int entity) {
    synchronized (player) {
      var run = player.getGameplay().getRogue(); var scene = player.getScene();
      if (!List.of(TRIAL).equals(ids)) return Retcode.ROGUE_PICK_AVATAR_INVALID;
      if (player.isInBattle() || run.pending() || run.getRoster().isEmpty()
          || !progress(run)) return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (scene == null || scene.getPlayer() != player || player.getEntryId() != 8000102
          || !(scene.getEntityById(entity) instanceof EntityProp prop) || prop.getScene() != scene
          || !scene.getLoadedGroups().contains(4) || prop.getGroupId() != 4
          || prop.getInstId() != 300002 || prop.getPropId() != 1001
          || !machine(scene, scene.getResourceGroups().get(4), prop.getPropInfo())
          || !RogueTutorialCombat.restoresTrigger(scene, 4, 300001))
        return Retcode.SCENE_ENTITY_NOT_EXIST;
      if (downloaded(run, TRIAL)) { sync(player); return Retcode.SUCC; }
      if (prop.getState() != PropState.Closed || run.getVitals().containsKey(TRIAL)
          || run.getDownloadedTrials().containsKey(TRIAL) || run.getUsedDownloadConsoles().contains(RECEIPT)
          || run.getInitialAvatarSp() < 0 || run.getInitialAvatarSp() > 10000)
        return Retcode.ROGUE_PICK_AVATAR_INVALID;
      if (!trialSource(player.getTrialManager().specialRow(TRIAL))) return Retcode.REQ_PARA_INVALID;
      var avatar = player.getTrialManager().buildTrialAvatar(TRIAL);
      if (avatar == null) return Retcode.REQ_PARA_INVALID;
      var frozen = new RogueTrialState(avatar);
      try { frozen.restore(player); } catch (RuntimeException invalid) { return Retcode.REQ_PARA_INVALID; }
      run.getDownloadedTrials().put(TRIAL, frozen);
      run.getVitals().put(TRIAL, new GameplayState.RogueVitals(avatar.getMaxHp(),
          (int) ((long) avatar.getMaxSp() * run.getInitialAvatarSp() / 10000)));
      run.getUsedDownloadConsoles().add(RECEIPT);
      try { player.save(); }
      catch (RuntimeException failed) {
        run.getDownloadedTrials().remove(TRIAL); run.getVitals().remove(TRIAL);
        run.getUsedDownloadConsoles().remove(RECEIPT); throw failed;
      }
      sync(player);
      return Retcode.SUCC;
    }
  }

  private RogueTutorialDownload() {}
}
