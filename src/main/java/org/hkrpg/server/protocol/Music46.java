package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

public final class Music46 {
  /** Validate raw scalar widths and multiplicity before protobuf parsing can truncate values. */
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    String name;
    if (command == session.getProtocol().clientCommand("MusicRhythmStartLevelCsReq"))
      name = "MusicRhythmStartLevelCsReq";
    else if (command == session.getProtocol().clientCommand("MusicRhythmFinishLevelCsReq"))
      name = "MusicRhythmFinishLevelCsReq";
    else return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var schema = session.getProtocol().clientSchema();
      if (!Parkour46.wireValid(schema.message(name), bytes, 0)) {
        var response = builder(session, name.replace("CsReq", "ScRsp"));
        set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
        if (name.equals("MusicRhythmFinishLevelCsReq"))
          set(response, "level_id", session.getPlayer().getGameplay().getRhythm().getActiveLevel());
        session.sendClientPacket(name.replace("CsReq", "ScRsp"), response.build().toByteArray());
      } else handle(session, name, schema.parse(name, bytes));
    }
    return true;
  }

  static void handle(GameSession s, String name, DynamicMessage req) {
    var p = s.getPlayer();
    var manager = new MusicManager(p);
    var state = p.getGameplay().getRhythm();
    var composition = new MusicComposition(p);
    switch (name) {
      case "MusicRhythmDataCsReq" -> {
        var b = builder(s, "MusicRhythmDataScRsp");
        set(b, "cur_level_id", state.getActiveLevel());
        set(b, "cur_song_id", state.getCurrentSong());
        // Despite its dumped name, CN 4.6 consumes field 4 as the global maximum difficulty flag.
        set(b, "show_hint", manager.maxDifficultyUnlocked());
        for (var level :
            GameplayDefinitions.musicLevels.values().stream()
                .sorted(Comparator.comparingInt(GameplayDefinitions.MusicLevel::ID))
                .toList()) {
          if (!manager.unlocked(level.ID())) continue;
          var row = builder(s, "MusicRhythmLevel");
          set(row, "level_id", level.ID());
          set(row, "full_combo", state.getFullCombos().contains(level.ID()));
          set(row, "unlock_level", state.getStars().getOrDefault(level.ID(), 0));
          add(b, "level_data_list", row.build());
        }
        if (manager.open())
          for (var entry : new TreeMap<>(state.getCompositions()).entrySet()) {
            if (!GameplayDefinitions.musicSongs.containsKey(entry.getKey())) continue;
            var row = builder(s, "MusicRhythmGroup");
            set(row, "music_group_id", entry.getValue().getPreset());
            set(row, "music_group_phase", entry.getKey());
            entry.getValue().getGrid().forEach(v -> add(row, "EOHMNCBOALC", v));
            entry.getValue().getEffects().forEach(v -> add(row, "BBINFDHMGII", v));
            add(b, "music_group", row.build());
          }
        var groups =
            GameplayDefinitions.musicGroups.values().stream()
                .filter(
                    g ->
                        GameplayDefinitions.musicLevels.values().stream()
                            .anyMatch(l -> l.Group() == g.ID() && manager.unlocked(l.ID())))
                .toList();
        var phases =
            groups.stream().map(GameplayDefinitions.MusicGroup::Phase).distinct().sorted().toList();
        for (int phase : phases) {
          var config = GameplayDefinitions.musicPhases.get(phase);
          if (config == null) continue;
          add(b, "unlock_phase_list", phase);
        }
        if (manager.open()) {
          // CN field 9 sets MusicRhythmSong.IsSFXUnlock, not basic song availability.
          GameplayDefinitions.musicSongs.keySet().stream()
              .filter(composition::sfxUnlocked)
              .sorted()
              .forEach(id -> add(b, "unlock_song_list", id));
          GameplayDefinitions.musicTracks.keySet().stream()
              .filter(composition::trackUnlocked)
              .sorted()
              .forEach(id -> add(b, "unlock_track_list", id));
        }
        s.sendClientPacket("MusicRhythmDataScRsp", b.build().toByteArray());
      }
      case "MusicRhythmSaveSongConfigDataCsReq" -> {
        var field = req.getDescriptorForType().findFieldByName("BENAFNHLCEO");
        var config = (DynamicMessage) value(req, "BENAFNHLCEO");
        var code = Retcode.REQ_PARA_INVALID;
        if (only(req, "BENAFNHLCEO")
            && req.hasField(field)
            && config.getUnknownFields().asMap().isEmpty()
            && only(config, "music_group_id", "music_group_phase", "EOHMNCBOALC", "BBINFDHMGII")) {
          @SuppressWarnings("unchecked")
          var grid = (List<Integer>) value(config, "EOHMNCBOALC");
          @SuppressWarnings("unchecked")
          var effects = (List<Integer>) value(config, "BBINFDHMGII");
          code =
              composition.save(
                  number(config, "music_group_phase"),
                  number(config, "music_group_id"),
                  grid,
                  effects);
        }
        send(
            s,
            "MusicRhythmSaveSongConfigDataScRsp",
            Map.of("retcode", code.getVal(), "cur_song_id", state.getCurrentSong()));
      }
      case "MusicRhythmStartLevelCsReq" -> {
        int id = number(req, "level_id");
        var code = only(req, "level_id") ? manager.start(id) : Retcode.REQ_PARA_INVALID;
        send(
            s,
            "MusicRhythmStartLevelScRsp",
            Map.of(
                "retcode",
                code.getVal(),
                "level_id",
                id,
                "trans_no",
                code == Retcode.SUCC ? state.getTransaction() : ""));
      }
      case "MusicRhythmFinishLevelCsReq" -> {
        int id = state.getActiveLevel();
        // score_id is retained as an opaque client counter; rewards use the named role_star report.
        var code =
            only(req, "role_star", "score_id", "full_combo")
                ? manager.finish(number(req, "role_star"), (Boolean) value(req, "full_combo"))
                : Retcode.REQ_PARA_INVALID;
        send(s, "MusicRhythmFinishLevelScRsp", Map.of("retcode", code.getVal(), "level_id", id));
      }
      default -> throw new ProtocolException("Unsupported music request");
    }
  }

  public static void syncSfx(emu.lunarcore.game.player.Player player, Set<Integer> songs) {
    if (songs.isEmpty()
        || !player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var s = player.getSession();
    var b = builder(s, "MusicRhythmUnlockSongSfxScNotify");
    songs.stream().sorted().forEach(id -> add(b, "PHCHFKMHDMA", id));
    s.sendClientPacket("MusicRhythmUnlockSongSfxScNotify", b.build().toByteArray());
  }

  public static void syncMaximum(emu.lunarcore.game.player.Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var s = player.getSession();
    s.sendClientPacket("MusicRhythmMaxDifficultyLevelsUnlockNotify", new byte[0]);
  }
}
