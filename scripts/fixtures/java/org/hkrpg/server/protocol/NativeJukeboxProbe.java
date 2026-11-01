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
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Synthetic native jukebox messages; compiled separately and excluded from the runtime JAR. */
public final class NativeJukeboxProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }
  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }
  private static void set(DynamicMessage.Builder m, String key, Object value) {
    m.setField(m.getDescriptorForType().findFieldByName(key), value);
  }
  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }
  private DynamicMessage call(String name) throws Exception { return call(name, builder(name)); }
  private DynamicMessage call(String name, DynamicMessage.Builder request) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(
        protocol.clientCommand(name), new byte[0], request.build().toByteArray()));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
    String response = name.replace("CsReq", "ScRsp");
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < end) {
      if (failure.get() != null) throw new IllegalStateException("Connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame != null && frame.command() == protocol.clientCommand(response)) {
        var message = protocol.clientSchema().parse(response, frame.body());
        require(message.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
        return message;
      }
    }
    throw new TimeoutException(response);
  }
  private DynamicMessage mix(int id, boolean paused, boolean noisePaused, int... layers) {
    var selected = builder("KHADHDLCNOI");
    set(selected, "id", id);
    set(selected, "is_pause", paused);
    var mix = builder("MNCBEDBDDHL");
    set(mix, "JKNLCEEDAHJ", selected.build());
    set(mix, "PGPNCNABHAM", noisePaused);
    for (int i = 0; i < layers.length; i += 2) {
      var row = builder("AAKECCJELLI");
      set(row, "id", layers[i]); set(row, "volume", layers[i + 1]);
      mix.addRepeatedField(mix.getDescriptorForType().findFieldByName("NNJCEINOEHH"), row.build());
    }
    return mix.build();
  }
  private void query(DynamicMessage expected) throws Exception {
    var response = call("GetJukeboxDataCsReq");
    require(field(response, "retcode").equals(0), "Jukebox query rejected");
    require(expected.equals(field(response, "GFFOBALDBPM")), "Saved mix did not restore");
    require(((List<?>) field(response, "IOKAJIBHLMP")).size() == 283, "Original catalog changed");
  }
  private void save(DynamicMessage expected, boolean valid, DynamicMessage old) throws Exception {
    var request = builder("PlayBackGroundMusicCsReq");
    set(request, "IKJNIKIGFLF", expected);
    var response = call("PlayBackGroundMusicCsReq", request);
    require(field(response, "retcode").equals(0) == valid, "Wrong save result");
    require((valid ? expected : old).equals(field(response, "GFFOBALDBPM")), "Wrong save container");
  }
  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var reply = call("PlayerGetTokenCsReq", token);
    require(field(reply, "retcode").equals(0), "Token rejected");
    int uid = (Integer) field(reply, "uid");
    require(field(call("PlayerLoginCsReq"), "retcode").equals(0), "Login rejected");
    require(field(call("PlayerLoginFinishCsReq"), "retcode").equals(0), "Login finish rejected");
    var expected = mix(210001, true, false,
        215001, 100, 215002, 90, 215004, 73, 215005, 0, 215003, 100);
    if (control.get("mode").getAsString().equals("fresh")) {
      query(mix(210000, false, false));
      save(expected, true, null); query(expected);
      save(mix(999999, false, false), false, expected); query(expected);
      var ambient = mix(0, false, true, 215003, 43, 215004, 70);
      save(ambient, true, null); query(ambient);
      var cleared = mix(210000, false, false);
      save(cleared, true, null); query(cleared);
      save(expected, true, null);
    }
    query(expected);
    return Map.of("passed", true, "uid", uid, "mode", control.get("mode").getAsString(),
        "nativeRequests", requests, "musicId", 210001, "musicPaused", true,
        "whiteNoisePaused", false, "layerIds", List.of(215001, 215002, 215004, 215005, 215003),
        "volumes", List.of(100, 90, 73, 0, 100), "clientGameplayVerified", false);
  }

  public static void main(String[] args) {
    var probe = new NativeJukeboxProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
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
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
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
