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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kcp.highway.*;

/** Reconstructed initial query sequence, with synthetic bodies; never captures client credentials. */
public final class NativeLoginInitialProbe implements KcpListener {
  private static final int[] QUERIES = {
    80, 534, 522, 2834, 334, 6023, 4583, 2922, 717, 6134, 2660, 1234, 934, 1715,
    5697, 5662, 1834, 5684, 6002, 7666, 5547, 6029, 7778, 158, 3405, 5734, 2705, 2742,
    31, 6334, 9, 705, 7172, 8034, 8110, 160, 5834, 1455, 4848, 6842, 7625, 96,
    1217, 8130, 1934, 8948, 8680, 4196, 9504, 9804, 8991, 10010, 9894, 9490, 1458, 2734,
    1284, 10065, 1015, 8370, 958, 4113, 2996, 3305, 5489, 3634, 7162, 4634, 4675, 4105,
    4442, 7134, 9112, 4734, 4812, 6634, 6930, 7234, 7578, 4185, 7552, 7456, 7284, 4140,
    4193, 8170, 9244, 9604, 8280, 2274, 2275, 9073, 9090, 8364, 9294, 9034, 6600, 9560,
    9641, 9726, 9328, 10290, 6734, 2621, 2534, 379, 6434, 6950, 3983, 1734, 2248, 2634,
    2613, 3134, 4334, 6970, 3758, 4234, 3805, 3968, 3958, 5134, 7159, 8960, 7562, 8150,
    8190, 8934, 9171, 9990, 8979, 9958, 10270, 10670, 10038, 10190, 2223, 87, 78, 1222, 834, 2160, 1534, 2334
  };
  // Frozen 4.6 command pairs; 4583/7172/8110/10010 require the independently audited raw names.
  private static final Set<Integer> EXPECTED_RESPONSES = Arrays.stream(new int[] {
    67, 525, 549, 2825, 325, 6033, 4515, 2949, 738, 6125, 2616, 1225, 925, 1742,
    5653, 5614, 1825, 5640, 6003, 7661, 5435, 6017, 7704, 156, 3475, 5725, 2775, 2739,
    91, 6325, 27, 775, 7173, 8025, 8116, 116, 5825, 1448, 4814, 6839, 7617, 47,
    1238, 8136, 1925, 8914, 8580, 4147, 9548, 9776, 8982, 10016, 9866, 9496, 1456, 63,
    4, 1249, 2725, 8376, 2947, 3375, 5523, 4439, 6625, 6936, 7206, 7599, 4173, 9266,
    6592, 2612, 341, 3915, 1725, 2214, 2625, 2623, 3125, 4225, 3875, 5125, 8196, 8906,
    2202, 825, 2116, 1525, 9996, 2325, 9638, 3756
  }).boxed().collect(Collectors.toUnmodifiableSet());
  private static final Map<Integer, Integer> INACTIVE_RETCODES = Map.ofEntries(
      Map.entry(5653, 15), Map.entry(7173, 6), Map.entry(8025, 11),
      Map.entry(8116, 7), Map.entry(47, 8), Map.entry(9776, 4), Map.entry(8982, 14),
      Map.entry(10016, 12), Map.entry(9866, 13), Map.entry(9496, 9),
      Map.entry(549, 1), Map.entry(2949, 6), Map.entry(3475, 8),
      Map.entry(5725, 4), Map.entry(5825, 10), Map.entry(8580, 9),
      Map.entry(8914, 13), Map.entry(9548, 8), Map.entry(6017, 2), Map.entry(9996, 9),
      Map.entry(3756, 3));
  // Additional supported snapshots observed in the second real initialization burst.
  private static final Map<Integer, String> FOLLOWUP_RESPONSES = Map.ofEntries(
      Map.entry(1825, "GetRogueInfoScRsp"),
      Map.entry(5435, "ChessRogueQueryScRsp"),
      Map.entry(825, "GetMailScRsp"),
      Map.entry(2116, "GetFirstTalkNpcScRsp"),
      Map.entry(1525, "GetShopListScRsp"),
      Map.entry(2725, "GetNpcMessageGroupScRsp"),
      Map.entry(8376, "GetRechargeGiftInfoScRsp"),
      Map.entry(2947, "GetFriendLoginInfoScRsp"),
      Map.entry(3375, "GetDailyActiveInfoScRsp"),
      Map.entry(5523, "GetChessRogueNousStoryInfoScRsp"),
      Map.entry(4439, "GetTreasureDungeonActivityDataScRsp"),
      Map.entry(6625, "GetStrongChallengeActivityDataScRsp"),
      Map.entry(6936, "GetOfferingInfoScRsp"),
      Map.entry(7206, "ClockParkGetInfoScRsp"),
      Map.entry(7599, "MusicRhythmDataScRsp"),
      Map.entry(4173, "DifficultyAdjustmentGetDataScRsp"),
      Map.entry(9266, "GetDiceCombatSystemDataScRsp"),
      Map.entry(6592, "GetLocalLegendDataScRsp"),
      Map.entry(2612, "GetMaterialSubmitActivityDataScRsp"),
      Map.entry(341, "GetPreAvatarActivityListScRsp"),
      Map.entry(3915, "GetChatEmojiListScRsp"),
      Map.entry(1725, "GetChallengeScRsp"),
      Map.entry(2214, "GetRaidInfoScRsp"),
      Map.entry(2625, "GetLoginActivityScRsp"),
      Map.entry(2623, "GetTrialActivityDataScRsp"),
      Map.entry(3125, "GetJukeboxDataScRsp"),
      Map.entry(4225, "GetBoxingClubInfoScRsp"),
      Map.entry(3875, "TextJoinQueryScRsp"),
      Map.entry(5125, "GetPhoneDataScRsp"),
      Map.entry(8196, "GetMarkChestScRsp"),
      Map.entry(8906, "GetChallengePeakDataScRsp"),
      Map.entry(2202, "GetAllSaveRaidScRsp"));
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Boolean> completed = new CompletableFuture<>();
  private final Set<Integer> received = new HashSet<>();
  private final String account, token;
  private boolean queriesSent;
  private boolean rogueInfoRetcodeVerified;
  private boolean chessRogueInitialProfileVerified;
  private boolean inactiveBattleSnapshotVerified;
  private boolean missionCustomBatchVerified;
  private boolean missionStatusBatchVerified;
  private boolean archiveSnapshotVerified;
  private boolean heartbeatAfterArchiveVerified;
  private boolean initialActivityScheduleVerified;
  private boolean initialSocialParkProfileVerified;
  private boolean laterStoryVideoKeysVerified;
  private final java.util.List<String> gachaBrowserUrls = new java.util.ArrayList<>();
  private int rejectedResponseCommand;
  private int playerUid;
  private long tokenStartedAt;
  private long tokenCompletedMillis;
  private long initialStartedAt;
  private long initialCompletedMillis;
  private long openingVideoPrimaryKey;
  private long videoModulus;

  NativeLoginInitialProbe(String account, String token) { this.account = account; this.token = token; }

  private void send(Ukcp connection, int command, byte[] body) {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[] {8, 1}, body));
    try { connection.write(bytes); } finally { bytes.release(); }
  }

  @Override public void onConnected(Ukcp connection) {
    var type = protocol.clientSchema().message("PlayerGetTokenCsReq");
    tokenStartedAt = System.nanoTime();
    send(connection, 60, DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("account_uid"), account)
        .setField(type.findFieldByName("token"), token)
        .setField(type.findFieldByName("platform"), 1).build().toByteArray());
  }

  @Override public void handleReceive(ByteBuf bytes, Ukcp connection) {
    int currentResponse = 0;
    try {
      while (bytes.isReadable()) {
        var frame = PacketCodec.decodeNext(bytes);
        if (frame == null) throw new IllegalStateException("Truncated initial response");
        currentResponse = frame.cmdId();
        received.add(frame.cmdId());
        if (frame.cmdId() == 1925) {
          var gacha = protocol.clientSchema().parse("GetGachaInfoScRsp", frame.body());
          var banners = gacha.getDescriptorForType().findFieldByName("gacha_info_list");
          require(gacha.getRepeatedFieldCount(banners) > 0, "Missing isolated gacha catalogue");
          for (int i = 0; i < gacha.getRepeatedFieldCount(banners); i++) {
            var banner = (DynamicMessage) gacha.getRepeatedField(banners, i);
            for (int field : new int[] {6, 12}) {
              String value = (String) banner.getField(banner.getDescriptorForType().findFieldByNumber(field));
              require(!value.isBlank(), "Empty native gacha browser URL leaves an SDK window registered");
              gachaBrowserUrls.add(value);
            }
          }
        }
        if (frame.cmdId() == 91) {
          // Native video bootstrap: type 2 decimal value initializes the movie divisor.
          var input = CodedInputStream.newInstance(frame.body());
          require(input.readTag() == 64 && input.readUInt32() == 0
              && input.readTag() == 122, "Missing native video bootstrap");
          var secret = CodedInputStream.newInstance(input.readByteArray());
          require(secret.readTag() == 16 && secret.readUInt32() == 2
              && secret.readTag() == 114, "Invalid video bootstrap type or key field");
          var decimal = secret.readStringRequireUtf8();
          require(decimal.equals("10120425825329403")
              && secret.readTag() == 0 && input.readTag() == 0,
              "Video bootstrap differs from the pinned source or invents unrelated keys");
          videoModulus = Long.parseUnsignedLong(decimal) ^ 0xdc0748fcc0c8daL;
          require(videoModulus == 72_043_514_036_987_937L, "Video divisor is invalid");
        }
        if (frame.cmdId() == 27) {
          // Independently matched ordinary opening-movie list; no fabricated activity keys.
          var input = CodedInputStream.newInstance(frame.body());
          require(input.readTag() == 40 && input.readUInt32() == 0
              && input.readTag() == 122, "Missing opening video key list");
          var key = CodedInputStream.newInstance(input.readByteArray());
          require(key.readTag() == 48, "Missing opening video primary key");
          openingVideoPrimaryKey = key.readUInt64();
          require(openingVideoPrimaryKey == 70_220_919L
              && key.readTag() == 112 && key.readUInt32() == 8
              && key.readTag() == 0 && input.readTag() == 122,
              "Opening video ID or source key differs from the pinned native contract");
          var boss = CodedInputStream.newInstance(input.readByteArray());
          require(boss.readTag() == 48 && boss.readUInt64() == 73_772_858_016_446_646L
              && boss.readTag() == 64 && boss.readUInt64() == 73_385_157_949_291_753L
              && boss.readTag() == 104 && boss.readUInt64() == 5_627_075_162_192_835_560L
              && boss.readTag() == 112 && boss.readUInt32() == 21
              && boss.readTag() == 0,
              "Post-boss video parameters differ from the validated 4.6 candidate");
          for (long[] expected : new long[][] {{16, 70_220_919L}, {310, 71_220_927L},
              {1, 60_220_919L}, {2, 60_220_919L}, {100, 12_230_601L}}) {
            require(input.readTag() == 122, "Missing later encrypted story movie");
            var legacy = CodedInputStream.newInstance(input.readByteArray());
            require(legacy.readTag() == 48 && legacy.readUInt64() == expected[1]
                && legacy.readTag() == 112 && legacy.readUInt32() == expected[0]
                && legacy.readTag() == 0, "Incorrect later story movie key");
          }
          require(input.readTag() == 0, "Unexpected movie entry");
          laterStoryVideoKeysVerified = true;
        }
        if (frame.cmdId() == 6125) {
          var prefs = protocol.clientSchema().parse("GetAllServerPrefsDataScRsp", frame.body());
          require(prefs.getUnknownFields().asMap().isEmpty()
              && prefs.getField(prefs.getDescriptorForType().findFieldByNumber(9)).equals(0)
              && prefs.getRepeatedFieldCount(prefs.getDescriptorForType().findFieldByNumber(11)) == 0,
              "Fresh account preferences must succeed without invented seen markers");
        }
        if (frame.cmdId() == 4) {
          // Activity scheduling now uses its typed reply (retcode field 10), rather
          // than the old inactive placeholder (field 9). This fresh fixture has
          // no operator windows or completed unlock prerequisites.
          var schedules = protocol.clientSchema().parse("GetActivityScheduleConfigScRsp", frame.body());
          require(schedules.getUnknownFields().asMap().isEmpty()
              && schedules.getField(schedules.getDescriptorForType().findFieldByName("retcode")).equals(0)
              && schedules.getRepeatedFieldCount(schedules.getDescriptorForType().findFieldByName("schedule_data")) == 0,
              "Fresh activity schedule must use its native schema without invented windows");
          initialActivityScheduleVerified = true;
        }
        if (frame.cmdId() == 9638) {
          var social = protocol.clientSchema().parse("SocialPlayGetDataScRsp", frame.body());
          require(social.getUnknownFields().asMap().isEmpty()
              && social.getField(social.getDescriptorForType().findFieldByName("retcode")).equals(0),
              "Initial social profile query failed");
          var park = child(child(social, "NDDJFLPBHBL"), "JIPPNPILKDJ");
          require(park.getRepeatedFieldCount(park.getDescriptorForType().findFieldByNumber(1)) == 0,
              "Initial social profile invented park placements");
          var cats = child(social, "ELDIONMAAHO");
          require(cats.getRepeatedFieldCount(cats.getDescriptorForType().findFieldByName("BJPHDKEBOBK")) == 0
              && cats.getRepeatedFieldCount(cats.getDescriptorForType().findFieldByName("NIELDCLPOLA")) == 0,
              "Initial social query opened a box or granted a capture");
          initialSocialParkProfileVerified = true;
        }
        if (INACTIVE_RETCODES.containsKey(frame.cmdId())) {
          var input = CodedInputStream.newInstance(frame.body());
          require(input.readTag() == INACTIVE_RETCODES.get(frame.cmdId()) << 3
              && input.readUInt32() == 0,
              "Inactive bootstrap response has invalid result or invented state: " + frame.cmdId());
          if (frame.cmdId() == 7173) {
            var response = protocol.clientSchema().parse("GetBadgeInfoScRsp", frame.body());
            require(response.getUnknownFields().asMap().isEmpty(), "Unknown initial badge response fields");
            var badges = child(response, "AIANIJCIBGE");
            require(badges.getUnknownFields().asMap().isEmpty()
                && badges.getRepeatedFieldCount(badges.getDescriptorForType().findFieldByName("CPMCCCODGAB")) == 0,
                "Fresh account must not receive invented badge ownership");
            requireEmptyProgress(response);
          } else if (frame.cmdId() == 8025 || frame.cmdId() == 8580 || frame.cmdId() == 6017) {
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
              var staticProfile = child(profile, "LFLCDNGALME");
              child(staticProfile, "DLDCFHHFMID");
              var guides = (List<?>) staticProfile.getField(
                  staticProfile.getDescriptorForType().findFieldByName("DANNIDIPFAL"));
              require(guides.size() == 1, "Missing unpassed permanent guide: handbook would be blank");
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
        var followup = FOLLOWUP_RESPONSES.get(frame.cmdId());
        if (followup != null) {
          var data = protocol.clientSchema().parse(followup, frame.body());
          require(data.getUnknownFields().asMap().isEmpty(), "Unknown follow-up response fields: " + frame.cmdId());
          var retcode = data.getDescriptorForType().findFieldByName("retcode");
          if (retcode != null) require(data.getField(retcode).equals(0),
              "Follow-up response rejected: " + frame.cmdId() + " retcode=" + data.getField(retcode));
          if (followup.equals("GetRogueInfoScRsp")) {
            require(retcode != null, "Rogue information result field is missing");
            rogueInfoRetcodeVerified = true;
          } else if (followup.equals("ChessRogueQueryScRsp")) {
            require(retcode != null, "Chess Rogue query result field is missing");
            requireInitialChessProfile(data);
            chessRogueInitialProfileVerified = true;
          } else if (followup.equals("GetShopListScRsp")) {
            require(data.getField(data.getDescriptorForType().findFieldByName("shop_type")).equals(1),
                "Shop query type was changed");
            var rows = (java.util.List<?>) data.getField(data.getDescriptorForType().findFieldByName("shop_list"));
            require(!rows.isEmpty(), "The pinned type-1 shop catalog must not be empty");
            var ids = new HashSet<Integer>();
            for (var value : rows) {
              var row = (DynamicMessage) value;
              int id = (Integer) row.getField(row.getDescriptorForType().findFieldByName("shop_id"));
              // The pinned 4.6 runtime catalog has no 988; configured alternatives have unit coverage.
              require(id > 0 && id != 988 && ids.add(id)
                  && row.getRepeatedFieldCount(row.getDescriptorForType().findFieldByName("goods_list")) > 0,
                  "Shop snapshot invents a catalog entry, duplicate or empty shop");
            }
          } else if (followup.equals("GetFirstTalkNpcScRsp")) {
            var rows = (java.util.List<?>) data.getField(data.getDescriptorForType().findFieldByNumber(2));
            require(rows.size() == 2, "NPC first-talk query did not resolve both requested IDs");
            for (int i = 0; i < rows.size(); i++) {
              var row = (DynamicMessage) rows.get(i);
              require(row.getField(row.getDescriptorForType().findFieldByNumber(1)).equals(10001 + i)
                  && row.getField(row.getDescriptorForType().findFieldByNumber(3)).equals(false),
                  "NPC first-talk identity changed or completion was invented");
            }
          } else if (followup.equals("GetMailScRsp")) {
            int total = (Integer) data.getField(data.getDescriptorForType().findFieldByName("total_num"));
            int rows = data.getRepeatedFieldCount(data.getDescriptorForType().findFieldByName("mail_list"));
            require(data.getField(data.getDescriptorForType().findFieldByName("start")).equals(0)
                && rows <= 50 && total >= rows
                && data.getField(data.getDescriptorForType().findFieldByName("is_end")).equals(total == rows),
                "Mail paging must report the actual offset, rows and end marker");
          } else if (followup.equals("GetJukeboxDataScRsp")) {
            var selected = child(child(data, "GFFOBALDBPM"), "JKNLCEEDAHJ");
            require((Integer) selected.getField(selected.getDescriptorForType().findFieldByName("id")) > 0,
                "Missing actual selected music");
            require(data.getRepeatedFieldCount(data.getDescriptorForType().findFieldByNumber(4)) > 0,
                "Missing configured music catalog");
          } else if (followup.equals("TextJoinQueryScRsp")) {
            var rows = (java.util.List<?>) data.getField(data.getDescriptorForType().findFieldByNumber(13));
            require(rows.size() == 2,
                "Text join must preserve normal selections and omit eight unset AvatarID overrides");
            for (int index = 0; index < 2; index++) {
              var row = (DynamicMessage) rows.get(index);
              require(row.getField(row.getDescriptorForType().findFieldByNumber(10)).equals(18 + index),
                  "Text join key changed");
              require((Integer) row.getField(row.getDescriptorForType().findFieldByNumber(15)) > 0,
                  "Text join selected item missing");
            }
          }
        }
        if (frame.cmdId() == 16) {
          var response = protocol.clientSchema().parse("PlayerGetTokenScRsp", frame.body());
          require(response.getField(response.getDescriptorForType().findFieldByName("retcode")).equals(0),
              "Synthetic token rejected");
          playerUid = (Integer) response.getField(response.getDescriptorForType().findFieldByName("uid"));
          require(playerUid > 0, "Player identity missing");
          tokenCompletedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tokenStartedAt);
          require(tokenCompletedMillis <= 4000,
              "Token response exceeded 4000 ms: " + tokenCompletedMillis);
          send(connection, 34, new byte[0]);
        } else if (frame.cmdId() == 25 && !queriesSent) {
          queriesSent = true;
          initialStartedAt = System.nanoTime();
          for (int command : QUERIES) {
            byte[] body = new byte[0];
            if (command == 334) {
              var type = protocol.clientSchema().message("GetAvatarDataCsReq");
              body = DynamicMessage.newBuilder(type).setField(type.findFieldByName("is_get_all"), true)
                  .build().toByteArray();
            }
            if (command == 5697) body = new byte[] {106, 2, 1, 2};
            if (command == 3758) body = new byte[] {32, 1};
            if (command == 1217) {
              var type = protocol.clientSchema().message("GetMissionStatusCsReq");
              var query = DynamicMessage.newBuilder(type);
              for (int id : new int[] {1000101, 1000201, 1000301, 1000500})
                query.addRepeatedField(type.findFieldByNumber(9), id);
              for (int i = 0; i < 600; i++)
                query.addRepeatedField(type.findFieldByNumber(9), 19900000 + i);
              body = query.build().toByteArray();
            }
            if (command == 1222) {
              var type = protocol.clientSchema().message("GetMainMissionCustomValueCsReq");
              var query = DynamicMessage.newBuilder(type);
              // Synthetic IDs, matching the observed canonical 550-byte frame-end batch size.
              for (int i = 0; i < 137; i++) query.addRepeatedField(
                  type.findFieldByName("main_mission_id_list"), i == 0 ? 1000000 : 3000000 + i);
              body = query.build().toByteArray();
              require(body.length == 550, "Mission custom-value batch fixture changed");
            }
            // Native login also sends this count-only query after scene/login-finish requests.
            if (command == 834) body = new byte[] {72, 50};
            if (command == 1534) {
              var type = protocol.clientSchema().message("GetShopListCsReq");
              body = DynamicMessage.newBuilder(type).setField(type.findFieldByName("shop_type"), 1)
                  .build().toByteArray();
            }
            if (command == 2160) {
              var type = protocol.clientSchema().message("GetFirstTalkNpcCsReq");
              body = DynamicMessage.newBuilder(type).addRepeatedField(type.findFieldByNumber(4), 10001)
                  .addRepeatedField(type.findFieldByNumber(4), 10002).build().toByteArray();
            }
            if (command == 31) body = new byte[] {90, 4, 0, 0, 0, 0};
            if (command == 3805) {
              var type = protocol.clientSchema().message("TextJoinQueryCsReq");
              var ids = type.findFieldByNumber(3);
              var query = DynamicMessage.newBuilder(type).addRepeatedField(ids, 18)
                  .addRepeatedField(ids, 19);
              // These DefaultItem values are text IDs (Warrior 1-4), not avatar IDs.
              for (int id : new int[] {55, 56, 57, 58, 83, 84, 85, 86})
                query.addRepeatedField(ids, id);
              body = query.build().toByteArray();
            } else if (command == 6930) {
              var type = protocol.clientSchema().message("GetOfferingInfoCsReq");
              var query = DynamicMessage.newBuilder(type);
              for (int id = 1; id <= 15; id++) query.addRepeatedField(type.findFieldByName("offering_id_list"), id);
              body = query.build().toByteArray();
            }
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
        } else if (frame.cmdId() == 1238) {
          var data = protocol.clientSchema().parse("GetMissionStatusScRsp", frame.body());
          require(data.getUnknownFields().asMap().isEmpty()
              && data.getField(data.getDescriptorForType().findFieldByNumber(7)).equals(0),
              "Mission status dependencies were rejected");
          var unfinished = (java.util.List<?>) data.getField(data.getDescriptorForType().findFieldByNumber(3));
          require(data.getRepeatedFieldCount(data.getDescriptorForType().findFieldByNumber(14)) == 0
              && unfinished.size() == 604,
              "Fresh login must answer every unfinished dependency without inventing menu unlocks");
          require(unfinished.subList(0, 4).equals(java.util.List.of(1000101, 1000201, 1000301, 1000500)),
              "Menu prerequisite identities changed");
          for (int i = 0; i < 600; i++) require(unfinished.get(i + 4).equals(19900000 + i),
              "Mission preparation batch omitted a negative answer");
          missionStatusBatchVerified = true;
        } else if (frame.cmdId() == protocol.clientCommand("GetMainMissionCustomValueScRsp")) {
          var data = protocol.clientSchema().parse("GetMainMissionCustomValueScRsp", frame.body());
          require(data.getField(data.getDescriptorForType().findFieldByName("retcode")).equals(0),
              "Native frame-end mission query was rejected");
          var rows = (java.util.List<?>) data.getField(data.getDescriptorForType().findFieldByName("main_mission_list"));
          require(rows.size() == 137, "Native frame-end mission query lost rows");
          for (int i = 0; i < rows.size(); i++) {
            var row = (DynamicMessage) rows.get(i);
            require(row.getField(row.getDescriptorForType().findFieldByName("id"))
                    .equals(i == 0 ? 1000000 : 3000000 + i)
                && row.getField(row.getDescriptorForType().findFieldByName("status")).toString()
                    .equals("MissionStatus_MissionNone")
                && row.getRepeatedFieldCount(row.getDescriptorForType().findFieldByName("custom_value_list")) == 0,
                "Mission custom-value query changed identity or invented fresh-account progress");
          }
          missionCustomBatchVerified = true;
        } else if (frame.cmdId() == protocol.clientCommand("GetCurBattleInfoScRsp")) {
          var data = protocol.clientSchema().parse("GetCurBattleInfoScRsp", frame.body());
          require(data.getField(data.getDescriptorForType().findFieldByName("retcode")).equals(0),
              "No active battle must be a successful initial query, not a generic failure");
          var battle = child(data, "battle_info");
          require(battle.getAllFields().isEmpty() && battle.getUnknownFields().asMap().isEmpty(),
              "Inactive battle query must provide the native container without inventing a battle");
          inactiveBattleSnapshotVerified = true;
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
        } else if (frame.cmdId() == 2325) {
          var data = protocol.clientSchema().parse("GetArchiveDataScRsp", frame.body());
          require(data.getUnknownFields().asMap().isEmpty()
              && data.getField(data.getDescriptorForType().findFieldByNumber(8)).equals(0),
              "Archive query rejected during reconnect initialization");
          var archive = child(data, "archive_data");
          require(archive.getUnknownFields().asMap().isEmpty(), "Unknown archive fields");
          var monsters = (java.util.List<?>) archive.getField(archive.getDescriptorForType().findFieldByNumber(13));
          var relics = (java.util.List<?>) archive.getField(archive.getDescriptorForType().findFieldByNumber(5));
          var equipment = (java.util.List<?>) archive.getField(archive.getDescriptorForType().findFieldByNumber(12));
          require(!monsters.isEmpty() && !relics.isEmpty() && !equipment.isEmpty(),
              "Existing nonempty archive catalog was dropped or replaced with an empty success");
          for (var value : monsters) {
            var row = (DynamicMessage) value;
            require(row.getUnknownFields().asMap().isEmpty()
                && (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(2)) > 0
                && (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(6)) == 1,
                "Archive monster identity or inherited count changed");
          }
          for (var value : relics) {
            var row = (DynamicMessage) value;
            int slot = (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(15));
            require(row.getUnknownFields().asMap().isEmpty()
                && (Integer) row.getField(row.getDescriptorForType().findFieldByNumber(3)) > 0
                && slot >= 1 && slot <= 6, "Archive relic set or slot changed");
          }
          archiveSnapshotVerified = true;
          var heartbeat = protocol.clientSchema().message("PlayerHeartBeatCsReq");
          send(connection, 44, DynamicMessage.newBuilder(heartbeat)
              .setField(heartbeat.findFieldByName("client_time_ms"), 1234567890124L).build().toByteArray());
        } else if (frame.cmdId() == 62) {
          var heartbeat = protocol.clientSchema().parse("PlayerHeartBeatScRsp", frame.body());
          long echo = (Long) heartbeat.getField(heartbeat.getDescriptorForType().findFieldByName("client_time_ms"));
          require(echo == 1234567890123L || echo == 1234567890124L, "Heartbeat echo changed");
          if (echo == 1234567890124L) heartbeatAfterArchiveVerified = true;
        }
        if (!completed.isDone() && received.containsAll(EXPECTED_RESPONSES) && heartbeatAfterArchiveVerified) {
          long nameHash = 0;
          for (char value : "CS_Chap00_Act210".toCharArray()) nameHash = nameHash * 11 + value;
          require(videoModulus != 0
              && Long.remainderUnsigned(nameHash + openingVideoPrimaryKey, videoModulus)
                  == 26_743_203_654_499_374L,
              "Opening movie cannot derive its native decoder value from both replies");
          require(rogueInfoRetcodeVerified && chessRogueInitialProfileVerified
                  && inactiveBattleSnapshotVerified && missionCustomBatchVerified && missionStatusBatchVerified
                  && archiveSnapshotVerified && laterStoryVideoKeysVerified,
              "Rogue, battle, mission, archive or later story video initial contracts were not verified");
          initialCompletedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initialStartedAt);
          require(initialCompletedMillis <= 4000,
              "Initial query burst exceeded 4000 ms: " + initialCompletedMillis);
          completed.complete(true);
        }
      }
    } catch (Exception error) {
      if (rejectedResponseCommand == 0) rejectedResponseCommand = currentResponse;
      completed.completeExceptionally(error);
    }
  }

  @Override public void handleException(Throwable error, Ukcp connection) { completed.completeExceptionally(error); }
  @Override public void handleClose(Ukcp connection) {
    if (!completed.isDone()) completed.completeExceptionally(new IllegalStateException("Closed during initial queries"));
  }

  private static final class ContractViolation extends IllegalStateException {
    ContractViolation(String message) { super(message); }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new ContractViolation(message);
  }

  private static DynamicMessage child(DynamicMessage parent, String name) {
    var field = parent.getDescriptorForType().findFieldByName(name);
    require(field != null && parent.hasField(field), "Missing native initial container " + name);
    return (DynamicMessage) parent.getField(field);
  }

  private static DynamicMessage nativeChild(DynamicMessage parent, int number, String type) {
    var field = parent.getDescriptorForType().findFieldByNumber(number);
    require(field != null && parent.hasField(field), "Missing Chess Rogue container field " + number);
    var nested = (DynamicMessage) parent.getField(field);
    require(nested.getDescriptorForType().getName().equals(type), "Unexpected Chess Rogue container " + type);
    return nested;
  }

  private static void requireInitialChessProfile(DynamicMessage response) {
    // Native receiver proof: HIM field 10 -> MOOK field 9 and DDAF field 12.
    // This isolated account has no eligibility quest or run; both logins must remain empty.
    require(!response.hasField(response.getDescriptorForType().findFieldByNumber(8)),
        "Fresh Chess Rogue profile invents an active DJE run");
    var profile = nativeChild(response, 10, "HIMJKAMHLMJ");
    for (int number : new int[] {11, 10})
      require(profile.getRepeatedFieldCount(profile.getDescriptorForType().findFieldByNumber(number)) == 0,
          "Fresh Chess Rogue profile invents unlocked areas or claimed first rewards");
    var dice = nativeChild(profile, 9, "MOOKMKEKMKC");
    var talent = nativeChild(profile, 12, "DDAFEPBGOHK");
    for (int number : new int[] {3, 9, 10})
      require(dice.getRepeatedFieldCount(dice.getDescriptorForType().findFieldByNumber(number)) == 0,
          "Fresh Chess Rogue dice profile invents records");
    require(talent.getField(talent.getDescriptorForType().findFieldByNumber(8)).equals(0),
        "Fresh Chess Rogue talent profile invents points");
    requireNoChessProgress(profile);
  }

  private static void requireNoChessProgress(DynamicMessage message) {
    require(message.getUnknownFields().asMap().isEmpty(), "Unknown Chess Rogue profile fields");
    for (var entry : message.getAllFields().entrySet()) {
      require(!entry.getKey().isRepeated() && entry.getValue() instanceof DynamicMessage,
          "Fresh Chess Rogue profile invents progress");
      requireNoChessProgress((DynamicMessage) entry.getValue());
    }
  }

  private static void requireEmptyProgress(DynamicMessage message) {
    for (var entry : message.getAllFields().entrySet()) {
      if (message.getDescriptorForType().getName().equals("KIKEAJJLAMH")
          && entry.getKey().getName().equals("DANNIDIPFAL")) {
        var guides = (List<?>) entry.getValue();
        require(guides.size() == 1, "Missing original permanent guide reference");
        var guide = (DynamicMessage) guides.getFirst();
        require(guide.getAllFields().size() == 1
            && guide.getField(guide.getDescriptorForType().findFieldByName("area_id")).equals(101),
            "Permanent guide must retain zero pass, reward and unlock flags");
        continue;
      }
      if (entry.getKey().isRepeated()
          && message.getDescriptorForType().getName().equals("TrainPartyInfo")
          && entry.getKey().getName().equals("area_list")) {
        var rooms = (List<?>) entry.getValue();
        require(rooms.size() == 1, "Missing original initial room reference");
        var room = (DynamicMessage) rooms.getFirst();
        require(room.getAllFields().size() == 1
            && room.getField(room.getDescriptorForType().findFieldByName("area_id")).equals(12),
            "Initial room reference must retain zero construction progress");
        continue;
      }
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
      int browserPages = probe.verifyGachaBrowserPages(args[1]);
      System.out.println(new Gson().toJson(Map.ofEntries(
          Map.entry("passed", true),
          Map.entry("initialRequests", QUERIES.length),
          Map.entry("initialResponses", EXPECTED_RESPONSES.size()),
          Map.entry("b51InactiveInitialProfileVerified", probe.received.contains(9996)),
          Map.entry("initialActivityScheduleVerified", probe.initialActivityScheduleVerified),
          Map.entry("initialSocialParkProfileVerified", probe.initialSocialParkProfileVerified),
          Map.entry("requiredNestedProfiles", true),
          Map.entry("requiredBasicSettings", true),
          Map.entry("openingVideoBootstrapContract", true),
          Map.entry("laterStoryVideoKeysVerified", probe.laterStoryVideoKeysVerified),
          Map.entry("playerUid", probe.playerUid),
          Map.entry("tokenResponseMillis", probe.tokenCompletedMillis),
          Map.entry("tokenDeadlineMillis", 4000),
          Map.entry("nativeResponse4515", true),
          Map.entry("sceneAfterUnsupportedQueries", true),
          Map.entry("loginFinishAndHeartbeat", true),
          Map.entry("clientGameplayVerified", false),
          Map.entry("followupSnapshotContracts", FOLLOWUP_RESPONSES.size()),
          Map.entry("textJoinAvatarDefaultsRetained", true),
          Map.entry("rogueInfoRetcodeZero", probe.rogueInfoRetcodeVerified),
          Map.entry("inactiveBattleSnapshotVerified", probe.inactiveBattleSnapshotVerified),
          Map.entry("missionCustomBatchVerified", probe.missionCustomBatchVerified),
          Map.entry("missionStatusBatchVerified", probe.missionStatusBatchVerified),
          Map.entry("archiveSnapshotVerified", probe.archiveSnapshotVerified),
          Map.entry("heartbeatAfterArchiveVerified", probe.heartbeatAfterArchiveVerified),
          Map.entry("gachaBrowserUrlsVerified", probe.gachaBrowserUrls.size()),
          Map.entry("gachaBrowserPagesVerified", browserPages),
          Map.entry("chessRogueInitialProfile", Map.of(
              "verified", probe.chessRogueInitialProfileVerified,
              "responseCommand", 5435, "retcode", 0,
              "requiredContainers", "HIMJKAMHLMJ:10/MOOKMKEKMKC:9/DDAFEPBGOHK:12",
              "activeRunPresent", false, "unlockedAreas", 0, "claimedFirstRewards", 0,
              "diceRecords", 0, "talentPoints", 0, "inventedProgress", false)),
          Map.entry("initialBurstCompletionMillis", probe.initialCompletedMillis),
          Map.entry("initialBurstDeadlineMillis", 4000),
          Map.entry("unsupportedQueryResponsesNotInvented", QUERIES.length - EXPECTED_RESPONSES.size()))));
      exit = 0;
    } catch (Exception error) {
      System.err.println("Initial query probe failed: " + error.getClass().getSimpleName()
          + "; missing initial responses " + EXPECTED_RESPONSES.stream()
              .filter(command -> !probe.received.contains(command)).sorted().toList());
      System.err.println("Rejected response command: " + probe.rejectedResponseCommand);
      Throwable detail = error;
      while (detail.getCause() != null) detail = detail.getCause();
      // Only our own constant contract labels and command numbers are safe to report.
      if (detail instanceof ContractViolation) System.err.println("Contract: " + detail.getMessage());
    } finally { client.stop(); }
    System.exit(exit);
  }

  private int verifyGachaBrowserPages(String expectedOrigin) throws Exception {
    var origin = java.net.URI.create(expectedOrigin);
    var checked = new HashSet<String>();
    try (var http = java.net.http.HttpClient.newHttpClient()) {
      for (String value : gachaBrowserUrls) {
        var url = java.net.URI.create(value);
        require(url.isAbsolute() && origin.getScheme().equals(url.getScheme())
            && origin.getHost().equals(url.getHost()) && origin.getPort() == url.getPort()
            && url.getUserInfo() == null && url.getFragment() == null,
            "Gacha browser URL does not use the advertised HTTP origin");
        require(Set.of("/gacha/details", "/gacha/history").contains(url.getPath())
            && url.getQuery() != null && url.getQuery().matches("gacha_id=[1-9][0-9]*"),
            "Invalid gacha browser page or banner query");
        if (!checked.add(url.getPath())) continue;
        var reply = http.send(java.net.http.HttpRequest.newBuilder(url)
            .timeout(java.time.Duration.ofSeconds(5)).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        require(reply.statusCode() == 200 && reply.body().contains("<!doctype html>")
            && reply.headers().firstValue("Content-Type").orElse("").contains("text/html"),
            "Packaged gacha browser page is not available");
      }
    }
    require(checked.size() == 2, "Both native gacha browser entries must resolve");
    return checked.size();
  }
}
