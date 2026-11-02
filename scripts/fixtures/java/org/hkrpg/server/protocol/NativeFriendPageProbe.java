package org.hkrpg.server.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.GameConstants;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Read-only synthetic friend-page requests against an isolated runtime; never operates the client. */
public final class NativeFriendPageProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<PacketCodec.Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;

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
    byte[] header = {8, (byte) ++requests};
    var request = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), header,
        body.build().toByteArray()));
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
      require(field(message, "retcode").equals(0), "Query rejected: " + response);
      return message;
    }
    throw new TimeoutException(response);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> execute(com.google.gson.JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    token.setField(token.getDescriptorForType().findFieldByName("account_uid"),
        control.get("accountUid").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("token"),
        control.get("comboToken").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("platform"), 1);
    int uid = (Integer) field(call("PlayerGetTokenCsReq", token), "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    var login = call("GetFriendLoginInfoCsReq");
    require(field(login, "_friend_uid_list").equals(List.of(GameConstants.SERVER_CONSOLE_UID)),
        "Login friend count differs from actual console friend");
    for (int repeat = 0; repeat < 3; repeat++) {
      var list = call("GetFriendListInfoCsReq");
      var rows = (List<DynamicMessage>) field(list, "friend_list");
      require(rows.size() == 1, "Console friend missing or duplicated");
      var profile = (DynamicMessage) field(rows.getFirst(), "player_info");
      require(field(profile, "uid").equals(GameConstants.SERVER_CONSOLE_UID), "Wrong friend UID");
      require(!((String) field(profile, "nickname")).isBlank(), "Missing friend nickname");
      require((Integer) field(profile, "level") > 0, "Missing friend level");
      require((Integer) field(profile, "head_icon") > 0, "Missing friend icon");
      require(((List<?>) field(profile, "assist_simple_info_list")).size() == 3,
          "Configured display avatars missing");
      require(field(list, "black_list").equals(List.of()), "Invented blacklist");
      var heartbeat = builder("PlayerHeartBeatCsReq");
      heartbeat.setField(heartbeat.getDescriptorForType().findFieldByName("client_time_ms"),
          1_793_500_000_000L + repeat);
      call("PlayerHeartBeatCsReq", heartbeat);
    }
    var applications = call("GetFriendApplyListInfoCsReq");
    require(field(applications, "receive_apply_list").equals(List.of()), "Invented applications");
    return Map.of("passed", true, "uid", uid, "mode", control.get("mode").getAsString(),
        "nativeRequests", requests, "friendPageQueries", 3, "heartbeatsAfterFriendPage", 3,
        "correlationHeadersVerified", true, "consoleFriendUid", GameConstants.SERVER_CONSOLE_UID,
        "clientUIVerified", false);
  }

  public static void main(String[] args) {
    var probe = new NativeFriendPageProbe();
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
