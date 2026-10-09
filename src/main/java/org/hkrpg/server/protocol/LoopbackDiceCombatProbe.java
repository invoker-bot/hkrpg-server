package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/**
 * Actual catalog, native packets and real inventory; synthetic KCP never proves game UI acceptance.
 */
public final class LoopbackDiceCombatProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private JsonObject input;
  private int step, addCount;
  private long unlock;
  private static final List<Integer> DICE = List.of(264101, 264201, 0, 0);

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
    System.err.println("DiceCombat request=" + name + ", step=" + step);
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

  private void buy(Ukcp c, int shop, int good) {
    var b = request("BuyShopGoodCsReq");
    set(b, "MCDNNFACGPE", shop);
    set(b, "KDMNGCLJDOM", good);
    send(c, "BuyShopGoodCsReq", b);
  }

  private void inventory(DynamicMessage m, int coins, int cards) {
    var counts = new TreeMap<Integer, Integer>();
    for (var value : (List<?>) field(m, "material_list")) {
      var row = (DynamicMessage) value;
      counts.put(number(row, "tid"), number(row, "num"));
    }
    require(
        counts.getOrDefault(264996, 0) == coins && counts.getOrDefault(264001, 0) == cards,
        "Real DiceCombat wallet/card balance changed");
    result.put("coins", coins);
    result.put("cards", cards);
  }

  private void system(DynamicMessage m, boolean owned) {
    require(number(m, "retcode") == 0, "DiceCombat system query rejected");
    var avatars = (List<?>) field(m, "avatar_list");
    require(avatars.size() == (owned ? 1 : 0), "Query fabricated/removed avatar ownership");
    require(
        ((List<?>) field(m, "OHFIIBEIPJE")).isEmpty()
            && ((List<?>) field(m, "KCNEKEGGAGG")).isEmpty(),
        "Query fabricated stage or die unlocks");
    if (owned) {
      var avatar = (DynamicMessage) avatars.getFirst();
      require(
          number(avatar, "dice_avatar_id") == 264001
              && number(avatar, "level") == 1
              && field(avatar, "dice_id_list").equals(DICE),
          "Base source avatar/dice changed");
      long time = (Long) field(avatar, "unlock_time");
      require(time > 0, "Missing actual acquisition time");
      if (unlock != 0) require(time == unlock, "Acquisition time changed across queries/recovery");
      unlock = time;
      result.put("unlockTime", time);
      result.put("avatar", 264001);
      result.put("level", 1);
      result.put("dice", DICE);
    }
  }

  private void shops(DynamicMessage m, boolean purchased, boolean closed) {
    require(number(m, "retcode") == 0, "DiceCombat shop query rejected");
    var shops = (List<?>) field(m, "OPNFOBJOPCB");
    if (closed) {
      require(shops.isEmpty(), "Closed activity exposed historical shops");
      return;
    }
    require(shops.size() == 3, "Missing actual three source shops");
    var first =
        shops.stream()
            .map(v -> (DynamicMessage) v)
            .filter(v -> number(v, "shop_id") == 1)
            .findFirst()
            .orElseThrow();
    var goods = (List<?>) field(first, "CNFFGBMPCHD");
    require(goods.size() == 11, "Locked Offering goods were exposed");
    var good =
        goods.stream()
            .map(v -> (DynamicMessage) v)
            .filter(v -> number(v, "KDMNGCLJDOM") == 1101)
            .findFirst()
            .orElseThrow();
    require(
        number(good, "item_id") == 264001 && (Boolean) field(good, "DOFNEAPHMKH") == purchased,
        "Source item/purchase-limit flag changed");
    var cost = (DynamicMessage) field(good, "cost");
    var costs = (List<?>) field(cost, "item_list");
    require(costs.size() == 1, "Source cost missing");
    var pile = (DynamicMessage) field((DynamicMessage) costs.getFirst(), "pile_item");
    require(
        number(pile, "item_id") == 264996 && number(pile, "item_num") == 1,
        "Actual resource price changed");
  }

  private void done() {
    require(
        addCount == (input.get("mode").getAsString().equals("fresh") ? 1 : 0),
        "Unexpected/repeated AddItemRsp");
    result.put("mode", input.get("mode").getAsString());
    result.put("addNotifications", addCount);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated DiceCombat frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetDiceCombatSystemDataScRsp",
                  "GetDiceCombatShopDataScRsp",
                  "BuyShopGoodScRsp",
                  "AddItemRsp",
                  "GetBagScRsp",
                  "MusicRhythmStartLevelScRsp",
                  "MusicRhythmFinishLevelScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown native response fields");
      System.err.println("DiceCombat response=" + name + ", step=" + step);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          query(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Login rejected");
          query(c, "PlayerLoginFinishCsReq");
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Login finish rejected");
          step = input.get("mode").getAsString().equals("fresh") ? 1 : 20;
          if (input.has("unlockTime")) unlock = input.get("unlockTime").getAsLong();
          query(c, "GetDiceCombatSystemDataCsReq");
        }
        case "GetDiceCombatSystemDataScRsp" -> {
          system(m, step != 1);
          if (step == 1 || step == 20) {
            step++;
            query(c, "GetDiceCombatShopDataCsReq");
          } else if (step == 11) {
            step = 12;
            buy(c, 1, 1101);
          } else throw new IllegalStateException("Unexpected system query step");
        }
        case "GetDiceCombatShopDataScRsp" -> {
          shops(m, step == 21, input.get("mode").getAsString().equals("closed"));
          if (step == 2) {
            step = 3;
            query(c, "GetBagCsReq");
          } else if (step == 21) {
            step = 22;
            buy(c, 1, 1101);
          } else throw new IllegalStateException("Unexpected shop query step");
        }
        case "GetBagScRsp" -> {
          require(number(m, "retcode") == 0, "Inventory rejected");
          if (step == 3) {
            inventory(m, 0, 0);
            step = 4;
            buy(c, 1, 1101);
          } else if (step == 9) {
            inventory(m, 10, 0);
            step = 10;
            buy(c, 1, 1101);
          } else if (step == 13 || step == 23) {
            inventory(m, 9, 1);
            done();
          } else throw new IllegalStateException("Unexpected inventory step");
        }
        case "BuyShopGoodScRsp" -> {
          int expected =
              switch (step) {
                case 4 -> Retcode.ITEM_NOT_ENOUGH.getVal();
                case 5, 6 -> Retcode.REQ_PARA_INVALID.getVal();
                case 10 -> 0;
                case 12 -> Retcode.BUY_TIMES_LIMIT.getVal();
                case 22 ->
                    input.get("mode").getAsString().equals("closed")
                        ? Retcode.SHOP_NOT_OPEN.getVal()
                        : Retcode.BUY_TIMES_LIMIT.getVal();
                default -> throw new IllegalStateException("Unexpected purchase step");
              };
          require(
              number(m, "retcode") == expected,
              "Purchase retcode changed " + number(m, "retcode") + " != " + expected);
          if (step == 4) {
            step = 5;
            buy(c, 2, 1101);
          } else if (step == 5) {
            step = 6;
            buy(c, 1, 1201);
          } else if (step == 6) {
            step = 7;
            var b = request("MusicRhythmStartLevelCsReq");
            set(b, "level_id", 101);
            send(c, "MusicRhythmStartLevelCsReq", b);
          } else if (step == 10) {
            shops(m, true, false);
            require(addCount == 1, "Missing native acquisition notification");
            step = 11;
            query(c, "GetDiceCombatSystemDataCsReq");
          } else {
            step++;
            query(c, "GetBagCsReq");
          }
        }
        case "MusicRhythmStartLevelScRsp" -> {
          require(
              step == 7 && number(m, "retcode") == 0 && number(m, "level_id") == 101,
              "Fixture funding start rejected");
          step = 8;
          var b = request("MusicRhythmFinishLevelCsReq");
          set(b, "role_star", 1);
          send(c, "MusicRhythmFinishLevelCsReq", b);
        }
        case "MusicRhythmFinishLevelScRsp" -> {
          require(step == 8 && number(m, "retcode") == 0, "Fixture funding reward rejected");
          step = 9;
          query(c, "GetBagCsReq");
        }
        case "AddItemRsp" -> {
          require(
              step == 10
                  && ++addCount == 1
                  && number(m, "avatar_id") == 264001
                  && (Long) field(m, "unlock_time") > 0,
              "Wrong/duplicate acquisition notification");
          unlock = (Long) field(m, "unlock_time");
          var rewards = (List<?>) field((DynamicMessage) field(m, "reward"), "item_list");
          require(
              rewards.size() == 1
                  && number((DynamicMessage) rewards.getFirst(), "item_id") == 264001
                  && number((DynamicMessage) rewards.getFirst(), "num") == 1,
              "Wrong acquisition reward");
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackDiceCombatProbe();
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
                    new IllegalStateException("DiceCombat disconnected"));
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
      System.err.println("DiceCombat probe failed: " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
