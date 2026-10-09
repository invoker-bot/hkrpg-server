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
 * Saved ClockPark normal dice rounds over synthetic UDP/KCP; full play and actual game UI remain
 * unverified.
 */
public final class NativeClockParkDiceProbe {
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

  private static int chapter(int script) {
    return new int[] {101, 201, 301, 410, 510}[script - 1];
  }

  private static int rounds(int script) {
    return new int[] {3, 4, 4, 8, 4}[script - 1];
  }

  private List<Integer> faces(DynamicMessage response) {
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

  private void verify(DynamicMessage response, int script) {
    require(number(response, "script_id") == script, "Wrong active script");
    if (script == 0) {
      require(((String) field(response, "IMJFLCKFJGF")).isEmpty(), "Idle transaction exists");
      return;
    }
    int index = number(response, "JIPPMKBDJJC");
    require(
        index >= 0
            && index < rounds(script)
            && number(response, "DCAHJELNJNP") == chapter(script)
            && number(response, "INDJEOAJJEN") == 0,
        "Round index/chapter/checkpoint differs");
    require(
        number(nested(response, "HGDLJDAFHLI"), "JGPHDBDHKKO") == chapter(script) * 100 + 1 + index,
        "Foreign original round");
    var dice = faces(response);
    require(
        dice.size() == 3 && dice.stream().allMatch(n -> n >= 1 && n <= 6),
        "Invalid saved server dice");
    var rows =
        (List<?>)
            field(
                nested(nested(nested(response, "HGDLJDAFHLI"), "FDNLIPKGBLM"), "CENIOBBIJMF"),
                "LONFKFCFDND");
    require(rows.size() == 3, "Wrong project pool size");
    for (int i = 0; i < 3; i++) {
      var row = (DynamicMessage) rows.get(i);
      require(
          number(row, "card_id") == 1001 + i
              && number(row, "NPCBECNBIJK") == 0
              && ((List<?>) field(row, "KCNEKEGGAGG")).isEmpty(),
          "Foreign or prefilled card");
    }
    require(!((String) field(response, "IMJFLCKFJGF")).isBlank(), "No transaction");
  }

  private Map<String, Object> placement(
      int script, int round, List<Integer> savedFaces, boolean forged) {
    var dice = request("IIMHLPBEDDH", Map.of());
    for (int i = 0; i < 3; i++) {
      var row = request("NIJHCCNMEGA", Map.of("card_id", 1001 + i));
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
    int previous = input.get("previousScript").getAsInt(),
        next = input.get("nextScript").getAsInt();
    var initial = call("ClockParkGetOngoingScriptInfoCsReq");
    verify(initial, previous);
    if (previous > 0) {
      require(
          field(initial, "IMJFLCKFJGF").equals(input.get("transaction").getAsString()),
          "Restart transaction changed");
      require(
          digest(initial).equals(input.get("ongoingSha256").getAsString()),
          "Restart changed pending dice or effects");
    }
    if (input.get("locked").getAsBoolean())
      call("ClockParkStartScriptCsReq", Map.of("script_id", 1), Retcode.FUNC_CHECK_FAILED.getVal());
    if (previous > 0 && input.get("quitPrevious").getAsBoolean()) {
      call(
          "ClockParkQuitScriptCsReq",
          Map.of("CNHLAAHDPGA", previous == 5 ? 1 : previous + 1),
          Retcode.REQ_PARA_INVALID.getVal());
      call(
          "ClockParkQuitScriptCsReq",
          Map.of("CNHLAAHDPGA", previous, "MHICFGAPPNB", input.get("manual").getAsBoolean()),
          0);
      verify(call("ClockParkGetOngoingScriptInfoCsReq"), 0);
      call(
          "ClockParkQuitScriptCsReq",
          Map.of("CNHLAAHDPGA", previous),
          Retcode.REQ_PARA_INVALID.getVal());
    }
    int steps = 0;
    if (next > 0) {
      var begun = call("ClockParkStartScriptCsReq", Map.of("script_id", next), 0);
      require(number(begun, "script_id") == next, "Wrong begin script");
      var story = call("ClockParkGetOngoingScriptInfoCsReq");
      require(
          nested(story, "HGDLJDAFHLI")
              .hasField(
                  nested(story, "HGDLJDAFHLI")
                      .getDescriptorForType()
                      .findFieldByName("ICPMABKBLCD")),
          "Missing original opening story");
      var finish =
          Map.<String, Object>of(
              "script_id",
              next,
              "JGPHDBDHKKO",
              chapter(next) * 100 + 1,
              "IKNEKEJEGLM",
              request("NKOCCGLHOML", Map.of()).build());
      call("ClockParkHandleWaitOperationCsReq", finish, 0);
      var current = call("ClockParkGetOngoingScriptInfoCsReq");
      verify(current, next);
      call("ClockParkHandleWaitOperationCsReq", finish, Retcode.REQ_PARA_INVALID.getVal());
      require(
          current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
          "Duplicate story rerolled dice");
      call(
          "ClockParkHandleWaitOperationCsReq",
          placement(next, chapter(next) * 100 + 1, faces(current), true),
          Retcode.REQ_PARA_INVALID.getVal());
      require(
          current.equals(call("ClockParkGetOngoingScriptInfoCsReq")), "Unowned face changed state");
      for (int i = 0; i < rounds(next) - 1; i++) {
        var before = current;
        var dice = faces(before);
        int round = number(nested(before, "HGDLJDAFHLI"), "JGPHDBDHKKO");
        var attrs = nested(before, "JOFJEIFLGBP");
        int cap = next == 1 ? 50 : next == 4 ? 60 : 100;
        var ack = call("ClockParkHandleWaitOperationCsReq", placement(next, round, dice, false), 0);
        steps++;
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        verify(current, next);
        require(
            number(ack, "JGPHDBDHKKO") == round + 1 && number(current, "JIPPMKBDJJC") == i + 1,
            "Original next round not acknowledged");
        var updated = nested(current, "JOFJEIFLGBP");
        require(
            number(updated, "JEKOHIHJFMK")
                    == Math.min(cap, number(attrs, "JEKOHIHJFMK") + dice.get(0) + dice.get(2))
                && number(updated, "AKKLILNJCPL")
                    == Math.min(cap, number(attrs, "AKKLILNJCPL") + dice.get(1))
                && number(updated, "PNOIJPDMCPG") == 0,
            "Original effects differ from saved faces");
        require(
            field(before, "IMJFLCKFJGF").equals(field(current, "IMJFLCKFJGF")),
            "Placement changed transaction");
        call(
            "ClockParkHandleWaitOperationCsReq",
            placement(next, round, dice, false),
            Retcode.REQ_PARA_INVALID.getVal());
        require(
            current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
            "Replayed placement changed state");
      }
      if (next <= 3)
        call(
            "ClockParkHandleWaitOperationCsReq",
            Map.of(
                "script_id",
                next,
                "JGPHDBDHKKO",
                chapter(next) * 100 + rounds(next),
                "MLHNHAFPLKD",
                request("MOEMKBLCKKO", Map.of()).build()),
            Retcode.REQ_PARA_INVALID.getVal());
      else
        call(
            "ClockParkHandleWaitOperationCsReq",
            placement(next, chapter(next) * 100 + rounds(next), faces(current), false),
            Retcode.FEATURE_SWITCH_CLOSED.getVal());
      require(
          current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
          "Wrong-phase checkpoint or unsupported game changed state");
      call("ClockParkStartScriptCsReq", Map.of("script_id", next), Retcode.SYSTEM_BUSY.getVal());
    }
    int active = next > 0 ? next : input.get("quitPrevious").getAsBoolean() ? 0 : previous;
    var current = call("ClockParkGetOngoingScriptInfoCsReq");
    verify(current, active);
    require(current.equals(call("ClockParkGetOngoingScriptInfoCsReq")), "Query changed saved dice");
    var catalog = call("ClockParkGetInfoCsReq");
    int reached = input.get("reachedScripts").getAsInt();
    require(
        number(catalog, "LNKAKGOCAOF") == active
            && number(catalog, "progress") == 0
            && number(catalog, "CDGICHDIIHH") == 0
            && ((List<?>) field(catalog, "HPEKLHNPDAJ")).isEmpty(),
        "Manufactured progress/talents/score");
    for (Object raw : (List<?>) field(catalog, "DCGDHFJLHCN")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "script_id");
      require(
          field(row, "CFJOGMJIHOE").equals(id <= reached ? List.of(chapter(id)) : List.of()),
          "Foreign reached receipt");
    }
    var output = new LinkedHashMap<String, Object>();
    output.put("passed", true);
    output.put("uid", uid);
    output.put("requests", requests);
    output.put("activeScript", active);
    output.put("reachedScripts", reached);
    output.put("transaction", field(current, "IMJFLCKFJGF"));
    output.put("ongoingSha256", digest(current));
    output.put("stepsApplied", steps);
    output.put("roundIndex", active == 0 ? 0 : number(current, "JIPPMKBDJJC"));
    output.put("pendingDice", active == 0 ? List.of() : faces(current));
    output.put(
        "attributeA", active == 0 ? 0 : number(nested(current, "JOFJEIFLGBP"), "JEKOHIHJFMK"));
    output.put(
        "attributeB", active == 0 ? 0 : number(nested(current, "JOFJEIFLGBP"), "AKKLILNJCPL"));
    output.put(
        "attributeC", active == 0 ? 0 : number(nested(current, "JOFJEIFLGBP"), "PNOIJPDMCPG"));
    output.put("scriptsExecuted", false);
    output.put("clientGameplayVerified", false);
    output.put("officialRoundCardPoolVerified", false);
    output.put("placementUsesDiceFaces", true);
    return output;
  }

  public static void main(String[] args) {
    var probe = new NativeClockParkDiceProbe();
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
