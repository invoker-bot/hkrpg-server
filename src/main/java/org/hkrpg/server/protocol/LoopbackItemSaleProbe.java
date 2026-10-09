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

/** Original unique-item decomposition, daily points and normal H2 restart over actual UDP/KCP. */
public final class LoopbackItemSaleProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, mail, small, equipment, firstGold, lastGold;
  private static final int QUEST = 2100109;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    System.err.println("Sale request=" + name + ", step=" + step);
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

  private void sell(
      Ukcp c, boolean materials, boolean relic, int uid, boolean duplicate, int next) {
    var entry = request("ItemCost");
    set(entry, relic ? "relic_unique_id" : "equipment_unique_id", uid);
    var cost = request("ItemCostData");
    var list = cost.getDescriptorForType().findFieldByName("item_list");
    cost.addRepeatedField(list, entry.build());
    if (duplicate) cost.addRepeatedField(list, entry.build());
    var b = request("SellItemCsReq");
    set(b, "cost_data", cost.build());
    set(b, "HNLKAEBKKBL", materials);
    step = next;
    send(c, "SellItemCsReq", b);
  }

  private void claim(Ukcp c, int next) {
    var b = request("TakeQuestRewardCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), QUEST);
    step = next;
    send(c, "TakeQuestRewardCsReq", b);
  }

  private Map<Integer, Integer> items(DynamicMessage list) {
    var values = new TreeMap<Integer, Integer>();
    for (var value : (List<?>) field(list, "item_list")) {
      var item = (DynamicMessage) value;
      values.merge(number(item, "item_id"), number(item, "num"), Math::addExact);
    }
    return values;
  }

  private void points(DynamicMessage m, int expected) {
    require(number(m, "daily_active_point") == expected, "Wrong actual activity points");
    require(
        field(m, "daily_active_quest_id_list").equals(List.of(QUEST)),
        "Wrong isolated original daily selection");
    result.put("dailyPoints", expected);
  }

  private void state(DynamicMessage m, int progress, int status) {
    var rows =
        ((List<?>) field(m, "quest_list"))
            .stream().map(v -> (DynamicMessage) v).filter(v -> number(v, "id") == QUEST).toList();
    require(rows.size() == 1, "Original relic daily missing");
    var row = rows.getFirst();
    require(
        number(row, "progress") == progress
            && ((EnumValueDescriptor) field(row, "status")).getNumber() == status,
        "Wrong daily state at " + step);
    result.put("dailyProgress", progress);
    result.put("dailyQuestStatus", status);
  }

  private void bag(
      DynamicMessage m, int gold, int cones, int remains, int relicMaterial, int coneMaterial) {
    var relics = new TreeMap<Integer, List<DynamicMessage>>();
    for (var value : (List<?>) field(m, "relic_list")) {
      var item = (DynamicMessage) value;
      relics.computeIfAbsent(number(item, "tid"), k -> new ArrayList<>()).add(item);
    }
    require(
        relics.keySet().equals(gold == 0 ? Set.of(31011) : Set.of(31011, 61011)),
        "Wrong relic source identities");
    require(
        relics.get(31011).size() == 1 && (gold == 0 || relics.get(61011).size() == gold),
        "Wrong actual unique relic removal");
    small = number(relics.get(31011).getFirst(), "unique_id");
    if (gold == 2) {
      firstGold = number(relics.get(61011).get(0), "unique_id");
      lastGold = number(relics.get(61011).get(1), "unique_id");
      require(firstGold != lastGold, "Duplicate native relic UID");
    } else if (gold == 1)
      require(number(relics.get(61011).getFirst(), "unique_id") == lastGold, "Wrong relic removed");
    var lightCones =
        ((List<?>) field(m, "equipment_list")).stream().map(v -> (DynamicMessage) v).toList();
    require(
        lightCones.size() == cones && lightCones.stream().allMatch(v -> number(v, "tid") == 20000),
        "Wrong light cone removal");
    if (cones == 1) equipment = number(lightCones.getFirst(), "unique_id");
    var stocks = new TreeMap<Integer, Integer>();
    for (var value : (List<?>) field(m, "material_list")) {
      var item = (DynamicMessage) value;
      require(
          stocks.put(number(item, "tid"), number(item, "num")) == null,
          "Duplicate actual material pile");
    }
    require(
        stocks.getOrDefault(235, 0) == remains
            && stocks.getOrDefault(232, 0) == relicMaterial
            && stocks.getOrDefault(221, 0) == coneMaterial,
        "Wrong actual return piles at " + step);
    require(!stocks.containsKey(23), "Virtual activity points became an inventory item");
    result.put("remainingRelics", 1 + gold);
    result.put("remainingLightCones", cones);
    result.put("remains", remains);
    result.put("relicMaterial", relicMaterial);
    result.put("coneMaterial", coneMaterial);
  }

  private void done() {
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    result.put("quest", QUEST);
    result.put("savedStateRestored", resume);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated sale frame");
      var name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetDailyActiveInfoScRsp",
                  "GetQuestDataScRsp",
                  "GetMailScRsp",
                  "TakeMailAttachmentScRsp",
                  "GetBagScRsp",
                  "SellItemScRsp",
                  "TakeQuestRewardScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown sale response fields");
      System.err.println("Sale response=" + name + ", step=" + step);
      boolean rejected =
          name.equals("SellItemScRsp") && Set.of(6, 8, 18, 31).contains(step)
              || name.equals("TakeQuestRewardScRsp") && Set.of(23, 34).contains(step);
      require(
          rejected ? number(m, "retcode") != 0 : number(m, "retcode") == 0,
          "Wrong response code at " + step + " " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" ->
            send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" -> query(c, "GetDailyActiveInfoCsReq", 1);
        case "GetDailyActiveInfoScRsp" -> {
          points(m, resume || step >= 22 ? 100 : 0);
          if (step == 1) query(c, "GetQuestDataCsReq", 2);
          else if (step == 22) claim(c, 23);
          else if (step == 25 || step == 36) done();
          else throw new IllegalStateException("Unexpected point step " + step);
        }
        case "GetQuestDataScRsp" -> {
          switch (step) {
            case 2 -> {
              state(m, resume ? 1 : 0, resume ? 3 : 1);
              result.put("nativeOriginalDailySelected", true);
              query(c, resume ? "GetBagCsReq" : "GetMailCsReq", resume ? 30 : 3);
            }
            case 11 -> {
              state(m, 0, 1);
              result.put("nativeLightConeDoesNotCompleteRelicDaily", true);
              query(c, "GetBagCsReq", 12);
            }
            case 14 -> {
              state(m, 1, 2);
              result.put("nativeActualRelicCompletesDaily", true);
              query(c, "GetBagCsReq", 15);
            }
            case 21 -> {
              state(m, 1, 3);
              query(c, "GetDailyActiveInfoCsReq", 22);
            }
            case 24 -> {
              state(m, 1, 3);
              query(c, "GetDailyActiveInfoCsReq", 25);
            }
            case 33 -> {
              state(m, 1, 3);
              claim(c, 34);
            }
            case 35 -> {
              state(m, 1, 3);
              query(c, "GetDailyActiveInfoCsReq", 36);
            }
            default -> {} // Debit/grant/claim can publish extra snapshots; explicit queries prove
                          // the final states.
          }
        }
        case "GetMailScRsp" -> {
          var matches =
              ((List<?>) field(m, "mail_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> field(v, "title").equals("Decomposition stock fixture"))
                      .toList();
          require(matches.size() == 1, "No unique stock mail");
          mail = number(matches.getFirst(), "id");
          var b = request("TakeMailAttachmentCsReq");
          b.addRepeatedField(b.getDescriptorForType().findFieldByName("mail_id_list"), mail);
          step = 4;
          send(c, "TakeMailAttachmentCsReq", b);
        }
        case "TakeMailAttachmentScRsp" -> {
          require(
              field(m, "succ_mail_id_list").equals(List.of(mail)),
              "Stock mail not actually claimed");
          require(
              items((DynamicMessage) field(m, "attachment"))
                  .equals(Map.of(31011, 1, 61011, 2, 20000, 1)),
              "Wrong actual stock delivery");
          result.put("nativeActualUniqueStock", true);
          query(c, "GetBagCsReq", 5);
        }
        case "GetBagScRsp" -> {
          switch (step) {
            case 5 -> {
              bag(m, 2, 1, 0, 0, 0);
              sell(c, true, true, small, true, 6);
            }
            case 7 -> {
              bag(m, 2, 1, 0, 0, 0);
              result.put("nativeDuplicateUniqueIdNoDebit", true);
              sell(c, true, true, equipment, false, 8);
            }
            case 9 -> {
              bag(m, 2, 1, 0, 0, 0);
              result.put("nativeWrongOneofNoDebit", true);
              sell(c, true, false, equipment, false, 10);
            }
            case 12 -> {
              bag(m, 2, 0, 0, 0, 1);
              result.put("nativeActualLightConeDebitAndReturn", true);
              sell(c, false, true, firstGold, false, 13);
            }
            case 15 -> {
              bag(m, 1, 0, 10, 0, 1);
              result.put("nativeActualRemainsDebitAndReturn", true);
              sell(c, true, true, lastGold, false, 16);
            }
            case 17 -> {
              bag(m, 0, 0, 10, 3, 1);
              result.put("nativeActualMaterialDebitAndReturn", true);
              sell(c, true, true, lastGold, false, 18);
            }
            case 19 -> {
              bag(m, 0, 0, 10, 3, 1);
              result.put("nativeRepeatedSaleNoExtraReturn", true);
              claim(c, 20);
            }
            case 30 -> {
              bag(m, 0, 0, 10, 3, 1);
              sell(c, true, false, small, false, 31);
            }
            case 32 -> {
              bag(m, 0, 0, 10, 3, 1);
              result.put("nativeSavedWrongOneofNoDebit", true);
              query(c, "GetQuestDataCsReq", 33);
            }
            default -> throw new IllegalStateException("Unexpected bag step " + step);
          }
        }
        case "SellItemScRsp" -> {
          var expected =
              switch (step) {
                case 10 -> Map.of(221, 1);
                case 13 -> Map.of(235, 10);
                case 16 -> Map.of(232, 3);
                default -> Map.<Integer, Integer>of();
              };
          require(
              items((DynamicMessage) field(m, "return_item_list")).equals(expected),
              "Wrong native decomposition return");
          switch (step) {
            case 6 -> query(c, "GetBagCsReq", 7);
            case 8 -> query(c, "GetBagCsReq", 9);
            case 10 -> query(c, "GetQuestDataCsReq", 11);
            case 13 -> query(c, "GetQuestDataCsReq", 14);
            case 16 -> query(c, "GetBagCsReq", 17);
            case 18 -> query(c, "GetBagCsReq", 19);
            case 31 -> query(c, "GetBagCsReq", 32);
            default -> throw new IllegalStateException("Unexpected sale step " + step);
          }
        }
        case "TakeQuestRewardScRsp" -> {
          boolean accepted = step == 20;
          require(
              field(m, "quest_id_list").equals(List.of(QUEST))
                  && field(m, "succ_quest_id_list").equals(accepted ? List.of(QUEST) : List.of()),
              "Wrong original daily claim identity");
          require(
              items((DynamicMessage) field(m, "reward")).isEmpty(),
              "Virtual points fabricated material rewards");
          result.put(
              accepted
                  ? "nativeHundredVirtualPointsOnce"
                  : resume ? "nativeSavedDuplicateClaimRejected" : "nativeDuplicateClaimRejected",
              true);
          query(c, "GetQuestDataCsReq", accepted ? 21 : resume ? 35 : 24);
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackItemSaleProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
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
                    new IllegalStateException("Sale probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(p.completed.get(25, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Item sale probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
