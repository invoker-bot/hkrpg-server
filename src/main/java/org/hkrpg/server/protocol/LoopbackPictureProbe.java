package org.hkrpg.server.protocol;

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

/** Actual empty photo reports, original daily rewards and persisted state over UDP/KCP. */
public final class LoopbackPictureProbe {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  final Map<String, Object> result = new LinkedHashMap<>();
  boolean resume;
  int step;

  static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }

  static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(message.getDescriptorForType().findFieldByName(name), value);
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

  void claim(Ukcp client, int id, int next) {
    var message = request("TakeQuestRewardCsReq");
    message.addRepeatedField(message.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), id);
    step = next;
    send(client, "TakeQuestRewardCsReq", message);
  }

  void quests(DynamicMessage message, int status, int progress) {
    var wanted = new HashMap<Integer, DynamicMessage>();
    for (var value : (List<?>) field(message, "quest_list")) {
      var row = (DynamicMessage) value;
      int id = number(row, "id");
      if (Set.of(2100129, 2100149).contains(id))
        require(wanted.put(id, row) == null, "Duplicate photo objective");
    }
    require(wanted.size() == 2, "Missing original photo objectives");
    for (int id : List.of(2100129, 2100149)) {
      var row = wanted.get(id);
      int phase =
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(row, "status")).getNumber();
      require(
          phase == status && number(row, "progress") == progress,
          "Photo objective state changed: " + id);
      result.put("quest" + id + "Status", phase);
      result.put("quest" + id + "Progress", progress);
    }
  }

  void points(DynamicMessage message, int count) {
    require(number(message, "daily_active_point") == count, "Actual photo point balance changed");
    require(
        ((List<?>) field(message, "daily_active_quest_id_list"))
            .containsAll(List.of(2100129, 2100149)),
        "Photo pool not selected");
    result.put("dailyPoints", count);
  }

  void claimed(DynamicMessage message, int id, boolean accepted) {
    require(
        ((List<?>) field(message, "succ_quest_id_list")).equals(accepted ? List.of(id) : List.of()),
        "Photo successful claim changed");
    require(
        ((List<?>) field(message, "quest_id_list")).equals(List.of(id)),
        "Photo requested claim changed");
    require(
        ((List<?>) field((DynamicMessage) field(message, "reward"), "item_list")).isEmpty(),
        "Virtual photo points became normal rewards");
  }

  void finish() {
    result.put("passed", true);
    result.put("savedPhotoDailiesRestored", resume);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  void receive(ByteBuf bytes, Ukcp client) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated photo frame");
      var name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetCurSceneInfoScRsp",
                  "GetDailyActiveInfoScRsp",
                  "GetQuestDataScRsp",
                  "TakePictureScRsp",
                  "TakeQuestRewardScRsp")
              .stream()
              .filter(value -> protocol.clientCommand(value) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      if (name.equals("GetQuestDataScRsp") && !Set.of(3, 5, 7, 9, 19, 31).contains(step)) continue;
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown photo response fields");
      boolean duplicate =
          name.equals("TakeQuestRewardScRsp") && Set.of(13, 17, 32, 33).contains(step);
      int expected =
          duplicate
              ? Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()
              : name.equals("TakePictureScRsp") && step == 4
                  ? Retcode.REQ_PARA_INVALID.getVal()
                  : 0;
      require(
          number(message, "retcode") == expected,
          "Photo response rejected: "
              + name
              + " step="
              + step
              + " code="
              + number(message, "retcode"));
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(message, "uid"));
          send(client, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" ->
            send(client, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" -> query(client, "GetCurSceneInfoCsReq", 1);
        case "GetCurSceneInfoScRsp" -> {
          require(step == 1, "Unexpected photo scene response");
          var scene = (DynamicMessage) field(message, "scene");
          require(
              number(scene, "entry_id") > 0 && number(scene, "leader_entity_id") > 0,
              "Photo has no actual current scene or actor");
          result.put("sceneEntry", number(scene, "entry_id"));
          result.put("actualSceneAndActorVerified", true);
          query(client, "GetDailyActiveInfoCsReq", 2);
        }
        case "GetDailyActiveInfoScRsp" -> {
          switch (step) {
            case 2 -> {
              points(message, resume ? 200 : 0);
              query(client, "GetQuestDataCsReq", 3);
            }
            case 10 -> {
              points(message, 0);
              result.put("nativePhotoDoesNotAutomaticallyGrantPoints", true);
              claim(client, 2100129, 11);
            }
            case 12 -> {
              points(message, 100);
              result.put("nativeFirstOriginalHundredPoints", true);
              claim(client, 2100129, 13);
            }
            case 14 -> {
              points(message, 100);
              claim(client, 2100149, 15);
            }
            case 16 -> {
              points(message, 200);
              result.put("nativeSecondOriginalHundredPoints", true);
              claim(client, 2100149, 17);
            }
            case 18 -> {
              points(message, 200);
              query(client, "GetQuestDataCsReq", 19);
            }
            case 34 -> {
              points(message, 200);
              finish();
            }
            default -> throw new IllegalStateException("Unexpected photo daily response");
          }
        }
        case "GetQuestDataScRsp" -> {
          switch (step) {
            case 3 -> {
              quests(message, resume ? 3 : 1, resume ? 1 : 0);
              result.put("nativeOriginalPhotoDailiesSelected", true);
              if (resume) query(client, "TakePictureCsReq", 30);
              else {
                step = 4;
                raw(client, "TakePictureCsReq", new byte[] {8, 1});
              }
            }
            case 5 -> {
              quests(message, 1, 0);
              result.put("nativeNonemptyPhotoRejectedWithoutProgress", true);
              query(client, "TakePictureCsReq", 6);
            }
            case 7 -> {
              quests(message, 2, 1);
              result.put("nativeAcceptedPhotoCompletesBothOriginalDailies", true);
              query(client, "TakePictureCsReq", 8);
            }
            case 9 -> {
              quests(message, 2, 1);
              result.put("nativeRepeatedPhotoDoesNotDuplicateProgress", true);
              query(client, "GetDailyActiveInfoCsReq", 10);
            }
            case 19 -> {
              quests(message, 3, 1);
              finish();
            }
            case 31 -> {
              quests(message, 3, 1);
              result.put("nativeSavedPhotoReportDoesNotReopenClaims", true);
              claim(client, 2100129, 32);
            }
            default -> throw new IllegalStateException("Unexpected photo quest response");
          }
        }
        case "TakePictureScRsp" -> {
          switch (step) {
            case 4 -> query(client, "GetQuestDataCsReq", 5);
            case 6 -> query(client, "GetQuestDataCsReq", 7);
            case 8 -> query(client, "GetQuestDataCsReq", 9);
            case 30 -> query(client, "GetQuestDataCsReq", 31);
            default -> throw new IllegalStateException("Unexpected photo acknowledgement");
          }
        }
        case "TakeQuestRewardScRsp" -> {
          switch (step) {
            case 11 -> {
              claimed(message, 2100129, true);
              query(client, "GetDailyActiveInfoCsReq", 12);
            }
            case 13 -> {
              claimed(message, 2100129, false);
              result.put("nativeFirstDuplicatePhotoClaimRejected", true);
              query(client, "GetDailyActiveInfoCsReq", 14);
            }
            case 15 -> {
              claimed(message, 2100149, true);
              query(client, "GetDailyActiveInfoCsReq", 16);
            }
            case 17 -> {
              claimed(message, 2100149, false);
              result.put("nativeSecondDuplicatePhotoClaimRejected", true);
              query(client, "GetDailyActiveInfoCsReq", 18);
            }
            case 32 -> {
              claimed(message, 2100129, false);
              claim(client, 2100149, 33);
            }
            case 33 -> {
              claimed(message, 2100149, false);
              result.put("nativeSavedDuplicatePhotoClaimsRejected", true);
              query(client, "GetDailyActiveInfoCsReq", 34);
            }
            default -> throw new IllegalStateException("Unexpected photo claim response");
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
      var probe = new LoopbackPictureProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
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
              } catch (Throwable error) {
                probe.completed.completeExceptionally(error);
              }
            }

            public void handleException(Throwable error, Ukcp channel) {
              probe.completed.completeExceptionally(error);
            }

            public void handleClose(Ukcp channel) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Photo probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception error) {
      System.err.println(
          "Photo probe failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
