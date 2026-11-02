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

/** Synthetic authenticated music and offering requests; never official game-client acceptance. */
public final class NativeMusicRewardsProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private boolean closing, loggedIn;
  private int requests;

  private static void require(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
  private static Object field(DynamicMessage m, String key) { return m.getField(m.getDescriptorForType().findFieldByName(key)); }
  private static int number(DynamicMessage m, String key) { return (Integer) field(m, key); }
  private DynamicMessage.Builder builder(String name) { return DynamicMessage.newBuilder(protocol.clientSchema().message(name)); }
  private static void set(DynamicMessage.Builder b, String key, Object value) { b.setField(b.getDescriptorForType().findFieldByName(key), value); }
  private static void add(DynamicMessage.Builder b, String key, Object value) { b.addRepeatedField(b.getDescriptorForType().findFieldByName(key), value); }
  private void send(String name, DynamicMessage.Builder b) {
    var packet = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[]{8, 1}, b.build().toByteArray()));
    try { channel.write(packet); requests++; } finally { packet.release(); }
  }
  private DynamicMessage call(String name, DynamicMessage.Builder b, int expected) throws Exception {
    send(name, b);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    int command = protocol.clientCommand(name.replace("CsReq", "ScRsp"));
    while (System.nanoTime() < deadline) {
      require(failure.get() == null, "KCP transport failure");
      var frame = inbox.poll(250, TimeUnit.MILLISECONDS);
      if (frame == null || frame.command != command) continue;
      var response = protocol.clientSchema().parse(name.replace("CsReq", "ScRsp"), frame.body);
      require(response.getUnknownFields().asMap().isEmpty(), "Unknown native response fields");
      require(number(response, "retcode") == expected, name + " retcode=" + number(response, "retcode") + " expected=" + expected);
      return response;
    }
    throw new IllegalStateException("Timed out: " + name);
  }
  private DynamicMessage call(String name) throws Exception { return call(name, builder(name), 0); }
  private void start(int level, int expected) throws Exception {
    var b = builder("MusicRhythmStartLevelCsReq"); set(b, "level_id", level);
    var response = call("MusicRhythmStartLevelCsReq", b, expected);
    if (expected == 0) require(!field(response, "trans_no").toString().isEmpty(), "Missing saved run transaction");
  }
  private void finish(int expected) throws Exception {
    var b = builder("MusicRhythmFinishLevelCsReq"); set(b, "role_star", 3); set(b, "full_combo", true);
    call("MusicRhythmFinishLevelCsReq", b, expected);
  }
  private int material(int id) throws Exception {
    var bag = call("GetBagCsReq"); int count = 0;
    for (var value : (List<?>) field(bag, "material_list")) {
      var row = (DynamicMessage) value;
      if (number(row, "tid") == id) count += number(row, "num");
    }
    return count;
  }
  private DynamicMessage offering() throws Exception {
    var b = builder("GetOfferingInfoCsReq"); add(b, "offering_id_list", 5);
    var response = call("GetOfferingInfoCsReq", b, 0);
    var rows = (List<?>) field(response, "offering_info_list");
    require(rows.size() == 1, "Missing music offering"); return (DynamicMessage) rows.getFirst();
  }
  private DynamicMessage claim(List<Integer> levels, int expected) throws Exception {
    var b = builder("TakeOfferingRewardCsReq"); set(b, "offering_id", 5);
    levels.forEach(level -> add(b, "take_reward_level_list", level));
    return call("TakeOfferingRewardCsReq", b, expected);
  }
  private void scene(boolean visible) throws Exception {
    var response = call("GetCurSceneInfoCsReq"); var scene = (DynamicMessage) field(response, "scene");
    require(number(scene, "floor_id") == 20101001, "Fixture is not in the authored snowfield");
    boolean found = false;
    for (var value : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) value;
      if (number(group, "group_id") != 102) continue;
      for (var raw : (List<?>) field(group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var npc = (DynamicMessage) field(entity, "npc");
        if (number(npc, "npc_id") == 3025) found = true;
      }
    }
    require(found == visible, "Playable snowfield NPC disagrees with the original eligibility gate");
  }
  private void rating(int level, int expected) throws Exception {
    var response = call("MusicRhythmDataCsReq");
    var rows = ((List<?>) field(response, "level_data_list")).stream().map(DynamicMessage.class::cast)
        .filter(row -> number(row, "level_id") == level).toList();
    require(rows.size() == 1 && number(rows.getFirst(), "unlock_level") == expected, "Saved rating changed for " + level);
  }
  private Map<String, Object> execute(JsonObject control) throws Exception {
    var b = builder("PlayerGetTokenCsReq"); set(b, "account_uid", control.get("accountUid").getAsString());
    set(b, "token", control.get("comboToken").getAsString()); set(b, "platform", 1);
    int uid = number(call("PlayerGetTokenCsReq", b, 0), "uid");
    call("PlayerLoginCsReq"); call("PlayerLoginFinishCsReq"); loggedIn = true;
    String mode = control.get("mode").getAsString();
    int badges = 0;
    if (mode.equals("locked")) {
      scene(false); start(601, Retcode.MUSIC_LOCKED.getVal()); finish(Retcode.MUSIC_LOCKED.getVal());
      require(material(270003) == 0, "Locked run granted a badge");
    } else if (mode.equals("earn")) {
      scene(true); rating(601, 0); start(601, 0); finish(0);
      finish(Retcode.MUSIC_LOCKED.getVal()); rating(601, 3);
      badges = material(270003); require(badges == 3, "Three-star source reward did not reach inventory");
    } else if (mode.equals("redeem")) {
      scene(true); rating(601, 3); require(material(270003) == 3, "Badges did not survive restart");
      start(602, 0); finish(0); require(material(270003) == 6, "Hard-mode reward missing");
      var submit = builder("SubmitOfferingItemCsReq"); set(submit, "offering_id", 5);
      call("SubmitOfferingItemCsReq", submit, 0);
      require(number(offering(), "offering_level") == 2, "Six badges did not advance two original levels");
      require(material(270003) == 0, "Actual badges were not consumed");
      var reward = (DynamicMessage) field(claim(List.of(1, 2), 0), "reward");
      var plan = new TreeMap<Integer, Integer>();
      for (var raw : (List<?>) field(reward, "item_list")) {
        var item = (DynamicMessage) raw; plan.merge(number(item, "item_id"), number(item, "num"), Integer::sum);
      }
      require(plan.equals(Map.of(1, 60, 2, 20000, 22, 200, 223, 4)), "Wrong original performance rewards: " + plan);
      claim(List.of(1), Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal());
      require(material(223) == 4, "Reward material did not reach actual inventory");
    } else if (mode.equals("verify")) {
      scene(true); rating(601, 3); rating(602, 3);
      var info = offering();
      require(number(info, "offering_level") == 2 && number(info, "total_exp") == 6, "Offering progress did not survive restart");
      require(new HashSet<>((List<?>) field(info, "has_taken_reward_id_list")).equals(Set.of(1, 2)), "Claimed levels changed");
      claim(List.of(1, 2), Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal());
      require(material(270003) == 0 && material(223) == 4, "Duplicate claim changed persisted inventory");
    } else require(mode.equals("bootstrap"), "Unknown fixture mode");
    return new LinkedHashMap<>(Map.of("passed", true, "uid", uid, "mode", mode,
        "badges", badges, "clientGameplayVerified", false));
  }
  private void logout() throws InterruptedException {
    if (!loggedIn || closing) return;
    closing = true; send("PlayerLogoutCsReq", builder("PlayerLogoutCsReq")); Thread.sleep(250);
  }
  public static void main(String[] args) {
    var probe = new NativeMusicRewardsProbe(); var client = new KcpClient(); Ukcp connection = null; int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
      var config = new ChannelConfig(); config.nodelay(true, 40, 2, true); config.setMtu(1200);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(15000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp value) { probe.connected.complete(value); }
        public void handleReceive(ByteBuf bytes, Ukcp value) {
          try { while (bytes.isReadable()) { var frame = PacketCodec.decodeNext(bytes); require(frame != null, "Truncated packet"); probe.inbox.add(new Frame(frame.cmdId(), frame.body())); } }
          catch (Throwable error) { probe.failure.compareAndSet(null, error); }
        }
        public void handleException(Throwable error, Ukcp value) { if (!probe.closing) probe.failure.compareAndSet(null, error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp value) { if (!probe.closing) probe.failure.compareAndSet(null, new IllegalStateException("Unexpected disconnect")); }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      var report = probe.execute(control); probe.logout(); report.put("nativeRequests", probe.requests);
      System.out.println(new Gson().toJson(report)); exit = 0;
    } catch (Exception failure) { System.err.println("Music reward probe failed: " + failure); }
    finally { try { probe.logout(); } catch (Exception ignored) {} if (connection != null) connection.close(); client.stop(); }
    System.exit(exit);
  }
}
