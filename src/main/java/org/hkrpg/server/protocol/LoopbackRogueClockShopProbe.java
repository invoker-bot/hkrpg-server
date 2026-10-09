package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
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

/** Acquire clock 70 before opening a different original merchant; isolated room/wallet only. */
public final class LoopbackRogueClockShopProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private final Set<Integer> notifiedBuffs = new TreeSet<>();
  private Map<Integer, Integer> bases, stock = new TreeMap<>();
  private Map<Integer, Integer> priorStock = new TreeMap<>(), miracleStock = new TreeMap<>();
  private final Map<Integer, Integer> merchantEntities = new TreeMap<>();
  private final Set<Integer> miracleNotifications = new TreeSet<>();
  private Set<Integer> passiveMerchantGroups = Set.of();
  private boolean resume, clockNotified, conversion, entryRevivalQuoteSeen;
  private int step, miracleEntity, buffEntity, balance, currencyCount, selectedMiracle = 70;
  private int purchaseIndex, replacements, revivalPriceNotifications, revivalBase;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static DynamicMessage child(DynamicMessage m, String name) {
    return (DynamicMessage) field(m, name);
  }

  private static List<DynamicMessage> list(DynamicMessage m, String name) {
    return ((List<?>) field(m, name)).stream().map(v -> (DynamicMessage) v).toList();
  }

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private static void packet(Ukcp c, int command, byte[] body) {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], body));
    try {
      c.write(bytes);
    } finally {
      bytes.release();
    }
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    packet(c, protocol.clientCommand(name), b.build().toByteArray());
  }

  private void query(Ukcp c, String name, int next) {
    step = next;
    send(c, name, request(name));
  }

  private void shop(Ukcp c, boolean miracle, boolean buy, int next) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt32(miracle ? 12 : buy ? 2 : 15, miracle ? miracleEntity : buffEntity);
    if (buy && miracle) out.writeUInt32(6, selectedMiracle);
    else if (buy) {
      var packed = new java.io.ByteArrayOutputStream();
      var ids = CodedOutputStream.newInstance(packed);
      for (int id : stock.keySet()) ids.writeUInt32NoTag(id);
      ids.flush();
      out.writeByteArray(6, packed.toByteArray());
    }
    out.flush();
    step = next;
    packet(c, miracle ? buy ? 5656 : 5675 : buy ? 5615 : 5616, bytes.toByteArray());
  }

  private int price(DynamicMessage row) {
    var cost = child(row, "cost_data");
    var costs = list(cost, "item_list");
    require(costs.size() == 1 && cost.equals(field(row, "KINNMAGDDJJ")), "Changed cost snapshot");
    var pile = child(costs.getFirst(), "pile_item");
    require(number(pile, "item_id") == 31, "Wrong merchant currency");
    return number(pile, "item_num");
  }

  private List<DynamicMessage> rows(DynamicMessage response, boolean miracle) {
    return list(
        child(response, miracle ? "DNPCCFFFBHN" : "HABJHGPFGEB"),
        miracle ? "MAJGMMCOCIG" : "buff_list");
  }

  private void game(DynamicMessage response, boolean purchased) {
    require(number(response, "retcode") == 0, "Rogue query failed");
    var run = child(child(response, "rogue_game_info"), "CFDIKLBINHK");
    require(
        number(child(run, "DHEKLJDBNNP"), "rogue_money") == balance,
        "Wallet changed or duplicate payment");
    var owned = list(child(child(run, "DNPCCFFFBHN"), "DNPCCFFFBHN"), "MAJGMMCOCIG");
    require(
        owned.size() == 1 && number(owned.getFirst(), "LJCEOGPOGBE") == 70,
        "Clock ownership changed");
    var buffs = new TreeMap<Integer, Integer>();
    for (var buff : list(child(run, "HABJHGPFGEB"), "CHCMEKAFKAF"))
      require(
          buffs.put(number(buff, "buff_id"), number(buff, "level")) == null,
          "Duplicate owned blessing");
    require(
        purchased
            ? buffs.keySet().equals(stock.keySet()) && buffs.values().stream().allMatch(v -> v == 1)
            : buffs.isEmpty(),
        "Blessing ownership mismatch");
    if (!resume && purchased)
      require(
          currencyCount == 2 && clockNotified && notifiedBuffs.equals(stock.keySet()),
          "Missing saved purchase notifications");
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated clock shop frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "GetRogueShopMiracleInfoScRsp",
                  "BuyRogueShopMiracleScRsp",
                  "GetRogueShopBuffInfoScRsp",
                  "BuyRogueShopBuffScRsp",
                  "GetRogueInfoScRsp",
                  "LeaveRogueScRsp",
                  "SyncRogueReviveInfoScNotify",
                  "SyncRogueVirtualItemInfoScNotify",
                  "SyncRogueCommonActionResultScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown fields in " + name);
      if (conversion) {
        receiveConversion(name, m, c);
        continue;
      }
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
          var req = request(resume ? "EnterRogueCsReq" : "StartRogueCsReq");
          set(req, "area_id", 100);
          if (!resume)
            req.addRepeatedField(
                req.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
                emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
          send(c, resume ? "EnterRogueCsReq" : "StartRogueCsReq", req);
        }
        case "StartRogueScRsp", "EnterRogueScRsp" -> {
          require(step == 0 && number(m, "retcode") == 0, "Entry rejected");
          var current = child(child(m, "rogue_game_info"), "CFDIKLBINHK");
          require(
              number(child(current, "DHEKLJDBNNP"), "rogue_money") == balance,
              "Initial/saved wallet mismatch");
          var scene = child(m, "scene");
          require(number(scene, "entry_id") == 8020101, "Wrong original merchant floor");
          for (var group : list(scene, "entity_group_list"))
            for (var row : list(group, "entity_list")) {
              if (!row.hasField(row.getDescriptorForType().findFieldByName("npc"))) continue;
              int groupId = number(row, "group_id"), npc = number(child(row, "npc"), "npc_id");
              require(number(row, "inst_id") == 400001, "Changed original merchant instance");
              if (groupId == 35 && npc == 1013) {
                require(miracleEntity == 0, "Duplicate miracle merchant");
                miracleEntity = number(row, "entity_id");
              } else if (groupId == 117 && npc == 1007) {
                require(buffEntity == 0, "Duplicate buff merchant");
                buffEntity = number(row, "entity_id");
              } else if (passiveMerchantGroups.contains(groupId)
                  && (groupId == 101 && npc == 1013 || groupId == 118 && npc == 1007)) {
                require(
                    merchantEntities.put(groupId, number(row, "entity_id")) == null,
                    "Duplicate passive merchant");
              } else throw new IllegalStateException("Unexpected merchant identity");
            }
          require(miracleEntity > 0 && buffEntity > 0, "Missing original merchants");
          require(
              merchantEntities.keySet().equals(passiveMerchantGroups),
              "Missing configured passive merchant");
          result.put("nativeOriginalMerchantsPresent", true);
          shop(c, true, false, 1);
        }
        case "GetRogueShopMiracleInfoScRsp" -> {
          require(
              (step == 1 || step == 11) && number(m, "retcode") == 0, "Miracle stock query failed");
          var quotes = rows(m, true);
          require(quotes.size() == 3, "Missing clock catalog");
          var ids = new TreeSet<Integer>();
          for (var row : quotes) {
            require(price(row) == 200, "Frozen pre-clock quote changed");
            ids.add(number(row, "LJCEOGPOGBE"));
          }
          require(ids.equals(Set.of(2, 12, 70)), "Changed clock catalog");
          var clock =
              quotes.stream().filter(r -> number(r, "LJCEOGPOGBE") == 70).findFirst().orElseThrow();
          boolean sold = resume || step == 11;
          require(
              (Boolean) field(clock, "GCIHGKCJOGB") == sold
                  && (Boolean) field(clock, "BELPGDAGFIH") == sold,
              "Clock stock ownership mismatch");
          if (step == 11) {
            result.put("preClockQuoteRemainsFrozen", true);
            query(c, "LeaveRogueCsReq", 12);
          } else if (resume) shop(c, true, true, 4);
          else {
            balance -= 200;
            shop(c, true, true, 2);
          }
        }
        case "BuyRogueShopMiracleScRsp" -> {
          if (step == 2) {
            require(number(m, "retcode") == 0, "Clock purchase failed");
            result.put("nativeClockAcquired", true);
            shop(c, true, true, 3);
          } else {
            require(
                (step == 3 || step == 4) && number(m, "retcode") != 0, "Clock duplicate accepted");
            result.put("clockPurchaseRetryRejected", true);
            query(c, "GetRogueInfoCsReq", 5);
          }
        }
        case "GetRogueInfoScRsp" -> {
          require(step == 5 || step == 10, "Unexpected rogue query");
          game(m, resume || step == 10);
          if (step == 5) shop(c, false, false, 6);
          else {
            result.put("nativePurchaseAndWalletVerified", true);
            shop(c, true, false, 11);
          }
        }
        case "GetRogueShopBuffInfoScRsp" -> {
          require(step == 6 && number(m, "retcode") == 0, "Buff shop query failed");
          var quotes = rows(m, false);
          require(!quotes.isEmpty() && quotes.size() <= 3, "Missing buff stock");
          var current = new TreeMap<Integer, Integer>();
          for (var row : quotes) {
            int id = number(row, "buff_id"), base = bases.getOrDefault(id, -1);
            require(
                base > 0 && price(row) == base + base / 4,
                "Clock surcharge disagrees with original buff category");
            require(
                number(row, "JBGENFIKGGF") == 1 && current.put(id, price(row)) == null,
                "Invalid buff stock");
            require(
                (Boolean) field(row, "GCIHGKCJOGB") == resume
                    && (Boolean) field(row, "BELPGDAGFIH") == resume,
                "Saved buff ownership mismatch");
          }
          if (resume) require(current.equals(stock), "Saved quotes changed");
          else {
            stock = current;
            balance -= stock.values().stream().mapToInt(Integer::intValue).sum();
          }
          result.put("clockSurchargedStockVerified", true);
          shop(c, false, true, 7);
        }
        case "BuyRogueShopBuffScRsp" -> {
          if (step == 7 && !resume) {
            require(number(m, "retcode") == 0, "Surcharged batch purchase failed");
            for (var row : rows(m, false))
              require(
                  (Boolean) field(row, "GCIHGKCJOGB") && (Boolean) field(row, "BELPGDAGFIH"),
                  "Bought stock unavailable/ownership mismatch");
            result.put("nativeSurchargedBatchPurchased", true);
            shop(c, false, true, 8);
          } else {
            require(
                (step == 7 && resume || step == 8) && number(m, "retcode") != 0,
                "Repeated buff purchase accepted");
            result.put("surchargedPurchaseRetryRejected", true);
            query(c, "GetRogueInfoCsReq", 10);
          }
        }
        case "SyncRogueVirtualItemInfoScNotify" -> {
          if (!resume && (step == 2 || step == 7)) {
            require(
                number(child(m, "NIKDPODBIEF"), "LKHAIEIDAJL") == balance,
                "Wrong currency notification");
            currencyCount++;
          }
        }
        case "SyncRogueCommonActionResultScNotify" -> {
          if (!resume)
            for (var action : list(m, "CLGGKLNPLCK")) {
              var detail = child(action, "detail");
              if (step == 2
                  && detail.hasField(
                      detail.getDescriptorForType().findFieldByName("GPJJMPCMAAA"))) {
                var item = child(child(detail, "GPJJMPCMAAA"), "miracle");
                require(
                    number(item, "LJCEOGPOGBE") == 70
                        && number(item, "DGGCFDKJDPO") == 0
                        && number(item, "total_count") == 0,
                    "Wrong clock acquisition notification");
                clockNotified = true;
              } else if (step == 7
                  && detail.hasField(
                      detail.getDescriptorForType().findFieldByName("IFOOEPKGGAG"))) {
                var buff = child(detail, "IFOOEPKGGAG");
                require(
                    number(buff, "HAGDKKBAAKK") == 1 && notifiedBuffs.add(number(buff, "buff_id")),
                    "Duplicate/invalid buff notification");
              }
            }
        }
        case "LeaveRogueScRsp" -> {
          require(step == 12 && number(m, "retcode") == 0, "Pause failed");
          result.put("stock", stock);
          result.put("fragments", balance);
          result.put("surcharge", stock.keySet().stream().mapToInt(id -> bases.get(id) / 4).sum());
          result.put("currencyNotifications", currencyCount);
          result.put("savedClockStockWalletAndBuffsRestored", resume);
          result.put("passed", true);
          result.put("clockConversionNetworkVerified", false);
          result.put("clientGameplayVerified", false);
          completed.complete(result);
        }
      }
    }
  }

  private void convertedGame(DynamicMessage response, boolean converted, boolean allBuffs) {
    require(number(response, "retcode") == 0, "Conversion state query failed");
    var run = child(child(response, "rogue_game_info"), "CFDIKLBINHK");
    require(
        number(child(run, "DHEKLJDBNNP"), "rogue_money") == balance,
        "Conversion wallet or one-time reward changed");
    var ids = new TreeSet<Integer>();
    for (var row : list(child(child(run, "DNPCCFFFBHN"), "DNPCCFFFBHN"), "MAJGMMCOCIG")) {
      int id = number(row, "LJCEOGPOGBE");
      require(
          ids.add(id) && number(row, "DGGCFDKJDPO") == 0 && number(row, "total_count") == 0,
          "Invalid conversion miracle row");
    }
    var expected = new TreeSet<Integer>();
    expected.add(converted ? 9 : 70);
    if (step != 0) expected.addAll(miracleStock.keySet());
    require(ids.equals(expected), "Clock replacement or acquired miracles changed");
    var expectedBuffs = new TreeSet<>(priorStock.keySet());
    if (allBuffs) expectedBuffs.addAll(stock.keySet());
    var actualBuffs = new TreeSet<Integer>();
    for (var row : list(child(run, "HABJHGPFGEB"), "CHCMEKAFKAF"))
      require(
          actualBuffs.add(number(row, "buff_id")) && number(row, "level") == 1,
          "Invalid saved conversion blessing");
    require(actualBuffs.equals(expectedBuffs), "Conversion changed blessing ownership");
  }

  private void nextMiracle(Ukcp c) throws Exception {
    selectedMiracle = new ArrayList<>(miracleStock.keySet()).get(purchaseIndex);
    if (!resume) balance -= miracleStock.get(selectedMiracle);
    shop(c, true, true, resume ? 22 : 21);
  }

  /** Continue an actual saved clock acquisition through expense and replacement requests. */
  private void receiveConversion(String name, DynamicMessage m, Ukcp c) throws Exception {
    switch (name) {
      case "PlayerGetTokenScRsp" -> {
        require(number(m, "retcode") == 0, "Conversion token rejected");
        result.put("uid", field(m, "uid"));
        send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
      }
      case "PlayerLoginScRsp" -> {
        require(number(m, "retcode") == 0, "Conversion login rejected");
        send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
      }
      case "PlayerLoginFinishScRsp" -> {
        require(number(m, "retcode") == 0, "Conversion login finish rejected");
        var req = request("EnterRogueCsReq");
        set(req, "area_id", 100);
        send(c, "EnterRogueCsReq", req);
      }
      case "EnterRogueScRsp" -> {
        require(step == 0, "Duplicate conversion entry");
        // An initial continuation owns only 70; restored continuations also own saved purchases.
        if (resume) step = 19;
        convertedGame(m, resume, resume);
        for (var group : list(child(m, "scene"), "entity_group_list"))
          for (var row : list(group, "entity_list")) {
            if (!row.hasField(row.getDescriptorForType().findFieldByName("npc"))) continue;
            int id = number(row, "group_id"), npc = number(child(row, "npc"), "npc_id");
            require(
                number(row, "inst_id") == 400001
                    && (Set.of(35, 101).contains(id) && npc == 1013
                        || Set.of(117, 118).contains(id) && npc == 1007)
                    && merchantEntities.put(id, number(row, "entity_id")) == null,
                "Changed conversion merchant placement");
          }
        require(
            merchantEntities.keySet().equals(Set.of(35, 101, 117, 118)),
            "Missing conversion merchant");
        miracleEntity = merchantEntities.get(101);
        buffEntity = merchantEntities.get(118);
        result.put("nativeOriginalConversionMerchantsPresent", true);
        shop(c, true, false, 20);
      }
      case "GetRogueShopMiracleInfoScRsp" -> {
        require(number(m, "retcode") == 0, "Conversion miracle quote rejected");
        var quotes = rows(m, true);
        require(quotes.size() == 3, "Missing conversion miracle stock");
        if (step == 20 || step == 30) {
          var current = new TreeMap<Integer, Integer>();
          for (var row : quotes) {
            int id = number(row, "LJCEOGPOGBE");
            boolean sold = resume || step == 30;
            require(
                Set.of(2, 12, 13, 19, 66).contains(id)
                    && price(row) == 250
                    && current.put(id, price(row)) == null
                    && (Boolean) field(row, "GCIHGKCJOGB") == sold
                    && (Boolean) field(row, "BELPGDAGFIH") == sold,
                "Changed frozen post-clock miracle quote");
          }
          if (resume || step == 30)
            require(current.equals(miracleStock), "Saved conversion miracle stock changed");
          else miracleStock = current;
          if (step == 20) nextMiracle(c);
          else {
            result.put("postClockQuotesRemainFrozenAfterConversion", true);
            buffEntity = merchantEntities.get(117);
            shop(c, false, false, 31);
          }
        } else {
          require(step == 28, "Unexpected conversion miracle quote");
          var ids = new TreeSet<Integer>();
          for (var row : quotes) {
            int id = number(row, "LJCEOGPOGBE");
            boolean owned = id != 70 && miracleStock.containsKey(id);
            require(
                price(row) == 200
                    && ids.add(id)
                    && (Boolean) field(row, "GCIHGKCJOGB") == (id == 70 || owned)
                    && (Boolean) field(row, "BELPGDAGFIH") == owned,
                "Pre-clock stock changed after replacement");
          }
          require(ids.equals(Set.of(2, 12, 70)), "Changed pre-clock conversion catalog");
          selectedMiracle = 70;
          shop(c, true, true, 29);
        }
      }
      case "BuyRogueShopMiracleScRsp" -> {
        if (step == 21) {
          require(number(m, "retcode") == 0, "Surcharged miracle purchase failed");
          shop(c, true, true, 22);
        } else if (step == 22) {
          require(number(m, "retcode") != 0, "Conversion miracle retry succeeded");
          if (++purchaseIndex < miracleStock.size()) nextMiracle(c);
          else query(c, "GetRogueInfoCsReq", 23);
        } else {
          require(
              step == 29 && number(m, "retcode") != 0,
              "Broken clock permitted reacquisition from frozen stock");
          result.put("convertedClockReacquisitionRejected", true);
          miracleEntity = merchantEntities.get(101);
          shop(c, true, false, 30);
        }
      }
      case "GetRogueInfoScRsp" -> {
        require(Set.of(23, 27, 33).contains(step), "Unexpected conversion state query");
        convertedGame(m, resume || step != 23, resume || step != 23);
        if (step == 23) shop(c, false, false, 24);
        else if (step == 27) {
          if (!resume)
            require(
                replacements == 1
                    && revivalPriceNotifications == 1
                    && currencyCount == 4
                    && miracleNotifications.equals(miracleStock.keySet())
                    && notifiedBuffs.equals(stock.keySet()),
                "Missing or repeated conversion notification");
          result.put("nativeConvertedOwnershipAndRewardVerified", true);
          miracleEntity = merchantEntities.get(35);
          shop(c, true, false, 28);
        } else {
          require(
              resume
                  ? currencyCount == 0 && replacements == 0
                  : currencyCount == 4 && replacements == 1,
              "Retry produced a second replacement or reward");
          query(c, "LeaveRogueCsReq", 34);
        }
      }
      case "GetRogueShopBuffInfoScRsp" -> {
        require(
            number(m, "retcode") == 0 && Set.of(24, 31, 32).contains(step),
            "Conversion blessing quote failed");
        var current = new TreeMap<Integer, Integer>();
        for (var row : rows(m, false)) {
          int id = number(row, "buff_id");
          boolean owned = resume || step != 24;
          require(
              bases.getOrDefault(id, -1) == 250
                  && price(row) == 312
                  && current.put(id, 312) == null
                  && number(row, "JBGENFIKGGF") == 1
                  && (Boolean) field(row, "GCIHGKCJOGB") == owned
                  && (Boolean) field(row, "BELPGDAGFIH") == owned,
              "Changed frozen conversion blessing quote");
        }
        require(current.size() == 3, "Incomplete conversion blessing stock");
        if (step == 31) {
          require(current.equals(priorStock), "Original blessing merchant lost stock");
          buffEntity = merchantEntities.get(118);
          shop(c, false, false, 32);
        } else if (step == 32) {
          require(current.equals(stock), "New blessing merchant lost stock");
          query(c, "GetRogueInfoCsReq", 33);
        } else {
          if (resume) require(current.equals(stock), "Saved conversion blessing stock changed");
          else {
            stock = current;
            require(
                Collections.disjoint(stock.keySet(), priorStock.keySet()),
                "Conversion merchant offered an owned blessing");
            balance -= stock.values().stream().mapToInt(Integer::intValue).sum();
            balance += 999;
          }
          shop(c, false, true, resume ? 26 : 25);
        }
      }
      case "BuyRogueShopBuffScRsp" -> {
        if (step == 25) {
          require(number(m, "retcode") == 0, "Clock-breaking blessing batch failed");
          result.put("nativeExpenseTriggeredClockConversion", true);
          shop(c, false, true, 26);
        } else {
          require(
              step == 26 && number(m, "retcode") != 0, "Clock conversion batch retry succeeded");
          query(c, "GetRogueInfoCsReq", 27);
        }
      }
      case "SyncRogueVirtualItemInfoScNotify" -> {
        if (step >= 20) {
          require(
              !resume
                  && (step == 21 || step == 25)
                  && number(child(m, "NIKDPODBIEF"), "LKHAIEIDAJL") == balance,
              "Duplicate or incorrect conversion currency notification");
          currencyCount++;
        }
      }
      case "SyncRogueCommonActionResultScNotify" -> {
        if (step >= 20)
          for (var action : list(m, "CLGGKLNPLCK")) {
            require(!resume, "Restored conversion replayed an action");
            var detail = child(action, "detail");
            if (detail.hasField(detail.getDescriptorForType().findFieldByName("OMFLCEBLLMI"))) {
              var replace = child(detail, "OMFLCEBLLMI");
              var item = child(replace, "HLCPFLOPGBM");
              require(
                  step == 25
                      && number(replace, "BJIBCOHEGIJ") == 70
                      && number(item, "LJCEOGPOGBE") == 9
                      && number(item, "DGGCFDKJDPO") == 0
                      && number(item, "total_count") == 0,
                  "Wrong native replacement payload");
              replacements++;
            } else if (detail.hasField(
                detail.getDescriptorForType().findFieldByName("GPJJMPCMAAA"))) {
              var item = child(child(detail, "GPJJMPCMAAA"), "miracle");
              require(
                  step == 21
                      && number(item, "LJCEOGPOGBE") == selectedMiracle
                      && miracleNotifications.add(selectedMiracle),
                  "Repeated miracle acquisition action");
            } else if (detail.hasField(
                detail.getDescriptorForType().findFieldByName("IFOOEPKGGAG"))) {
              var buff = child(detail, "IFOOEPKGGAG");
              require(
                  step == 25
                      && number(buff, "HAGDKKBAAKK") == 1
                      && notifiedBuffs.add(number(buff, "buff_id")),
                  "Repeated blessing purchase action");
            } else throw new IllegalStateException("Unexpected conversion action payload");
          }
      }
      case "SyncRogueReviveInfoScNotify" -> {
        if (step >= 20) {
          if (step == 20 && !entryRevivalQuoteSeen) {
            require(
                priceInfo(child(m, "MFEODNJNGDP"))
                    == (resume ? revivalBase : revivalBase + revivalBase / 4),
                "Wrong entry revival quote for saved clock ownership");
            entryRevivalQuoteSeen = true;
            break;
          }
          require(
              !resume && step == 25 && priceInfo(child(m, "MFEODNJNGDP")) == revivalBase,
              "Conversion did not restore base revival quote");
          revivalPriceNotifications++;
        }
      }
      case "LeaveRogueScRsp" -> {
        require(step == 34 && number(m, "retcode") == 0, "Conversion pause failed");
        result.put("stock", stock);
        result.put("miracleStock", miracleStock);
        result.put("priorStock", priorStock);
        result.put("fragments", balance);
        result.put("replacementNotifications", replacements);
        result.put("revivalPriceNotifications", revivalPriceNotifications);
        require(entryRevivalQuoteSeen, "Missing native entry revival quote");
        result.put("nativeEntryRevivalQuoteVerified", true);
        result.put("currencyNotifications", currencyCount);
        result.put("savedConvertedStockWalletAndBlessingsRestored", resume);
        result.put("passed", true);
        result.put("clockConversionNetworkVerified", true);
        result.put("officialThresholdComparisonVerified", false);
        result.put("clientGameplayVerified", false);
        completed.complete(result);
      }
    }
  }

  private int priceInfo(DynamicMessage info) {
    var costs = list(child(info, "LNIEFKBMIPD"), "item_list");
    require(costs.size() == 1, "Unexpected revival currencies");
    var pile = child(costs.getFirst(), "pile_item");
    require(number(pile, "item_id") == 31, "Wrong revival currency");
    return number(pile, "item_num");
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackRogueClockShopProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      p.conversion = credentials.has("conversion") && credentials.get("conversion").getAsBoolean();
      p.balance =
          credentials
              .get(p.resume || p.conversion ? "expectedFragments" : "initialFragments")
              .getAsInt();
      p.bases =
          new Gson()
              .fromJson(
                  credentials.get("basePrices"),
                  new TypeToken<Map<Integer, Integer>>() {}.getType());
      if (p.resume)
        p.stock =
            new Gson()
                .fromJson(
                    credentials.get("stock"), new TypeToken<Map<Integer, Integer>>() {}.getType());
      if (credentials.has("passiveMerchantGroups"))
        p.passiveMerchantGroups =
            new Gson()
                .fromJson(
                    credentials.get("passiveMerchantGroups"),
                    new TypeToken<Set<Integer>>() {}.getType());
      if (p.conversion) {
        p.priorStock =
            new Gson()
                .fromJson(
                    credentials.get("priorStock"),
                    new TypeToken<Map<Integer, Integer>>() {}.getType());
        p.revivalBase = credentials.get("revivalBase").getAsInt();
        if (p.resume)
          p.miracleStock =
              new Gson()
                  .fromJson(
                      credentials.get("miracleStock"),
                      new TypeToken<Map<Integer, Integer>>() {}.getType());
      }
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

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                p.receive(bytes, c);
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
                    new IllegalStateException("Clock shop disconnected"));
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
          "Clock shop probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
