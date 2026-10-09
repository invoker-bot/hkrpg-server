package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Native packet/inventory persistence probe, with an isolated accepted mission fixture. */
public final class LoopbackDicePveProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private JsonObject input;
  private int step;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void sendBytes(Ukcp c, String name, byte[] bytes) {
    System.err.println("Dice PVE request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], bytes));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    sendBytes(c, name, b.build().toByteArray());
  }

  private void query(Ukcp c, String name) {
    send(c, name, request(name));
  }

  private void finish(Ukcp c, int stage, boolean win, boolean flag) {
    var b = request("DiceCombatFinishPveStageCsReq");
    set(b, "MPDHNFNCIEA", stage);
    set(b, "is_win", win);
    set(b, "AINKFEINNAK", flag);
    set(b, "IMKHIDEBGEB", 77);
    send(c, "DiceCombatFinishPveStageCsReq", b);
  }

  private void wide(Ukcp c) throws IOException {
    var stream = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(stream);
    out.writeBool(1, true);
    out.writeUInt64(4, 0x100000000L);
    out.writeUInt32(10, 1001);
    out.flush();
    sendBytes(c, "DiceCombatFinishPveStageCsReq", stream.toByteArray());
  }

  private void system(DynamicMessage m, boolean saved) {
    require(number(m, "retcode") == 0, "System query rejected");
    require(
        field(m, "OHFIIBEIPJE").equals(saved ? List.of(1001) : List.of()),
        "Invented or lost first-win history");
    require(
        ((List<?>) field(m, "avatar_list")).isEmpty()
            && ((List<?>) field(m, "KCNEKEGGAGG")).isEmpty(),
        "PVE fabricated card/die ownership");
    if (saved) result.put("completedStages", List.of(1001));
  }

  private void mission(DynamicMessage m, int expected) {
    require(number(m, "retcode") == 0, "Mission query rejected");
    var rows =
        ((List<?>) field(m, "mission_list"))
            .stream()
                .map(v -> (DynamicMessage) v)
                .filter(v -> number(v, "id") == 105400302)
                .toList();
    require(
        rows.size() == 1 && number(rows.getFirst(), "progress") == expected,
        "Accepted source mission progress changed");
    result.put("missionProgress", expected);
  }

  private void inventory(DynamicMessage m) {
    require(number(m, "retcode") == 0, "Inventory query rejected");
    var counts = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(m, "material_list")) {
      var row = (DynamicMessage) raw;
      counts.put(number(row, "tid"), number(row, "num"));
    }
    require(
        counts.getOrDefault(264996, 0) == 1 && counts.getOrDefault(264998, 0) == 100,
        "First reward was duplicated or lost");
    result.put("coins", 1);
    result.put("dust", 100);
  }

  private void done() {
    result.put("mode", input.get("mode").getAsString());
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated frame");
      var name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetDiceCombatSystemDataScRsp",
                  "FinishPveStageScRsp",
                  "GetMissionDataScRsp",
                  "GetBagScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
      System.err.println("Dice PVE response=" + name + ", step=" + step);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          query(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Login rejected");
          query(c, "PlayerLoginFinishCsReq");
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Login finish rejected");
          step = input.get("mode").getAsString().equals("fresh") ? 1 : 20;
          query(c, "GetDiceCombatSystemDataCsReq");
        }
        case "GetDiceCombatSystemDataScRsp" -> {
          system(m, step != 1);
          if (step == 1) step = 2;
          else if (step == 9) step = 10;
          else {
            require(step == 20, "Unexpected history query");
            step = 21;
          }
          query(c, "GetMissionDataCsReq");
        }
        case "GetMissionDataScRsp" -> {
          mission(m, step == 2 ? 0 : 1);
          if (step == 2) {
            step = 3;
            finish(c, 99999, true, false);
          } else if (step == 10) {
            step = 11;
            finish(c, 1001, true, false);
          } else {
            require(step == 21, "Unexpected mission query");
            step = 22;
            finish(c, 1001, true, false);
          }
        }
        case "FinishPveStageScRsp" -> {
          if (step >= 3 && step <= 6) {
            require(
                number(m, "retcode") == Retcode.REQ_PARA_INVALID.getVal(),
                "Invalid native result accepted");
            require(
                !(Boolean) field(m, "is_win") && number(m, "MPDHNFNCIEA") == 0,
                "Error advertised a completed stage");
            if (step == 3) {
              result.put("unknownStageRejected", true);
              step = 4;
              finish(c, 1001, false, false);
            } else if (step == 4) {
              result.put("lossDidNotBecomeWin", true);
              step = 5;
              finish(c, 1001, true, true);
            } else if (step == 5) {
              result.put("unmatchedFlagRejected", true);
              step = 6;
              wide(c);
            } else {
              result.put("wideScalarRejected", true);
              step = 8;
              finish(c, 1001, true, false);
            }
          } else {
            require(
                number(m, "retcode") == 0
                    && number(m, "MPDHNFNCIEA") == 1001
                    && (Boolean) field(m, "is_win"),
                "Reported source victory rejected");
            var rewards = (List<?>) field((DynamicMessage) field(m, "reward"), "item_list");
            if (step == 8) {
              var piles = new TreeMap<Integer, Integer>();
              for (var raw : rewards) {
                var item = (DynamicMessage) raw;
                piles.put(number(item, "item_id"), number(item, "num"));
              }
              require(piles.equals(Map.of(264996, 1, 264998, 100)), "Source first reward changed");
              result.put("actualSourceFirstReward", true);
              step = 9;
              query(c, "GetDiceCombatSystemDataCsReq");
            } else {
              require(step == 11 || step == 22, "Unexpected victory retry");
              require(rewards.isEmpty(), "Retry re-granted reward");
              result.put("repeatRewardEmpty", true);
              step++;
              query(c, "GetBagCsReq");
            }
          }
        }
        case "GetBagScRsp" -> {
          require(step == 12 || step == 23, "Unexpected inventory query");
          inventory(m);
          done();
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackDicePveProbe();
      probe.input =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", probe.input.get("accountUid").getAsString());
      set(token, "token", probe.input.get("comboToken").getAsString());
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

            public void handleReceive(ByteBuf b, Ukcp c) {
              try {
                probe.receive(b, c);
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
                    new IllegalStateException("PVE probe disconnected"));
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
      System.err.println("Dice PVE probe failed: " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
