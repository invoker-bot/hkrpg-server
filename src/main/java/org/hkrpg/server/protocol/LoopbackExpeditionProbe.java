package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.LoopbackPictureProbe.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/**
 * Native expedition set/take, actual bag inventory, original daily claims and persisted queries.
 */
public final class LoopbackExpeditionProbe {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  final Map<String, Object> result = new LinkedHashMap<>();
  String mode;
  int step;
  long expectedTime, lastTime;

  DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  void raw(Ukcp client, String name, byte[] body) {
    var packet =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      client.write(packet);
    } finally {
      packet.release();
    }
  }

  void send(Ukcp client, String name, DynamicMessage.Builder message) {
    raw(client, name, message.build().toByteArray());
  }

  void query(Ukcp client, String name, int next) {
    step = next;
    send(client, name, request(name));
  }

  void assign(Ukcp client, int next) {
    var message = request("AcceptMultipleExpeditionCsReq");
    for (int id : List.of(1001, 1002)) {
      var row = request("ExpeditionInfo");
      set(row, "id", id);
      message.addRepeatedField(
          message.getDescriptorForType().findFieldByName("expedition"), row.build());
    }
    step = next;
    send(client, "AcceptMultipleExpeditionCsReq", message);
  }

  void claim(Ukcp client, int id, int next) {
    var message = request("TakeQuestRewardCsReq");
    message.addRepeatedField(message.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), id);
    step = next;
    send(client, "TakeQuestRewardCsReq", message);
  }

  List<Integer> ids(DynamicMessage message, String name) {
    return ((List<?>) field(message, name))
        .stream().map(r -> number((DynamicMessage) r, "id")).toList();
  }

  void quests(DynamicMessage message, int status, int progress) {
    int found = 0;
    for (var value : (List<?>) field(message, "quest_list")) {
      var row = (DynamicMessage) value;
      int id = number(row, "id");
      if (!Set.of(2100002, 2100133).contains(id)) continue;
      int phase =
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(row, "status")).getNumber();
      require(
          phase == status && number(row, "progress") == progress,
          "Original expedition daily changed: " + id);
      found++;
      result.put("quest" + id + "Status", phase);
      result.put("quest" + id + "Progress", progress);
    }
    require(found == 2, "Missing original expedition dailies");
  }

  void points(DynamicMessage message, int expected) {
    require(number(message, "daily_active_point") == expected, "Expedition daily points changed");
    result.put("dailyPoints", expected);
  }

  void finish() {
    result.put("passed", true);
    result.put("mode", mode);
    result.put("rewardTime", lastTime);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  void receive(ByteBuf bytes, Ukcp client) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated expedition packet");
      var name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetExpeditionDataScRsp",
                  "AcceptMultipleExpeditionScRsp",
                  "TakeMultipleExpeditionRewardScRsp",
                  "GetDailyActiveInfoScRsp",
                  "GetQuestDataScRsp",
                  "TakeQuestRewardScRsp",
                  "GetBagScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      if (name.equals("GetQuestDataScRsp") && !Set.of(2, 5, 11, 21).contains(step)) continue;
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown expedition reply fields");
      int expected =
          name.equals("TakeMultipleExpeditionRewardScRsp") && step == 16
              ? Retcode.EXPEDITION_NOT_FINISH.getVal()
              : name.equals("AcceptMultipleExpeditionScRsp") && step == 3
                  ? Retcode.REQ_PARA_INVALID.getVal()
                  : name.equals("TakeQuestRewardScRsp") && step == 10
                      ? Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()
                      : 0;
      require(
          number(message, "retcode") == expected,
          "Expedition reply " + name + " step=" + step + " ret=" + number(message, "retcode"));
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(message, "uid"));
          send(client, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" ->
            send(client, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" -> query(client, "GetExpeditionDataCsReq", 1);
        case "GetExpeditionDataScRsp" -> {
          require(
              number(message, "total_expedition_count") == 2,
              "Unfinished team gates became unlocked");
          var unlocked = (List<?>) field(message, "CAALNNFJIAH");
          lastTime = (Long) field(message, "BJMGDBCHEAN");
          result.put("active", ids(message, "expedition_info"));
          result.put("unlocked", unlocked);
          result.put("teamCount", 2);
          if (mode.equals("bootstrap")) {
            require(
                unlocked.isEmpty() && ids(message, "expedition_info").isEmpty() && lastTime == 0,
                "Locked query created expedition state");
            result.put("nativeMissionGatePreserved", true);
            query(client, "GetQuestDataCsReq", 2);
          } else {
            require(
                unlocked.equals(List.of(1001, 1002, 2001, 3001, 4001)),
                "Original mission-gated harvest list changed: " + unlocked);
            if (mode.equals("dispatch") && step == 1) {
              require(
                  ids(message, "expedition_info").isEmpty() && lastTime == 0,
                  "Dispatch fixture not fresh");
              step = 3;
              raw(client, "AcceptMultipleExpeditionCsReq", new byte[] {82, 0});
            } else {
              require(
                  ids(message, "expedition_info").equals(List.of(1001, 1002))
                      && lastTime > 0
                      && lastTime % 3600 == 0,
                  "Saved active assignments changed");
              if (expectedTime > 0)
                require(lastTime == expectedTime, "Saved hourly anchor changed");
              if (mode.equals("harvest") && step == 1)
                query(client, "TakeMultipleExpeditionRewardCsReq", 15);
              else if (mode.equals("resume")) query(client, "GetBagCsReq", 20);
              else query(client, "GetDailyActiveInfoCsReq", 14);
            }
          }
        }
        case "AcceptMultipleExpeditionScRsp" -> {
          require(
              ((List<?>) field(message, "DJOBNPIAEJP")).isEmpty(),
              "Immediate or replayed set generated harvest");
          if (step == 3) {
            require(
                ids(message, "accept_multi_expedition").isEmpty(),
                "Malformed dispatch changed state");
            result.put("nativeMalformedSetRejected", true);
            assign(client, 4);
          } else if (step == 4) {
            require(
                ids(message, "accept_multi_expedition").equals(List.of(1001, 1002)),
                "Actual dispatch lost source IDs");
            lastTime = (Long) field(message, "BJMGDBCHEAN");
            require(lastTime > 0 && lastTime % 3600 == 0, "Native hourly anchor missing");
            result.put("nativeActualDispatch", true);
            query(client, "GetQuestDataCsReq", 5);
          } else if (step == 12) {
            require(
                (Long) field(message, "BJMGDBCHEAN") == lastTime,
                "Duplicate dispatch reset anchor");
            result.put("nativeRepeatedSetNoResetOrRewards", true);
            query(client, "GetExpeditionDataCsReq", 13);
          } else throw new IllegalStateException("Unexpected dispatch reply");
        }
        case "GetQuestDataScRsp" -> {
          if (step == 2) {
            quests(message, 1, 0);
            result.put("queryDoesNotCompleteDaily", true);
            finish();
          } else if (step == 5) {
            quests(message, 2, 1);
            result.put("nativeBothOriginalDispatchDailiesFinished", true);
            query(client, "GetDailyActiveInfoCsReq", 6);
          } else if (step == 11) {
            quests(message, 3, 1);
            assign(client, 12);
          } else if (step == 21) {
            quests(message, 3, 1);
            result.put("savedDailyClaimsRestored", true);
            query(client, "GetDailyActiveInfoCsReq", 22);
          }
        }
        case "GetDailyActiveInfoScRsp" -> {
          if (step == 6) {
            points(message, 0);
            result.put("dispatchDoesNotAutomaticallyGrantPoints", true);
            claim(client, 2100002, 7);
          } else if (step == 8) {
            points(message, 100);
            result.put("firstOriginalHundredPoints", true);
            claim(client, 2100133, 9);
          } else if (step == 22 || step == 14) {
            points(message, 200);
            finish();
          }
        }
        case "TakeQuestRewardScRsp" -> {
          int id = step == 7 ? 2100002 : step == 9 ? 2100133 : 2100002;
          require(
              ((List<?>) field(message, "succ_quest_id_list"))
                  .equals(step == 10 ? List.of() : List.of(id)),
              "Expedition claim success IDs changed");
          require(
              ((List<?>) field((DynamicMessage) field(message, "reward"), "item_list")).isEmpty(),
              "Virtual daily points became bag items");
          if (step == 7) query(client, "GetDailyActiveInfoCsReq", 8);
          else if (step == 9) {
            result.put("secondOriginalHundredPoints", true);
            claim(client, 2100002, 10);
          } else {
            result.put("duplicateDailyClaimRejected", true);
            query(client, "GetQuestDataCsReq", 11);
          }
        }
        case "TakeMultipleExpeditionRewardScRsp" -> {
          var groups = (List<?>) field(message, "DJOBNPIAEJP");
          if (step == 15) {
            require(
                ((List<?>) field(message, "NPCMKBNJABA")).equals(List.of(1001, 1002))
                    && groups.size() == 2,
                "Actual harvest assignment groups missing");
            var paid = new TreeMap<Integer, Integer>();
            for (var group : groups)
              for (var value : (List<?>) field((DynamicMessage) group, "item_list")) {
                var item = (DynamicMessage) value;
                int id = number(item, "item_id");
                require(paid.put(id, number(item, "num")) == null, "Repeated harvest item");
              }
            require(
                paid.equals(Map.of(111001, 4, 111011, 4)),
                "Two-hour frozen original payout changed: " + paid);
            lastTime = (Long) field(message, "BJMGDBCHEAN");
            require(
                lastTime > expectedTime && lastTime % 3600 == 0, "Harvest did not advance anchor");
            result.put("nativeTwoHourOriginalHarvest", true);
            query(client, "TakeMultipleExpeditionRewardCsReq", 16);
          } else {
            require(
                groups.isEmpty() && ((List<?>) field(message, "NPCMKBNJABA")).isEmpty(),
                "Duplicate harvest paid again");
            require(
                (Long) field(message, "BJMGDBCHEAN") == lastTime,
                "Duplicate harvest changed timestamp");
            result.put("nativeDuplicateHarvestRejected", true);
            query(client, "GetBagCsReq", 20);
          }
        }
        case "GetBagScRsp" -> {
          var stocks = new TreeMap<Integer, Integer>();
          for (var value : (List<?>) field(message, "material_list")) {
            var item = (DynamicMessage) value;
            int id = number(item, "tid");
            if (Set.of(111001, 111011).contains(id)) stocks.put(id, number(item, "num"));
          }
          require(
              stocks.equals(Map.of(111001, 4, 111011, 4)),
              "Actual stored expedition materials changed: " + stocks);
          result.put("materialStocks", stocks);
          result.put("actualHarvestInventoryVerified", true);
          query(client, "GetQuestDataCsReq", 21);
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackExpeditionProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.mode = credentials.get("mode").getAsString();
      probe.expectedTime =
          credentials.has("expectedTime") ? credentials.get("expectedTime").getAsLong() : 0;
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
              } catch (Throwable e) {
                probe.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable error, Ukcp channel) {
              probe.completed.completeExceptionally(error);
            }

            public void handleClose(Ukcp channel) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Expedition probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(25, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception error) {
      System.err.println(
          "Expedition probe failed: "
              + error.getClass().getSimpleName()
              + ": "
              + error.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
