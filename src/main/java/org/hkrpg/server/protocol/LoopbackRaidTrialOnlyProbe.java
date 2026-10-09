package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Authored trial-only entries and normal storage restoration; no game executable is used. */
public final class LoopbackRaidTrialOnlyProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private JsonObject credentials;
  private int step;
  private boolean ordinary;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  private int number(String name) {
    return credentials.get(name).getAsInt();
  }

  private boolean flag(String name) {
    return credentials.has(name) && credentials.get(name).getAsBoolean();
  }

  private boolean formalParty() {
    return flag("formalMode") || flag("playerMode");
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp channel, String name, DynamicMessage.Builder body) {
    System.err.println("Trial-only request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], body.build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp channel, int next) {
    step = next;
    send(channel, "GetCurLineupDataCsReq", request("GetCurLineupDataCsReq"));
  }

  private void start(Ukcp channel, boolean invalid) {
    step = invalid ? 3 : 4;
    var body = request("StartRaidCsReq");
    set(body, "raid_id", number("raid"));
    if (invalid) {
      var choices = body.getDescriptorForType().findFieldByName("NIMDEPFJJNI");
      var row = DynamicMessage.newBuilder(choices.getMessageType());
      set(row, "id", flag("playerMode") ? 1111 : 8001);
      var type = row.getDescriptorForType().findFieldByName("avatar_type");
      row.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
      body.addRepeatedField(choices, row.build());
      if (flag("duplicateRoleMode")) {
        for (int id : selected()) {
          var trial = DynamicMessage.newBuilder(choices.getMessageType());
          set(trial, "id", id);
          trial.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
          body.addRepeatedField(choices, trial.build());
        }
      }
    } else if (flag("formalMode") && !flag("reenter")
        || flag("pathMode")
        || flag("candidateMode")) {
      var choices = body.getDescriptorForType().findFieldByName("NIMDEPFJJNI");
      for (int id : selected()) {
        var row = DynamicMessage.newBuilder(choices.getMessageType());
        set(row, "id", id);
        var type = row.getDescriptorForType().findFieldByName("avatar_type");
        row.setField(
            type,
            type.getEnumType()
                .findValueByName(
                    flag("pathMode") || flag("candidateMode")
                        ? "AvatarType_AvatarTrialType"
                        : "AvatarType_AvatarFormalType"));
        body.addRepeatedField(choices, row.build());
      }
    }
    send(channel, "StartRaidCsReq", body);
  }

  private List<Integer> selected() {
    return credentials.getAsJsonArray("selected").asList().stream()
        .map(JsonElement::getAsInt)
        .toList();
  }

  private void lineup(DynamicMessage m) {
    require((Integer) field(m, "retcode") == 0, "Lineup query rejected");
    var lineup = (DynamicMessage) field(m, "lineup");
    var rows = (List<?>) field(lineup, "avatar_list");
    var ids = new ArrayList<Integer>();
    for (var raw : rows) {
      var avatar = (DynamicMessage) raw;
      ids.add((Integer) field(avatar, "id"));
      require(
          ((EnumValueDescriptor) field(avatar, "avatar_type"))
              .getName()
              .equals(
                  ordinary || formalParty()
                      ? "AvatarType_AvatarFormalType"
                      : "AvatarType_AvatarTrialType"),
          "Wrong semantic actor type");
    }
    require(ids.equals(ordinary ? List.of(8001) : selected()), "Automatic party changed");
    if (!ordinary) {
      require(
          ((EnumValueDescriptor) field(lineup, "extra_lineup_type")).getNumber() == 13,
          "Wrong raid lineup type");
      require(
          (Integer) field(lineup, "leader_slot") == number("leader"), "Declared captain changed");
      result.put(formalParty() ? "frozenFormalRoster" : "automaticRoster", ids);
      result.put("leader", number("leader"));
    } else result.put("ordinaryRosterRestored", true);
  }

  private void done() {
    result.put("raid", number("raid"));
    result.put("mode", credentials.get("mode").getAsString());
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    result.put("officialStoryMissionVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp channel) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated trial-only frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "EnterSceneScRsp",
                  "AcceptMainMissionScRsp",
                  "MusicRhythmStartLevelScRsp",
                  "MusicRhythmFinishLevelScRsp",
                  "StartRaidScRsp",
                  "GetCurLineupDataScRsp",
                  "ChangeLineupLeaderScRsp",
                  "GetCurSceneInfoScRsp",
                  "GetAvatarDataScRsp",
                  "GetMissionDataScRsp",
                  "LeaveRaidScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response field " + name);
      int code = (Integer) field(m, "retcode");
      System.err.println("Trial-only response=" + name + ", step=" + step + ", code=" + code);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(code == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          send(channel, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require(code == 0, "Login rejected");
          send(channel, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require(code == 0, "Login finish rejected");
          if (flag("reenter")) start(channel, false);
          else if (flag("resume")) query(channel, 5);
          else {
            step = 1;
            var body = request("EnterSceneCsReq");
            set(body, "entry_id", 1010101);
            send(channel, "EnterSceneCsReq", body);
          }
        }
        case "EnterSceneScRsp" -> {
          require(step == 1 && code == 0, "Isolated ordinary entry rejected");
          step = 2;
          var body = request("AcceptMainMissionCsReq");
          set(body, "main_mission_id", number("parent"));
          send(channel, "AcceptMainMissionCsReq", body);
        }
        case "AcceptMainMissionScRsp" -> {
          require(step == 2 && code == 0, "Isolated parent rejected");
          if (flag("formalMode")) {
            step = 13;
            var body = request("MusicRhythmStartLevelCsReq");
            set(body, "level_id", 101);
            send(channel, "MusicRhythmStartLevelCsReq", body);
          } else start(channel, true);
        }
        case "MusicRhythmStartLevelScRsp" -> {
          require(step == 13 && code == 0, "Isolated formal funding start rejected");
          step = 14;
          var body = request("MusicRhythmFinishLevelCsReq");
          set(body, "role_star", 1);
          send(channel, "MusicRhythmFinishLevelCsReq", body);
        }
        case "MusicRhythmFinishLevelScRsp" -> {
          require(step == 14 && code == 0, "Isolated formal funding rejected");
          start(channel, true);
        }
        case "StartRaidScRsp" -> {
          if (step == 3) {
            require(code != 0, "Formal substitution accepted");
            result.put(
                flag("playerMode")
                    ? "unownedFormalRejected"
                    : flag("formalMode")
                        ? "missingRequiredRoleRejected"
                        : "formalSubstitutionRejected",
                true);
            start(channel, false);
          } else {
            require(step == 4 && code == 0, "Authored trial-only entry rejected");
            query(channel, 5);
          }
        }
        case "GetCurLineupDataScRsp" -> {
          lineup(m);
          if (!ordinary && step == 5 && flag("locked") && selected().size() > 1) {
            step = 6;
            var body = request("ChangeLineupLeaderCsReq");
            set(body, "slot", (number("leader") + 1) % selected().size());
            send(channel, "ChangeLineupLeaderCsReq", body);
          } else {
            step = 8;
            send(channel, "GetCurSceneInfoCsReq", request("GetCurSceneInfoCsReq"));
          }
        }
        case "ChangeLineupLeaderScRsp" -> {
          require(step == 6 && code != 0, "Locked captain changed");
          result.put("captainChangeRejected", true);
          query(channel, 7);
        }
        case "GetCurSceneInfoScRsp" -> {
          require(step == 8 && code == 0, "Scene query rejected");
          var scene = (DynamicMessage) field(m, "scene");
          require(
              (Integer) field(scene, "entry_id") == (ordinary ? 1010101 : number("entry"))
                  && (Integer) field(scene, "plane_id") == (ordinary ? 10101 : number("plane"))
                  && (Integer) field(scene, "floor_id") == (ordinary ? 10101001 : number("floor")),
              "Authored or return scene changed");
          result.put(ordinary ? "ordinaryReturnScene" : "sourceRaidScene", true);
          step = 9;
          send(channel, "GetAvatarDataCsReq", request("GetAvatarDataCsReq"));
        }
        case "GetAvatarDataScRsp" -> {
          require(step == 9 && code == 0, "Owned storage query rejected");
          var ids =
              ((List<?>) field(m, "avatar_list"))
                  .stream()
                      .map(raw -> (Integer) field((DynamicMessage) raw, "base_avatar_id"))
                      .toList();
          require(
              new HashSet<>(ids).equals(new HashSet<>(formalParty() ? selected() : List.of(8001))),
              "Owned storage differs from prepared roles");
          result.put(
              formalParty() ? "ownedStorageMatchesPreparedRoster" : "formalStorageUnchanged", true);
          if (ordinary) done();
          else {
            step = 10;
            send(channel, "GetMissionDataCsReq", request("GetMissionDataCsReq"));
          }
        }
        case "GetMissionDataScRsp" -> {
          require(
              step == 10
                  && code == 0
                  && !((List<?>) field(m, "finished_main_mission_id_list"))
                      .contains(number("main")),
              "Unplayed story falsely completed");
          var first =
              ((List<?>) field(m, "mission_list"))
                  .stream()
                      .map(raw -> (DynamicMessage) raw)
                      .filter(row -> (Integer) field(row, "id") == number("first"))
                      .findFirst()
                      .orElseThrow();
          require((Integer) field(first, "progress") == 0, "Unplayed source objective advanced");
          result.put("sourceFirstObjectiveUncompleted", true);
          if (flag("leave")) {
            step = 11;
            var body = request("LeaveRaidCsReq");
            set(body, "raid_id", number("raid"));
            send(channel, "LeaveRaidCsReq", body);
          } else done();
        }
        case "LeaveRaidScRsp" -> {
          require(step == 11 && code == 0, "Pause return rejected");
          ordinary = true;
          query(channel, 12);
        }
        default -> throw new IllegalStateException("Unexpected response " + name);
      }
    }
  }

  public static void main(String[] args) {
    int exit = 1;
    var client = new KcpClient();
    Ukcp connection = null;
    try {
      var probe = new LoopbackRaidTrialOnlyProbe();
      probe.credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", probe.credentials.get("accountUid").getAsString());
      set(token, "token", probe.credentials.get("comboToken").getAsString());
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
                    new IllegalStateException("Trial-only probe disconnected"));
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
      System.err.println("Trial-only probe failed: " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
