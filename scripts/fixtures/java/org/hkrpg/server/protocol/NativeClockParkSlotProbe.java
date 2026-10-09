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

/**
 * Original zero-cost slot, saved weighted ticket and cached result H2 recovery over synthetic
 * UDP/KCP; actual UI remains unverified.
 */
public final class NativeClockParkSlotProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static int status(DynamicMessage m) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(m, "status")).getNumber();
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  private void check(boolean value, String reason) {
    require(value, reason);
    checks.add(reason);
  }

  private DynamicMessage.Builder request(String name, Map<String, Object> fields) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    return b;
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int code) throws Exception {
    return rawCall(name, request(name, fields).build().toByteArray(), code);
  }

  private DynamicMessage rawCall(String name, byte[] body, int code) throws Exception {
    requests++;
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Clock park catalog KCP failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() != protocol.clientCommand(response)) continue;
      var message = protocol.clientSchema().parse(response, frame.body());
      require(
          message.getUnknownFields().asMap().isEmpty(),
          "Unknown clock park catalog response fields: " + response);
      String retcode = response.equals("FinishQuestScRsp") ? "PLDKENJLKME" : "retcode";
      require(
          number(message, retcode) == code,
          "Rejected " + response + " code=" + number(message, retcode) + " expected=" + code);
      return message;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private DynamicMessage nested(DynamicMessage message, String name) {
    return (DynamicMessage) field(message, name);
  }

  private List<Integer> faces(DynamicMessage response) {
    if (number(response, "INDJEOAJJEN") != 0) return List.of();
    return (List<Integer>)
        field(
            nested(nested(nested(response, "HGDLJDAFHLI"), "FDNLIPKGBLM"), "CENIOBBIJMF"),
            "KCNEKEGGAGG");
  }

  private String digest(DynamicMessage response) throws Exception {
    return HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(response.toByteArray()));
  }

  private Map<String, Object> placement(
      int script, int round, List<Integer> savedFaces, boolean forged) {
    var dice = request("IIMHLPBEDDH", Map.of());
    for (int i = 0; i < 3; i++) {
      var row = request("NIJHCCNMEGA", Map.of("card_id", List.of(1001, 1007, 1008).get(i)));
      row.addRepeatedField(
          row.getDescriptorForType().findFieldByName("KCNEKEGGAGG"),
          forged && i == 1 ? 7 : savedFaces.get(i));
      dice.addRepeatedField(
          dice.getDescriptorForType().findFieldByName("LONFKFCFDND"), row.build());
    }
    return Map.of(
        "script_id",
        script,
        "JGPHDBDHKKO",
        round,
        "JFAJEBGKPEH",
        request("KJAIAGDNPGC", Map.of("CENIOBBIJMF", dice.build())).build());
  }

  private String phase(DynamicMessage current) {
    if (number(current, "script_id") == 0) return "IDLE";
    var wait = nested(current, "HGDLJDAFHLI");
    for (var e :
        Map.of(
                "STORY",
                "ICPMABKBLCD",
                "DICE",
                "FDNLIPKGBLM",
                "CHECKPOINT",
                "BELJJJLLCBK",
                "BRANCH",
                "ABIPNABIDPE",
                "SLOT",
                "HGHBDACDJKP")
            .entrySet())
      if (wait.hasField(wait.getDescriptorForType().findFieldByName(e.getValue())))
        return e.getKey();
    throw new IllegalStateException("Unknown wait phase");
  }

  private List<Integer> pending(DynamicMessage current) {
    String phase = phase(current);
    if (phase.equals("DICE")) return faces(current);
    if (phase.equals("BRANCH"))
      return (List<Integer>)
          field(
              nested(nested(nested(current, "HGDLJDAFHLI"), "ABIPNABIDPE"), "CENIOBBIJMF"),
              "KCNEKEGGAGG");
    return List.of();
  }

  private Map<String, Object> select(DynamicMessage current, int card, int face) {
    var row = request("NIJHCCNMEGA", Map.of("card_id", card));
    row.addRepeatedField(row.getDescriptorForType().findFieldByName("KCNEKEGGAGG"), face);
    var dice = request("IIMHLPBEDDH", Map.of());
    dice.addRepeatedField(dice.getDescriptorForType().findFieldByName("LONFKFCFDND"), row.build());
    return Map.of(
        "script_id",
        number(current, "script_id"),
        "JGPHDBDHKKO",
        number(nested(current, "HGDLJDAFHLI"), "JGPHDBDHKKO"),
        "JFAJEBGKPEH",
        request("KJAIAGDNPGC", Map.of("CENIOBBIJMF", dice.build())).build());
  }

  private Map<String, Object> confirm(DynamicMessage current, String field, String type) {
    return Map.of(
        "script_id",
        number(current, "script_id"),
        "JGPHDBDHKKO",
        number(nested(current, "HGDLJDAFHLI"), "JGPHDBDHKKO"),
        field,
        request(type, Map.of()).build());
  }

  private DynamicMessage normal(DynamicMessage current) throws Exception {
    int script = number(current, "script_id"),
        round = number(nested(current, "HGDLJDAFHLI"), "JGPHDBDHKKO");
    var before = nested(current, "JOFJEIFLGBP");
    var dice = faces(current);
    var request = placement(script, round, dice, false);
    call("ClockParkHandleWaitOperationCsReq", request, 0);
    var next = call("ClockParkGetOngoingScriptInfoCsReq");
    var attrs = nested(next, "JOFJEIFLGBP");
    int cap = script == 1 ? 50 : 100;
    require(
        number(attrs, "JEKOHIHJFMK")
                == Math.min(cap, number(before, "JEKOHIHJFMK") + dice.get(0) + dice.get(1))
            && number(attrs, "AKKLILNJCPL")
                == Math.min(cap, number(before, "AKKLILNJCPL") + dice.get(1) + dice.get(2))
            && number(attrs, "PNOIJPDMCPG")
                == Math.min(cap, number(before, "PNOIJPDMCPG") + dice.get(2)),
        "Actual die effects differ");
    call("ClockParkHandleWaitOperationCsReq", request, 5);
    require(next.equals(call("ClockParkGetOngoingScriptInfoCsReq")), "Replay changed progress");
    return next;
  }

  private DynamicMessage resultNotification() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame != null
          && frame.command() == protocol.clientCommand("ClockParkFinishScriptScNotify"))
        return protocol.clientSchema().parse("ClockParkFinishScriptScNotify", frame.body());
    }
    throw new TimeoutException("ClockParkFinishScriptScNotify");
  }

  private int itemCount() throws Exception {
    int count = 0, rows = 0;
    for (var raw : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var item = (DynamicMessage) raw;
      if (number(item, "tid") == 140484) {
        count += number(item, "num");
        rows++;
      }
    }
    require(rows <= 1 && count >= 0 && count <= 1, "Invalid actual unique item inventory");
    return count;
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    int uid =
        number(
            call(
                "PlayerGetTokenCsReq",
                Map.of(
                    "account_uid",
                    input.get("accountUid").getAsString(),
                    "token",
                    input.get("comboToken").getAsString(),
                    "platform",
                    1),
                0),
            "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    var current = call("ClockParkGetOngoingScriptInfoCsReq");
    require(
        input.get("ongoingSha256").getAsString().isEmpty()
            || digest(current).equals(input.get("ongoingSha256").getAsString()),
        "Restart changed complete query");
    require(
        number(current, "script_id") == input.get("previousScript").getAsInt(),
        "Restart changed script");
    int starts = input.get("runsStarted").getAsInt(), steps = 0;
    String action = input.get("action").getAsString();
    int lotteryId = input.get("lotteryId").getAsInt();
    int ticket = input.get("ticket").getAsInt();
    if (action.equals("locked")) {
      call("ClockParkStartScriptCsReq", Map.of("script_id", 5), Retcode.FUNC_CHECK_FAILED.getVal());
    } else if (action.equals("start") || action.equals("skip")) {
      call("ClockParkStartScriptCsReq", Map.of("script_id", 5), 0);
      starts++;
      current = call("ClockParkGetOngoingScriptInfoCsReq");
      require(
          phase(current).equals("STORY") && number(current, "DCAHJELNJNP") == 510,
          "Missing original slot story");
      call("ClockParkHandleWaitOperationCsReq", confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML"), 0);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
      int rounds = action.equals("skip") ? 4 : 1;
      for (int i = 0; i < rounds; i++) {
        current = normal(current);
        steps++;
      }
      lotteryId = 0;
      if (action.equals("skip")) {
        ticket = number(slot(current), "gacha_random");
        var before = nested(current, "JOFJEIFLGBP");
        call("ClockParkHandleWaitOperationCsReq", gacha(current, false, ticket), 0);
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        require(
            before.equals(nested(current, "JOFJEIFLGBP")), "Skipping changed original attributes");
        verifyCheckpoint(current);
      }
    } else if (action.equals("pending")) {
      require(
          phase(current).equals("DICE") && number(current, "JIPPMKBDJJC") == 1,
          "Missing restored first normal receipt");
      for (int i = 1; i < 4; i++) {
        current = normal(current);
        steps++;
      }
      require(phase(current).equals("SLOT"), "Original game was skipped");
      ticket = number(slot(current), "gacha_random");
      require(
          ticket >= 0 && ticket < 1000 && number(slot(current), "NDNHCFBFPMH") == 1,
          "Invalid owned ticket/play count");
      var dice = nested(slot(current), "CENIOBBIJMF");
      require(
          ((List<?>) field(dice, "KCNEKEGGAGG")).isEmpty(),
          "Slot offered unnecessary dice placement");
      var rows = (List<?>) field(dice, "LONFKFCFDND");
      require(rows.size() == 1, "Wrong project gameplay card count");
      var card = (DynamicMessage) rows.getFirst();
      require(
          number(card, "card_id") == 5150
              && field(card, "KCNEKEGGAGG").equals(List.of(1))
              && number(card, "NPCBECNBIJK") == 1,
          "Missing filled original game card");
      call("ClockParkHandleWaitOperationCsReq", gacha(current, true, (ticket + 1) % 1000), 5);
      require(
          current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
          "Foreign ticket changed waiting response");
    } else if (action.equals("draw") || action.equals("replay")) {
      require(
          phase(current).equals("SLOT") && number(slot(current), "gacha_random") == ticket,
          "Owned ticket changed on restart");
      var before = nested(current, "JOFJEIFLGBP");
      var operation = gacha(current, true, ticket);
      var ack = call("ClockParkHandleWaitOperationCsReq", operation, 0);
      int expected = ticket == 0 ? 42 : ticket < 10 ? 32 : ticket < 650 ? 22 : 12;
      require(
          number(ack, "IGBHCBNKEBO") == expected && number(ack, "DMNECEKOLAJ") == 0,
          "Actual lottery result is not ACK wire field 8");
      lotteryId = expected;
      current = call("ClockParkGetOngoingScriptInfoCsReq");
      var after = nested(current, "JOFJEIFLGBP");
      int gain = expected == 42 ? 4 : expected == 32 ? 6 : expected == 22 ? 8 : 12;
      require(
          number(after, "AKKLILNJCPL")
              == (action.equals("draw")
                  ? Math.min(100, number(before, "AKKLILNJCPL") + gain)
                  : number(before, "AKKLILNJCPL")),
          "Draw/recovery replay credited wrong gain");
      require(
          number(after, "JEKOHIHJFMK") == number(before, "JEKOHIHJFMK")
              && number(after, "PNOIJPDMCPG") == number(before, "PNOIJPDMCPG"),
          "Zero-cost card consumed A/C");
      require(number(slot(current), "NDNHCFBFPMH") == 0, "Repeated play still available");
      var replay = call("ClockParkHandleWaitOperationCsReq", operation, 0);
      require(
          number(replay, "IGBHCBNKEBO") == expected
              && current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
          "Cached ACK duplicated gain or lost result");
    } else if (action.equals("exit")) {
      require(phase(current).equals("SLOT"), "Missing restored played slot");
      var before = nested(current, "JOFJEIFLGBP");
      var op = gacha(current, false, ticket);
      call("ClockParkHandleWaitOperationCsReq", op, 0);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
      require(before.equals(nested(current, "JOFJEIFLGBP")), "Exit duplicated gain");
      verifyCheckpoint(current);
      call("ClockParkHandleWaitOperationCsReq", op, 5);
      require(
          current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
          "Exit replay changed checkpoint");
    } else if (action.equals("quit")) {
      require(phase(current).equals("CHECKPOINT"), "Missing restored original slot checkpoint");
      verifyCheckpoint(current);
      if (field(nested(nested(current, "HGDLJDAFHLI"), "BELJJJLLCBK"), "OEKKAJJEGAK")
          .equals(true)) {
        var ack =
            call(
                "ClockParkHandleWaitOperationCsReq",
                confirm(current, "MLHNHAFPLKD", "MOEMKBLCKKO"),
                0);
        require(
            ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                    .getNumber()
                == 2,
            "Missing original slot branch acknowledgement");
        var branch = call("ClockParkGetOngoingScriptInfoCsReq");
        require(phase(branch).equals("BRANCH"), "Missing original 5119 branch");
        var cards =
            (List<?>)
                field(
                    nested(nested(nested(branch, "HGDLJDAFHLI"), "ABIPNABIDPE"), "CENIOBBIJMF"),
                    "LONFKFCFDND");
        require(
            cards.size() == 1 && number((DynamicMessage) cards.getFirst(), "card_id") == 5119,
            "Wrong original first slot branch card");
      }
      call("ClockParkQuitScriptCsReq", Map.of("CNHLAAHDPGA", 5), 0);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
    } else require(action.equals("reload"), "Unknown action");
    var info = call("ClockParkGetInfoCsReq");
    int count = itemCount();
    require(
        count == 0 && number(info, "progress") == 0, "Failure credited an ending item/progress");
    var reached = new TreeMap<Integer, Object>();
    for (var raw : (List<?>) field(info, "DCGDHFJLHCN")) {
      var row = (DynamicMessage) raw;
      reached.put(number(row, "script_id"), field(row, "CFJOGMJIHOE"));
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("passed", true);
    out.put("uid", uid);
    out.put("requests", requests);
    out.put("runsStarted", starts);
    out.put("placementsApplied", steps);
    out.put("activeScript", number(current, "script_id"));
    out.put("chapter", number(current, "DCAHJELNJNP"));
    out.put("roundIndex", number(current, "JIPPMKBDJJC"));
    out.put("phase", phase(current));
    out.put("pendingDice", pending(current));
    out.put("reachedChapters", reached);
    out.put("transaction", field(current, "IMJFLCKFJGF"));
    out.put("ongoingSha256", digest(current));
    out.put("failureCompleted", false);
    out.put("failureTransaction", "");
    out.put("lotteryId", lotteryId);
    out.put("ticket", ticket);
    out.put(
        "slotPlayed", phase(current).equals("SLOT") && number(slot(current), "NDNHCFBFPMH") == 0);
    out.put("actualUniqueItemCount", count);
    out.put("progress", 0);
    out.put("projectPool", List.of(1001, 1007, 1008));
    out.put("officialRoundCardPoolVerified", false);
    out.put("scriptsExecuted", false);
    out.put("clientGameplayVerified", false);
    out.put("placementUsesDiceFaces", true);
    for (var e :
        Map.of(
                "attributeA",
                "JEKOHIHJFMK",
                "attributeB",
                "AKKLILNJCPL",
                "attributeC",
                "PNOIJPDMCPG")
            .entrySet()) out.put(e.getKey(), number(nested(current, "JOFJEIFLGBP"), e.getValue()));
    return out;
  }

  private DynamicMessage slot(DynamicMessage current) {
    return nested(nested(current, "HGDLJDAFHLI"), "HGHBDACDJKP");
  }

  private Map<String, Object> gacha(DynamicMessage current, boolean draw, int ticket) {
    return Map.of(
        "script_id",
        5,
        "JGPHDBDHKKO",
        51008,
        "EJLKEGLKGDN",
        request("IGJLLHMJKPC", Map.of("IMKGOKGKDPH", draw, "gacha_random", ticket)).build());
  }

  private void verifyCheckpoint(DynamicMessage current) {
    require(
        phase(current).equals("CHECKPOINT") && number(current, "INDJEOAJJEN") == 501,
        "Missing original slot checkpoint");
    var attrs = nested(current, "JOFJEIFLGBP");
    boolean passed =
        number(attrs, "JEKOHIHJFMK") + number(attrs, "AKKLILNJCPL") + number(attrs, "PNOIJPDMCPG")
            >= 95;
    require(
        field(nested(nested(current, "HGDLJDAFHLI"), "BELJJJLLCBK"), "OEKKAJJEGAK").equals(passed),
        "Original sum condition differs");
  }

  public static void main(String[] args) {
    var probe = new NativeClockParkSlotProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
          new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var credentials = JsonParser.parseString(control.readLine()).getAsJsonObject();
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
            public void onConnected(Ukcp c) {
              probe.connected.complete(c);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated clock park catalog frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp c) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp c) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("Clock park catalog probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials)) + "\n").getBytes(StandardCharsets.UTF_8));
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
