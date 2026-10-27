package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Disposable account, real CN4.6 KCP packets, and normal H2 stop/restart; no client UI. */
public final class NativePresetLineupProbe {
  record Frame(int command, byte[] header, byte[] body) {}
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  final AtomicReference<Throwable> failure = new AtomicReference<>();
  final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  Ukcp channel;
  int requests;

  static void check(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }

  static Object field(DynamicMessage message, String name) {
    return message.getField(Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name), name));
  }

  static byte[] scalar(int number, int value) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt32(number, value);
    out.flush();
    return bytes.toByteArray();
  }

  Frame raw(int command, byte[] body, int response, int packetId) throws Exception {
    byte[] header = packetId == 0 ? new byte[0] : scalar(1, packetId);
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, header, body));
    try { channel.write(bytes); } finally { bytes.release(); }
    requests++;
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < until) {
      if (failure.get() != null) throw new IllegalStateException("KCP disconnected", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null || frame.command() != response) continue;
      if (packetId != 0) check(Arrays.equals(header, frame.header()), "Reply packet ID not echoed: " + response);
      return frame;
    }
    throw new TimeoutException("Waiting for " + response);
  }

  DynamicMessage call(String name, Map<String, Object> fields, int packetId) throws Exception {
    var req = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach((key, value) -> req.setField(req.getDescriptorForType().findFieldByName(key), value));
    String response = name.replace("CsReq", "ScRsp");
    var frame = raw(protocol.clientCommand(name), req.build().toByteArray(), protocol.clientCommand(response), packetId);
    var result = protocol.clientSchema().parse(response, frame.body());
    check(result.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
    var ret = result.getDescriptorForType().findFieldByName("retcode");
    if (ret != null) check((Integer) result.getField(ret) == 0, "Failed response: " + response);
    return result;
  }

  DynamicMessage call(String name) throws Exception { return call(name, Map.of(), 0); }

  List<DynamicMessage> teams() throws Exception {
    var response = call("GetAllLineupDataCsReq", Map.of(), 810 + requests);
    var raw = (List<?>) field(response, "lineup_list");
    var result = new ArrayList<DynamicMessage>();
    for (var row : raw) result.add((DynamicMessage) row);
    check(result.size() == 9, "Normal team count changed");
    return result;
  }

  void favourite(int index, boolean on, int expected) throws Exception {
    var bytes = new ByteArrayOutputStream();
    if (on) bytes.writeBytes(scalar(3, 1));
    if (index != 0) bytes.writeBytes(scalar(9, index));
    var response = UnknownFieldSet.parseFrom(raw(793, bytes.toByteArray(), 773, 900 + requests).body());
    int code = response.hasField(13) ? response.getField(13).getVarintList().getFirst().intValue() : 0;
    int actualIndex = response.hasField(7) ? response.getField(7).getVarintList().getFirst().intValue() : 0;
    boolean actualFavourite = response.hasField(9) && response.getField(9).getVarintList().getFirst() == 1;
    check(code == expected && actualIndex == index, "Favourite reply mismatch");
    if (expected == 0) check(actualFavourite == on, "Favourite reply did not echo state");
  }

  void recommendations() throws Exception {
    var request = protocol.clientSchema().message("GetBigDataAllRecommendCsReq");
    var kindField = request.findFieldByName("big_data_recommend_type");
    for (int kind : List.of(3, 4, 5, 6, 7, 8)) {
      var response = call("GetBigDataAllRecommendCsReq", Map.of("big_data_recommend_type",
          kindField.getEnumType().findValueByNumber(kind)), 1000 + requests);
      check(((Descriptors.EnumValueDescriptor) field(response, "big_data_recommend_type")).getNumber() == kind,
          "Recommendation kind not echoed");
      String container = kind == 3 ? "relic_avatar" : kind == 4 ? "avatar_relic" : "KHCMLINMGEN";
      var descriptor = response.getDescriptorForType().findFieldByName(container);
      check(response.hasField(descriptor), "Empty recommendation container missing");
      check(((DynamicMessage) response.getField(descriptor)).getAllFields().isEmpty(), "Unexpected recommendation statistics");
    }
  }

  static List<String> roster(List<DynamicMessage> teams) {
    var result = new ArrayList<String>();
    for (var team : teams) {
      var copy = team.toBuilder().clearField(team.getDescriptorForType().findFieldByName("_is_favourite")).build();
      result.add(Base64.getEncoder().encodeToString(copy.toByteArray()));
    }
    return result;
  }

  Map<String, Object> execute(JsonObject credentials) throws Exception {
    String mode = credentials.get("mode").getAsString();
    var token = call("PlayerGetTokenCsReq", Map.of("account_uid", credentials.get("accountUid").getAsString(),
        "token", credentials.get("comboToken").getAsString(), "platform", 1), 0);
    int uid = (Integer) field(token, "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    var before = teams();
    // A new isolated account is still in the opening trial: row 0 is a temporary party.
    // Mark ordinary rows 1 and 2 and verify that the trial party remains untouched.
    boolean current = (Boolean) field(before.get(1), "_is_favourite");
    check(current == (mode.equals("reconnect") || mode.equals("after-restart")), "Persisted flag differs in " + mode);
    if (mode.equals("initial")) {
      favourite(1, true, 0);
      favourite(2, true, 0);
      favourite(9, true, 5);
      recommendations();
    } else if (mode.equals("after-restart")) {
      favourite(1, false, 0);
      favourite(2, false, 0);
    }
    else check(mode.equals("reconnect") || mode.equals("final"), "Unsupported phase");
    var after = teams();
    boolean expected = mode.equals("initial") || mode.equals("reconnect");
    check((Boolean) field(after.get(1), "_is_favourite") == expected, "Second team flag differs");
    check((Boolean) field(after.get(2), "_is_favourite") == expected, "Third team flag differs");
    check(!(Boolean) field(after.getFirst(), "_is_favourite"), "Trial party must stay separate");
    for (int i = 3; i < after.size(); i++) check(!(Boolean) field(after.get(i), "_is_favourite"), "Other team changed");
    check(roster(before).equals(roster(after)), "Favourite query changed a roster");
    return Map.of("uid", uid, "phase", mode, "favourite", expected, "teamCount", after.size(),
        "unchangedTeamSnapshots", roster(after), "requests", requests, "clientGameplayVerified", false);
  }

  public static void main(String[] args) {
    var probe = new NativePresetLineupProbe();
    var client = new KcpClient();
    int exit = 1;
    try {
      var credentials = JsonParser.parseString(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine()).getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1400); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true); config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp c) { probe.connected.complete(c); }
        public void handleReceive(ByteBuf bytes, Ukcp c) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              check(frame != null, "Truncated KCP frame");
              probe.inbox.add(new Frame(frame.cmdId(), frame.header(), frame.body()));
            }
          } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error, Ukcp c) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp c) { probe.failure.compareAndSet(null, new IllegalStateException("KCP closed")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(credentials)));
      exit = 0;
    } catch (Exception error) { error.printStackTrace(System.err); }
    finally { client.stop(); }
    System.exit(exit);
  }
}
