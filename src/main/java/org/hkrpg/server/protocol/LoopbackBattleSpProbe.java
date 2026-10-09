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

/** Constructed native result reports against the original 305001 event and owned 8001 actor. */
public final class LoopbackBattleSpProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private String phase = "login";
  private boolean observeOnly;
  private Integer expectedStored;
  private int battleId, stageId, outcome, notifications;
  private static final int[] EXPECTED = {4208, 2500, 2500, 4208};
  private static final String[] END = {
    "BATTLE_END_WIN", "BATTLE_END_LOSE", "BATTLE_END_QUIT", "BATTLE_END_WIN"
  };

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private void send(Ukcp connection, String name, DynamicMessage.Builder body) {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], body.build().toByteArray()));
    try {
      connection.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp connection) {
    send(connection, "GetAllLineupDataCsReq", request("GetAllLineupDataCsReq"));
  }

  private void start(Ukcp connection) {
    phase = "start";
    var body = request("SceneEnterStageCsReq");
    set(body, "event_id", 305001);
    send(connection, "SceneEnterStageCsReq", body);
  }

  private void finish(Ukcp connection, boolean invalid) {
    var body = request("PVEBattleResultCsReq");
    set(body, "battle_id", battleId);
    set(body, "stage_id", stageId);
    var end = body.getDescriptorForType().findFieldByName("end_status");
    body.setField(end, end.getEnumType().findValueByName(END[outcome]));
    var stats = request("BattleStatistics");
    var actor = request("AvatarBattleInfo");
    var property = request("AvatarProperty");
    set(actor, "id", 8001);
    set(property, "max_hp", 100.0);
    set(property, "left_hp", outcome == 1 ? 0.0 : 50.0);
    set(property, "max_sp", outcome == 1 ? 90.0 : 120.0);
    set(property, "left_sp", invalid ? 121.0 : outcome == 1 ? 22.5 : outcome == 2 ? 0.0 : 50.5);
    set(actor, "avatar_status", property.build());
    stats.addRepeatedField(
        stats.getDescriptorForType().findFieldByName("battle_avatar_list"), actor.build());
    set(body, "stt", stats.build());
    send(connection, "PVEBattleResultCsReq", body);
  }

  private int sp(DynamicMessage lineup) {
    var rows = (List<?>) field(lineup, "avatar_list");
    var actors =
        rows.stream()
            .map(v -> (DynamicMessage) v)
            .filter(v -> (Integer) field(v, "id") == 8001)
            .toList();
    require(actors.size() == 1, "Owned 8001 actor missing or duplicated");
    var bar = (DynamicMessage) field(actors.getFirst(), "sp_bar");
    require((Integer) field(bar, "max_sp") == 10000, "Stored SP scale changed");
    int current = (Integer) field(bar, "cur_sp");
    require(current >= 0 && current <= 10000, "SP outside storage range");
    return current;
  }

  private void done() {
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    result.put("constructedResultReports", true);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp connection) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated SP frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetAllLineupDataScRsp",
                  "SceneEnterStageScRsp",
                  "PVEBattleResultScRsp",
                  "GetCurBattleInfoScRsp",
                  "SyncLineupNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown SP response fields: " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require((Integer) field(message, "retcode") == 0, "Token rejected");
          result.put("uid", field(message, "uid"));
          send(connection, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require((Integer) field(message, "retcode") == 0, "Login rejected");
          send(connection, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require((Integer) field(message, "retcode") == 0, "Login finish rejected");
          phase = "initial";
          query(connection);
        }
        case "GetAllLineupDataScRsp" -> {
          require((Integer) field(message, "retcode") == 0, "Lineup query rejected");
          var lineups =
              ((List<?>) field(message, "lineup_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> field(v, "index").equals(field(message, "cur_index")))
                      .toList();
          require(lineups.size() == 1, "Current party missing");
          int stored = sp(lineups.getFirst());
          if (phase.equals("initial")) {
            result.put("initialSp", stored);
            if (expectedStored != null)
              require(stored == expectedStored, "SP did not survive reconnect/restart");
            if (observeOnly) {
              result.put("storedSp", stored);
              done();
            } else start(connection);
          } else {
            require(
                phase.equals("verify") && stored == EXPECTED[outcome], "Settlement SP mismatch");
            result.put("outcome" + outcome + "Sp", stored);
            if (++outcome == EXPECTED.length) {
              require(notifications == 3, "Wrong number of settlement SP notifications");
              result.put("notificationCount", notifications);
              result.put("storedSp", stored);
              done();
            } else start(connection);
          }
        }
        case "SceneEnterStageScRsp" -> {
          require(
              phase.equals("start") && (Integer) field(message, "retcode") == 0,
              "Stage start rejected");
          var battle = (DynamicMessage) field(message, "battle_info");
          battleId = (Integer) field(battle, "battle_id");
          stageId = (Integer) field(battle, "stage_id");
          require(stageId == 3050010 && battleId > 0, "Original event world-zero stage changed");
          var waves = (List<?>) field(battle, "monster_wave_list");
          require(waves.size() == 1, "Original wave count changed");
          var monsters = (List<?>) field((DynamicMessage) waves.getFirst(), "monster_list");
          require(
              monsters.size() == 1
                  && (Integer) field((DynamicMessage) monsters.getFirst(), "monster_id")
                      == 800204001,
              "Original monster roster changed");
          phase = outcome == 0 ? "invalid" : "settled";
          finish(connection, outcome == 0);
        }
        case "PVEBattleResultScRsp" -> {
          int code = (Integer) field(message, "retcode");
          switch (phase) {
            case "invalid" -> {
              require(code != 0, "Over-capacity report accepted");
              result.put("overCapacityRejected", true);
              phase = "active";
              send(connection, "GetCurBattleInfoCsReq", request("GetCurBattleInfoCsReq"));
            }
            case "settled" -> {
              require(code == 0, "Valid settlement rejected");
              phase = "replay";
              finish(connection, false);
            }
            case "replay" -> {
              require(code != 0, "Repeated settlement accepted");
              result.put("outcome" + outcome + "ReplayRejected", true);
              phase = "verify";
              query(connection);
            }
            default -> throw new IllegalStateException("Unexpected SP response: " + phase);
          }
        }
        case "GetCurBattleInfoScRsp" -> {
          require(
              phase.equals("active") && (Integer) field(message, "retcode") == 0,
              "Invalid report consumed active battle");
          var battle = (DynamicMessage) field(message, "battle_info");
          require(
              (Integer) field(battle, "battle_id") == battleId, "Active battle identity changed");
          result.put("invalidReportPreservedBattle", true);
          phase = "settled";
          finish(connection, false);
        }
        case "SyncLineupNotify" -> {
          if (!phase.equals("settled")) continue;
          require(
              outcome != 2 && sp((DynamicMessage) field(message, "lineup")) == EXPECTED[outcome],
              "Settlement notification SP mismatch");
          notifications++;
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackBattleSpProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.observeOnly =
          credentials.has("observeOnly") && credentials.get("observeOnly").getAsBoolean();
      probe.expectedStored =
          credentials.has("expectedSp") ? credentials.get("expectedSp").getAsInt() : null;
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
                    new IllegalStateException("SP probe disconnected"));
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
          "Battle SP probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
