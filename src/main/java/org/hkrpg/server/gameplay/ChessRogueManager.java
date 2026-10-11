package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Original tutorial progression with explicitly declared server physical routing and economy. */
public final class ChessRogueManager {
  private final Player player;
  private ChessRogueState lineupRun;
  private ChessRogueLineup lineup;
  private Battle started;
  private Combat combat;
  private record Combat(Battle battle, ChessRogueState run, Scene scene, ChessRogueLineup lineup,
      int layer, int cell, EntityMonster monster, int entity, int group, int instance,
      GameplayDefinitions.RogueMonster row, BattleStage stage, List<BattleMonsterWave> waves, int level) {}

  public ChessRogueManager(Player player) { this.player = player; }
  public ChessRogueLineup lineup() {
    var run = player.getGameplay().getChessRogue();
    if (lineupRun != run) { lineupRun = run; lineup = new ChessRogueLineup(player,run); }
    return lineup;
  }
  public boolean owns(Battle battle) { return started == battle || combat != null && combat.battle() == battle; }

  private boolean occupied(boolean includeChess) {
    var gameplay = player.getGameplay();
    var classic = gameplay.getRogue();
    return includeChess && gameplay.getChessRogue().active()
        || classic.isInside() || classic.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        || player.isInBattle() || player.getInstance() != null
        || gameplay.getStoryLines().getCurrentId() != 0 || gameplay.getStoryLines().isTransitionPending()
        || gameplay.getParkourVenue().inside() || gameplay.getParkour().getActiveLevel() != 0
        || gameplay.getRhythm().getActiveLevel() != 0 || gameplay.getClockPark().getActive() != null
        || gameplay.getCurrentMissionRaid() != 0;
  }
  private boolean rosterValid(List<Integer> ids) {
    return ids != null && !ids.isEmpty() && ids.size() <= 4 && new HashSet<>(ids).size() == ids.size()
        && ids.stream().allMatch(id -> id != null && id > 0 && player.getAvatarById(id) != null
            && player.getAvatarById(id).getAvatarId() == id
            && player.getAvatarById(id).getAvatarType() == AvatarType.AVATAR_FORMAL_TYPE);
  }
  public Retcode start(int area,int aeon,List<Integer> ids) {
    synchronized (player) {
      if (occupied(true)) return Retcode.ROGUE_STATUS_NOT_MATCH;
      var ordinary = player.getCurrentLineup();
      if (ordinary == null || ordinary.isExtraLineup() || player.getScene() == null
          || player.getScene().getPlaneType() == emu.lunarcore.game.enums.PlaneType.Rogue)
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (!rosterValid(ids) || ordinary.getLeader() < 0 || ordinary.getLeader() >= ordinary.size())
        return Retcode.REQ_PARA_INVALID;
      if (area != 101 || aeon != 6 || !MissionStateRecords.mainCompleted(player,8013104)
          || !ChessRogueRoutes.available() || ChessRogueRoutes.returnSignature(player.getScene(),player.getEntryId()).isEmpty())
        return Retcode.ROGUE_AREA_INVALID;
      var prior = player.getGameplay().getChessRogue();
      var candidate = ChessRogueState.begin(player,ChessRogueRoutes.rule(),List.copyOf(ids));
      player.getGameplay().setChessRogue(candidate);
      try {
        var status = transition(candidate, () -> {});
        if (status != Retcode.SUCC) player.getGameplay().setChessRogue(prior);
        return status;
      } catch (RuntimeException failed) { player.getGameplay().setChessRogue(prior); throw failed; }
    }
  }
  public Retcode resume(int area) {
    synchronized (player) {
      var run = player.getGameplay().getChessRogue();
      if (occupied(false) || !ChessRogueRoutes.valid(run) || run.isFailed() || area != run.getArea()
          || !rosterValid(run.getRoster()) || !ChessRogueRoutes.available()
          || (run.isInside() ? !ChessRogueRoutes.sceneSource(player.getScene(),run,true)
              : !ChessRogueRoutes.returnedSceneSource(player.getScene(),run))) return Retcode.ROGUE_STATUS_NOT_MATCH;
      return transition(run, () -> {});
    }
  }
  public Retcode queryStatus() {
    synchronized (player) {
      var run = player.getGameplay().getChessRogue();
      if (!occupied(false) && ChessRogueProfile.initialSource(player)) return Retcode.SUCC;
      return ChessRogueRoutes.valid(run) && rosterValid(run.getRoster())
          && (run.isInside() ? ChessRogueRoutes.sceneSource(player.getScene(),run,true)
              : !occupied(false) && !run.isFailed() && ChessRogueRoutes.returnedSceneSource(player.getScene(),run))
          ? Retcode.SUCC : Retcode.ROGUE_STATUS_NOT_MATCH;
    }
  }
  private boolean ready(ChessRogueState run) {
    return !occupied(false) && run.isInside() && !run.isFailed() && ChessRogueRoutes.valid(run)
        && rosterValid(run.getRoster()) && ChessRogueRoutes.sceneSource(player.getScene(),run,true);
  }
  public Retcode select(int cell) {
    synchronized (player) {
      var run = player.getGameplay().getChessRogue();
      if (!ready(run) || !run.allowedCells().contains(cell)) return Retcode.ROGUE_STATUS_NOT_MATCH;
      var before = run.copy();
      try { run.select(cell); player.save(); }
      catch (RuntimeException failed) { run.restore(before); throw failed; }
      return Retcode.SUCC;
    }
  }
  public Retcode enter(int cell,int monsterGroup) {
    synchronized (player) {
      var run = player.getGameplay().getChessRogue();
      if (!ready(run) || monsterGroup != 0 || cell != run.getCurrentCell()
          || run.status(cell) != 1 || cell == run.getEnteredCell()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      return transition(run,run::enter);
    }
  }
  public Retcode nextLayer() {
    synchronized (player) {
      var run = player.getGameplay().getChessRogue();
      var board = ChessRogueRoutes.board(run.getLayerIndex());
      if (!ready(run) || run.getLayerIndex() != 0 || board == null || run.getCurrentCell() != board.end()
          || run.getEnteredCell() != board.end()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      for (int cell : board.path()) {
        var victory = run.getVictories().get("0:"+cell);
        if (run.status(cell) != 3 || victory == null || !run.getRunId().equals(victory.getRunId())
            || victory.getCell() != cell || victory.getLayer() != 0 || victory.getSettlementId() == null
            || victory.getSettlementId().isBlank()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      }
      return transition(run, () -> run.enterLayer(1,ChessRogueRoutes.board(1)));
    }
  }

  /** Native5462 saves a healthy record; it never manufactures finished-run settlement or rewards. */
  public Retcode leaveAndSave() {
    synchronized (player) {
      var run = player.getGameplay().getChessRogue();
      var room = ChessRogueRoutes.room(run);
      var returnSignature = ChessRogueRoutes.validReturnSignature(run);
      if (!ready(run) || room == null || !player.getScene().getLoadedGroups().equals(new HashSet<>(room.groups()))
          || returnSignature.isEmpty()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      var before = run.copy(); var location = player.captureSceneLocation();
      var entityIds = new IdentityHashMap<emu.lunarcore.game.avatar.GameAvatar,Integer>();
      for (var avatar : player.getAvatars().getAvatars().values()) entityIds.put(avatar,avatar.getEntityId());
      try {
        var back = run.getReturnTo(); back.bindSignature(returnSignature);
        run.inside(false);
        if (!player.loadScene(back.getPlane(),back.getFloor(),back.getEntry(),back.getPos(),back.getRot(),false,back.getDimension())) {
          player.restoreSceneLocation(location); run.restore(before); entityIds.forEach((avatar,id) -> avatar.setEntityId(id));
          return Retcode.ROGUE_AREA_INVALID;
        }
        return Retcode.SUCC;
      } catch (RuntimeException failed) {
        player.restoreSceneLocation(location); run.restore(before); entityIds.forEach((avatar,id) -> avatar.setEntityId(id));
        throw failed;
      }
    }
  }

  private Retcode transition(ChessRogueState run,Runnable change) {
    var before = run.copy();
    var location = player.captureSceneLocation();
    var entityIds = new IdentityHashMap<emu.lunarcore.game.avatar.GameAvatar,Integer>();
    for (var avatar : player.getAvatars().getAvatars().values()) entityIds.put(avatar,avatar.getEntityId());
    try {
      run.inside(true);
      change.run();
      var room = ChessRogueRoutes.room(run);
      var floor = GameData.getFloorInfo(80201,80201001);
      var anchor = room == null || floor == null ? null : floor.getAnchorInfo(room.anchorGroup(),room.anchor());
      if (anchor == null || !ChessRogueRoutes.valid(run) || !ChessRogueRoutes.available()) {
        run.restore(before); return Retcode.ROGUE_AREA_INVALID;
      }
      boolean needsExplicitSave = player.getPlaneId() == 80201 && player.getFloorId() == 80201001
          && player.getEntryId() == room.entry();
      if (!player.loadScene(80201,80201001,room.entry(),anchor.getPos(),anchor.getRot(),false,room.dimension())) {
        player.restoreSceneLocation(location); run.restore(before);
        entityIds.forEach((avatar,id) -> avatar.setEntityId(id));
        return Retcode.ROGUE_AREA_INVALID;
      }
      // Same-floor Rogue rooms still create a fresh scene; persist its chosen cell/position once.
      if (needsExplicitSave) player.save();
      return Retcode.SUCC;
    } catch (RuntimeException failed) {
      player.restoreSceneLocation(location); run.restore(before);
      entityIds.forEach((avatar,id) -> avatar.setEntityId(id));
      throw failed;
    }
  }

  /** Called during actual Scene construction, before Player.loadScene can save a candidate. */
  public static void onSceneLoad(Scene scene) {
    var run = scene.getPlayer().getGameplay().getChessRogue();
    if (!run.isInside() || !ChessRogueRoutes.valid(run) || !ChessRogueRoutes.sceneSource(scene,run,false))
      throw new IllegalStateException("Chess scene does not match its full original source");
    var room = ChessRogueRoutes.room(run);
    room.groups().forEach(scene::loadGroup);
    // Scene.groups holds only property/state-bearing SceneGroup views, not every loaded group.
    if (!scene.getLoadedGroups().equals(new HashSet<>(room.groups())))
      throw new IllegalStateException("Chess room did not load the complete declared group set");
    long monsters = scene.getEntities().values().stream().filter(EntityMonster.class::isInstance).count();
    long expected = room.monster() > 0 && run.status(run.getEnteredCell()) != 3 ? 1 : 0;
    if (monsters != expected) throw new IllegalStateException("Chess room actors do not match the declared original encounter");
  }
  public static EntityMonster loadMonster(Scene scene,GroupInfo group,MonsterInfo info) {
    var run = scene.getPlayer().getGameplay().getChessRogue();
    var room = ChessRogueRoutes.room(run);
    if (!run.isInside() || !ChessRogueRoutes.valid(run) || room == null || room.monster() == 0
        || run.status(run.getEnteredCell()) == 3 || !room.groups().contains(group.getId())
        || info.isIsDelete() || info.isIsClientOnly() || !info.isLoadOnInitial()
        || scene.getResourceGroups().get(group.getId()) != group || group.getMonsterById(info.getID()) != info
        || !ChessRogueRoutes.sceneSource(scene,run,false)) return null;
    var pool = GameplayDefinitions.rogueMonsterGroups.get(room.monsterPool());
    var row = GameplayDefinitions.rogueMonsters.get(room.monster());
    if (pool == null || pool.RogueMonsterListAndWeight() == null
        || pool.RogueMonsterListAndWeight().getOrDefault(room.monster(),0) <= 0
        || !RogueMonsterSelection.complete(row)) return null;
    var monster = new EntityMonster(scene,GameData.getNpcMonsterExcelMap().get(row.NpcMonsterID()),group,info);
    monster.setEventId(row.EventID()); monster.setCustomStage(row.EventID()); monster.setCustomLevel(run.getMonsterLevel());
    return monster;
  }

  public void onBattleStart(Battle battle) {
    synchronized (player) {
      started = null; combat = null;
      var run = player.getGameplay().getChessRogue();
      if (!ready(run) || battle.getPlayer() != player || battle.getLineup() != lineup()
          || run.getCurrentCell() != run.getEnteredCell() || run.status(run.getEnteredCell()) != 2
          || ChessRogueRoutes.room(run).monster() == 0) return;
      started = battle;
    }
  }
  public void onBattlePrepared(Battle battle) {
    synchronized (player) {
      var run = player.getGameplay().getChessRogue();
      if (started != battle || combat != null || !ChessRogueRoutes.sceneSource(player.getScene(),run,true)
          || battle.getLineup() != lineup() || battle.getNpcMonsters().size() != 1) return;
      var monster = battle.getNpcMonsters().getFirst();
      var room = ChessRogueRoutes.room(run);
      var row = GameplayDefinitions.rogueMonsters.get(room.monster());
      var scene = player.getScene();
      var group = scene.getResourceGroups().get(monster.getGroupId());
      if (monster.getScene() != scene || scene.getEntities().get(monster.getEntityId()) != monster
          || monster.getEntityId() <= 0 || monster.isFarmElement() || !room.groups().contains(monster.getGroupId())
          || group == null || group.getMonsterById(monster.getInstId()) != monster.getMonsterInfo()
          || monster.getExcel() != GameData.getNpcMonsterExcelMap().get(row.NpcMonsterID())
          || monster.getEventId() != row.EventID() || monster.getCustomStage() != GameData.getStageExcelMap().get(row.EventID())
          || monster.getCustomLevel() != run.getMonsterLevel() || battle.getWaves().isEmpty()
          || battle.getWaves().size() != monster.getCustomStage().getMonsterWaves().size()
          || battle.getWaves().stream().anyMatch(w -> w.getCustomLevel() != run.getMonsterLevel())) return;
      combat = new Combat(battle,run,scene,lineup(),run.getLayerIndex(),run.getEnteredCell(),monster,
          monster.getEntityId(),monster.getGroupId(),monster.getInstId(),row,monster.getCustomStage(),
          List.copyOf(battle.getWaves()),run.getMonsterLevel());
    }
  }
  /** The finite guide commits its run before ordinary mission/drop callbacks or actor removal. */
  public Battle finishReport(Battle battle, BattleEndStatus result,
      emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics statistics) {
    synchronized (player) {
      var saved = combat;
      var run = player.getGameplay().getChessRogue();
      if (!bindingMatches(saved,battle,run) || battle.isSettling()
          || result != BattleEndStatus.BATTLE_END_WIN && result != BattleEndStatus.BATTLE_END_LOSE
          || !reportValid(run,statistics) || saved.monster().getEntityId() != saved.entity()
          || saved.scene().getEntities().get(saved.entity()) != saved.monster()) return null;
      var before = run.copy();
      var priorResult = battle.getResult(); var priorStatistics = battle.getStats();
      try {
        battle.setResult(result); battle.setStats(statistics);
        for (var row : statistics.getBattleAvatarList()) {
          var avatar = player.getAvatarById(row.getId()); var vital = row.getAvatarStatus();
          int hp = (int)Math.round(vital.getLeftHp() / vital.getMaxHp() * 10000);
          int sp = vital.getMaxSp() == 0 ? 0 : (int)Math.round(vital.getLeftSp() / vital.getMaxSp() * avatar.getMaxSp());
          run.hp(row.getId(),Math.max(hp,result == BattleEndStatus.BATTLE_END_LOSE ? 2000 : 0));
          run.sp(row.getId(),Math.clamp(sp,0,avatar.getMaxSp()));
        }
        if (result == BattleEndStatus.BATTLE_END_WIN)
          run.victory(battle.getId(),saved.row().EventID(),saved.group(),saved.instance());
        else run.lose();
        player.save();
      } catch (RuntimeException failed) {
        run.restore(before); battle.setResult(priorResult); battle.setStats(priorStatistics);
        throw failed;
      }
      // The player lock covers admission and the synchronous commit; begin only after that commit.
      if (!battle.beginSettlement()) throw new IllegalStateException("Chess settlement changed under its player lock");
      if (result == BattleEndStatus.BATTLE_END_WIN) saved.scene().removeEntity(saved.monster());
      started = null; combat = null; player.setBattle(null);
      player.sendPacket(new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(saved.lineup()));
      org.hkrpg.server.protocol.ChessRogue46.sync(player);
      return battle;
    }
  }

  private boolean bindingMatches(Combat saved,Battle battle,ChessRogueState run) {
    return saved != null && saved.battle() == battle && saved.run() == run && saved.scene() == player.getScene()
        && player.getBattle() == battle && battle.getPlayer() == player
        && battle.getLineup() == saved.lineup() && lineup() == saved.lineup()
        && run.getLayerIndex() == saved.layer() && run.getCurrentCell() == saved.cell()
        && run.getEnteredCell() == saved.cell() && run.status(saved.cell()) == 2 && run.isInside() && !run.isFailed()
        && ChessRogueRoutes.valid(run) && ChessRogueRoutes.sceneSource(saved.scene(),run,true)
        && new HashSet<>(saved.scene().getLoadedGroups()).equals(new HashSet<>(ChessRogueRoutes.room(run).groups()))
        && !run.getVictories().containsKey(run.receiptKey()) && battle.getNpcMonsters().size() == 1
        && battle.getNpcMonsters().getFirst() == saved.monster() && sameWaves(saved,battle)
        && saved.monster().getGroupId() == saved.group() && saved.monster().getInstId() == saved.instance()
        && saved.monster().getEventId() == saved.row().EventID() && saved.monster().getCustomStage() == saved.stage()
        && saved.monster().getCustomLevel() == saved.level()
        && saved.monster().getExcel() == GameData.getNpcMonsterExcelMap().get(saved.row().NpcMonsterID())
        && saved.scene().getResourceGroups().get(saved.group()).getMonsterById(saved.instance()) == saved.monster().getMonsterInfo();
  }

  private boolean reportValid(ChessRogueState run, emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics stats) {
    if (stats == null || stats.getRoundCnt() < 0 || !MissionBattleCustomValues.validStatistics(stats)) return false;
    var seen = new HashSet<Integer>();
    for (var row : stats.getBattleAvatarList()) {
      var avatar = player.getAvatarById(row.getId()); var vital = row.getAvatarStatus();
      if (avatar == null || !run.getRoster().contains(row.getId()) || !seen.add(row.getId())
          || !Double.isFinite(vital.getMaxHp()) || vital.getMaxHp() <= 0
          || !Double.isFinite(vital.getLeftHp()) || vital.getLeftHp() < 0 || vital.getLeftHp() > vital.getMaxHp()
          || !Double.isFinite(vital.getMaxSp()) || vital.getMaxSp() < 0
          || !Double.isFinite(vital.getLeftSp()) || vital.getLeftSp() < 0 || vital.getLeftSp() > vital.getMaxSp()) return false;
    }
    return true;
  }

  public void onBattleFinish(Battle battle) {
    synchronized (player) {
      var saved = combat;
      var run = player.getGameplay().getChessRogue();
      if (!bindingMatches(saved,battle,run) || battle.getStats() == null) return;
      if (battle.getResult() != BattleEndStatus.BATTLE_END_WIN && battle.getResult() != BattleEndStatus.BATTLE_END_LOSE) return;
      if (battle.getResult() == BattleEndStatus.BATTLE_END_WIN
          && (saved.monster().getEntityId() != 0 || saved.scene().getEntities().containsKey(saved.entity()))) return;
      var before = run.copy();
      try {
        if (battle.getResult() == BattleEndStatus.BATTLE_END_WIN)
          run.victory(battle.getId(),saved.row().EventID(),saved.group(),saved.instance());
        else run.lose();
        player.save();
      } catch (RuntimeException failed) { run.restore(before); throw failed; }
      started = null; combat = null;
      org.hkrpg.server.protocol.ChessRogue46.sync(player);
    }
  }
  private static boolean sameWaves(Combat saved,Battle battle) {
    if (saved.waves().size() != battle.getWaves().size()) return false;
    for (int i = 0; i < saved.waves().size(); i++)
      if (saved.waves().get(i) != battle.getWaves().get(i) || saved.waves().get(i).getCustomLevel() != saved.level()) return false;
    return true;
  }
}
