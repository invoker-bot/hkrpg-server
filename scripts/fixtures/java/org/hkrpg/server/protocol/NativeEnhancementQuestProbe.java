package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Test-only native paid and curio upgrades; no ownership, counter or completion seeding. */
public final class NativeEnhancementQuestProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int target,
      current,
      selected,
      expectedFragments,
      progressNotifications,
      currencyNotifications,
      buffNotifications,
      jadeNotifications,
      queries,
      actualEnhancements,
      actualPurchases,
      explorations,
      automatic,
      curioPurchases;
  private int observedJades = -1;
  private boolean awaitEnhancement, awaitClaim;
  private Set<Integer> claimed = Set.of();
  private final Set<Integer> enhanceable = new HashSet<>();
  private Set<Integer> expectedBuffs = Set.of();
  private final Set<Integer> notifiedBuffs = new HashSet<>();
  private final List<Map<String, Integer>> operations = new ArrayList<>();
  private final Map<Integer, Integer> questProgress = new TreeMap<>(),
      questPhases = new TreeMap<>();
  private final List<String> checks = new ArrayList<>();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private static int phase(DynamicMessage m) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(m, "status")).getNumber();
  }

  private void validateQuests(DynamicMessage m, int count, Set<Integer> closed) {
    require(number(m, "retcode") == 0, "Original enhancement quest query failed");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(m, "quest_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id == 4092314 || id == 4092315)
        require(rows.put(id, row) == null, "Duplicate enhancement goal");
    }
    require(rows.size() == 2, "Original enhancement goals missing");
    for (int id : List.of(4092314, 4092315)) {
      int goal = id == 4092314 ? 10 : 200;
      var row = rows.get(id);
      int status = closed.contains(id) ? 3 : count >= goal ? 2 : 1;
      require(
          number(row, "progress") == Math.min(goal, count)
              && phase(row) == status
              && ((Long) field(row, "finish_time") > 0) == (status != 1),
          "Enhancement progress/phase/time differs from actual operations");
      questProgress.put(id, number(row, "progress"));
      questPhases.put(id, phase(row));
    }
  }

  private void observe(Frame f) throws Exception {
    if (awaitEnhancement && f.command() == protocol.clientCommand("GetQuestDataScRsp")) {
      validateQuests(
          protocol.clientSchema().parse("GetQuestDataScRsp", f.body()), current, claimed);
      progressNotifications++;
    }
    if (awaitEnhancement
        && f.command() == protocol.clientCommand("SyncRogueVirtualItemInfoScNotify")) {
      var m = protocol.clientSchema().parse("SyncRogueVirtualItemInfoScNotify", f.body());
      require(
          number((DynamicMessage) field(m, "NIKDPODBIEF"), "LKHAIEIDAJL") == expectedFragments,
          "Enhancement debit notification differs from frozen price");
      currencyNotifications++;
    }
    if (awaitEnhancement
        && f.command() == protocol.clientCommand("SyncRogueCommonActionResultScNotify")) {
      var m = protocol.clientSchema().parse("SyncRogueCommonActionResultScNotify", f.body());
      for (var raw : (List<?>) field(m, "CLGGKLNPLCK")) {
        var detail = (DynamicMessage) field((DynamicMessage) raw, "detail");
        if (detail.hasField(detail.getDescriptorForType().findFieldByName("IFOOEPKGGAG"))) {
          var buff = (DynamicMessage) field(detail, "IFOOEPKGGAG");
          require(
              expectedBuffs.contains(number(buff, "buff_id"))
                  && number(buff, "HAGDKKBAAKK") == 2
                  && notifiedBuffs.add(number(buff, "buff_id")),
              "Actual enhanced buff notification differs");
          buffNotifications++;
        }
      }
    }
    if (awaitClaim && f.command() == protocol.clientCommand("PlayerSyncScNotify")) {
      var m = protocol.clientSchema().parse("PlayerSyncScNotify", f.body());
      if (m.hasField(m.getDescriptorForType().findFieldByName("basic_info"))) {
        observedJades = number((DynamicMessage) field(m, "basic_info"), "hcoin");
        require(
            observedJades == 5 || target == 200 && observedJades == 15,
            "Original enhancement goal payment differs");
        jadeNotifications++;
      }
    }
  }

  private Frame raw(int command, byte[] payload, int response) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], payload));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Native enhancement connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    String response = name.replace("CsReq", "ScRsp");
    var frame =
        raw(
            protocol.clientCommand(name),
            b.build().toByteArray(),
            protocol.clientCommand(response));
    var m = protocol.clientSchema().parse(response, frame.body());
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown enhancement response fields");
    if (!name.equals("GetRogueBuffEnhanceInfoCsReq"))
      require(
          number(m, "retcode") == 0, "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  private void quests(int count) throws Exception {
    validateQuests(call("GetQuestDataCsReq"), count, claimed);
    queries++;
  }

  private static int cost(DynamicMessage quote) {
    var list = (List<?>) field((DynamicMessage) field(quote, "cost_data"), "item_list");
    require(list.size() == 1, "Enhancement/shop cost is not one pile");
    var pile = (DynamicMessage) field((DynamicMessage) list.getFirst(), "pile_item");
    require(number(pile, "item_id") == 31, "Enhancement/shop does not use fragments");
    return number(pile, "item_num");
  }

  private void enhance(boolean success) throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(13, selected);
    out.flush();
    var frame = raw(1885, buffer.toByteArray(), 1807);
    var fields = UnknownFieldSet.parseFrom(frame.body());
    var status = fields.getField(6).getVarintList();
    if (success) {
      require(
          status.isEmpty() && fields.getField(3).getLengthDelimitedList().size() == 1,
          "Native enhancement did not return the committed buff");
      var buff =
          protocol
              .clientSchema()
              .parse(
                  "KJAMNOGFAMJ",
                  fields.getField(3).getLengthDelimitedList().getFirst().toByteArray());
      require(
          number(buff, "buff_id") == selected && number(buff, "HAGDKKBAAKK") == 2,
          "Native enhancement returned wrong identity/level");
    } else
      require(
          status.size() == 1
              && status.getFirst() != 0
              && fields.getField(3).getLengthDelimitedList().isEmpty(),
          "Repeated enhancement succeeded");
  }

  private boolean iteration() throws Exception {
    var started =
        call(
            "StartRogueCsReq",
            Map.of(
                "area_id",
                100,
                "NOEPBNMJFGL",
                List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)));
    explorations++;
    var scene = (DynamicMessage) field(started, "scene");
    int merchant = 0, herta = 0;
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (!entity.hasField(entity.getDescriptorForType().findFieldByName("npc"))) continue;
        int npc = number((DynamicMessage) field(entity, "npc"), "npc_id");
        if (npc == 1007) {
          require(
              number(entity, "group_id") == (automatic == 0 ? 77 : 117)
                  && number(entity, "inst_id") == (automatic == 0 ? 400002 : 400001),
              "Wrong original blessing merchant");
          merchant = number(entity, "entity_id");
        }
        if (npc == 1013) {
          require(
              number(entity, "group_id") == (automatic == 0 ? 46 : 35)
                  && number(entity, "inst_id") == 400001,
              "Wrong original rest-room Herta");
          herta = number(entity, "entity_id");
        }
      }
    require(merchant > 0 && herta > 0, "Actual original merchant/Herta absent");
    var buffer = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(15, merchant);
    out.flush();
    var stock =
        protocol
            .clientSchema()
            .parse(
                "GetRogueShopBuffInfoScRsp",
                raw(5616, buffer.toByteArray(), protocol.clientCommand("GetRogueShopBuffInfoScRsp"))
                    .body());
    require(number(stock, "retcode") == 0, "Actual blessing shop query failed");
    var list = (List<?>) field((DynamicMessage) field(stock, "HABJHGPFGEB"), "buff_list");
    require(!list.isEmpty(), "Actual blessing stock empty");
    if (automatic != 0) return automaticIteration(merchant, herta, list);
    var quote =
        list.stream()
            .map(v -> (DynamicMessage) v)
            .filter(v -> enhanceable.contains(number(v, "buff_id")))
            .findFirst()
            .orElse(null);
    if (quote == null) {
      call("LeaveRogueCsReq");
      call("QuitRogueCsReq", Map.of("area_id", 100));
      return false;
    }
    selected = number(quote, "buff_id");
    int purchaseCost = cost(quote);
    require(
        number(quote, "JBGENFIKGGF") == 1 && !(Boolean) field(quote, "GCIHGKCJOGB"),
        "Initial buff is not a fresh level-one offer");
    buffer.reset();
    out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(2, merchant);
    out.writeUInt32(6, selected);
    out.flush();
    var bought =
        protocol
            .clientSchema()
            .parse(
                "BuyRogueShopBuffScRsp",
                raw(5615, buffer.toByteArray(), protocol.clientCommand("BuyRogueShopBuffScRsp"))
                    .body());
    require(number(bought, "retcode") == 0, "Actual source buff purchase failed");
    actualPurchases++;
    var info = call("GetRogueBuffEnhanceInfoCsReq");
    require(number(info, "PLDKENJLKME") == 0, "Actual Herta query failed");
    var offers = (List<?>) field((DynamicMessage) field(info, "retcode"), "EMAGFIBLNCJ");
    require(
        offers.size() == 1 && number((DynamicMessage) offers.getFirst(), "buff_id") == selected,
        "Herta did not quote the actual purchased buff " + selected + ": " + offers);
    int enhancementCost = cost((DynamicMessage) offers.getFirst());
    require(enhancementCost > 0 && purchaseCost > 0, "Invalid actual project quote");
    expectedFragments = 1000 - purchaseCost - enhancementCost;
    require(expectedFragments >= 0, "Isolated enhancement budget insufficient");
    int p = progressNotifications, c = currencyNotifications, b = buffNotifications;
    current++;
    expectedBuffs = Set.of(selected);
    notifiedBuffs.clear();
    awaitEnhancement = true;
    enhance(true);
    awaitEnhancement = false;
    actualEnhancements++;
    operations.add(
        Map.of(
            "buff",
            selected,
            "purchaseCost",
            purchaseCost,
            "enhancementCost",
            enhancementCost,
            "remainingFragments",
            expectedFragments));
    require(
        progressNotifications == p + 1
            && currencyNotifications == c + 1
            && buffNotifications == b + 1,
        "Actual progress/buff/debit notifications did not precede enhancement success");
    enhance(false);
    var game = (DynamicMessage) field(call("GetRogueInfoCsReq"), "rogue_game_info");
    var run = (DynamicMessage) field(game, "CFDIKLBINHK");
    require(
        number((DynamicMessage) field(run, "DHEKLJDBNNP"), "rogue_money") == expectedFragments,
        "Enhancement/retry changed actual wallet");
    var owned = (List<?>) field((DynamicMessage) field(run, "HABJHGPFGEB"), "CHCMEKAFKAF");
    require(
        owned.size() == 1
            && number((DynamicMessage) owned.getFirst(), "buff_id") == selected
            && number((DynamicMessage) owned.getFirst(), "level") == 2,
        "Actual source owned buff was not enhanced");
    call("LeaveRogueCsReq");
    call("QuitRogueCsReq", Map.of("area_id", 100));
    return true;
  }

  private DynamicMessage curio(int entity, boolean buy) throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(12, entity);
    if (buy) out.writeUInt32(6, automatic);
    out.flush();
    String name = buy ? "BuyRogueShopMiracleScRsp" : "GetRogueShopMiracleInfoScRsp";
    return protocol
        .clientSchema()
        .parse(
            name,
            raw(buy ? 5656 : 5675, buffer.toByteArray(), protocol.clientCommand(name)).body());
  }

  private boolean automaticIteration(int merchant, int curioEntity, List<?> list) throws Exception {
    var info = curio(curioEntity, false);
    require(number(info, "retcode") == 0, "Actual source curio shop query failed");
    var stock = (List<?>) field((DynamicMessage) field(info, "DNPCCFFFBHN"), "MAJGMMCOCIG");
    var selectedCurio =
        stock.stream()
            .map(v -> (DynamicMessage) v)
            .filter(v -> number(v, "LJCEOGPOGBE") == automatic)
            .findFirst()
            .orElse(null);
    var selectedQuotes =
        list.stream()
            .map(v -> (DynamicMessage) v)
            .filter(v -> enhanceable.contains(number(v, "buff_id")))
            .limit(Math.min(2, target - current))
            .toList();
    if (selectedCurio == null || selectedQuotes.isEmpty()) {
      call("LeaveRogueCsReq");
      call("QuitRogueCsReq", Map.of("area_id", 100));
      return false;
    }
    int curioCost = cost(selectedCurio);
    require(curioCost == 200, "Unexpected isolated project curio price");
    var ids = new ArrayList<Integer>();
    int purchaseCost = 0;
    for (var quote : selectedQuotes) {
      require(
          number(quote, "JBGENFIKGGF") == 1 && !(Boolean) field(quote, "GCIHGKCJOGB"),
          "Source buff is not an available level-one offer");
      ids.add(number(quote, "buff_id"));
      purchaseCost += cost(quote);
    }
    var buffer = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(2, merchant);
    for (int id : ids) out.writeUInt32(6, id);
    out.flush();
    var bought =
        protocol
            .clientSchema()
            .parse(
                "BuyRogueShopBuffScRsp",
                raw(5615, buffer.toByteArray(), protocol.clientCommand("BuyRogueShopBuffScRsp"))
                    .body());
    require(number(bought, "retcode") == 0, "Actual buffs were not purchased for the curio");
    actualPurchases += ids.size();
    expectedFragments = 1000 - purchaseCost - curioCost;
    require(expectedFragments >= 0, "Isolated source stock budget insufficient");
    current += ids.size();
    expectedBuffs = Set.copyOf(ids);
    notifiedBuffs.clear();
    int p = progressNotifications, c = currencyNotifications, b = buffNotifications;
    awaitEnhancement = true;
    var acquired = curio(curioEntity, true);
    awaitEnhancement = false;
    require(number(acquired, "retcode") == 0, "Actual curio enhancement purchase failed");
    require(
        progressNotifications == p + 1
            && currencyNotifications == c + 1
            && buffNotifications == b + ids.size()
            && notifiedBuffs.equals(expectedBuffs),
        "Actual automatic count/buffs/debit did not precede success");
    actualEnhancements += ids.size();
    curioPurchases++;
    for (var quote : selectedQuotes)
      operations.add(
          Map.of(
              "buff",
              number(quote, "buff_id"),
              "purchaseCost",
              cost(quote),
              "enhancementCost",
              0,
              "curio",
              automatic,
              "curioCost",
              curioCost,
              "purchaseTotalCost",
              purchaseCost,
              "transaction",
              curioPurchases,
              "remainingFragments",
              expectedFragments));
    require(number(curio(curioEntity, true), "retcode") != 0, "Repeated curio purchase succeeded");
    var run =
        (DynamicMessage)
            field(
                (DynamicMessage) field(call("GetRogueInfoCsReq"), "rogue_game_info"),
                "CFDIKLBINHK");
    require(
        number((DynamicMessage) field(run, "DHEKLJDBNNP"), "rogue_money") == expectedFragments,
        "Actual automatic wallet/retry differs");
    var owned = (List<?>) field((DynamicMessage) field(run, "HABJHGPFGEB"), "CHCMEKAFKAF");
    var actual = new HashSet<Integer>();
    for (var v : owned) {
      var row = (DynamicMessage) v;
      require(number(row, "level") == 2, "Owned curio buff was not enhanced");
      actual.add(number(row, "buff_id"));
    }
    require(
        actual.equals(expectedBuffs) && owned.size() == ids.size(),
        "Actual curio upgraded different buffs");
    var miracles =
        (List<?>)
            field(
                (DynamicMessage) field((DynamicMessage) field(run, "DNPCCFFFBHN"), "DNPCCFFFBHN"),
                "MAJGMMCOCIG");
    require(
        miracles.size() == 1 && number((DynamicMessage) miracles.getFirst(), "LJCEOGPOGBE") == 20,
        "Actual original upgrade curio not owned after purchase/replacement");
    call("LeaveRogueCsReq");
    call("QuitRogueCsReq", Map.of("area_id", 100));
    return true;
  }

  private Map<String, Object> execute(JsonObject credentials, java.io.BufferedReader ignored)
      throws Exception {
    target = credentials.get("target").getAsInt();
    automatic = credentials.has("automatic") ? credentials.get("automatic").getAsInt() : 0;
    require(
        automatic == 0 || automatic == 20 || automatic == 21, "Unsupported actual upgrade source");
    for (var id : credentials.getAsJsonArray("enhanceable")) enhanceable.add(id.getAsInt());
    require(!enhanceable.isEmpty(), "No original enhanceable source identities");
    require(target == 10 || target == 200, "Unsupported original enhancement goal");
    boolean resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1));
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    observedJades = number((DynamicMessage) field(login, "basic_info"), "hcoin");
    require(
        observedJades == (resume ? target == 10 ? 5 : 15 : 0),
        "Fresh/restored enhancement wallet differs");
    call("PlayerLoginFinishCsReq");
    var ids = target == 10 ? List.of(4092314) : List.of(4092314, 4092315);
    if (resume) {
      current = target;
      claimed = Set.copyOf(ids);
      quests(target);
      checks.add("same-uid-count-goals-and-wallet-restored-after-normal-restart");
    } else {
      quests(0);
      current = 0;
      while (current < target) {
        require(
            explorations < target * (automatic == 0 ? 10 : 100),
            "No supported original stock after bounded exploration");
        iteration();
      }
      current = target;
      quests(target);
      awaitClaim = true;
      var reward = call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", ids));
      awaitClaim = false;
      require(
          field(reward, "succ_quest_id_list").equals(ids), "Wrong actual source reward identities");
      var rewards = (List<?>) field((DynamicMessage) field(reward, "reward"), "item_list");
      int total = 0;
      for (var raw : rewards) {
        var item = (DynamicMessage) raw;
        require(
            number(item, "item_id") == 1
                && (number(item, "num") == 5 || target == 200 && number(item, "num") == 10),
            "Unauthored enhancement goal reward");
        total += number(item, "num");
      }
      require(
          total == (target == 10 ? 5 : 15)
              && observedJades == total
              && jadeNotifications == ids.size(),
          "Original actual goal rewards were not notified before success");
      claimed = Set.copyOf(ids);
      checks.add(
          automatic == 0
              ? "actual-native-purchases-and-paid-enhancements-advance-original-goals"
              : "actual-native-curio-purchases-and-upgrades-advance-original-goals");
      checks.add("original-jades-actually-paid-before-claim-success");
    }
    var duplicate =
        DynamicMessage.newBuilder(protocol.clientSchema().message("TakeQuestRewardCsReq"));
    duplicate.setField(duplicate.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), ids);
    var repeated =
        protocol
            .clientSchema()
            .parse(
                "TakeQuestRewardScRsp",
                raw(
                        protocol.clientCommand("TakeQuestRewardCsReq"),
                        duplicate.build().toByteArray(),
                        protocol.clientCommand("TakeQuestRewardScRsp"))
                    .body());
    require(
        number(repeated, "retcode") != 0
            && ((List<?>) field(repeated, "succ_quest_id_list")).isEmpty()
            && ((List<?>) field((DynamicMessage) field(repeated, "reward"), "item_list")).isEmpty(),
        "Repeated enhancement rewards paid again");
    quests(target);
    checks.add("native-repeat-claims-pay-nothing");
    checks.add("ended-run-count-and-claimed-goals-retained");
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("resume", resume);
    result.put("target", target);
    result.put("automaticCurio", automatic);
    result.put("actualCurioPurchases", curioPurchases);
    result.put("actualPurchases", actualPurchases);
    result.put("actualEnhancements", actualEnhancements);
    result.put("actualExplorations", explorations);
    result.put("questNotifications", progressNotifications);
    result.put("currencyNotifications", currencyNotifications);
    result.put("buffNotifications", buffNotifications);
    result.put("jadeNotifications", jadeNotifications);
    result.put("questQueries", queries);
    result.put("jades", observedJades);
    result.put("originalQuestProgress", questProgress);
    result.put("originalQuestPhases", questPhases);
    result.put("actualPaidOperations", operations);
    result.put("checks", checks);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeEnhancementQuestProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
          new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var credentials = JsonParser.parseString(control.readLine()).getAsJsonObject();
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
              probe.connected.complete(c);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated enhancement frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp c) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp c) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("Enhancement probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials, control)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      // stop() already closes all channels; a second close races the KCP listener cleanup.
      client.stop();
    }
    System.exit(exit);
  }
}
