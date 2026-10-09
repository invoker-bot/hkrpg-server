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
import kcp.highway.*;

/** Authored event-room placement over real UDP/KCP; effects remain explicitly unavailable. */
public final class LoopbackRogueRecoveryProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, leader, before, expectedMp;
  private final Map<Integer, Integer> boxes = new HashMap<>();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c, String name, int next) {
    step = next;
    send(c, name, request(name));
  }

  private void cast(Ukcp c, int index, int next, int... targets) {
    var b = request("SceneCastSkillCsReq");
    set(b, "cast_entity_id", leader);
    set(b, "attacked_by_entity_id", leader);
    set(b, "skill_index", index);
    for (int id : targets)
      b.addRepeatedField(b.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), id);
    step = next;
    send(c, "SceneCastSkillCsReq", b);
  }

  private void scene(DynamicMessage scene, boolean consumed) {
    require(number(scene, "entry_id") == 8000103, "Wrong rest-room entry");
    leader = number(scene, "leader_entity_id");
    require(leader > 0, "No current caster");
    var found = new HashMap<Integer, Integer>();
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var v : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var e = (DynamicMessage) v;
        var f = e.getDescriptorForType().findFieldByName("prop");
        if (!e.hasField(f) || number(e, "group_id") != 22) continue;
        int inst = number(e, "inst_id");
        if (!Set.of(300012, 300013, 300015).contains(inst)) continue;
        int prop = number((DynamicMessage) e.getField(f), "prop_id");
        require(
            prop == (inst == 300012 ? 100025 : 100024), "Original recovery prop identity changed");
        found.put(inst, number(e, "entity_id"));
      }
    require(found.containsKey(300015), "Untouched source HP box disappeared");
    require(
        consumed ? !found.containsKey(300012) && !found.containsKey(300013) : found.size() == 3,
        "Recovery box destruction/recreation state changed");
    if (!consumed) boxes.putAll(found);
    result.put(consumed ? "nativeConsumedBoxesAbsent" : "nativeOriginalBoxesPresent", true);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated recovery frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "GetCurLineupDataScRsp",
                  "SceneCastSkillScRsp",
                  "GetCurSceneInfoScRsp",
                  "LeaveRogueScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown recovery response fields");
      int retcode = number(m, "retcode");
      if (name.equals("SceneCastSkillScRsp") && step == 8) {
        require(
            retcode == emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(),
            "Stale recovery targets were not rejected: " + retcode);
        result.put("nativeStaleTargetsRejected", true);
      } else {
        require(
            retcode == 0,
            "Recovery request rejected: " + name + " step=" + step + " retcode=" + retcode);
      }
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" ->
            send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" -> {
          String cmd = resume ? "EnterRogueCsReq" : "StartRogueCsReq";
          var b = request(cmd);
          set(b, "area_id", 100);
          if (!resume)
            b.addRepeatedField(
                b.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
                emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
          send(c, cmd, b);
        }
        case "StartRogueScRsp", "EnterRogueScRsp" -> {
          scene((DynamicMessage) field(m, "scene"), resume);
          query(c, "GetCurLineupDataCsReq", resume ? 9 : 1);
        }
        case "GetCurLineupDataScRsp" -> {
          var lineup = (DynamicMessage) field(m, "lineup");
          int mp = number(lineup, "mp");
          if (step == 1) {
            before = mp;
            require(mp >= 2, "Initial technique points below fixture target");
            if (mp > 2) cast(c, 1, 2);
            else cast(c, 0, 4, boxes.get(300012));
          } else if (step == 3) {
            require(mp == before - 1, "Technique did not consume exactly one actual point");
            before = mp;
            if (mp > 2) cast(c, 1, 2);
            else cast(c, 0, 4, boxes.get(300012));
          } else if (step == 5) {
            expectedMp = Math.min(number(lineup, "max_mp"), before + 2);
            require(mp == expectedMp, "MP recovery did not affect the actual run");
            result.put("nativeMpRecovered", true);
            cast(c, 0, 6, boxes.get(300013));
          } else {
            require(step == 9 && mp == expectedMp, "Saved/stale-request technique points changed");
            result.put("mp", mp);
            result.put("nativeMpStable", true);
            query(c, "LeaveRogueCsReq", 10);
          }
        }
        case "SceneCastSkillScRsp" -> {
          require(
              !m.hasField(m.getDescriptorForType().findFieldByName("battle_info")),
              "Box destruction created a battle");
          if (step == 2) query(c, "GetCurLineupDataCsReq", 3);
          else if (step == 4) query(c, "GetCurLineupDataCsReq", 5);
          else if (step == 6) query(c, "GetCurSceneInfoCsReq", 7);
          else {
            require(step == 8, "Unexpected cast response");
            query(c, "GetCurLineupDataCsReq", 9);
          }
        }
        case "GetCurSceneInfoScRsp" -> {
          require(step == 7, "Unexpected scene response");
          scene((DynamicMessage) field(m, "scene"), true);
          cast(c, 0, 8, boxes.get(300012), boxes.get(300013));
        }
        case "LeaveRogueScRsp" -> {
          require(step == 10, "Unexpected leave response");
          result.put("savedRecoveryPropsRestored", resume);
          result.put("passed", true);
          result.put("curioMultiplierNetworkVerified", false);
          result.put("hpRecoveryDeltaNetworkVerified", false);
          result.put("clientGameplayVerified", false);
          completed.complete(result);
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackRogueRecoveryProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      if (p.resume) p.expectedMp = credentials.get("expectedMp").getAsInt();
      var token = p.request("PlayerGetTokenCsReq");
      set(token, "account_uid", credentials.get("accountUid").getAsString());
      set(token, "token", credentials.get("comboToken").getAsString());
      set(token, "platform", 1);
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
              p.send(c, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf b, Ukcp c) {
              try {
                p.receive(b, c);
              } catch (Throwable e) {
                p.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              p.completed.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              if (!p.completed.isDone())
                p.completed.completeExceptionally(
                    new IllegalStateException("Recovery probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(p.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Recovery probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
