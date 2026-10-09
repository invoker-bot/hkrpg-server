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
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Test-only native connection; it never edits server state or completes achievements directly. */
public final class NativeTalentQuestProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private Map<Integer, Integer> costs = new TreeMap<>();
  private int current,
      observedPoints = -1,
      observedJades = -1,
      pointNotifications,
      questNotifications,
      jadeNotifications,
      queries,
      activations;
  private boolean awaitActivation, awaitClaim;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static int status(DynamicMessage m) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(m, "status")).getNumber();
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private void check(boolean ok, String reason) {
    require(ok, reason);
    checks.add(reason);
  }

  private int expectedPoints() {
    return 100000
        - costs.entrySet().stream()
            .filter(e -> e.getKey() <= current)
            .mapToInt(Map.Entry::getValue)
            .sum();
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int code) throws Exception {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Native talent connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      if (f.command() == protocol.clientCommand("SyncRogueVirtualItemInfoScNotify")
          && awaitActivation) {
        var m = protocol.clientSchema().parse("SyncRogueVirtualItemInfoScNotify", f.body());
        observedPoints = number((DynamicMessage) field(m, "NIKDPODBIEF"), "OFHBBKOKANF");
        require(
            observedPoints == expectedPoints(),
            "Actual source talent debit differs from original price");
        pointNotifications++;
      }
      if (f.command() == protocol.clientCommand("PlayerSyncScNotify") && awaitClaim) {
        var m = protocol.clientSchema().parse("PlayerSyncScNotify", f.body());
        if (m.hasField(m.getDescriptorForType().findFieldByName("basic_info"))) {
          int jades = number((DynamicMessage) field(m, "basic_info"), "hcoin");
          require(jades == 5 || jades == 10, "Actual original jade payment differs");
          observedJades = jades;
          jadeNotifications++;
        }
      }
      if (f.command() == protocol.clientCommand("GetQuestDataScRsp") && awaitActivation) {
        var m = protocol.clientSchema().parse("GetQuestDataScRsp", f.body());
        var rows = targetRows(m);
        if (rows.size() == 2
            && number(rows.get(4092316), "progress") == Math.min(2, current)
            && number(rows.get(4092317), "progress") == current) {
          validateQuests(m, current, false);
          questNotifications++;
        }
      }
      if (f.command() != protocol.clientCommand(response)) continue;
      var m = protocol.clientSchema().parse(response, f.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown talent response fields");
      require(
          number(m, "retcode") == code,
          "Unexpected " + response + " retcode=" + number(m, "retcode") + " expected=" + code);
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private Map<Integer, DynamicMessage> targetRows(DynamicMessage m) {
    var rows = new TreeMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(m, "quest_list")) {
      var r = (DynamicMessage) raw;
      int id = number(r, "id");
      if (id == 4092316 || id == 4092317)
        require(rows.put(id, r) == null, "Duplicate original achievement identity");
    }
    return rows;
  }

  private void validateQuests(DynamicMessage m, int count, boolean closed) {
    var rows = targetRows(m);
    require(rows.size() == 2, "Original source talent achievements are absent");
    for (int id : List.of(4092316, 4092317)) {
      int goal = id == 4092316 ? 2 : 26;
      var r = rows.get(id);
      int phase = closed ? 3 : count >= goal ? 2 : 1;
      require(
          number(r, "progress") == Math.min(goal, count) && status(r) == phase,
          "Wrong original talent progress or phase");
      require(
          ((Long) field(r, "finish_time") > 0) == (phase != 1),
          "Wrong original talent completion time");
    }
  }

  private void quests(int count, boolean closed) throws Exception {
    validateQuests(call("GetQuestDataCsReq"), count, closed);
    queries++;
  }

  private void talents(DynamicMessage m, int count) {
    var data = (DynamicMessage) field(m, "JCOBJAMPOAL");
    var rows = (List<?>) field(data, "BLHJIPBHHFO");
    require(rows.size() == 42, "Source ability tree does not have its 42 original nodes");
    var enabled = new TreeSet<Integer>();
    var ids = new HashSet<Integer>();
    for (var raw : rows) {
      var r = (DynamicMessage) raw;
      int id = number(r, "CKHCLKOALKE");
      require(id > 0 && ids.add(id), "Duplicate source talent");
      if (field(r, "status").toString().equals("RogueTalentStatus_Enable")) enabled.add(id);
    }
    var expected = new TreeSet<Integer>();
    for (int i = 1; i <= count; i++) expected.add(i);
    require(
        enabled.equals(expected), "Actual ability tree ownership differs from native activations");
  }

  private void points() throws Exception {
    var info = (DynamicMessage) field(call("GetRogueInfoCsReq"), "rogue_game_info");
    int balance =
        number(
            (DynamicMessage) field((DynamicMessage) field(info, "LFLCDNGALME"), "NIKDPODBIEF"),
            "GEBGGMAGDJB");
    require(balance == expectedPoints(), "Source talent wallet did not restore");
    observedPoints = balance;
  }

  private Map<String, Object> execute(JsonObject credentials, java.io.BufferedReader control)
      throws Exception {
    boolean resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
    credentials
        .getAsJsonObject("costs")
        .entrySet()
        .forEach(e -> costs.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
    require(
        costs.size() == 26 && costs.values().stream().allMatch(n -> n > 0),
        "Original talent costs missing");
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1),
            0);
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    int loginJades = number((DynamicMessage) field(login, "basic_info"), "hcoin");
    require(loginJades == (resume ? 10 : 0), "Native login wallet is wrong");
    observedJades = loginJades;
    call("PlayerLoginFinishCsReq");
    current = resume ? 26 : 0;
    quests(current, resume);
    talents(call("GetRogueTalentInfoCsReq"), current);
    if (!resume) {
      System.out.println("HKRPG_TALENT_BUDGET_READY:" + uid);
      System.out.flush();
      require("resume".equals(control.readLine()), "Isolated starting budget was not acknowledged");
      long budgetDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      while (true) {
        var info = (DynamicMessage) field(call("GetRogueInfoCsReq"), "rogue_game_info");
        int balance =
            number(
                (DynamicMessage) field((DynamicMessage) field(info, "LFLCDNGALME"), "NIKDPODBIEF"),
                "GEBGGMAGDJB");
        if (balance == 100000) break;
        require(
            balance == 0 && System.nanoTime() < budgetDeadline,
            "Isolated actual Item32 grant did not reach its exact budget");
        Thread.sleep(50);
      }
      quests(0, false);
      points();
      for (current = 1; current <= 26; current++) {
        int p = pointNotifications, q = questNotifications;
        awaitActivation = true;
        var response = call("EnableRogueTalentCsReq", Map.of("CKHCLKOALKE", current), 0);
        awaitActivation = false;
        activations++;
        talents(response, current);
        require(
            pointNotifications == p + 1 && questNotifications == q + 1,
            "Actual debit and achievement notification did not arrive before activation success");
        if (current == 1 || current == 26) {
          var retry =
              call(
                  "EnableRogueTalentCsReq",
                  Map.of("CKHCLKOALKE", current),
                  Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal());
          talents(retry, current);
        }
      }
      current = 26;
      quests(26, false);
      points();
      check(activations == 26, "twenty-six-actual-source-priced-activations");
      awaitClaim = true;
      var reward =
          call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", List.of(4092316, 4092317)), 0);
      awaitClaim = false;
      require(
          field(reward, "succ_quest_id_list").equals(List.of(4092316, 4092317)),
          "Wrong actually paid achievement identities");
      var actual = (List<?>) field((DynamicMessage) field(reward, "reward"), "item_list");
      int amount = 0;
      for (var raw : actual) {
        var rewardItem = (DynamicMessage) raw;
        require(
            number(rewardItem, "item_id") == 1 && number(rewardItem, "num") == 5,
            "Unauthored achievement reward");
        amount += number(rewardItem, "num");
      }
      check(
          actual.size() == 2 && amount == 10 && observedJades == 10 && jadeNotifications == 2,
          "two-original-five-jade-payments-notified-before-success");
    } else {
      points();
      talents(
          call(
              "EnableRogueTalentCsReq",
              Map.of("CKHCLKOALKE", 1),
              Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()),
          26);
      check(true, "same-uid-native-login-wallet-tree-and-goals-restored");
    }
    var duplicate =
        call(
            "TakeQuestRewardCsReq",
            Map.of("ODJIJFJCHHB", List.of(4092316, 4092317)),
            Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal());
    check(
        ((List<?>) field(duplicate, "succ_quest_id_list")).isEmpty()
            && ((List<?>) field((DynamicMessage) field(duplicate, "reward"), "item_list"))
                .isEmpty(),
        "duplicate-native-claims-pay-nothing");
    quests(26, true);
    talents(call("GetRogueTalentInfoCsReq"), 26);
    points();
    check(true, "native-final-query-retains-two-closed-original-goals");
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("resume", resume);
    result.put("checks", checks);
    result.put("actualActivations", activations);
    result.put("talentPoints", observedPoints);
    result.put("jades", observedJades);
    result.put("questNotifications", questNotifications);
    result.put("currencyNotifications", pointNotifications);
    result.put("jadeNotifications", jadeNotifications);
    result.put("questQueries", queries);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeTalentQuestProbe();
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
                  require(frame != null, "Truncated talent frame");
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
                  null, new IllegalStateException("Talent probe disconnected"));
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
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
