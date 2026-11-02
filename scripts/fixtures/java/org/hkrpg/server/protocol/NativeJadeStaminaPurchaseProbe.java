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

/** Isolated native jade purchases: original prices, response identity, balances, and quota reload. */
public final class NativeJadeStaminaPurchaseProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<PacketCodec.Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;
  private volatile DynamicMessage stamina, syncedPlayer;

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
    return callWithId(name, body, expectedCode, ++requests);
  }

  private DynamicMessage callWithId(String name, byte[] body, int expectedCode, int packetId) throws Exception {
    byte[] header = {8, (byte) packetId};
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

  private void times(int expected) throws Exception {
    var reply = call("GetBasicInfoCsReq");
    require(field(reply, "exchange_times").equals(expected), "Daily purchase count was not restored");
    var settings = (DynamicMessage) field(reply, "player_setting_info");
    require(settings.hasField(settings.getDescriptorForType().findFieldByNumber(13)), "Required settings container missing");
  }

  private void balances(int jade, int power) {
    require(stamina != null && field(stamina, "stamina").equals(power)
        && field(stamina, "reserve_stamina").equals(0), "Wrong power or reserve notification");
    require(syncedPlayer != null && field(syncedPlayer, "hcoin").equals(jade)
        && field(syncedPlayer, "stamina").equals(power), "Wrong jade or player balance notification");
  }

  private void cost(DynamicMessage reply, int price, int ordinal) {
    require(field(reply, "stamina_add").equals(60), "Wrong purchase grant");
    require(field(reply, "exchange_times").equals(ordinal), "Wrong purchase count");
    var rows = (java.util.List<?>) field(reply, "item_cost_list");
    require(rows.size() == 1, "Wrong item cost count");
    var pile = (DynamicMessage) field((DynamicMessage) rows.getFirst(), "pile_item");
    require(field(pile, "item_id").equals(1) && field(pile, "item_num").equals(price), "Wrong actual jade cost");
    require((Long) field(reply, "last_recover_time") >= 0, "Invalid recovery timestamp");
  }

  private Map<String, Object> execute(com.google.gson.JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    token.setField(token.getDescriptorForType().findFieldByName("account_uid"), control.get("accountUid").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("token"), control.get("comboToken").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("platform"), 1);
    int uid = (Integer) field(call("PlayerGetTokenCsReq", token), "uid");
    var login = call("PlayerLoginCsReq");
    String mode = control.get("mode").getAsString();
    int expectedPower = mode.equals("purchases") ? 0 : mode.equals("restart") ? 480 : 300;
    require(field(login, "stamina").equals(expectedPower), "Power changed across normal H2 restart");
    var basic = (DynamicMessage) field(login, "basic_info");
    if (!mode.equals("initialize"))
      require(field(basic, "hcoin").equals(mode.equals("purchases") ? 1000 : 100), "Jade changed across normal H2 restart");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("initialize")) times(0);
    else if (mode.equals("purchases")) {
      times(0);
      int spent = 0, firstId = 0;
      DynamicMessage first = null;
      int[] prices = {50, 75, 75, 100, 100, 150, 150, 200};
      for (int i = 0; i < prices.length; i++) {
        int id = requests + 1;
        var reply = call("ExchangeStaminaCsReq");
        cost(reply, prices[i], i + 1); spent += prices[i]; balances(1000 - spent, (i + 1) * 60);
        if (i == 0) {
          firstId = id; first = reply; requests++;
          require(first.equals(callWithId("ExchangeStaminaCsReq", new byte[0], 0, firstId)), "Duplicate reply changed");
          balances(950, 60); times(1);
        }
      }
      requests++;
      require(first.equals(callWithId("ExchangeStaminaCsReq", new byte[0], 0, firstId)), "Old successful receipt was replaced");
      balances(100, 480); times(8);
      var limited = call("ExchangeStaminaCsReq", new byte[0], 2100);
      require(field(limited, "stamina_add").equals(0)
          && ((java.util.List<?>) field(limited, "item_cost_list")).isEmpty(), "Quota rejection falsely charged or granted");
      balances(100, 480); times(8);
    } else if (mode.equals("restart")) {
      times(8);
      call("ExchangeStaminaCsReq", new byte[0], 2100);
      times(8);
    } else throw new IllegalStateException("Unknown probe phase");
    var heartbeat = builder("PlayerHeartBeatCsReq");
    heartbeat.setField(heartbeat.getDescriptorForType().findFieldByName("client_time_ms"), 1_793_500_000_000L);
    call("PlayerHeartBeatCsReq", heartbeat);
    return Map.of("passed", true, "uid", uid, "mode", mode, "nativeRequests", requests,
        "successfulPurchases", mode.equals("purchases") ? 8 : 0,
        "jadeSpent", mode.equals("purchases") ? 900 : 0, "powerAdded", mode.equals("purchases") ? 480 : 0,
        "correlationHeadersVerified", true, "samePacketReplayVerified", mode.equals("purchases"), "clientUIVerified", false);
  }

  public static void main(String[] args) {
    var probe = new NativeJadeStaminaPurchaseProbe();
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
              if (frame.cmdId() == probe.protocol.clientCommand("PlayerSyncScNotify")) {
                var sync = probe.protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
                var field = sync.getDescriptorForType().findFieldByName("basic_info");
                if (sync.hasField(field)) probe.syncedPlayer = (DynamicMessage) sync.getField(field);
              }
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
