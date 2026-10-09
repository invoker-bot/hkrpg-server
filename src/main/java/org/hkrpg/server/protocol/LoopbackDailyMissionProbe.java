package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
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
 * Original daily 3000201 assignment, five talks, aggregation and one-time main/activity payouts.
 */
public final class LoopbackDailyMissionProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private boolean mainRewardNotify;
  private int observedWallet = -1;
  private int awaitedQuest;
  private boolean questCompletionNotify;

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
      if (awaitedQuest != 0 && frame.command() == protocol.clientCommand("GetQuestDataScRsp")) {
        var data = protocol.clientSchema().parse("GetQuestDataScRsp", frame.body());
        require(data.getUnknownFields().asMap().isEmpty(), "Unknown quest notification fields");
        for (var raw : (List<?>) field(data, "quest_list")) {
          var row = (DynamicMessage) raw;
          if (number(row, "id") == awaitedQuest
              && number(row, "progress") == 1
              && ((EnumValueDescriptor) field(row, "status")).getNumber() == 2)
            questCompletionNotify = true;
        }
      }
      if (frame.command() == protocol.clientCommand("MissionRewardScNotify")) {
        var notify = protocol.clientSchema().parse("MissionRewardScNotify", frame.body());
        require(notify.getUnknownFields().asMap().isEmpty(), "Unknown main reward fields");
        if (number(notify, "main_mission_id") != 3000201) continue;
        var reward = (DynamicMessage) field(notify, "reward");
        var items = (List<?>) field(reward, "item_list");
        require(items.size() == 1, "Wrong daily main reward item count");
        var item = (DynamicMessage) items.getFirst();
        require(
            number(item, "item_id") == 2 && number(item, "num") == 5000,
            "Wrong original daily credit notification");
        mainRewardNotify = true;
      }
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var notify = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        var basic = notify.getDescriptorForType().findFieldByName("basic_info");
        if (basic != null && notify.hasField(basic))
          observedWallet = number((DynamicMessage) notify.getField(basic), "scoin");
      }
      if (frame.command() != command) continue;
      var m = protocol.clientSchema().parse(response, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      require(
          number(m, "retcode") == code,
          "Response rejected: " + response + " code=" + number(m, "retcode"));
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private void talk(int id, String text, int code, String label) throws Exception {
    call("FinishTalkMissionCsReq", Map.of("sub_mission_id", id, "talk_str", text), code);
    check(true, label);
  }

  private void entry() throws Exception {
    call("EnterSceneCsReq", Map.of("entry_id", 2000301), 0);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    check(
        number(scene, "entry_id") == 2000301
            && number(scene, "plane_id") == 20003
            && number(scene, "floor_id") == 20003001
            && number(scene, "leader_entity_id") > 0,
        "real-scene-at-original-daily-floor");
  }

  private void progress(boolean completed, boolean partial, String label) throws Exception {
    var data = call("GetMissionDataCsReq");
    var mains = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(data, "main_mission_list")) {
      var main = (DynamicMessage) raw;
      if (number(main, "id") == 3000201) mains.add(main);
    }
    require(mains.size() == 1, "Missing or duplicate actual daily assignment");
    require(
        ((EnumValueDescriptor) field(mains.getFirst(), "status")).getNumber()
            == (completed ? 2 : 1),
        "Wrong actual daily main phase");
    require(
        ((List<?>) field(data, "finished_main_mission_id_list")).contains(3000201) == completed,
        "Wrong actual daily completion history");
    var subs = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(data, "mission_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id >= 300020101 && id <= 300020106)
        require(subs.put(id, row) == null, "Duplicate original daily sub");
    }
    require(subs.containsKey(300020101), "Original daily start was not accepted");
    require(
        number(subs.get(300020101), "progress") == (partial || completed ? 1 : 0),
        "Wrong first talk progress");
    if (partial || completed) {
      require(subs.size() == 6, "Missing full source daily objective graph");
      require(
          number(subs.get(300020102), "progress") == 3
              && ((EnumValueDescriptor) field(subs.get(300020102), "status")).getNumber() == 2,
          "Original three-target aggregate did not finish");
      require(
          number(subs.get(300020106), "progress") == (completed ? 1 : 0),
          "Wrong final daily talk progress");
    }
    check(true, label);
  }

  private void quest(int status, String label) throws Exception {
    var data = call("GetQuestDataCsReq");
    var matches = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(data, "quest_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "id") == 2100003) matches.add(row);
    }
    require(matches.size() == 1, "Missing or duplicate original daily active target");
    var row = matches.getFirst();
    check(
        ((EnumValueDescriptor) field(row, "status")).getNumber() == status
            && number(row, "progress") == (status == 1 ? 0 : 1)
            && ((Long) field(row, "finish_time") > 0) == (status != 1),
        label);
  }

  private void points(int expected, String label) throws Exception {
    var data = call("GetDailyActiveInfoCsReq");
    check(number(data, "daily_active_point") == expected, label);
  }

  private void claim(int code, String label) throws Exception {
    var data = call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", List.of(2100003)), code);
    check(
        ((List<?>) field(data, "succ_quest_id_list"))
            .equals(code == 0 ? List.of(2100003) : List.of()),
        label);
  }

  private Map<String, Object> execute(JsonObject credentials, java.io.BufferedReader control)
      throws Exception {
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1),
            0);
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    if (credentials.has("clockFixture") && credentials.get("clockFixture").getAsBoolean()) {
      System.out.println("HKRPG_DAILY_CLOCK_FIXTURE_READY:" + uid);
      System.out.flush();
      require("resume".equals(control.readLine()), "Daily clock fixture did not resume");
    }
    String phase = credentials.get("phase").getAsString();
    boolean complete = Set.of("unclaimed", "claimed").contains(phase);
    int wallet = number((DynamicMessage) field(login, "basic_info"), "scoin");
    check(wallet == (complete ? 5000 : 0), "daily-main-wallet-restored-" + phase);
    progress(
        complete,
        !phase.equals("first"),
        "actual-assignment-restored-without-client-accept-" + phase);
    quest(
        phase.equals("claimed") ? 3 : complete ? 2 : 1,
        "original-daily-active-state-restored-" + phase);
    points(phase.equals("claimed") ? 200 : 0, "original-daily-points-restored-" + phase);
    if (phase.equals("first")) {
      entry();
      talk(
          300020106,
          "Mission_300020106",
          Retcode.MISSION_NOT_DOING.getVal(),
          "final-source-talk-cannot-skip-three-target-aggregate");
      talk(
          300020101,
          "wrong-daily-dialogue",
          Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),
          "wrong-original-daily-talk-rejected");
      progress(false, false, "rejected-talks-do-not-finish-daily");
      for (int id : List.of(300020101, 300020103, 300020104, 300020105))
        talk(id, "Mission_" + id, 0, "original-daily-talk-" + id);
      progress(false, true, "four-talks-complete-original-three-target-aggregate");
      quest(1, "partial-source-main-does-not-finish-activity");
      claim(Retcode.QUEST_NOT_FINISH.getVal(), "incomplete-daily-active-reward-rejected");
      points(0, "partial-daily-points-stay-zero");
    } else if (phase.equals("finish")) {
      entry();
      awaitedQuest = 2100003;
      questCompletionNotify = false;
      mainRewardNotify = false;
      talk(300020106, "Mission_300020106", 0, "fifth-original-talk-completes-daily-main");
      check(questCompletionNotify, "original-daily-quest-notify-before-final-talk-response");
      check(
          mainRewardNotify && observedWallet == 5000,
          "original-5000-credit-main-notify-before-final-talk-response");
      awaitedQuest = 0;
      progress(true, true, "all-six-original-daily-objectives-finished");
      quest(2, "actual-daily-completion-finishes-active-target");
      points(0, "completion-does-not-auto-claim-200-points");
      talk(
          300020106,
          "Mission_300020106",
          Retcode.MISSION_NOT_DOING.getVal(),
          "completed-original-daily-talk-not-replayed");
      call(
          "AcceptMainMissionCsReq",
          Map.of("main_mission_id", 3000201),
          Retcode.MISSION_ALREADY_DONE.getVal());
      check(true, "completed-daily-cannot-restart-today");
    } else if (phase.equals("unclaimed")) {
      claim(0, "actual-original-200-point-reward-claimed");
      points(200, "actual-original-200-points-delivered");
      quest(3, "actual-original-daily-reward-closed");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(), "duplicate-original-daily-claim-rejected");
      points(200, "duplicate-original-daily-claim-keeps-200-points");
    } else {
      require(phase.equals("claimed"), "Unknown daily probe phase");
      claim(
          Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),
          "saved-daily-reward-cannot-be-claimed-again");
      points(200, "saved-daily-reward-keeps-exact-200-points");
      call(
          "AcceptMainMissionCsReq",
          Map.of("main_mission_id", 3000201),
          Retcode.MISSION_ALREADY_DONE.getVal());
      check(true, "saved-completed-daily-cannot-restart-today");
    }
    return new LinkedHashMap<>(
        Map.of(
            "passed",
            true,
            "uid",
            uid,
            "phase",
            phase,
            "checks",
            checks,
            "loginWallet",
            wallet,
            "immediateQuestCompletionNotifyVerified",
            questCompletionNotify,
            "mainRewardNotifyBeforeResponseVerified",
            mainRewardNotify,
            "sourceMainGraphUnmodified",
            true,
            "clientGameplayVerified",
            false));
  }

  public static void main(String[] args) {
    var probe = new LoopbackDailyMissionProbe();
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
