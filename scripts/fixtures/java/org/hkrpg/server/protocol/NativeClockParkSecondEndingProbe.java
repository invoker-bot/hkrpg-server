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
 * Original conditional chapter actions and cross-chapter H2 recovery over synthetic UDP/KCP; actual
 * UI remains unverified.
 */
public final class NativeClockParkSecondEndingProbe {
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
                "ABIPNABIDPE")
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
      if (number(item, "tid") == 140483) {
        count += number(item, "num");
        rows++;
      }
    }
    require(rows <= 1 && count >= 0 && count <= 1, "Invalid actual unique item inventory");
    return count;
  }

  private int starts, steps;
  private boolean failureCompleted;
  private String failureTransaction;

  private DynamicMessage reach(boolean good) throws Exception {
    for (int attempt = 0; attempt < 120; attempt++) {
      call("ClockParkStartScriptCsReq", Map.of("script_id", 2), 0);
      starts++;
      var current = call("ClockParkGetOngoingScriptInfoCsReq");
      while (phase(current).equals("STORY")) {
        int chapter = number(current, "DCAHJELNJNP");
        if (chapter == 205 || chapter == 206) {
          require(chapter == (good ? 205 : 206), "Wrong naturally selected ending story");
          return current;
        }
        require(Set.of(201, 202, 203, 204).contains(chapter), "Foreign original chapter");
        call(
            "ClockParkHandleWaitOperationCsReq", confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML"), 0);
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        for (int i = 0; i < (chapter <= 202 ? 4 : 5); i++) {
          current = normal(current);
          steps++;
        }
        int point = chapter == 201 ? 211 : chapter == 202 ? 221 : chapter == 203 ? 231 : 241;
        int threshold = chapter == 201 ? 30 : chapter == 202 ? 55 : chapter == 203 ? 90 : 95;
        require(
            phase(current).equals("CHECKPOINT") && number(current, "INDJEOAJJEN") == point,
            "Missing actual original checkpoint");
        boolean passed = number(nested(current, "JOFJEIFLGBP"), "AKKLILNJCPL") >= threshold;
        require(
            field(nested(nested(current, "HGDLJDAFHLI"), "BELJJJLLCBK"), "OEKKAJJEGAK")
                .equals(passed),
            "Original gate bypassed actual B");
        var operation = confirm(current, "MLHNHAFPLKD", "MOEMKBLCKKO");
        var ack = call("ClockParkHandleWaitOperationCsReq", operation, 0);
        require(
            ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                    .getNumber()
                == (passed ? 2 : 1),
            "Wrong native checkpoint status");
        if (!passed) {
          failureCompleted = true;
          failureTransaction = (String) field(current, "IMJFLCKFJGF");
          var result = nested(resultNotification(), "NLEOMLNOKCC");
          require(
              field(result, "HGPKMHFPMBJ").equals(true)
                  && number(result, "progress") == 0
                  && ((List<?>) field(nested(result, "reward"), "item_list")).isEmpty(),
              "Failed actual gate awarded ending");
          require(
              phase(call("ClockParkGetOngoingScriptInfoCsReq")).equals("IDLE"),
              "Failure did not close run");
          break;
        }
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        require(phase(current).equals("BRANCH"), "No actual branch wait");
        int face = pending(current).getFirst();
        if (chapter == 202 && (face >= 3) != good) {
          call("ClockParkQuitScriptCsReq", Map.of("CNHLAAHDPGA", 2), 0);
          break;
        }
        int card = chapter == 201 ? 2101 : chapter == 202 ? 2102 : chapter == 203 ? 2103 : 2104;
        call("ClockParkHandleWaitOperationCsReq", select(current, card, face), 0);
        current = call("ClockParkGetOngoingScriptInfoCsReq");
      }
    }
    throw new IllegalStateException("No desired ending naturally reached within 120 actual runs");
  }

  private DynamicMessage finish(DynamicMessage current, boolean good, boolean first)
      throws Exception {
    require(
        phase(current).equals("STORY") && number(current, "DCAHJELNJNP") == (good ? 205 : 206),
        "No restored original ending story");
    var operation = confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML");
    var ack = call("ClockParkHandleWaitOperationCsReq", operation, 0);
    require(
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                .getNumber()
            == 5,
        "No native finished status");
    var result = nested(resultNotification(), "NLEOMLNOKCC");
    require(
        number(result, "script_id") == 2 && number(result, "DCAHJELNJNP") == (good ? 205 : 206),
        "Wrong native result identity");
    require(
        field(result, "HGPKMHFPMBJ").equals(false) && field(result, "DMNEACGEHEC").equals(first),
        "Wrong native narrative completion flags");
    require(
        number(result, "progress") == (good && first ? 2000 : 0), "Wrong original/repeat progress");
    var items = (List<?>) field(nested(result, "reward"), "item_list");
    require(items.size() == (good && first ? 1 : 0), "Wrong original/empty/repeat reward count");
    if (!items.isEmpty()) {
      var item = (DynamicMessage) items.getFirst();
      require(
          number(item, "item_id") == 140483 && number(item, "num") == 1,
          "Wrong actual unique ending award");
    }
    call("ClockParkHandleWaitOperationCsReq", operation, 5);
    var idle = call("ClockParkGetOngoingScriptInfoCsReq");
    require(phase(idle).equals("IDLE"), "Ending did not close run");
    return idle;
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
        "H2 restart changed whole native query");
    require(
        number(current, "script_id") == input.get("previousScript").getAsInt(),
        "Restart changed active script");
    starts = input.get("runsStarted").getAsInt();
    failureCompleted = input.get("failureCompleted").getAsBoolean();
    failureTransaction = input.get("failureTransaction").getAsString();
    boolean trueCompleted = input.get("trueCompleted").getAsBoolean(),
        falseCompleted = input.get("falseCompleted").getAsBoolean();
    String action = input.get("action").getAsString();
    if (action.equals("locked"))
      call("ClockParkStartScriptCsReq", Map.of("script_id", 2), Retcode.FUNC_CHECK_FAILED.getVal());
    else if (action.equals("reachTrue") || action.equals("reachFalse"))
      current = reach(action.equals("reachTrue"));
    else if (action.equals("finishTrue")) {
      current = finish(current, true, !trueCompleted);
      trueCompleted = true;
    } else if (action.equals("finishFalse")) {
      current = finish(current, false, !falseCompleted);
      falseCompleted = true;
    } else require(action.equals("reload"), "Unknown native ending stage");
    var info = call("ClockParkGetInfoCsReq");
    int count = itemCount();
    int progress = number(info, "progress");
    require(
        count == (trueCompleted ? 1 : 0) && progress == (trueCompleted ? 2000 : 0),
        "Actual bag/progress differ from earned proofs");
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
    out.put("failureCompleted", failureCompleted);
    out.put("failureTransaction", failureTransaction);
    out.put("actualUniqueItemCount", count);
    out.put("progress", progress);
    out.put("trueCompleted", trueCompleted);
    out.put("falseCompleted", falseCompleted);
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

  public static void main(String[] args) {
    var probe = new NativeClockParkSecondEndingProbe();
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
