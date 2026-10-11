package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Actual source NPC, source offering requests and reward replies over native UDP/KCP. */
public final class NativeKraterOfferingProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, rejected;

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }

  private static int number(DynamicMessage m, String key) {
    var value = field(m, key);
    return value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e
        ? e.getNumber()
        : (Integer) value;
  }

  private static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private DynamicMessage call(String name, DynamicMessage.Builder b) throws Exception {
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      channel.write(bytes);
      requests++;
    } finally {
      bytes.release();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Connection failure", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame != null
          && frame.command() == protocol.clientCommand(name.replace("CsReq", "ScRsp"))) {
        var reply = protocol.clientSchema().parse(name.replace("CsReq", "ScRsp"), frame.body());
        require(reply.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
        return reply;
      }
    }
    throw new TimeoutException(name);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage info() throws Exception {
    var b = builder("GetOfferingInfoCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("offering_id_list"), 6);
    var reply = call("GetOfferingInfoCsReq", b);
    require(number(reply, "retcode") == 0, "Source offering query rejected: " + reply);
    var rows = (List<?>) field(reply, "offering_info_list");
    require(rows.size() == 1, "Missing or duplicate offering");
    return (DynamicMessage) rows.getFirst();
  }

  private void progress(JsonObject expected) throws Exception {
    var state = info();
    require(
        number(state, "offering_id") == 6
            && number(state, "offering_state") == 2
            && number(state, "offering_level") == expected.get("level").getAsInt()
            && number(state, "level_exp") == expected.get("exp").getAsInt()
            && number(state, "total_exp") == expected.get("total").getAsInt(),
        "Source offering progress differs: " + state);
    var taken = new TreeSet<Integer>();
    ((List<?>) field(state, "has_taken_reward_id_list")).forEach(v -> taken.add((Integer) v));
    var wanted = new TreeSet<Integer>();
    expected.getAsJsonArray("taken").forEach(v -> wanted.add(v.getAsInt()));
    require(taken.equals(wanted), "Claimed source levels differ");
  }

  private int stock() throws Exception {
    var reply = call("GetBagCsReq");
    require(number(reply, "retcode") == 0, "Actual bag query rejected");
    int count = 0;
    for (var row : (List<?>) field(reply, "material_list")) {
      var material = (DynamicMessage) row;
      if (number(material, "tid") == 120004) count += number(material, "num");
    }
    return count;
  }

  private DynamicMessage mutate(String name, int entity, JsonArray levels) throws Exception {
    var b = builder(name);
    set(b, "offering_id", 6);
    set(b, "interacted_prop_entity_id", entity);
    if (levels != null)
      for (var v : levels)
        b.addRepeatedField(
            b.getDescriptorForType().findFieldByName("take_reward_level_list"), v.getAsInt());
    return call(name, b);
  }

  private void reject(DynamicMessage reply) {
    require(
        number(reply, "retcode") != 0, "Rejected operation paid or consumed material: " + reply);
    rejected++;
  }

  private void recipe(DynamicMessage reply, JsonObject expected) {
    require(number(reply, "retcode") == 0, "Reward rejected: " + reply);
    var actual = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field((DynamicMessage) field(reply, "reward"), "item_list")) {
      var item = (DynamicMessage) raw;
      actual.merge(number(item, "item_id"), number(item, "num"), Math::addExact);
    }
    var wanted = new TreeMap<Integer, Integer>();
    expected
        .entrySet()
        .forEach(e -> wanted.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
    require(
        actual.equals(wanted), "Original reward recipe differs: " + actual + " expected=" + wanted);
  }

  private DynamicMessage questClaim() throws Exception {
    var b = builder("TakeQuestRewardCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), 6029857);
    return call("TakeQuestRewardCsReq", b);
  }

  private void quest(int status) throws Exception {
    var reply = call("GetQuestDataCsReq");
    require(number(reply, "retcode") == 0, "Quest query rejected");
    var rows =
        ((List<?>) field(reply, "quest_list"))
            .stream().map(v -> (DynamicMessage) v).filter(v -> number(v, "id") == 6029857).toList();
    require(
        rows.size() == 1
            && number(rows.getFirst(), "status") == status
            && number(rows.getFirst(), "progress") == (status == 1 ? 0 : 1),
        "Original goal progress differs: " + rows);
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var reply = call("PlayerGetTokenCsReq", token);
    require(number(reply, "retcode") == 0, "Token rejected");
    int uid = number(reply, "uid");
    require(
        number(call("PlayerLoginCsReq"), "retcode") == 0
            && number(call("PlayerLoginFinishCsReq"), "retcode") == 0,
        "Login rejected");
    if (control.get("seed").getAsBoolean()) {
      System.out.println("READY_UID=" + uid);
      System.out.flush();
      boolean found = false;
      for (int i = 0; i < 120 && !found; i++) {
        for (var row : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
          if (number((DynamicMessage) row, "base_avatar_id") == 1001) found = true;
        if (!found) Thread.sleep(200);
      }
      require(found, "Missing explicit console roster fixture");
      return Map.of(
          "passed",
          true,
          "seed",
          true,
          "uid",
          uid,
          "nativeRequests",
          requests,
          "clientGameplayVerified",
          false);
    }
    var sceneReply = call("GetCurSceneInfoCsReq");
    require(number(sceneReply, "retcode") == 0, "Source scene query rejected");
    var scene = (DynamicMessage) field(sceneReply, "scene");
    require(
        number(scene, "plane_id") == 10402
            && number(scene, "floor_id") == 10402001
            && number(scene, "entry_id") == 1040201,
        "Source entry differs");
    int entity = 0;
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var e = (DynamicMessage) raw;
        if (number(e, "group_id") == 24 && number(e, "inst_id") == 400001) {
          require(
              entity == 0 && e.hasField(e.getDescriptorForType().findFieldByName("npc")),
              "Wrong or duplicate source offering NPC");
          require(
              number((DynamicMessage) field(e, "npc"), "npc_id") == 100, "Different source NPC");
          entity = number(e, "entity_id");
        }
      }
    require(entity > 0, "Missing original NPC");
    progress(control.getAsJsonObject("before"));
    int added = control.get("give").getAsInt();
    int base = stock();
    if (added > 0) {
      System.out.println("READY_MATERIAL=" + uid);
      System.out.flush();
      for (int i = 0; i < 120 && stock() != base + added; i++) Thread.sleep(200);
      require(stock() == base + added, "Missing real console material fixture");
      reject(mutate("SubmitOfferingItemCsReq", 0, null));
      reject(mutate("SubmitOfferingItemCsReq", 999999, null));
      require(stock() == base + added, "Invalid entity consumed material");
      require(
          number(mutate("SubmitOfferingItemCsReq", entity, null), "retcode") == 0,
          "Original NPC submission rejected");
      require(stock() == 0, "Actual submitted materials remain");
    }
    reject(mutate("SubmitOfferingItemCsReq", entity, null));
    progress(control.getAsJsonObject("submitted"));
    var levels = control.getAsJsonArray("levels");
    if (!levels.isEmpty()) {
      reject(mutate("TakeOfferingRewardCsReq", 0, levels));
      reject(mutate("TakeOfferingRewardCsReq", 999999, levels));
      recipe(mutate("TakeOfferingRewardCsReq", entity, levels), control.getAsJsonObject("rewards"));
      reject(mutate("TakeOfferingRewardCsReq", entity, levels));
    }
    progress(control.getAsJsonObject("after"));
    int status = control.get("questStatus").getAsInt();
    quest(status);
    if (control.get("claimGoal").getAsBoolean()) {
      recipe(questClaim(), control.getAsJsonObject("goalRewards"));
      var repeated = questClaim();
      reject(repeated);
      require(((List<?>) field(repeated, "succ_quest_id_list")).isEmpty(), "Repeated goal paid");
      quest(3);
    } else reject(questClaim());
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "nativeRequests",
        requests,
        "rejectedRequests",
        rejected,
        "sourceEntity",
        entity,
        "sourceOffering",
        6,
        "submittedMaterial",
        added,
        "claimedLevels",
        levels,
        "after",
        control.get("after"),
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeKraterOfferingProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
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
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable e) {
                probe.failure.set(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              probe.failure.set(e);
              probe.connected.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(control)) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      e.printStackTrace(System.err);
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
