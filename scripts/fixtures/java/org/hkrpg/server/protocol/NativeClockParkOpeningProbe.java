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
 * Saved ClockPark openings over synthetic UDP/KCP; full play and actual game UI remain unverified.
 */
public final class NativeClockParkOpeningProbe {
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
    verifyOngoing(initial, previous);
    if (previous > 0 && input.has("transaction"))
      require(
          field(initial, "IMJFLCKFJGF").equals(input.get("transaction").getAsString()),
          "Restart replaced transaction");
    if (input.get("locked").getAsBoolean()) {
      call("ClockParkStartScriptCsReq", Map.of("script_id", 1), Retcode.FUNC_CHECK_FAILED.getVal());
    }
    if (previous > 0 && input.get("quitPrevious").getAsBoolean()) {
      call(
          "ClockParkQuitScriptCsReq",
          Map.of("CNHLAAHDPGA", previous == 5 ? 1 : previous + 1, "MHICFGAPPNB", true),
          Retcode.REQ_PARA_INVALID.getVal());
      call(
          "ClockParkQuitScriptCsReq",
          Map.of("CNHLAAHDPGA", previous, "MHICFGAPPNB", input.get("manual").getAsBoolean()),
          0);
      verifyOngoing(call("ClockParkGetOngoingScriptInfoCsReq"), 0);
      call(
          "ClockParkQuitScriptCsReq",
          Map.of("CNHLAAHDPGA", previous),
          Retcode.REQ_PARA_INVALID.getVal());
    }
    if (next > 0) {
      var begun = call("ClockParkStartScriptCsReq", Map.of("script_id", next), 0);
      require(number(begun, "script_id") == next, "Start returned foreign script");
      call("ClockParkStartScriptCsReq", Map.of("script_id", next), Retcode.SYSTEM_BUSY.getVal());
    }
    int active = next > 0 ? next : input.get("quitPrevious").getAsBoolean() ? 0 : previous;
    var current = call("ClockParkGetOngoingScriptInfoCsReq");
    verifyOngoing(current, active);
    var repeat = call("ClockParkGetOngoingScriptInfoCsReq");
    require(current.equals(repeat), "Query altered saved opening");
    if (active > 0) {
      int chapter = new int[] {101, 201, 301, 410, 510}[active - 1];
      call(
          "ClockParkHandleWaitOperationCsReq",
          Map.of(
              "script_id",
              active,
              "JGPHDBDHKKO",
              chapter * 100 + 1,
              "MLHNHAFPLKD",
              request("MOEMKBLCKKO", Map.of()).build()),
          Retcode.REQ_PARA_INVALID.getVal());
      require(
          current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
          "Unsupported checkpoint operation changed opening");
    }
    var catalog = call("ClockParkGetInfoCsReq");
    require(number(catalog, "LNKAKGOCAOF") == active, "Current catalog script differs");
    int reached = input.get("reachedScripts").getAsInt();
    var ids = new ArrayList<Integer>();
    for (Object raw : (List<?>) field(catalog, "DCGDHFJLHCN")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "script_id");
      ids.add(id);
      require(
          field(row, "CFJOGMJIHOE")
              .equals(
                  id <= reached ? List.of(new int[] {101, 201, 301, 410, 510}[id - 1]) : List.of()),
          "Fabricated reached receipt");
    }
    require(
        ids.equals(List.of(1, 2, 3, 4, 5, 6))
            && number(catalog, "progress") == 0
            && number(catalog, "CDGICHDIIHH") == 0
            && ((List<?>) field(catalog, "HPEKLHNPDAJ")).isEmpty(),
        "Invented score, ending or talent");
    rawCall(
        "ClockParkStartScriptCsReq", new byte[] {64, 1, 64, 1}, Retcode.REQ_PARA_INVALID.getVal());
    check(true, "Native saved opening, repeat query, rejection and exit checked");
    var output = new LinkedHashMap<String, Object>();
    output.put("passed", true);
    output.put("uid", uid);
    output.put("requests", requests);
    output.put("activeScript", active);
    output.put("reachedScripts", reached);
    output.put("checks", checks);
    output.put("transaction", field(current, "IMJFLCKFJGF"));
    output.put(
        "ongoingSha256",
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(current.toByteArray())));
    output.put("scriptsExecuted", false);
    output.put("clientGameplayVerified", false);
    return output;
  }

  private void verifyOngoing(DynamicMessage response, int script) {
    require(number(response, "script_id") == script, "Unexpected ongoing script");
    if (script == 0) {
      require(((String) field(response, "IMJFLCKFJGF")).isEmpty(), "Idle has run transaction");
      return;
    }
    int chapter = new int[] {101, 201, 301, 410, 510}[script - 1];
    require(
        number(response, "DCAHJELNJNP") == chapter
            && field(response, "FKNBOIIMFLD").equals(List.of(chapter)),
        "Chapter/path differs");
    var waiting = (DynamicMessage) field(response, "HGDLJDAFHLI");
    require(
        number(waiting, "JGPHDBDHKKO") == chapter * 100 + 1
            && waiting.hasField(waiting.getDescriptorForType().findFieldByName("ICPMABKBLCD")),
        "Not original first story waiting state");
    require(!((String) field(response, "IMJFLCKFJGF")).isBlank(), "Missing run transaction");
    var attributes = (DynamicMessage) field(response, "JOFJEIFLGBP");
    require(
        number(attributes, "JEKOHIHJFMK") == 0
            && number(attributes, "AKKLILNJCPL") == 0
            && number(attributes, "PNOIJPDMCPG") == 0,
        "Opening project rule differs");
  }

  public static void main(String[] args) {
    var probe = new NativeClockParkOpeningProbe();
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
