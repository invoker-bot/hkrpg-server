package org.hkrpg.server.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Isolated native stamina preference toggles, exact reply headers, and persisted readback. */
public final class NativeQuickStaminaSettingsProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<PacketCodec.Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;
  private volatile DynamicMessage stamina;

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage call(String name, DynamicMessage.Builder body) throws Exception {
    return call(name, body.build().toByteArray(), 0);
  }

  private DynamicMessage call(String name, byte[] body, int expectedCode) throws Exception {
    byte[] header = {8, (byte) ++requests};
    var request = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), header,
        body));
    try { channel.write(request); } finally { request.release(); }
    String response = name.replace("CsReq", "ScRsp");
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      if (failure.get() != null) throw new IllegalStateException("Connection failed", failure.get());
      var frame = inbox.poll(100, TimeUnit.MILLISECONDS);
      if (frame == null || frame.cmdId() != protocol.clientCommand(response)) continue;
      require(Arrays.equals(header, frame.header()), "Response lost the pending request header");
      var message = protocol.clientSchema().parse(response, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
      require(field(message, "retcode").equals(expectedCode), "Wrong result code: " + response);
      return message;
    }
    throw new TimeoutException(response);
  }

  private void toggle(boolean enabled) throws Exception {
    byte[] body = java.util.HexFormat.of().parseHex(enabled ? "0a03e01701" : "0a03e01700");
    var reply = call("UpdatePlayerSettingCsReq", body, 0);
    var setting = (DynamicMessage) field(reply, "player_setting");
    var selected = setting.getDescriptorForType().findFieldByNumber(380);
    require(setting.hasField(selected) && setting.getField(selected).equals(enabled),
        "Setting ack lost the selected true/false oneof");
  }

  private void preference(boolean expected) throws Exception {
    var reply = call("GetBasicInfoCsReq");
    var setting = (DynamicMessage) field(reply, "player_setting_info");
    require(setting.hasField(setting.getDescriptorForType().findFieldByNumber(13)),
        "Required login settings container disappeared");
    require(setting.getField(setting.getDescriptorForType().findFieldByNumber(711)).equals(expected),
        "Quick stamina preference was not restored");
  }

  private Map<String, Object> execute(com.google.gson.JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    token.setField(token.getDescriptorForType().findFieldByName("account_uid"),
        control.get("accountUid").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("token"),
        control.get("comboToken").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("platform"), 1);
    int uid = (Integer) field(call("PlayerGetTokenCsReq", token), "uid");
    var login = call("PlayerLoginCsReq");
    int initialStamina = (Integer) field(login, "stamina");
    call("PlayerLoginFinishCsReq");
    String mode = control.get("mode").getAsString();
    boolean expected = !mode.equals("fresh") && !mode.equals("disabled-restart");
    preference(expected);
    switch (mode) {
      case "fresh" -> { toggle(true); toggle(true); expected = true; preference(true); }
      case "reconnect" -> {
        toggle(false); preference(false); toggle(true); preference(true); expected = true;
      }
      case "restart" -> { toggle(false); preference(false); expected = false; }
      case "disabled-restart" -> { toggle(false); preference(false); expected = false; }
      default -> throw new IllegalStateException("Unknown probe phase");
    }
    var rejected = call("UpdatePlayerSettingCsReq",
        java.util.HexFormat.of().parseHex("0a03e01702"), 5);
    require(rejected.hasField(rejected.getDescriptorForType().findFieldByName("player_setting")),
        "Invalid settings reply omitted its callback container");
    preference(expected);
    var heartbeat = builder("PlayerHeartBeatCsReq");
    heartbeat.setField(heartbeat.getDescriptorForType().findFieldByName("client_time_ms"),
        1_793_500_000_000L);
    call("PlayerHeartBeatCsReq", heartbeat);
    // An unchanged balance need not emit a notification. Check any received notification;
    // the runner also compares actual login stamina across reconnects and H2 restarts.
    require(stamina == null || field(stamina, "stamina").equals(initialStamina)
        && field(stamina, "reserve_stamina").equals(0),
        "A preference toggle exchanged or consumed stamina");
    return Map.of("passed", true, "uid", uid, "mode", mode,
        "nativeRequests", requests, "enabledAtEnd", expected, "stamina", initialStamina,
        "balanceNotificationReceived", stamina != null, "correlationHeadersVerified", true,
        "invalidRequestRepliedAndSessionAlive", true, "clientUIVerified", false);
  }

  public static void main(String[] args) {
    var probe = new NativeQuickStaminaSettingsProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
          .getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp channel) { probe.connected.complete(channel); }
        public void handleReceive(ByteBuf bytes, Ukcp channel) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated frame");
              if (frame.cmdId() == probe.protocol.clientCommand("StaminaInfoScNotify"))
                probe.stamina = probe.protocol.clientSchema().parse("StaminaInfoScNotify", frame.body());
              probe.inbox.add(frame);
            }
          } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error, Ukcp channel) {
          probe.failure.set(error); probe.connected.completeExceptionally(error);
        }
        public void handleClose(Ukcp channel) {
          probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
        }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write((new Gson().toJson(probe.execute(control)) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush(); exit = 0;
    } catch (Exception error) { error.printStackTrace(System.err); }
    finally { if (connection != null) connection.close(); client.stop(); }
    System.exit(exit);
  }
}
