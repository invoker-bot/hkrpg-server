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

/** Saved active trials over real UDP/KCP; does not launch the game client. */
public final class LoopbackTrialRecoveryProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private JsonObject input;
  private int step;
  private boolean activeTrialNotification;
  private static final int STAGE = 310010;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder body) {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], body.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void send(Ukcp c, String name) {
    send(c, name, request(name));
  }

  private void stage(Ukcp c, String name) {
    var b = request(name);
    b.setField(b.getDescriptorForType().findFieldByName("stage_id"), STAGE);
    send(c, name, b);
  }

  private Map<String, Integer> place(DynamicMessage scene) {
    return Map.of(
        "entry",
        (Integer) field(scene, "entry_id"),
        "plane",
        (Integer) field(scene, "plane_id"),
        "floor",
        (Integer) field(scene, "floor_id"));
  }

  private void complete() {
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated trial recovery frame");
      if (frame.cmdId() == protocol.clientCommand("CurTrialActivityScNotify")) {
        var message = protocol.clientSchema().parse("CurTrialActivityScNotify", frame.body());
        var type = message.getDescriptorForType();
        require(
            ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                message.getField(type.findFieldByNumber(10))).getNumber() == 0,
            "Manual trial recovery fabricated completion");
        require((Integer) message.getField(type.findFieldByNumber(1)) == STAGE,
            "Restored trial notification used the wrong stage");
        require(input.has("resume") && input.get("resume").getAsBoolean(),
            "Fresh login fabricated an active trial");
        activeTrialNotification = true;
        result.put("activeTrialNotification", true);
        continue;
      }
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StartTrialActivityScRsp",
                  "GetTrialActivityDataScRsp",
                  "GetCurSceneInfoScRsp",
                  "GetCurLineupDataScRsp",
                  "LeaveTrialActivityScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(
          (Integer) field(m, "retcode") == 0,
          "Trial recovery request failed: " + name + " / " + field(m, "retcode"));
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> send(c, "PlayerLoginFinishCsReq");
        case "PlayerLoginFinishScRsp" -> {
          if (input.has("resume") && input.get("resume").getAsBoolean()) {
            require(activeTrialNotification,
                "Restored login did not initialize the native RoleTrialInstance");
            step = 4;
            send(c, "GetTrialActivityDataCsReq");
          } else {
            step = 1;
            send(c, "GetCurSceneInfoCsReq");
          }
        }
        case "StartTrialActivityScRsp" -> {
          require(
              step == 3 && (Integer) field(m, "stage_id") == STAGE,
              "Wrong saved trial start identity");
          step = 4;
          send(c, "GetTrialActivityDataCsReq");
        }
        case "GetTrialActivityDataScRsp" -> {
          require(
              step == 4 && (Integer) field(m, "activity_stage_id") == STAGE,
              "Active trial identity was not restored");
          step = 5;
          send(c, "GetCurSceneInfoCsReq");
        }
        case "GetCurSceneInfoScRsp" -> {
          var scene = (DynamicMessage) field(m, "scene");
          var actors = new TreeSet<Integer>();
          for (var row : (List<?>) field(scene, "entity_group_list"))
            for (var value : (List<?>) field((DynamicMessage) row, "entity_list")) {
              var entity = (DynamicMessage) value;
              var actorField = entity.getDescriptorForType().findFieldByName("actor");
              if (entity.hasField(actorField)) {
                var actor = (DynamicMessage) entity.getField(actorField);
                if (((com.google.protobuf.Descriptors.EnumValueDescriptor)
                            field(actor, "avatar_type"))
                        .getNumber()
                    == 1) actors.add((Integer) field(actor, "base_avatar_id"));
              }
            }
          if (step == 1) {
            require(actors.isEmpty(), "New trial started inside another trial");
            result.put("home", place(scene));
            step = 2;
            send(c, "GetCurLineupDataCsReq");
          } else if (step == 5) {
            require(actors.size() == 3, "Saved trial scene lost its actual temporary actors: " + actors);
            result.put("trialScene", place(scene));
            result.put("trialActors", actors);
            step = 6;
            send(c, "GetCurLineupDataCsReq");
          } else {
            require(
                step == 8 && actors.isEmpty(),
                "Trial exit left temporary actors in the ordinary scene");
            var home = input.getAsJsonObject("expectedHome");
            for (var e : place(scene).entrySet())
              require(
                  home.get(e.getKey()).getAsInt() == e.getValue(),
                  "Trial recovery changed the original home: " + e.getKey());
            step = 9;
            send(c, "GetCurLineupDataCsReq");
          }
        }
        case "GetCurLineupDataScRsp" -> {
          var lineup = (DynamicMessage) field(m, "lineup");
          if (step == 2) {
            result.put("homeLineup", Base64.getEncoder().encodeToString(lineup.toByteArray()));
            step = 3;
            stage(c, "StartTrialActivityCsReq");
          } else if (step == 6) {
            require(
                field(lineup, "extra_lineup_type")
                    .toString()
                    .equals("ExtraLineupType_LineupStageTrial"),
                "Trial recovery selected the ordinary party");
            var avatars = (List<?>) field(lineup, "avatar_list");
            require(avatars.size() == ((Collection<?>) result.get("trialActors")).size(), "Saved trial roster was lost");
            for (var row : avatars) {
              var actor = (DynamicMessage) row;
              require(
                  (Integer) field(actor, "hp") == 10000
                      && (Integer) field((DynamicMessage) field(actor, "sp_bar"), "cur_sp") == 0,
                  "Saved trial HP/SP changed after reconnect");
            }
            result.put("trialLineup", Base64.getEncoder().encodeToString(lineup.toByteArray()));
            result.put("activeTrialSnapshot", true);
            if (input.has("leave") && input.get("leave").getAsBoolean()) {
              step = 7;
              stage(c, "LeaveTrialActivityCsReq");
            } else complete();
          } else {
            require(
                step == 9
                    && Base64.getEncoder()
                        .encodeToString(lineup.toByteArray())
                        .equals(input.get("expectedHomeLineup").getAsString()),
                "Trial recovery changed the ordinary party, HP/SP or secret points");
            result.put("originalHomeAndPartyRestored", true);
            complete();
          }
        }
        case "LeaveTrialActivityScRsp" -> {
          require(
              step == 7 && (Integer) field(m, "stage_id") == STAGE,
              "Wrong recovered trial exit identity");
          step = 8;
          send(c, "GetCurSceneInfoCsReq");
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackTrialRecoveryProbe();
      probe.input =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      var token = probe.request("PlayerGetTokenCsReq");
      token.setField(
          token.getDescriptorForType().findFieldByName("account_uid"),
          probe.input.get("accountUid").getAsString());
      token.setField(
          token.getDescriptorForType().findFieldByName("token"),
          probe.input.get("comboToken").getAsString());
      token.setField(token.getDescriptorForType().findFieldByName("platform"), 1);
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
              probe.send(c, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                probe.receive(bytes, c);
              } catch (Exception e) {
                probe.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              probe.completed.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Trial recovery disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Trial recovery failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }

  private LoopbackTrialRecoveryProbe() {}
}
