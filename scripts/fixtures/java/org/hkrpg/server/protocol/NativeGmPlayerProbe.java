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
  private final List<Map<String, Object>> equipmentSyncs = new ArrayList<>();
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
    return callWire(name, request.build().toByteArray(), true);
  }

  private DynamicMessage callWire(String name, byte[] payload, boolean success) throws Exception {
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name.replace("UnlockSkillTree", "UnlockSkilltree")), new byte[0], payload));
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
        if (!((List<?>) field(sync, "equipment_list")).isEmpty()
            || !((List<?>) field(sync, "relic_list")).isEmpty()) equipmentSyncs.add(project(sync));
      }
      if (frame.command() != protocol.clientCommand(response.replace("UnlockSkillTree", "UnlockSkilltree"))) continue;
      var result = protocol.clientSchema().parse(response, frame.body());
      String retcodeField = Set.of("PromoteAvatarScRsp", "PromoteEquipmentScRsp").contains(response) ? "PLDKENJLKME" : "retcode";
      if (!result.getUnknownFields().asMap().isEmpty() || ((Integer) field(result, retcodeField) == 0) != success)
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
    String lastPullId = "";
    Map<String, Object> lastPull = null;
    String lastEquipmentId = "";
    Map<String, Object> lastEquipment = null;
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(40);
    while (!Files.exists(directory.resolve("quit")) && System.nanoTime() < deadline) {
      // Optional owned smoke control. No production endpoint or runtime hook is installed.
      Path pullRequest = directory.resolve("gacha-pull-request.json");
      if (Files.exists(pullRequest)) {
        var requested = JsonParser.parseString(Files.readString(pullRequest)).getAsJsonObject();
        String id = requested.get("requestId").getAsString();
        if (!id.equals(lastPullId)) {
          int count = requested.get("count").getAsInt();
          if (count != 1 && count != 10) throw new IllegalArgumentException("Invalid smoke pull count");
          var result = call("DoGachaCsReq", Map.of("gacha_id",1001,"gacha_num",count));
          lastPull = Map.of("requestId",id,"count",count,"response",project(result));
          lastPullId = id;
        }
      }
      Path equipmentRequest = directory.resolve("equipment-request.json");
      if (Files.exists(equipmentRequest)) {
        var requested = JsonParser.parseString(Files.readString(equipmentRequest)).getAsJsonObject();
        String id = requested.get("requestId").getAsString();
        if (!id.equals(lastEquipmentId)) {
          String command = requested.get("command").getAsString();
          if (!Set.of("ExpUpEquipmentCsReq", "ExpUpRelicCsReq", "RankUpEquipmentCsReq",
              "PromoteAvatarCsReq", "PromoteEquipmentCsReq", "UnlockSkillTreeCsReq", "LockEquipmentCsReq", "LockRelicCsReq").contains(command))
            throw new IllegalArgumentException("Unsupported owned equipment smoke request");
          equipmentSyncs.clear();
          var result = callWire(command, Base64.getDecoder().decode(requested.get("wire").getAsString()),
              requested.get("success").getAsBoolean());
          lastEquipment = Map.of("requestId", id, "response", project(result), "syncs", List.copyOf(equipmentSyncs));
          lastEquipmentId = id;
        }
      }
      call("PlayerHeartBeatCsReq", Map.of("client_time_ms", System.currentTimeMillis()));
      var snapshot = new LinkedHashMap<String, Object>();
      snapshot.put("uid", uid);
      snapshot.put("bag", project(call("GetBagCsReq")));
      snapshot.put("avatars", project(call("GetAvatarDataCsReq", Map.of("is_get_all", true))));
      snapshot.put("mail", project(call("GetMailCsReq")));
      snapshot.put("gacha", project(call("GetGachaInfoCsReq")));
      snapshot.put("basic", Map.copyOf(basic));
      snapshot.put("requests", requests);
      if (lastPull != null) snapshot.put("gachaPull",lastPull);
      if (lastEquipment != null) snapshot.put("equipmentReply", lastEquipment);
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
