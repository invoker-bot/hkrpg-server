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
public final class LoopbackRogueDialogueProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int expectedUnique, unique, step;
  private byte[] firstQuery;

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

  private void choose(Ukcp c, int option) {
    var b = request("SelectRogueCommonDialogueOptionCsReq");
    set(b, "AIAFEKGBFIF", unique);
    set(b, "HAFOKMHCGFM", option);
    send(c, "SelectRogueCommonDialogueOptionCsReq", b);
  }

  private void checkRun(DynamicMessage game) {
    var run = (DynamicMessage) field(game, "CFDIKLBINHK");
    int coins = number((DynamicMessage) field(run, "DHEKLJDBNNP"), "rogue_money");
    require(coins == 300, "Unavailable event changed wallet");
    result.put("fragments", coins);
  }

  private void entry(DynamicMessage scene) {
    require(number(scene, "entry_id") == 8000103, "Wrong authored event entrance");
    int actors = 0;
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) value;
        var f = entity.getDescriptorForType().findFieldByName("npc");
        if (!entity.hasField(f)) continue;
        var npc = (DynamicMessage) entity.getField(f);
        if (number(npc, "npc_id") != 3013) continue;
        actors++;
        require(
            number(entity, "group_id") == 21 && number(entity, "inst_id") == 400001,
            "Wrong original event actor placement");
        var extra = (DynamicMessage) field(npc, "extra_info");
        var event = (DynamicMessage) field(extra, "rogue_game_info");
        require(
            number(event, "OEANPIHCHHF") == 40398 && number(event, "MJBKNMJHOGP") == 0,
            "Incorrect native ID/progress semantics");
        unique = number(event, "AIAFEKGBFIF");
        require(unique > 0, "Missing native event lease");
        if (resume)
          require(unique == expectedUnique, "Event instance changed across reconnect/restart");
        result.put("entity", number(entity, "entity_id"));
      }
    require(actors == 1, "Expected one original event NPC");
    result.put("unique", unique);
    result.put("nativeNpcMetadata", true);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated event frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "GetRogueCommonDialogueDataScRsp",
                  "SelectRogueCommonDialogueOptionScRsp",
                  "GetRogueInfoScRsp",
                  "LeaveRogueScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown event response fields");
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Login rejected");
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Login finish rejected");
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
          require(step == 0 && number(m, "retcode") == 0, "Event room entry rejected");
          checkRun((DynamicMessage) field(m, "rogue_game_info"));
          entry((DynamicMessage) field(m, "scene"));
          step = 1;
          send(c, "GetRogueCommonDialogueDataCsReq", request("GetRogueCommonDialogueDataCsReq"));
        }
        case "GetRogueCommonDialogueDataScRsp" -> {
          require((step == 1 || step == 2) && number(m, "retcode") == 0, "Event query failed");
          var rows = (List<?>) field(m, "dialogue_list");
          require(rows.size() == 1, "Event query returned stale/extra actors");
          var row = (DynamicMessage) rows.getFirst();
          require(number(row, "AIAFEKGBFIF") == unique, "Query lease differs from scene");
          var owner = (DynamicMessage) field((DynamicMessage) field(row, "owner"), "MFAEGBJBJBE");
          require(number(owner, "OEANPIHCHHF") == 40398, "Wrong query owner dialogue");
          var options = (List<?>) field(row, "KFNMIEMBJGD");
          require(
              options.stream()
                  .map(DynamicMessage.class::cast)
                  .map(o -> number(o, "HAFOKMHCGFM"))
                  .toList()
                  .equals(List.of(14101, 14102, 14103)),
              "Original options changed");
          for (var value : options) {
            var option = (DynamicMessage) value;
            require(
                !(Boolean) field(option, "is_valid") && !(Boolean) field(option, "is_selected"),
                "Unknown effects were enabled/completed");
            require(
                ((List<?>) field(option, "LDCJONHGDAN")).isEmpty(),
                "Unknown effect results fabricated");
          }
          if (step == 1) {
            firstQuery = m.toByteArray();
            step = 2;
            send(c, "GetRogueCommonDialogueDataCsReq", request("GetRogueCommonDialogueDataCsReq"));
          } else {
            require(Arrays.equals(firstQuery, m.toByteArray()), "Repeat query mutated state");
            result.put("nativeOriginalDialogueQuery", true);
            result.put("nativeQueryStable", true);
            step = 3;
            choose(c, 14101);
          }
        }
        case "SelectRogueCommonDialogueOptionScRsp" -> {
          require(number(m, "AIAFEKGBFIF") == unique, "Selection echo changed lease");
          if (step == 3) {
            require(
                number(m, "retcode") == 2901 && number(m, "HAFOKMHCGFM") == 14101,
                "Unsupported effect falsely succeeded");
            result.put("nativeUnsupportedEffectRejected", true);
            step = 4;
            choose(c, 14104);
          } else {
            require(
                step == 4 && number(m, "retcode") == 5 && number(m, "HAFOKMHCGFM") == 14104,
                "Foreign option accepted");
            result.put("nativeForeignOptionRejected", true);
            step = 5;
            send(c, "GetRogueInfoCsReq", request("GetRogueInfoCsReq"));
          }
        }
        case "GetRogueInfoScRsp" -> {
          require(step == 5 && number(m, "retcode") == 0, "Post-event universe query failed");
          checkRun((DynamicMessage) field(m, "rogue_game_info"));
          result.put("eventWalletUnchanged", true);
          step = 6;
          send(c, "LeaveRogueCsReq", request("LeaveRogueCsReq"));
        }
        case "LeaveRogueScRsp" -> {
          require(step == 6 && number(m, "retcode") == 0, "Event room leave failed");
          result.put("savedEventLeaseRestored", resume);
          result.put("passed", true);
          result.put("completeEventEffects", false);
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
      var p = new LoopbackRogueDialogueProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      if (p.resume) p.expectedUnique = credentials.get("expectedUnique").getAsInt();
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
                    new IllegalStateException("Event probe disconnected"));
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
          "Event probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
