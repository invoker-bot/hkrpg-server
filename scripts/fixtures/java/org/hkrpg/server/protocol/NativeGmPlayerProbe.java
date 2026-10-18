package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** A real isolated KCP player kept online for the GM HTTP/browser acceptance flow. */
public final class NativeGmPlayerProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final Map<String, Object> basic = new LinkedHashMap<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage message, String name) {
    return message.getField(
        Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name), name));
  }

  private static Map<String, Object> project(DynamicMessage message) {
    var result = new LinkedHashMap<String, Object>();
    message
        .getAllFields()
        .forEach((descriptor, value) -> result.put(descriptor.getName(), projectValue(value)));
    return result;
  }

  private static Object projectValue(Object value) {
    if (value instanceof DynamicMessage message) return project(message);
    if (value instanceof List<?> list)
      return list.stream().map(NativeGmPlayerProbe::projectValue).toList();
    if (value instanceof Descriptors.EnumValueDescriptor enumeration) return enumeration.getName();
    if (value instanceof ByteString bytes)
      return Base64.getEncoder().encodeToString(bytes.toByteArray());
    return value;
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var request = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) ->
            request.setField(request.getDescriptorForType().findFieldByName(key), value));
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], request.build().toByteArray()));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    requests++;
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("KCP failure", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var sync = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        basic.putAll(project((DynamicMessage) field(sync, "basic_info")));
      }
      if (frame.command() != protocol.clientCommand(response)) continue;
      var result = protocol.clientSchema().parse(response, frame.body());
      if (!result.getUnknownFields().asMap().isEmpty() || (Integer) field(result, "retcode") != 0)
        throw new IllegalStateException("Unexpected response: " + response);
      return result;
    }
    throw new IllegalStateException("Timeout: " + response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  private void run(int port, Path credentials, Path directory) throws Exception {
    var control = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                control.get("accountUid").getAsString(),
                "token",
                control.get("comboToken").getAsString(),
                "platform",
                1));
    int uid = (Integer) field(token, "uid");
    basic.putAll(project((DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info")));
    call("PlayerLoginFinishCsReq");
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(40);
    while (!Files.exists(directory.resolve("quit")) && System.nanoTime() < deadline) {
      call("PlayerHeartBeatCsReq", Map.of("client_time_ms", System.currentTimeMillis()));
      var snapshot = new LinkedHashMap<String, Object>();
      snapshot.put("uid", uid);
      snapshot.put("bag", project(call("GetBagCsReq")));
      snapshot.put("avatars", project(call("GetAvatarDataCsReq", Map.of("is_get_all", true))));
      snapshot.put("mail", project(call("GetMailCsReq")));
      snapshot.put("basic", Map.copyOf(basic));
      snapshot.put("requests", requests);
      Path temporary = directory.resolve("snapshot.tmp");
      Files.writeString(temporary, new Gson().toJson(snapshot));
      Files.move(
          temporary, directory.resolve("snapshot.json"), StandardCopyOption.REPLACE_EXISTING);
      Thread.sleep(1500);
    }
  }

  public static void main(String[] args) {
    var probe = new NativeGmPlayerProbe();
    var client = new KcpClient();
    int exit = 1;
    try {
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(40000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp channel) {
              probe.connected.complete(channel);
            }

            public void handleReceive(ByteBuf bytes, Ukcp channel) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  if (frame == null) throw new IllegalStateException("Truncated GM frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp channel) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp channel) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("GM player disconnected"));
            }
          });
      int port = Integer.parseInt(args[0]);
      client.connect(new InetSocketAddress("127.0.0.1", port), config);
      probe.channel = probe.connected.get(15, TimeUnit.SECONDS);
      probe.run(port, Path.of(args[1]), Path.of(args[2]));
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      client.stop();
    }
    System.exit(exit);
  }
}
