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

/** Actual resource levels and inventory through synthetic KCP; no game client acceptance. */
public final class LoopbackMusicRewardProbe {
  private static final int ITEM = 270003;
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private String mode;
  private int step;
  private String previousTransaction = "";

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
    System.err.println("Music probe request=" + name + ", step=" + step);
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
    send(c, name, request(name));
  }

  private void start(Ukcp c, int level) {
    var b = request("MusicRhythmStartLevelCsReq");
    set(b, "level_id", level);
    send(c, "MusicRhythmStartLevelCsReq", b);
  }

  private void finish(Ukcp c, int rating, boolean combo) {
    var b = request("MusicRhythmFinishLevelCsReq");
    set(b, "role_star", rating);
    set(b, "score_id", 0);
    set(b, "full_combo", combo);
    send(c, "MusicRhythmFinishLevelCsReq", b);
  }

  private void levels(DynamicMessage m, int active, int first, int second, boolean combo) {
    require((Integer) field(m, "retcode") == 0, "Music data rejected");
    require((Integer) field(m, "cur_level_id") == active, "Active resource level changed");
    for (int id : List.of(101, 102)) {
      var rows =
          ((List<?>) field(m, "level_data_list"))
              .stream()
                  .map(v -> (DynamicMessage) v)
                  .filter(v -> (Integer) field(v, "level_id") == id)
                  .toList();
      require(rows.size() == 1, "Resource level unavailable " + id);
      var row = rows.getFirst();
      require(
          (Integer) field(row, "unlock_level") == (id == 101 ? first : second),
          "Rating was not saved " + id);
      require((Boolean) field(row, "full_combo") == (id == 102 && combo), "Combo changed " + id);
    }
  }

  private void done(int count, int active) {
    result.put("mode", mode);
    result.put("materialId", ITEM);
    result.put("materialCount", count);
    result.put("activeLevel", active);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated music frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "MusicRhythmDataScRsp",
                  "MusicRhythmStartLevelScRsp",
                  "MusicRhythmFinishLevelScRsp",
                  "GetBagScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      System.err.println("Music probe response=" + name + ", step=" + step);
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown music fields " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          query(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login rejected");
          query(c, "PlayerLoginFinishCsReq");
        }
        case "PlayerLoginFinishScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login finish rejected");
          step =
              switch (mode) {
                case "fresh" -> 1;
                case "resume" -> 20;
                case "restart" -> 40;
                default -> throw new IllegalArgumentException("Unknown mode");
              };
          query(c, "MusicRhythmDataCsReq");
        }
        case "MusicRhythmDataScRsp" -> {
          if (step == 1) {
            levels(m, 0, 0, 0, false);
            step = 2;
            query(c, "GetBagCsReq");
          } else if (step == 4) {
            levels(m, 101, 0, 0, false);
            step = 5;
            finish(c, 1, false);
          } else if (step == 6 || step == 9) {
            levels(m, 0, 1, 0, false);
            step++;
            query(c, "GetBagCsReq");
          } else if (step == 12) {
            levels(m, 101, 1, 0, false);
            done(1, 101);
          } else if (step == 20) {
            levels(m, 101, 1, 0, false);
            step = 21;
            query(c, "GetBagCsReq");
          } else if (step == 23) {
            levels(m, 0, 2, 0, false);
            step = 24;
            query(c, "GetBagCsReq");
          } else if (step == 26) {
            levels(m, 102, 2, 0, false);
            done(2, 102);
          } else if (step == 40) {
            levels(m, 102, 2, 0, false);
            step = 41;
            query(c, "GetBagCsReq");
          } else if (step == 43 || step == 48) {
            levels(m, 0, 2, 3, true);
            step++;
            query(c, "GetBagCsReq");
          } else throw new IllegalStateException("Unexpected music data step " + step);
        }
        case "GetBagScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Inventory query failed");
          int count =
              ((List<?>) field(m, "material_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> (Integer) field(v, "tid") == ITEM)
                      .mapToInt(v -> (Integer) field(v, "num"))
                      .sum();
          int expected =
              switch (step) {
                case 2 -> 0;
                case 7, 10, 21 -> 1;
                case 24, 41 -> 2;
                case 44, 46, 49 -> 5;
                default -> throw new IllegalStateException("Unexpected inventory step " + step);
              };
          require(count == expected, "Actual material count " + count + " != " + expected);
          if (step == 2) {
            step = 3;
            start(c, 101);
          } else if (step == 7) {
            step = 8;
            finish(c, 1, false);
          } else if (step == 10) {
            step = 11;
            start(c, 101);
          } else if (step == 21) {
            step = 22;
            finish(c, 2, false);
          } else if (step == 24) {
            step = 25;
            start(c, 102);
          } else if (step == 41) {
            step = 42;
            finish(c, 3, true);
          } else if (step == 44) {
            step = 45;
            finish(c, 3, true);
          } else if (step == 46) {
            step = 47;
            start(c, 102);
          } else if (step == 49) done(5, 0);
        }
        case "MusicRhythmStartLevelScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Start rejected");
          require(
              (Integer) field(m, "level_id") == (step >= 25 ? 102 : 101), "Start level changed");
          String transaction = (String) field(m, "trans_no");
          require(
              !transaction.isBlank() && !transaction.equals(previousTransaction),
              "Missing/reused transaction");
          previousTransaction = transaction;
          if (step == 47) {
            step = 50;
            finish(c, 3, true);
          } else {
            step++;
            query(c, "MusicRhythmDataCsReq");
          }
        }
        case "MusicRhythmFinishLevelScRsp" -> {
          boolean rejected = step == 8 || step == 45;
          require(((Integer) field(m, "retcode") != 0) == rejected, "Settlement result changed");
          require(
              (Integer) field(m, "level_id") == (rejected ? 0 : (step >= 42 ? 102 : 101)),
              "Settlement lost actual level");
          if (step == 45) {
            step = 46;
            query(c, "GetBagCsReq");
          } else if (step == 50) {
            step = 48;
            query(c, "MusicRhythmDataCsReq");
          } else {
            step++;
            query(c, "MusicRhythmDataCsReq");
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
      var probe = new LoopbackMusicRewardProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.mode = credentials.has("mode") ? credentials.get("mode").getAsString() : "fresh";
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
                    new IllegalStateException("Music probe disconnected"));
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
          "Music reward probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
