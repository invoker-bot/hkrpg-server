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
public final class NativeClockParkFifthEndingProbe {
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
    var dice = assignedFaces(current);
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
      if (number(item, "tid") == 140485) {
        count += number(item, "num");
        rows++;
      }
    }
    require(rows <= 1 && count >= 0 && count <= 1, "Invalid actual unique item inventory");
    return count;
  }

  private List<Integer> assignedFaces(DynamicMessage current) {
    var saved = faces(current);
    require(saved.size() == 3, "Missing three actual owned dice");
    var before = nested(current, "JOFJEIFLGBP");
    List<Integer> chosen = null;
    int bestSum = -1, bestLowest = -1;
    for (int i = 0; i < 3; i++)
      for (int j = 0; j < 3; j++)
        for (int k = 0; k < 3; k++) {
          if (i == j || j == k || i == k) continue;
          var candidate = List.of(saved.get(i), saved.get(j), saved.get(k));
          int a =
              Math.min(100, number(before, "JEKOHIHJFMK") + candidate.get(0) + candidate.get(1));
          int b =
              Math.min(100, number(before, "AKKLILNJCPL") + candidate.get(1) + candidate.get(2));
          int c = Math.min(100, number(before, "PNOIJPDMCPG") + candidate.get(2));
          int sum = a + b + c, lowest = Math.min(a, Math.min(b, c));
          if (sum > bestSum || sum == bestSum && lowest > bestLowest) {
            chosen = candidate;
            bestSum = sum;
            bestLowest = lowest;
          }
        }
    require(
        saved.stream().sorted().toList().equals(chosen.stream().sorted().toList()),
        "Dice assignment changed the owned multiset");
    return chosen;
  }

  private DynamicMessage slot(DynamicMessage current) {
    return nested(nested(current, "HGDLJDAFHLI"), "HGHBDACDJKP");
  }

  private Map<String, Object> gacha(DynamicMessage current, boolean draw, int ticket) {
    return Map.of(
        "script_id",
        5,
        "JGPHDBDHKKO",
        number(nested(current, "HGDLJDAFHLI"), "JGPHDBDHKKO"),
        "EJLKEGLKGDN",
        request("IGJLLHMJKPC", Map.of("IMKGOKGKDPH", draw, "gacha_random", ticket)).build());
  }

  private int startedRuns, placements, lastLottery, lastTicket;
  private boolean failed;
  private String failedTransaction = "";

  private boolean passed(DynamicMessage current) {
    return (Boolean) field(nested(nested(current, "HGDLJDAFHLI"), "BELJJJLLCBK"), "OEKKAJJEGAK");
  }

  private void verifyCheckpoint(DynamicMessage current) {
    require(phase(current).equals("CHECKPOINT"), "Missing actual slot checkpoint");
    int point = number(current, "INDJEOAJJEN");
    int threshold =
        switch (point) {
          case 501 -> 95;
          case 502 -> 150;
          case 503 -> 250;
          default -> throw new IllegalStateException("Foreign checkpoint");
        };
    var attrs = nested(current, "JOFJEIFLGBP");
    require(
        passed(current)
            == (number(attrs, "JEKOHIHJFMK")
                    + number(attrs, "AKKLILNJCPL")
                    + number(attrs, "PNOIJPDMCPG")
                >= threshold),
        "Original sum condition differs");
  }

  private DynamicMessage normals(DynamicMessage current, int count) throws Exception {
    require(phase(current).equals("STORY"), "Missing restored original chapter story");
    call("ClockParkHandleWaitOperationCsReq", confirm(current, "IKNEKEJEGLM", "NKOCCGLHOML"), 0);
    current = call("ClockParkGetOngoingScriptInfoCsReq");
    for (int i = 0; i < count; i++) {
      current = normal(current);
      placements++;
    }
    require(phase(current).equals("SLOT"), "Original chapter game skipped");
    var game = slot(current);
    lastTicket = number(game, "gacha_random");
    lastLottery = 0;
    int chapter = number(current, "DCAHJELNJNP");
    int card =
        switch (chapter) {
          case 510 -> 5150;
          case 520 -> 5250;
          case 530 -> 5350;
          case 540 -> 5450;
          default -> throw new IllegalStateException("Foreign game chapter");
        };
    var dice = nested(game, "CENIOBBIJMF");
    var cards = (List<?>) field(dice, "LONFKFCFDND");
    require(
        cards.size() == 1 && ((List<?>) field(dice, "KCNEKEGGAGG")).isEmpty(),
        "Wrong game card/dice count");
    var row = (DynamicMessage) cards.getFirst();
    require(
        number(row, "card_id") == card
            && number(row, "NPCBECNBIJK") == 1
            && field(row, "KCNEKEGGAGG").equals(List.of(1)),
        "Wrong prefilled original chapter card");
    require(
        lastTicket >= 0 && lastTicket < 1000 && number(game, "NDNHCFBFPMH") == 1,
        "Wrong owned ticket/play count");
    var foreign = gacha(current, true, (lastTicket + 1) % 1000);
    call("ClockParkHandleWaitOperationCsReq", foreign, 5);
    require(
        current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
        "Foreign ticket changed owned game");
    return current;
  }

  private DynamicMessage draw(DynamicMessage current) throws Exception {
    require(phase(current).equals("SLOT"), "Missing restored pending chapter game");
    lastTicket = number(slot(current), "gacha_random");
    var before = nested(current, "JOFJEIFLGBP");
    var op = gacha(current, true, lastTicket);
    var ack = call("ClockParkHandleWaitOperationCsReq", op, 0);
    lastLottery = lastTicket == 0 ? 42 : lastTicket < 10 ? 32 : lastTicket < 650 ? 22 : 12;
    require(
        number(ack, "IGBHCBNKEBO") == lastLottery && number(ack, "DMNECEKOLAJ") == 0,
        "Wrong native lottery field8");
    current = call("ClockParkGetOngoingScriptInfoCsReq");
    var after = nested(current, "JOFJEIFLGBP");
    int gain = lastLottery == 42 ? 4 : lastLottery == 32 ? 6 : lastLottery == 22 ? 8 : 12;
    require(
        number(after, "AKKLILNJCPL") == Math.min(100, number(before, "AKKLILNJCPL") + gain)
            && number(after, "JEKOHIHJFMK") == number(before, "JEKOHIHJFMK")
            && number(after, "PNOIJPDMCPG") == number(before, "PNOIJPDMCPG"),
        "Wrong original zero-cost gain");
    require(number(slot(current), "NDNHCFBFPMH") == 0, "Draw was not consumed");
    var replay = call("ClockParkHandleWaitOperationCsReq", op, 0);
    require(
        number(replay, "IGBHCBNKEBO") == lastLottery
            && current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
        "Cached draw duplicated attributes");
    return current;
  }

  private DynamicMessage exitSlot(DynamicMessage current) throws Exception {
    lastTicket = number(slot(current), "gacha_random");
    var before = nested(current, "JOFJEIFLGBP");
    var op = gacha(current, false, lastTicket);
    call("ClockParkHandleWaitOperationCsReq", op, 0);
    current = call("ClockParkGetOngoingScriptInfoCsReq");
    require(before.equals(nested(current, "JOFJEIFLGBP")), "Exit changed attributes");
    verifyCheckpoint(current);
    call("ClockParkHandleWaitOperationCsReq", op, 5);
    require(
        current.equals(call("ClockParkGetOngoingScriptInfoCsReq")),
        "Exit replay changed checkpoint");
    return current;
  }

  private void fail(DynamicMessage current) throws Exception {
    verifyCheckpoint(current);
    require(!passed(current), "Failed a passed condition");
    failedTransaction = (String) field(current, "IMJFLCKFJGF");
    var ack =
        call(
            "ClockParkHandleWaitOperationCsReq", confirm(current, "MLHNHAFPLKD", "MOEMKBLCKKO"), 0);
    require(
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                .getNumber()
            == 1,
        "Missing real failure acknowledgement");
    var notify = nested(resultNotification(), "NLEOMLNOKCC");
    require(
        field(notify, "HGPKMHFPMBJ").equals(true)
            && number(notify, "progress") == 0
            && ((List<?>) field(nested(notify, "reward"), "item_list")).isEmpty(),
        "Failure credited an ending");
    require(
        phase(call("ClockParkGetOngoingScriptInfoCsReq")).equals("IDLE"),
        "Failure retained active run");
    failed = true;
  }

  private DynamicMessage firstPoint() throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      call("ClockParkStartScriptCsReq", Map.of("script_id", 5), 0);
      startedRuns++;
      var current = call("ClockParkGetOngoingScriptInfoCsReq");
      current = exitSlot(draw(normals(current, 4)));
      if (passed(current)) return current;
      fail(current);
    }
    throw new IllegalStateException("No naturally passed original501 within bounded attempts");
  }

  private DynamicMessage offer(DynamicMessage current) throws Exception {
    verifyCheckpoint(current);
    require(passed(current), "Cannot offer branch from failed condition");
    var ack =
        call(
            "ClockParkHandleWaitOperationCsReq", confirm(current, "MLHNHAFPLKD", "MOEMKBLCKKO"), 0);
    require(
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(ack, "LNPGHCLDOKG"))
                .getNumber()
            == 2,
        "Wrong branch acknowledgement");
    current = call("ClockParkGetOngoingScriptInfoCsReq");
    require(
        phase(current).equals("BRANCH") && pending(current).size() == 1,
        "Missing owned branch face");
    return current;
  }

  private DynamicMessage selectCurrent(DynamicMessage current, int card) throws Exception {
    var attrs = nested(current, "JOFJEIFLGBP");
    String uuid = (String) field(current, "IMJFLCKFJGF");
    int face = pending(current).getFirst();
    var op = select(current, card, face);
    call("ClockParkHandleWaitOperationCsReq", op, 0);
    current = call("ClockParkGetOngoingScriptInfoCsReq");
    int expected =
        switch (card) {
          case 5119 -> 520;
          case 5221 -> face <= 2 ? 540 : 530;
          case 5327 -> 550;
          case 5427 -> 560;
          default -> throw new IllegalStateException("Foreign branch card");
        };
    require(
        phase(current).equals("STORY")
            && number(current, "DCAHJELNJNP") == expected
            && attrs.equals(nested(current, "JOFJEIFLGBP"))
            && uuid.equals(field(current, "IMJFLCKFJGF")),
        "Original branch target/attributes/UUID differs");
    call("ClockParkHandleWaitOperationCsReq", op, 5);
    require(
        current.equals(call("ClockParkGetOngoingScriptInfoCsReq")), "Branch replay changed story");
    return current;
  }

  private DynamicMessage secondPoint() throws Exception {
    for (int attempt = 0; attempt < 20; attempt++) {
      var current = selectCurrent(offer(firstPoint()), 5119);
      current = exitSlot(draw(normals(current, 4)));
      if (passed(current)) return current;
      fail(current);
    }
    throw new IllegalStateException("No naturally passed original502 within bounded complete runs");
  }

  private DynamicMessage reach(boolean good) throws Exception {
    for (int attempt = 0; attempt < 120; attempt++) {
      var current = offer(secondPoint());
      if ((pending(current).getFirst() >= 3) != good) {
        call("ClockParkQuitScriptCsReq", Map.of("CNHLAAHDPGA", 5), 0);
        continue;
      }
      current = selectCurrent(current, 5221);
      current = exitSlot(draw(normals(current, 5)));
      if (!passed(current)) {
        fail(current);
        continue;
      }
      return selectCurrent(offer(current), good ? 5327 : 5427);
    }
    throw new IllegalStateException(
        "No desired original fifth ending naturally reached within bounded complete runs");
  }

  private DynamicMessage finish(DynamicMessage current, boolean good, boolean first)
      throws Exception {
    require(
        phase(current).equals("STORY") && number(current, "DCAHJELNJNP") == (good ? 550 : 560),
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
        number(result, "script_id") == 5 && number(result, "DCAHJELNJNP") == (good ? 550 : 560),
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
          number(item, "item_id") == 140485 && number(item, "num") == 1,
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
    startedRuns = input.get("runsStarted").getAsInt();
    failed = input.get("failureCompleted").getAsBoolean();
    failedTransaction = input.get("failureTransaction").getAsString();
    boolean trueCompleted = input.get("trueCompleted").getAsBoolean(),
        falseCompleted = input.get("falseCompleted").getAsBoolean();
    String action = input.get("action").getAsString();
    if (action.equals("locked"))
      call("ClockParkStartScriptCsReq", Map.of("script_id", 5), Retcode.FUNC_CHECK_FAILED.getVal());
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
    out.put("runsStarted", startedRuns);
    out.put("placementsApplied", placements);
    out.put("activeScript", number(current, "script_id"));
    out.put("chapter", number(current, "DCAHJELNJNP"));
    out.put("roundIndex", number(current, "JIPPMKBDJJC"));
    out.put("phase", phase(current));
    out.put("pendingDice", pending(current));
    out.put("reachedChapters", reached);
    out.put("transaction", field(current, "IMJFLCKFJGF"));
    out.put("ongoingSha256", digest(current));
    out.put("failureCompleted", failed);
    out.put("failureTransaction", failedTransaction);
    out.put("actualUniqueItemCount", count);
    out.put("progress", progress);
    out.put("trueCompleted", trueCompleted);
    out.put("falseCompleted", falseCompleted);
    out.put("projectPool", List.of(1001, 1007, 1008));
    out.put("ownedDiceAssignmentStrategy", "maximize-capped-sum-then-lowest-attribute.v1");
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
    var probe = new NativeClockParkFifthEndingProbe();
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
