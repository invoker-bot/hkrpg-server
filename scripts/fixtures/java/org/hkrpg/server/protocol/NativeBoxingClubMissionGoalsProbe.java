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

/**
 * Nonperiodic activity goals and complete original rewards over native KCP; wins are never
 * prewritten.
 */
public final class NativeBoxingClubMissionGoalsProbe {
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

  private DynamicMessage info(int id) throws Exception {
    var reply = call("GetBoxingClubInfoCsReq");
    require(number(reply, "retcode") == 0, "Info rejected");
    for (var v : (List<?>) field(reply, "challenge_list")) {
      var row = (DynamicMessage) v;
      if (number(row, "challenge_id") == id) return row;
    }
    throw new IllegalStateException("Missing source boxing level " + id);
  }

  private void choose(int id, int buff, boolean resonance) throws Exception {
    String name =
        resonance ? "ChooseBoxingClubResonanceCsReq" : "ChooseBoxingClubStageOptionalBuffCsReq";
    var b = builder(name);
    set(b, "challenge_id", id);
    set(b, resonance ? "HODLKBMKMCF" : "MFKAOHKPIPE", buff);
    require(number(call(name, b), "retcode") == 0, "Source choice rejected");
  }

  private DynamicMessage report(DynamicMessage battle, int count) throws Exception {
    var r = builder("PVEBattleResultCsReq");
    set(r, "battle_id", field(battle, "battle_id"));
    set(r, "stage_id", field(battle, "stage_id"));
    var f = r.getDescriptorForType().findFieldByName("end_status");
    r.setField(f, f.getEnumType().findValueByName("BATTLE_END_WIN"));
    var stats = builder("BattleStatistics");
    set(stats, "round_cnt", count);
    set(r, "stt", stats.build());
    return call("PVEBattleResultCsReq", r);
  }

  private void earn(JsonObject control, int id, int total) throws Exception {
    var spec = control.getAsJsonObject("boxSpecs").getAsJsonObject(String.valueOf(id));
    int prior = number(info(id), "LFFBKJBJJDD");
    var b = builder("SetBoxingClubResonanceCsReq");
    set(b, "challenge_id", id);
    var actor = builder("AKLDICEFCIL");
    set(actor, "avatar_id", 1001);
    var type = actor.getDescriptorForType().findFieldByName("avatar_type");
    actor.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("OOJOMKDMBBM"), actor.build());
    var seed = call("SetBoxingClubResonanceCsReq", b);
    require(number(seed, "retcode") == 0, "Roster rejected");
    require(
        number((DynamicMessage) field(seed, "OCDLPDDMCLP"), "IFHHFBIJOFC") == 0,
        "Preselected resonance");
    choose(id, spec.get("resonance").getAsInt(), true);
    int cycles = 0;
    var expectedBuffs = new HashSet<Integer>();
    expectedBuffs.add(spec.get("resonance").getAsInt());
    for (int i = 0; i < 3; i++) {
      if (i > 0) {
        int selected =
            spec.getAsJsonArray("optionalLists")
                .get(i)
                .getAsJsonArray()
                .get(total == 5 ? 1 : 0)
                .getAsInt();
        choose(id, selected, false);
        expectedBuffs.add(selected);
      }
      var start = builder("StartBoxingClubBattleCsReq");
      set(start, "challenge_id", id);
      var reply = call("StartBoxingClubBattleCsReq", start);
      require(number(reply, "retcode") == 0, "Source battle rejected");
      var battle = (DynamicMessage) field(reply, "battle_info");
      require(
          number(battle, "stage_id") == spec.getAsJsonArray("stages").get(i).getAsInt()
              && number(battle, "rounds_limit") == 20 - cycles,
          "Wrong source stage or budget");
      var actors = (List<?>) field(battle, "battle_avatar_list");
      require(
          actors.size() == 1
              && number((DynamicMessage) actors.getFirst(), "id") == 1001
              && number((DynamicMessage) actors.getFirst(), "hp") == 10000,
          "Wrong independent formal team");
      var buffs = new HashSet<Integer>();
      for (var raw : (List<?>) field(battle, "buff_list"))
        buffs.add(number((DynamicMessage) raw, "id"));
      require(buffs.equals(expectedBuffs), "Unselected source buff injected");
      var pending = call("GetCurBattleInfoCsReq");
      require(number(report(battle, 0), "retcode") != 0, "Zero count accepted");
      require(pending.equals(call("GetCurBattleInfoCsReq")), "Rejected result consumed battle");
      int count = total == 6 ? 2 : i == 2 ? total - 2 : 1;
      require(number(report(battle, count), "retcode") == 0, "Bound WIN rejected");
      require(number(report(battle, count), "retcode") != 0, "WIN replay accepted");
      cycles += count;
    }
    var done = info(id);
    require(
        number(done, "LFFBKJBJJDD") == (prior == 0 ? total : Math.min(prior, total))
            && number(done, "IFHHFBIJOFC") == 0
            && Boolean.TRUE.equals(field(done, "MLOGJKEOCPP")),
        "Lost earned completion/receipt");
    require(
        reward != null
            && ((List<?>) field((DynamicMessage) field(reward, "reward"), "item_list")).isEmpty(),
        "Invented original boxing reward");
  }

  private Map<Integer, Integer> recipe(JsonObject control, int id) {
    var result = new TreeMap<Integer, Integer>();
    for (var entry :
        control.getAsJsonObject("questRecipes").getAsJsonObject(String.valueOf(id)).entrySet())
      result.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt());
    return result;
  }

  private void earnFirst(JsonObject control, int id) throws Exception {
    var spec = control.getAsJsonObject("firstSpecs").getAsJsonObject(String.valueOf(id));
    var request = builder("MatchBoxingClubOpponentCsReq");
    set(request, "challenge_id", id);
    var member = builder("AKLDICEFCIL");
    set(member, "avatar_id", 1001);
    var kind = member.getDescriptorForType().findFieldByName("avatar_type");
    member.setField(kind, kind.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
    request.addRepeatedField(
        request.getDescriptorForType().findFieldByName("OOJOMKDMBBM"), member.build());
    require(
        number(call("MatchBoxingClubOpponentCsReq", request), "retcode") == 0,
        "Original first match rejected");
    var current = info(id);
    int group = number(current, "IFHHFBIJOFC");
    var groupSpec = spec.getAsJsonObject("groups").getAsJsonObject(String.valueOf(group));
    require(groupSpec != null, "Unknown original first group");
    for (int i = 0; i < 4; i++) {
      var start = builder("StartBoxingClubBattleCsReq");
      set(start, "challenge_id", id);
      var reply = call("StartBoxingClubBattleCsReq", start);
      require(number(reply, "retcode") == 0, "Original first round rejected");
      var battle = (DynamicMessage) field(reply, "battle_info");
      require(
          number(battle, "stage_id") == groupSpec.getAsJsonArray("stages").get(i).getAsInt(),
          "Wrong original first stage");
      require(number(battle, "rounds_limit") == 20 - i, "Wrong first remaining turn budget");
      var actors = (List<?>) field(battle, "battle_avatar_list");
      require(
          actors.size() == 1
              && number((DynamicMessage) actors.getFirst(), "id") == 1001
              && number((DynamicMessage) actors.getFirst(), "hp") == 10000
              && number(
                      (DynamicMessage) field((DynamicMessage) actors.getFirst(), "sp_bar"),
                      "cur_sp")
                  == 5000,
          "Wrong original first frozen formal party");
      var expected = new LinkedHashSet<Integer>();
      expected.add(spec.get("buff").getAsInt());
      for (int j = 0; j < i; j++) expected.add(groupSpec.getAsJsonArray("buffs").get(j).getAsInt());
      var actual = new LinkedHashSet<Integer>();
      for (var raw : (List<?>) field(battle, "buff_list"))
        actual.add(number((DynamicMessage) raw, "id"));
      require(actual.equals(expected), "Wrong earned first buffs");
      var pending = call("GetCurBattleInfoCsReq");
      require(number(report(battle, 0), "retcode") != 0, "First zero WIN accepted");
      require(pending.equals(call("GetCurBattleInfoCsReq")), "Rejected first WIN consumed battle");
      require(number(report(battle, 1), "retcode") == 0, "Bound first WIN rejected");
      require(number(report(battle, 1), "retcode") != 0, "First WIN replay accepted");
    }
    current = info(id);
    require(
        number(current, "LFFBKJBJJDD") == 4
            && number(current, "IFHHFBIJOFC") == 0
            && Boolean.TRUE.equals(field(current, "MLOGJKEOCPP")),
        "Lost original first completed receipt");
    var actual = new TreeMap<Integer, Integer>();
    require(reward != null, "Missing source first reward notice");
    for (var raw : (List<?>) field((DynamicMessage) field(reward, "reward"), "item_list")) {
      var item = (DynamicMessage) raw;
      actual.merge(number(item, "item_id"), number(item, "num"), Math::addExact);
    }
    var expected = new TreeMap<Integer, Integer>();
    for (var e : spec.getAsJsonObject("firstPassItems").entrySet())
      expected.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt());
    require(actual.equals(expected), "Wrong original first-pass reward");
  }

  private int claimAll(JsonObject control, int min, int max) throws Exception {
    for (int id = min; id <= max; id++) {
      claim(id, true, recipe(control, id));
      claim(id, false, Map.of());
      quest(id, id == 6000075 ? 5 : id == 6000397 ? 7 : 1, 3);
    }
    return max - min + 1;
  }

  private Map<Integer, DynamicMessage> quests() throws Exception {
    var reply = call("GetQuestDataCsReq");
    require(number(reply, "retcode") == 0, "Quest query rejected");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(reply, "quest_list")) {
      var q = (DynamicMessage) raw;
      require(rows.put(number(q, "id"), q) == null, "Duplicate quest id");
    }
    return rows;
  }

  private void quest(int id, int progress, int phase) throws Exception {
    var row = quests().get(id);
    require(
        row != null && number(row, "progress") == progress && number(row, "status") == phase,
        "Wrong earned resident quest " + id + ": " + row);
  }

  private void claim(int id, boolean accepted, Map<Integer, Integer> expected) throws Exception {
    var b = builder("TakeQuestRewardCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), id);
    var reply = call("TakeQuestRewardCsReq", b);
    require((number(reply, "retcode") == 0) == accepted, "Wrong source reward admission " + id);
    require(
        field(reply, "succ_quest_id_list").equals(accepted ? List.of(id) : List.of()),
        "Wrong claim success IDs");
    if (accepted) {
      var actual = new TreeMap<Integer, Integer>();
      for (var raw : (List<?>) field((DynamicMessage) field(reply, "reward"), "item_list")) {
        var item = (DynamicMessage) raw;
        actual.merge(number(item, "item_id"), number(item, "num"), Math::addExact);
      }
      require(actual.equals(expected), "Wrong complete original reward " + actual);
    }
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
    int phase = control.get("phase").getAsInt(), wins = 0, claims = 0;
    if (phase == 0) {
      require(
          number(info(1), "LFFBKJBJJDD") == 0 && number(info(6), "LFFBKJBJJDD") == 0,
          "Prewritten boxing completion");
      var rows = quests();
      require(
          !rows.containsKey(6000060) && !rows.containsKey(6000370),
          "Locked temporary goal published");
      System.out.println("READY_UID=" + uid);
      System.out.flush();
      boolean owned = false;
      for (int i = 0; i < 120 && !owned; i++) {
        for (var raw : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
          if (number((DynamicMessage) raw, "base_avatar_id") == 1001) owned = true;
        if (!owned) Thread.sleep(200);
      }
      require(owned, "Console formal fixture missing");
    } else if (phase == 1) {
      quest(6000060, 0, 1);
      quest(6000370, 0, 1);
      claim(6000075, false, Map.of());
      for (int id = 1; id <= 5; id++) {
        earnFirst(control, id);
        wins += 4;
        quest(6000075, id, id == 5 ? 2 : 1);
      }
      claims = claimAll(control, 6000060, 6000080);
    } else if (phase == 2) {
      quest(6000075, 5, 3);
      claim(6000075, false, Map.of());
      earn(control, 6, 9);
      wins += 3;
      quest(6000369, 1, 2);
      quest(6000370, 0, 1);
      quest(6000371, 0, 1);
      quest(6000372, 0, 1);
      quest(6000397, 1, 1);
      claim(6000370, false, Map.of());
      claim(6000397, false, Map.of());
    } else if (phase == 3) {
      quest(6000369, 1, 2);
      quest(6000370, 0, 1);
      earn(control, 6, 6);
      wins += 3;
      quest(6000370, 1, 2);
      quest(6000371, 1, 2);
      quest(6000372, 1, 2);
      for (int id = 7; id <= 12; id++) {
        earn(control, id, 3);
        wins += 3;
      }
      quest(6000397, 7, 2);
      claims = claimAll(control, 6000369, 6000397);
    } else if (phase == 4) {
      var rows = quests();
      for (int id = 6000060; id <= 6000080; id++)
        require(!rows.containsKey(id), "Expired first goal published");
      for (int id = 6000369; id <= 6000397; id++)
        require(!rows.containsKey(id), "Expired second goal published");
      claim(6000060, false, Map.of());
      claim(6000075, false, Map.of());
      claim(6000370, false, Map.of());
      claim(6000397, false, Map.of());
    } else {
      var rows = quests();
      for (int id = 6000060; id <= 6000080; id++) {
        require(number(rows.get(id), "status") == 3, "First nonperiodic claim reset");
        claim(id, false, Map.of());
      }
      for (int id = 6000369; id <= 6000397; id++) {
        require(number(rows.get(id), "status") == 3, "Second nonperiodic claim reset");
        claim(id, false, Map.of());
      }
    }
    if (phase > 0) {
      var ids =
          List.of(201073101, 201073201, 201073301, 201073401, 201073501, 801520101, 801520301);
      var request = builder("GetMissionStatusCsReq");
      set(request, "sub_mission_id_list", ids);
      var response = call("GetMissionStatusCsReq", request);
      require(number(response, "retcode") == 0, "Story status rejected");
      var statuses = new TreeMap<Integer, String>();
      for (var raw : (List<?>) field(response, "sub_mission_status_list")) {
        var row = (DynamicMessage) raw;
        statuses.put(
            number(row, "id"),
            ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(row, "status")).getName());
      }
      for (int id : ids)
        require(
            (id < 800000000 || phase >= 3
                    ? "MissionStatus_MissionFinish"
                    : "MissionStatus_MissionDoing")
                .equals(statuses.get(id)),
            "Original boxing story target differs: " + id + "=" + statuses.get(id));
    }
    require(!quests().containsKey(6000399), "Unlinked historical quest published");
    claim(6000399, false, Map.of());
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
        "claimedActivityRewards",
        claims,
        "completionPrewritten",
        false,
        "clientGameplayVerified",
        false,
        "storyGoalStatusVerified",
        phase > 0,
        "storyFloorScopeIsFixture",
        true);
  }

  public static void main(String[] args) {
    var probe = new NativeBoxingClubMissionGoalsProbe();
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
