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
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** All original offering entry queries and real saved offering-six history over UDP/KCP. */
public final class NativeOfferingQueryProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, rejected;

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }

  private static int number(DynamicMessage m, String key) {
    var value = field(m, key);
    return value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e
        ? e.getNumber()
        : (Integer) value;
  }

  private static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private DynamicMessage call(String name, DynamicMessage.Builder b) throws Exception {
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      channel.write(bytes);
      requests++;
    } finally {
      bytes.release();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Connection failure", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame != null
          && frame.command() == protocol.clientCommand(name.replace("CsReq", "ScRsp"))) {
        var reply = protocol.clientSchema().parse(name.replace("CsReq", "ScRsp"), frame.body());
        require(reply.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
        return reply;
      }
    }
    throw new TimeoutException(name);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage query(List<Integer> ids) throws Exception {
    var request = builder("GetOfferingInfoCsReq");
    ids.forEach(
        id ->
            request.addRepeatedField(
                request.getDescriptorForType().findFieldByName("offering_id_list"), id));
    return call("GetOfferingInfoCsReq", request);
  }

  private List<DynamicMessage> rows(DynamicMessage reply) {
    return ((List<?>) field(reply, "offering_info_list"))
        .stream().map(DynamicMessage.class::cast).toList();
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var login = call("PlayerGetTokenCsReq", token);
    require(number(login, "retcode") == 0, "Token rejected");
    int uid = number(login, "uid");
    require(uid == control.get("uid").getAsInt(), "Restored UID differs");
    require(
        number(call("PlayerLoginCsReq"), "retcode") == 0
            && number(call("PlayerLoginFinishCsReq"), "retcode") == 0,
        "Login rejected");
    var before = call("GetBagCsReq");
    require(number(before, "retcode") == 0, "Bag query rejected");
    var ids = java.util.stream.IntStream.rangeClosed(1, 15).boxed().toList();
    var originalSix = rows(query(List.of(6))).getFirst();
    require(
        number(originalSix, "offering_level") == 50
            && number(originalSix, "total_exp") == 18800
            && number(originalSix, "level_exp") == 0,
        "Saved real offering six payment differs");
    require(
        ((List<?>) field(originalSix, "has_taken_reward_id_list")).size() == 50,
        "Saved fifty rewards differ");
    for (var order : List.of(ids, List.of(10, 6, 12, 1), ids.reversed())) {
      var response = query(order);
      require(number(response, "retcode") == 0, "Original batch query rejected: " + response);
      var entries = rows(response);
      require(
          entries.stream().map(row -> number(row, "offering_id")).toList().equals(order),
          "Batch order changed");
      for (var row : entries) {
        int id = number(row, "offering_id");
        if (id == 6) require(row.equals(originalSix), "Batch rewrote paid six history");
        else
          require(
              number(row, "offering_level") == 0
                  && number(row, "total_exp") == 0
                  && number(row, "level_exp") == 0
                  && ((List<?>) field(row, "has_taken_reward_id_list")).isEmpty(),
              "Query manufactured progress for " + id);
      }
    }
    for (var invalid : List.of(List.of(6, 16, 12), List.of(1, 6, 1), List.of(0), List.of(-1))) {
      var reply = query(invalid);
      require(
          number(reply, "retcode") == emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal()
              && rows(reply).isEmpty(),
          "Invalid batch returned partial success");
      rejected++;
    }
    require(before.equals(call("GetBagCsReq")), "Entry query changed the actual inventory");
    require(
        originalSix.equals(rows(query(List.of(6))).getFirst()),
        "Entry query changed saved offering six");
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "nativeRequests",
        requests,
        "rejectedRequests",
        rejected,
        "queriedOriginalTypes",
        15,
        "retainedPaidMaterials",
        18800,
        "retainedClaimedLevels",
        50,
        "inventoryUnchanged",
        true,
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeOfferingQueryProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
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
              probe.connected.complete(c);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable e) {
                probe.failure.set(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              probe.failure.set(e);
              probe.connected.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(control)) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      e.printStackTrace(System.err);
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
