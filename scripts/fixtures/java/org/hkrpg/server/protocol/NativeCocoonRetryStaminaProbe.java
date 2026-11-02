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

/** Isolated native 24-wave wins, actual reserve recovery, duplicates, and restart readback. */
public final class NativeCocoonRetryStaminaProbe {
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
      require(expectedCode < 0 ? !field(message, "retcode").equals(0) : field(message, "retcode").equals(expectedCode), "Wrong result code: " + response + " / " + field(message, "retcode"));
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

  private void balance(int expectedStamina, int expectedReserve) {
    require(stamina != null && field(stamina, "stamina").equals(expectedStamina)
        && field(stamina, "reserve_stamina").equals(expectedReserve), "Wrong conserved balances");
  }

  private DynamicMessage start(String name, int count, int expected) throws Exception {
    var body = builder(name);
    body.setField(body.getDescriptorForType().findFieldByName("cocoon_id"), 1001);
    body.setField(body.getDescriptorForType().findFieldByName("HECCOBFBJFI"), count);
    if (name.equals("StartCocoonStageCsReq"))
      body.setField(body.getDescriptorForType().findFieldByName("prop_entity_id"), 33);
    var reply = call(name, body.build().toByteArray(), expected);
    if (expected == 0) {
      require(field(reply, "wave").equals(count), "Wrong wave echo");
      if (name.equals("StartCocoonStageCsReq")) {
        require(field(reply, "challenge_cnt").equals(count), "Wrong challenge echo");
        require(field(reply, "prop_entity_id").equals(33), "Wrong prop echo");
      }
      var info = (DynamicMessage) field(reply, "battle_info");
      require(((java.util.List<?>) field(info, "monster_wave_list")).size() == count, "Shortened battle");
      return info;
    }
    require(!reply.hasField(reply.getDescriptorForType().findFieldByName("battle_info")), "Rejected entry has a battle");
    return null;
  }

  private void finish(DynamicMessage info, int expected) throws Exception {
    var body = builder("PVEBattleResultCsReq");
    for (String key : new String[] {"battle_id", "stage_id"})
      body.setField(body.getDescriptorForType().findFieldByName(key), field(info, key));
    var status = body.getDescriptorForType().findFieldByName("end_status");
    body.setField(status, status.getEnumType().findValueByName("BATTLE_END_WIN"));
    call("PVEBattleResultCsReq", body.build().toByteArray(), expected);
  }

  private Map<String, Object> execute(com.google.gson.JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    token.setField(token.getDescriptorForType().findFieldByName("account_uid"), control.get("accountUid").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("token"), control.get("comboToken").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("platform"), 1);
    int uid = (Integer) field(call("PlayerGetTokenCsReq", token), "uid");
    var login = call("PlayerLoginCsReq");
    String mode = control.get("mode").getAsString();
    int initial = mode.equals("restart") ? 0 : 300;
    require(field(login, "stamina").equals(initial), "Login balance changed across normal H2 restart");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("initialize")) {
      preference(false); toggle(true); preference(true);
    } else if (mode.equals("battles")) {
      preference(true);
      var first = start("StartCocoonStageCsReq", 24, 0);
      finish(first, 0); balance(60, 394);
      finish(first, -1); balance(60, 394);
      // Exactly the observed retry: 60 current, enabled preference, and 394.5 real reserve.
      var second = start("StartCocoonStageCsReq", 24, 0);
      balance(240, 214);
      require(!field(first, "battle_id").equals(field(second, "battle_id")), "Retry reused a settled battle");
      start("StartCocoonStageCsReq", 24, 5); balance(240, 214);
      finish(second, 0); balance(0, 214);
      finish(second, -1); balance(0, 214);
      start("StartCocoonStageCsReq", 24, 2101); balance(0, 214);
    } else if (mode.equals("restart")) {
      preference(true); toggle(false); preference(false);
      // Reserve can afford one wave, but a disabled preference must still refuse automatic use.
      start("QuickStartCocoonStageCsReq", 1, 2101);
      toggle(true); preference(true);
    } else throw new IllegalStateException("Unknown probe phase");
    var heartbeat = builder("PlayerHeartBeatCsReq");
    heartbeat.setField(heartbeat.getDescriptorForType().findFieldByName("client_time_ms"), 1_793_500_000_000L);
    call("PlayerHeartBeatCsReq", heartbeat);
    return Map.of("passed", true, "uid", uid, "mode", mode, "nativeRequests", requests,
        "actualSettledChallenges", mode.equals("battles") ? 48 : 0,
        "correlationHeadersVerified", true, "enabledAtEnd", true, "clientUIVerified", false);
  }

  public static void main(String[] args) {
    var probe = new NativeCocoonRetryStaminaProbe();
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
