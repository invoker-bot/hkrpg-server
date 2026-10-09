package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.UnknownFieldSet;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Positive combat and saved-wallet checks using real resource entities over UDP/KCP. */
public final class LoopbackRogueCombatProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private boolean laurel, fightLaurel, laurelNotification;
  private int battleMiracle, initialMp, expectedMp;
  private boolean fightMiracle;
  private List<Integer> battleAvatars = List.of();
  private int destinationSite, destinationRoom;
  private int expectedBalance, initialBalance, paidBalance, battleId, stageId, step;
  private boolean currencyNotification;
  private boolean roomCurrencyNotification;
  private DynamicMessage enteredScene;
  private int destructionTarget;
  private boolean codeRepair;
  private int codeWins;
  private final Queue<Integer> codeEnemies = new ArrayDeque<>();
  private final List<Integer> codeProgress = new ArrayList<>();
  private final List<Map<String, Object>> codeBattles = new ArrayList<>();
  private boolean codeReplacement;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
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

  private int wallet(DynamicMessage game) {
    var run = (DynamicMessage) field(game, "CFDIKLBINHK");
    return (Integer) field((DynamicMessage) field(run, "DHEKLJDBNNP"), "rogue_money");
  }

  private void finish(Ukcp c, boolean wrongId) throws Exception {
    var b = request("PVEBattleResultCsReq");
    set(b, "battle_id", wrongId ? battleId + 1 : battleId);
    set(b, "stage_id", stageId);
    var end = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(
        end, end.getEnumType().findValueByName(laurel ? "BATTLE_END_LOSE" : "BATTLE_END_WIN"));
    if (laurel) {
      var stats = emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance();
      for (int id : battleAvatars)
        stats.addBattleAvatarList(
            emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
                .setId(id)
                .setAvatarStatus(
                    emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty.newInstance()
                        .setMaxHp(100)
                        .setMaxSp(100)
                        .setLeftHp(0)
                        .setLeftSp(9)));
      set(
          b,
          "stt",
          protocol
              .clientSchema()
              .parse(
                  "BattleStatistics",
                  protocol.translate("BattleStatistics", stats.toByteArray(), true)));
    }
    send(c, "PVEBattleResultCsReq", b);
  }

  private void verifyLaurel(DynamicMessage game, int used) {
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var owned =
        (DynamicMessage) field((DynamicMessage) field(current, "DNPCCFFFBHN"), "DNPCCFFFBHN");
    var matches =
        ((List<?>) field(owned, "MAJGMMCOCIG"))
            .stream()
                .map(v -> (DynamicMessage) v)
                .filter(v -> (Integer) field(v, "LJCEOGPOGBE") == 61)
                .toList();
    require(matches.size() == 1, "Saved laurel absent or duplicated");
    var item = matches.getFirst();
    require(
        (Integer) field(item, "DGGCFDKJDPO") == used && (Integer) field(item, "total_count") == 1,
        "Laurel use count lost or consumed again");
    result.put("laurelUseCount", used);
    result.put("laurelTotalCount", 1);
  }

  private void verifyBattleMiracle(DynamicMessage game) {
    int ownedId =
        codeRepair && (!fightMiracle || codeWins >= 3) ? battleMiracle + 1 : battleMiracle;
    boolean brokenCode = codeRepair && fightMiracle && codeWins < 3;
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var owned =
        (DynamicMessage) field((DynamicMessage) field(current, "DNPCCFFFBHN"), "DNPCCFFFBHN");
    var matches =
        ((List<?>) field(owned, "MAJGMMCOCIG"))
            .stream()
                .map(v -> (DynamicMessage) v)
                .filter(v -> (Integer) field(v, "LJCEOGPOGBE") == ownedId)
                .toList();
    require(matches.size() == 1, "Permanent battle curio absent or duplicated");
    require(
        (Integer) field(matches.getFirst(), "DGGCFDKJDPO") == (brokenCode ? codeWins : 0)
            && (Integer) field(matches.getFirst(), "total_count") == (brokenCode ? 3 : 0),
        "Permanent battle curio incorrectly consumed");
    if (codeRepair) {
      require(
          ((List<?>) field(owned, "MAJGMMCOCIG"))
              .stream()
                  .map(v -> (DynamicMessage) v)
                  .noneMatch(
                      v ->
                          (Integer) field(v, "LJCEOGPOGBE")
                              == (brokenCode ? battleMiracle + 1 : battleMiracle)),
          "Both code variants owned at once");
      result.put("codeOwnedId", ownedId);
      result.put("codeUseCount", brokenCode ? codeWins : 0);
    }
    result.put("battleMiracle", battleMiracle);
    result.put("battleMiracleUnconsumed", true);
  }

  private void verifyBattleBinding(DynamicMessage battle) {
    if (battleMiracle == 67) {
      require(
          ((List<?>) field(battle, "buff_list"))
              .stream()
                  .map(v -> (DynamicMessage) v)
                  .noneMatch(
                      v ->
                          Set.of(
                                  633102, 633104, 633105, 633107, 633108, 633109, 633110, 633111,
                                  633112, 631014, 631016, 631018, 631020, 631022, 631024)
                              .contains((Integer) field(v, "id"))),
          "Room clock fabricated a curio battle ability");
      result.put("roomClockHasNoCurioBattleBuff", true);
      return;
    }
    int expected =
        switch (codeRepair && codeWins >= 3 ? battleMiracle + 1 : battleMiracle) {
          case 8 -> 633102;
          case 14 -> 633104;
          case 58 -> 633112;
          case 19 -> 633105;
          case 45 -> 631013;
          case 46 -> 631014;
          case 47 -> 631015;
          case 48 -> 631016;
          case 49 -> 631017;
          case 50 -> 631018;
          case 51 -> 631019;
          case 52 -> 631020;
          case 53 -> 631021;
          case 54 -> 631022;
          case 55 -> 631023;
          case 56 -> 631024;
          case 59 -> 633107;
          case 62 -> 633108;
          case 66 -> 633109;
          case 68 -> 633110;
          case 10, 71 -> 633111;
          default -> throw new IllegalStateException("Unsupported battle curio");
        };
    var matches =
        ((List<?>) field(battle, "buff_list"))
            .stream()
                .map(v -> (DynamicMessage) v)
                .filter(v -> (Integer) field(v, "id") == expected)
                .toList();
    require(matches.size() == 1, "Actual battle curio binding absent or duplicated");
    var buff = matches.getFirst();
    require(
        (Integer) field(buff, "level") == 1
            && (Integer) field(buff, "owner_index") == -1
            && (Integer) field(buff, "wave_flag") == -1,
        "Wrong battle curio level, owner or wave scope");
    var dynamic = new TreeMap<String, Float>();
    for (var raw : (List<?>) field(buff, "dynamic_values")) {
      var entry = (DynamicMessage) raw;
      require(
          dynamic.put((String) field(entry, "key"), (Float) field(entry, "value")) == null,
          "Duplicate battle curio parameter");
    }
    require(
        dynamic.equals(
            switch (battleMiracle) {
              case 14 ->
                  Map.of("ItemNumber", (float) initialBalance, "#ADF_1", 100f, "#ADF_2", .16f);
              case 68 -> Map.of("#ADF_1", 1f, "#ADF_2", 3f);
              case 58 -> Map.of("RoguePropCounter_0_7394D591", 1f, "#ADF_1", .03f);
              default -> Map.of();
            }),
        "Wrong frozen battle curio parameters");
    result.put("battleDynamicValues", dynamic);
    result.put("battleBuff", expected);
    result.put("battleBindingVerified", true);
  }

  private void nextCombatRoom(Ukcp c) {
    var enter = request("EnterRogueMapRoomCsReq");
    set(enter, "MAJKFIMPOOF", destinationSite);
    set(enter, "room_id", destinationRoom);
    step = 13;
    send(c, "EnterRogueMapRoomCsReq", enter);
  }

  private void beginEncounter(Ukcp c, DynamicMessage scene) {
    if (battleMiracle == 58 && !result.containsKey("destructibleRetryKeepsOneCount")) {
      enteredScene = scene;
      require((Integer) field(scene, "entry_id") == 8112201, "Wrong destruction fixture entry");
      for (var raw : (List<?>) field(scene, "entity_group_list")) {
        for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
          var entity = (DynamicMessage) value;
          if ((Integer) field(entity, "group_id") == 73
              && (Integer) field(entity, "inst_id") == 300009
              && entity.hasField(entity.getDescriptorForType().findFieldByName("prop"))) {
            require(
                (Integer) field((DynamicMessage) field(entity, "prop"), "prop_id") == 101017,
                "Wrong resource destructible");
            destructionTarget = (Integer) field(entity, "entity_id");
            break;
          }
        }
        if (destructionTarget > 0) break;
      }
      require(destructionTarget > 0, "Actual resource destructible missing");
      attackDestructible(c, 20);
      return;
    }
    int enemy = 0;
    if (codeRepair) {
      if (enteredScene == null) {
        enteredScene = scene;
        for (var raw : (List<?>) field(scene, "entity_group_list"))
          for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
            var entity = (DynamicMessage) value;
            if (entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster")))
              codeEnemies.add((Integer) field(entity, "entity_id"));
          }
        require(
            codeEnemies.size() == 4 && new HashSet<>(codeEnemies).size() == 4,
            "Code fixture must contain four distinct actual resource NPCs");
      }
      require(!codeEnemies.isEmpty(), "Missing next distinct code battle");
      enemy = codeEnemies.remove();
    } else {
      for (var raw : (List<?>) field(scene, "entity_group_list")) {
        for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
          var entity = (DynamicMessage) value;
          if (entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster"))) {
            enemy = (Integer) field(entity, "entity_id");
            break;
          }
        }
        if (enemy > 0) break;
      }
    }
    require(enemy > 0, "No actual rogue encounter loaded");
    var b = request("SceneCastSkillCsReq");
    set(b, "cast_entity_id", field(scene, "leader_entity_id"));
    set(b, "attacked_by_entity_id", field(scene, "leader_entity_id"));
    b.addRepeatedField(
        b.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), enemy);
    step = 1;
    send(c, "SceneCastSkillCsReq", b);
  }

  private void attackDestructible(Ukcp c, int nextStep) {
    var b = request("SceneCastSkillCsReq");
    set(b, "cast_entity_id", field(enteredScene, "leader_entity_id"));
    set(b, "attacked_by_entity_id", field(enteredScene, "leader_entity_id"));
    b.addRepeatedField(
        b.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), destructionTarget);
    step = nextStep;
    send(c, "SceneCastSkillCsReq", b);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated combat probe frame");
      if (frame.cmdId() == 719 && step == 10) {
        var fields = UnknownFieldSet.parseFrom(frame.body());
        require(
            !fields.hasField(9) && !fields.hasField(6),
            "Native rogue lineup replacement failed or used Join response fields");
        result.put("nativeRogueLineupReplacement", true);
        step = 5;
        send(c, "LeaveRogueCsReq", request("LeaveRogueCsReq"));
        continue;
      }
      if (frame.cmdId() == 1807) {
        var fields = UnknownFieldSet.parseFrom(frame.body());
        require(
            (step == 7 || step == 8)
                && !fields.getField(6).getVarintList().isEmpty()
                && fields.getField(6).getVarintList().getFirst() != 0
                && !fields.hasField(3),
            "Invalid native enhancement accepted or wrong retcode field");
        if (step == 7) {
          step = 8;
          var wrongMode =
              UnknownFieldSet.newBuilder()
                  .addField(9, UnknownFieldSet.Field.newBuilder().addVarint(600000).build())
                  .build();
          var packet =
              Unpooled.wrappedBuffer(
                  PacketCodec.encode(1885, new byte[0], wrongMode.toByteArray()));
          try {
            c.write(packet);
          } finally {
            packet.release();
          }
        } else {
          result.put("nativeClassicEnhancementAndOtherModeRejected", true);
          step = 9;
          var pick = request("PickRogueAvatarCsReq");
          set(pick, "prop_entity_id", 1);
          pick.addRepeatedField(
              pick.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
              emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
          send(c, "PickRogueAvatarCsReq", pick);
        }
        continue;
      }
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "SceneCastSkillScRsp",
                  "SceneGroupRefreshScNotify",
                  "GetCurBattleInfoScRsp",
                  "PVEBattleResultScRsp",
                  "GetRogueInfoScRsp",
                  "GetCurLineupDataScRsp",
                  "EnterRogueMapRoomScRsp",
                  "SyncRogueCommonActionResultScNotify",
                  "GetRogueBuffEnhanceInfoScRsp",
                  "PickRogueAvatarScRsp",
                  "LeaveRogueScRsp",
                  "SyncRogueVirtualItemInfoScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown combat response fields: " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Combat token rejected");
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Combat login rejected");
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Combat login finish rejected");
          var b = request(resume ? "EnterRogueCsReq" : "StartRogueCsReq");
          set(b, "area_id", 100);
          if (!resume)
            b.addRepeatedField(
                b.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
                emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
          send(c, resume ? "EnterRogueCsReq" : "StartRogueCsReq", b);
        }
        case "StartRogueScRsp", "EnterRogueScRsp" -> {
          require(step == 0 && (Integer) field(m, "retcode") == 0, "Combat universe entry failed");
          var game = (DynamicMessage) field(m, "rogue_game_info");
          initialBalance = wallet(game);
          require(initialBalance == (resume ? expectedBalance : 50), "Saved combat wallet changed");
          var current = (DynamicMessage) field(game, "CFDIKLBINHK");
          var map = (DynamicMessage) field(current, "COOOMDKGODF");
          result.put("room", field(map, "HPKMGKPPELC"));
          result.put("site", field(map, "ANHDBMOPGDC"));
          result.put("fragments", initialBalance);
          if (laurel || battleMiracle > 0) {
            if (laurel) verifyLaurel(game, fightLaurel ? 0 : 1);
            else verifyBattleMiracle(game);
            if (fightLaurel || fightMiracle) {
              for (var raw : (List<?>) field(map, "LLNICHDDIAN")) {
                var row = (DynamicMessage) raw;
                if (field(row, "CAGDNHCOCKM").toString().equals("RogueRoomStatus_Unlock")) {
                  destinationSite = (Integer) field(row, "MAJKFIMPOOF");
                  destinationRoom = (Integer) field(row, "room_id");
                  break;
                }
              }
              require(
                  destinationSite > 1 && destinationRoom > 0,
                  "No actual next combat room in laurel fixture");
              if (battleMiracle > 0) {
                step = 14;
                send(c, "GetCurLineupDataCsReq", request("GetCurLineupDataCsReq"));
              } else nextCombatRoom(c);
            } else {
              result.put("savedCombatWalletRestored", true);
              step = 12;
              send(c, "GetCurLineupDataCsReq", request("GetCurLineupDataCsReq"));
            }
            break;
          }
          if (resume) {
            result.put("savedCombatWalletRestored", true);
            step = 5;
            send(c, "LeaveRogueCsReq", request("LeaveRogueCsReq"));
            break;
          }
          beginEncounter(c, (DynamicMessage) field(m, "scene"));
        }
        case "EnterRogueMapRoomScRsp" -> {
          if (battleMiracle == 67 && step == 17) {
            require((Integer) field(m, "retcode") != 0, "Repeated room entry accepted");
            step = 18;
            send(c, "GetRogueInfoCsReq", request("GetRogueInfoCsReq"));
            break;
          }
          require(
              (laurel && fightLaurel || battleMiracle > 0 && fightMiracle)
                  && step == 13
                  && (Integer) field(m, "retcode") == 0
                  && (Integer) field(m, "ANHDBMOPGDC") == destinationSite,
              "Laurel next combat room entry failed");
          result.put("combatSite", destinationSite);
          result.put("combatRoom", destinationRoom);
          result.put("combatEntry", field((DynamicMessage) field(m, "scene"), "entry_id"));
          if (battleMiracle == 67) {
            enteredScene = (DynamicMessage) field(m, "scene");
            step = 17;
            var retry = request("EnterRogueMapRoomCsReq");
            set(retry, "MAJKFIMPOOF", destinationSite);
            set(retry, "room_id", destinationRoom);
            send(c, "EnterRogueMapRoomCsReq", retry);
          } else beginEncounter(c, (DynamicMessage) field(m, "scene"));
        }
        case "SceneGroupRefreshScNotify" -> {
          if (battleMiracle != 58 || destructionTarget == 0) break;
          for (var raw : (List<?>) field(m, "group_refresh_list")) {
            var group = (DynamicMessage) raw;
            if ((Integer) field(group, "group_id") != 73) continue;
            for (var value : (List<?>) field(group, "refresh_entity")) {
              var refresh = (DynamicMessage) value;
              if ((Integer) field(refresh, "delete_entity") != destructionTarget) continue;
              require(
                  (Integer) field(m, "floor_id") == 81122001
                      && ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                                  field(group, "refresh_type"))
                              .getNumber()
                          == 3
                      && !result.containsKey("actualDestructibleRemoved"),
                  "Wrong or duplicate actual destructible removal");
              result.put("actualDestructibleRemoved", true);
            }
          }
        }
        case "SceneCastSkillScRsp" -> {
          if (battleMiracle == 58 && (step == 20 || step == 21)) {
            require(
                ((Integer) field(m, "retcode") == 0) == (step == 20)
                    && !m.hasField(m.getDescriptorForType().findFieldByName("battle_info")),
                "Unexpected destructible response at step " + step + ": " + m);
            if (step == 20) {
              require(
                  Boolean.TRUE.equals(result.get("actualDestructibleRemoved")),
                  "Successful attack omitted actual destructible removal");
              result.put("actualDestructibleAttacked", true);
              attackDestructible(c, 21);
            } else {
              result.put("destructibleRetryRejected", true);
              result.put("destructibleRetryKeepsOneCount", true);
              beginEncounter(c, enteredScene);
            }
            break;
          }
          require(
              step == 1 && (Integer) field(m, "retcode") == 0,
              "Actual rogue attack failed: " + field(m, "retcode"));
          var battle = (DynamicMessage) field(m, "battle_info");
          battleId = (Integer) field(battle, "battle_id");
          stageId = (Integer) field(battle, "stage_id");
          if (laurel) {
            battleAvatars =
                ((List<?>) field(battle, "battle_avatar_list"))
                    .stream().map(v -> (Integer) field((DynamicMessage) v, "id")).toList();
            require(!battleAvatars.isEmpty(), "Missing actual laurel battle roster");
            result.put("battleAvatars", battleAvatars);
            result.put("battleId", battleId);
            result.put("stageId", stageId);
          }
          require(
              battleId > 0 && !((List<?>) field(battle, "monster_wave_list")).isEmpty(),
              "Missing actual encounter battle");
          if (battleMiracle > 0) {
            verifyBattleBinding(battle);
            if (codeRepair)
              codeBattles.add(
                  Map.of(
                      "battleId",
                      battleId,
                      "stageId",
                      stageId,
                      "buff",
                      result.get("battleBuff"),
                      "beforeWins",
                      codeWins));
            result.put("battleId", battleId);
            result.put("stageId", stageId);
            step = 16;
            send(c, "GetCurBattleInfoCsReq", request("GetCurBattleInfoCsReq"));
            break;
          }
          step = 2;
          finish(c, true);
        }
        case "GetCurBattleInfoScRsp" -> {
          require(
              battleMiracle > 0 && step == 16 && (Integer) field(m, "retcode") == 0,
              "Battle binding query failed");
          var battle = (DynamicMessage) field(m, "battle_info");
          require(
              (Integer) field(battle, "battle_id") == battleId, "Battle identity changed on query");
          verifyBattleBinding(battle);
          result.put(
              battleMiracle == 67 ? "roomClockBattleQueryStable" : "battleBindingQueryStable",
              true);
          step = 2;
          finish(c, true);
        }
        case "PVEBattleResultScRsp" -> {
          int code = (Integer) field(m, "retcode");
          if (step == 2) {
            require(code != 0, "Wrong rogue battle identity accepted");
            step = 3;
            finish(c, false);
          } else if (step == 3) {
            require(
                code == 0 && (Integer) field(m, "battle_id") == battleId, "Rogue victory failed");
            if (laurel) {
              require(
                  field(m, "end_status").toString().equals("BATTLE_END_WIN"),
                  "Laurel did not convert the accepted defeat");
              result.put("actualLaurelLossConvertedToVictory", true);
            }
            step = 4;
            finish(c, false);
          } else {
            require(step == 4 && code != 0, "Repeated rogue victory accepted");
            result.put("actualRogueVictoryAndRetry", true);
            send(c, "GetRogueInfoCsReq", request("GetRogueInfoCsReq"));
          }
        }
        case "SyncRogueVirtualItemInfoScNotify" -> {
          if (battleMiracle == 67 && step == 13) {
            var info = (DynamicMessage) field(m, "NIKDPODBIEF");
            int balance = (Integer) field(info, "LKHAIEIDAJL");
            require(
                balance == initialBalance - initialBalance / 20,
                "Wrong room clock debit notification");
            require(!roomCurrencyNotification, "Repeated room clock debit notification");
            initialBalance = balance;
            roomCurrencyNotification = true;
            result.put("roomFragmentBalance", balance);
            result.put("roomFragmentCurrencyNotification", true);
            break;
          }
          if (step < 3 || resume && !fightLaurel && !fightMiracle) break;
          var info = (DynamicMessage) field(m, "NIKDPODBIEF");
          paidBalance = (Integer) field(info, "LKHAIEIDAJL");
          require(
              paidBalance >= initialBalance + 20 && paidBalance <= initialBalance + 40,
              "Wrong actual encounter fragment notification");
          currencyNotification = true;
        }
        case "GetRogueInfoScRsp" -> {
          if (battleMiracle == 67 && step == 18) {
            require(
                (Integer) field(m, "retcode") == 0 && roomCurrencyNotification,
                "Missing successful room debit notification");
            require(
                wallet((DynamicMessage) field(m, "rogue_game_info")) == initialBalance,
                "Repeated entry spent room fragments again");
            verifyBattleMiracle((DynamicMessage) field(m, "rogue_game_info"));
            result.put("roomClockRepeatEntryRejected", true);
            beginEncounter(c, enteredScene);
            break;
          }
          require(step == 4 && (Integer) field(m, "retcode") == 0, "Combat wallet query failed");
          int balance = wallet((DynamicMessage) field(m, "rogue_game_info"));
          require(
              currencyNotification && balance == paidBalance,
              "Combat fragments lost or duplicated");
          result.put("fragments", balance);
          result.put("fragmentCurrencyNotification", true);
          if (battleMiracle > 0) {
            if (codeRepair) {
              codeWins++;
              require(
                  codeProgress.size() == Math.min(codeWins, 2)
                      && codeReplacement == (codeWins >= 3),
                  "Missing code progress or replacement notification");
            }
            verifyBattleMiracle((DynamicMessage) field(m, "rogue_game_info"));
            if (codeRepair && codeWins < 4) {
              initialBalance = balance;
              currencyNotification = false;
              beginEncounter(c, enteredScene);
              break;
            }
            if (codeRepair) {
              result.put("codeThreeWinsRepaired", true);
              result.put("codeFourthBattleUsesRepairedBinding", true);
              result.put("codeProgressNotifications", codeProgress);
              result.put("codeReplacementNotification", codeReplacement);
              result.put("codeBattleSnapshots", codeBattles);
            }
            step = 11;
            send(c, "GetCurLineupDataCsReq", request("GetCurLineupDataCsReq"));
            break;
          }
          if (laurel) {
            require(laurelNotification, "Missing saved laurel damage notification");
            verifyLaurel((DynamicMessage) field(m, "rogue_game_info"), 1);
            step = 11;
            send(c, "GetCurLineupDataCsReq", request("GetCurLineupDataCsReq"));
            break;
          }
          step = 6;
          send(c, "GetRogueBuffEnhanceInfoCsReq", request("GetRogueBuffEnhanceInfoCsReq"));
        }
        case "SyncRogueCommonActionResultScNotify" -> {
          if (codeRepair && step == 3) {
            for (var raw : (List<?>) field(m, "CLGGKLNPLCK")) {
              var detail = (DynamicMessage) field((DynamicMessage) raw, "detail");
              var update = detail.getDescriptorForType().findFieldByName("KDJBDMLDMHG");
              var replace = detail.getDescriptorForType().findFieldByName("OMFLCEBLLMI");
              if (detail.hasField(update)) {
                var item =
                    (DynamicMessage) field((DynamicMessage) detail.getField(update), "miracle");
                if ((Integer) field(item, "LJCEOGPOGBE") != battleMiracle) continue;
                require(
                    codeWins < 2
                        && (Integer) field(item, "DGGCFDKJDPO") == codeWins + 1
                        && (Integer) field(item, "total_count") == 3
                        && !codeProgress.contains(codeWins + 1),
                    "Wrong or duplicate code progress notification");
                codeProgress.add(codeWins + 1);
              } else if (detail.hasField(replace)) {
                var replacement = (DynamicMessage) detail.getField(replace);
                var item = (DynamicMessage) field(replacement, "HLCPFLOPGBM");
                require(
                    codeWins == 2
                        && !codeReplacement
                        && replace.getNumber() == 1957
                        && (Integer) field(replacement, "BJIBCOHEGIJ") == battleMiracle
                        && (Integer) field(item, "LJCEOGPOGBE") == battleMiracle + 1
                        && (Integer) field(item, "DGGCFDKJDPO") == 0
                        && (Integer) field(item, "total_count") == 0,
                    "Wrong or duplicate native code replacement notification");
                codeReplacement = true;
              }
            }
            break;
          }
          if (!laurel || step != 3) break;
          for (var raw : (List<?>) field(m, "CLGGKLNPLCK")) {
            var detail = (DynamicMessage) field((DynamicMessage) raw, "detail");
            if (!detail.hasField(detail.getDescriptorForType().findFieldByName("KDJBDMLDMHG")))
              continue;
            var item =
                (DynamicMessage) field((DynamicMessage) field(detail, "KDJBDMLDMHG"), "miracle");
            if ((Integer) field(item, "LJCEOGPOGBE") != 61) continue;
            require(
                (Integer) field(item, "DGGCFDKJDPO") == 1
                    && (Integer) field(item, "total_count") == 1,
                "Wrong laurel damage counter notification");
            laurelNotification = true;
            result.put("laurelDamageNotification", true);
          }
        }
        case "GetCurLineupDataScRsp" -> {
          require(
              (laurel || battleMiracle > 0) && (step == 11 || step == 12 || step == 14),
              "Unexpected exploration lineup query");
          var team = (DynamicMessage) field(m, "lineup");
          var avatars = (List<?>) field(team, "avatar_list");
          require(
              field(team, "extra_lineup_type").toString().equals("ExtraLineupType_LineupRogue")
                  && !avatars.isEmpty(),
              "Laurel restored the wrong lineup");
          if (battleMiracle > 0) {
            int mp = (Integer) field(team, "mp");
            if (step == 14) {
              initialMp = mp;
              nextCombatRoom(c);
              break;
            }
            require(
                mp == (step == 11 ? initialMp : expectedMp),
                "Battle boost-point effect incorrectly spent exploration technique points");
            result.put("techniquePoints", mp);
            result.put("battleTechniquePointsUnchanged", true);
            step = 5;
            send(c, "LeaveRogueCsReq", request("LeaveRogueCsReq"));
            break;
          }
          for (var raw : avatars)
            require(
                ((Number) field((DynamicMessage) raw, "hp")).intValue() == 10000,
                "Laurel full health was overwritten by defeat stats or lost on reload");
          result.put("laurelFullHealth", true);
          step = 5;
          send(c, "LeaveRogueCsReq", request("LeaveRogueCsReq"));
        }
        case "GetRogueBuffEnhanceInfoScRsp" -> {
          require(
              step == 6 && (Integer) field(m, "PLDKENJLKME") != 0,
              "Enhancement shop opened outside the actual rest room");
          result.put("nativeEnhancementOutsideRestRoomRejected", true);
          step = 7;
          var body =
              UnknownFieldSet.newBuilder()
                  .addField(13, UnknownFieldSet.Field.newBuilder().addVarint(600000).build())
                  .build();
          var packet =
              Unpooled.wrappedBuffer(PacketCodec.encode(1885, new byte[0], body.toByteArray()));
          try {
            c.write(packet);
          } finally {
            packet.release();
          }
        }
        case "PickRogueAvatarScRsp" -> {
          require(
              step == 9
                  && (Integer) field(m, "retcode") != 0
                  && ((List<?>) field(m, "NOEPBNMJFGL")).isEmpty(),
              "Invalid native avatar download accepted");
          result.put("nativeAvatarDownloadInvalidTargetRejected", true);
          step = 10;
          var edit = request("ReplaceLineupCsReq");
          var extra = edit.getDescriptorForType().findFieldByName("extra_lineup_type");
          edit.setField(extra, extra.getEnumType().findValueByNumber(2));
          var slotField = edit.getDescriptorForType().findFieldByName("lineup_slot_list");
          var slot = DynamicMessage.newBuilder(slotField.getMessageType());
          set(slot, "id", emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
          var avatarType = slot.getDescriptorForType().findFieldByName("avatar_type");
          slot.setField(avatarType, avatarType.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
          edit.addRepeatedField(slotField, slot.build());
          send(c, "ReplaceLineupCsReq", edit);
        }
        case "LeaveRogueScRsp" -> {
          require(step == 5 && (Integer) field(m, "retcode") == 0, "Combat pause failed");
          require(
              field((DynamicMessage) field(m, "lineup"), "extra_lineup_type")
                  .toString()
                  .equals("ExtraLineupType_LineupNone"),
              "Normal combat lineup not restored");
          result.put("passed", true);
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
      var probe = new LoopbackRogueCombatProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      probe.laurel = credentials.has("laurel") && credentials.get("laurel").getAsBoolean();
      probe.fightLaurel =
          credentials.has("fightLaurel") && credentials.get("fightLaurel").getAsBoolean();
      probe.battleMiracle =
          credentials.has("battleMiracle") ? credentials.get("battleMiracle").getAsInt() : 0;
      require(
          probe.battleMiracle == 0
              || Set.of(
                      8, 10, 14, 19, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 58, 59, 62, 66, 67,
                      68, 71)
                  .contains(probe.battleMiracle),
          "Unsupported battle-miracle probe");
      probe.codeRepair =
          credentials.has("codeRepair") && credentials.get("codeRepair").getAsBoolean();
      require(
          !probe.codeRepair || Set.of(45, 47, 49, 51, 53, 55).contains(probe.battleMiracle),
          "Code repair probe requires a broken source variant");
      probe.fightMiracle =
          credentials.has("fightMiracle") && credentials.get("fightMiracle").getAsBoolean();
      probe.expectedMp =
          credentials.has("expectedMp") ? credentials.get("expectedMp").getAsInt() : 0;
      if (probe.resume) probe.expectedBalance = credentials.get("expectedFragments").getAsInt();
      var token = probe.request("PlayerGetTokenCsReq");
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
                    new IllegalStateException("Combat probe disconnected"));
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
          "Rogue combat probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
