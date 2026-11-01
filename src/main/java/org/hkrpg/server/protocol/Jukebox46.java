package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.ArrayList;
import org.hkrpg.server.gameplay.JukeboxState;

/** CN4.6 save 3105/3175 and query 3134/3125 share the complete native playback container. */
public final class Jukebox46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) {
    var protocol = session.getProtocol();
    if (!protocol.experimental() || command != 3105 && command != 3134) return false;
    var player = session.getPlayer();
    if (session.getState() != SessionState.ACTIVE || player == null) return true;
    synchronized (player) {
      var gameplay = player.getGameplay();
      var previous = gameplay.getJukebox();
      var current = previous == null
          ? new JukeboxState(player.getCurrentBgm(), false, false, java.util.List.of()) : previous;
      Retcode result = Retcode.SUCC;
      if (command == 3105) {
        var requested = read(session, bytes);
        if (requested == null || !requested.valid()) result = Retcode.REQ_PARA_INVALID;
        else {
          gameplay.setJukebox(requested);
          try {
            player.save();
            current = requested;
          } catch (RuntimeException failed) {
            gameplay.setJukebox(previous);
            result = Retcode.SERVER_INTERNAL_ERROR;
          }
        }
      } else if (bytes == null || bytes.length != 0) result = Retcode.REQ_PARA_INVALID;
      String responseName = command == 3105 ? "PlayBackGroundMusicScRsp" : "GetJukeboxDataScRsp";
      var response = builder(session, responseName);
      // Even errors carry the old selection: the native callback dereferences this container.
      if (!current.valid()) {
        if (result == Retcode.SUCC) result = Retcode.PLAYER_DATA_ERROR;
        current = new JukeboxState(0, false, false, java.util.List.of());
      }
      set(response, "GFFOBALDBPM", playback(session, current));
      if (command == 3134) {
        for (int id : GameData.getAllMusicIds()) {
          var row = builder(session, "LHHGCDLCJDA");
          set(row, "id", id);
          set(row, "group_id", GameData.getMusicGroupId(id));
          // Preserve the inherited catalog policy; this fix does not introduce unlock progression.
          set(row, "BIMDKNLMICG", true);
          add(response, "IOKAJIBHLMP", row.build());
        }
      }
      set(response, "retcode", result.getVal());
      session.sendClientPacket(responseName, response.build().toByteArray());
    }
    return true;
  }

  private static JukeboxState read(GameSession session, byte[] bytes) {
    if (bytes == null || bytes.length > 256) return null;
    try {
      var descriptor = session.getProtocol().clientSchema().message("PlayBackGroundMusicCsReq");
      if (!Parkour46.wireValid(descriptor, bytes, 0)) return null;
      var request = DynamicMessage.parseFrom(descriptor, bytes);
      if (!request.hasField(descriptor.findFieldByName("IKJNIKIGFLF"))) return null;
      var mix = (DynamicMessage) value(request, "IKJNIKIGFLF");
      // An absent BGM is the native ambient-only representation; zero also means no BGM.
      var music = (DynamicMessage) value(mix, "JKNLCEEDAHJ");
      var layers = new ArrayList<JukeboxState.Layer>();
      for (Object entry : (java.util.List<?>) value(mix, "NNJCEINOEHH")) {
        var layer = (DynamicMessage) entry;
        layers.add(new JukeboxState.Layer(number(layer, "id"), number(layer, "volume")));
      }
      return new JukeboxState(number(music, "id"), (boolean) value(music, "is_pause"),
          (boolean) value(mix, "PGPNCNABHAM"), layers);
    } catch (IOException invalid) {
      return null;
    }
  }

  private static DynamicMessage playback(GameSession session, JukeboxState state) {
    var music = builder(session, "KHADHDLCNOI");
    set(music, "id", state.getMusicId());
    set(music, "is_pause", state.isMusicPaused());
    var mix = builder(session, "MNCBEDBDDHL");
    set(mix, "JKNLCEEDAHJ", music.build());
    set(mix, "PGPNCNABHAM", state.isWhiteNoisePaused());
    for (var entry : state.getLayers()) {
      var layer = builder(session, "AAKECCJELLI");
      set(layer, "id", entry.getId());
      set(layer, "volume", entry.getVolume());
      add(mix, "NNJCEINOEHH", layer.build());
    }
    return mix.build();
  }

  private Jukebox46() {}
}
