package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Real 43305 scenes over KCP, with an explicitly shortened mission tail and isolated parent. */
public final class LoopbackMissionRaidProbe {
  private static final int RAID = 43305,
      MAIN = 2030002,
      PARENT = 2030001,
      FIRST = 203000201,
      SECOND = 203000202,
      TRANSFER = 203000233,
      LAST = 203000234;
  private static final Gson GSON = new Gson();
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private JsonObject credentials;
  private String phase;
  private int step, sceneTransfers, finishNotifications;
  private boolean clock;
  private int clockNpc;
  private final List<Integer> clockNotifications = new ArrayList<>();
  private Map<String, Object> home, checkpoint;

  private static Object field(DynamicMessage value, String name) {
    return value.getField(value.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage value, String name) {
    return (Integer) field(value, name);
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp channel, String name, DynamicMessage.Builder b) {
    System.err.println("Mission Raid request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp channel, String name, int next) {
    step = next;
    send(channel, name, request(name));
  }

  private void accept(Ukcp channel, int id, int next) {
    step = next;
    var b = request("AcceptMainMissionCsReq");
    set(b, "main_mission_id", id);
    send(channel, "AcceptMainMissionCsReq", b);
  }

  private void start(Ukcp channel, int next) {
    step = next;
    var b = request("StartRaidCsReq");
    set(b, "raid_id", RAID);
    send(channel, "StartRaidCsReq", b);
  }

  private void leave(Ukcp channel, int id, boolean saved, int next) {
    step = next;
    var b = request("LeaveRaidCsReq");
    set(b, "raid_id", id);
    set(b, "is_save", saved);
    send(channel, "LeaveRaidCsReq", b);
  }

  private void talk(Ukcp channel, int id, int next) {
    step = next;
    var b = request("FinishTalkMissionCsReq");
    set(b, "sub_mission_id", id);
    set(b, "talk_str", "Mission_" + id);
    send(channel, "FinishTalkMissionCsReq", b);
  }

  private void clockPay(Ukcp channel, int itemId, int next) {
    step = next;
    var b = request("SubmitEmotionItemCsReq");
    set(b, "script_id", 10108);
    var list = request("ItemList");
    var item = request("Item");
    set(item, "item_id", itemId);
    set(item, "num", 1);
    list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"), item.build());
    set(b, "item_list", list.build());
    send(channel, "SubmitEmotionItemCsReq", b);
  }

  private void clockReport(Ukcp channel, int dialogue, int next) {
    step = next;
    var b = request("FinishEmotionDialoguePerformanceCsReq");
    set(b, "script_id", 10108);
    set(b, "dialogue_id", dialogue);
    set(b, "BLKFCFGMMBD", clockNpc);
    send(channel, "FinishEmotionDialoguePerformanceCsReq", b);
  }

  private void clockNpc(DynamicMessage scene) {
    require(
        number(scene, "plane_id") == 10301 && number(scene, "floor_id") == 10301001,
        "Clockwork home is not the authored floor");
    clockNpc = 0;
    for (Object raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      if (number(group, "group_id") != 61) continue;
      for (Object entity : (List<?>) field(group, "entity_list")) {
        var row = (DynamicMessage) entity;
        if (number(row, "inst_id") != 400001) continue;
        require(
            clockNpc == 0 && number((DynamicMessage) field(row, "npc"), "npc_id") == 13107,
            "Clockwork NPC identity changed or duplicated");
        clockNpc = number(row, "entity_id");
      }
    }
    require(clockNpc > 0, "Authored clockwork NPC missing");
    result.put("clockNpc", Map.of("group", 61, "instance", 400001, "npc", 13107));
  }

  private void clockState(
      DynamicMessage message, int expectedStep, int emotion, Set<Integer> history) {
    require(number(message, "retcode") == 0, "Clockwork query failed");
    DynamicMessage script = null;
    for (Object raw : (List<?>) field(message, "script_info_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "script_id") == 10108) {
        require(script == null, "Duplicate clockwork script");
        script = row;
      }
    }
    require(
        script != null
            && ((Descriptors.EnumValueDescriptor) field(script, "step")).getNumber() == expectedStep
            && ((Descriptors.EnumValueDescriptor) field(script, "cur_emotion_type")).getNumber()
                == emotion,
        "Wrong persisted clockwork step or emotion");
    var actual = new HashSet<Integer>();
    for (Object raw : (List<?>) field(message, "dialogue_info_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "dialogue_id");
      if (id / 100 == 10108 && (Boolean) field(row, "GDPHOIMONLN")) actual.add(id);
    }
    require(actual.equals(history), "Clockwork dialogue history changed: " + actual);
    result.put("clockStep", expectedStep);
    result.put("clockHistory", new TreeSet<>(actual));
  }

  private static Map<String, Object> motion(DynamicMessage entity) {
    var m = (DynamicMessage) field(entity, "motion");
    var out = new LinkedHashMap<String, Object>();
    for (String name : List.of("pos", "rot")) {
      var point = (DynamicMessage) field(m, name);
      out.put(name, List.of(number(point, "x"), number(point, "y"), number(point, "z")));
    }
    return out;
  }

  private static Map<String, Object> place(DynamicMessage scene) {
    Map<String, Object> actorMotion = null;
    for (Object raw : (List<?>) field(scene, "entity_group_list"))
      for (Object item : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) item;
        var actor = entity.getDescriptorForType().findFieldByName("actor");
        if (entity.hasField(actor)) {
          actorMotion = motion(entity);
          break;
        }
      }
    require(actorMotion != null, "Missing formal scene actor and actual position");
    return Map.of(
        "plane",
        number(scene, "plane_id"),
        "floor",
        number(scene, "floor_id"),
        "entry",
        number(scene, "entry_id"),
        "motion",
        actorMotion);
  }

  private static void raidScene(DynamicMessage scene, int floor) {
    require(
        number(scene, "plane_id") == RAID
            && number(scene, "floor_id") == floor
            && number(scene, "entry_id") == floor,
        "Real raid scene/entry mismatch");
    place(scene);
  }

  private void home(DynamicMessage scene) {
    require(
        GSON.toJsonTree(place(scene)).equals(credentials.get("home")),
        "Exact home location or rotation lost");
  }

  private void missions(DynamicMessage message, boolean finished) {
    require(number(message, "retcode") == 0, "Mission query rejected");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (Object raw : (List<?>) field(message, "mission_list")) {
      var row = (DynamicMessage) raw;
      rows.put(number(row, "id"), row);
    }
    if (clock) {
      int expected = finished ? 1 : 0;
      for (int goal : List.of(19997993, 19997994))
        require(
            rows.containsKey(goal) && number(rows.get(goal), "progress") == expected,
            "Parent raid objective advanced early or actual completion lost: " + goal);
      result.put("parentRaidObjectives", Map.of(19997993, expected, 19997994, expected));
    }
    require(
        rows.containsKey(FIRST) && number(rows.get(FIRST), "progress") == 1,
        "First talk history lost");
    if (step != 10) {
      require(
          rows.containsKey(SECOND) && number(rows.get(SECOND), "progress") == 1,
          "Second talk history lost");
      require(
          rows.containsKey(TRANSFER) && number(rows.get(TRANSFER), "progress") == 1,
          "Transfer completed before actual move or history lost");
      require(
          rows.containsKey(LAST) && number(rows.get(LAST), "progress") == (finished ? 1 : 0),
          "Tail completed early or completion lost");
      boolean done = ((List<?>) field(message, "finished_main_mission_id_list")).contains(MAIN);
      require(done == finished, "Main mission result differs from actual tail");
    } else
      require(
          rows.containsKey(SECOND) && number(rows.get(SECOND), "progress") == 0,
          "One talk completed the next objective");
  }

  private void raidInfo(DynamicMessage message) {
    require(number(message, "retcode") == 0, "Raid query rejected");
    var rows = (List<?>) field(message, "finished_raid_info_list");
    require(rows.size() == 1, "Actual finished raid not present exactly once");
    var row = (DynamicMessage) rows.getFirst();
    require(
        number(row, "raid_id") == RAID && number(row, "world_level") == 0,
        "Verified finished raid fields changed");
  }

  private void done() {
    result.put("phase", phase);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    result.put("sceneTransfers", sceneTransfers);
    result.put("finishNotifications", finishNotifications);
    if (clock) result.put("clockNotifications", clockNotifications);
    if (home != null) result.put("home", home);
    if (checkpoint != null) result.put("checkpoint", checkpoint);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp channel) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated raid frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "AcceptMainMissionScRsp",
                  "GetCurSceneInfoScRsp",
                  "StartRaidScRsp",
                  "LeaveRaidScRsp",
                  "FinishTalkMissionScRsp",
                  "GetMissionDataScRsp",
                  "GetRaidInfoScRsp",
                  "GetSaveRaidScRsp",
                  "GetAllSaveRaidScRsp",
                  "RaidInfoNotify",
                  "GetHeartDialInfoScRsp",
                  "SubmitEmotionItemScRsp",
                  "HeartDialScriptChangeScNotify",
                  "FinishEmotionDialoguePerformanceScRsp",
                  "ChangeScriptEmotionScRsp",
                  "GetBagScRsp",
                  "EnterSceneScRsp",
                  "EnterSceneByServerScNotify")
              .stream()
              .filter(candidate -> protocol.clientCommand(candidate) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown raid response fields: " + name);
      System.err.println("Mission Raid response=" + name + ", step=" + step);
      switch (name) {
        case "HeartDialScriptChangeScNotify" -> {
          if (!clock) continue;
          for (Object raw : (List<?>) field(m, "changed_script_info_list")) {
            var script = (DynamicMessage) raw;
            if (number(script, "script_id") == 10108)
              clockNotifications.add(
                  ((Descriptors.EnumValueDescriptor) field(script, "step")).getNumber());
          }
        }
        case "GetHeartDialInfoScRsp" -> {
          require(clock, "Unexpected clockwork query");
          switch (step) {
            case 101 -> {
              clockState(m, 0, 3, Set.of());
              clockReport(channel, 1010801, 102);
            }
            case 105 -> {
              clockState(m, 2, 3, Set.of(1010801));
              require(
                  clockNotifications.equals(List.of(2)),
                  "Missing stage notification absent or duplicated");
              clockReport(channel, 1010802, 106);
            }
            case 111 -> {
              clockState(m, 2, 3, Set.of(1010801, 1010802));
              clockPay(channel, 140410, 112);
            }
            case 113 -> {
              clockState(m, 3, 3, Set.of(1010801, 1010802));
              require(
                  clockNotifications.equals(List.of(3)),
                  "Lock stage notification absent or duplicated");
              clockReport(channel, 1010803, 114);
            }
            case 125 -> {
              clockState(m, 3, 3, Set.of(1010801, 1010802, 1010803));
              query(channel, "GetMissionDataCsReq", 21);
            }
            case 115 -> {
              clockState(m, 4, 3, Set.of(1010801, 1010802, 1010803));
              require(
                  clockNotifications.equals(List.of(4)),
                  "Actual mission completion missing one Normal notification");
              query(channel, "GetCurSceneInfoCsReq", 116);
            }
            case 119, 122 -> {
              clockState(m, 4, 2, Set.of(1010801, 1010802, 1010803, 1010807));
              if (step == 122) {
                require(
                    clockNotifications.isEmpty(),
                    "Reconnect repeated a saved clockwork notification");
                clockPay(channel, 140410, 126);
              } else query(channel, "GetBagCsReq", 120);
            }
            default -> throw new IllegalStateException("Unexpected clockwork state step=" + step);
          }
        }
        case "SubmitEmotionItemScRsp" -> {
          require(clock && number(m, "script_id") == 10108, "Wrong payment response script");
          if (step == 103 || step == 126) {
            require(number(m, "retcode") != 0, "Wrong phase or duplicate payment accepted");
            if (step == 103) clockPay(channel, 140408, 104);
            else query(channel, "GetBagCsReq", 123);
          } else {
            require(
                number(m, "retcode") == 0 && (step == 104 || step == 112),
                "Clockwork payment rejected");
            query(channel, "GetHeartDialInfoCsReq", step == 104 ? 105 : 113);
          }
        }
        case "FinishEmotionDialoguePerformanceScRsp" -> {
          require(
              clock && number(m, "retcode") == 0,
              "Authored phase dialogue rejected: " + number(m, "retcode"));
          switch (step) {
            case 102 -> clockPay(channel, 140410, 103);
            case 106 -> query(channel, "GetBagCsReq", 107);
            case 114 -> start(channel, 4);
            case 118 -> query(channel, "GetHeartDialInfoCsReq", 119);
            default -> throw new IllegalStateException("Unexpected clockwork dialogue response");
          }
        }
        case "ChangeScriptEmotionScRsp" -> {
          require(
              clock && step == 117 && number(m, "retcode") == 0, "Normal emotion change rejected");
          clockReport(channel, 1010807, 118);
        }
        case "GetBagScRsp" -> {
          require(clock && number(m, "retcode") == 0, "Clockwork inventory query failed");
          var counts = new HashMap<Integer, Integer>();
          counts.put(140408, 0);
          counts.put(140410, 0);
          for (Object raw : (List<?>) field(m, "material_list")) {
            var item = (DynamicMessage) raw;
            int id = number(item, "tid");
            if (id == 140408 || id == 140410) counts.merge(id, number(item, "num"), Integer::sum);
          }
          require(
              counts.equals(Map.of(140408, 2, 140410, step == 107 ? 1 : 0)),
              "Phase debit or restored inventory changed: " + counts);
          result.put("emotionItemCounts", counts);
          if (step == 107) done();
          else if (step == 120) start(channel, 26);
          else {
            require(step == 123, "Unexpected clockwork inventory step");
            query(channel, "GetRaidInfoCsReq", 30);
          }
        }
        case "EnterSceneScRsp" -> {
          require(
              clock && step == 124 && number(m, "retcode") != 0,
              "Ordinary map teleport abandoned the raid");
          result.put("ordinaryTeleportRejected", true);
          leave(channel, RAID + 1, false, 5);
        }
        case "RaidInfoNotify" -> {
          if (number(m, "raid_id") != RAID) continue;
          int status =
              ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(m, "status"))
                  .getNumber();
          if (status == 2) {
            require(
                (Long) field(m, "raid_finish_time") > 0,
                "Finished raid missing actual finish time");
            finishNotifications++;
          }
        }
        case "EnterSceneByServerScNotify" -> {
          var scene = (DynamicMessage) field(m, "scene");
          if (number(scene, "floor_id") == 43305002) sceneTransfers++;
        }
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Raid token rejected");
          result.put("uid", field(m, "uid"));
          send(channel, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Raid login rejected");
          send(channel, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Raid login finish rejected");
          query(
              channel,
              "GetCurSceneInfoCsReq",
              phase.equals("start") ? 1 : phase.equals("clock-lock") ? 110 : 20);
        }
        case "AcceptMainMissionScRsp" -> {
          if (step == 2 || step == 16) {
            require(
                number(m, "retcode") != 0 && number(m, "main_mission_id") == MAIN,
                "Raid-only acceptance bypassed instance ownership");
            if (step == 2) accept(channel, PARENT, 3);
            else start(channel, 17);
          } else {
            require(
                step == 3 && number(m, "retcode") == 0 && number(m, "main_mission_id") == PARENT,
                "Parent fixture acceptance failed");
            if (clock) query(channel, "GetCurSceneInfoCsReq", 100);
            else start(channel, 4);
          }
        }
        case "StartRaidScRsp" -> {
          if (step == 7 || step == 26 || step == 31) {
            require(number(m, "retcode") != 0, "Repeated/finished raid entry was accepted");
            if (step == 7) talk(channel, LAST, 8);
            else if (step == 31) query(channel, "GetMissionDataCsReq", 32);
            else {
              step = 27;
              var b = request("GetSaveRaidCsReq");
              set(b, "raid_id", RAID);
              send(channel, "GetSaveRaidCsReq", b);
            }
            continue;
          }
          require(
              (step == 4 || step == 17) && number(m, "retcode") == 0,
              "Native mission raid entry rejected");
          var wrapper = (DynamicMessage) field(m, "scene");
          require(
              number(wrapper, "raid_id") == RAID && number(wrapper, "world_level") == 0,
              "Raid wrapper identity mismatch");
          var scene = (DynamicMessage) field(wrapper, "MOPHKIGKDIK");
          raidScene(scene, step == 4 ? 43305001 : 43305002);
          var team = (DynamicMessage) field(wrapper, "lineup");
          require(
              !((List<?>) field(team, "avatar_list")).isEmpty(), "Formal team lost on raid entry");
          require(
              ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                          field(team, "extra_lineup_type"))
                      .getNumber()
                  == 0,
              "Player raid replaced ordinary lineup");
          if (step == 4 && clock) {
            step = 124;
            var target = request("EnterSceneCsReq");
            set(target, "entry_id", 1030101);
            send(channel, "EnterSceneCsReq", target);
          } else if (step == 4) leave(channel, RAID + 1, false, 5);
          else {
            require(
                GSON.toJsonTree(place(scene)).equals(GSON.toJsonTree(checkpoint)),
                "Reentry lost the actual checkpoint");
            require(sceneTransfers > 0, "Authored transfer missing scene notify");
            done();
          }
        }
        case "LeaveRaidScRsp" -> {
          if (step == 5 || step == 6) {
            require(number(m, "retcode") != 0, "Wrong raid or saved exit accepted");
            if (step == 5) leave(channel, RAID, true, 6);
            else start(channel, 7);
          } else {
            require(
                (step == 14 || step == 24) && number(m, "retcode") == 0, "Raid return rejected");
            query(channel, "GetCurSceneInfoCsReq", step == 14 ? 15 : 25);
          }
        }
        case "FinishTalkMissionScRsp" -> {
          if (step == 8) {
            require(number(m, "retcode") != 0, "Unaccepted wrong-floor tail completed");
            talk(channel, FIRST, 9);
          } else {
            require(number(m, "retcode") == 0, "Actual owned talk rejected");
            if (step == 9) query(channel, "GetMissionDataCsReq", 10);
            else if (step == 11) query(channel, "GetCurSceneInfoCsReq", 12);
            else {
              require(step == 22, "Unexpected talk response");
              query(channel, "GetRaidInfoCsReq", 23);
            }
          }
        }
        case "GetCurSceneInfoScRsp" -> {
          require(number(m, "retcode") == 0, "Raid scene query rejected");
          var scene = (DynamicMessage) field(m, "scene");
          switch (step) {
            case 1 -> {
              home = place(scene);
              credentials.add("home", GSON.toJsonTree(home));
              accept(channel, MAIN, 2);
            }
            case 100, 110 -> {
              clockNpc(scene);
              if (step == 100) {
                home = place(scene);
                credentials.add("home", GSON.toJsonTree(home));
                query(channel, "GetHeartDialInfoCsReq", 101);
              } else {
                home(scene);
                query(channel, "GetHeartDialInfoCsReq", 111);
              }
            }
            case 116 -> {
              clockNpc(scene);
              home(scene);
              step = 117;
              var change = request("ChangeScriptEmotionCsReq");
              set(change, "script_id", 10108);
              set(change, "BLKFCFGMMBD", clockNpc);
              var emotion = change.getDescriptorForType().findFieldByName("target_emotion_type");
              change.setField(emotion, emotion.getEnumType().findValueByNumber(2));
              send(channel, "ChangeScriptEmotionCsReq", change);
            }
            case 12 -> {
              raidScene(scene, 43305002);
              checkpoint = place(scene);
              query(channel, "GetMissionDataCsReq", 13);
            }
            case 15 -> {
              home(scene);
              accept(channel, MAIN, 16);
            }
            case 20 -> {
              if (phase.equals("finished")) {
                home(scene);
                if (clock) {
                  clockNpc(scene);
                  query(channel, "GetHeartDialInfoCsReq", 122);
                } else query(channel, "GetRaidInfoCsReq", 30);
              } else {
                raidScene(scene, 43305002);
                require(
                    GSON.toJsonTree(place(scene)).equals(credentials.get("checkpoint")),
                    "Saved active floor/position changed");
                query(
                    channel,
                    clock ? "GetHeartDialInfoCsReq" : "GetMissionDataCsReq",
                    clock ? 125 : 21);
              }
            }
            case 25 -> {
              home(scene);
              if (clock) query(channel, "GetHeartDialInfoCsReq", 115);
              else start(channel, 26);
            }
            default -> throw new IllegalStateException("Unexpected scene query step=" + step);
          }
        }
        case "GetMissionDataScRsp" -> {
          missions(m, step == 29 || step == 32);
          switch (step) {
            case 10 -> talk(channel, SECOND, 11);
            case 13 -> leave(channel, RAID, false, 14);
            case 21 -> {
              if (phase.equals("complete")) talk(channel, LAST, 22);
              else done();
            }
            case 29, 32 -> done();
            default -> throw new IllegalStateException("Unexpected mission query");
          }
        }
        case "GetRaidInfoScRsp" -> {
          raidInfo(m);
          if (step == 23) {
            require(finishNotifications > 0, "Actual completion missing native raid finish notify");
            leave(channel, RAID, false, 24);
          } else {
            require(step == 30, "Unexpected raid query");
            start(channel, 31);
          }
        }
        case "GetSaveRaidScRsp" -> {
          require(
              step == 27
                  && number(m, "raid_id") == RAID
                  && !(Boolean) field(m, "is_save")
                  && number(m, "retcode") != 0,
              "Invented saved raid");
          query(channel, "GetAllSaveRaidCsReq", 28);
        }
        case "GetAllSaveRaidScRsp" -> {
          require(
              step == 28
                  && number(m, "retcode") == 0
                  && ((List<?>) field(m, "raid_data_list")).isEmpty(),
              "Invented saved RaidData");
          query(channel, "GetMissionDataCsReq", 29);
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp channel = null;
    int exit = 1;
    try {
      var probe = new LoopbackMissionRaidProbe();
      probe.credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.phase = probe.credentials.get("phase").getAsString();
      probe.clock = probe.credentials.has("clock") && probe.credentials.get("clock").getAsBoolean();
      require(
          Set.of("start", "inside", "complete", "finished").contains(probe.phase)
              || probe.clock && probe.phase.equals("clock-lock"),
          "Invalid probe phase");
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", probe.credentials.get("accountUid").getAsString());
      set(token, "token", probe.credentials.get("comboToken").getAsString());
      set(token, "platform", 1);
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1400);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp c) {
              probe.send(c, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf data, Ukcp c) {
              try {
                probe.receive(data, c);
              } catch (Exception failure) {
                probe.completed.completeExceptionally(failure);
              }
            }

            public void handleException(Throwable failure, Ukcp c) {
              probe.completed.completeExceptionally(failure);
            }

            public void handleClose(Ukcp c) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Mission raid probe disconnected"));
            }
          });
      channel =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (GSON.toJson(probe.completed.get(25, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception failure) {
      System.err.println(
          "Mission Raid probe failed: "
              + failure.getClass().getSimpleName()
              + ": "
              + failure.getMessage());
    } finally {
      if (channel != null) channel.close();
      client.stop();
    }
    System.exit(exit);
  }
}
