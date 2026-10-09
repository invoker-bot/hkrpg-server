package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import lombok.Getter;

/** Verified source-1 default/easy selections at original worlds 1..6, frozen for each raid. */
@Entity(useDiscriminator = false)
public final class RaidDifficultySettings {
  private Map<Integer, Choice> choices = new HashMap<>();

  private static boolean verifiedWorld(int world) {
    return world >= 1 && world <= 6;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Choice {
    private int raid, source, difficulty;
    private String resourceIdentity;

    protected Choice() {}

    private Choice(int raid, int difficulty, String identity) {
      this.raid = raid;
      source = 1;
      this.difficulty = difficulty;
      resourceIdentity = identity;
    }

    boolean matches(GameplayDefinitions.Raid row) {
      return row != null
          && raid == row.RaidID()
          && source == 1
          && (difficulty == 0 || difficulty == 1)
          && resourceIdentity != null
          && resourceIdentity.equals(identity(row));
    }
  }

  static String identity(GameplayDefinitions.Raid row) {
    if (row == null
        || row.RaidID() != 43306
        || row.WorldLevel() != 0
        || !RaidEntranceSources.adjustable(row)) return null;
    var source = RaidEntranceSources.fingerprint(row);
    if (!source.isJsonObject()) return null;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public List<Choice> snapshot() {
    if (choices == null || choices.size() > 1)
      throw new IllegalStateException("Invalid saved raid difficulty choices");
    var result = new ArrayList<Choice>();
    for (var item : choices.entrySet()) {
      var value = item.getValue();
      if (item.getKey() == null
          || value == null
          || item.getKey() != value.raid
          || !value.matches(MissionRaid.resource(value.raid, 0)))
        throw new IllegalStateException("Changed saved raid difficulty source");
      result.add(value);
    }
    return List.copyOf(result);
  }

  public Retcode update(Player player, int raid, int source, int difficulty) {
    synchronized (player) {
      var row = MissionRaid.resource(raid, 0);
      var identity = identity(row);
      if (source != 1
          || difficulty < 0
          || difficulty > 1
          || !verifiedWorld(player.getWorldLevel())
          || identity == null) return Retcode.REQ_PARA_INVALID;
      if (player.isInBattle()
          || player.getInstance() != null
          || player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getTrialRun() != null
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getRogue().isInside()) return Retcode.REQ_PARA_INVALID;
      try {
        snapshot();
      } catch (IllegalStateException invalid) {
        return Retcode.PLAYER_DATA_ERROR;
      }
      var previous = choices.get(raid);
      if (previous != null && previous.difficulty == difficulty) return Retcode.SUCC;
      choices.put(raid, new Choice(raid, difficulty, identity));
      try {
        player.save();
      } catch (RuntimeException failed) {
        if (previous == null) choices.remove(raid);
        else choices.put(raid, previous);
        return Retcode.SYSTEM_BUSY;
      }
      return Retcode.SUCC;
    }
  }

  private int selection(GameplayDefinitions.Raid row) {
    if (identity(row) == null) return -1;
    try {
      var saved = snapshot();
      if (saved.isEmpty()) return 0;
      var value = saved.getFirst();
      return value.matches(row) ? value.getDifficulty() : -1;
    } catch (IllegalStateException invalid) {
      return -1;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Frozen {
    private int raid, actualWorld, effectiveWorld, difficulty;
    private String resourceIdentity;

    protected Frozen() {}

    private Frozen(GameplayDefinitions.Raid row, int world, int selectedDifficulty) {
      raid = row.RaidID();
      actualWorld = world;
      difficulty = selectedDifficulty;
      effectiveWorld = world - selectedDifficulty;
      resourceIdentity = identity(row);
    }

    boolean matches(Player player, GameplayDefinitions.Raid row) {
      return row != null
          && raid == row.RaidID()
          && verifiedWorld(actualWorld)
          && (difficulty == 0 || difficulty == 1)
          && effectiveWorld == actualWorld - difficulty
          && player.getWorldLevel() == actualWorld
          && resourceIdentity != null
          && resourceIdentity.equals(identity(row));
    }
  }

  static boolean permits(Player player, GameplayDefinitions.Raid row) {
    var saved = player.getGameplay().getMissionRaids().get(row.RaidID());
    if (saved != null && saved.getDifficultySnapshot() != null)
      return saved.getDifficultySnapshot().matches(player, row);
    if (!RaidEntranceSources.adjustable(row)) return true;
    if (player.getWorldLevel() == 0) return true;
    return saved == null
        && verifiedWorld(player.getWorldLevel())
        && player.getGameplay().getRaidDifficultySettings().selection(row) >= 0;
  }

  static Frozen freeze(Player player, GameplayDefinitions.Raid row) {
    int selected = player.getGameplay().getRaidDifficultySettings().selection(row);
    return verifiedWorld(player.getWorldLevel()) && selected >= 0
        ? new Frozen(row, player.getWorldLevel(), selected)
        : null;
  }

  /** Destination Scene can be under construction before Player.scene and entry are published. */
  public static int world(Scene scene) {
    if (scene == null) return -1;
    var player = scene.getPlayer();
    var manager = new MissionRaid(player);
    var run = manager.current();
    if (run == null)
      return player.getGameplay().getCurrentMissionRaid() == 0 ? player.getWorldLevel() : -1;
    if (run.getDifficultySnapshot() == null)
      return RaidEntranceSources.adjustable(
                  MissionRaid.resource(run.getRaidId(), run.getWorldLevel()))
              && player.getWorldLevel() != 0
          ? -1
          : player.getWorldLevel();
    return scene.getDimensionId() == 0
            && scene.getPlaneId() == run.getPlane()
            && scene.getFloorId() == run.getFloor()
            && manager.owns(run.getMainMission())
        ? run.getDifficultySnapshot().getEffectiveWorld()
        : -1;
  }

  public static int world(Player player) {
    var run = new MissionRaid(player).current();
    if (run == null)
      return player.getGameplay().getCurrentMissionRaid() == 0 ? player.getWorldLevel() : -1;
    if (run.getDifficultySnapshot() == null)
      return RaidEntranceSources.adjustable(
                  MissionRaid.resource(run.getRaidId(), run.getWorldLevel()))
              && player.getWorldLevel() != 0
          ? -1
          : player.getWorldLevel();
    return new MissionRaid(player).active() ? world(player.getScene()) : -1;
  }

  public record BattleScope(Player owner, GameplayState.MissionRaidRun run, Frozen original) {
    public boolean matches(Player player) {
      return player == owner
          && new MissionRaid(player).current() == run
          && run.getDifficultySnapshot() == original
          && new MissionRaid(player).active()
          && original.matches(player, MissionRaid.resource(run.getRaidId(), run.getWorldLevel()));
    }

    public int world() {
      return original.getEffectiveWorld();
    }
  }

  public static BattleScope battleScope(Player player) {
    var manager = new MissionRaid(player);
    var run = manager.current();
    return run != null && run.getDifficultySnapshot() != null && manager.active()
        ? new BattleScope(player, run, run.getDifficultySnapshot())
        : null;
  }
}
