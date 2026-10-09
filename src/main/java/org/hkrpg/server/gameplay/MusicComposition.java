package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Saved arrangements use the resource song's track order and one bit per resource bar. */
public final class MusicComposition {
  private final Player player;

  public MusicComposition(Player player) {
    this.player = player;
  }

  public boolean subFinished(int id) {
    if (id <= 0 || player.getGameplay().getCancelledSubs().contains(id)) return false;
    var sub = player.getMissionManager().sub(id);
    var config = GameplayDefinitions.subs.get(id);
    return sub != null
        && sub.getPhase() != QuestState.Phase.DOING
        && (config == null
            || !player.getGameplay().getCancelledMissions().contains(config.getMainMissionID()));
  }

  public boolean songUnlocked(int id) {
    var song = GameplayDefinitions.musicSongs.get(id);
    if (song == null || !new MusicManager(player).open()) return false;
    return switch (song.UnlockType()) {
      case 1 ->
          song.UnlockTypeParam() > 0
              && !player.getGameplay().getCancelledMissions().contains(song.UnlockTypeParam())
              && player.getMissionManager().finished(song.UnlockTypeParam());
      case 3 -> subFinished(song.UnlockTypeParam());
      case 2 -> {
        var levels = MusicManager.resourceLevels();
        int minimum = song.UnlockTypeParam() == 0 ? 1 : song.UnlockTypeParam();
        yield minimum > 0
            && minimum <= 3
            && !levels.isEmpty()
            && levels.stream()
                .allMatch(
                    l ->
                        player.getGameplay().getRhythm().getStars().getOrDefault(l.ID(), 0)
                            >= minimum);
      }
      default -> false;
    };
  }

  public boolean trackUnlocked(int id) {
    var track = GameplayDefinitions.musicTracks.get(id);
    return track != null
        && (track.UnlockSubMissionID() == 0 || subFinished(track.UnlockSubMissionID()));
  }

  public boolean sfxUnlocked(int id) {
    var song = GameplayDefinitions.musicSongs.get(id);
    return song != null
        && new MusicManager(player).open()
        && song.SoundEffectIDList() != null
        && !song.SoundEffectIDList().isEmpty()
        && subFinished(song.SoundEffectUnlockSubMission());
  }

  public Set<Integer> unlockedSfxSongs() {
    var songs = new TreeSet<Integer>();
    GameplayDefinitions.musicSongs.keySet().stream().filter(this::sfxUnlocked).forEach(songs::add);
    return songs;
  }

  public Retcode save(int songId, int preset, List<Integer> grid, List<Integer> effects) {
    synchronized (player) {
      var song = GameplayDefinitions.musicSongs.get(songId);
      int gate =
          GameplayDefinitions.musicConstants.getOrDefault(
              "Activity_MusicRhythm_MusicComposeUnlockMission", -1);
      if (gate < 0
          || (gate != 0
              && (!player.getMissionManager().finished(gate)
                  || player.getGameplay().getCancelledMissions().contains(gate)))
          || !songUnlocked(songId)
          || player.isInBattle()
          || player.getGameplay().getRhythm().getActiveLevel() != 0) return Retcode.MUSIC_LOCKED;
      if (song.TrackIDList() == null
          || song.TrackIDList().isEmpty()
          || song.GridNumList() == null
          || song.GridNumList().isEmpty()
          || song.GridNumList().size() > 32
          || song.GridNumList().stream().anyMatch(v -> v == null || v <= 0)
          || grid == null
          || effects == null
          || preset < 0
          || effects.size() > 32
          || new HashSet<>(effects).size() != effects.size()
          || (!effects.isEmpty()
              && (!subFinished(song.SoundEffectUnlockSubMission())
                  || song.SoundEffectIDList() == null
                  || !song.SoundEffectIDList().containsAll(effects))))
        return Retcode.REQ_PARA_INVALID;
      List<Integer> effective;
      if (preset == 0) {
        effective = grid;
      } else {
        var config = GameplayDefinitions.musicPresets.get(preset);
        if (!grid.isEmpty()
            || song.PresetIDList() == null
            || !song.PresetIDList().contains(preset)
            || config == null
            || config.PresetGridConfig() == null) return Retcode.REQ_PARA_INVALID;
        effective = config.PresetGridConfig();
      }
      if (effective.size() != song.TrackIDList().size()) return Retcode.REQ_PARA_INVALID;
      long allowed = (1L << song.GridNumList().size()) - 1;
      for (int i = 0; i < effective.size(); i++) {
        var value = effective.get(i);
        if (value == null || (Integer.toUnsignedLong(value) & ~allowed) != 0)
          return Retcode.REQ_PARA_INVALID;
        // Built-in presets are resource authored; edits cannot enable an unacquired instrument.
        if (preset == 0 && value != 0 && !trackUnlocked(song.TrackIDList().get(i)))
          return Retcode.MUSIC_LOCKED;
      }
      // A preset request carries no edited grid, so the client keeps its resource preset.
      player.getGameplay().getRhythm().compose(songId, preset, grid, effects);
      player.save();
      player.getMissionManager().onMusicCompositionSaved(songId);
      return Retcode.SUCC;
    }
  }
}
