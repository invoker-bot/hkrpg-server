package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.send.PacketSyncLineupNotify;
import emu.lunarcore.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Original 99% loss; five-percent gain/break odds and pool 10004 are explicit project policy. */
public final class RogueGalacticLottery {
  private static final int ITEM = 63, POOL = 10004;

  static RogueLotteryState.Outcome outcome(String acquisition, String key) {
    try {
      var hash =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (RogueLotteryState.RULE + ":" + acquisition + ":" + key)
                      .getBytes(StandardCharsets.UTF_8));
      int ticket = java.nio.ByteBuffer.wrap(hash).getInt() & Integer.MAX_VALUE;
      ticket %= 100;
      return ticket < 5
          ? RogueLotteryState.Outcome.GAIN
          : ticket < 10 ? RogueLotteryState.Outcome.BREAK : RogueLotteryState.Outcome.NONE;
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String key(GameplayState.RogueRun run, Scene scene, EntityProp prop) {
    return run.getSiteId()
        + ":"
        + run.getRoomId()
        + ":"
        + scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + prop.getGroupId()
        + ":"
        + prop.getInstId();
  }

  private static boolean context(Player player, GameplayState.RogueRun run, Scene scene) {
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    var item = run.getMiracles().get(ITEM);
    return scene != null
        && player.getScene() == scene
        && scene.getPlaneType() == PlaneType.Rogue
        && run == player.getGameplay().getRogue()
        && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        && run.isInside()
        && !run.pending()
        && !player.isInBattle()
        && entry != null
        && entry.getId() == room.MapEntrance()
        && player.getEntryId() == room.MapEntrance()
        && scene.getPlaneId() == entry.getPlaneID()
        && scene.getFloorId() == entry.getFloorID()
        && Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())
        && !run.getRoster().isEmpty()
        && run.getRoster().size() <= 4
        && new HashSet<>(run.getRoster()).size() == run.getRoster().size()
        && run.getRoster().stream()
            .allMatch(
                id ->
                    id != null
                        && id > 0
                        && player.getAvatarById(id) != null
                        && run.getVitals().get(id) != null
                        && run.getVitals().get(id).getHp() >= 0
                        && run.getVitals().get(id).getHp() <= 10000)
        && RogueMiracles.active(run, ITEM)
        && item.getLottery() != null
        && item.getLottery().valid(item)
        && item.getLottery().getRolls().size() < 4096;
  }

  public static Destruction prepare(Player player, EntityProp prop) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      var scene = player.getScene();
      if (!context(player, run, scene)
          || prop == null
          || !scene.canDestroyProp(prop)
          || !RoguePropDestruction.requiresReceipt(player, prop)
          || !configurationValid()) return null;
      var identity = key(run, scene, prop);
      var item = run.getMiracles().get(ITEM);
      if (!RogueLotteryState.validKey(identity) || item.getDestroyedProps().contains(identity))
        return null;
      return new Destruction(
          player,
          run,
          scene,
          prop,
          prop.getEntityId(),
          identity,
          item,
          outcome(item.getAcquisitionId(), identity),
          definitions());
    }
  }

  /** A valid active lottery must be prepared before accepting its source-bound physical trigger. */
  public static boolean requiresRoll(Player player, EntityProp prop) {
    return context(player, player.getGameplay().getRogue(), player.getScene())
        && RoguePropDestruction.requiresReceipt(player, prop);
  }

  static boolean configurationValid() {
    var pool = GameplayDefinitions.miracleGroups.get(POOL);
    return pool != null
        && pool.RogueMiracleGroupID() == POOL
        && pool.MiracleWeight() != null
        && !pool.MiracleWeight().isEmpty()
        && pool.MiracleWeight().size() <= 1000
        && pool.MiracleWeight().entrySet().stream()
            .allMatch(
                e ->
                    e.getKey() != null
                        && e.getKey() > 0
                        && e.getValue() != null
                        && e.getValue() > 0);
  }

  private static String definitions() {
    return JsonUtils.encode(
        Arrays.asList(
            GameplayDefinitions.miracles,
            GameplayDefinitions.miracleEffects,
            GameplayDefinitions.miracleGroups.get(POOL),
            RogueHandbookCatalog.snapshot()));
  }

  private static List<Integer> candidates(Player player) {
    var pool = GameplayDefinitions.miracleGroups.get(POOL);
    if (!configurationValid()) return null;
    var ids =
        pool.MiracleWeight().keySet().stream()
            .filter(id -> id != 21 && id != ITEM && RogueMiracles.canAcquire(player, id))
            .sorted()
            .toList();
    return ids;
  }

  public static final class Destruction {
    private final Player player;
    private final GameplayState.RogueRun run;
    private final Scene scene;
    private final EntityProp prop;
    private final int entity;
    private final String key, definitions;
    private final GameplayState.RogueMiracle item;
    private final RogueLotteryState.Outcome outcome;
    private final List<Integer> roster;
    private final Map<Integer, GameplayState.RogueVitals> vitals;
    private final Map<Integer, emu.lunarcore.game.avatar.GameAvatar> actors;
    private boolean completed;

    private Destruction(
        Player player,
        GameplayState.RogueRun run,
        Scene scene,
        EntityProp prop,
        int entity,
        String key,
        GameplayState.RogueMiracle item,
        RogueLotteryState.Outcome outcome,
        String definitions) {
      this.player = player;
      this.run = run;
      this.scene = scene;
      this.prop = prop;
      this.entity = entity;
      this.key = key;
      this.item = item;
      this.outcome = outcome;
      this.definitions = definitions;
      this.roster = List.copyOf(run.getRoster());
      this.vitals = new HashMap<>();
      this.actors = new HashMap<>();
      roster.forEach(
          id -> {
            vitals.put(id, run.getVitals().get(id));
            actors.put(id, player.getAvatarById(id));
          });
    }

    /** Called after the exact physical prop was removed and its ordinary recovery was applied. */
    public void complete() {
      synchronized (player) {
        if (completed
            || !context(player, run, scene)
            || run.getMiracles().get(ITEM) != item
            || prop.getEntityId() != 0
            || scene.getEntities().containsKey(entity)
            || !key.equals(key(run, scene, prop))
            || !roster.equals(run.getRoster())
            || roster.stream()
                .anyMatch(
                    id ->
                        run.getVitals().get(id) != vitals.get(id)
                            || player.getAvatarById(id) != actors.get(id))
            || !definitions.equals(definitions())) return;
        RogueLotteryState.Roll roll = RogueLotteryState.Roll.none();
        RogueMiracles.Acquisition grant = null;
        if (outcome == RogueLotteryState.Outcome.BREAK) {
          var before = new TreeMap<Integer, Integer>();
          var after = new TreeMap<Integer, Integer>();
          for (int id : run.getRoster()) {
            int hp = run.getVitals().get(id).getHp();
            before.put(id, hp);
            after.put(id, (hp + 99) / 100);
          }
          roll = RogueLotteryState.Roll.broken(before, after);
          if (!item.getLottery().remember(item, key, roll) || !item.consume()) return;
          after.forEach((id, hp) -> run.getVitals().get(id).setHp(hp));
        } else if (outcome == RogueLotteryState.Outcome.GAIN) {
          var ids = candidates(player);
          if (ids == null) return;
          if (!ids.isEmpty()) {
            var pool = GameplayDefinitions.miracleGroups.get(POOL);
            long total = 0;
            for (int id : ids) total = Math.addExact(total, pool.MiracleWeight().get(id));
            var random =
                new Random(Objects.hash(RogueLotteryState.RULE, item.getAcquisitionId(), key));
            long ticket = random.nextLong(total);
            int awarded = 0;
            for (int id : ids) {
              ticket -= pool.MiracleWeight().get(id);
              if (ticket < 0) {
                awarded = id;
                break;
              }
            }
            var projected =
                JsonUtils.decode(JsonUtils.encode(player.getGameplay()), GameplayState.class);
            var preview = new PreviewPlayer(player, projected);
            grant = RogueMiracles.acquireDeferred(preview, awarded);
            if (grant == null || grant.diceChange() != null) return;
            var next = projected.getRogue();
            var acquired = next.getMiracles().get(awarded);
            roll = RogueLotteryState.Roll.gain(awarded, acquired.getAcquisitionId());
            var nextLottery = next.getMiracles().get(ITEM);
            if (!nextLottery.getLottery().remember(nextLottery, key, roll)) return;
            int enhancements =
                projected.getRogueEnhancements().total()
                    - player.getGameplay().getRogueEnhancements().total();
            if (enhancements < 0
                || !player.getGameplay().getRogueEnhancements().canRecordEnhancements(enhancements))
              return;
            run.getMiracles().clear();
            run.getMiracles().putAll(next.getMiracles());
            run.getBlessings().clear();
            run.getBlessings().putAll(next.getBlessings());
            player.getGameplay().getRogueCollectedBuffs().clear();
            player
                .getGameplay()
                .getRogueCollectedBuffs()
                .addAll(projected.getRogueCollectedBuffs());
            player.getGameplay().getRogueHandbook().merge(projected.getRogueHandbook());
            if (!player.getGameplay().getRogueEnhancements().recordEnhancements(enhancements))
              throw new IllegalStateException("Prechecked enhancement count changed");
          } else {
            roll = RogueLotteryState.Roll.exhausted();
            if (!item.getLottery().remember(item, key, roll)) return;
          }
        } else if (!item.getLottery().remember(item, key, roll)) return;
        completed = true;
        boolean resonancePending = grant != null && RogueResonance.queue(run);
        player.save();
        if (grant != null) {
          org.hkrpg.server.protocol.Universe46.syncMiracle(player, roll.getAwardedId(), true, 0);
          grant.syncEffects(player);
        }
        if (resonancePending) org.hkrpg.server.protocol.Universe46.syncPending(player);
        if (outcome == RogueLotteryState.Outcome.BREAK) {
          org.hkrpg.server.protocol.Universe46.syncMiracle(player, ITEM, false, 0);
          if (player.isOnline())
            player.sendPacket(new PacketSyncLineupNotify(player.getUniverseManager().lineup()));
        }
      }
    }
  }

  private static final class PreviewPlayer extends Player {
    private final Player source;
    private final GameplayState state;

    private PreviewPlayer(Player source, GameplayState state) {
      this.source = source;
      this.state = state;
    }

    @Override
    public GameplayState getGameplay() {
      return state;
    }

    @Override
    public int getLevel() {
      return source.getLevel();
    }

    @Override
    public int getWorldLevel() {
      return source.getWorldLevel();
    }

    @Override
    public MissionManager getMissionManager() {
      return source.getMissionManager();
    }
  }

  private RogueGalacticLottery() {}
}
