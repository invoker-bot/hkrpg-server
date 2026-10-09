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

/**
 * Own earned resonance menus over KCP; starting blessings are explicit isolated H2 fixture data.
 */
public final class NativeRogueResonanceProbe {
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
            PacketCodec.encode(
                name.equals("HandleRogueCommonPendingActionCsReq")
                    ? Universe46.PENDING_ACTION_COMMAND
                    : protocol.clientCommand(name),
                new byte[0],
                bytes));
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

  private DynamicMessage current(DynamicMessage game) {
    return (DynamicMessage) field(game, "CFDIKLBINHK");
  }

  private DynamicMessage action(DynamicMessage game) {
    return (DynamicMessage) field(current(game), "FMIGOKIDKJL");
  }

  private List<Integer> offered(DynamicMessage game) {
    var detail = (DynamicMessage) field(action(game), "detail");
    var info = (DynamicMessage) field(detail, "PCHGEGFBKLF");
    var rows = (List<?>) field(info, "LEOFNINDJDB");
    check(!(boolean) field(info, "can_roll"), "earned-resonance-menu-has-no-reroll");
    return rows.stream().map(row -> number((DynamicMessage) row, "buff_id")).toList();
  }

  private DynamicMessage select(DynamicMessage game, int id, int batchOffset) throws Exception {
    var req = DynamicMessage.newBuilder(protocol.clientSchema().message("OFEDNNJIJFL"));
    req.setField(
        req.getDescriptorForType().findFieldByName("MPLGEEGOAMC"),
        number(action(game), "queue_position") + batchOffset);
    var field = req.getDescriptorForType().findFieldByName("DLKIKBIDJDB");
    var choice = DynamicMessage.newBuilder(field.getMessageType());
    choice.setField(choice.getDescriptorForType().findFieldByName("BAOFIGFFMLN"), id);
    req.setField(field, choice.build());
    return raw("HandleRogueCommonPendingActionCsReq", req.build().toByteArray());
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    String mode = input.get("mode").getAsString();
    require(Set.of("setup", "earn", "resume-earned", "recover").contains(mode), "Unknown mode");
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
    var query = info(call("GetRogueInfoCsReq"));
    catalogue(query);
    var game = info(enter(!mode.equals("setup"), 7));
    selection(game, 7);
    if (mode.equals("earn") || mode.equals("resume-earned")) {
      if (mode.equals("earn")) {
        var ordinary = offered(game);
        check(ordinary.size() == 1, "saved-single-normal-reward-menu");
        check(
            number(select(game, ordinary.getFirst(), 1), "retcode") != 0,
            "wrong-normal-batch-rejected");
        check(
            number(select(game, ordinary.getFirst(), 0), "retcode") == 0,
            "actual-normal-blessing-selected");
      } else {
        check(number(action(game), "queue_position") > 0, "resume-recovered-earned-menu");
      }
      var pending = info(call("GetRogueInfoCsReq"));
      var next = offered(pending);
      int acquired = input.get("acquired").getAsInt();
      var expected = new TreeSet<Integer>(List.of(612621, 612622, 612623));
      for (int i = 0; i < Math.max(0, acquired - 1); i++) expected.remove(612621 + i);
      check(
          next.equals(acquired == 0 ? List.of(612620) : new ArrayList<>(expected)),
          "only-still-unowned-original-path-seven-resonance");
      int buff = next.getFirst();
      check(number(select(pending, buff, 1), "retcode") != 0, "wrong-earned-batch-rejected");
      check(number(select(pending, buff, 0), "retcode") == 0, "actual-earned-resonance-selected");
      check(number(select(pending, buff, 0), "retcode") != 0, "earned-resonance-replay-rejected");
      game = info(call("GetRogueInfoCsReq"));
      var owned = (DynamicMessage) field(current(game), "HABJHGPFGEB");
      check(
          ((List<?>) field(owned, "CHCMEKAFKAF"))
              .stream().anyMatch(r -> number((DynamicMessage) r, "buff_id") == buff),
          "resonance-present-in-actual-query");
      check(number(action(game), "queue_position") == 0, "earned-action-no-longer-pending");
    } else if (mode.equals("recover")) {
      var owned = (DynamicMessage) field(current(game), "HABJHGPFGEB");
      var ids =
          ((List<?>) field(owned, "CHCMEKAFKAF"))
              .stream().map(r -> number((DynamicMessage) r, "buff_id")).toList();
      check(
          ids.containsAll(List.of(612620, 612621, 612622, 612623)),
          "four-original-resonance-buffs-survive-normal-H2-restart");
      check(number(action(game), "queue_position") == 0, "no-new-resonance-after-four-awards");
    }
    check(number(call("LeaveRogueCsReq"), "retcode") == 0, "actual-pause");
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "mode",
        mode,
        "requests",
        requests,
        "checks",
        checks,
        "clientGameplayVerified",
        false,
        "completeResonance",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeRogueResonanceProbe();
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
