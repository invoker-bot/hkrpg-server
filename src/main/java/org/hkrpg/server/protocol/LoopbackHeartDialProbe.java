package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Real clockwork NPC/dialogue resources with explicitly isolated mission and loading gates. */
public final class LoopbackHeartDialProbe {
  private static final int MAIN = 19997101, FIRST = 199971011, SECOND = 199971012;
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private boolean missing;
  private boolean full;
  private boolean floor;
  private int step, npc;
  private final List<Integer> unlockNotifications = new ArrayList<>();
  private int repairNotifications;
  private final List<Integer> notifiedSteps = new ArrayList<>();

  private int scriptId() {
    return full ? 13113 : missing ? 20004 : 20002;
  }

  private int defaultEmotion() {
    return full ? 0 : 2;
  }

  private int itemId() {
    return full ? 140406 : 140469;
  }

  private int dialogueId(int emotion) {
    return scriptId() * 100 + emotion;
  }

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }

  private static int enumValue(DynamicMessage message, String name) {
    return ((Descriptors.EnumValueDescriptor) field(message, name)).getNumber();
  }

  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(message.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp connection, String name, DynamicMessage.Builder message) {
    System.err.println("Clockwork request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], message.build().toByteArray()));
    try {
      connection.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp connection, String name, int next) {
    step = next;
    send(connection, name, request(name));
  }

  private void change(Ukcp connection, boolean wrong) {
    var message = request("ChangeScriptEmotionCsReq");
    set(message, "script_id", scriptId());
    set(message, "BLKFCFGMMBD", wrong ? npc + 10000 : npc);
    var emotion = message.getDescriptorForType().findFieldByName("target_emotion_type");
    message.setField(emotion, emotion.getEnumType().findValueByNumber(3));
    send(connection, "ChangeScriptEmotionCsReq", message);
  }

  private void finish(Ukcp connection, int dialogue) {
    var message = request("FinishEmotionDialoguePerformanceCsReq");
    set(message, "script_id", scriptId());
    set(message, "dialogue_id", dialogue);
    set(message, "BLKFCFGMMBD", npc);
    send(connection, "FinishEmotionDialoguePerformanceCsReq", message);
  }

  private void pay(Ukcp connection, int count) {
    var message = request("SubmitEmotionItemCsReq");
    set(message, "script_id", scriptId());
    var list = request("ItemList");
    var item = request("Item");
    set(item, "item_id", itemId());
    set(item, "num", count);
    list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"), item.build());
    set(message, "item_list", list.build());
    send(connection, "SubmitEmotionItemCsReq", message);
  }

  private void inventory(DynamicMessage message) {
    int amount = 0;
    for (Object raw : (List<?>) field(message, "material_list")) {
      var item = (DynamicMessage) raw;
      if (number(item, "tid") == itemId()) amount += number(item, "num");
    }
    require(amount == 2, "Clockwork repair consumed a wrong or repeated amount");
    result.put("emotionItemCount", amount);
  }

  private void scene(DynamicMessage response) {
    require(number(response, "retcode") == 0, "Clockwork scene query rejected");
    var scene = (DynamicMessage) field(response, "scene");
    require(
        number(scene, "plane_id") == (missing && !full ? 20313 : 10301)
            && number(scene, "floor_id") == (missing && !full ? 20313001 : 10301001)
            && number(scene, "entry_id") == (missing && !full ? 2031302 : 1030101),
        "Clockwork source scene changed");
    if (floor) {
      var actors = new LinkedHashMap<Integer, Integer>();
      for (Object rawGroup : (List<?>) field(scene, "entity_group_list")) {
        var group = (DynamicMessage) rawGroup;
        int id = number(group, "group_id");
        if (id != 290 && id != 400) continue;
        for (Object rawEntity : (List<?>) field(group, "entity_list")) {
          var entity = (DynamicMessage) rawEntity;
          if (number(entity, "inst_id") != 400001) continue;
          int actor = number((DynamicMessage) field(entity, "npc"), "npc_id");
          require(actors.put(id, actor) == null, "Duplicate floor-gated NPC instance");
        }
      }
      Map<Integer, Integer> expected =
          resume || step == 45 ? Map.of(400, 13208) : step == 46 ? Map.of(290, 13117) : Map.of();
      require(actors.equals(expected), "Floor choice loaded a wrong NPC branch: " + actors);
      result.put("floorNpcBranch", actors);
      if (step == 1) result.put("defaultFloorBranchEmpty", true);
      if (step == 46) result.put("intermediateFloorBranch", actors);
    }
    npc = 0;
    boolean replacement = full && (resume || step == 45);
    int targetGroup = full ? replacement ? 162 : 131 : missing ? 94 : 47;
    int targetNpc = full ? replacement ? 13114 : 13113 : missing ? 13129 : 13151;
    for (Object rawGroup : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) rawGroup;
      if (number(group, "group_id") != targetGroup) {
        if (full && number(group, "group_id") == (replacement ? 131 : 162))
          require(
              ((List<?>) field(group, "entity_list"))
                  .stream().noneMatch(raw -> number((DynamicMessage) raw, "inst_id") == 400001),
              "Both clockwork mission and replacement NPCs are loaded");
        continue;
      }
      for (Object rawEntity : (List<?>) field(group, "entity_list")) {
        var entity = (DynamicMessage) rawEntity;
        if (number(entity, "inst_id") != (missing ? 400001 : 400005)) continue;
        var actor = (DynamicMessage) field(entity, "npc");
        require(
            number(actor, "npc_id") == targetNpc && npc == 0,
            "Clockwork source NPC changed or duplicated");
        npc = number(entity, "entity_id");
      }
    }
    require(npc > 0, "Actual clockwork NPC is absent");
    result.put(
        "npc",
        Map.of(
            "id",
            targetNpc,
            "group",
            targetGroup,
            "instance",
            missing ? 400001 : 400005,
            "entity",
            npc));
    if (full && !replacement) result.put("initialNpc", result.get("npc"));
    if (replacement) result.put("replacementNpcGateVerified", true);
  }

  private void state(DynamicMessage message, int emotion, boolean done) {
    state(message, emotion, done, 4);
  }

  private void state(DynamicMessage message, int emotion, boolean done, int expectedStep) {
    require(
        number(message, "retcode") == 0 && enumValue(message, "unlock_status") == 2,
        "Clockwork unlock query changed");
    DynamicMessage script = null;
    for (Object raw : (List<?>) field(message, "script_info_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "script_id") == scriptId()) {
        require(script == null, "Duplicate clockwork script");
        script = row;
      }
    }
    require(
        script != null
            && enumValue(script, "step") == expectedStep
            && enumValue(script, "cur_emotion_type") == emotion,
        "Authored normal step or saved emotion changed");
    boolean performed = false;
    boolean missingPerformed = false;
    boolean fullPerformed = false;
    for (Object raw : (List<?>) field(message, "dialogue_info_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "dialogue_id") == dialogueId(3))
        performed = (Boolean) field(row, "GDPHOIMONLN");
      if (number(row, "dialogue_id") == dialogueId(5))
        missingPerformed = (Boolean) field(row, "GDPHOIMONLN");
      if (number(row, "dialogue_id") == dialogueId(6))
        fullPerformed = (Boolean) field(row, "GDPHOIMONLN");
    }
    require(performed == done, "Clockwork completion flag changed");
    result.put("emotion", emotion);
    result.put("dialoguePerformed", performed);
    if (missing) {
      require(missingPerformed == (step != 2), "Missing-step performance was lost or invented");
      result.put("missingDialoguePerformed", missingPerformed);
      result.put("scriptStep", expectedStep);
      if (full) {
        require(
            fullPerformed == (step == 44 || step == 5 || step == 10 || step == 21),
            "Full-step performance was lost or invented");
        result.put("fullDialoguePerformed", fullPerformed);
      }
    }
  }

  private void missions(DynamicMessage message) {
    require(number(message, "retcode") == 0, "Clockwork mission query rejected");
    var progress = new HashMap<Integer, Integer>();
    for (Object raw : (List<?>) field(message, "mission_list")) {
      var row = (DynamicMessage) raw;
      progress.put(number(row, "id"), number(row, "progress"));
    }
    require(
        Objects.equals(progress.get(FIRST), 1) && Objects.equals(progress.get(SECOND), 0),
        "Clockwork report advanced a newly accepted target");
    require(
        !((List<?>) field(message, "finished_main_mission_id_list")).contains(MAIN),
        "Incomplete clockwork mission was marked complete");
    result.put("missionProgress", Map.of("first", 1, "second", 0));
    if (full) {
      require(
          ((List<?>) field(message, "finished_main_mission_id_list")).contains(4240301),
          "The authored Full target main mission was not completed");
      result.put("fullTargetMissionCompleted", true);
    }
    if (missing) {
      require(
          Objects.equals(progress.get(199971013), 1),
          "Repair did not advance its actual stored-step objective");
      result.put("stepMissionProgress", 1);
    }
  }

  private void done() {
    require(
        resume
            ? unlockNotifications.isEmpty()
            : !unlockNotifications.isEmpty()
                && unlockNotifications.getLast() == 2
                && new HashSet<>(unlockNotifications).size() == unlockNotifications.size(),
        "Clockwork unlock publication is missing or repeated");
    result.put("unlockNotifications", List.copyOf(unlockNotifications));
    if (missing)
      require(
          repairNotifications == (resume ? 0 : full ? 2 : 1),
          "Repair notification was lost or repeated");
    result.put("repairNotifications", repairNotifications);
    if (full) {
      require(
          notifiedSteps.equals(resume ? List.of() : List.of(1, 4)),
          "Full steps were skipped or republished");
      result.put("notifiedSteps", List.copyOf(notifiedSteps));
    }
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    result.put("restored", resume);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp connection) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated clockwork frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "AcceptMainMissionScRsp",
                  "GetCurSceneInfoScRsp",
                  "GetHeartDialInfoScRsp",
                  "ChangeScriptEmotionScRsp",
                  "FinishEmotionDialoguePerformanceScRsp",
                  "HeartDialScriptChangeScNotify",
                  "SubmitEmotionItemScRsp",
                  "GetBagScRsp",
                  "GetMissionDataScRsp")
              .stream()
              .filter(candidate -> protocol.clientCommand(candidate) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var message = protocol.clientSchema().parse(name, frame.body());
      require(
          message.getUnknownFields().asMap().isEmpty(),
          "Unknown clockwork response fields: " + name);
      System.err.println("Clockwork response=" + name + ", step=" + step);
      switch (name) {
        case "HeartDialScriptChangeScNotify" -> {
          int status = enumValue(message, "unlock_status");
          require(status >= 1 && status <= 2, "Unexpected fixture unlock status");
          var scripts = (List<?>) field(message, "changed_script_info_list");
          require(
              ((List<?>) field(message, "changed_dialogue_info_list")).isEmpty()
                  && ((List<?>) field(message, "EBLMNLBBFNL")).isEmpty(),
              "Unlock publication invented unrelated clockwork changes");
          if (scripts.isEmpty()) unlockNotifications.add(status);
          else {
            require(
                missing && (step == 31 || full && step == 43) && scripts.size() == 1,
                "Unexpected repair notification");
            var script = (DynamicMessage) scripts.getFirst();
            int expectedStep = full && step == 31 ? 1 : 4;
            require(
                number(script, "script_id") == scriptId()
                    && enumValue(script, "step") == expectedStep
                    && enumValue(script, "cur_emotion_type") == defaultEmotion(),
                "Repair notification has wrong script state");
            repairNotifications++;
            notifiedSteps.add(expectedStep);
          }
        }
        case "PlayerGetTokenScRsp" -> {
          require(number(message, "retcode") == 0, "Clockwork token rejected");
          result.put("uid", field(message, "uid"));
          send(connection, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require(number(message, "retcode") == 0, "Clockwork login rejected");
          send(connection, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(message, "retcode") == 0, "Clockwork login finish rejected");
          if (resume) query(connection, "GetCurSceneInfoCsReq", 20);
          else {
            var accept = request("AcceptMainMissionCsReq");
            set(accept, "main_mission_id", MAIN);
            send(connection, "AcceptMainMissionCsReq", accept);
          }
        }
        case "AcceptMainMissionScRsp" -> {
          if (full && step == 28 && number(message, "main_mission_id") == 4240301) {
            require(number(message, "retcode") == 0, "Full target fixture acceptance rejected");
            query(connection, "GetCurSceneInfoCsReq", 1);
            continue;
          }
          require(
              number(message, "retcode") == 0 && number(message, "main_mission_id") == MAIN,
              "Clockwork fixture acceptance rejected");
          if (full) {
            step = 28;
            var accept = request("AcceptMainMissionCsReq");
            set(accept, "main_mission_id", 4240301);
            send(connection, "AcceptMainMissionCsReq", accept);
          } else query(connection, "GetCurSceneInfoCsReq", 1);
        }
        case "GetCurSceneInfoScRsp" -> {
          if (step != 1 && step != 20 && step != 45 && step != 46) continue;
          scene(message);
          if (step == 46) {
            step = 41;
            change(connection, false);
            continue;
          }
          if (step == 45) {
            step = 3;
            change(connection, true);
            continue;
          }
          query(connection, "GetHeartDialInfoCsReq", step == 1 ? 2 : 21);
        }
        case "GetHeartDialInfoScRsp" -> {
          if (step == 2) {
            if (missing) {
              state(message, defaultEmotion(), false, 0);
              step = 26;
              finish(connection, dialogueId(5));
              continue;
            }
            state(message, 1, false);
            step = 3;
            change(connection, true);
          } else if (step == 5) {
            state(message, 3, false);
            step = 6;
            finish(connection, dialogueId(4));
          } else if (step == 10) {
            state(message, 3, true);
            done();
          } else if (step == 21) {
            state(message, 3, true);
            step = 22;
            finish(connection, dialogueId(3));
          } else if (step == 27) {
            state(message, defaultEmotion(), false, 0);
            step = 30;
            pay(connection, 2);
          } else if (step == 32) {
            state(message, defaultEmotion(), false, full ? 1 : 4);
            query(connection, "GetBagCsReq", 33);
          } else if (step == 44) {
            state(message, defaultEmotion(), false, 4);
            query(connection, "GetCurSceneInfoCsReq", 45);
          } else throw new IllegalStateException("Unexpected clockwork query step: " + step);
        }
        case "ChangeScriptEmotionScRsp" -> {
          System.err.println(
              "Clockwork change result="
                  + number(message, "retcode")
                  + ", script="
                  + number(message, "script_id")
                  + ", emotion="
                  + enumValue(message, "emotion_type"));
          if (step == 41) {
            require(number(message, "retcode") != 0, "Full mission gate allowed emotion changes");
            step = 42;
            finish(connection, dialogueId(3));
          } else if (step == 3) {
            require(number(message, "retcode") != 0, "Wrong clockwork NPC accepted");
            step = 4;
            change(connection, false);
          } else {
            require(
                step == 4
                    && number(message, "retcode") == 0
                    && number(message, "script_id") == scriptId()
                    && enumValue(message, "emotion_type") == 3,
                "Clockwork emotion change rejected");
            query(connection, "GetHeartDialInfoCsReq", 5);
          }
        }
        case "FinishEmotionDialoguePerformanceScRsp" -> {
          if (step == 42) {
            require(number(message, "retcode") != 0, "Full mission gate allowed a normal dialogue");
            step = 43;
            finish(connection, dialogueId(6));
          } else if (step == 43) {
            require(number(message, "retcode") == 0, "Authored Full dialogue rejected");
            query(connection, "GetHeartDialInfoCsReq", 44);
          } else if (step == 26) {
            require(
                number(message, "retcode") == 0 && number(message, "dialogue_id") == dialogueId(5),
                "Missing-step performance rejected");
            query(connection, "GetHeartDialInfoCsReq", 27);
          } else if (step == 6) {
            require(number(message, "retcode") != 0, "Wrong emotion dialogue accepted");
            step = 7;
            finish(connection, dialogueId(3));
          } else if (step == 7) {
            require(
                number(message, "retcode") == 0
                    && number(message, "script_id") == scriptId()
                    && number(message, "dialogue_id") == dialogueId(3),
                "Actual emotion dialogue rejected");
            query(connection, "GetMissionDataCsReq", 8);
          } else {
            require(
                (step == 9 || step == 22) && number(message, "retcode") != 0,
                "Repeated dialogue report accepted");
            query(
                connection,
                step == 9 ? "GetHeartDialInfoCsReq" : "GetMissionDataCsReq",
                step == 9 ? 10 : 23);
          }
        }
        case "GetMissionDataScRsp" -> {
          if (step != 8 && step != 23) continue;
          missions(message);
          if (step == 23) {
            if (missing) query(connection, "GetBagCsReq", 40);
            else done();
          } else {
            step = 9;
            finish(connection, dialogueId(3));
          }
        }
        case "SubmitEmotionItemScRsp" -> {
          require(
              missing && number(message, "script_id") == scriptId(), "Unexpected item response");
          if (step == 30) {
            require(number(message, "retcode") != 0, "Excessive item submission accepted");
            step = 31;
            pay(connection, 1);
          } else if (step == 31) {
            require(number(message, "retcode") == 0, "Actual emotion item rejected");
            query(connection, "GetHeartDialInfoCsReq", 32);
          } else {
            require(
                step == 34 && number(message, "retcode") != 0, "Repeated repair consumed an item");
            query(connection, "GetBagCsReq", 35);
          }
        }
        case "GetBagScRsp" -> {
          inventory(message);
          if (step == 33) {
            step = 34;
            pay(connection, 1);
          } else if (step == 35) {
            if (floor) {
              query(connection, "GetCurSceneInfoCsReq", 46);
              continue;
            }
            step = full ? 41 : 3;
            change(connection, !full);
          } else {
            require(step == 40, "Unexpected inventory query");
            done();
          }
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackHeartDialProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      probe.missing = credentials.has("missing") && credentials.get("missing").getAsBoolean();
      probe.full = credentials.has("full") && credentials.get("full").getAsBoolean();
      probe.floor = credentials.has("floor") && credentials.get("floor").getAsBoolean();
      require(!probe.floor || probe.full, "Floor gate probe requires the Full mission fixture");
      probe.missing |= probe.full;
      var token = probe.request("PlayerGetTokenCsReq");
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
            public void onConnected(Ukcp channel) {
              probe.send(channel, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf bytes, Ukcp channel) {
              try {
                probe.receive(bytes, channel);
              } catch (Exception failure) {
                probe.completed.completeExceptionally(failure);
              }
            }

            public void handleException(Throwable failure, Ukcp channel) {
              probe.completed.completeExceptionally(failure);
            }

            public void handleClose(Ukcp channel) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Clockwork probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception failure) {
      System.err.println(
          "Clockwork probe failed: "
              + failure.getClass().getSimpleName()
              + ": "
              + failure.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
