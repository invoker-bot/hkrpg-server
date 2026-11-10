package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.gameplay.MissionRaid;
import org.hkrpg.server.gameplay.ParkourRaid;
import org.hkrpg.server.gameplay.RaidTeams;

/** Verified raid wire fields; unsaved parkour and a resource-checked Player/Mission subset. */
public final class Raid46 {
  private static final Set<String> REQUESTS =
      Set.of(
          "StartRaidCsReq",
          "LeaveRaidCsReq",
          "GetRaidInfoCsReq",
          "GetSaveRaidCsReq",
          "GetAllSaveRaidCsReq");

  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    String name =
        REQUESTS.stream()
            .filter(n -> s.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    var req = s.getProtocol().clientSchema().parse(name, bytes);
    synchronized (s.getPlayer()) {
      var manager = new ParkourRaid(s.getPlayer());
      var story = new MissionRaid(s.getPlayer());
      boolean valid = Parkour46.wireValid(req.getDescriptorForType(), bytes, 0);
      if (!name.equals("StartRaidCsReq") && !name.equals("LeaveRaidCsReq")) {
        var query = builder(s, name.replace("CsReq", "ScRsp"));
        var result = valid ? Retcode.SUCC : Retcode.REQ_PARA_INVALID;
        if (name.equals("GetSaveRaidCsReq")) {
          int id = number(req, "raid_id"), worldLevel = number(req, "world_level");
          if (valid && !ParkourRaid.supported(id, worldLevel)) result = Retcode.RAID_NO_SAVE;
          set(query, "raid_id", id);
          set(query, "world_level", worldLevel);
          set(query, "is_save", false);
        }
        if (valid && name.equals("GetRaidInfoCsReq")) {
          for (var entry :
              new TreeMap<>(s.getPlayer().getGameplay().getMissionRaids()).entrySet()) {
            var run = entry.getValue();
            if (run == null || entry.getKey() != run.getRaidId() || !story.completed(run)) continue;
            var finished = builder(s, "FinishedRaidInfo");
            set(finished, "raid_id", run.getRaidId());
            set(finished, "world_level", run.getWorldLevel());
            add(query, "finished_raid_info_list", finished.build());
          }
        }
        // Unsaved instances have no RaidData snapshot; real completions belong to GetRaidInfo.
        set(query, "retcode", result.getVal());
        s.sendClientPacket(name.replace("CsReq", "ScRsp"), query.build().toByteArray());
        return true;
      }
      var code = Retcode.REQ_PARA_INVALID;
      String responseName = name.replace("CsReq", "ScRsp");
      int raid = number(req, "raid_id");
      int world = s.getPlayer().getGameplay().getParkourVenue().getWorldLevel();
      if (story.current() != null) world = story.current().getWorldLevel();
      if (name.equals("StartRaidCsReq")) {
        world = number(req, "world_level");
        var selected = new ArrayList<Integer>();
        var choices = new ArrayList<RaidTeams.Selection>();
        for (var raw : (List<?>) value(req, "NIMDEPFJJNI")) {
          var avatar = (DynamicMessage) raw;
          String avatarType = ((EnumValueDescriptor) value(avatar, "avatar_type")).getName();
          if (number(avatar, "assist_uid") != 0
              || !Set.of("AvatarType_AvatarFormalType", "AvatarType_AvatarTrialType")
                  .contains(avatarType)) valid = false;
          selected.add(number(avatar, "id"));
          choices.add(
              new RaidTeams.Selection(
                  number(avatar, "id"), "AvatarType_AvatarTrialType".equals(avatarType)));
        }
        if (selected.size() > 4 || !((List<?>) value(req, "avatar_list")).isEmpty()) valid = false;
        if (valid)
          code =
              MissionRaid.resource(raid, world) == null
                  ? (choices.stream().anyMatch(RaidTeams.Selection::trial)
                      ? Retcode.REQ_PARA_INVALID
                      : manager.enter(
                          raid,
                          world,
                          number(req, "is_save"),
                          number(req, "prop_entity_id"),
                          selected))
                  : story.enterSelected(
                      raid, world, number(req, "is_save"), number(req, "prop_entity_id"), choices);
      } else if (valid)
        code =
            story.current() == null
                ? manager.leave(raid, (boolean) value(req, "is_save"))
                : story.leave(raid, (boolean) value(req, "is_save"));
      GameDiagnostics.outcome(s, name, raid, code, "raid-entry-or-exit", "worldLevel=" + world);
      var response = builder(s, responseName);
      set(response, "retcode", code.getVal());
      if (name.equals("LeaveRaidCsReq")) {
        set(response, "raid_id", raid);
        set(response, "world_level", world);
      }
      if (code == Retcode.SUCC) {
        if (name.equals("StartRaidCsReq")) {
          var scene = builder(s, "GEGLJDELBHA");
          set(scene, "raid_id", raid);
          set(scene, "world_level", world);
          set(
              scene,
              "MOPHKIGKDIK",
              converted(s, "SceneInfo", s.getPlayer().getScene().toProto().toByteArray()));
          set(
              scene,
              "lineup",
              converted(s, "LineupInfo", s.getPlayer().getCurrentLineup().toProto().toByteArray()));
          set(response, "scene", scene.build());
        } else {
          // LeaveRaidScRsp has no scene payload: return through the existing verified scene notify.
          s.send(CmdId.SyncServerSceneChangeNotify);
          s.send(
              new emu.lunarcore.server.packet.send.PacketEnterSceneByServerScNotify(s.getPlayer()));
        }
      }
      s.sendClientPacket(responseName, response.build().toByteArray());
      if (code == Retcode.SUCC && name.equals("StartRaidCsReq")) sync(s);
    }
    return true;
  }

  private static DynamicMessage converted(GameSession s, String type, byte[] bytes)
      throws IOException {
    return s.getProtocol().clientSchema().parse(type, s.getProtocol().translate(type, bytes, true));
  }

  public static void sync(GameSession s) throws IOException {
    var venue = s.getPlayer().getGameplay().getParkourVenue();
    var story = new MissionRaid(s.getPlayer());
    var run = story.current();
    if (!new ParkourRaid(s.getPlayer()).active() && !story.active()) return;
    var b = builder(s, "RaidInfoNotify");
    set(b, "raid_id", run == null ? venue.getRaidId() : run.getRaidId());
    set(b, "world_level", run == null ? venue.getWorldLevel() : run.getWorldLevel());
    var field = b.getDescriptorForType().findFieldByName("status");
    b.setField(
        field, field.getEnumType().findValueByNumber(run != null && story.completed(run) ? 2 : 1));
    if (run != null && story.completed(run))
      set(
          b,
          "raid_finish_time",
          s.getPlayer().getGameplay().getMainMissions().get(run.getMainMission()).getFinishTime());
    s.sendClientPacket("RaidInfoNotify", b.build().toByteArray());
  }

  public static void sync(emu.lunarcore.game.player.Player player) {
    var session = player.getSession();
    if (session == null
        || session.getState() != SessionState.ACTIVE
        || !session.getProtocol().experimental()) return;
    try {
      sync(session);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot encode verified raid notification", failure);
    }
  }
}
