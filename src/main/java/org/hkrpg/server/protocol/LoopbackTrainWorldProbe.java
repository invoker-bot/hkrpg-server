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

/** Synthetic KCP checks actual train/world resources with explicitly isolated entrance gates. */
public final class LoopbackTrainWorldProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private long previousContext, context;
  private int step;
  private final Set<Integer> notified = new HashSet<>();

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

  private void enter(Ukcp c, int entry) {
    var b = request("EnterSceneCsReq");
    set(b, "entry_id", entry);
    send(c, "EnterSceneCsReq", b);
  }

  private void select(Ukcp c, long runtime, int world) {
    var b = request("SetTrainWorldIdCsReq");
    set(b, "interact_id", runtime);
    set(b, "CFDCEEMIOCO", world);
    send(c, "SetTrainWorldIdCsReq", b);
  }

  private void query(Ukcp c) {
    send(c, "GetCurSceneInfoCsReq", request("GetCurSceneInfoCsReq"));
  }

  private void finish() {
    result.put("context", context);
    result.put("world", 101);
    result.put("nativeCurrentSceneVerified", true);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated train frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "EnterSceneScRsp",
                  "GetCurSceneInfoScRsp",
                  "SetTrainWorldIdScRsp",
                  "TrainWorldIdChangeScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown train response fields: " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Train token rejected");
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Train login rejected");
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Train login finish rejected");
          if (resume) query(c);
          else {
            step = 1;
            enter(c, 2010101);
          }
        }
        case "EnterSceneScRsp" -> {
          require(number(m, "retcode") == 0, "Actual entrance rejected at step " + step);
          if (step == 1) {
            step = 2;
            enter(c, 1000001);
          } else {
            require(step == 2, "Unexpected entrance response");
            step = 0;
            query(c);
          }
        }
        case "TrainWorldIdChangeScNotify" -> notified.add(number(m, "CFDCEEMIOCO"));
        case "GetCurSceneInfoScRsp" -> {
          require(number(m, "retcode") == 0, "Train scene query rejected");
          var scene = (DynamicMessage) field(m, "scene");
          require(
              number(scene, "entry_id") == 1000001 && number(scene, "plane_id") == 10000,
              "Actual train scene missing");
          long runtime = (Long) field(scene, "interact_id");
          require(runtime != 0, "Train context missing");
          int world = number(scene, "world_id");
          if (step == 0) {
            context = runtime;
            if (resume) {
              require(world == 101, "Saved train world lost on reconnect");
              require(context != previousContext, "Old context reused");
              result.put("savedTrainWorldRestored", true);
              step = 7;
              select(c, previousContext, 201);
            } else {
              require(world == 201, "Last actual visited world changed");
              step = 3;
              select(c, context, 101);
            }
          } else if (step == 4) {
            require(
                world == 101 && runtime == context, "Selected world not reflected in actual scene");
            require(notified.contains(101), "Native world notification missing");
            result.put("nativeWorldNotification", true);
            result.put("nativeWorldSelection", true);
            step = 5;
            select(c, context ^ (1L << 32), 201);
          } else {
            require(
                step == 10 && world == 101 && runtime == context, "Restored final scene changed");
            require(
                notified.containsAll(Set.of(101, 201)),
                "Restored visited world notifications missing");
            result.put("visitedWorldsRestored", true);
            finish();
          }
        }
        case "SetTrainWorldIdScRsp" -> {
          int ret = number(m, "retcode"), world = number(m, "CFDCEEMIOCO");
          if (step == 3) {
            require(ret == 0 && world == 101, "Native train selection rejected");
            step = 4;
            query(c);
          } else if (step == 5) {
            require(ret != 0 && world == 101, "UInt64 context alias accepted");
            result.put("fullWidthContextRejected", true);
            step = 6;
            select(c, context, 501);
          } else if (step == 6) {
            require(ret != 0 && world == 101, "Unvisited resource world accepted");
            result.put("unvisitedWorldRejected", true);
            finish();
          } else if (step == 7) {
            require(ret != 0 && world == 101, "Prior connection context accepted");
            result.put("previousContextRejected", true);
            step = 8;
            select(c, context, 201);
          } else if (step == 8) {
            require(ret == 0 && world == 201, "Saved visited world missing");
            step = 9;
            select(c, context, 101);
          } else {
            require(step == 9 && ret == 0 && world == 101, "Saved train world cannot be selected");
            step = 10;
            query(c);
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
      var probe = new LoopbackTrainWorldProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      if (probe.resume) probe.previousContext = credentials.get("previousContext").getAsLong();
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
                    new IllegalStateException("Train world probe disconnected"));
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
          "Train world probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
