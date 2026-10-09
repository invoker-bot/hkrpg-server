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
 * Versioned balanced project pool and cross-chapter H2 recovery over synthetic UDP/KCP; actual UI
 * remains unverified.
 */
public final class NativeClockParkBalancedProbe {
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
    String action = input.get("action").getAsString(),
        failureTransaction = input.get("failureTransaction").getAsString();
    boolean completed = input.get("failureCompleted").getAsBoolean();
    if (action.equals("locked"))
      call("ClockParkStartScriptCsReq", Map.of("script_id", 2), Retcode.FUNC_CHECK_FAILED.getVal());
    else if (action.equals("point")) {
      boolean passed = false;
      for (int attempt = 0; attempt < 40 && !passed; attempt++) {
        call("ClockParkStartScriptCsReq", Map.of("script_id", 2), 0);
        starts++;
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        require(
            phase(current).equals("STORY") && number(current, "DCAHJELNJNP") == 201,
            "Missing original 201 story");
        call(
            "ClockParkHandleWaitOperationCsReq", confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML"), 0);
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        for (int i = 0; i < 4; i++) {
          var menu = nested(nested(nested(current, "HGDLJDAFHLI"), "FDNLIPKGBLM"), "CENIOBBIJMF");
          var ids =
              ((List<?>) field(menu, "LONFKFCFDND"))
                  .stream().map(v -> number((DynamicMessage) v, "card_id")).toList();
          require(ids.equals(List.of(1001, 1007, 1008)), "New run did not offer balanced pool");
          // A legacy-pool request must leave the complete actual waiting response unchanged.
          var foreign =
              new HashMap<String, Object>(
                  placement(
                      2,
                      number(nested(current, "HGDLJDAFHLI"), "JGPHDBDHKKO"),
                      faces(current),
                      false));
          var outer = ((DynamicMessage) foreign.get("JFAJEBGKPEH")).toBuilder();
          var dice = nested(outer.build(), "CENIOBBIJMF").toBuilder();
          var rowsField = dice.getDescriptorForType().findFieldByName("LONFKFCFDND");
          var row = ((DynamicMessage) dice.getRepeatedField(rowsField, 1)).toBuilder();
          row.setField(row.getDescriptorForType().findFieldByName("card_id"), 1002);
          dice.setRepeatedField(rowsField, 1, row.build());
          outer.setField(outer.getDescriptorForType().findFieldByName("CENIOBBIJMF"), dice.build());
          foreign.put("JFAJEBGKPEH", outer.build());
          call("ClockParkHandleWaitOperationCsReq", foreign, 5);
          require(
              current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
              "Foreign pool changed waiting dice");
          current = normal(current);
          steps++;
        }
        require(
            phase(current).equals("CHECKPOINT") && number(current, "INDJEOAJJEN") == 211,
            "Missing original 211 checkpoint");
        passed = number(nested(current, "JOFJEIFLGBP"), "AKKLILNJCPL") >= 30;
        require(
            field(nested(nested(current, "HGDLJDAFHLI"), "BELJJJLLCBK"), "OEKKAJJEGAK")
                .equals(passed),
            "Checkpoint disagrees with actual B attribute");
        if (!passed) {
          failureTransaction = (String) field(current, "IMJFLCKFJGF");
          var ack =
              call(
                  "ClockParkHandleWaitOperationCsReq",
                  confirm(current, "MLHNHAFPLKD", "MOEMKBLCKKO"),
                  0);
          require(
              ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                      .getNumber()
                  == 1,
              "Missing actual failure acknowledgement");
          var result = nested(resultNotification(), "NLEOMLNOKCC");
          require(
              field(result, "HGPKMHFPMBJ").equals(true)
                  && number(result, "progress") == 0
                  && ((List<?>) field(nested(result, "reward"), "item_list")).isEmpty(),
              "Failure credited rewards");
          current = call("ClockParkGetOngoingScriptInfoCsReq");
          require(phase(current).equals("IDLE"), "Failure retained active run");
          completed = true;
        }
      }
      require(passed, "No naturally passed balanced checkpoint in bounded attempts");
    } else if (action.equals("branch")) {
      require(phase(current).equals("CHECKPOINT"), "No restored passed checkpoint");
      var operation = confirm(current, "MLHNHAFPLKD", "MOEMKBLCKKO");
      var ack = call("ClockParkHandleWaitOperationCsReq", operation, 0);
      require(
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                  .getNumber()
              == 2,
          "Missing native pass acknowledgement");
      call("ClockParkHandleWaitOperationCsReq", operation, 5);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
      require(phase(current).equals("BRANCH") && pending(current).size() == 1, "No branch face");
    } else if (action.equals("story")) {
      require(phase(current).equals("BRANCH"), "No restored branch");
      var attributes = nested(current, "JOFJEIFLGBP");
      var transaction = field(current, "IMJFLCKFJGF");
      var operation = select(current, 2101, pending(current).getFirst());
      call("ClockParkHandleWaitOperationCsReq", operation, 0);
      call("ClockParkHandleWaitOperationCsReq", operation, 5);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
      require(
          phase(current).equals("STORY")
              && number(current, "DCAHJELNJNP") == 202
              && field(current, "FKNBOIIMFLD").equals(List.of(201, 202)),
          "Branch did not enter original 202 story");
      require(
          attributes.equals(nested(current, "JOFJEIFLGBP"))
              && transaction.equals(field(current, "IMJFLCKFJGF")),
          "Chapter transition reset actual attributes/UUID");
    } else if (action.equals("dice")) {
      require(
          phase(current).equals("STORY") && number(current, "DCAHJELNJNP") == 202,
          "No restored 202 story");
      call("ClockParkHandleWaitOperationCsReq", confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML"), 0);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
      require(phase(current).equals("DICE"), "No next chapter dice");
    } else if (action.equals("progress")) {
      require(
          phase(current).equals("DICE") && number(current, "DCAHJELNJNP") == 202,
          "No restored 202 dice");
      current = normal(current);
      steps++;
      require(number(current, "JIPPMKBDJJC") == 1, "Next chapter did not apply one receipt");
    } else if (action.equals("quit")) {
      call("ClockParkQuitScriptCsReq", Map.of("CNHLAAHDPGA", 2), 0);
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
    out.put("failureCompleted", completed);
    out.put("failureTransaction", failureTransaction);
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

  public static void main(String[] args) {
    var probe = new NativeClockParkBalancedProbe();
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
