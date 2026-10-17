package org.hkrpg.server.protocol;

import com.google.gson.Gson;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kcp.highway.*;

/** Reconstructed initial query sequence, with synthetic bodies; never captures client credentials. */
public final class NativeLoginInitialProbe implements KcpListener {
  private static final int[] QUERIES = {
    80, 534, 522, 2834, 334, 6023, 4583, 2922, 717, 6134, 2660, 1234, 934, 1715,
    5697, 5662, 1834, 5684, 6002, 7666, 5547, 6029, 7778, 158, 3405, 5734,
    2705, 2742, 31, 6334, 9, 705, 7172, 8034, 8110, 160, 5834, 1455, 4848,
    6842, 7625, 96, 1217, 8130, 1934, 8948, 8680, 4196, 9504, 9804, 8991,
    10010, 9894, 9490, 1458, 87, 78, 1222
  };
  // Frozen 4.6 command pairs; 4583/7172/8110/10010 require the independently audited raw names.
  private static final Set<Integer> EXPECTED_RESPONSES = Arrays.stream(new int[] {
    67, 525, 549, 2825, 325, 6033, 4515, 2949, 738, 6125, 2616, 1225, 925, 1742,
    5653, 5614, 1825, 5640, 6003, 7661, 5435, 6017, 7704, 156, 3475, 5725,
    2775, 2739, 91, 6325, 27, 775, 7173, 8025, 8116, 116, 5825, 1448, 4814,
    6839, 7617, 47, 1238, 8136, 1925, 8914, 8580, 4147, 9548, 9776, 8982,
    10016, 9866, 9496, 1456, 63, 4, 1249
  }).boxed().collect(Collectors.toUnmodifiableSet());
  private static final Map<Integer, Integer> INACTIVE_RETCODES = Map.ofEntries(
      Map.entry(5653, 15), Map.entry(91, 8), Map.entry(7173, 6), Map.entry(8025, 11),
      Map.entry(8116, 7), Map.entry(47, 8), Map.entry(9776, 4), Map.entry(8982, 14),
      Map.entry(10016, 12), Map.entry(9866, 13), Map.entry(9496, 9), Map.entry(4, 9),
      Map.entry(27, 5), Map.entry(549, 1), Map.entry(2949, 6), Map.entry(3475, 8),
      Map.entry(5725, 4), Map.entry(5825, 10), Map.entry(6125, 9), Map.entry(8580, 9),
      Map.entry(8914, 13), Map.entry(9548, 8), Map.entry(6017, 2));
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Boolean> completed = new CompletableFuture<>();
  private final Set<Integer> received = new HashSet<>();
  private final String account, token;
  private boolean queriesSent;
  private int playerUid;

  NativeLoginInitialProbe(String account, String token) { this.account = account; this.token = token; }

  private void send(Ukcp connection, int command, byte[] body) {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[] {8, 1}, body));
    try { connection.write(bytes); } finally { bytes.release(); }
  }

  @Override public void onConnected(Ukcp connection) {
    var type = protocol.clientSchema().message("PlayerGetTokenCsReq");
    send(connection, 60, DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("account_uid"), account)
        .setField(type.findFieldByName("token"), token)
        .setField(type.findFieldByName("platform"), 1).build().toByteArray());
  }

  @Override public void handleReceive(ByteBuf bytes, Ukcp connection) {
    try {
      while (bytes.isReadable()) {
        var frame = PacketCodec.decodeNext(bytes);
        if (frame == null) throw new IllegalStateException("Truncated initial response");
        received.add(frame.cmdId());
        if (INACTIVE_RETCODES.containsKey(frame.cmdId())) {
          var input = CodedInputStream.newInstance(frame.body());
          require(input.readTag() == INACTIVE_RETCODES.get(frame.cmdId()) << 3
              && input.readUInt32() == 0,
              "Inactive bootstrap response has invalid result or invented state: " + frame.cmdId());
          if (frame.cmdId() == 8025 || frame.cmdId() == 8580 || frame.cmdId() == 6017) {
            var name = switch (frame.cmdId()) {
              case 8025 -> "TrainPartyGetDataScRsp";
              case 8580 -> "GridFightGetDataScRsp";
              default -> "RogueTournQueryScRsp";
            };
            var profile = protocol.clientSchema().parse(name, frame.body());
            if (frame.cmdId() == 8025) {
              var data = child(profile, "train_party_data");
              child(data, "train_party_info");
              require(data.getField(data.getDescriptorForType().findFieldByNumber(6)).equals(1),
                  "TrainParty initial turn must resolve to ProgressID 1");
            } else if (frame.cmdId() == 6017) {
              child(child(profile, "LFLCDNGALME"), "DLDCFHHFMID");
              require(!profile.hasField(profile.getDescriptorForType().findFieldByNumber(5)),
                  "Inactive profile must not start a tournament run");
            } else {
              var system = child(profile, "system_info");
              child(child(system, "permanent_info"), "talent");
              var initial = child(system, "static_info");
              child(initial, "exp"); child(initial, "season_talent");
              require(initial.getField(initial.getDescriptorForType().findFieldByName("division_id")).equals(1)
                  && initial.getField(initial.getDescriptorForType().findFieldByName("season")).equals(1)
                  && initial.getField(initial.getDescriptorForType().findFieldByName("sub_season")).equals(4),
                  "GridFight initial configuration references do not resolve");
              require(!profile.hasField(profile.getDescriptorForType().findFieldByName("current_info")),
                  "Inactive profile must not start a GridFight run");
            }
            requireEmptyProgress(profile);
          } else require(input.readTag() == 0, "Invented bootstrap state");
        }
        if (frame.cmdId() == 16) {
          var response = protocol.clientSchema().parse("PlayerGetTokenScRsp", frame.body());
          require(response.getField(response.getDescriptorForType().findFieldByName("retcode")).equals(0),
              "Synthetic token rejected");
          playerUid = (Integer) response.getField(response.getDescriptorForType().findFieldByName("uid"));
          require(playerUid > 0, "Player identity missing");
          send(connection, 34, new byte[0]);
        } else if (frame.cmdId() == 25 && !queriesSent) {
          queriesSent = true;
          for (int command : QUERIES) {
            byte[] body = new byte[0];
            if (command == 334) {
              var type = protocol.clientSchema().message("GetAvatarDataCsReq");
              body = DynamicMessage.newBuilder(type).setField(type.findFieldByName("is_get_all"), true)
                  .build().toByteArray();
            }
            if (command == 5697) body = new byte[] {106, 2, 1, 2};
            if (command == 31) body = new byte[] {90, 4, 0, 0, 0, 0};
            send(connection, command, body);
          }
          var type = protocol.clientSchema().message("PlayerHeartBeatCsReq");
          send(connection, 44, DynamicMessage.newBuilder(type)
              .setField(type.findFieldByName("client_time_ms"), 1234567890123L).build().toByteArray());
        } else if (frame.cmdId() == 4515) {
          var input = CodedInputStream.newInstance(frame.body());
          require(input.readTag() == 24 && input.readUInt32() == 0 && input.readTag() == 0,
              "Inactive return profile is not native field3 success without activity data");
        } else if (frame.cmdId() == 67) {
          var basic = protocol.clientSchema().parse("GetBasicInfoScRsp", frame.body());
          require(basic.getField(basic.getDescriptorForType().findFieldByName("retcode")).equals(0),
              "Basic information rejected");
          var settings = child(basic, "player_setting_info");
          child(settings, "OJPAODIHAJE");
          requireEmptyProgress(settings);
        } else if (frame.cmdId() == protocol.clientCommand("GetCurSceneInfoScRsp")) {
          var scene = protocol.clientSchema().parse("GetCurSceneInfoScRsp", frame.body());
          require(scene.getField(scene.getDescriptorForType().findFieldByName("retcode")).equals(0)
              && scene.hasField(scene.getDescriptorForType().findFieldByName("scene")), "Scene snapshot missing");
        } else if (frame.cmdId() == protocol.clientCommand("GetGachaInfoScRsp")) {
          var catalog = protocol.clientSchema().parse("GetGachaInfoScRsp", frame.body());
          require(catalog.getField(catalog.getDescriptorForType().findFieldByName("retcode")).equals(0)
              && catalog.getRepeatedFieldCount(catalog.getDescriptorForType().findFieldByName("gacha_info_list")) > 0,
              "Configured banner snapshot missing");
        } else if (frame.cmdId() == protocol.clientCommand("GetPamSkinDataScRsp")) {
          var skin = protocol.clientSchema().parse("GetPamSkinDataScRsp", frame.body());
          require(skin.getField(skin.getDescriptorForType().findFieldByName("cur_skin")).equals(252000),
              "Default Pam skin must exist in the actual catalog");
        } else if (frame.cmdId() == 62) {
          var heartbeat = protocol.clientSchema().parse("PlayerHeartBeatScRsp", frame.body());
          require(heartbeat.getField(heartbeat.getDescriptorForType().findFieldByName("client_time_ms"))
              .equals(1234567890123L), "Heartbeat echo changed");
        }
        if (received.containsAll(EXPECTED_RESPONSES) && received.contains(62))
          completed.complete(true);
      }
    } catch (Exception error) { completed.completeExceptionally(error); }
  }

  @Override public void handleException(Throwable error, Ukcp connection) { completed.completeExceptionally(error); }
  @Override public void handleClose(Ukcp connection) {
    if (!completed.isDone()) completed.completeExceptionally(new IllegalStateException("Closed during initial queries"));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private static DynamicMessage child(DynamicMessage parent, String name) {
    var field = parent.getDescriptorForType().findFieldByName(name);
    require(field != null && parent.hasField(field), "Missing native initial container " + name);
    return (DynamicMessage) parent.getField(field);
  }

  private static void requireEmptyProgress(DynamicMessage message) {
    for (var entry : message.getAllFields().entrySet()) {
      require(!entry.getKey().isRepeated(), "Initial profile invents unlocked records");
      if (entry.getValue() instanceof DynamicMessage nested) requireEmptyProgress(nested);
      else {
        String key = message.getDescriptorForType().getName() + "." + entry.getKey().getName();
        int expected = switch (key) {
          case "TrainPartyData.unlock_area_num", "GridFightStaticGameInfo.season",
              "GridFightStaticGameInfo.division_id" -> 1;
          case "GridFightStaticGameInfo.sub_season" -> 4;
          default -> throw new IllegalStateException("Initial profile invents progress: " + key);
        };
        require(entry.getValue().equals(expected), "Invalid initial configuration reference: " + key);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    var credentials = new Gson().fromJson(new InputStreamReader(System.in, StandardCharsets.UTF_8), Map.class);
    var probe = new NativeLoginInitialProbe((String) credentials.get("accountUid"), (String) credentials.get("comboToken"));
    var client = new KcpClient();
    int exit = 1;
    try {
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true); config.setConv(1234567); config.setAckNoDelay(true);
      config.setTimeoutMillis(15_000);
      client.init(config, probe);
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.completed.get(20, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(Map.of("passed", true, "initialRequests", QUERIES.length,
          "initialResponses", EXPECTED_RESPONSES.size(),
          "requiredNestedProfiles", true,
          "requiredBasicSettings", true,
          "playerUid", probe.playerUid,
          "nativeResponse4515", true, "sceneAfterUnsupportedQueries", true,
          "loginFinishAndHeartbeat", true, "clientGameplayVerified", false)));
      exit = 0;
    } catch (Exception error) {
      System.err.println("Initial query probe failed: " + error.getClass().getSimpleName()
          + "; missing initial responses " + EXPECTED_RESPONSES.stream()
              .filter(command -> !probe.received.contains(command)).sorted().toList());
    } finally { client.stop(); }
    System.exit(exit);
  }
}
