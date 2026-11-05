package org.hkrpg.server.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.MissionSceneEntrances;
import org.hkrpg.server.gameplay.SceneAnchors;
import org.hkrpg.server.gameplay.StoryLineConditions;
import org.hkrpg.server.gameplay.WorldChests;

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
                  // NavMapModule indexes by SceneIdentifier. A second row for the same floor
                  // overwrites the first, potentially selecting an unrelated story entrance.
                  .sorted(Comparator.<emu.lunarcore.data.excel.MapEntranceExcel>comparingInt(
                          e -> e.getId() == player.getEntryId()
                                  && e.getPlaneID() == player.getPlaneId()
                                  && floor == player.getFloorId() ? 0 : 1)
                      .thenComparingInt(e -> e.getId()))
                  .limit(1)
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
            for (int chest : WorldChests.openedOnFloor(player, entry.getPlaneID(), floor, 0))
              Gameplay46.add(map, "opened_chest_id_list", chest);
            var groups = mapGroups(player, entry, config, values);
            for (var group : groups.entrySet()) {
              var info = Gameplay46.builder(session, "MazeGroup");
              Gameplay46.set(info, "group_id", group.getKey());
              for (var property : group.getValue().entrySet())
                putMap(info, "property_map", property.getKey(), property.getValue());
              Gameplay46.add(map, "maze_group_list", info.build());
            }
            for (var prop :
                config.getCachedTeleports().values().stream()
                    .sorted(Comparator.comparingInt(p -> p.getID()))
                    .toList()) {
              if (!groups.containsKey(prop.getGroup().getId())
                  || !prop.isLoadOnInitial()
                  || SceneAnchors.find(entry.getId(), prop.getGroup().getId(), prop.getID()) != prop)
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

  /** Map availability is metadata, not permission to create a second live scene. */
  private static SortedMap<Integer, SortedMap<String, Integer>> mapGroups(
      emu.lunarcore.game.player.Player player, emu.lunarcore.data.excel.MapEntranceExcel entry,
      FloorInfo floor, Map<String, Integer> floorValues) {
    var result = new TreeMap<Integer, SortedMap<String, Integer>>();
    var scene = player.getScene();
    if (scene != null && scene.getPlaneId() == entry.getPlaneID()
        && scene.getFloorId() == entry.getFloorID()) {
      // Scene.groups contains property/state records, not every loaded group. In particular,
      // the train's original G2 anchor has no properties and is absent from that map.
      for (int id : scene.getLoadedGroups()) {
        var properties = new TreeMap<String, Integer>();
        var group = scene.getGroups().get(id);
        if (group != null) group.getProperties().object2IntEntrySet().forEach(
            p -> properties.put(p.getKey(), p.getIntValue()));
        result.put(id, properties);
      }
      return result;
    }
    for (var prop : floor.getCachedTeleports().values()) {
      var group = prop.getGroup();
      if (!prop.isLoadOnInitial()
          || SceneAnchors.find(entry.getId(), group.getId(), prop.getID()) != prop
          || floor.getGroups(0).get(group.getId()) != group
          || !floor.isGroupInDimension(group.getId(), 0)
          || !remoteAnchorGroup(group)
          || !savedValuesMatch(group, floor, floorValues)
          || entry.getPlaneID() == 20501 && group.getId() >= 360) continue;
      // Initial ordinary anchor groups can use simple, declared floor values. Other gameplay
      // gates still require the live loader.
      var properties = new TreeMap<String, Integer>();
      if (group.getGroupPropertyMap() != null)
        for (var property : group.getGroupPropertyMap().values())
          properties.put(property.getName(), player.getGameplay().getStoryValues().getOrDefault(
              "group:" + entry.getFloorID() + ":" + group.getId() + ":" + property.getName(),
              property.getDefaultValue()));
      result.put(group.getId(), properties);
    }
    return result;
  }

  private static boolean remoteAnchorGroup(GroupInfo group) {
    return group.getLoadSide() == GroupInfo.GroupLoadSide.Server
        && (group.getCategory() == null
            || group.getCategory() == GroupInfo.GroupCategory.Normal)
        && group.isLoadOnInitial() && group.getOwnerMainMissionID() == 0
        && emptyCondition(group.getLoadCondition()) && emptyCondition(group.getUnloadCondition())
        && emptyObject(group.getAtmosphereCondition()) && emptyObject(group.getValueSource())
        && !group.getGroupName().contains("Bug") && !group.getGroupName().contains("Book")
        && !group.getGroupName().contains("AngryBlock") && !group.getGroupName().contains("ShelfDoor");
  }

  private static boolean savedValuesMatch(
      GroupInfo group, FloorInfo floor, Map<String, Integer> floorValues) {
    var raw = group.getSavedValueCondition();
    if (emptyObject(raw)) return true;
    if (!(raw instanceof JsonObject condition)
        || !Set.of("Conditions", "Operation").containsAll(condition.keySet())
        || condition.has("Operation") && !new JsonPrimitive("And").equals(condition.get("Operation"))
        || !(condition.get("Conditions") instanceof JsonArray clauses) || clauses.isEmpty())
      return false;
    for (var clause : clauses) {
      if (!(clause instanceof JsonObject row)
          || !Set.of("SavedValueName", "Operation", "Value").containsAll(row.keySet())
          || row.has("Operation") && !new JsonPrimitive("Equal").equals(row.get("Operation"))
          || !(row.get("SavedValueName") instanceof JsonPrimitive key) || !key.isString()
          || !(row.get("Value") instanceof JsonPrimitive expected) || !expected.isNumber())
        return false;
      String name = key.getAsString();
      Integer actual = floorValues.get(name);
      if (actual == null || floor.getExtraDatas().stream().filter(d -> name.equals(d.getName())).count() != 1)
        return false;
      try {
        if (actual != expected.getAsBigDecimal().intValueExact()) return false;
      } catch (ArithmeticException | NumberFormatException invalid) {
        return false;
      }
    }
    return true;
  }

  private static boolean emptyCondition(emu.lunarcore.data.config.LoadConditionInfo condition) {
    return condition == null || "And".equals(condition.getOperation())
        && condition.getConditions() != null && condition.getConditions().length == 0
        && !condition.isDelayToLevelReload();
  }

  private static boolean emptyObject(com.google.gson.JsonElement value) {
    return value == null || value.isJsonNull() || value.isJsonObject() && value.getAsJsonObject().isEmpty();
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
