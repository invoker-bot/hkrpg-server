package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MonsterInfo;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify;
import emu.lunarcore.server.packet.send.PacketScenePlaneEventScNotify;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One-time exploration encounters from the 4.6 ChestMonster table. */
public final class WorldTrotters {
  private static final int TROTTER = 8002040;
  private static final int JADE_PER_TROTTER = 60;

  record Location(int id, int plane, int floor, int group, int instance, int event) {
    String key() {
      return "world-trotter:" + id + ":" + plane + ":" + floor + ":" + group + ":" + instance + ":" + event;
    }

    boolean matches(int planeId, GroupInfo source, MonsterInfo actor) {
      return plane == planeId && source != null && group == source.getId()
          && instance == actor.getID() && event == actor.getEventID()
          && actor.getNPCMonsterID() == TROTTER && actor.getFarmElementID() == 0
          && !actor.isIsDelete() && !actor.isIsClientOnly() && actor.isLoadOnInitial();
    }
  }

  // ChestMonster.json (MonsterType Chest), joined to default-dimension NPC placements.
  private static final Map<Integer, List<Location>> LOCATIONS = Stream.of(
      new Location(1, 20001, 20001001, 28, 200001, 20001111),
      new Location(2, 20002, 20002001, 46, 200001, 20002109),
      new Location(3, 20003, 20003001, 33, 200001, 20003109),
      new Location(4, 20004, 20004001, 89, 200001, 20004125),
      new Location(11, 20101, 20101001, 34, 200001, 20101111),
      new Location(12, 20111, 20111001, 24, 200001, 20111133),
      new Location(13, 20121, 20121001, 55, 200001, 20121143),
      new Location(14, 20122, 20122001, 63, 200001, 20122106),
      new Location(15, 20123, 20123001, 52, 200001, 20123108),
      new Location(16, 20131, 20131001, 31, 200001, 20131105),
      new Location(17, 20132, 20132001, 15, 200001, 20132113),
      new Location(18, 20134, 20134001, 12, 200001, 20134107),
      new Location(21, 20211, 20211001, 103, 200001, 20211110),
      new Location(22, 20212, 20212001, 76, 200001, 20212120),
      new Location(23, 20221, 20221001, 59, 200001, 20221120),
      new Location(24, 20222, 20222001, 57, 200001, 20222125),
      new Location(25, 20231, 20231001, 43, 200001, 20231130),
      new Location(26, 20232, 20232001, 49, 200001, 20232125),
      new Location(27, 20223, 20223001, 17, 200001, 20223125),
      new Location(28, 20313, 20313001, 168, 200001, 20313140),
      new Location(29, 20312, 20312001, 208, 200001, 20312130),
      new Location(30, 20311, 20311001, 348, 200001, 20311170),
      new Location(31, 20311, 20311001, 348, 200002, 20311169),
      new Location(32, 20322, 20322001, 221, 200001, 20322170),
      new Location(33, 20321, 20321001, 179, 200001, 20321170),
      new Location(34, 20331, 20331001, 213, 200001, 20331170),
      new Location(35, 20332, 20332001, 13, 200001, 20332170),
      new Location(36, 20241, 20241001, 141, 200001, 20241170)
  ).collect(Collectors.groupingBy(Location::floor));

  private WorldTrotters() {}

  private static boolean ordinary(PlaneType type) {
    return type == PlaneType.Maze || type == PlaneType.Town;
  }

  private static boolean nativeReports(Player player) {
    return player.getSession() == null || player.getSession().getProtocol().experimental();
  }

  private static Location source(Scene scene, GroupInfo group, MonsterInfo actor) {
    if (actor == null || actor.getNPCMonsterID() != TROTTER || scene.getDimensionId() != 0
        || !ordinary(scene.getPlaneType()) || scene.getFloorInfo() == null || group == null
        || scene.getResourceGroups().get(group.getId()) != group || group.getMonsterList() == null
        || !group.getMonsterList().contains(actor)) return null;
    for (var location : LOCATIONS.getOrDefault(scene.getFloorId(), List.of())) {
      if (location.matches(scene.getPlaneId(), group, actor)) return location;
    }
    return null;
  }

  private static Location source(EntityMonster actor) {
    var scene = actor.getScene();
    if (actor.getExcel() == null || actor.getExcel().getId() != TROTTER || scene == null
        || scene.getFloorInfo() == null) return null;
    var location = source(scene, scene.getResourceGroups().get(actor.getGroupId()), actor.getMonsterInfo());
    return location != null && location.event() == actor.getEventId()
        && location.instance() == actor.getInstId() && !actor.isFarmElement() ? location : null;
  }

  private static boolean finished(Player player, Location location) {
    var receipt = player.getGameplay().getRewards().get(location.key());
    return receipt != null && receipt.isGranted() && receipt.getRewardId() == 0;
  }

  public static boolean spawns(Scene scene, GroupInfo group, MonsterInfo actor) {
    var location = source(scene, group, actor);
    return location == null || !finished(scene.getPlayer(), location);
  }

  public static boolean ownsDrops(EntityMonster actor) {
    return source(actor) != null && nativeReports(actor.getScene().getPlayer());
  }

  public static List<Integer> finishedOnFloor(Player player, int plane, int floor) {
    var locations = LOCATIONS.get(floor);
    if (locations == null) return List.of();
    var planeConfig = GameData.getMazePlaneExcelMap().get(plane);
    var config = GameData.getFloorInfo(plane, floor);
    if (planeConfig == null || !ordinary(planeConfig.getPlaneType()) || config == null) return List.of();
    var groups = config.getGroups(0);
    return locations.stream().filter(location -> {
      var group = groups.get(location.group());
      return location.plane() == plane && finished(player, location) && group != null
          && group.getMonsterList() != null && group.getMonsterList().stream()
              .anyMatch(actor -> location.matches(plane, group, actor));
    }).map(Location::id).toList();
  }

  /** Called with the player lock before consuming the battle. Null leaves the result retryable. */
  public static List<GameItem> recordBattle(Battle battle, BattleEndStatus result,
      List<MissionBattleKills.Monster> report) {
    var player = battle.getPlayer();
    var scene = player.getScene();
    if (result != BattleEndStatus.BATTLE_END_WIN || !nativeReports(player) || scene == null)
      return List.of();
    var locations = battle.getNpcMonsters().stream().filter(actor -> actor.getScene() == scene
        && actor.isAlive() && scene.getEntityById(actor.getEntityId()) == actor
        && battle.hasMazeSkillTarget(actor)).map(actor -> {
          var location = source(actor);
          return location != null && !finished(player, location)
              && confirmedKill(battle, actor, location, report) ? location : null;
        }).filter(Objects::nonNull).distinct().toList();
    if (locations.isEmpty()) return List.of();
    var reward = commit(player, locations);
    return reward == null ? null : List.of(reward);
  }

  private static boolean confirmedKill(Battle battle, EntityMonster actor, Location location,
      List<MissionBattleKills.Monster> report) {
    int stage = location.event() * 10 + actor.getWorldLevel();
    long roster = battle.getWaves().stream().filter(wave -> wave.getStage().getId() == stage
        && wave.getStage().getStageType() == StageType.Mainline)
        .flatMap(wave -> wave.getMonsters().stream()).filter(id -> id == TROTTER).count();
    if (roster != 1) return false;
    var rows = report.stream().filter(row -> row.monsterId() == TROTTER && row.stageId() == stage).toList();
    if (rows.size() != 1) return false;
    var death = rows.getFirst();
    return death.entityId() > 0 && (death.deathSource() == 1 || death.deathSource() == 2)
        && report.stream().filter(row -> row.entityId() == death.entityId()).count() == 1;
  }

  /** Battle removal requires a committed kill. Verified direct scene kills use the same receipt. */
  public static boolean defeat(EntityMonster actor) {
    var player = actor.getScene().getPlayer();
    var location = source(actor);
    if (location == null || !nativeReports(player) || finished(player, location)) return true;
    if (player.isInBattle()) return false;
    var reward = commit(player, List.of(location));
    if (reward == null) return false;
    player.sendPacket(new PacketScenePlaneEventScNotify(reward));
    return true;
  }

  private static GameItem commit(Player player, List<Location> locations) {
    var receipts = player.getGameplay().getRewards();
    var jade = GameData.getItemExcelMap().get(GameConstants.MATERIAL_HCOIN_ID);
    if (jade == null || locations.stream().anyMatch(location -> receipts.containsKey(location.key())))
      return null;
    int amount = JADE_PER_TROTTER * locations.size();
    var reward = new GameItem(jade, amount);
    if (!player.tryChangeHCoin(amount)) return null;
    for (var location : locations) {
      var receipt = new GameplayState.Receipt(0);
      receipt.complete();
      receipts.put(location.key(), receipt);
    }
    try {
      player.save();
    } catch (RuntimeException failed) {
      player.tryChangeHCoin(-amount);
      for (var location : locations) receipts.remove(location.key());
      return null;
    }
    player.sendPacket(new PacketPlayerSyncScNotify(player));
    return reward;
  }
}
