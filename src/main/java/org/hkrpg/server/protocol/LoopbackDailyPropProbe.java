package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Original daily objectives and point rewards over UDP/KCP; no game UI acceptance. */
public final class LoopbackDailyPropProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, leader;
  private int pushedPoints = -1, pushedPropProgress = -1;
  private final Map<Integer, Integer> boxes = new HashMap<>();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
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

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c, String name, int next) {
    step = next;
    send(c, name, request(name));
  }

  private void cast(Ukcp c, int index, int next, int... targets) {
    var b = request("SceneCastSkillCsReq");
    set(b, "cast_entity_id", leader);
    set(b, "attacked_by_entity_id", leader);
    set(b, "skill_index", index);
    for (int id : targets)
      b.addRepeatedField(b.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), id);
    step = next;
    send(c, "SceneCastSkillCsReq", b);
  }

  private void scene(DynamicMessage scene, boolean consumed) {
    require(number(scene, "entry_id") == 8000103, "Wrong rest-room entry");
    leader = number(scene, "leader_entity_id");
    require(leader > 0, "No current caster");
    var found = new HashMap<Integer, Integer>();
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var v : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var e = (DynamicMessage) v;
        var f = e.getDescriptorForType().findFieldByName("prop");
        if (!e.hasField(f) || number(e, "group_id") != 22) continue;
        int inst = number(e, "inst_id");
        if (!Set.of(300012, 300013, 300015).contains(inst)) continue;
        int prop = number((DynamicMessage) e.getField(f), "prop_id");
        require(
            prop == (inst == 300012 ? 100025 : 100024), "Original recovery prop identity changed");
        found.put(inst, number(e, "entity_id"));
      }

    require(
        consumed ? found.isEmpty() : found.size() == 3,
        "Recovery box destruction/recreation state changed");
    if (!consumed) boxes.putAll(found);
    result.put(consumed ? "nativeConsumedBoxesAbsent" : "nativeOriginalBoxesPresent", true);
  }

  private void claim(Ukcp c, int id, int next) {
    var b = request("TakeQuestRewardCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), id);
    step = next;
    send(c, "TakeQuestRewardCsReq", b);
  }

  private void quests(DynamicMessage response, int propStatus, int propProgress) {
    var wanted = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(response, "quest_list")) {
      var q = (DynamicMessage) raw;
      int id = number(q, "id");
      if (Set.of(2100132, 2100144).contains(id))
        require(wanted.put(id, q) == null, "Duplicate daily quest");
    }
    require(wanted.size() == 2, "Original daily quests missing");
    var auto = wanted.get(2100132);
    int status =
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(auto, "status")).getNumber();
    require(
        status == (step == 2 && !resume ? 2 : 3) && number(auto, "progress") == 1,
        "Auto daily state changed");
    var prop = wanted.get(2100144);
    status =
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(prop, "status")).getNumber();
    require(
        status == propStatus && number(prop, "progress") == propProgress,
        "Original prop daily state changed");
    result.put("autoQuestStatus", step == 2 && !resume ? 2 : 3);
    result.put("propQuestStatus", status);
    result.put("propProgress", propProgress);
  }

  private void points(DynamicMessage response, int expected) {
    require(
        number(response, "daily_active_point") == expected, "Actual daily point balance changed");
    var ids = (List<?>) field(response, "daily_active_quest_id_list");
    require(ids.contains(2100132) && ids.contains(2100144), "Original daily pool not selected");
    result.put("dailyPoints", expected);
  }

  private void claimed(DynamicMessage response, int id, boolean accepted) {
    var successful = (List<?>) field(response, "succ_quest_id_list");
    require(
        successful.equals(accepted ? List.of(id) : List.of()),
        "Daily successful claim list changed");
    require(
        ((List<?>) field(response, "quest_id_list")).equals(List.of(id)),
        "Daily request IDs changed");
    require(
        ((List<?>) field((DynamicMessage) field(response, "reward"), "item_list")).isEmpty(),
        "Activity points became ordinary inventory rewards");
  }

  private void finish() {
    result.put("savedDailyAndPropsRestored", resume);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated daily frame");
      if (frame.cmdId() == protocol.clientCommand("DailyActiveInfoNotify")) {
        require(frame.header().length == 0, "Daily push borrowed a request identity");
        var notification = protocol.clientSchema().parse("DailyActiveInfoNotify", frame.body());
        require(notification.getUnknownFields().asMap().isEmpty(), "Unknown daily notification fields");
        pushedPoints = number(notification, "daily_active_point");
        continue;
      }
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "SceneCastSkillScRsp",
                  "GetCurSceneInfoScRsp",
                  "LeaveRogueScRsp",
                  "GetDailyActiveInfoScRsp",
                  "GetQuestDataScRsp",
                  "TakeQuestRewardScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown daily response fields");
      if (name.equals("GetQuestDataScRsp") && step == 8) {
        for (var raw : (List<?>) field(m, "quest_list")) {
          var quest = (DynamicMessage) raw;
          if (number(quest, "id") == 2100144) pushedPropProgress = number(quest, "progress");
        }
      }
      boolean duplicate =
          name.equals("TakeQuestRewardScRsp") && Set.of(5, 15, 20, 21).contains(step);
      boolean stale = name.equals("SceneCastSkillScRsp") && step == 11;
      int expected =
          duplicate
              ? emu.lunarcore.server.packet.Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()
              : stale ? emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal() : 0;
      require(
          number(m, "retcode") == expected,
          "Daily response rejected: " + name + " step=" + step + " code=" + number(m, "retcode"));
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" ->
            send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" -> query(c, "GetDailyActiveInfoCsReq", 1);
        case "GetDailyActiveInfoScRsp" -> {
          if (step == 1) {
            points(m, resume ? 300 : 0);
            query(c, "GetQuestDataCsReq", 2);
          } else if (step == 4) {
            points(m, 100);
            result.put("nativeOriginalAutoReward", true);
            claim(c, 2100132, 5);
          } else if (step == 6) {
            points(m, 100);
            var b = request("StartRogueCsReq");
            set(b, "area_id", 100);
            b.addRepeatedField(
                b.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
                emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
            step = 7;
            send(c, "StartRogueCsReq", b);
          } else if (step == 14) {
            points(m, 300);
            result.put("nativeOriginalPropReward", true);
            claim(c, 2100144, 15);
          } else if (step == 16) {
            points(m, 300);
            query(c, "GetQuestDataCsReq", 17);
          } else if (step == 22) {
            points(m, 300);
            var b = request("EnterRogueCsReq");
            set(b, "area_id", 100);
            step = 23;
            send(c, "EnterRogueCsReq", b);
          }
        }
        case "GetQuestDataScRsp" -> {
          if (step == 2) {
            quests(m, resume ? 3 : 1, resume ? 3 : 0);
            if (resume) claim(c, 2100132, 20);
            else claim(c, 2100132, 3);
          } else if (step == 9) {
            quests(m, 2, 3);
            result.put("nativeThreeDestructionsFinishedDaily", true);
            query(c, "GetCurSceneInfoCsReq", 10);
          } else if (step == 12) {
            quests(m, 2, 3);
            result.put("nativeStaleTargetsCannotCredit", true);
            claim(c, 2100144, 13);
          } else if (step == 17) {
            quests(m, 3, 3);
            query(c, "LeaveRogueCsReq", 18);
          } else if (step == 24) {
            quests(m, 3, 3);
            query(c, "LeaveRogueCsReq", 25);
          }
        }
        case "TakeQuestRewardScRsp" -> {
          if (step == 3) {
            claimed(m, 2100132, true);
            require(pushedPoints == 100, "Claimed login points did not arrive before a refresh query");
            result.put("nativeAutoPointPushBeforeQuery", true);
            query(c, "GetDailyActiveInfoCsReq", 4);
          } else if (step == 5) {
            claimed(m, 2100132, false);
            result.put("nativeAutoDuplicateRejected", true);
            query(c, "GetDailyActiveInfoCsReq", 6);
          } else if (step == 13) {
            claimed(m, 2100144, true);
            require(pushedPoints == 300, "Claimed prop points did not arrive before a refresh query");
            result.put("nativePropPointPushBeforeQuery", true);
            query(c, "GetDailyActiveInfoCsReq", 14);
          } else if (step == 15) {
            claimed(m, 2100144, false);
            result.put("nativePropDuplicateRejected", true);
            query(c, "GetDailyActiveInfoCsReq", 16);
          } else if (step == 20) {
            claimed(m, 2100132, false);
            claim(c, 2100144, 21);
          } else if (step == 21) {
            claimed(m, 2100144, false);
            result.put("nativeSavedDuplicateClaimsRejected", true);
            query(c, "GetDailyActiveInfoCsReq", 22);
          }
        }
        case "StartRogueScRsp" -> {
          require(step == 7, "Unexpected start");
          scene((DynamicMessage) field(m, "scene"), false);
          cast(c, 0, 8, boxes.get(300012), boxes.get(300013), boxes.get(300015));
        }
        case "EnterRogueScRsp" -> {
          require(step == 23, "Unexpected resume");
          scene((DynamicMessage) field(m, "scene"), true);
          query(c, "GetQuestDataCsReq", 24);
        }
        case "SceneCastSkillScRsp" -> {
          require(
              !m.hasField(m.getDescriptorForType().findFieldByName("battle_info")),
              "Props created a battle");
          if (step == 8) {
            require(pushedPropProgress == 3, "Destruction progress did not arrive before a refresh query");
            result.put("nativePropProgressPushBeforeQuery", true);
            query(c, "GetQuestDataCsReq", 9);
          }
          else {
            require(step == 11, "Unexpected cast");
            query(c, "GetQuestDataCsReq", 12);
          }
        }
        case "GetCurSceneInfoScRsp" -> {
          require(step == 10, "Unexpected scene query");
          scene((DynamicMessage) field(m, "scene"), true);
          cast(c, 0, 11, boxes.get(300012), boxes.get(300013), boxes.get(300015));
        }
        case "LeaveRogueScRsp" -> {
          require(step == 18 || step == 25, "Unexpected leave");
          finish();
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackDailyPropProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();

      var token = p.request("PlayerGetTokenCsReq");
      set(token, "account_uid", credentials.get("accountUid").getAsString());
      set(token, "token", credentials.get("comboToken").getAsString());
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
              p.send(c, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf b, Ukcp c) {
              try {
                p.receive(b, c);
              } catch (Throwable e) {
                p.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              p.completed.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              if (!p.completed.isDone())
                p.completed.completeExceptionally(
                    new IllegalStateException("Recovery probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(p.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Daily prop probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
