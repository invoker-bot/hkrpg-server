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

/** External-only real room victories, pending menu recovery and exact-resource rejection. */
public final class NativeBlessingSelectionProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private DynamicMessage pending;
  private int requests, victories, selections;
  private static final int BUFF = 612050;

  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }

  private static int number(DynamicMessage m, String key) {
    return (Integer) field(m, key);
  }

  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private void observe(Frame frame) throws Exception {
    if (frame.command() == protocol.clientCommand("SyncRogueCommonPendingActionScNotify"))
      pending = protocol.clientSchema().parse("SyncRogueCommonPendingActionScNotify", frame.body());
    if (frame.command() == protocol.clientCommand("SyncRogueCommonActionResultScNotify")) {
      var m = protocol.clientSchema().parse("SyncRogueCommonActionResultScNotify", frame.body());
      for (var raw : (List<?>) field(m, "CLGGKLNPLCK")) {
        var detail = (DynamicMessage) field((DynamicMessage) raw, "detail");
        var name = detail.getDescriptorForType().findFieldByName("ABIFHABICLO");
        if (name != null && detail.hasField(name)) selections++;
      }
    }
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
      observe(frame);
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

  private DynamicMessage current() throws Exception {
    var reply = call("GetRogueInfoCsReq");
    require(number(reply, "retcode") == 0, "Run query failed");
    var current =
        (DynamicMessage) field((DynamicMessage) field(reply, "rogue_game_info"), "CFDIKLBINHK");
    var action = current.getDescriptorForType().findFieldByName("FMIGOKIDKJL");
    if (current.hasField(action)) {
      var snapshot = builder("SyncRogueCommonPendingActionScNotify");
      set(snapshot, "DLOJFEEHLIJ", 101);
      set(snapshot, "action", current.getField(action));
      pending = snapshot.build();
    }
    return current;
  }

  private int coins(DynamicMessage current) {
    return number((DynamicMessage) field(current, "DHEKLJDBNNP"), "rogue_money");
  }

  private Map<Integer, Integer> owned(DynamicMessage current) {
    var result = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field((DynamicMessage) field(current, "HABJHGPFGEB"), "CHCMEKAFKAF")) {
      var row = (DynamicMessage) raw;
      require(
          result.put(number(row, "buff_id"), number(row, "level")) == null, "Duplicate blessing");
    }
    return result;
  }

  private int menu() {
    require(pending != null, "Missing server-earned pending menu");
    var action = (DynamicMessage) field(pending, "action");
    var offer = (DynamicMessage) field((DynamicMessage) field(action, "detail"), "PCHGEGFBKLF");
    var rows = (List<?>) field(offer, "LEOFNINDJDB");
    require(rows.size() == 1, "Wrong candidate count");
    var row = (DynamicMessage) rows.getFirst();
    require(
        number(row, "buff_id") == BUFF && number(row, "HAGDKKBAAKK") == 1,
        "Wrong source blessing/tier");
    return number(action, "queue_position");
  }

  private DynamicMessage choose(int batch) throws Exception {
    var choice = builder("BPLNKHGAILK");
    set(choice, "BAOFIGFFMLN", BUFF);
    var req = builder("OFEDNNJIJFL");
    set(req, "MPLGEEGOAMC", batch);
    set(req, "DLKIKBIDJDB", choice.build());
    return call("OFEDNNJIJFL", req);
  }

  private void fight(DynamicMessage scene, int target) throws Exception {
    var attack = builder("SceneCastSkillCsReq");
    set(attack, "cast_entity_id", field(scene, "leader_entity_id"));
    set(attack, "attacked_by_entity_id", field(scene, "leader_entity_id"));
    attack.addRepeatedField(
        attack.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), target);
    var cast = call("SceneCastSkillCsReq", attack);
    require(number(cast, "retcode") == 0, "Source encounter rejected");
    var battle = (DynamicMessage) field(cast, "battle_info");
    int id = number(battle, "battle_id"), stage = number(battle, "stage_id");
    require(id > 0 && stage > 0, "Missing resource battle");
    var result = builder("PVEBattleResultCsReq");
    set(result, "battle_id", id);
    set(result, "stage_id", stage);
    var end = result.getDescriptorForType().findFieldByName("end_status");
    result.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    require(
        number(call("PVEBattleResultCsReq", result), "retcode") == 0, "Source victory rejected");
    victories++;
    require(
        number(call("PVEBattleResultCsReq", result), "retcode") != 0, "Repeated victory accepted");
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var authenticated = call("PlayerGetTokenCsReq", token);
    require(number(authenticated, "retcode") == 0, "Token rejected");
    int uid = number(authenticated, "uid");
    require(number(call("PlayerLoginCsReq"), "retcode") == 0, "Login rejected");
    require(number(call("PlayerLoginFinishCsReq"), "retcode") == 0, "Login finish rejected");
    String mode = control.get("mode").getAsString();
    boolean fresh = mode.equals("earn");
    var entry = builder(fresh ? "StartRogueCsReq" : "EnterRogueCsReq");
    set(entry, "area_id", 100);
    if (fresh)
      entry.addRepeatedField(
          entry.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
          emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
    var entered = call(fresh ? "StartRogueCsReq" : "EnterRogueCsReq", entry);
    require(number(entered, "retcode") == 0, "Entry rejected");
    if (fresh) {
      var scene = (DynamicMessage) field(entered, "scene");
      var enemies = new ArrayList<Integer>();
      require(number(scene, "entry_id") == 8000101, "Wrong original room entry");
      for (var group : (List<?>) field(scene, "entity_group_list"))
        for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
          var entity = (DynamicMessage) raw;
          if (entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster")))
            enemies.add(number(entity, "entity_id"));
        }
      require(!enemies.isEmpty() && enemies.size() <= 20, "Missing original encounters");
      for (int enemy : enemies) fight(scene, enemy);
    }
    var before = current();
    int balance = coins(before);
    var old = owned(before);
    int batch = fresh ? menu() : control.get("batch").getAsInt();
    if (!mode.equals("repeat"))
      require(menu() == batch && old.isEmpty(), "Pending menu or ownership changed across restart");
    int retcode = -1;
    if (mode.equals("missing")) {
      var source = pending.toByteArray();
      var rejected = choose(batch);
      retcode = number(rejected, "retcode");
      require(
          retcode == 5 && number(rejected, "BFNEPPCNOOI") == batch,
          "Missing resource selection accepted");
      require(before.equals(current()), "Rejected choice changed run state");
      require(Arrays.equals(source, pending.toByteArray()), "Rejected choice changed menu");
    } else if (mode.equals("restore") || mode.equals("repeat")) {
      var picked = choose(batch);
      retcode = number(picked, "retcode");
      require(retcode == (mode.equals("restore") ? 0 : 5), "Unexpected selection result");
      var after = current();
      require(
          coins(after) == balance && owned(after).equals(Map.of(BUFF, 1)),
          "Blessing or wallet incorrect");
      var settled = after;
      require(number(choose(batch), "retcode") == 5, "Duplicate selection accepted");
      require(settled.equals(current()), "Repeat changed run");
    }
    var after = current();
    return Map.ofEntries(
        Map.entry("uid", uid),
        Map.entry("passed", true),
        Map.entry("mode", mode),
        Map.entry("batch", batch),
        Map.entry("blessing", BUFF),
        Map.entry("level", 1),
        Map.entry("fragments", coins(after)),
        Map.entry("owned", owned(after)),
        Map.entry("retcode", retcode),
        Map.entry("actualVictories", victories),
        Map.entry("nativeRequests", requests),
        Map.entry("clientGameplayVerified", false),
        Map.entry("combatInputReplayVerified", false));
  }

  public static void main(String[] args) {
    var probe = new NativeBlessingSelectionProbe();
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
