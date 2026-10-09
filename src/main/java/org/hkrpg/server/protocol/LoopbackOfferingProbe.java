package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import kcp.highway.*;

/** Actual ID 12 source costs/rewards, real inventory and normal reconnect/H2 persistence. */
public final class LoopbackOfferingProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private final Map<Integer, Long> times = new TreeMap<>();
  private JsonObject input;
  private int step, notifications, credits, jade, outfitNotifications;
  private DynamicMessage basic;
  private static final List<Integer> LEVELS = IntStream.rangeClosed(1, 25).boxed().toList();
  private static final List<Integer> AVATAR_DICE = List.of(264101, 264201, 264301, 264518);

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private static void add(DynamicMessage.Builder b, String name, Object value) {
    b.addRepeatedField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    System.err.println("Offering request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c, String name) {
    send(c, name, request(name));
  }

  private void offering(Ukcp c) {
    var b = request("GetOfferingInfoCsReq");
    add(b, "offering_id_list", 12);
    send(c, "GetOfferingInfoCsReq", b);
  }

  private void submit(Ukcp c) {
    var b = request("SubmitOfferingItemCsReq");
    set(b, "offering_id", 12);
    send(c, "SubmitOfferingItemCsReq", b);
  }

  private void claim(Ukcp c, List<Integer> levels) {
    var b = request("TakeOfferingRewardCsReq");
    set(b, "offering_id", 12);
    levels.forEach(l -> add(b, "take_reward_level_list", l));
    send(c, "TakeOfferingRewardCsReq", b);
  }

  private void buy(Ukcp c) {
    var b = request("BuyShopGoodCsReq");
    set(b, "MCDNNFACGPE", 1);
    set(b, "KDMNGCLJDOM", 1201);
    send(c, "BuyShopGoodCsReq", b);
  }

  private void equip(Ukcp c, List<Integer> ids) {
    var child = request("CJLCPMDGIBO");
    ids.forEach(id -> add(child, "EAKOLIJOEPA", id));
    var b = request("SetPlayerOutfitCsReq");
    set(b, "DHKFCDAGHDM", child.build());
    send(c, "SetPlayerOutfitCsReq", b);
  }

  private List<?> outfits(DynamicMessage m) {
    return (List<?>) field((DynamicMessage) field(m, "DHKFCDAGHDM"), "EAKOLIJOEPA");
  }

  private boolean closed() {
    return input.get("mode").getAsString().equals("closed");
  }

  private boolean progression() {
    return input.has("progression") && input.get("progression").getAsBoolean();
  }

  private boolean fresh() {
    return input.get("mode").getAsString().equals("fresh");
  }

  private void upgrade(Ukcp c) {
    var b = request("DiceCombatUpgradeAvatarCsReq");
    set(b, "KOEOEHIMOEI", 264021);
    send(c, "DiceCombatUpgradeAvatarCsReq", b);
  }

  private void modify(Ukcp c, int die) {
    var b = request("DiceCombatModifyAvatarDiceCsReq");
    set(b, "avatar_id", 264021);
    set(b, "LLPCNBJIMCN", die);
    send(c, "DiceCombatModifyAvatarDiceCsReq", b);
  }

  private void progressionAvatar(DynamicMessage m, int level, int die) {
    var avatar = (DynamicMessage) field(m, "avatar");
    require(
        number(avatar, "dice_avatar_id") == 264021
            && number(avatar, "level") == level
            && field(avatar, "dice_id_list").equals(List.of(264101, 264201, 264301, die))
            && field(avatar, "unlock_time").equals(times.get(264021)),
        "Upgrade/modify native avatar mismatch");
  }

  private void progress(DynamicMessage info, boolean maximum, boolean claimed) {
    require(
        number(info, "offering_id") == 12
            && number(info, "offering_level") == (maximum ? 25 : 0)
            && number(info, "level_exp") == 0
            && number(info, "total_exp") == (maximum ? 2000 : 0),
        "Source offering progress/cost changed");
    require(
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(info, "offering_state"))
                .getNumber()
            == (closed() ? 1 : 2),
        "Wrong offering availability");
    require(
        field(info, "has_taken_reward_id_list").equals(claimed ? LEVELS : List.of()),
        "Query fabricated/lost reward levels");
    if (maximum) {
      result.put("offeringLevel", 25);
      result.put("totalExp", 2000);
      result.put("claimedLevels", claimed ? LEVELS : List.of());
    }
  }

  private Map<Integer, Integer> material(DynamicMessage message) {
    var counts = new TreeMap<Integer, Integer>();
    for (var value : (List<?>) field(message, "material_list")) {
      var row = (DynamicMessage) value;
      counts.put(number(row, "tid"), number(row, "num"));
    }
    return counts;
  }

  private Map<Integer, Integer> rewards(DynamicMessage message) {
    var counts = new TreeMap<Integer, Integer>();
    for (var value : (List<?>) field((DynamicMessage) field(message, "reward"), "item_list")) {
      var row = (DynamicMessage) value;
      counts.merge(number(row, "item_id"), number(row, "num"), Integer::sum);
    }
    return counts;
  }

  private void bag(DynamicMessage message) {
    require(number(message, "retcode") == 0, "Bag query rejected");
    var counts = material(message);
    if (step == 7) {
      require(
          counts.getOrDefault(264999, 0) == 2050 && counts.getOrDefault(264996, 0) == 10,
          "Fixture points/coin delivery changed");
      if (progression())
        require(counts.getOrDefault(264998, 0) == 1000, "Missing isolated upgrade dust");
      return;
    }
    var expected =
        Map.of(
            264999, 50, 264996, 24, 264021, 1, 264403, 1, 150093, 1, 227005, 1, 213, 24, 223, 24,
            233, 24, 241, 1);
    expected.forEach(
        (id, count) ->
            require(
                counts.getOrDefault(id, 0).equals(count),
                "Real offering inventory changed: " + id + "=" + counts.getOrDefault(id, 0)));
    require(
        basic != null
            && number(basic, "scoin") == credits + 500000
            && number(basic, "hcoin") == jade + 240,
        "Actual virtual reward balances changed");
    result.put("material", new TreeMap<>(expected));
    if (progression()) {
      int dust = fresh() && step == 14 ? 1000 : 760;
      require(counts.getOrDefault(264998, 0) == dust, "Upgrade dust debit/restart mismatch");
      ((Map<Integer, Integer>) result.get("material")).put(264998, dust);
      result.put("upgradeDust", dust);
    }
    result.put("credits", number(basic, "scoin"));
    result.put("jade", number(basic, "hcoin"));
    result.put("playerLevel", number(basic, "level"));
    result.put("displayExp", number(basic, "exp"));
  }

  private void system(DynamicMessage message) {
    require(number(message, "retcode") == 0, "Dice system rejected");
    var avatars = (List<?>) field(message, "avatar_list");
    var dice = (List<?>) field(message, "KCNEKEGGAGG");
    require(
        avatars.size() == 1
            && dice.size() == 1
            && ((List<?>) field(message, "OHFIIBEIPJE")).isEmpty(),
        "Offering/buy manufactured unrelated stage/ownership");
    var avatar = (DynamicMessage) avatars.getFirst();
    var die = (DynamicMessage) dice.getFirst();
    require(
        number(avatar, "dice_avatar_id") == 264021
            && number(avatar, "level") == (progression() && step != 15 ? 3 : 1)
            && field(avatar, "dice_id_list")
                .equals(
                    progression() && step != 15
                        ? List.of(264101, 264201, 264301, 264403)
                        : AVATAR_DICE),
        "Authored reward avatar/base dice changed");
    require(number(die, "CLEKAKLPNBP") == 264403, "Wrong source Offering-level shop die");
    for (var entry :
        Map.of(
                264021,
                (Long) field(avatar, "unlock_time"),
                264403,
                (Long) field(die, "unlock_time"))
            .entrySet()) {
      require(
          entry.getValue() > 0
              && (!times.containsKey(entry.getKey())
                  || times.get(entry.getKey()).equals(entry.getValue())),
          "Acquisition time changed");
      times.put(entry.getKey(), entry.getValue());
    }
    result.put("unlockTimes", times);
    result.put("avatarDice", field(avatar, "dice_id_list"));
    if (progression()) result.put("avatarLevel", number(avatar, "level"));
  }

  private void done() {
    result.put("mode", input.get("mode").getAsString());
    result.put("addNotifications", notifications);
    result.put("outfitNotifications", outfitNotifications);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated offering frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "PlayerSyncScNotify",
                  "GetOfferingInfoScRsp",
                  "SubmitOfferingItemScRsp",
                  "TakeOfferingRewardScRsp",
                  "GetDiceCombatSystemDataScRsp",
                  "BuyShopGoodScRsp",
                  "AddItemRsp",
                  "GetBagScRsp",
                  "MusicRhythmStartLevelScRsp",
                  "MusicRhythmFinishLevelScRsp",
                  "SetPlayerOutfitScRsp",
                  "GetAvatarDataScRsp",
                  "UpgradeAvatarRsp",
                  "ModifyAvatarDiceRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown native response fields");
      System.err.println("Offering response=" + name + ", step=" + step);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          query(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Login rejected");
          basic = (DynamicMessage) field(m, "basic_info");
          credits =
              input.has("initialCredits")
                  ? input.get("initialCredits").getAsInt()
                  : number(basic, "scoin");
          jade =
              input.has("initialJade")
                  ? input.get("initialJade").getAsInt()
                  : number(basic, "hcoin");
          result.put("initialCredits", credits);
          result.put("initialJade", jade);
          if (input.has("unlockTimes"))
            input
                .getAsJsonObject("unlockTimes")
                .asMap()
                .forEach((id, time) -> times.put(Integer.parseInt(id), time.getAsLong()));
          query(c, "PlayerLoginFinishCsReq");
        }
        case "PlayerSyncScNotify" -> {
          var f = m.getDescriptorForType().findFieldByName("basic_info");
          if (m.hasField(f)) basic = (DynamicMessage) m.getField(f);
          var outfit = m.getDescriptorForType().findFieldByName("DHKFCDAGHDM");
          if (m.hasField(outfit)) {
            require(
                outfits(m).equals(step == 26 ? List.of() : List.of(1005)),
                "Invalid outfit synchronization");
            outfitNotifications++;
          }
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Login finish rejected");
          step = input.get("mode").getAsString().equals("fresh") ? 1 : 20;
          offering(c);
        }
        case "GetOfferingInfoScRsp" -> {
          require(number(m, "retcode") == 0, "Offering query rejected");
          var list = (List<?>) field(m, "offering_info_list");
          require(list.size() == 1, "Missing actual offering");
          progress((DynamicMessage) list.getFirst(), step != 1, step == 20);
          if (step == 1) {
            step = 2;
            buy(c);
          } else if (step == 20) {
            step = 21;
            submit(c);
          } else throw new IllegalStateException("Unexpected offering query");
        }
        case "BuyShopGoodScRsp" -> {
          int expected =
              step == 2
                  ? Retcode.REQ_PARA_INVALID.getVal()
                  : step == 13
                      ? 0
                      : closed()
                          ? Retcode.SHOP_NOT_OPEN.getVal()
                          : Retcode.BUY_TIMES_LIMIT.getVal();
          require(
              number(m, "retcode") == expected,
              "Dice offering-gate purchase changed: " + number(m, "retcode"));
          if (step == 2) {
            step = 3;
            var b = request("MusicRhythmStartLevelCsReq");
            set(b, "level_id", 101);
            send(c, "MusicRhythmStartLevelCsReq", b);
          } else if (step == 13) {
            require(notifications == 2, "Missing actual reward/shop acquisition");
            step = 14;
            query(c, "GetBagCsReq");
          } else if (step == 23) {
            step = 24;
            query(c, "GetBagCsReq");
          } else throw new IllegalStateException("Unexpected purchase");
        }
        case "MusicRhythmStartLevelScRsp" -> {
          require(step == 3 && number(m, "retcode") == 0, "Fixture funding start rejected");
          step = 4;
          var b = request("MusicRhythmFinishLevelCsReq");
          set(b, "role_star", 1);
          send(c, "MusicRhythmFinishLevelCsReq", b);
        }
        case "MusicRhythmFinishLevelScRsp" -> {
          require(step == 4 && number(m, "retcode") == 0, "Fixture funding rejected");
          step = 7;
          query(c, "GetBagCsReq");
        }
        case "GetBagScRsp" -> {
          bag(m);
          if (step == 48 || step == 52) {
            done();
            break;
          }
          if (step == 7) {
            step = 8;
            submit(c);
          } else if (step == 14 || step == 24) {
            step++;
            query(c, "GetDiceCombatSystemDataCsReq");
          } else throw new IllegalStateException("Unexpected bag query");
        }
        case "SubmitOfferingItemScRsp" -> {
          if (step == 8) {
            require(number(m, "retcode") == 0, "Real source offering debit rejected");
            progress((DynamicMessage) field(m, "offering_info"), true, false);
            step = 9;
            claim(c, List.of(26));
          } else if (step == 21) {
            require(
                number(m, "retcode")
                    == (closed()
                        ? Retcode.SHOP_NOT_OPEN.getVal()
                        : Retcode.REQ_PARA_INVALID.getVal()),
                "Cap/reopened submission was not rejected");
            step = 22;
            claim(c, LEVELS);
          } else throw new IllegalStateException("Unexpected offering submission");
        }
        case "TakeOfferingRewardScRsp" -> {
          if (step == 9) {
            require(
                number(m, "retcode") == Retcode.REQ_PARA_INVALID.getVal() && rewards(m).isEmpty(),
                "Unearned reward granted");
            step = 10;
            claim(c, LEVELS);
          } else if (step == 10) {
            require(
                number(m, "retcode") == 0,
                "Actual 25-level rewards rejected: " + number(m, "retcode"));
            progress((DynamicMessage) field(m, "offering_info"), true, true);
            var amounts = rewards(m);
            require(
                amounts.getOrDefault(22, 0) == 2200
                    && amounts.getOrDefault(2, 0) == 500000
                    && amounts.getOrDefault(1, 0) == 240
                    && amounts.getOrDefault(264996, 0) == 19
                    && amounts.getOrDefault(227005, 0) == 1
                    && amounts.getOrDefault(264021, 0) == 1,
                "Actual source reward vector changed");
            result.put("rewardItems", amounts);
            step = 11;
            claim(c, LEVELS);
          } else if (step == 11) {
            require(
                number(m, "retcode") == Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()
                    && rewards(m).isEmpty(),
                "Reward repeated");
            step = 13;
            buy(c);
          } else if (step == 22) {
            require(
                number(m, "retcode")
                        == (closed()
                            ? Retcode.SHOP_NOT_OPEN.getVal()
                            : Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal())
                    && rewards(m).isEmpty(),
                "Restart/closed reward repeated");
            step = 23;
            buy(c);
          } else throw new IllegalStateException("Unexpected offering claim");
        }
        case "AddItemRsp" -> {
          int id = step == 10 ? number(m, "avatar_id") : number(m, "CLEKAKLPNBP");
          require(
              (step == 10 && id == 264021 || step == 13 && id == 264403)
                  && (Long) field(m, "unlock_time") > 0,
              "Wrong reward/shop acquisition");
          require(rewards(m).equals(Map.of(id, 1)), "Wrong acquisition item delta");
          times.put(id, (Long) field(m, "unlock_time"));
          notifications++;
        }
        case "GetDiceCombatSystemDataScRsp" -> {
          require(step == 15 || step == 25 || step == 47, "Unexpected owned system snapshot");
          system(m);
          if (step == 47) {
            step = 48;
            query(c, "GetBagCsReq");
            break;
          }
          step = step == 15 ? 16 : 30;
          query(c, "GetAvatarDataCsReq");
        }
        case "GetAvatarDataScRsp" -> {
          require(number(m, "retcode") == 0, "Avatar outfit query rejected");
          require(
              outfits(m).equals(step == 16 || step == 27 ? List.of() : List.of(1005)),
              "Outfit equipment was auto-equipped, lost or not cleared");
          if (step == 16) {
            step = 17;
            equip(c, List.of(1006));
          } else if (step == 19) {
            step = 26;
            equip(c, List.of());
          } else if (step == 27) {
            step = 28;
            equip(c, List.of(1005));
          } else if (step == 30) {
            step = 31;
            equip(c, List.of(1005));
          } else if (step == 29 || step == 32) {
            require(outfitNotifications == (step == 29 ? 3 : 1), "Outfit sync count changed");
            result.put("equippedOutfits", outfits(m));
            if (progression()) {
              step = fresh() ? 40 : 50;
              upgrade(c);
            } else done();
          } else throw new IllegalStateException("Unexpected avatar outfit query");
        }
        case "SetPlayerOutfitScRsp" -> {
          require(
              number(m, "PLDKENJLKME") == (step == 17 ? Retcode.ITEM_NOT_ENOUGH.getVal() : 0),
              "Owned/unowned outfit validation failed");
          if (step == 17) {
            require(outfitNotifications == 0, "Unowned outfit synchronized");
            step = 18;
            equip(c, List.of(1005));
          } else if (step == 18 || step == 26 || step == 28 || step == 31) {
            step++;
            query(c, "GetAvatarDataCsReq");
          } else throw new IllegalStateException("Unexpected outfit response");
        }
        case "UpgradeAvatarRsp" -> {
          require(progression(), "Unexpected upgrade response");
          if (step == 40 || step == 41) {
            require(number(m, "retcode") == 0, "Native upgrade failed: " + number(m, "retcode"));
            progressionAvatar(m, step == 40 ? 2 : 3, 264518);
            step++;
            upgrade(c);
          } else if (step == 42 || step == 50) {
            require(
                number(m, "retcode")
                    == (closed() ? Retcode.SHOP_NOT_OPEN : Retcode.REQ_PARA_INVALID).getVal(),
                "Max level/closed upgrade not rejected");
            require(
                !m.hasField(m.getDescriptorForType().findFieldByName("avatar")),
                "Failed upgrade manufactured an avatar");
            step = step == 42 ? 43 : 51;
            modify(c, 264403);
          } else throw new IllegalStateException("Unexpected upgrade step");
        }
        case "ModifyAvatarDiceRsp" -> {
          require(progression(), "Unexpected modify response");
          require(
              number(m, "retcode") == (closed() ? Retcode.SHOP_NOT_OPEN.getVal() : 0),
              "Native equipment/closed validation failed");
          if (!closed()) progressionAvatar(m, 3, step == 45 ? 264518 : 264403);
          if (step == 43) {
            step = 44;
            modify(c, 264403);
          } else if (step == 44) {
            step = 45;
            modify(c, 264518);
          } else if (step == 45) {
            step = 46;
            modify(c, 264403);
          } else if (step == 46) {
            step = 47;
            query(c, "GetDiceCombatSystemDataCsReq");
          } else if (step == 51) {
            step = 52;
            query(c, "GetBagCsReq");
          } else throw new IllegalStateException("Unexpected modify step");
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackOfferingProbe();
      probe.input =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", probe.input.get("accountUid").getAsString());
      set(token, "token", probe.input.get("comboToken").getAsString());
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

            public void handleReceive(ByteBuf b, Ukcp c) {
              try {
                probe.receive(b, c);
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
                    new IllegalStateException("Offering disconnected"));
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
      System.err.println("Offering probe failed: " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
