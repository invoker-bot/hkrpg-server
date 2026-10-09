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
 * Original true ending, unique item and normal H2 recovery over synthetic UDP/KCP; actual UI
 * remains unverified.
 */
public final class NativeClockParkMissionProgressProbe {
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

  private void verify(DynamicMessage response, int script) {
    require(number(response, "script_id") == script, "Wrong active script");
    if (script == 0) {
      require(((String) field(response, "IMJFLCKFJGF")).isEmpty(), "Idle transaction exists");
      return;
    }
    int index = number(response, "JIPPMKBDJJC");
    if (script <= 3 && index == rounds(script)) {
      require(
          number(response, "DCAHJELNJNP") == chapter(script)
              && number(response, "INDJEOAJJEN") == new int[] {111, 211, 311}[script - 1],
          "Foreign checkpoint");
      var wait = nested(response, "HGDLJDAFHLI");
      require(
          wait.hasField(wait.getDescriptorForType().findFieldByName("BELJJJLLCBK")),
          "Missing checkpoint wait");
      require(
          number(wait, "JGPHDBDHKKO") == chapter(script) * 100 + rounds(script),
          "Checkpoint replaced final original round");
      var attributes = nested(response, "JOFJEIFLGBP");
      boolean passed =
          script == 1
              ? number(attributes, "JEKOHIHJFMK") >= 15
              : script == 2
                  ? number(attributes, "AKKLILNJCPL") >= 30
                  : number(attributes, "JEKOHIHJFMK") >= 30;
      require(
          (Boolean) field(nested(wait, "BELJJJLLCBK"), "OEKKAJJEGAK") == passed,
          "Checkpoint bypassed saved attributes");
      require(!((String) field(response, "IMJFLCKFJGF")).isBlank(), "Checkpoint lost transaction");
      return;
    }
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
                == Math.min(cap, number(before, "JEKOHIHJFMK") + dice.get(0) + dice.get(2))
            && number(attrs, "AKKLILNJCPL")
                == Math.min(cap, number(before, "AKKLILNJCPL") + dice.get(1)),
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

  private DynamicMessage reachEndingBranch(int[] counters) throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      call("ClockParkStartScriptCsReq", Map.of("script_id", 1), 0);
      counters[0]++;
      var current = call("ClockParkGetOngoingScriptInfoCsReq");
      boolean failed = false;
      for (int chapter : List.of(101, 102)) {
        require(
            phase(current).equals("STORY") && number(current, "DCAHJELNJNP") == chapter,
            "Missing original chapter story");
        call(
            "ClockParkHandleWaitOperationCsReq", confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML"), 0);
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        for (int i = 0; i < (chapter == 101 ? 3 : 4); i++) {
          current = normal(current);
          counters[1]++;
        }
        require(phase(current).equals("CHECKPOINT"), "Missing original checkpoint");
        var operation = confirm(current, "MLHNHAFPLKD", "MOEMKBLCKKO");
        if (!(Boolean)
            field(nested(nested(current, "HGDLJDAFHLI"), "BELJJJLLCBK"), "OEKKAJJEGAK")) {
          counters[2]++;
          var ack = call("ClockParkHandleWaitOperationCsReq", operation, 0);
          require(
              ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                      .getNumber()
                  == 1,
              "Missing native death status");
          var result = nested(resultNotification(), "NLEOMLNOKCC");
          require(
              field(result, "HGPKMHFPMBJ").equals(true)
                  && field(result, "DMNEACGEHEC").equals(false)
                  && number(result, "progress") == 0
                  && number(result, "script_id") == 1
                  && number(result, "DCAHJELNJNP") == chapter
                  && ((List<?>) field(nested(result, "reward"), "item_list")).isEmpty(),
              "Failure created an ending/reward");
          require(
              phase(call("ClockParkGetOngoingScriptInfoCsReq")).equals("IDLE"),
              "Failure did not close run");
          failed = true;
          break;
        }
        call("ClockParkHandleWaitOperationCsReq", operation, 0);
        current = call("ClockParkGetOngoingScriptInfoCsReq");
        require(
            phase(current).equals("BRANCH") && pending(current).size() == 1,
            "Missing original branch");
        var rows =
            (List<?>)
                field(
                    nested(nested(nested(current, "HGDLJDAFHLI"), "ABIPNABIDPE"), "CENIOBBIJMF"),
                    "LONFKFCFDND");
        require(
            rows.size() == 1
                && number((DynamicMessage) rows.getFirst(), "card_id")
                    == (chapter == 101 ? 1101 : 1102),
            "Wrong branch card");
        if (chapter == 101) {
          call(
              "ClockParkHandleWaitOperationCsReq",
              select(current, 1101, pending(current).getFirst()),
              0);
          current = call("ClockParkGetOngoingScriptInfoCsReq");
        }
      }
      if (!failed) return current;
    }
    throw new IllegalStateException("No naturally passed two-chapter run in bounded attempts");
  }

  private DynamicMessage endingStory(DynamicMessage current) throws Exception {
    var before = nested(current, "JOFJEIFLGBP");
    var transaction = field(current, "IMJFLCKFJGF");
    var operation = select(current, 1102, pending(current).getFirst());
    call(
        "ClockParkHandleWaitOperationCsReq", select(current, 1101, pending(current).getFirst()), 5);
    require(
        current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
        "Foreign card changed ending branch");
    call("ClockParkHandleWaitOperationCsReq", operation, 0);
    var next = call("ClockParkGetOngoingScriptInfoCsReq");
    require(
        phase(next).equals("STORY")
            && number(next, "DCAHJELNJNP") == 103
            && field(next, "FKNBOIIMFLD").equals(List.of(101, 102, 103))
            && number(nested(next, "HGDLJDAFHLI"), "JGPHDBDHKKO") == 0,
        "Ending skipped original story wait");
    require(
        before.equals(nested(next, "JOFJEIFLGBP"))
            && transaction.equals(field(next, "IMJFLCKFJGF")),
        "Ending reset attributes/UUID");
    call("ClockParkHandleWaitOperationCsReq", operation, 5);
    require(
        next.equals(call("ClockParkGetOngoingScriptInfoCsReq")), "Replayed branch changed story");
    return next;
  }

  private Map<String, Object> finishEnding(DynamicMessage current, boolean first) throws Exception {
    var operation = confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML");
    var ack = call("ClockParkHandleWaitOperationCsReq", operation, 0);
    require(
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                .getNumber()
            == 5,
        "Ending acknowledgment lacks native finished status");
    var notification = resultNotification();
    require(
        notification.getUnknownFields().asMap().isEmpty(), "Unknown finish notification fields");
    var result = nested(notification, "NLEOMLNOKCC");
    require(
        number(result, "script_id") == 1
            && number(result, "DCAHJELNJNP") == 103
            && number(result, "progress") == (first ? 2000 : 0)
            && field(result, "DMNEACGEHEC").equals(first)
            && field(result, "HGPKMHFPMBJ").equals(false),
        "Wrong authored ending result");
    var rewards = (List<?>) field(nested(result, "reward"), "item_list");
    require(rewards.size() == (first ? 1 : 0), "Repeated or missing reward display");
    if (first)
      require(
          number((DynamicMessage) rewards.getFirst(), "item_id") == 140484
              && number((DynamicMessage) rewards.getFirst(), "num") == 1,
          "Wrong authored unique reward");
    call("ClockParkHandleWaitOperationCsReq", operation, 5);
    require(
        itemCount() == 1 && phase(call("ClockParkGetOngoingScriptInfoCsReq")).equals("IDLE"),
        "Ending did not finish/credit once");
    return Map.of(
        "newEnding",
        first,
        "progressAdded",
        first ? 2000 : 0,
        "rewardRows",
        rewards.size(),
        "ackStatus",
        5);
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
        "H2 restart changed query");
    require(
        number(current, "script_id") == input.get("previousScript").getAsInt(),
        "Restart changed script");
    String action = input.get("action").getAsString();
    int[] counters = {input.get("runsStarted").getAsInt(), 0, 0};
    Map<String, Object> result = Map.of();
    if (action.equals("locked"))
      call("ClockParkStartScriptCsReq", Map.of("script_id", 1), Retcode.FUNC_CHECK_FAILED.getVal());
    else if (action.equals("branch")) current = reachEndingBranch(counters);
    else if (action.equals("story")) current = endingStory(current);
    else if (action.equals("finish") || action.equals("recover")) {
      require(
          phase(current).equals("STORY") && number(current, "DCAHJELNJNP") == 103,
          "No restored ending story");
      result = finishEnding(current, true);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
    } else if (action.equals("repeat")) {
      current = endingStory(reachEndingBranch(counters));
      result = finishEnding(current, false);
      current = call("ClockParkGetOngoingScriptInfoCsReq");
    } else require(action.equals("reload"), "Unknown action");
    var info = call("ClockParkGetInfoCsReq");
    int item = itemCount();
    int expectedItem = input.get("expectedItem").getAsInt(),
        expectedProgress = input.get("expectedProgress").getAsInt();
    require(
        item == expectedItem && number(info, "progress") == expectedProgress,
        "Real inventory/progress differs");
    var reached = new TreeMap<Integer, Object>();
    for (var raw : (List<?>) field(info, "DCGDHFJLHCN")) {
      var row = (DynamicMessage) raw;
      reached.put(number(row, "script_id"), field(row, "CFJOGMJIHOE"));
    }
    boolean goalFinished = false;
    if (!action.equals("locked")) {
      var missions =
          call("GetMissionStatusCsReq", Map.of("sub_mission_id_list", List.of(802210103)), 0);
      var targets =
          ((List<?>) field(missions, "sub_mission_status_list"))
              .stream()
                  .map(DynamicMessage.class::cast)
                  .filter(s -> number(s, "id") == 802210103)
                  .toList();
      require(targets.size() == 1, "Original progress target response missing");
      var statusName =
          ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                  field(targets.getFirst(), "status"))
              .getName();
      goalFinished = statusName.equals("MissionStatus_MissionFinish");
      require(
          goalFinished == (expectedProgress >= 2000),
          "Story progress target differs from settled ending");
      require(
          goalFinished || statusName.equals("MissionStatus_MissionDoing"),
          "Accepted progress target lost its doing state");
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("passed", true);
    out.put("uid", uid);
    out.put("requests", requests);
    out.put("runsStarted", counters[0]);
    out.put("placementsApplied", counters[1]);
    out.put("naturallyFailedCheckpoints", counters[2]);
    out.put("activeScript", number(current, "script_id"));
    out.put("chapter", number(current, "DCAHJELNJNP"));
    out.put("roundIndex", number(current, "JIPPMKBDJJC"));
    out.put("phase", phase(current));
    out.put("pendingDice", pending(current));
    out.put("reachedChapters", reached);
    out.put("transaction", field(current, "IMJFLCKFJGF"));
    out.put("ongoingSha256", digest(current));
    out.put("actualUniqueItemCount", item);
    out.put("progress", number(info, "progress"));
    out.put("storyGoalFinished", goalFinished);
    out.put("endingResult", result);
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
    var probe = new NativeClockParkMissionProgressProbe();
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
