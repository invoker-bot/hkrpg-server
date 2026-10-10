package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.GameplayState.RogueRun.Phase.*;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Classical universe exploration domain; the complete 4.6 client entry flow is still unverified.
 */
public final class UniverseManager {
  private final Player player;
  private GameplayState.RogueRun lineupRun;
  private RogueLineup lineup;
  private Battle boundBattle;
  private String battleRun;
  private int battleSite, battleRoom;
  private emu.lunarcore.game.scene.Scene battleScene;
  private boolean combatPrepared;
  private int battleFragments = -1;
  private String battleDebtAcquisition = "";
  private String battleLaurelAcquisition = "";
  private Map<Integer, String> battleCodeAcquisitions = Map.of();
  private List<CombatMonster> combatMonsters = List.of();
  private List<CombatWave> combatWaves = List.of();
  private RogueBattleKills.Context battleKills;

  private record CombatMonster(
      emu.lunarcore.game.scene.entity.EntityMonster monster,
      int group,
      int instance,
      int event,
      int level,
      emu.lunarcore.game.battle.BattleStage stage,
      emu.lunarcore.data.excel.NpcMonsterExcel npc,
      RogueMonsterSelection.Binding sourceBinding) {
    boolean matches(emu.lunarcore.game.scene.entity.EntityMonster current) {
      return current == monster
          && current.getGroupId() == group
          && current.getInstId() == instance
          && current.getEventId() == event
          && current.getCustomLevel() == level
          && current.getCustomStage() == stage
          && current.getExcel() == npc
          && sourceBinding.matches(current.getScene(), group);
    }
  }

  private record CombatWave(emu.lunarcore.game.battle.BattleMonsterWave wave, int level) {}

  private boolean combatLevelsMatch(Battle battle) {
    if (combatMonsters.size() != battle.getNpcMonsters().size()) return false;
    for (int i = 0; i < combatMonsters.size(); i++)
      if (!combatMonsters.get(i).matches(battle.getNpcMonsters().get(i))) return false;
    if (combatWaves.size() != battle.getWaves().size()) return false;
    for (int i = 0; i < combatWaves.size(); i++) {
      var saved = combatWaves.get(i);
      if (saved.wave() != battle.getWaves().get(i)
          || saved.level() != saved.wave().getCustomLevel()) return false;
    }
    return true;
  }

  public UniverseManager(Player player) {
    this.player = player;
  }

  public Retcode start(int areaId) {
    synchronized (player) {
      var team = player.getCurrentLineup();
      return start(areaId, team == null ? List.of() : List.copyOf(team.getAvatars()), true);
    }
  }

  public Retcode start(int areaId, List<Integer> selected, boolean sendPacket) {
    return start(areaId, selected, 0, sendPacket);
  }

  public Retcode start(int areaId, List<Integer> selected, int aeon, boolean sendPacket) {
    synchronized (player) {
      if (aeon < 0 || aeon > 9 || aeon != 0 && RogueAeonCatalog.find(aeon).isEmpty())
        return Retcode.REQ_PARA_INVALID;
      if (aeon != 0 && !RogueAeonCatalog.available(player).contains(aeon))
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      var prior = player.getGameplay().getRogue();
      if (prior.getPhase() == EXPLORING
          || prior.isInside()
          || player.isInBattle()
          || player.getInstance() != null
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getParkour().getActiveLevel() != 0
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getClockPark().getActive() != null
          || player.getGameplay().getCurrentMissionRaid() != 0)
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (GameplayDefinitions.rogueConstants.containsKey("Start_Rogue_Coin_Base")
          && RogueEconomy.initialCoins().isEmpty()) return Retcode.ROGUE_AREA_INVALID;
      settleResults();
      if (prior.getMiracleFragmentGrants() == null
          || prior.getMiracleFragmentGrants().values().stream()
              .anyMatch(grant -> grant == null || !grant.isSettled()))
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      var area = GameplayDefinitions.areas.get(areaId);
      if (area == null || !RogueUnlocks.open(player, area.UnlockID()))
        return Retcode.ROGUE_AREA_INVALID;
      var team = player.getCurrentLineup();
      if (team == null
          || team.isExtraLineup()
          || player.getScene() == null
          || player.getScene().getPlaneType() == emu.lunarcore.game.enums.PlaneType.Rogue)
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      var ids = selected == null ? List.<Integer>of() : List.copyOf(selected);
      if (ids.isEmpty()
          || ids.size() > 4
          || new HashSet<>(ids).size() != ids.size()
          || team.getLeader() < 0
          || team.getLeader() >= team.size()
          || ids.stream()
              .anyMatch(
                  id ->
                      player.getAvatarById(id) == null
                          || player.getAvatarById(id).getAvatarId() != id
                          || player.getAvatarById(id).getAvatarType()
                              != emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType
                                  .AVATAR_FORMAL_TYPE)) return Retcode.REQ_PARA_INVALID;
      var planned = RogueMapPools.assign(area.mapId(), player.getUid(), areaId);
      if (planned.isEmpty()) return Retcode.ROGUE_AREA_INVALID;
      var assignments = planned.get().rooms();
      var boss = RogueBossEncounters.prepare(area, assignments);
      if (!boss.valid()) return Retcode.ROGUE_AREA_INVALID;
      var run = new GameplayState.RogueRun(areaId, area.mapId(), assignments, boss.plan(), aeon);
      run.setupTeam(player, ids);
      int first = planned.get().start();
      var room = GameplayDefinitions.rooms.get(assignments.get(first));
      run.enter(first, room.RogueRoomID());
      player.getGameplay().setRogue(run);
      if (run.getTalents().contains(21)) {
        var pool = RogueTalentEffects.group(100005);
        if (pool.isPresent()) createBlessingOffer(run, pool.get(), false);
      }
      if (!player.enterScene(room.MapEntrance(), 0, sendPacket)) {
        player.getGameplay().setRogue(prior);
        return Retcode.ROGUE_AREA_INVALID;
      }
      readyRestRoom(run, room);
      player.save();
      if (sendPacket) {
        org.hkrpg.server.protocol.Universe46.syncMap(player);
        org.hkrpg.server.protocol.Universe46.syncPending(player);
        org.hkrpg.server.protocol.RogueRevival46.sync(player);
      }
      return Retcode.SUCC;
    }
  }

  public Retcode enter(int siteId) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (run.getPhase() != EXPLORING
          || (!run.getRoster().isEmpty() && !run.isInside())
          || player.isInBattle()
          || run.pending()
          || !run.getCleared().contains(run.getSiteId())) return Retcode.ROGUE_STATUS_NOT_MATCH;
      var current = site(run.getMapId(), run.getSiteId());
      if (current == null
          || current.NextSiteIDList() == null
          || !current.NextSiteIDList().contains(siteId)
          || run.getCleared().contains(siteId)) return Retcode.ROGUE_AREA_INVALID;
      var room = GameplayDefinitions.rooms.get(run.getRooms().get(siteId));
      if (room == null) return Retcode.ROGUE_AREA_INVALID;
      int priorSite = run.getSiteId(), priorRoom = run.getRoomId();
      run.enter(siteId, room.RogueRoomID());
      if (!player.enterScene(room.MapEntrance(), 0, true)) {
        run.enter(priorSite, priorRoom);
        return Retcode.ROGUE_AREA_INVALID;
      }
      readyRestRoom(run, room);
      int beforeRoomEffects = run.getCoins();
      RogueMiracles.onRoomEntered(player);
      player.save();
      if (run.getCoins() != beforeRoomEffects)
        org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      org.hkrpg.server.protocol.Universe46.syncMap(player);
      return Retcode.SUCC;
    }
  }

  public boolean resume() {
    return resume(player.getGameplay().getRogue().getAreaId(), true) == Retcode.SUCC;
  }

  public Retcode resume(int area, boolean sendPacket) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (run.getPhase() != EXPLORING
          || player.isInBattle()
          || player.getInstance() != null
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getParkour().getActiveLevel() != 0
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getClockPark().getActive() != null
          || player.getGameplay().getCurrentMissionRaid() != 0)
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (area != run.getAreaId()) return Retcode.ROGUE_AREA_INVALID;
      if (run.getRoster().isEmpty()
          || run.getReturnTo() == null
          || run.getRoster().stream().anyMatch(id -> player.getAvatarById(id) == null))
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      boolean wasInside = run.isInside();
      run.setInside(true);
      boolean entered = room != null && player.enterScene(room.MapEntrance(), 0, sendPacket);
      if (entered) {
        readyRestRoom(run, room);
        // Recover earned choices saved before their deferred notification/queue completed.
        RogueResonance.queue(run);
        player.save();
        if (sendPacket) {
          org.hkrpg.server.protocol.Universe46.syncMap(player);
          org.hkrpg.server.protocol.Universe46.syncPending(player);
          org.hkrpg.server.protocol.RogueRevival46.sync(player);
        }
      } else run.setInside(wasInside);
      return entered ? Retcode.SUCC : Retcode.ROGUE_AREA_INVALID;
    }
  }

  public RogueLineup lineup() {
    var run = player.getGameplay().getRogue();
    if (lineupRun != run) {
      lineupRun = run;
      lineup = new RogueLineup(player, run);
    }
    return lineup;
  }

  /** Pause exploration; terminal runs also leave through the same saved location. */
  public Retcode leave(boolean sendPacket) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      var destination = run.getReturnTo();
      if (!run.isInside() || player.isInBattle() || destination == null)
        return Retcode.ROGUE_STATUS_NOT_MATCH;
      settleResults();
      // Cache the terminal record while the client is still in rogue mode, before scene
      // transition.
      org.hkrpg.server.protocol.Universe46.syncFinish(player);
      run.setInside(false);
      if (!player.loadScene(
          destination.getPlane(),
          destination.getFloor(),
          destination.getEntry(),
          destination.getPos(),
          destination.getRot(),
          sendPacket)) {
        run.setInside(true);
        return Retcode.ROGUE_AREA_INVALID;
      }
      player.save();
      return Retcode.SUCC;
    }
  }

  /** Native QuitRogue has no scene payload: close a run only after returning outside. */
  public Retcode quit(int area) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (area != run.getAreaId()) return Retcode.ROGUE_AREA_INVALID;
      if (run.isInside() || run.isDismissed()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (run.getPhase() == FINISHED || run.getPhase() == FAILED || run.getPhase() == ABANDONED) {
        settleResults();
        run.dismiss();
        player.save();
        return Retcode.SUCC;
      }
      var result = abandon();
      if (result == Retcode.SUCC) {
        run.dismiss();
        player.save();
      }
      return result;
    }
  }

  public void onBattleFinish(Battle battle) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      if (run.getPhase() != EXPLORING
          || battle.getPlayer() != player
          || boundBattle != battle
          || !run.getRunId().equals(battleRun)
          || run.getSiteId() != battleSite
          || run.getRoomId() != battleRoom
          || battleScene != player.getScene()
          || (!run.getRoster().isEmpty() && !run.isInside())
          || room == null
          || player.getEntryId() != room.MapEntrance()
          || battle.getNpcMonsters().isEmpty()
          || battle.getNpcMonsters().stream().anyMatch(m -> m.getScene() != player.getScene())
          || battle.getNpcMonsters().stream()
              .anyMatch(m -> !UniverseEntityLoader.roomGroups(player).contains(m.getGroupId())))
        return;
      if (combatPrepared
          && (battleFragments < 0
              || !combatLevelsMatch(battle)
              || battle.getNpcMonsters().size() != combatMonsters.size()
              || java.util.stream.IntStream.range(0, combatMonsters.size())
                  .anyMatch(i -> !combatMonsters.get(i).matches(battle.getNpcMonsters().get(i)))))
        return;
      if (battle.getResult()
          == emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_LOSE) {
        finishRun(FAILED);
        return;
      }
      if (battle.getResult()
          != emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN) return;
      String key = battle.getId() + ":" + battle.getTimestamp();
      if (run.getProcessedBattles().add(key)) {
        boolean credited = false;
        if (battleFragments > 0) {
          var grant =
              run.getCombatFragmentGrants()
                  .computeIfAbsent(
                      key, ignored -> new GameplayState.RogueCombatGrant(battleFragments));
          credited = grant.credit(run);
        }
        var debt = RogueMiracles.debtVictory(run, battleDebtAcquisition, key);
        var codes = RogueCodeMiracles.victory(run, battleCodeAcquisitions);
        credited |= debt.credited();
        // The grant, balance and once-only marker are saved together by the miracle
        // callback.
        RogueMiracles.onBattleWon(player);
        codes.sync(player);
        if (debt.changed()) org.hkrpg.server.protocol.Universe46.syncMiracle(player, 60, false, 0);
        if (credited) org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      }
      // GroupID can be a portal. Every configured content group participates in clear checks.
      if (player.getScene() == null
          || player.getScene().getEntities().values().stream()
              .anyMatch(
                  e ->
                      e instanceof emu.lunarcore.game.scene.entity.EntityMonster m
                          && UniverseEntityLoader.roomGroups(player).contains(m.getGroupId())
                          && m.isAlive())) return;
      if (!run.clear()) return;
      player.save();
      player.getDailyManager().onRogueRoom();
      var site = site(run.getMapId(), run.getSiteId());
      if (site != null && site.NextSiteIDList() != null && site.NextSiteIDList().isEmpty()) {
        finishRun(FINISHED);
      } else offerBlessings();
      org.hkrpg.server.protocol.Universe46.syncMap(player);
    }
  }

  public Retcode abandon() {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (run.getPhase() != EXPLORING || player.isInBattle()) return Retcode.ROGUE_STATUS_NOT_MATCH;
      if (run.isInside()) {
        var result = leave(true);
        if (result != Retcode.SUCC) return result;
      }
      finishRun(ABANDONED);
      org.hkrpg.server.protocol.Universe46.syncMap(player);
      return Retcode.SUCC;
    }
  }

  private void finishRun(GameplayState.RogueRun.Phase outcome) {
    var state = player.getGameplay();
    var run = state.getRogue();
    if (run.getPhase() != EXPLORING) return;
    var area = GameplayDefinitions.areas.get(run.getAreaId());
    boolean first =
        outcome == FINISHED
            && !state.getRewards().containsKey("rogue-first:" + run.getAreaId())
            && state.getRogueResults().values().stream()
                .noneMatch(r -> r.getAreaId() == run.getAreaId() && r.getOutcome() == FINISHED);
    var result =
        new GameplayState.RogueResult(
            player, run, outcome, first ? area == null ? -1 : area.FirstReward() : 0);
    state.getRogueResults().put(run.getRunId(), result);
    run.finish(outcome);
    if (outcome == FINISHED && state.recordRogueWin(run)) player.getDailyManager().onRogueFinish();
    if (first && area != null && area.FirstReward() == 0) {
      // Tutorial/zero-reward areas still complete the first-clear marker, without an
      // inventory
      // grant.
      var empty = new GameplayState.Receipt(0);
      empty.complete();
      state.getRewards().put("rogue-first:" + run.getAreaId(), empty);
    }
    player.save();
    settleResults();
    if (outcome == FINISHED) player.getMissionManager().refresh();
    org.hkrpg.server.protocol.Universe46.syncFinish(player);
  }

  /** Retry only unreserved frozen grants; a partially granted reservation needs reconciliation. */
  public void settleResults() {
    synchronized (player) {
      settleCombatFragments();
      for (var result : List.copyOf(player.getGameplay().getRogueResults().values())) {
        player.getCycleScoreManager().settle(result);
        if (result.isEarningsReady()
            && !result.isEarningsSettled()
            && player.tryChangeTalentPoints(result.getTalentCoin())) {
          // Both the balance and once-only marker live in this single player document.
          result.settleEarnings();
          player.save();
          org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
        }
        if (result.getOutcome() != FINISHED
            || result.isRewardSettled()
            || !result.isFirstRewardReady()) continue;
        var items = new emu.lunarcore.game.inventory.ItemParamMap();
        result.getFirstRewardPlan().forEach(items::add);
        var claim = player.getRewardLedger().claimItems("rogue-first:" + result.getAreaId(), items);
        if (claim.retcode() == Retcode.SUCC
            || claim.retcode() == Retcode.QUEST_REWARD_ALREADY_TAKEN) {
          result.settle(claim.items());
          player.save();
        }
      }
    }
  }

  private static GameplayDefinitions.Site site(int map, int site) {
    return GameplayDefinitions.maps.getOrDefault(map, List.of()).stream()
        .filter(s -> s.SiteID() == site)
        .findFirst()
        .orElse(null);
  }

  /** A confirmed noncombat rest arrival makes adjacent exits ready, without a victory event. */
  private void readyRestRoom(GameplayState.RogueRun run, GameplayDefinitions.Room room) {
    if (room == null
        || room.RogueRoomType() != 5
        || !run.isInside()
        || run.getPhase() != EXPLORING
        || player.isInBattle()
        || run.getCleared().contains(run.getSiteId())
        || run.getRoomId() != room.RogueRoomID()
        || !Objects.equals(run.getRooms().get(run.getSiteId()), room.RogueRoomID())
        || player.getEntryId() != room.MapEntrance()
        || room.GroupWithContent() == null
        || room.GroupWithContent().isEmpty()
        || !room.GroupWithContent().containsKey(room.GroupID())) return;
    var current = site(run.getMapId(), run.getSiteId());
    if (current == null || current.NextSiteIDList() == null || current.NextSiteIDList().isEmpty())
      return;
    var scene = player.getScene();
    var entry = emu.lunarcore.data.GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    if (scene == null
        || scene.getPlayer() != player
        || scene.getPlaneType() != emu.lunarcore.game.enums.PlaneType.Rogue
        || entry == null
        || scene.getPlaneId() != entry.getPlaneID()
        || scene.getFloorId() != entry.getFloorID()
        || scene.getEntryId() != room.MapEntrance()
        || scene.getFloorInfo() == null) return;
    for (var assigned : room.GroupWithContent().entrySet()) {
      if (assigned.getKey() == null
          || assigned.getKey() <= 0
          || assigned.getValue() == null
          || assigned.getValue() < 0
          || !scene.getLoadedGroups().contains(assigned.getKey())) return;
      var group = scene.getResourceGroups().get(assigned.getKey());
      if (group == null
          || group.getId() != assigned.getKey()
          || (group.getMonsterList() != null
              && group.getMonsterList().stream()
                  .anyMatch(
                      m ->
                          m == null
                              || (!m.isIsDelete() && !m.isIsClientOnly() && m.isLoadOnInitial()))))
        return;
    }
    if (scene.getEntities().values().stream()
        .anyMatch(
            e ->
                e instanceof emu.lunarcore.game.scene.entity.EntityMonster m
                    && room.GroupWithContent().containsKey(m.getGroupId())
                    && m.isAlive())) return;
    run.clear();
  }

  public List<Integer> offerBlessings() {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (run.getPhase() != EXPLORING || !run.getCleared().contains(run.getSiteId()))
        return List.of();
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      if (room != null && room.RogueRoomType() == 5) return List.of();
      if (run.pending()) {
        org.hkrpg.server.protocol.Universe46.syncPending(player);
        return List.copyOf(run.getOffers());
      }
      if (!run.getSelectionRooms().add(run.getSiteId())) return List.of();
      var consumed = createBlessingOffer(run, null, true);
      player.save();
      consumed.forEach(
          id -> org.hkrpg.server.protocol.Universe46.syncMiracle(player, id, false, 0));
      org.hkrpg.server.protocol.Universe46.syncPending(player);
      return List.copyOf(run.getOffers());
    }
  }

  private Set<Integer> createBlessingOffer(
      GameplayState.RogueRun run, Set<Integer> pool, boolean victory) {
    boolean legendary = RogueMiracles.legendaryVictory(run, victory);
    var enhancedCommon =
        GameplayDefinitions.blessings.stream()
            .filter(
                b ->
                    b.MazeBuffLevel() == 2
                        && b.RogueVersion() == 1
                        && "Common".equals(b.RogueBuffCategory()))
            .map(GameplayDefinitions.Blessing::MazeBuffID)
            .collect(java.util.stream.Collectors.toSet());
    var levels = new TreeMap<Integer, Integer>();
    var weights = new TreeMap<Integer, Integer>();
    GameplayDefinitions.blessings.stream()
        .filter(b -> b.MazeBuffLevel() == 1 && b.RogueVersion() == 1)
        .forEach(
            b -> {
              int level = RogueMiracles.blessingOfferLevel(run, b, victory);
              if (level == 1 || enhancedCommon.contains(b.MazeBuffID())) {
                levels.put(b.MazeBuffID(), level);
                weights.put(b.MazeBuffID(), RogueMiracles.blessingWeight(run, b, victory));
              }
            });
    var candidates =
        GameplayDefinitions.blessings.stream()
            .filter(
                b ->
                    b.MazeBuffLevel() == 1
                        && b.RogueVersion() == 1
                        && b.AeonID() == 0
                        && b.BattleEventBuffType() == null
                        && Set.of("Common", "Rare", "Legendary")
                            .contains(Objects.toString(b.RogueBuffCategory(), ""))
                        && (!legendary || "Legendary".equals(b.RogueBuffCategory()))
                        && (pool == null || pool.contains(b.MazeBuffID()))
                        && (b.UnlockIDList() == null
                            || b.UnlockIDList().stream()
                                .allMatch(id -> RogueUnlocks.open(player, id)))
                        && !run.getBlessings().containsKey(b.MazeBuffID())
                        && levels.containsKey(b.MazeBuffID())
                        && emu.lunarcore.data.GameData.getMazeBuffExcelMap()
                                .get(b.MazeBuffID(), levels.get(b.MazeBuffID()))
                            != null)
            .map(GameplayDefinitions.Blessing::MazeBuffID)
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    candidates =
        drawBlessings(
            candidates, weights, new Random(Objects.hash(run.getRunId(), run.getSiteId())));
    levels.keySet().retainAll(candidates);
    weights.keySet().retainAll(candidates);
    if (candidates.isEmpty()) return Set.of();
    int optionLimit = RogueMiracles.blessingOptionLimit(run, victory);
    boolean doubleChoice =
        RogueMiracles.doubleVictoryChoice(run, victory, Math.min(optionLimit, candidates.size()));
    var costs = RogueMiracles.blessingRerollPlans(run, victory);
    var selected =
        candidates.subList(0, Math.min(doubleChoice ? 2 : optionLimit, candidates.size()));
    int enhancements = RogueMiracles.beaconEnhancements(run, victory);
    var eligible = enhancements == 1 ? RogueMiracles.beaconCandidates(levels) : Set.<Integer>of();
    var displayedLevels =
        enhancements == 1
            ? RogueMiracles.beaconLevels(
                levels,
                eligible,
                selected,
                new Random(
                    Objects.hash(run.getRunId(), Math.incrementExact(run.getSelectionId()), 0)))
            : levels;
    run.offer(
        selected,
        candidates,
        costs.stream().map(GameplayState.RogueFragmentCost::getCost).toList(),
        displayedLevels,
        doubleChoice ? 2 : 1,
        weights);
    run.freezeRerollPlans(costs);
    run.freezeBeaconPlan(enhancements, levels, eligible);
    return RogueMiracles.consumeVictoryMenu(run, victory, doubleChoice);
  }

  private static ArrayList<Integer> drawBlessings(
      List<Integer> candidates, Map<Integer, Integer> weights, Random random) {
    var remaining = new ArrayList<>(candidates);
    if (remaining.stream().allMatch(id -> weights.getOrDefault(id, 1) == 1)) {
      Collections.shuffle(remaining, random);
      return remaining;
    }
    var selected = new ArrayList<Integer>();
    while (!remaining.isEmpty()) {
      long ticket = random.nextLong(remaining.stream().mapToLong(weights::get).sum());
      for (int id : remaining) {
        ticket -= weights.get(id);
        if (ticket < 0) {
          selected.add(id);
          break;
        }
      }
      remaining.remove(selected.getLast());
    }
    return selected;
  }

  public OptionalInt blessingRerollCost() {
    var run = player.getGameplay().getRogue();
    int count = run.getOfferRerolls();
    if (run.getOffers().isEmpty()
        || !run.validOfferLevels()
        || count < 0
        || count >= run.getOfferRerollCosts().size()
        || rerollCandidates(run).size() < run.offerChoiceRemaining()) return OptionalInt.empty();
    int cost = run.getOfferRerollCosts().get(count);
    return cost >= 0 && run.rerollPlan(count) != null ? OptionalInt.of(cost) : OptionalInt.empty();
  }

  private List<Integer> rerollCandidates(GameplayState.RogueRun run) {
    return run.getOfferPool().stream()
        .filter(
            id ->
                !run.getOfferExcluded().contains(id)
                    && !run.getBlessings().containsKey(id)
                    && run.offerBaseLevel(id) > 0
                    && emu.lunarcore.data.GameData.getMazeBuffExcelMap()
                            .get(id, run.offerBaseLevel(id))
                        != null
                    && (run.getOfferEnhancementCount() != 1
                        || !run.getOfferEnhanceable().contains(id)
                        || emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(id, 2) != null))
        .distinct()
        .sorted()
        .toList();
  }

  /** The native empty roll request means the only current menu; an explicit batch must match. */
  public Retcode rerollBlessings(int batch) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (run.getPhase() != EXPLORING
          || (!run.getRoster().isEmpty() && !run.isInside())
          || player.isInBattle()
          || (batch != 0 && batch != run.getSelectionId())) return Retcode.REQ_PARA_INVALID;
      var cost = blessingRerollCost();
      if (cost.isEmpty()) return Retcode.REQ_PARA_INVALID;
      var candidates =
          drawBlessings(
              rerollCandidates(run),
              run.getOfferWeights() == null ? Map.of() : run.getOfferWeights(),
              new Random(
                  Objects.hash(run.getRunId(), run.getSelectionId(), run.getOfferRerolls() + 1)));
      var selected =
          List.copyOf(candidates.subList(0, Math.min(run.offerOptionLimit(), candidates.size())));
      var debit = RogueMiracles.pay(run, run.rerollPlan(run.getOfferRerolls()));
      if (debit.retcode() != Retcode.SUCC) return debit.retcode();
      run.reroll(selected);
      if (run.getOfferEnhancementCount() == 1)
        run.replaceBeaconLevels(
            RogueMiracles.beaconLevels(
                run.getOfferBaseLevels(),
                run.getOfferEnhanceable(),
                selected,
                new Random(
                    Objects.hash(run.getRunId(), run.getSelectionId(), run.getOfferRerolls()))));
      // Fragment debit, menu and count share this single Player document.
      player.save();
      debit.syncEffects(player);
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      return Retcode.SUCC;
    }
  }

  public Retcode selectBlessing(int selectionId, int buff) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (run.getPhase() != EXPLORING
          || (!run.getRoster().isEmpty() && !run.isInside())
          || player.isInBattle()
          || selectionId != run.getSelectionId()
          || !run.getOffers().contains(buff)
          || !run.validOfferLevels()
          || run.offerLevel(buff) <= 0
          || run.getBlessings().containsKey(buff)) return Retcode.REQ_PARA_INVALID;
      var binding =
          emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(buff, run.offerLevel(buff));
      if (binding == null || binding.getID() != buff || binding.getLv() != run.offerLevel(buff))
        return Retcode.REQ_PARA_INVALID;
      run.select(buff);
      run.activateResonance(buff);
      player.getGameplay().getRogueCollectedBuffs().add(buff);
      RogueResonance.queue(run);
      player.save();
      player.getQuestManager().onRogueHandbookProgress();
      org.hkrpg.server.protocol.Universe46.syncBlessing(player, buff, selectionId);
      org.hkrpg.server.protocol.Universe46.syncMap(player);
      if (run.pending()) org.hkrpg.server.protocol.Universe46.syncPending(player);
      return Retcode.SUCC;
    }
  }

  /** Candidates originate in a configured event reward group, never in a client-provided list. */
  public List<Integer> offerMiracles(int group) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      var pool = GameplayDefinitions.miracleGroups.get(group);
      if (run.getPhase() != EXPLORING
          || run.pending()
          || pool == null
          || pool.MiracleWeight() == null
          || player.isInBattle()) return List.of();
      var remaining = new TreeMap<Integer, Integer>();
      pool.MiracleWeight()
          .forEach(
              (id, weight) -> {
                if (weight != null
                    && weight > 0
                    && RogueMiracles.canAcquire(player, id)
                    && !run.getMiracles().containsKey(id)) remaining.put(id, weight);
              });
      var random = new Random(Objects.hash(run.getRunId(), run.getSelectionId(), group));
      var selected = new ArrayList<Integer>();
      while (!remaining.isEmpty() && selected.size() < 3) {
        long total = remaining.values().stream().mapToLong(Integer::longValue).sum();
        long ticket = random.nextLong(total);
        for (var entry : remaining.entrySet()) {
          ticket -= entry.getValue();
          if (ticket < 0) {
            selected.add(entry.getKey());
            break;
          }
        }
        remaining.remove(selected.getLast());
      }
      if (selected.isEmpty()) return List.of();
      run.offerMiracles(selected);
      player.save();
      org.hkrpg.server.protocol.Universe46.syncPending(player);
      return List.copyOf(selected);
    }
  }

  public Retcode selectMiracle(int batch, int id) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (run.getPhase() != EXPLORING
          || (!run.getRoster().isEmpty() && !run.isInside())
          || player.isInBattle()
          || batch != run.getSelectionId()
          || !run.getMiracleOffers().contains(id)) return Retcode.REQ_PARA_INVALID;
      var acquired = RogueMiracles.acquireDeferred(player, id);
      if (acquired == null) return Retcode.REQ_PARA_INVALID;
      run.getMiracleOffers().clear();
      boolean resonancePending = RogueResonance.queue(run);
      player.save();
      acquired.syncEffects(player);
      if (resonancePending) org.hkrpg.server.protocol.Universe46.syncPending(player);
      org.hkrpg.server.protocol.Universe46.syncMiracle(player, id, true, batch);
      org.hkrpg.server.protocol.Universe46.syncMap(player);
      return Retcode.SUCC;
    }
  }

  public Retcode enhanceBlessing(int buff) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (!run.getRoster().isEmpty()) return RogueEnhancement.enhance(player, buff);
      var level = run.getBlessings().get(buff);
      if (run.getPhase() != EXPLORING
          || (!run.getRoster().isEmpty() && !run.isInside())
          || player.isInBattle()
          || run.pending()
          || level == null) return Retcode.REQ_PARA_INVALID;
      var row =
          GameplayDefinitions.blessings.stream()
              .filter(b -> b.MazeBuffID() == buff && b.MazeBuffLevel() == level)
              .findFirst();
      if (row.isEmpty()
          || GameplayDefinitions.blessings.stream()
              .noneMatch(b -> b.MazeBuffID() == buff && b.MazeBuffLevel() == level + 1)
          || emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(buff, level + 1) == null)
        return Retcode.REQ_PARA_INVALID;
      int cost =
          switch (row.get().RogueBuffCategory()) {
            case "Common" -> 100;
            case "Rare" -> 130;
            case "Legendary" -> 160;
            default -> -1;
          };
      if (cost < 0) return Retcode.REQ_PARA_INVALID;
      var debit = RogueMiracles.pay(run, RogueMiracles.enhancementPlan(run, cost));
      if (debit.retcode() != Retcode.SUCC) return debit.retcode();
      run.enhance(buff);
      player.save();
      debit.syncEffects(player);
      org.hkrpg.server.protocol.Universe46.syncBlessing(player, buff, 0);
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      return Retcode.SUCC;
    }
  }

  public void onBattleStart(Battle battle) {
    battleKills = null;
    combatPrepared = false;
    battleFragments = -1;
    battleDebtAcquisition = "";
    battleLaurelAcquisition = "";
    battleCodeAcquisitions = Map.of();
    combatMonsters = List.of();
    combatWaves = List.of();
    var run = player.getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    if (run.getPhase() != EXPLORING || room == null || player.getEntryId() != room.MapEntrance())
      return;
    boundBattle = battle;
    battleRun = run.getRunId();
    battleSite = run.getSiteId();
    battleRoom = run.getRoomId();
    battleScene = player.getScene();
    RogueResonance.attach(battle, run);
    run.getBlessings()
        .forEach(
            (id, level) -> {
              if (emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(id, level) != null)
                battle.addBuff(new emu.lunarcore.game.battle.MazeBuff(id, level, -1, -1));
            });
  }

  /** Project rule follows LunarCore development ec315bb1 (AGPL): 20..40 per linked NPC. */
  public void onBattlePrepared(Battle battle) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (boundBattle != battle || combatPrepared || run.getPhase() != EXPLORING || !run.isInside())
        return;
      combatPrepared = true;
      var room = GameplayDefinitions.rooms.get(run.getRoomId());
      if (room == null
          || !Set.of(1, 2, 4, 6, 7).contains(room.RogueRoomType())
          || room.GroupWithContent() == null
          || run.pending()
          || run.getCleared().contains(run.getSiteId())
          || battle.getLineup() != lineup()
          || battle.getNpcMonsters().isEmpty()) return;
      var identities = new HashSet<String>();
      var snapshots = new ArrayList<CombatMonster>();
      int waveIndex = 0;
      for (var monster : battle.getNpcMonsters()) {
        int content = room.GroupWithContent().getOrDefault(monster.getGroupId(), 0);
        var binding = RogueMonsterSelection.current(battleScene, monster.getGroupId());
        var row = binding == null ? null : binding.mapping();
        if (content <= 0
            || row == null
            || monster.getExcel() == null
            || monster.getScene() != battleScene
            || monster.getEntityId() <= 0
            || battleScene.getEntities().get(monster.getEntityId()) != monster
            || monster.getInstId() <= 0
            || monster.isFarmElement()
            || !identities.add(monster.getGroupId() + ":" + monster.getInstId())
            || monster.getExcel()
                != emu.lunarcore.data.GameData.getNpcMonsterExcelMap().get(row.NpcMonsterID())
            || monster.getEventId() != row.EventID()
            || monster.getCustomLevel()
                != RogueBossEncounters.level(run, room, monster.getGroupId())
            || monster.getCustomStage() == null
            || monster.getCustomStage()
                != emu.lunarcore.data.GameData.getStageExcelMap().get(row.EventID())) return;
        snapshots.add(
            new CombatMonster(
                monster,
                monster.getGroupId(),
                monster.getInstId(),
                monster.getEventId(),
                monster.getCustomLevel(),
                monster.getCustomStage(),
                monster.getExcel(),
                binding));
        int waveCount = monster.getCustomStage().getMonsterWaves().size();
        for (int i = 0; i < waveCount; i++) {
          if (waveIndex >= battle.getWaves().size()
              || battle.getWaves().get(waveIndex++).getCustomLevel() != monster.getCustomLevel())
            return;
        }
      }
      if (waveIndex != battle.getWaves().size()) return;
      combatWaves =
          battle.getWaves().stream().map(w -> new CombatWave(w, w.getCustomLevel())).toList();
      try {
        int unit =
            20
                + new Random(Objects.hash(run.getRunId(), battle.getId(), battle.getTimestamp()))
                    .nextInt(21);
        battleDebtAcquisition = RogueMiracles.debtAcquisition(run);
        battleFragments =
            !battleDebtAcquisition.isBlank()
                ? 0
                : RogueMiracles.combatFragments(
                    run,
                    room.RogueRoomType() == 7 ? 0 : Math.multiplyExact(unit, snapshots.size()));
        combatMonsters = List.copyOf(snapshots);
        battleKills = RogueBattleKills.capture(player, battle);
        RogueBattleMiracles.bind(player, battle);
        battleCodeAcquisitions = RogueCodeMiracles.prepare(run, battle);
        var current = site(run.getMapId(), run.getSiteId());
        var map = GameplayDefinitions.maps.getOrDefault(run.getMapId(), List.of());
        if (current != null
            && current.RogueMapID() == run.getMapId()
            && current.NextSiteIDList() != null
            && !current.NextSiteIDList().isEmpty()
            && map.stream().filter(s -> s.SiteID() == run.getSiteId()).count() == 1
            && new HashSet<>(current.NextSiteIDList()).size() == current.NextSiteIDList().size()
            && current.NextSiteIDList().stream()
                .allMatch(
                    next ->
                        next != null
                            && next > 0
                            && next != run.getSiteId()
                            && map.stream()
                                    .filter(
                                        s -> s.SiteID() == next && s.RogueMapID() == run.getMapId())
                                    .count()
                                == 1))
          battleLaurelAcquisition = RogueMiracles.laurelAcquisition(run);
      } catch (ArithmeticException e) {
        battleFragments = -1;
      }
    }
  }

  /** Resolve a resource-bound loss before the battle service performs defeat side effects. */
  public void onBattleKillOutcome(Battle battle) {
    synchronized (player) {
      if (boundBattle == battle && battleKills != null && combatLevelsMatch(battle))
        battleKills.commit(battle);
    }
  }

  public emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus resolveBattleOutcome(
      Battle battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus reported) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      if (reported != emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_LOSE
          || player.getBattle() != battle
          || boundBattle != battle
          || !combatPrepared
          || battleFragments < 0
          || !combatLevelsMatch(battle)
          || battleLaurelAcquisition.isBlank()
          || battle.getPlayer() != player
          || run.getPhase() != EXPLORING
          || !run.isInside()
          || run.pending()
          || !run.getRunId().equals(battleRun)
          || run.getSiteId() != battleSite
          || run.getRoomId() != battleRoom
          || player.getScene() != battleScene
          || battle.getLineup() != lineup()
          || combatMonsters.isEmpty()
          || combatMonsters.size() != battle.getNpcMonsters().size()
          || battle.getStage() != combatMonsters.getFirst().stage()
          || battle.getStaminaCost() != 0
          || java.util.stream.IntStream.range(0, combatMonsters.size())
              .anyMatch(
                  i -> {
                    var monster = battle.getNpcMonsters().get(i);
                    return !combatMonsters.get(i).matches(monster)
                        || monster.getScene() != battleScene
                        || battleScene.getEntities().get(monster.getEntityId()) != monster;
                  })
          || !RogueMiracles.useLaurel(run, battleLaurelAcquisition)) return reported;
      player.save();
      org.hkrpg.server.protocol.Universe46.syncMiracle(player, 61, false, 0);
      return emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN;
    }
  }

  /** Overflow leaves a confirmed, frozen grant pending rather than wrapping the wallet. */
  private void settleCombatFragments() {
    var run = player.getGameplay().getRogue();
    if (run.getPhase() != EXPLORING) return;
    boolean changed = false;
    for (var grant : run.getCombatFragmentGrants().values()) changed |= grant.credit(run);
    for (var grant : run.getMiracleFragmentGrants().values()) changed |= grant.credit(run);
    if (changed) {
      player.save();
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
    }
  }
}
