package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Own KCP requests for actual selected-path persistence, not official client input or UI. */
public final class NativeRogueAeonProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage value, String name) {
    return value.getField(
        Objects.requireNonNull(value.getDescriptorForType().findFieldByName(name), name));
  }

  private static int number(DynamicMessage value, String name) {
    return ((Number) field(value, name)).intValue();
  }

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private void check(boolean ok, String why) {
    require(ok, why);
    checks.add(why);
  }

  private DynamicMessage raw(String name, byte[] bytes) throws Exception {
    requests++;
    var encoded =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], bytes));
    try {
      channel.write(encoded);
    } finally {
      encoded.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Path connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null || frame.command() != protocol.clientCommand(response)) continue;
      var parsed = protocol.clientSchema().parse(response, frame.body());
      require(parsed.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
      return parsed;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var builder = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) ->
            builder.setField(builder.getDescriptorForType().findFieldByName(key), value));
    return raw(name, builder.build().toByteArray());
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  private DynamicMessage info(DynamicMessage response) {
    return (DynamicMessage) field(response, "rogue_game_info");
  }

  private void catalogue(DynamicMessage game) {
    var basic = (DynamicMessage) field(game, "LFLCDNGALME");
    var paths = (DynamicMessage) field(basic, "AEFMMOHKCHJ");
    check(
        field(paths, "OPAEGCBNACJ").equals(List.of(1, 2, 7)),
        "original-unlocked-paths-in-source-sort");
    check(number(paths, "unlock_aeon_enhance_num") == 0, "no-fabricated-resonance-count");
  }

  private void selection(DynamicMessage game, int expected) {
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var path = (DynamicMessage) field(current, "AEFMMOHKCHJ");
    check(number(path, "JPPAGGPJOMO") == expected, "actual-selected-path-" + expected);
    check(number(path, "unlock_aeon_enhance_num") == 0, "selected-state-without-fake-enhancement");
  }

  private DynamicMessage enter(boolean resume, int aeon) throws Exception {
    return resume
        ? call("EnterRogueCsReq", Map.of("area_id", 100))
        : call(
            "StartRogueCsReq",
            Map.of("area_id", 100, "AFEMBLFCCCH", aeon, "NOEPBNMJFGL", List.of(8001)));
  }

  private void malformed(String mode) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(buffer);
    output.writeUInt32(2, 8001);
    output.writeUInt64(10, mode.equals("overflow") ? (1L << 32) + 100 : 100);
    output.writeUInt32(5, 0);
    if (mode.equals("duplicate")) output.writeUInt32(5, 0);
    output.flush();
    check(
        number(raw("StartRogueCsReq", buffer.toByteArray()), "retcode")
            == Retcode.REQ_PARA_INVALID.getVal(),
        "raw-" + mode + "-start-rejected");
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    String mode = input.get("mode").getAsString();
    require(Set.of("start", "resume", "new").contains(mode), "Unknown mode");
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                input.get("accountUid").getAsString(),
                "token",
                input.get("comboToken").getAsString(),
                "platform",
                1));
    require(number(token, "retcode") == 0, "Token rejected");
    int uid = number(token, "uid");
    require(number(call("PlayerLoginCsReq"), "retcode") == 0, "Login rejected");
    require(number(call("PlayerLoginFinishCsReq"), "retcode") == 0, "Login finish rejected");
    var queried = call("GetRogueInfoCsReq");
    require(number(queried, "retcode") == 0, "Query rejected");
    catalogue(info(queried));
    if (mode.equals("start")) {
      check(number(enter(false, 3), "retcode") != 0, "unverified-kill-unlock-keeps-path-locked");
      malformed("overflow");
      malformed("duplicate");
    } else selection(info(queried), 7);
    var entered = enter(!mode.equals("start"), 7);
    check(number(entered, "retcode") == 0, "actual-" + mode + "-entry");
    selection(info(entered), 7);
    var lineup = (DynamicMessage) field(entered, "lineup");
    check(
        field(lineup, "extra_lineup_type").toString().equals("ExtraLineupType_LineupRogue")
            && ((List<?>) field(lineup, "avatar_list")).size() == 1
            && number((DynamicMessage) ((List<?>) field(lineup, "avatar_list")).getFirst(), "id")
                == 8001,
        "actual-dedicated-owned-lineup");
    check(number(enter(false, 1), "retcode") != 0, "running-choice-cannot-be-overwritten");
    check(number(call("LeaveRogueCsReq"), "retcode") == 0, "actual-pause");
    int saved = 7;
    if (mode.equals("new")) {
      check(
          number(call("QuitRogueCsReq", Map.of("area_id", 100)), "retcode") == 0,
          "actual-abandon-old-run");
      var next = enter(false, 1);
      check(number(next, "retcode") == 0, "new-selected-path-run");
      selection(info(next), 1);
      check(number(call("LeaveRogueCsReq"), "retcode") == 0, "new-run-paused");
      saved = 1;
    }
    selection(info(call("GetRogueInfoCsReq")), saved);
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("mode", mode);
    result.put("selectedAeon", saved);
    result.put("requests", requests);
    result.put("checks", checks);
    result.put("clientGameplayVerified", false);
    result.put("completeResonance", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeRogueAeonProbe();
    var client = new KcpClient();
    int exit = 1;
    try {
      var input =
          JsonParser.parseString(
                  new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                      .readLine())
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
            public void onConnected(Ukcp channel) {
              probe.connected.complete(channel);
            }

            public void handleReceive(ByteBuf bytes, Ukcp channel) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated frame");
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
                  null, new IllegalStateException("Path connection closed"));
            }
          });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(input)));
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      client.stop();
    }
    System.exit(exit);
  }
}
