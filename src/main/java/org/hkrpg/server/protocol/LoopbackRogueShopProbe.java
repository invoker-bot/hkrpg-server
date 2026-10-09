package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/**
 * Actual resource merchant over UDP/KCP; the runner explicitly uses an isolated room/wallet
 * fixture.
 */
public final class LoopbackRogueShopProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private Map<Integer, Integer> stock = new TreeMap<>();
  private Map<Integer, Integer> expectedBlessings = new TreeMap<>();
  private final Map<Integer, Integer> notifiedBlessings = new TreeMap<>();
  private boolean resume, miracle, currencyNotification, itemNotification;
  private int expectedBalance, entity, selected, step;
  private int diceReplacementId, expectedDiceReplacementId, diceReplacementNotifications;
  private boolean verifyRestExit;
  private int exitSite, exitRoom;
  private Map<Integer, Integer> beforeExitBlessings = Map.of();
  private Set<Integer> expectedImmediateBuffs = Set.of();
  private boolean verifyHandbook, handbookOnly, clearRun;
  private Set<Integer> expectedHandbookBuffs = Set.of(), expectedHandbookMiracles = Set.of();
  private Map<Integer, Integer> handbookUnlocks = Map.of();
  private int handbookQueries, handbookNotifications;
  private boolean verifyHandbookReward;
  private Set<Integer> claimedHandbookMiracles = Set.of();
  private int loginJades, notifiedJades = -1, rewardJadeNotifications;
  private boolean verifyBlessingQuests, blessingQuestLoginVerified;
  private Map<Integer, Integer> blessingQuestGoals = Map.of(), blessingQuestPaths = Map.of(), blessingHandbookPaths = Map.of();
  private Map<Integer, Integer> blessingQuestProgress = Map.of();
  private int blessingQuestQueries, blessingQuestNotifications;
  private int blessingTier, tierGoal, tierAmount, initialFragments = 300, tierExplorations, tierActualPurchases, tierWalletNotifications;
  private boolean tierClaimRequested, tierClaimed, tierRetryRejected;
  private boolean verifyMiracleQuests;
  private int miracleTier;
  private Set<Integer> handbookEligibleBuffs = Set.of();
  private Map<Integer, Set<Integer>> miraclePathBuffs = Map.of();
  private Set<Integer> allowedMiracles = Set.of();
  private int tierRoundQuestNotifications, tierRoundHandbookNotifications;

  private boolean collectionQuests() { return verifyBlessingQuests || verifyMiracleQuests; }
  private int collectionTier() { return miracleTier > 0 ? miracleTier : blessingTier; }
  private Set<Integer> tierCollections() { return verifyMiracleQuests ? expectedHandbookMiracles : expectedHandbookBuffs; }

  private void startTierExploration(Ukcp c) {
    require(++tierExplorations <= 400, "Source shop did not provide sufficient distinct opportunities");
    selected = 0; entity = 0; currencyNotification = false; itemNotification = false; step = 0;
    notifiedBlessings.clear();
    tierRoundQuestNotifications=0;tierRoundHandbookNotifications=0;
    var b = request("StartRogueCsReq"); set(b,"area_id",100);
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
    send(c,"StartRogueCsReq",b);
  }

  private void quitForTier(Ukcp c) {
    step = 114; var b=request("QuitRogueCsReq");set(b,"area_id",100);send(c,"QuitRogueCsReq",b);
  }

  private void claimTier(Ukcp c) {
    var b=request("TakeQuestRewardCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"),collectionTier());
    send(c,"TakeQuestRewardCsReq",b);
  }

  private void claimHandbook(Ukcp c, Set<Integer> ids, boolean packed) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    if (packed) {
      var list = new java.io.ByteArrayOutputStream();
      var values = CodedOutputStream.newInstance(list);
      for (int id : new TreeSet<>(ids)) values.writeUInt32NoTag(id);
      values.flush(); out.writeByteArray(2, list.toByteArray());
    } else for (int id : new TreeSet<>(ids)) out.writeUInt32(2, id);
    out.flush();
    var packet = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand("TakeRogueMiracleHandbookRewardCsReq"), new byte[0], bytes.toByteArray()));
    try { c.write(packet); } finally { packet.release(); }
  }

  private void queryHandbook(Ukcp c) {
    send(c, "GetRogueHandbookDataCsReq", request("GetRogueHandbookDataCsReq"));
  }

  private Set<Integer> handbookIds(DynamicMessage message, String list, String identity) {
    var ids = new TreeSet<Integer>();
    for (var raw : (List<?>) field(message, list)) {
      var row = (DynamicMessage) raw;
      int id = (Integer) field(row, identity);
      require(id > 0 && ids.add(id), "Invalid or duplicate actual handbook record");
      if (identity.equals("NPNMLPNBHDP"))
        require((Boolean) field(row, "ALAJJMKOMML") == claimedHandbookMiracles.contains(id), "Actual handbook reward status differs from confirmed claim");
    }
    return ids;
  }

  private Set<Integer> acquiredHandbookMiracles() {
    var ids = new TreeSet<>(expectedHandbookMiracles);
    if (miracle) {
      int unlocked = handbookUnlocks.getOrDefault(selected, 0);
      if (unlocked > 0) ids.add(unlocked);
      if (selected == 21) {
        int replacement = resume ? expectedDiceReplacementId : diceReplacementId;
        int replacementUnlock = handbookUnlocks.getOrDefault(replacement, 0);
        require(replacementUnlock > 0, "Replacement lacks original explicit handbook identity");
        ids.add(replacementUnlock);
      }
    }
    return ids;
  }

  private Set<Integer> acquiredHandbookBuffs() {
    var ids = new TreeSet<>(expectedHandbookBuffs);
    if (!miracle) ids.add(selected);
    if (immediateRewards()) {
      var acquired = (resume ? expectedBlessings : notifiedBlessings).keySet();
      if (verifyMiracleQuests) acquired.stream().filter(handbookEligibleBuffs::contains).forEach(ids::add);
      else ids.addAll(acquired);
    }
    return ids;
  }

  private void finishHandbook(Ukcp c) {
    if (collectionQuests() && step != 100) {
      step = 100;
      send(c, "GetQuestDataCsReq", request("GetQuestDataCsReq"));
      return;
    }
    result.put("handbookBuffs", new TreeSet<>(expectedHandbookBuffs));
    result.put("handbookMiracles", new TreeSet<>(expectedHandbookMiracles));
    result.put("handbookQueries", handbookQueries);
    result.put("handbookNotifications", handbookNotifications);
    result.put("handbookRunCleared", clearRun);
    if (collectionQuests()) {
      require(blessingQuestLoginVerified && blessingQuestQueries == 2, "Source quest login/query snapshot missing");
      require(resume || blessingQuestNotifications > 0, "Actual collection omitted quest progress notification");
      String prefix = verifyMiracleQuests ? "miracleQuest" : "blessingQuest";
      result.put(prefix+"Progress", blessingQuestProgress);
      result.put(prefix+"Queries", blessingQuestQueries);
      result.put(prefix+"Notifications", blessingQuestNotifications);
      result.put(prefix+"LoginSnapshotVerified", blessingQuestLoginVerified);
      result.put("loginJades", loginJades);
      if (collectionTier() > 0) {
        require(tierClaimed && (resume || tierRetryRejected && tierWalletNotifications > 0), "Original source tier reward claim was not verified");
        result.put(verifyMiracleQuests ? "miracleTier" : "blessingTier",collectionTier());result.put("tierGoal",tierGoal);result.put("tierAmount",tierAmount);
        result.put("tierClaimed",tierClaimed);result.put("tierRetryRejected",tierRetryRejected);
        result.put("tierWalletNotifications",tierWalletNotifications);result.put("tierActualPurchases",tierActualPurchases);
        result.put("tierExplorations",tierExplorations);result.put("confirmedJades",resume?loginJades:loginJades+tierAmount);
      }
    }
    if (verifyHandbookReward) {
      result.put("handbookClaimed", new TreeSet<>(claimedHandbookMiracles));
      result.put("loginJades", loginJades);
      result.put("rewardJadeNotifications", rewardJadeNotifications);
      result.put("confirmedJades", notifiedJades < 0 ? loginJades : notifiedJades);
    }
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private boolean immediateRewards() {
    return selected == 5 || (selected >= 22 && selected <= 28);
  }

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

  private void sendShop(Ukcp c, boolean buy) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt32(miracle ? 12 : buy ? 2 : 15, entity);
    if (buy && miracle) {
      out.writeUInt32(6, selected);
    } else if (buy) {
      var packed = new java.io.ByteArrayOutputStream();
      var ids = CodedOutputStream.newInstance(packed);
      ids.writeUInt32NoTag(selected);
      ids.flush();
      out.writeByteArray(6, packed.toByteArray());
    }
    out.flush();
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                miracle ? buy ? 5656 : 5675 : buy ? 5615 : 5616, new byte[0], bytes.toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private int price(DynamicMessage row) {
    var costs = (DynamicMessage) field(row, "cost_data");
    var list = (List<?>) field(costs, "item_list");
    require(list.size() == 1, "Wrong shop costs");
    var pile = (DynamicMessage) field((DynamicMessage) list.getFirst(), "pile_item");
    require((Integer) field(pile, "item_id") == 31, "Shop did not use cosmic fragments");
    int cost = (Integer) field(pile, "item_num");
    require(cost > 0 && cost <= 300, "Unexpected frozen project price");
    require(costs.equals(field(row, "KINNMAGDDJJ")), "Original quote changed");
    return cost;
  }

  private List<DynamicMessage> rows(DynamicMessage response) {
    return ((List<?>)
            field(
                (DynamicMessage) field(response, miracle ? "DNPCCFFFBHN" : "HABJHGPFGEB"),
                miracle ? "MAJGMMCOCIG" : "buff_list"))
        .stream().map(v -> (DynamicMessage) v).toList();
  }

  private int itemId(DynamicMessage row) {
    return (Integer) field(row, miracle ? "LJCEOGPOGBE" : "buff_id");
  }

  private int expectedMiracleLifetime() {
    return Set.of(45, 47, 49, 51, 53, 55).contains(selected)
        ? 3
        : selected == 1 ? 2 : selected == 4 || selected == 7 || selected == 61 ? 1 : selected == 60 ? 5 : 0;
  }

  private Map<Integer, Integer> ownedBlessings(DynamicMessage game) {
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var buffs = (DynamicMessage) field(current, "HABJHGPFGEB");
    var owned = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(buffs, "CHCMEKAFKAF")) {
      var buff = (DynamicMessage) raw;
      int id = (Integer) field(buff, "buff_id"), level = (Integer) field(buff, "level");
      require(
          id > 0 && owned.putIfAbsent(id, level) == null, "Duplicate or invalid owned blessing");
    }
    return owned;
  }

  private void restExitScene(DynamicMessage scene, DynamicMessage game) {
    boolean mapPortal = false;
    for (var rawGroup : (List<?>) field(scene, "entity_group_list"))
      for (var rawEntity : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var row = (DynamicMessage) rawEntity;
        if (!row.hasField(row.getDescriptorForType().findFieldByName("prop"))) continue;
        var prop = (DynamicMessage) field(row, "prop");
        var extra = (DynamicMessage) field(prop, "extra_info");
        if (!extra.hasField(extra.getDescriptorForType().findFieldByName("rogue_game_info"))) {
          if ((Integer) field(prop, "prop_id") == 1000
              && (Integer) field(row, "group_id") == 45
              && (Integer) field(row, "inst_id") == 300001) {
            require(
                (Integer) field(prop, "prop_state")
                    == emu.lunarcore.game.enums.PropState.Open.getVal(),
                "The actual Duck map portal is closed; saved map="
                    + field((DynamicMessage) field(game, "CFDIKLBINHK"), "COOOMDKGODF"));
            mapPortal = true;
          }
          continue;
        }
        var destination = (DynamicMessage) field(extra, "rogue_game_info");
        require(
            (Integer) field(prop, "prop_state") == emu.lunarcore.game.enums.PropState.Open.getVal(),
            "Actual noncombat rest portal is still closed");
        if (exitSite == 0) {
          exitSite = (Integer) field(destination, "MAJKFIMPOOF");
          exitRoom = (Integer) field(destination, "room_id");
        }
      }
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var map = (DynamicMessage) field(current, "COOOMDKGODF");
    boolean finished = false, unlocked = false;
    for (var raw : (List<?>) field(map, "LLNICHDDIAN")) {
      var room = (DynamicMessage) raw;
      int site = (Integer) field(room, "MAJKFIMPOOF");
      String status = field(room, "CAGDNHCOCKM").toString();
      if (site == 1) finished = status.equals("RogueRoomStatus_Finish");
      if (mapPortal && exitSite == 0 && status.equals("RogueRoomStatus_Unlock")) {
        exitSite = site;
        exitRoom = (Integer) field(room, "room_id");
      }
      if (site == exitSite)
        unlocked =
            status.equals("RogueRoomStatus_Unlock") && (Integer) field(room, "room_id") == exitRoom;
    }
    require(
        exitSite > 1 && exitRoom > 0 && finished && unlocked,
        "No matching open rest portal and available adjacent map destination: mapPortal="
            + mapPortal
            + ", exitSite="
            + exitSite
            + ", exitRoom="
            + exitRoom
            + ", savedMap="
            + map);
    result.put("actualRestPortalOpen", true);
    result.put("restPortalKind", mapPortal ? "map-selection" : "direct");
    result.put("exitSite", exitSite);
    result.put("exitRoom", exitRoom);
  }

  private void sendExit(Ukcp c, boolean wrongRoom) {
    var b = request("EnterRogueMapRoomCsReq");
    set(b, "MAJKFIMPOOF", exitSite);
    set(b, "room_id", wrongRoom ? exitRoom + 1 : exitRoom);
    send(c, "EnterRogueMapRoomCsReq", b);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated shop probe frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "GetRogueShopBuffInfoScRsp",
                  "BuyRogueShopBuffScRsp",
                  "GetRogueShopMiracleInfoScRsp",
                  "BuyRogueShopMiracleScRsp",
                  "GetRogueInfoScRsp",
                  "EnterRogueMapRoomScRsp",
                  "LeaveRogueScRsp",
                  "QuitRogueScRsp",
                  "GetRogueHandbookDataScRsp",
                  "SyncRogueHandbookDataUpdateScNotify",
                  "TakeRogueMiracleHandbookRewardScRsp",
                  "GetQuestDataScRsp",
                  "TakeQuestRewardScRsp",
                  "PlayerSyncScNotify",
                  "SyncRogueVirtualItemInfoScNotify",
                  "SyncRogueCommonActionResultScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + name);
      switch (name) {
        case "GetQuestDataScRsp" -> {
          if (!collectionQuests()) break;
          require((Integer) field(m,"retcode") == 0, "Source quest query failed");
          var collected = new TreeSet<>(expectedHandbookBuffs);
          if (!resume && step == 2) collected.add(selected);
          var expected = new TreeMap<Integer,Integer>();
          for (int id:blessingQuestGoals.keySet()) {
            int path = blessingQuestPaths.get(id);
            int count = verifyMiracleQuests ? (step == 2 && !resume ? acquiredHandbookMiracles() : expectedHandbookMiracles).size() : (int) collected.stream().filter(buff -> blessingHandbookPaths.getOrDefault(buff,0) == path).count();
            expected.put(id,Math.min(count,blessingQuestGoals.get(id)));
          }
          var actual = new TreeMap<Integer,Integer>();
          for (var raw:(List<?>) field(m,"quest_list")) {
            var row=(DynamicMessage) raw; int id=(Integer) field(row,"id");
            if (!blessingQuestGoals.containsKey(id)) continue;
            require(actual.putIfAbsent(id,(Integer) field(row,"progress")) == null,"Duplicate source quest identity");
            int phase = expected.get(id) == blessingQuestGoals.get(id).intValue() ? 2 : 1;
            if (id == collectionTier() && (tierClaimRequested || tierClaimed)) phase = 3;
            require(((com.google.protobuf.Descriptors.EnumValueDescriptor) field(row,"status")).getNumber() == phase
                && ((Long) field(row,"finish_time") > 0) == (phase != 1),"Source tier phase/time differs from its actual distinct count");
          }
          require(actual.equals(expected),"Source quest progress differs from actual distinct collection: " + actual + " expected " + expected);
          if (step == 101) {
            blessingQuestLoginVerified = true; blessingQuestQueries++;
            step = handbookOnly ? 90 : 80; queryHandbook(c);
          } else if (step == 100) {
            blessingQuestProgress = actual; blessingQuestQueries++; finishHandbook(c);
          } else if (!resume && step == 2) {blessingQuestNotifications++;tierRoundQuestNotifications++;}
          else blessingQuestLoginVerified = true;
        }
        case "PlayerGetTokenScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Shop token rejected");
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Shop login rejected");
          if (verifyHandbookReward || collectionQuests()) loginJades = (Integer) field((DynamicMessage) field(m,"basic_info"),"hcoin");
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Shop login finish rejected");
          if (collectionQuests()) {
            step = 101; send(c,"GetQuestDataCsReq",request("GetQuestDataCsReq")); break;
          }
          if (verifyHandbook) {
            step = handbookOnly ? 90 : 80;
            queryHandbook(c);
            break;
          }
          var b = request(resume ? "EnterRogueCsReq" : "StartRogueCsReq");
          set(b, "area_id", 100);
          if (!resume)
            b.addRepeatedField(
                b.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
                emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
          send(c, resume ? "EnterRogueCsReq" : "StartRogueCsReq", b);
        }
        case "SyncRogueHandbookDataUpdateScNotify" -> {
          if (verifyHandbook) {
            require(!resume && step == 2, "Handbook query or reconnection caused a collection notification");
            var buffs = handbookIds(m, "IPNAAGCMNLB", "maze_buff_id");
            var miracles = handbookIds(m, "BFDFABHMCNE", "NPNMLPNBHDP");
            require(!buffs.isEmpty() || !miracles.isEmpty(), "Empty fake collection notification");
            require(miracles.equals(acquiredHandbookMiracles()), "Notification contains uncollected or wrongly aliased miracles");
            if (!miracle) require(buffs.equals(blessingTier > 0 ? acquiredHandbookBuffs() : Set.of(selected)), "Purchased blessing absent from native handbook update");
            if (verifyMiracleQuests) {
              // A two-blessing box commits both before its first individual action notification.
              var freshBuffs=new HashSet<>(buffs);freshBuffs.removeAll(expectedHandbookBuffs);
              require(buffs.containsAll(expectedHandbookBuffs) && handbookEligibleBuffs.containsAll(freshBuffs)
                  && freshBuffs.size()<=(immediateRewards() ? selected==5 ? 2 : 1 : 0)
                  && (!immediateRewards() || selected==5 || expectedImmediateBuffs.containsAll(freshBuffs)),
                  "Curio handbook contains a blessing outside the committed source reward");
            }
            if (miracle && !immediateRewards() && !verifyMiracleQuests) require(buffs.isEmpty(), "Curio purchase invented blessing collection");
            handbookNotifications++;
            tierRoundHandbookNotifications++;
          }
        }
        case "GetRogueHandbookDataScRsp" -> {
          require(verifyHandbook && Set.of(80, 81, 82, 84, 87, 90, 92).contains(step), "Unsolicited handbook response");
          require((Integer) field(m, "retcode") == 0, "Actual handbook query failed");
          var data = (DynamicMessage) field(m, "HAMKPLCBEBG");
          var buffs = handbookIds(data, "buff_list", "maze_buff_id");
          var miracles = handbookIds(data, "MAJGMMCOCIG", "NPNMLPNBHDP");
          require(((List<?>) field(data, "PMIFFBHOOOO")).isEmpty(), "Miracles were incorrectly encoded as event collection");
          var expectedBuffs = step == 81 ? acquiredHandbookBuffs() : expectedHandbookBuffs;
          var expectedMiracles = step == 81 ? acquiredHandbookMiracles() : expectedHandbookMiracles;
          require(buffs.equals(expectedBuffs) && miracles.equals(expectedMiracles), "Actual saved handbook differs from explicit collected identities");
          handbookQueries++;
          if (step == 80) {
            if (collectionTier() > 0 && !resume) {startTierExploration(c);break;}
            step = 0;
            var b = request(resume ? "EnterRogueCsReq" : "StartRogueCsReq");
            set(b, "area_id", 100);
            if (!resume) b.addRepeatedField(b.getDescriptorForType().findFieldByName("NOEPBNMJFGL"), emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
            send(c, resume ? "EnterRogueCsReq" : "StartRogueCsReq", b);
          } else if (step == 81) {
            require(resume || handbookNotifications > 0 || miracles.isEmpty() && buffs.isEmpty(), "Actual acquisition lacked handbook notification");
            expectedHandbookBuffs = buffs;
            expectedHandbookMiracles = miracles;
            step = 82;
            queryHandbook(c);
          } else if (step == 82 && collectionTier() > 0 && !resume && !tierClaimed) {
            require(tierCollections().size() == tierActualPurchases,"A purchase was duplicated or did not collect exactly one distinct source blessing");
            if (tierCollections().size() < tierGoal) quitForTier(c);
            else {
              require(tierCollections().size()==tierGoal,"Native acquisition exceeded the exact source tier goal");
              tierClaimRequested=true;step=112;claimTier(c);
            }
          } else if (step == 82 && verifyHandbookReward && !resume) {
            require(!expectedHandbookMiracles.isEmpty(), "No actual collected miracle to claim");
            step = 83;
            var invalid = new TreeSet<>(expectedHandbookMiracles); invalid.add(99999999);
            claimHandbook(c, invalid, true);
          } else if (step == 84) {
            require(claimedHandbookMiracles.isEmpty() && rewardJadeNotifications == 0, "Invalid batch paid or marked an earlier valid reward");
            step = 85; claimHandbook(c, expectedHandbookMiracles, true);
          } else if (step == 90 && clearRun) {
            step = 91;
            var b = request("QuitRogueCsReq");
            set(b, "area_id", 100);
            send(c, "QuitRogueCsReq", b);
          } else if (resume && verifyMiracleQuests && !tierRetryRejected) {
            step=113;claimTier(c);
          } else finishHandbook(c);
        }
        case "PlayerSyncScNotify" -> {
          if (collectionQuests() && m.hasField(m.getDescriptorForType().findFieldByName("basic_info")))
          {
            int actual = (Integer) field((DynamicMessage) field(m,"basic_info"),"hcoin");
            if (collectionTier() > 0 && !resume && tierClaimRequested) {
              require(actual==loginJades+tierAmount,"Source tier did not pay its exact original jade amount");
              tierWalletNotifications++;
            } else require(actual == loginJades,"A collection incorrectly paid an unclaimed handbook tier");
          }
          if (verifyHandbookReward && m.hasField(m.getDescriptorForType().findFieldByName("basic_info"))) {
            int jades = (Integer) field((DynamicMessage) field(m,"basic_info"),"hcoin");
            if (jades != loginJades) {
              require(!resume && step == 85 && jades > loginJades && jades <= loginJades + 30 * expectedHandbookMiracles.size(), "Query, retry or unrelated stage paid handbook jades");
              notifiedJades = jades; rewardJadeNotifications++;
            }
          }
        }
        case "TakeQuestRewardScRsp" -> {
          require(collectionTier() > 0 && (!resume || verifyMiracleQuests && step==113) && (step==112 || step==113),"Unexpected source tier claim response");
          var ids=(List<?>) field(m,"succ_quest_id_list");
          var items=(List<?>) field((DynamicMessage) field(m,"reward"),"item_list");
          if (step==112) {
            require((Integer) field(m,"retcode")==0 && ids.equals(List.of(collectionTier())) && items.size()==1
                && tierWalletNotifications>0,"Source tier claim lacked actual payment or its confirmed identity");
            var item=(DynamicMessage) items.getFirst();
            require((Integer) field(item,"item_id")==1 && (Integer) field(item,"num")==tierAmount,"Source tier reward items differ from actual jades");
            tierClaimed=true;step=113;claimTier(c);
          } else {
            require((Integer) field(m,"retcode")!=0 && ids.isEmpty() && items.isEmpty(),"Repeated source tier reward claim paid again");
            tierRetryRejected=true;finishHandbook(c);
          }
        }
        case "TakeRogueMiracleHandbookRewardScRsp" -> {
          require(verifyHandbookReward && Set.of(83,85,86).contains(step), "Unexpected handbook reward response");
          var identities = new TreeSet<Integer>();
          for (var value : (List<?>) field(m,"CNDLCGEFLCJ")) require(identities.add((Integer)value), "Duplicate returned paid identity");
          var reward = (DynamicMessage) field(m,"reward");
          int amount=0;
          for (var value : (List<?>) field(reward,"item_list")) {
            var item=(DynamicMessage)value;
            require((Integer)field(item,"item_id")==1 && (Integer)field(item,"num")==30, "Handbook returned an unauthored or falsely reported reward");
            amount += 30;
          }
          if (step == 85) {
            require((Integer)field(m,"retcode")==0 && identities.equals(expectedHandbookMiracles) && amount==30*identities.size(), "Original collected reward claim failed");
            require(notifiedJades==loginJades+amount && rewardJadeNotifications>0, "Actual jade balance was not synchronized before success");
            claimedHandbookMiracles=Set.copyOf(identities);
            result.put("handbookOriginalRewardActuallyPaid",true);
            step=86; claimHandbook(c,expectedHandbookMiracles,false);
          } else {
            require((Integer)field(m,"retcode")!=0 && identities.isEmpty() && amount==0, "Invalid or repeated claim paid or reported reward");
            if (step==83) {
              result.put("handbookInvalidBatchNoPayment",true);step=84;
            } else {
              result.put("handbookClaimRetryRejected",true);step=87;
            }
            queryHandbook(c);
          }
        }
        case "QuitRogueScRsp" -> {
          if (collectionTier() > 0 && !resume && step==114) {
            require((Integer) field(m,"retcode")==0,"Source exploration reset failed");startTierExploration(c);break;
          }
          require(verifyHandbook && handbookOnly && clearRun && step == 91 && (Integer) field(m, "retcode") == 0, "Actual exploration clearing failed");
          step = 92;
          queryHandbook(c);
        }
        case "StartRogueScRsp", "EnterRogueScRsp" -> {
          require(
              step == 0 && (Integer) field(m, "retcode") == 0,
              "Resource merchant room entry rejected");
          int balance = wallet((DynamicMessage) field(m, "rogue_game_info"));
          require(
              balance == (resume ? expectedBalance : initialFragments), "Fixture or saved shop wallet changed");
          if (miracle && immediateRewards()) {
            var buffs = ownedBlessings((DynamicMessage) field(m, "rogue_game_info"));
            require(
                resume ? buffs.equals(expectedBlessings) : buffs.isEmpty(),
                "Immediate reward history changed before purchase or on reentry");
          }
          var scene = (DynamicMessage) field(m, "scene");
          if (verifyRestExit) restExitScene(scene, (DynamicMessage) field(m, "rogue_game_info"));
          for (var group : (List<?>) field(scene, "entity_group_list"))
            for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
              var npc = (DynamicMessage) raw;
              if (npc.hasField(npc.getDescriptorForType().findFieldByName("npc"))
                  && (Integer) field((DynamicMessage) field(npc, "npc"), "npc_id")
                      == (miracle ? 1013 : 1007)) {
                require(entity == 0, "Duplicate merchant in assigned room");
                entity = (Integer) field(npc, "entity_id");
                require(
                    (Integer) field(npc, "group_id") == (miracle ? 35 : 77)
                        && (Integer) field(npc, "inst_id") == (miracle ? 400001 : 400002),
                    "Wrong source merchant binding");
              }
            }
          require(entity > 0, "Source room did not load the actual merchant");
          result.put("entry", field(scene, "entry_id"));
          result.put("entity", entity);
          step = 1;
          sendShop(c, false);
        }
        case "GetRogueShopBuffInfoScRsp", "GetRogueShopMiracleInfoScRsp" -> {
          require(step == 1 && (Integer) field(m, "retcode") == 0, "Actual merchant query failed");
          var rows = rows(m);
          require(!rows.isEmpty() && rows.size() <= 3, "Merchant stock missing");
          var current = new TreeMap<Integer, Integer>();
          for (var row : rows) {
            if (!miracle) require((Integer) field(row, "JBGENFIKGGF") == 1, "Wrong blessing level");
            require(current.put(itemId(row), price(row)) == null, "Duplicate stock");
          }
          if (resume) {
            require(current.equals(stock), "Saved shop inventory or prices changed");
            var bought = rows.stream().filter(r -> itemId(r) == selected).findFirst().orElseThrow();
            require(
                (Boolean) field(bought, "GCIHGKCJOGB")
                    && (Boolean) field(bought, "BELPGDAGFIH") == (selected != 21),
                "Sold ownership did not restore");
            result.put("savedShopStockAndOwnershipRestored", true);
            step = 4;
          } else {
            stock = current;
            if (collectionTier() > 0) {
              selected=stock.keySet().stream().filter(id -> (!verifyMiracleQuests || allowedMiracles.contains(id)) && !tierCollections().contains(miracle ? handbookUnlocks.getOrDefault(id,0) : id)).findFirst().orElse(0);
              if (verifyMiracleQuests) expectedImmediateBuffs=miraclePathBuffs.getOrDefault(selected,Set.of());
              if (selected==0) {step=115;send(c,"LeaveRogueCsReq",request("LeaveRogueCsReq"));break;}
            }
            if (selected <= 0) selected = stock.keySet().iterator().next();
            require(stock.containsKey(selected), "Requested item absent from source fixture stock");
            expectedBalance = initialFragments - stock.get(selected);
            var first = rows.stream().filter(r -> itemId(r) == selected).findFirst().orElseThrow();
            require(
                !(Boolean) field(first, "GCIHGKCJOGB") && !(Boolean) field(first, "BELPGDAGFIH"),
                "Fresh stock not available");
            step = 2;
          }
          sendShop(c, true);
        }
        case "BuyRogueShopBuffScRsp", "BuyRogueShopMiracleScRsp" -> {
          if (step == 2) {
            require((Integer) field(m, "retcode") == 0, "Positive native merchant purchase failed");
            if (verifyMiracleQuests) require(currencyNotification && itemNotification && tierRoundQuestNotifications>0
                && tierRoundHandbookNotifications>0,"Actual collection/progress/currency notifications did not precede purchase success");
            var bought =
                rows(m).stream().filter(r -> itemId(r) == selected).findFirst().orElseThrow();
            require(
                (Boolean) field(bought, "GCIHGKCJOGB")
                    && (Boolean) field(bought, "BELPGDAGFIH") == (selected != 21),
                "Purchase stock not marked owned and unavailable");
            result.put("nativeMerchantPurchase", true);
            if (collectionTier() > 0) tierActualPurchases++;
            step = 4;
            sendShop(c, true);
          } else {
            require(
                step == 4 && (Integer) field(m, "retcode") != 0,
                "Repeated merchant purchase accepted");
            result.put("nativeMerchantRetryRejected", true);
            step = 5;
            send(c, "GetRogueInfoCsReq", request("GetRogueInfoCsReq"));
          }
        }
        case "SyncRogueVirtualItemInfoScNotify" -> {
          if (!resume && step >= 2 && !(collectionTier() > 0 && (step==114 || step==115))) {
            require(
                (Integer) field((DynamicMessage) field(m, "NIKDPODBIEF"), "LKHAIEIDAJL")
                    == expectedBalance,
                "Wrong merchant balance notification");
            currencyNotification = true;
          }
        }
        case "SyncRogueCommonActionResultScNotify" -> {
          if (!resume && step == 2)
            for (var raw : (List<?>) field(m, "CLGGKLNPLCK")) {
              var detail = (DynamicMessage) field((DynamicMessage) raw, "detail");
              if (miracle
                  && immediateRewards()
                  && detail.hasField(
                      detail.getDescriptorForType().findFieldByName("IFOOEPKGGAG"))) {
                var buff = (DynamicMessage) field(detail, "IFOOEPKGGAG");
                int id = (Integer) field(buff, "buff_id"),
                    level = (Integer) field(buff, "HAGDKKBAAKK");
                require(
                    id > 0 && level == 1 && notifiedBlessings.putIfAbsent(id, level) == null,
                    "Invalid or duplicate immediate blessing notification");
              } else if (miracle
                  && detail.hasField(
                      detail.getDescriptorForType().findFieldByName("GPJJMPCMAAA"))) {
                var added = (DynamicMessage) field(detail, "GPJJMPCMAAA");
                var item = (DynamicMessage) field(added, "miracle");
                require(
                    (Integer) field(item, "LJCEOGPOGBE") == selected
                        && (Integer) field(item, "DGGCFDKJDPO") == 0
                        && (Integer) field(item, "total_count") == expectedMiracleLifetime(),
                    "Wrong purchased curio lifetime notification");
                itemNotification = true;
              } else if (miracle && selected == 21
                  && detail.hasField(detail.getDescriptorForType().findFieldByName("OMFLCEBLLMI"))) {
                require(itemNotification, "Dice replacement preceded its acquisition");
                var replacement = (DynamicMessage) field(detail, "OMFLCEBLLMI");
                var item = (DynamicMessage) field(replacement, "HLCPFLOPGBM");
                diceReplacementId = (Integer) field(item, "LJCEOGPOGBE");
                require((Integer) field(replacement, "BJIBCOHEGIJ") == 21
                    && Set.of(4, 6).contains(diceReplacementId)
                    && (Integer) field(item, "DGGCFDKJDPO") == 0
                    && (Integer) field(item, "total_count") == (diceReplacementId == 4 ? 1 : 0)
                    && ++diceReplacementNotifications == 1,
                    "Incorrect or duplicate actual dice replacement");
              } else if (!miracle
                  && detail.hasField(
                      detail.getDescriptorForType().findFieldByName("IFOOEPKGGAG"))) {
                var buff = (DynamicMessage) field(detail, "IFOOEPKGGAG");
                require(
                    (Integer) field(buff, "buff_id") == selected
                        && (Integer) field(buff, "HAGDKKBAAKK") == 1,
                    "Wrong purchased blessing notification");
                itemNotification = true;
              }
            }
        }
        case "GetRogueInfoScRsp" -> {
          if (step == 10) {
            require((Integer) field(m, "retcode") == 0, "Post-exit query failed");
            var game = (DynamicMessage) field(m, "rogue_game_info");
            var current = (DynamicMessage) field(game, "CFDIKLBINHK");
            var map = (DynamicMessage) field(current, "COOOMDKGODF");
            require(
                (Integer) field(map, "ANHDBMOPGDC") == exitSite
                    && wallet(game) == expectedBalance
                    && ownedBlessings(game).equals(beforeExitBlessings),
                "Rest exit changed the wallet, rewards or destination");
            for (var raw : (List<?>) field(map, "LLNICHDDIAN")) {
              var row = (DynamicMessage) raw;
              if ((Integer) field(row, "MAJKFIMPOOF") == exitSite)
                require(
                    field(row, "CAGDNHCOCKM").toString().equals("RogueRoomStatus_Play"),
                    "The adjacent combat room was automatically completed");
            }
            result.put("restExitNoVictoryRewards", true);
            step = 6;
            send(c, "LeaveRogueCsReq", request("LeaveRogueCsReq"));
            break;
          }
          require(
              step == 5 && (Integer) field(m, "retcode") == 0, "Saved shop wallet query failed");
          require(
              wallet((DynamicMessage) field(m, "rogue_game_info")) == expectedBalance,
              "Merchant charged twice or lost wallet");
          require(
              resume || (currencyNotification && itemNotification),
              "Missing actual purchase notifications");
          if (miracle) {
            var game = (DynamicMessage) field(m, "rogue_game_info");
            var current = (DynamicMessage) field(game, "CFDIKLBINHK");
            var miracles = (DynamicMessage) field(current, "DNPCCFFFBHN");
            var owned = (DynamicMessage) field(miracles, "DNPCCFFFBHN");
            int ownedId = selected == 21 ? resume ? expectedDiceReplacementId : diceReplacementId : selected;
            var matching =
                ((List<?>) field(owned, "MAJGMMCOCIG"))
                    .stream()
                        .map(row -> (DynamicMessage) row)
                        .filter(row -> (Integer) field(row, "LJCEOGPOGBE") == ownedId)
                        .toList();
            require(matching.size() == 1, "Purchased curio ownership absent or duplicated");
            var item = matching.getFirst();
            require(
                (Integer) field(item, "DGGCFDKJDPO") == 0
                    && (Integer) field(item, "total_count")
                        == (selected == 21 ? ownedId == 4 ? 1 : 0 : expectedMiracleLifetime()),
                "Purchased curio lifetime did not restore");
            result.put("ownedMiracleUseCount", field(item, "DGGCFDKJDPO"));
            result.put("ownedMiracleTotalCount", field(item, "total_count"));
            if (selected == 21) {
              require(Set.of(4, 6).contains(ownedId)
                  && ((List<?>) field(owned, "MAJGMMCOCIG")).size() == 1
                  && (resume || diceReplacementNotifications == 1),
                  "Dice was retained, ownership changed or replacement was not notified");
              result.put("diceReplacementId", ownedId);
              result.put("diceSelfReplacementVerified", true);
              result.put("diceReplacementNotifications", diceReplacementNotifications);
            }
            if (immediateRewards()) {
              var buffs = ownedBlessings(game);
              require(
                  buffs.size() >= 1
                      && buffs.size() <= (selected == 5 ? 2 : 1)
                      && buffs.values().stream().allMatch(level -> level == 1),
                  "Box did not grant one or two distinct level-one blessings");
              if (selected != 5)
                require(
                    !expectedImmediateBuffs.isEmpty()
                        && expectedImmediateBuffs.containsAll(buffs.keySet()),
                    "Firewax granted a blessing from the wrong resource path");
              require(
                  buffs.equals(resume ? expectedBlessings : notifiedBlessings),
                  "Actual immediate blessings disagree with notifications or saved history");
              result.put("grantedBlessings", buffs);
            }
          }
          result.put("stock", stock);
          result.put("selected", selected);
          result.put("fragments", expectedBalance);
          result.put("merchantCurrencyNotification", currencyNotification);
          result.put(
              miracle ? "merchantMiracleNotification" : "merchantBlessingNotification",
              itemNotification);
          result.put("miracleMode", miracle);
          if (verifyRestExit) {
            beforeExitBlessings = ownedBlessings((DynamicMessage) field(m, "rogue_game_info"));
            step = 7;
            sendExit(c, true);
            break;
          }
          step = 6;
          send(c, "LeaveRogueCsReq", request("LeaveRogueCsReq"));
        }
        case "EnterRogueMapRoomScRsp" -> {
          if (step == 7) {
            require((Integer) field(m, "retcode") != 0, "Forged portal room accepted");
            result.put("restExitWrongDestinationRejected", true);
            step = 8;
            sendExit(c, false);
          } else if (step == 8) {
            require(
                (Integer) field(m, "retcode") == 0 && (Integer) field(m, "ANHDBMOPGDC") == exitSite,
                "Actual bound rest exit failed");
            var scene = (DynamicMessage) field(m, "scene");
            require(
                (Integer) field(scene, "entry_id") > 0, "Rest exit did not return an actual scene");
            result.put("actualRestExitEntered", true);
            result.put("restExitEntry", field(scene, "entry_id"));
            step = 9;
            sendExit(c, false);
          } else {
            require(step == 9 && (Integer) field(m, "retcode") != 0, "Repeated rest exit accepted");
            result.put("restExitRetryRejected", true);
            step = 10;
            send(c, "GetRogueInfoCsReq", request("GetRogueInfoCsReq"));
          }
        }
        case "LeaveRogueScRsp" -> {
          if (collectionTier() > 0 && !resume && step==115) {
            require((Integer) field(m,"retcode")==0,"Empty opportunity exploration could not be paused");quitForTier(c);break;
          }
          require(
              step == 6 && (Integer) field(m, "retcode") == 0, "Merchant exploration pause failed");
          if (verifyHandbook) {
            step = 81;
            queryHandbook(c);
            break;
          }
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
      var probe = new LoopbackRogueShopProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      probe.verifyHandbook = credentials.has("verifyHandbook") && credentials.get("verifyHandbook").getAsBoolean();
      probe.handbookOnly = credentials.has("handbookOnly") && credentials.get("handbookOnly").getAsBoolean();
      probe.clearRun = credentials.has("clearRun") && credentials.get("clearRun").getAsBoolean();
      probe.verifyHandbookReward = credentials.has("verifyHandbookReward") && credentials.get("verifyHandbookReward").getAsBoolean();
      probe.verifyBlessingQuests = credentials.has("verifyBlessingQuests") && credentials.get("verifyBlessingQuests").getAsBoolean();
      if (credentials.has("blessingTier")) {
        probe.blessingTier=credentials.get("blessingTier").getAsInt();probe.tierAmount=credentials.get("tierAmount").getAsInt();
        probe.initialFragments=credentials.get("initialFragments").getAsInt();
        probe.tierClaimed=credentials.has("tierClaimed") && credentials.get("tierClaimed").getAsBoolean();
        require(Set.of(3000101,3000130).contains(probe.blessingTier) && (probe.tierAmount==80 || probe.tierAmount==30)
            && probe.initialFragments==30000 && probe.verifyBlessingQuests,"Unexpected source tier fixture");
      }
      if (probe.verifyBlessingQuests) {
        var mapType=new com.google.gson.reflect.TypeToken<Map<Integer,Integer>>() {}.getType();
        var gson=new Gson();
        probe.blessingQuestGoals=gson.fromJson(credentials.get("blessingQuestGoals"),mapType);
        probe.blessingQuestPaths=gson.fromJson(credentials.get("blessingQuestPaths"),mapType);
        probe.blessingHandbookPaths=gson.fromJson(credentials.get("blessingHandbookPaths"),mapType);
        require(probe.blessingQuestGoals.size()==53 && probe.blessingQuestPaths.keySet().equals(probe.blessingQuestGoals.keySet()),"Original 53 tier definitions missing");
        if (probe.blessingTier > 0) probe.tierGoal=probe.blessingQuestGoals.get(probe.blessingTier);
      }
      if (credentials.has("miracleTier")) {
        var gson=new Gson();
        probe.verifyMiracleQuests=true;
        probe.miracleTier=credentials.get("miracleTier").getAsInt();
        probe.tierAmount=credentials.get("tierAmount").getAsInt();
        probe.tierGoal=probe.miracleTier==4092318 ? 3 : 40;
        probe.tierClaimed=credentials.has("tierClaimed") && credentials.get("tierClaimed").getAsBoolean();
        probe.blessingQuestGoals=Map.of(4092318,3,4092319,40);
        probe.blessingQuestPaths=Map.of(4092318,0,4092319,0);
        probe.handbookEligibleBuffs=gson.fromJson(credentials.get("handbookEligibleBuffs"),new com.google.gson.reflect.TypeToken<Set<Integer>>(){}.getType());
        probe.miraclePathBuffs=gson.fromJson(credentials.get("miraclePathBuffs"),new com.google.gson.reflect.TypeToken<Map<Integer,Set<Integer>>>(){}.getType());
        probe.allowedMiracles=gson.fromJson(credentials.get("allowedMiracles"),new com.google.gson.reflect.TypeToken<Set<Integer>>(){}.getType());
        require(Set.of(4092318,4092319).contains(probe.miracleTier) && probe.tierAmount==(probe.miracleTier==4092318 ? 5 : 10)
            && probe.allowedMiracles.size()==40 && !probe.allowedMiracles.contains(21)
            && !probe.verifyBlessingQuests && !probe.verifyHandbookReward && probe.verifyHandbook,"Unexpected original curio goal fixture");
      }
      if (credentials.has("claimedHandbookMiracles")) probe.claimedHandbookMiracles = new Gson().fromJson(credentials.get("claimedHandbookMiracles"),new com.google.gson.reflect.TypeToken<Set<Integer>>(){}.getType());
      if (probe.verifyHandbook) {
        var gson = new Gson();
        if (credentials.has("expectedHandbookBuffs")) probe.expectedHandbookBuffs = gson.fromJson(credentials.get("expectedHandbookBuffs"), new com.google.gson.reflect.TypeToken<Set<Integer>>() {}.getType());
        if (credentials.has("expectedHandbookMiracles")) probe.expectedHandbookMiracles = gson.fromJson(credentials.get("expectedHandbookMiracles"), new com.google.gson.reflect.TypeToken<Set<Integer>>() {}.getType());
        probe.handbookUnlocks = gson.fromJson(credentials.get("handbookUnlocks"), new com.google.gson.reflect.TypeToken<Map<Integer, Integer>>() {}.getType());
      }
      probe.expectedDiceReplacementId = credentials.has("expectedDiceReplacementId")
          ? credentials.get("expectedDiceReplacementId").getAsInt() : 0;
      probe.miracle = credentials.has("miracle") && credentials.get("miracle").getAsBoolean();
      probe.verifyRestExit =
          credentials.has("verifyRestExit") && credentials.get("verifyRestExit").getAsBoolean();
      if (credentials.has("selected")) probe.selected = credentials.get("selected").getAsInt();
      if (credentials.has("expectedImmediateBuffs"))
        probe.expectedImmediateBuffs =
            new Gson()
                .fromJson(
                    credentials.get("expectedImmediateBuffs"),
                    new com.google.gson.reflect.TypeToken<Set<Integer>>() {}.getType());
      if (probe.resume) {
        probe.expectedBalance = credentials.get("expectedFragments").getAsInt();
        probe.selected = credentials.get("selected").getAsInt();
        probe.stock =
            new Gson()
                .fromJson(
                    credentials.get("stock"),
                    new com.google.gson.reflect.TypeToken<Map<Integer, Integer>>() {}.getType());
        if (probe.miracle && probe.immediateRewards())
          probe.expectedBlessings =
              new Gson()
                  .fromJson(
                      credentials.get("grantedBlessings"),
                      new com.google.gson.reflect.TypeToken<Map<Integer, Integer>>() {}.getType());
      }
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
                    new IllegalStateException("Shop probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(probe.collectionTier() > 0 ? 300 : 20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Rogue shop probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
