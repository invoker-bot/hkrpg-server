package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Actual first-season four-round requests and restart checks; no completion is seeded. */
public final class NativeBoxingClubRoundsProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;
  private DynamicMessage reward;

  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }

  private static int number(DynamicMessage m, String key) {
    var value = field(m, key);
    return value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e
        ? e.getNumber()
        : (Integer) value;
  }

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private DynamicMessage call(String name, DynamicMessage.Builder b) throws Exception {
    var response = name.replace("CsReq", "ScRsp");
    int command = name.equals("OFEDNNJIJFL") ? 5676 : protocol.clientCommand(name);
    if (name.equals("OFEDNNJIJFL")) response = "HandleRogueCommonPendingActionScRsp";
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], b.build().toByteArray()));
    try {
      channel.write(bytes);
      requests++;
    } finally {
      bytes.release();
    }
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < end) {
      if (failure.get() != null)
        throw new IllegalStateException("Connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == 4283)
        reward = protocol.clientSchema().parse("BoxingClubRewardScNotify", frame.body());
      if (frame.command() == protocol.clientCommand(response)) {
        var m = protocol.clientSchema().parse(response, frame.body());
        require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
        return m;
      }
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage info() throws Exception {
    var reply = call("GetBoxingClubInfoCsReq");
    require(number(reply, "retcode") == 0, "Info rejected");
    for (var v : (List<?>) field(reply, "challenge_list")) {
      var r = (DynamicMessage) v;
      if (number(r, "challenge_id") == 1) return r;
    }
    throw new IllegalStateException("Missing first challenge");
  }

  private void progress(int index, int cycles) throws Exception {
    var r = info();
    require(
        number(r, "IFHHFBIJOFC") == 10
            && number(r, "stage_index") == index
            && number(r, "EAFICCLCLLA") == cycles,
        "Wrong active progress");
    require(((List<?>) field(r, "EJEFDFEFOMH")).size() == index, "Wrong earned buff count");
  }

  private void match() throws Exception {
    var b = builder("MatchBoxingClubOpponentCsReq");
    set(b, "challenge_id", 1);
    var actor = builder("AKLDICEFCIL");
    set(actor, "avatar_id", 1001);
    var f = actor.getDescriptorForType().findFieldByName("avatar_type");
    actor.setField(f, f.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("OOJOMKDMBBM"), actor.build());
    require(number(call("MatchBoxingClubOpponentCsReq", b), "retcode") == 0, "Match rejected");
    progress(0, 0);
  }

  private DynamicMessage report(DynamicMessage battle, int count) throws Exception {
    var r = builder("PVEBattleResultCsReq");
    set(r, "battle_id", field(battle, "battle_id"));
    set(r, "stage_id", field(battle, "stage_id"));
    var f = r.getDescriptorForType().findFieldByName("end_status");
    r.setField(f, f.getEnumType().findValueByName("BATTLE_END_WIN"));
    var st = builder("BattleStatistics");
    set(st, "round_cnt", count);
    set(r, "stt", st.build());
    return call("PVEBattleResultCsReq", r);
  }

  private void fight(JsonObject control, int index, int cycles, int count) throws Exception {
    var start = builder("StartBoxingClubBattleCsReq");
    set(start, "challenge_id", 1);
    var reply = call("StartBoxingClubBattleCsReq", start);
    require(number(reply, "retcode") == 0, "Start rejected");
    var battle = (DynamicMessage) field(reply, "battle_info");
    require(
        number(battle, "stage_id") == control.getAsJsonArray("stages").get(index).getAsInt(),
        "Wrong original stage");
    require(number(battle, "rounds_limit") == 20 - cycles, "Wrong remaining budget");
    var actors = (List<?>) field(battle, "battle_avatar_list");
    require(
        actors.size() == 1 && number((DynamicMessage) actors.get(0), "id") == 1001,
        "Wrong frozen actor");
    require(
        number((DynamicMessage) actors.get(0), "hp") == 10000
            && number((DynamicMessage) field((DynamicMessage) actors.get(0), "sp_bar"), "cur_sp")
                == 5000,
        "Wrong full HP/half SP");
    var buffs = (List<?>) field(battle, "buff_list");
    var expected = new LinkedHashSet<Integer>();
    expected.add(3100037);
    for (int i = 0; i < index; i++)
      expected.add(control.getAsJsonArray("gainBuffs").get(i).getAsInt());
    var actual = new LinkedHashSet<Integer>();
    for (var v : buffs) {
      var b = (DynamicMessage) v;
      actual.add(number(b, "id"));
      require(number(b, "level") == 1 && number(b, "wave_flag") == -1, "Wrong buff binding");
    }
    require(actual.equals(expected) && buffs.size() == expected.size(), "Wrong earned buffs");
    var pending = call("GetCurBattleInfoCsReq");
    require(number(report(battle, 0), "retcode") != 0, "Zero count accepted");
    require(pending.equals(call("GetCurBattleInfoCsReq")), "Rejected count consumed battle");
    require(number(report(battle, count), "retcode") == 0, "Win rejected");
    require(number(report(battle, count), "retcode") != 0, "Replay accepted");
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var t = call("PlayerGetTokenCsReq", token);
    require(number(t, "retcode") == 0, "Token rejected");
    int uid = number(t, "uid");
    require(number(call("PlayerLoginCsReq"), "retcode") == 0, "Login rejected");
    require(number(call("PlayerLoginFinishCsReq"), "retcode") == 0, "Finish login rejected");
    int phase = control.get("phase").getAsInt(), wins = 0;
    if (phase == 0) {
      require(number(info(), "IFHHFBIJOFC") == 0, "Prewritten match");
      System.out.println("READY_UID=" + uid);
      System.out.flush();
      boolean owned = false;
      for (int i = 0; i < 120 && !owned; i++) {
        for (var raw : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
          if (number((DynamicMessage) raw, "base_avatar_id") == 1001) owned = true;
        if (!owned) Thread.sleep(200);
      }
      require(owned, "Console avatar fixture missing");
    } else if (phase == 1) {
      match();
      for (int i = 0; i < 2; i++) {
        fight(control, i, i * 2, 2);
        wins++;
        progress(i + 1, (i + 1) * 2);
      }
    } else if (phase == 2) {
      progress(2, 4);
      for (int i = 2; i < 4; i++) {
        fight(control, i, i * 2, 2);
        wins++;
        if (i < 3) progress(i + 1, (i + 1) * 2);
      }
      var r = info();
      require(
          number(r, "IFHHFBIJOFC") == 0
              && number(r, "LFFBKJBJJDD") == 8
              && Boolean.TRUE.equals(field(r, "MLOGJKEOCPP")),
          "Missing earned first completion");
      require(
          reward != null
              && number(reward, "EAFICCLCLLA") == 8
              && Boolean.TRUE.equals(field(reward, "is_win")),
          "Missing native completion notification");
      var items = (List<?>) field((DynamicMessage) field(reward, "reward"), "item_list");
      var delivered = new TreeMap<Integer, Integer>();
      for (var v : items) {
        var item = (DynamicMessage) v;
        delivered.put(number(item, "item_id"), number(item, "num"));
      }
      require(delivered.equals(Map.of(1, 20, 2, 10000, 213, 2)), "Wrong original first reward");
      match();
      for (int i = 0; i < 4; i++) {
        fight(control, i, i, 1);
        wins++;
        if (i < 3) progress(i + 1, i + 1);
      }
      r = info();
      require(
          number(r, "LFFBKJBJJDD") == 4 && Boolean.TRUE.equals(field(r, "MLOGJKEOCPP")),
          "Best record did not improve");
      require(
          ((List<?>) field((DynamicMessage) field(reward, "reward"), "item_list")).isEmpty(),
          "Repeated challenge granted reward");
    } else {
      var r = info();
      require(
          number(r, "IFHHFBIJOFC") == 0
              && number(r, "LFFBKJBJJDD") == 4
              && Boolean.TRUE.equals(field(r, "MLOGJKEOCPP")),
          "Restart lost record/delivery");
      var start = builder("StartBoxingClubBattleCsReq");
      set(start, "challenge_id", 1);
      require(
          number(call("StartBoxingClubBattleCsReq", start), "retcode") != 0,
          "Start without match accepted");
    }
    var direct = builder("SceneEnterStageCsReq");
    set(direct, "event_id", 304004);
    require(
        number(call("SceneEnterStageCsReq", direct), "retcode") != 0,
        "Generic event bypassed boxing context");
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "phase",
        phase,
        "nativeRequests",
        requests,
        "acceptedRoundWins",
        wins,
        "completionPrewritten",
        false,
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeBoxingClubRoundsProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
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
                  var f = PacketCodec.decodeNext(bytes);
                  require(f != null, "Truncated frame");
                  probe.inbox.add(new Frame(f.cmdId(), f.body()));
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
              probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
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
