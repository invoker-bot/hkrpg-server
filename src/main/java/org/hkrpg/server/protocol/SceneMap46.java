package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.MissionSceneEntrances;
import org.hkrpg.server.gameplay.SceneAnchors;
import org.hkrpg.server.gameplay.StoryLineConditions;

/** Base scene map queries expose authored defaults and saved anchor transitions. */
public final class SceneMap46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (command == session.getProtocol().clientCommand("EnterSectionCsReq")) {
      if (session.getState() != SessionState.ACTIVE) return true;
      acknowledgeSection(session, bytes);
      return true;
    }
    String name;
    if (command == session.getProtocol().clientCommand("GetUnlockTeleportCsReq"))
      name = "GetUnlockTeleportCsReq";
    else if (command == session.getProtocol().clientCommand("GetSceneMapInfoCsReq"))
      name = "GetSceneMapInfoCsReq";
    else if (command == session.getProtocol().clientCommand("EnterSceneCsReq"))
      name = "EnterSceneCsReq";
    else return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var req = session.getProtocol().clientSchema().parse(name, bytes);
    var player = session.getPlayer();
    synchronized (player) {
      boolean valid = req.getUnknownFields().asMap().isEmpty();
      if (name.equals("EnterSceneCsReq")) {
        int entryId = Gameplay46.number(req, "entry_id");
        int teleport = Gameplay46.number(req, "teleport_id");
        var entry = GameData.getMapEntranceExcelMap().get(entryId);
        valid &=
            Gameplay46.only(
                req, "entry_id", "teleport_id", "interact_id", "is_close_map", "scene_identifier");
        var field = req.getDescriptorForType().findFieldByName("scene_identifier");
        if (req.hasField(field)) {
          var identifier = (DynamicMessage) req.getField(field);
          valid &=
              identifier.getUnknownFields().asMap().isEmpty()
                  && Gameplay46.only(identifier, "floor_id")
                  && entry != null
                  && Gameplay46.number(identifier, "floor_id") == entry.getFloorID();
        }
        var plane = entry == null ? null : GameData.getMazePlaneExcelMap().get(entry.getPlaneID());
        var result =
            !valid
                ? Retcode.REQ_PARA_INVALID
                : entry == null || plane == null
                    ? Retcode.SCENE_ENTRY_ID_NOT_MATCH
                    : player.getBattle() != null
                            || player.getGameplay().getCurrentMissionRaid() != 0
                            || player.getGameplay().getParkourVenue().inside()
                        ? Retcode.SYSTEM_BUSY
                        : switch (plane.getPlaneType()) {
                          case Town, Maze, Train -> Retcode.SUCC;
                          default -> Retcode.PLANE_TYPE_NOT_ALLOW;
                        };
        // A recovered transition is already applied; the main mission may still be unfinished.
        boolean recovered = result == Retcode.SUCC
            && MissionSceneEntrances.acknowledgesRecovery(session, entry, teleport);
        if (result == Retcode.SUCC
            && (!Arrays.stream(entry.getFinishMainMissionList())
                    .allMatch(
                        id ->
                            player.getMissionManager().finished(id)
                                && !player.getGameplay().getCancelledMissions().contains(id))
                || !Arrays.stream(entry.getFinishSubMissionList())
                    .allMatch(id -> StoryLineConditions.finished(player, id)))
            && !MissionSceneEntrances.allows(player, entry, teleport)
            && !recovered)
          result = Retcode.MISSION_NOT_FINISH;
        if (result == Retcode.SUCC && !recovered
            && !MissionSceneEntrances.enter(player, entryId, teleport, player.isOnline()))
          result = Retcode.SCENE_ENTRY_ID_NOT_MATCH;
        var response = Gameplay46.builder(session, "EnterSceneScRsp");
        Gameplay46.set(response, "retcode", result.getVal());
        if (result == Retcode.SUCC) {
          var identifier = Gameplay46.builder(session, "SceneIdentifier");
          Gameplay46.set(identifier, "floor_id", entry.getFloorID());
          Gameplay46.set(response, "scene_identifier", identifier.build());
          Gameplay46.set(response, "is_close_map", Gameplay46.value(req, "is_close_map"));
        }
        session.sendClientPacket("EnterSceneScRsp", response.build().toByteArray());
      } else if (name.equals("GetUnlockTeleportCsReq")) {
        var ids = (List<?>) Gameplay46.value(req, "entry_id_list");
        valid &= Gameplay46.only(req, "entry_id_list") && !ids.isEmpty() && ids.size() <= 100;
        var unlocked = new TreeSet<Integer>();
        for (var id : ids) {
          var entry = GameData.getMapEntranceExcelMap().get((int) id);
          if (entry == null
              || GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID()) == null)
            valid = false;
          else unlocked.addAll(SceneAnchors.unlocked(player, entry));
        }
        var response = Gameplay46.builder(session, "GetUnlockTeleportScRsp");
        Gameplay46.set(
            response, "retcode", (valid ? Retcode.SUCC : Retcode.REQ_PARA_INVALID).getVal());
        if (valid) for (int id : unlocked) Gameplay46.add(response, "unlocked_teleport_list", id);
        session.sendClientPacket("GetUnlockTeleportScRsp", response.build().toByteArray());
      } else {
        var identifiers = (List<?>) Gameplay46.value(req, "scene_identifiers");
        valid &=
            Gameplay46.only(req, "scene_identifiers")
                && !identifiers.isEmpty()
                && identifiers.size() <= 32;
        var floors = new TreeSet<Integer>();
        for (var row : identifiers) {
          var id = (DynamicMessage) row;
          int floor = Gameplay46.number(id, "floor_id");
          if (!id.getUnknownFields().asMap().isEmpty()
              || !Gameplay46.only(id, "floor_id")
              || floor <= 0) valid = false;
          floors.add(floor);
        }
        var response = Gameplay46.builder(session, "GetSceneMapInfoScRsp");
        for (int floor : floors) {
          var entries =
              GameData.getMapEntranceExcelMap().values().stream()
                  .filter(
                      e ->
                          e.getFloorID() == floor
                              && GameData.getFloorInfo(e.getPlaneID(), floor) != null)
                  .sorted(Comparator.comparingInt(e -> e.getId()))
                  .toList();
          if (entries.isEmpty()) {
            valid = false;
            continue;
          }
          for (var entry : entries) {
            var map = Gameplay46.builder(session, "SceneMapInfo");
            Gameplay46.set(map, "entry_id", entry.getId());
            Gameplay46.set(map, "floor_id", floor);
            if (player.getFloorId() == floor && player.getPlaneId() == entry.getPlaneID())
              Gameplay46.set(map, "cur_map_entry_id", player.getEntryId());
            var identifier = Gameplay46.builder(session, "SceneIdentifier");
            Gameplay46.set(identifier, "floor_id", floor);
            Gameplay46.set(map, "scene_identifier", identifier.build());
            var config = GameData.getFloorInfo(entry.getPlaneID(), floor);
            var values = new TreeMap<String, Integer>();
            for (var value : config.getExtraDatas())
              values.put(value.getName(), value.getDefaultValue());
            String prefix = "floor:" + floor + ":";
            player
                .getGameplay()
                .getStoryValues()
                .forEach(
                    (key, value) -> {
                      if (key.startsWith(prefix)) values.put(key.substring(prefix.length()), value);
                    });
            for (var value : values.entrySet())
              putMap(map, "floor_saved_value_map", value.getKey(), value.getValue());
            var scene = player.getScene();
            if (scene != null
                && scene.getPlaneId() == entry.getPlaneID()
                && scene.getFloorId() == floor) {
              for (var group :
                  scene.getGroups().values().stream()
                      .sorted(Comparator.comparingInt(g -> g.getId()))
                      .toList()) {
                var info = Gameplay46.builder(session, "MazeGroup");
                Gameplay46.set(info, "group_id", group.getId());
                for (var property : group.getProperties().object2IntEntrySet())
                  putMap(info, "property_map", property.getKey(), property.getIntValue());
                Gameplay46.add(map, "maze_group_list", info.build());
              }
            }
            for (var prop :
                config.getCachedTeleports().values().stream()
                    .sorted(Comparator.comparingInt(p -> p.getID()))
                    .toList()) {
              if (SceneAnchors.find(entry.getId(), prop.getGroup().getId(), prop.getID()) == null)
                continue;
              var state = SceneAnchors.state(player, entry, prop);
              if (SceneAnchors.unlocked(player, entry, prop))
                Gameplay46.add(map, "unlock_teleport_list", prop.getMappingInfoID());
              var info = Gameplay46.builder(session, "MazePropState");
              Gameplay46.set(info, "group_id", prop.getGroup().getId());
              Gameplay46.set(info, "config_id", prop.getID());
              Gameplay46.set(info, "state", state.getVal());
              Gameplay46.add(map, "maze_prop_list", info.build());
            }
            Gameplay46.add(response, "scene_map_info", map.build());
          }
        }
        if (!valid)
          response.clearField(response.getDescriptorForType().findFieldByName("scene_map_info"));
        Gameplay46.set(
            response, "retcode", (valid ? Retcode.SUCC : Retcode.REQ_PARA_INVALID).getVal());
        session.sendClientPacket("GetSceneMapInfoScRsp", response.build().toByteArray());
      }
    }
    return true;
  }

  /** Same-floor section notification; it cannot teleport, unlock a gate, or finish a mission. */
  private static void acknowledgeSection(GameSession session, byte[] bytes) throws IOException {
    boolean valid = bytes.length <= 64;
    var input = com.google.protobuf.CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    long runtime = 0;
    try {
      int tag;
      while (valid && (tag = input.readTag()) != 0) {
        int field = tag >>> 3;
        if ((tag & 7) != 0 || (field != 10 && field != 13) || !seen.add(field)) {
          valid = false;
          break;
        }
        long value = input.readUInt64();
        if (field == 10) valid = Long.compareUnsigned(value, Integer.MAX_VALUE) <= 0;
        else runtime = value;
      }
    } catch (IOException malformed) {
      valid = false;
    }
    synchronized (session.getPlayer()) {
      var scene = session.getPlayer().getScene();
      var result = !valid ? Retcode.REQ_PARA_INVALID
          : scene == null || runtime != scene.getRuntimeId()
              ? Retcode.SCENE_ENTRY_ID_NOT_MATCH : Retcode.SUCC;
      var response = Gameplay46.builder(session, "EnterSectionScRsp");
      Gameplay46.set(response, "retcode", result.getVal());
      session.sendClientPacket("EnterSectionScRsp", response.build().toByteArray());
    }
  }

  private static void putMap(DynamicMessage.Builder owner, String field, String key, int value) {
    var descriptor = owner.getDescriptorForType().findFieldByName(field);
    var entry = DynamicMessage.newBuilder(descriptor.getMessageType());
    Gameplay46.set(entry, "key", key);
    Gameplay46.set(entry, "value", value);
    owner.addRepeatedField(descriptor, entry.build());
  }

  private SceneMap46() {}
}
