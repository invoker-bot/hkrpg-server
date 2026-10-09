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

/** Native classic queries/results/positions over synthetic KCP with real resource rewards. */
public final class LoopbackMatchThreeProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private final Map<Integer, Integer> baseline = new TreeMap<>();
  private String mode;
  private int step, notifications;
  private static final Set<Integer> ROSTER = Set.of(300, 301, 302, 303, 304, 305, 310, 311);

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
    int command = name.equals("PBDKDFCOAPB") ? 7411 : protocol.clientCommand(name);
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], b.build().toByteArray()));
    try {
      c.write(bytes);
    } finally {
      bytes.release();
    }
  }

  private void query(Ukcp c, String name) {
    send(c, name, request(name));
  }

  private void data(Ukcp c) {
    query(c, "PBDKDFCOAPB");
  }

  private void finish(Ukcp c, int levelMode, int power) {
    var b = request("MatchThreeLevelEndCsReq");
    set(b, "level_id", 1100);
    set(b, "KKHPKMAHDMK", levelMode);
    set(b, "HBONKLEOEEI", 300);
    set(b, "LALGADHLMCB", power);
    set(b, "uuid", levelMode == 1 ? "loopback-classic-normal" : "loopback-classic-hard");
    var f = b.getDescriptorForType().findFieldByName("GMONIMLFKFC");
    for (var e : (levelMode == 1 ? Map.of(5, 2, 10, 4) : Map.of(5, 1, 10, 3)).entrySet()) {
      var row = DynamicMessage.newBuilder(f.getMessageType());
      set(row, "key", e.getKey());
      set(row, "value", e.getValue());
      b.addRepeatedField(f, row.build());
    }
    send(c, "MatchThreeLevelEndCsReq", b);
  }

  private void position(Ukcp c, int bird, int pos) {
    var b = request("MatchThreeSetBirdPosCsReq");
    set(b, "HBONKLEOEEI", bird);
    set(b, "pos", pos);
    send(c, "MatchThreeSetBirdPosCsReq", b);
  }

  private void snapshot(DynamicMessage d, int count, int pos, int power) {
    var birds = (List<?>) field(d, "DBINCLPHEFN");
    require(birds.size() == 8, "Classic roster size changed");
    var ids = new HashSet<Integer>();
    for (var v : birds) {
      var b = (DynamicMessage) v;
      int id = number(b, "HBONKLEOEEI");
      require(ids.add(id), "Duplicate bird");
      require(number(b, "count") == (id == 300 ? count : 0), "Unexpected bird use count");
      require(number(b, "LALGADHLMCB") == (id == 300 ? power : 0), "Bird maximum changed");
      require(number(b, "pos") == (id == 301 ? pos : 0), "Saved bird position changed");
    }
    require(ids.equals(ROSTER), "Foreign resource version leaked into classic birds");
    var levels = (List<?>) field(d, "MJNNNJPDKDI");
    require(levels.size() == count, "Finished level/mode count changed");
    var modes = new HashSet<Integer>();
    for (var v : levels) {
      var l = (DynamicMessage) v;
      require(number(l, "level_id") == 1100, "Unexpected completion");
      require(modes.add(number(l, "KKHPKMAHDMK")), "Duplicate mode");
    }
    require(
        modes.equals(count == 0 ? Set.of() : count == 1 ? Set.of(1) : Set.of(1, 2)),
        "Normal and hard modes were conflated");
    var stats = new TreeMap<Integer, Integer>();
    for (var v : (List<?>) field(d, "GMONIMLFKFC")) {
      var r = (DynamicMessage) v;
      require(stats.put(number(r, "key"), number(r, "value")) == null, "Duplicate totals");
    }
    require(
        stats.equals(
            count == 0 ? Map.of() : count == 1 ? Map.of(5, 2, 10, 4) : Map.of(5, 3, 10, 7)),
        "Reported statistic totals changed");
    require(((List<?>) field(d, "AEOMEFEMBJI")).isEmpty(), "Unverified achievements were invented");
  }

  private Map<Integer, Integer> inventory(DynamicMessage m) {
    require(number(m, "retcode") == 0, "Inventory rejected");
    var found = new TreeMap<Integer, Integer>();
    for (int id : List.of(212, 232, 235)) found.put(id, 0);
    for (var v : (List<?>) field(m, "material_list")) {
      var r = (DynamicMessage) v;
      int id = number(r, "tid");
      if (found.containsKey(id)) found.put(id, number(r, "num"));
    }
    return found;
  }

  private void checkItems(Map<Integer, Integer> counts, boolean hard) {
    require(
        counts.get(212) == baseline.get(212) + 6, "Normal first reward changed or was repeated");
    require(counts.get(232) == baseline.get(232) + (hard ? 2 : 0), "Hard first reward changed");
    require(counts.get(235) == baseline.get(235) + (hard ? 20 : 0), "Hard second reward changed");
  }

  private void done(Map<Integer, Integer> counts) {
    result.put("mode", mode);
    result.put("baselineItems", baseline);
    result.put("items", counts);
    result.put("finishedModes", List.of(1, 2));
    result.put("birdUseCount", 2);
    result.put("birdMaximum", 80);
    result.put("bird301Position", 6);
    result.put("statistics", Map.of(5, 3, 10, 7));
    result.put("notifications", notifications);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated classic frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "MatchThreeGetDataDataScRsp",
                  "MatchThreeLevelEndScRsp",
                  "MatchThreeSetBirdPosScRsp",
                  "MatchThreeSyncDataScNotify",
                  "GetBagScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unexpected native fields");
      System.err.println("Classic probe response=" + name + ", step=" + step);
      if (name.equals("MatchThreeSyncDataScNotify")) {
        boolean hard = step == 11 || step == 22;
        snapshot(
            (DynamicMessage) field(m, "GJFKKFFJDAH"),
            hard ? 2 : 1,
            step <= 4 ? 0 : 6,
            hard ? 80 : 50);
        notifications++;
        continue;
      }
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Token rejected");
          result.put("uid", number(m, "uid"));
          query(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Login rejected");
          query(c, "PlayerLoginFinishCsReq");
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Login finish rejected");
          step = mode.equals("fresh") ? 1 : 20;
          data(c);
        }
        case "MatchThreeGetDataDataScRsp" -> {
          require(number(m, "retcode") == 0, "Native classic query rejected");
          var d = (DynamicMessage) field(m, "GJFKKFFJDAH");
          if (step == 1) {
            snapshot(d, 0, 0, 0);
            step = 2;
            query(c, "GetBagCsReq");
          } else if (step == 7 || step == 10) {
            snapshot(d, 1, 6, 50);
            if (step == 7) {
              step = 8;
              query(c, "GetBagCsReq");
            } else {
              step = 11;
              finish(c, 2, 80);
            }
          } else if (step == 12 || step == 20 || step == 23) {
            snapshot(d, 2, 6, 80);
            step++;
            query(c, "GetBagCsReq");
          } else throw new IllegalStateException("Unexpected classic query step " + step);
        }
        case "GetBagScRsp" -> {
          var counts = inventory(m);
          if (step == 2) {
            baseline.putAll(counts);
            step = 3;
            finish(c, 1, 50);
          } else if (step == 8) {
            checkItems(counts, false);
            step = 9;
            finish(c, 1, 51);
          } else if (step == 13 || step == 24) {
            checkItems(counts, true);
            require(
                notifications >= (mode.equals("fresh") ? 4 : 1),
                "Missing native persistence notifications");
            done(counts);
          } else if (step == 21) {
            checkItems(counts, true);
            step = 22;
            finish(c, 1, 50);
          } else throw new IllegalStateException("Unexpected inventory step " + step);
        }
        case "MatchThreeLevelEndScRsp" -> {
          require(
              (number(m, "retcode") != 0) == (step == 9), "Unexpected classic settlement outcome");
          require(
              number(m, "level_id") == 1100 && number(m, "KKHPKMAHDMK") == (step == 11 ? 2 : 1),
              "Result identity changed");
          if (step == 3) {
            step = 4;
            finish(c, 1, 50);
          } else if (step == 4) {
            step = 5;
            position(c, 301, 6);
          } else if (step == 9) {
            step = 10;
            data(c);
          } else if (step == 11) {
            step = 12;
            data(c);
          } else if (step == 22) {
            step = 23;
            data(c);
          } else throw new IllegalStateException("Unexpected result step " + step);
        }
        case "MatchThreeSetBirdPosScRsp" -> {
          require(
              (number(m, "retcode") != 0) == (step == 6), "Occupied location was not protected");
          require(
              number(m, "HBONKLEOEEI") == (step == 5 ? 301 : 300) && number(m, "pos") == 6,
              "Position response changed");
          if (step == 5) {
            step = 6;
            position(c, 300, 6);
          } else {
            step = 7;
            data(c);
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
      var probe = new LoopbackMatchThreeProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.mode = credentials.get("mode").getAsString();
      require(Set.of("fresh", "resume", "restart").contains(probe.mode), "Invalid mode");
      if (credentials.has("baselineItems"))
        credentials
            .getAsJsonObject("baselineItems")
            .asMap()
            .forEach((key, value) -> probe.baseline.put(Integer.parseInt(key), value.getAsInt()));
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

            public void handleReceive(ByteBuf b, Ukcp c) {
              try {
                probe.receive(b, c);
              } catch (Exception ex) {
                probe.completed.completeExceptionally(ex);
              }
            }

            public void handleException(Throwable ex, Ukcp c) {
              probe.completed.completeExceptionally(ex);
            }

            public void handleClose(Ukcp c) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Classic probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception ex) {
      System.err.println(
          "Classic probe failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
