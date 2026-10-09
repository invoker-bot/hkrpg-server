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

/** Original message choices and recovered talk gate over isolated UDP/KCP. */
public final class LoopbackMissionTalkGatesProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private int wallet;
  private int currencyUpdates;

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

  private DynamicMessage.Builder request(String name, Map<String, Object> values) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    values.forEach((k, v) -> b.setField(b.getDescriptorForType().findFieldByName(k), v));
    return b;
  }

  private DynamicMessage call(String name, Map<String, Object> values, int code) throws Exception {
    return rawCall(name, request(name, values).build().toByteArray(), code);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private DynamicMessage rawCall(String name, byte[] body, int code) throws Exception {
    var packet =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Mission talk KCP failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var sync = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        if (sync.hasField(sync.getDescriptorForType().findFieldByName("basic_info"))) {
          wallet = number((DynamicMessage) field(sync, "basic_info"), "scoin");
          currencyUpdates++;
        }
      }
      if (frame.command() != protocol.clientCommand(response)) continue;
      var m = protocol.clientSchema().parse(response, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown native response fields");
      String key = "retcode";
      require(number(m, key) == code, response + " expected=" + code + " actual=" + number(m, key));
      return m;
    }
    throw new TimeoutException(response);
  }

  private Map<Integer, Integer> bag() throws Exception {
    var result = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var row = (DynamicMessage) raw;
      require(row.getUnknownFields().asMap().isEmpty(), "Unknown bag fields");
      require(result.put(number(row, "tid"), number(row, "num")) == null, "Duplicate bag identity");
    }
    return result;
  }

  private void progress(int message, int talk, boolean mainFinished) throws Exception {
    var all = call("GetMissionDataCsReq");
    var found = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(all, "mission_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id == 434011111 || id == 434011114)
        require(found.put(id, row) == null, "Duplicate original objective");
    }
    require(found.size() == 2, "Missing original objective");
    for (int id : List.of(434011111, 434011114)) {
      var row = found.get(id);
      int wanted = id == 434011111 ? message : talk;
      check(number(row, "progress") == wanted, "source-objective-" + id + "-progress-" + wanted);
      check(
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(row, "status"))
              .getName()
              .equals(wanted == 1 ? "MissionStatus_MissionFinish" : "MissionStatus_MissionDoing"),
          "source-objective-" + id + "-phase-" + wanted);
    }
    check(
        ((List<?>) field(all, "finished_main_mission_id_list")).contains(4340111) == mainFinished,
        "source-main-final-state-" + mainFinished);
  }

  private void talk(int id, String text, int code) throws Exception {
    call("FinishTalkMissionCsReq", Map.of("sub_mission_id", id, "talk_str", text), code);
    check(true, "talk-report-" + id + "-code-" + code);
  }

  private void section(int code) throws Exception {
    call("FinishSectionIdCsReq", Map.of("section_id", 1306100), code);
    check(true, "source-section-finish-code-" + code);
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
    var login = call("PlayerLoginCsReq");
    wallet = number((DynamicMessage) field(login, "basic_info"), "scoin");
    call("PlayerLoginFinishCsReq");
    String mode = input.get("mode").getAsString();
    if (mode.equals("bootstrap")) call("EnterSceneCsReq", Map.of("entry_id", 2032101), 0);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    check(
        number(scene, "entry_id") == 2032101
            && number(scene, "plane_id") == 20321
            && number(scene, "floor_id") == 20321001
            && number(scene, "leader_entity_id") > 0,
        "original-scene-and-actual-actor");
    var initial = bag();
    if (mode.equals("pending") || mode.equals("complete")) {
      progress(0, 0, false);
      talk(434011114, "Mission_434011114", Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal());
      talk(0, "Mission_434011114", Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal());
      section(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal());
      call(
          "FinishItemIdCsReq",
          Map.of("item_id", 130610000),
          Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal());
      check(true, "source-npc-line-cannot-be-a-player-choice");
      progress(0, 0, false);
      check(
          wallet == 0 && initial.getOrDefault(212, 0) == 0 && initial.getOrDefault(222, 0) == 0,
          "unmet-script-does-not-grant-main-reward");
      if (mode.equals("complete")) {
        call("FinishItemIdCsReq", Map.of("item_id", 130610009), 0);
        check(true, "actual-offered-source-message-choice");
        section(0);
        progress(1, 0, false);
        section(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal());
        talk(0, "Mission_434011114", 0);
        progress(1, 1, true);
      }
    } else if (!Set.of("bootstrap", "resume").contains(mode))
      throw new IllegalArgumentException("Unknown probe mode");
    if (mode.equals("complete") || mode.equals("resume")) {
      progress(1, 1, true);
      talk(434011114, "Mission_434011114", Retcode.MISSION_NOT_DOING.getVal());
      section(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal());
      var stock = bag();
      check(wallet == 5000, "source-five-thousand-credit-reward-once");
      check(
          stock.getOrDefault(212, 0) == 3 && stock.getOrDefault(222, 0) == 2,
          "source-material-reward-three-and-two-once");
    } else check(bag().equals(initial) && wallet == 0, "pending-inventory-and-wallet-preserved");
    var stock = bag();
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("mode", mode);
    result.put("checks", checks);
    result.put("wallet", wallet);
    result.put("material212", stock.getOrDefault(212, 0));
    result.put("material222", stock.getOrDefault(222, 0));
    result.put("currencyUpdates", currencyUpdates);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new LoopbackMissionTalkGatesProbe();
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
                  require(frame != null, "Truncated dice main-page frame");
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
                  null, new IllegalStateException("Mission talk probe disconnected"));
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
