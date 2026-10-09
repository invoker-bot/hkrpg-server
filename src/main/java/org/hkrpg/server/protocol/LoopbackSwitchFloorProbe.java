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
 * Original 4340104 named choice, two outgoing/returning floors, books and actual message tail
 * rewards.
 */
public final class LoopbackSwitchFloorProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
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

  private boolean bookNotify, tailNotify;
  private int observedWallet = -1;

  private Map<Integer, Integer> items(DynamicMessage list) {
    var values = new TreeMap<Integer, Integer>();
    require(list.getUnknownFields().asMap().isEmpty(), "Unknown source reward list fields");
    for (var raw : (List<?>) field(list, "item_list")) {
      var item = (DynamicMessage) raw;
      require(item.getUnknownFields().asMap().isEmpty(), "Unknown source reward item fields");
      require(
          values.put(number(item, "item_id"), number(item, "num")) == null,
          "Duplicate source reward item");
    }
    return values;
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int code) throws Exception {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name),
                new byte[0],
                request(name, fields).build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    int command = protocol.clientCommand(response);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("KCP connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("SubMissionRewardScNotify")) {
        var notify = protocol.clientSchema().parse("SubMissionRewardScNotify", frame.body());
        require(notify.getUnknownFields().asMap().isEmpty(), "Unknown source sub reward fields");
        var quantities = items((DynamicMessage) field(notify, "reward"));
        if (number(notify, "sub_mission_id") == 434010402) {
          require(quantities.equals(Map.of(190543, 1)), "Original book payout differs");
          bookNotify = true;
        }
        if (number(notify, "sub_mission_id") == 434010409) {
          require(quantities.equals(Map.of(2, 3000, 212, 3)), "Original tail payout differs");
          tailNotify = true;
        }
      }
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var notify = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        var basic = notify.getDescriptorForType().findFieldByName("basic_info");
        if (notify.hasField(basic))
          observedWallet = number((DynamicMessage) notify.getField(basic), "scoin");
      }
      if (frame.command() != command) continue;
      var message = protocol.clientSchema().parse(response, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      require(
          number(message, "retcode") == code,
          "Response rejected: " + response + " code=" + number(message, "retcode"));
      return message;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private Map<Integer, Integer> bag() throws Exception {
    var values = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var item = (DynamicMessage) raw;
      require(item.getUnknownFields().asMap().isEmpty(), "Unknown native bag item");
      require(
          values.put(number(item, "tid"), number(item, "num")) == null,
          "Duplicate source bag stack");
    }
    return values;
  }

  private int bookCount(int uid) throws Exception {
    var response = call("GetPlayerDetailInfoCsReq", Map.of("uid", uid), 0);
    var detail = (DynamicMessage) field(response, "detail_info");
    require(detail.getUnknownFields().asMap().isEmpty(), "Unknown native detail fields");
    require(number(detail, "uid") == uid, "Native book profile has wrong owner");
    var record = (DynamicMessage) field(detail, "record_info");
    require(record.getUnknownFields().asMap().isEmpty(), "Unknown native book record fields");
    require(
        record.getDescriptorForType().findFieldByNumber(9).getName().equals("JJKIKOBAJLF"),
        "Frozen book field changed");
    return number(record, "JJKIKOBAJLF");
  }

  private void move(int entry) throws Exception {
    call("EnterSceneCsReq", Map.of("entry_id", entry), 0);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require(scene.getUnknownFields().asMap().isEmpty(), "Unknown native scene fields");
    check(
        number(scene, "entry_id") == entry
            && number(scene, "floor_id") == entry / 100 * 1000 + 1
            && number(scene, "plane_id") == entry / 100,
        "actual-original-floor-" + entry);
  }

  private void talk(int id, Integer choice, int code) throws Exception {
    var fields = new HashMap<String, Object>();
    fields.put("sub_mission_id", id);
    fields.put("talk_str", "Mission_" + id);
    if (choice != null) {
      var type =
          protocol
              .clientSchema()
              .message("FinishTalkMissionCsReq")
              .findFieldByName("custom_value_list")
              .getMessageType();
      var row = DynamicMessage.newBuilder(type);
      row.setField(type.findFieldByName("index"), 0);
      row.setField(type.findFieldByName("custom_value"), choice);
      fields.put("custom_value_list", List.of(row.build()));
    }
    var response = call("FinishTalkMissionCsReq", fields, code);
    check(number(response, "sub_mission_id") == id, "original-dialogue-" + id + "-code-" + code);
  }

  private void progress(int first, int second, int returned, boolean complete, String label)
      throws Exception {
    var data = call("GetMissionDataCsReq");
    var subs = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(data, "mission_list")) {
      var row = (DynamicMessage) raw;
      require(row.getUnknownFields().asMap().isEmpty(), "Unknown mission row");
      require(subs.put(number(row, "id"), row) == null, "Duplicate native mission row");
    }
    require(
        subs.containsKey(434010404) && number(subs.get(434010404), "progress") == first,
        "Wrong first original outgoing progress");
    require(
        second < 0
            ? !subs.containsKey(434010407)
            : subs.containsKey(434010407) && number(subs.get(434010407), "progress") == second,
        "Wrong second original outgoing progress");
    require(
        returned < 0
            ? !subs.containsKey(434010405)
            : subs.containsKey(434010405) && number(subs.get(434010405), "progress") == returned,
        "Wrong original return progress");
    require(
        ((List<?>) field(data, "finished_main_mission_id_list")).contains(4340104) == complete,
        "Wrong original main completion");
    check(true, label);
  }

  private void performance(int id, JsonObject input, int code) throws Exception {
    var requestType = protocol.clientSchema().message("FinishPerformSectionIdCsReq");
    var sectionField = requestType.findFieldByName("HBMLBLAJKKL");
    var sectionType = sectionField.getMessageType();
    var section = DynamicMessage.newBuilder(sectionType);
    section.setField(sectionType.findFieldByName("MGPGDLIJKLM"), id);
    var itemsField = sectionType.findFieldByName("DPPAFBAHEFH");
    for (var raw : input.getAsJsonObject("messageChoices").getAsJsonArray(Integer.toString(id))) {
      var item = DynamicMessage.newBuilder(itemsField.getMessageType());
      item.setField(item.getDescriptorForType().findFieldByName("item_id"), raw.getAsInt());
      section.addRepeatedField(itemsField, item.build());
    }
    call("FinishPerformSectionIdCsReq", Map.of("HBMLBLAJKKL", section.build()), code);
    check(true, "original-message-path-" + id + "-code-" + code);
  }

  private Map<String, Object> execute(JsonObject input, java.io.BufferedReader unused)
      throws Exception {
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                input.get("accountUid").getAsString(),
                "token",
                input.get("comboToken").getAsString(),
                "platform",
                1),
            0);
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    var phase = input.get("phase").getAsString();
    int wallet = number((DynamicMessage) field(login, "basic_info"), "scoin");
    if (phase.equals("observer")) {
      int target = input.get("targetUid").getAsInt();
      require(target != uid && target > 0, "Observer and original owner must differ");
      check(bookCount(uid) == 0, "observer-has-no-owned-books");
      check(bookCount(target) == 1, "offline-original-owner-book-profile-after-H2-restart");
      for (int i = 0; i < 12; i++)
        require(bookCount(target) == 1, "Read-only offline profile changed");
      check(true, "twelve-offline-profile-reads-keep-one-book");
      check(
          !bag().containsKey(190543) && wallet == 0,
          "offline-profile-does-not-transfer-book-or-credit");
      return Map.of(
          "passed",
          true,
          "phase",
          phase,
          "uid",
          uid,
          "targetUid",
          target,
          "targetBookCount",
          1,
          "offlineOriginalBeforeOwnerLogin",
          true,
          "checks",
          checks,
          "clientGameplayVerified",
          false);
    }
    boolean completed = phase.equals("final");
    check(wallet == (completed ? 3000 : 0), "source-wallet-restored-" + phase);
    var stock = bag();
    int baseline =
        phase.equals("first") ? stock.getOrDefault(212, 0) : input.get("initial212").getAsInt();
    check(
        stock.getOrDefault(190543, 0) == (phase.equals("first") ? 0 : 1),
        "source-book-restored-" + phase);
    check(
        stock.getOrDefault(212, 0) == baseline + (completed ? 3 : 0),
        "source-tail-material-restored-" + phase);
    if (phase.equals("first")) {
      check(bookCount(uid) == 0, "original-profile-is-empty-before-source-book-grant");
      move(2031101);
      talk(434010401, null, 0);
      talk(434010402, 0, Retcode.MISSION_CUSTOM_VALUE_NOT_VALID.getVal());
      talk(434010402, 1, 0);
      check(bookNotify, "actual-source-book-notify-before-dialogue-response");
      require(bag().getOrDefault(190543, 0) == 1, "Original book not stored");
      progress(0, -1, -1, false, "source-choice-opens-first-outgoing-only");
      move(2031101);
      progress(0, -1, -1, false, "same-original-floor-does-not-complete-outgoing");
      move(2031201);
      progress(1, -1, 0, false, "first-source-outgoing-with-return-pending");
    } else if (phase.equals("return")) {
      progress(1, -1, 0, false, "saved-first-outgoing-keeps-return-pending");
      move(2031101);
      progress(1, -1, 1, false, "original-return-does-not-skip-first-message");
      performance(1303200, input, 0);
      progress(1, 0, 1, false, "original-message-opens-second-outgoing");
      move(2031201);
      progress(1, 1, 1, false, "second-source-outgoing-keeps-tail-pending");
    } else if (phase.equals("tail")) {
      progress(1, 1, 1, false, "saved-second-outgoing-keeps-tail-pending");
      move(2031101);
      performance(1303300, input, 0);
      check(
          tailNotify && observedWallet == 3000,
          "actual-source-tail-notify-before-performance-response");
      progress(1, 1, 1, true, "source-original-main-completed-by-real-tail");
    } else {
      require(phase.equals("final"), "Unknown source floor probe phase");
      progress(1, 1, 1, true, "source-original-main-restored-completed");
      talk(434010402, 1, Retcode.MISSION_NOT_DOING.getVal());
      performance(1303300, input, Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal());
      call(
          "AcceptMainMissionCsReq",
          Map.of("main_mission_id", 4340104),
          Retcode.MISSION_ALREADY_DONE.getVal());
      check(true, "source-completed-branch-cannot-reopen");
    }
    stock = bag();
    boolean paid = completed || phase.equals("tail");
    check(
        stock.getOrDefault(190543, 0) == 1
            && stock.getOrDefault(212, 0) == baseline + (paid ? 3 : 0),
        "actual-original-book-and-tail-stock-" + phase);
    int profileBooks = bookCount(uid);
    check(profileBooks == 1, "actual-native-book-profile-" + phase);
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("phase", phase);
    result.put("loginWallet", wallet);
    result.put("initial212", baseline);
    result.put("book", stock.getOrDefault(190543, 0));
    result.put("profileBookCount", profileBooks);
    result.put("material212", stock.getOrDefault(212, 0));
    result.put("checks", checks);
    result.put("bookRewardNotifyBeforeResponse", bookNotify);
    result.put("tailRewardNotifyBeforeResponse", tailNotify);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new LoopbackSwitchFloorProbe();
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
                  require(frame != null, "Truncated mission frame");
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
                  null, new IllegalStateException("Mission probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials, control)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
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
