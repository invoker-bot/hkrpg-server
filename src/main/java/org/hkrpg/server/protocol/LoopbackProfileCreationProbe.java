package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Actual owned formal path selection; the mission is an explicitly isolated synthetic fixture. */
public final class LoopbackProfileCreationProbe {
  private static final int MAIN = 19996926, SUB = 199969261;
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
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
    System.err.println("Profile probe request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c, String name) {
    var b = request(name);
    if (name.equals("GetAvatarDataCsReq")) set(b, "is_get_all", true);
    send(c, name, b);
  }

  private void choose(Ukcp c, boolean conflict) {
    var b = request("SetPlayerInfoCsReq");
    set(b, "nickname", conflict ? "Different" : "开拓者");
    set(b, "is_modify", true);
    var gender = b.getDescriptorForType().findFieldByName("gender");
    b.setField(gender, gender.getEnumType().findValueByNumber(conflict ? 1 : 2));
    send(c, "SetPlayerInfoCsReq", b);
  }

  private void mission(DynamicMessage m, int expected, boolean finished) {
    require((Integer) field(m, "retcode") == 0, "Profile mission query failed");
    var rows =
        ((List<?>) field(m, "mission_list"))
            .stream()
                .map(v -> (DynamicMessage) v)
                .filter(row -> (Integer) field(row, "id") == SUB)
                .toList();
    require(
        rows.size() == 1 && (Integer) field(rows.getFirst(), "progress") == expected,
        "Creation goal changed");
    require(
        ((List<?>) field(m, "finished_main_mission_id_list")).contains(MAIN) == finished,
        "Creation main mission changed");
  }

  private void done() {
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated profile frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "AcceptMainMissionScRsp",
                  "GetMissionDataScRsp",
                  "SetPlayerInfoScRsp",
                  "GetBasicInfoScRsp",
                  "GetAvatarDataScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      System.err.println("Profile probe response=" + name + ", step=" + step);
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown profile fields: " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          query(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login rejected");
          if (resume)
            require(
                "开拓者".equals(field((DynamicMessage) field(m, "basic_info"), "nickname")),
                "Profile nickname was not restored");
          query(c, "PlayerLoginFinishCsReq");
        }
        case "PlayerLoginFinishScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login finish rejected");
          if (resume) {
            step = 20;
            query(c, "GetBasicInfoCsReq");
          } else {
            var b = request("AcceptMainMissionCsReq");
            set(b, "main_mission_id", MAIN);
            send(c, "AcceptMainMissionCsReq", b);
          }
        }
        case "AcceptMainMissionScRsp" -> {
          require(
              step == 0
                  && (Integer) field(m, "retcode") == 0
                  && (Integer) field(m, "main_mission_id") == MAIN,
              "Creation fixture acceptance failed");
          step = 1;
          query(c, "GetMissionDataCsReq");
        }
        case "GetMissionDataScRsp" -> {
          if (step == 1) {
            mission(m, 0, false);
            step = 2;
            query(c, "GetBasicInfoCsReq");
          } else {
            mission(m, 1, true);
            if (step == 6) {
              step = 7;
              choose(c, false);
            } else if (step == 10) {
              result.put("acceptedCreationMissionFinished", true);
              result.put("sameChoiceIdempotentConflictingChoiceRejected", true);
              done();
            } else if (step == 22) {
              step = 23;
              choose(c, true);
            } else if (step == 25) {
              result.put("savedProfileAndMissionRestored", true);
              result.put("conflictingChoiceRejectedAfterRestore", true);
              done();
            } else throw new IllegalStateException("Unexpected creation mission step " + step);
          }
        }
        case "GetBasicInfoScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Native basic profile failed");
          boolean expected = step != 2;
          require(
              (Boolean) field(m, "is_gender_set") == expected
                  && (Integer) field(m, "gender") == (expected ? 2 : 1),
              "Default or confirmed gender was misreported");
          if (step == 2) {
            result.put("defaultAvatarDidNotCompleteCreation", true);
            step = 3;
            choose(c, false);
          } else if (step == 4 || step == 20) {
            step++;
            query(c, "GetAvatarDataCsReq");
          } else if (step == 9 || step == 24) {
            step++;
            query(c, "GetMissionDataCsReq");
          } else throw new IllegalStateException("Unexpected basic profile step " + step);
        }
        case "SetPlayerInfoScRsp" -> {
          boolean failed = step == 8 || step == 23;
          require(
              (Boolean) field(m, "is_modify") && ((Integer) field(m, "retcode") != 0) == failed,
              "Creation result changed");
          require(
              ((EnumValueDescriptor) field(m, "cur_avatar_path")).getNumber() == 8002,
              "Response did not preserve actual owned path");
          if (step == 3 || step == 8 || step == 23) {
            step++;
            query(c, "GetBasicInfoCsReq");
          } else if (step == 7) {
            step = 8;
            choose(c, true);
          } else throw new IllegalStateException("Unexpected choice step " + step);
        }
        case "GetAvatarDataScRsp" -> {
          var rows =
              ((List<?>) field(m, "avatar_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(row -> (Integer) field(row, "base_avatar_id") == 8001)
                      .toList();
          require(
              (Integer) field(m, "retcode") == 0
                  && rows.size() == 1
                  && (Integer) field(rows.getFirst(), "cur_multi_path_avatar_type") == 8002,
              "Actual formal avatar path not selected/restored");
          result.put("actualFormalAvatarPath", 8002);
          result.put("gender", 2);
          step++;
          query(c, "GetMissionDataCsReq");
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackProfileCreationProbe();
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
            public void onConnected(Ukcp c) {
              probe.send(c, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                probe.receive(bytes, c);
              } catch (Exception e) {
                probe.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              probe.completed.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Profile probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Profile creation probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
