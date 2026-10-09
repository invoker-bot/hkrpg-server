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

/** Actual source relic composition, unique affixes and normal H2 restart over UDP/KCP. */
public final class LoopbackSelectedRelicProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private final Set<Integer> returnedIds = new HashSet<>();
  private final Map<Integer, List<Integer>> selectedByUid = new HashMap<>();
  private boolean resume, stockClaimed;
  private int action = -1, mail, coins, initialCoins, expectedCoins;

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

  private void compose(Ukcp c) {
    var b = request("ComposeSelectedRelicCsReq");
    set(b, "compose_id", 9001);
    set(b, "compose_relic_id", 61011);
    set(b, "count", 1);
    set(b, "main_affix_id", action == 0 ? 999 : action == 5 ? 0 : 1);
    var subs = b.getDescriptorForType().findFieldByName("sub_affix_id_list");
    if (action == 1) {
      b.addRepeatedField(subs, 2);
      b.addRepeatedField(subs, 2);
    }
    if (action == 3) {
      b.addRepeatedField(subs, 2);
      b.addRepeatedField(subs, 8);
    }
    if (action == 4) b.addRepeatedField(subs, 2);
    if (action == 2) set(b, "wr_item_list", request("ItemCostData").build());
    send(c, "ComposeSelectedRelicCsReq", b);
  }

  private void bag(DynamicMessage m, int made) {
    var counts = new HashMap<Integer, Integer>();
    for (var v : (List<?>) field(m, "material_list")) {
      var item = (DynamicMessage) v;
      require(counts.put(number(item, "tid"), number(item, "num")) == null, "Duplicate stack");
    }
    require(
        counts.getOrDefault(235, 0) == 300 - made * 100
            && counts.getOrDefault(236, 0) == (made == 3 ? 1 : 3 - made)
            && counts.getOrDefault(237, 0) == (made == 0 ? 5 : made == 1 ? 1 : 0),
        "Actual relic debit differs: " + counts);
    require(
        coins == (resume ? expectedCoins : initialCoins + 5000 - made * 1000),
        "Actual relic coin debit differs: " + coins);
    var ids = new HashSet<Integer>();
    var rolls = new ArrayList<String>();
    for (var v : (List<?>) field(m, "relic_list")) {
      var relic = (DynamicMessage) v;
      int uid = number(relic, "unique_id");
      require(
          uid > 0
              && ids.add(uid)
              && number(relic, "tid") == 61011
              && number(relic, "main_affix_id") == 1,
          "Invalid unique relic");
      var affixes = new TreeMap<Integer, Integer>();
      for (var entry : (List<?>) field(relic, "sub_affix_list")) {
        var sub = (DynamicMessage) entry;
        int id = number(sub, "affix_id"), step = number(sub, "step");
        require(
            id >= 2
                && id <= 12
                && number(sub, "cnt") == 1
                && step >= 0
                && step <= 2
                && affixes.put(id, step) == null,
            "Invalid actual sub affix");
      }
      require(affixes.size() >= 3 && affixes.size() <= 4, "Missing actual sub affixes");
      if (!resume && selectedByUid.containsKey(uid))
        require(
            affixes.keySet().containsAll(selectedByUid.get(uid)),
            "Selected native sub affixes lost");
      rolls.add(number(relic, "tid") + ":" + number(relic, "main_affix_id") + ":" + affixes);
    }
    require(
        ids.size() == made && (resume || ids.equals(returnedIds)),
        "Response relics differ from actual owned bag");
    Collections.sort(rolls);
    result.put("relicRolls", rolls);
    result.put("remaining235", counts.getOrDefault(235, 0));
    result.put("remaining236", counts.getOrDefault(236, 0));
    result.put("remaining237", counts.getOrDefault(237, 0));
    result.put("scoin", coins);
  }

  private void finish() {
    result.put("savedRelicsAndMaterialsRestored", resume);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var f = PacketCodec.decodeNext(bytes);
      require(f != null, "Truncated relic frame");
      var name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetMailScRsp",
                  "TakeMailAttachmentScRsp",
                  "GetBagScRsp",
                  "ComposeSelectedRelicScRsp",
                  "PlayerSyncScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == f.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, f.body());
      if (name.equals("PlayerSyncScNotify")) {
        var basic = m.getDescriptorForType().findFieldByName("basic_info");
        if (m.hasField(basic)) coins = number((DynamicMessage) m.getField(basic), "scoin");
        continue;
      }
      boolean denied =
          name.equals("ComposeSelectedRelicScRsp") && (resume || action < 3 || action == 6);
      require(
          denied ? number(m, "retcode") != 0 : number(m, "retcode") == 0,
          "Wrong relic response " + name + " action=" + action);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(m, "uid"));
          query(c, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          coins = number((DynamicMessage) field(m, "basic_info"), "scoin");
          initialCoins = coins;
          query(c, "PlayerLoginFinishCsReq");
        }
        case "PlayerLoginFinishScRsp" -> query(c, resume ? "GetBagCsReq" : "GetMailCsReq");
        case "GetMailScRsp" -> {
          var matches =
              ((List<?>) field(m, "mail_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> field(v, "title").equals("Selected relic stock fixture"))
                      .toList();
          require(matches.size() == 1, "No unique isolated relic stock mail");
          mail = number(matches.getFirst(), "id");
          var b = request("TakeMailAttachmentCsReq");
          b.addRepeatedField(b.getDescriptorForType().findFieldByName("mail_id_list"), mail);
          send(c, "TakeMailAttachmentCsReq", b);
        }
        case "TakeMailAttachmentScRsp" -> {
          require(
              field(m, "succ_mail_id_list").equals(List.of(mail)),
              "Stock mail not actually claimed");
          stockClaimed = true;
          result.put("nativeActualMaterialStock", true);
          query(c, "GetBagCsReq");
        }
        case "GetBagScRsp" -> {
          require(resume || stockClaimed, "Unclaimed stock");
          int made = resume ? 3 : action < 3 ? 0 : Math.min(action - 2, 3);
          bag(m, made);
          if (resume) {
            if (action == 6) {
              result.put("nativeSavedMissingMaterialRejected", true);
              finish();
            } else {
              action = 6;
              compose(c);
            }
          } else {
            if (action >= 0)
              result.put(
                  List.of(
                          "nativeInvalidMainNoDebit",
                          "nativeDuplicateSubsNoDebit",
                          "nativeUnverifiedCostsNoDebit",
                          "nativeTwoSubsAndActualDebit",
                          "nativeOneSubAndActualDebit",
                          "nativeRandomMainAndActualDebit",
                          "nativeMissingMaterialNoDebit")
                      .get(action),
                  true);
            if (action == 6) finish();
            else {
              action++;
              compose(c);
            }
          }
        }
        case "ComposeSelectedRelicScRsp" -> {
          var outputs = (DynamicMessage) field(m, "return_item_list");
          var items = (List<?>) field(outputs, "item_list");
          require(items.size() == (denied ? 0 : 1), "Rejected request claimed an output");
          if (!denied) {
            var item = (DynamicMessage) items.getFirst();
            int uid = number(item, "unique_id");
            require(
                uid > 0
                    && returnedIds.add(uid)
                    && number(item, "item_id") == 61011
                    && number(item, "num") == 1
                    && number(item, "main_affix_id") == 1,
                "Actual unique output invalid");
            selectedByUid.put(
                uid, action == 3 ? List.of(2, 8) : action == 4 ? List.of(2) : List.of());
          }
          query(c, "GetBagCsReq");
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackSelectedRelicProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      if (p.resume) p.expectedCoins = credentials.get("expectedScoin").getAsInt();
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
                    new IllegalStateException("Relic probe disconnected"));
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
          "Selected relic probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
