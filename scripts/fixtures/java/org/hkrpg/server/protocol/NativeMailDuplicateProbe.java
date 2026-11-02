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
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Isolated native welcome-mail claim with already-owned, fully stocked starter characters. */
public final class NativeMailDuplicateProbe {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final BlockingQueue<PacketCodec.Frame> inbox = new LinkedBlockingQueue<>();
  final AtomicReference<Throwable> failure = new AtomicReference<>();
  final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  Ukcp channel;
  int requests;

  static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }

  DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  DynamicMessage call(String name) throws Exception {
    return call(name, builder(name), 0);
  }

  DynamicMessage call(String name, DynamicMessage.Builder body, int code) throws Exception {
    byte[] header = {8, (byte) ++requests};
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), header, body.build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      if (failure.get() != null)
        throw new IllegalStateException("Connection failed", failure.get());
      var frame = inbox.poll(100, TimeUnit.MILLISECONDS);
      if (frame == null || frame.cmdId() != protocol.clientCommand(response)) continue;
      require(Arrays.equals(header, frame.header()), "Response request identity changed");
      var message = protocol.clientSchema().parse(response, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
      require(
          number(message, "retcode") == code,
          "Wrong result code: " + response + "=" + number(message, "retcode"));
      return message;
    }
    throw new TimeoutException(response);
  }

  Map<Integer, Integer> rewards(DynamicMessage message) {
    var result = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(message, "item_list")) {
      var item = (DynamicMessage) raw;
      result.merge(number(item, "item_id"), number(item, "num"), Math::addExact);
    }
    return result;
  }

  int mail(boolean claimed) throws Exception {
    var response = call("GetMailCsReq");
    var rows = (List<?>) field(response, "mail_list");
    require(rows.size() == 1, "Expected exactly the configured welcome mail");
    var row = (DynamicMessage) rows.getFirst();
    require(field(row, "title").equals("Welcome to a LunarCore server"), "Wrong welcome mail");
    require(
        rewards((DynamicMessage) field(row, "attachment"))
            .equals(claimed ? Map.of() : Map.of(2, 1000000, 101, 100, 102, 100, 1001, 1, 1002, 1)),
        "Original mail display changed");
    if (claimed) require((boolean) field(row, "is_read"), "Claimed mail is unread");
    return number(row, "id");
  }

  void bag(boolean claimed) throws Exception {
    var quantities = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "tid");
      if (Set.of(101, 102, 252, 11001, 11002).contains(id))
        quantities.merge(id, number(row, "num"), Math::addExact);
    }
    require(
        quantities.equals(
            Map.of(
                101,
                claimed ? 107 : 7,
                102,
                claimed ? 109 : 9,
                252,
                claimed ? 51 : 11,
                11001,
                6,
                11002,
                6)),
        "Actual stored mail quantities changed: " + quantities);
  }

  DynamicMessage take(int id, int code) throws Exception {
    var request = builder("TakeMailAttachmentCsReq");
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("mail_id_list"), id);
    return call("TakeMailAttachmentCsReq", request, code);
  }

  Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    token.setField(
        token.getDescriptorForType().findFieldByName("account_uid"),
        control.get("accountUid").getAsString());
    token.setField(
        token.getDescriptorForType().findFieldByName("token"),
        control.get("comboToken").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("platform"), 1);
    int uid = number(call("PlayerGetTokenCsReq", token, 0), "uid");
    var login = call("PlayerLoginCsReq");
    String mode = control.get("mode").getAsString();
    boolean claimed = mode.equals("restart");
    if (!mode.equals("initialize"))
      require(
          number((DynamicMessage) field(login, "basic_info"), "scoin")
              == (claimed ? 1001234 : 1234),
          "Persisted credit balance changed");
    call("PlayerLoginFinishCsReq");
    int id = mail(claimed);
    if (mode.equals("claim")) {
      bag(false);
      var read = builder("MarkReadMailCsReq");
      read.setField(read.getDescriptorForType().findFieldByName("id"), id);
      require(
          number(call("MarkReadMailCsReq", read, 0), "id") == id, "Read acknowledgement changed");
      var result = take(id, 0);
      require(
          field(result, "succ_mail_id_list").equals(List.of(id)),
          "Claim omitted the successful mail");
      require(
          rewards((DynamicMessage) field(result, "attachment"))
              .equals(Map.of(2, 1000000, 101, 100, 102, 100, 252, 40)),
          "Converted response amounts changed");
      bag(true);
      require(mail(true) == id, "Mail identity changed within session");
    } else require(mode.equals("initialize") || claimed, "Unknown probe mode");
    if (!mode.equals("initialize")) {
      var duplicate = take(id, Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT.getVal());
      require(
          ((List<?>) field(duplicate, "succ_mail_id_list")).isEmpty(),
          "Duplicate falsely succeeded");
      require(
          rewards((DynamicMessage) field(duplicate, "attachment")).isEmpty(),
          "Duplicate advertised rewards");
      bag(true);
    }
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "mode",
        mode,
        "nativeRequests",
        requests,
        "claimedMailId",
        id,
        "clientUIVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeMailDuplicateProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200);
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
              probe.connected.complete(channel);
            }

            public void handleReceive(ByteBuf bytes, Ukcp channel) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated mail frame");
                  probe.inbox.add(frame);
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
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
