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

/** Synthetic native reports and source achievement claims; never launches the game client. */
public final class LoopbackDiceMainPageProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private int currentJade;
  private int currencyUpdates;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  private void check(boolean value, String reason) {
    require(value, reason);
    checks.add(reason);
  }

  private DynamicMessage.Builder request(String name, Map<String, Object> values) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    values.forEach((k, v) -> b.setField(b.getDescriptorForType().findFieldByName(k), v));
    return b;
  }

  private DynamicMessage call(String name, Map<String, Object> values, int code) throws Exception {
    return rawCall(name, request(name, values).build().toByteArray(), code);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private DynamicMessage rawCall(String name, byte[] body, int code) throws Exception {
    var packet =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Dice main-page KCP failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var sync = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        if (sync.hasField(sync.getDescriptorForType().findFieldByName("basic_info"))) {
          currentJade = number((DynamicMessage) field(sync, "basic_info"), "hcoin");
          currencyUpdates++;
        }
      }
      if (frame.command() != protocol.clientCommand(response)) continue;
      var m = protocol.clientSchema().parse(response, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown native response fields");
      String key = response.equals("FinishQuestScRsp") ? "PLDKENJLKME" : "retcode";
      require(number(m, key) == code, response + " expected=" + code + " actual=" + number(m, key));
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage quest(int progress, String phase) throws Exception {
    var all = call("GetQuestDataCsReq");
    var rows =
        ((List<?>) field(all, "quest_list"))
            .stream().map(x -> (DynamicMessage) x).filter(x -> number(x, "id") == 4055044).toList();
    if (phase.equals("absent")) {
      check(rows.isEmpty(), "unearned-source-quest-absent");
      return null;
    }
    require(rows.size() == 1, "Source quest missing or duplicated");
    var q = rows.getFirst();
    check(number(q, "progress") == progress, "source-quest-progress-" + progress);
    check(
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(q, "status")).getNumber()
            == switch (phase) {
              case "QUEST_DOING" -> 1;
              case "QUEST_FINISH" -> 2;
              case "QUEST_CLOSE" -> 3;
              default -> -1;
            },
        "source-quest-phase-" + phase);
    return q;
  }

  private void claim(int code) throws Exception {
    var b = request("TakeQuestRewardCsReq", Map.of());
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), 4055044);
    var reply = rawCall("TakeQuestRewardCsReq", b.build().toByteArray(), code);
    check(
        field(reply, "succ_quest_id_list").equals(code == 0 ? List.of(4055044) : List.of()),
        "native-reward-success-list-" + code);
    if (code == 0) {
      var reward = (DynamicMessage) field(reply, "reward");
      var items = (List<?>) field(reward, "item_list");
      check(
          items.size() == 1
              && number((DynamicMessage) items.getFirst(), "item_id") == 1
              && number((DynamicMessage) items.getFirst(), "num") == 5,
          "native-source-five-jade-reward-body");
    }
    check(true, "source-five-jade-claim-code-" + code);
  }

  private int jade() throws Exception {
    call("GetBagCsReq");
    return currentJade;
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    int uid =
        number(
            call(
                "PlayerGetTokenCsReq",
                Map.of(
                    "account_uid",
                    input.get("accountUid").getAsString(),
                    "token",
                    input.get("comboToken").getAsString(),
                    "platform",
                    1),
                0),
            "uid");
    var login = call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    int before = number((DynamicMessage) field(login, "basic_info"), "hcoin");
    currentJade = before;
    String mode = input.get("mode").getAsString();
    int count = 0;
    if (mode.equals("bootstrap")) {
      quest(0, "absent");
      for (var hex : List.of("0801", "08", "00", "0a00", "0b0c", "4001")) {
        rawCall(
            "DiceCombatMainPageRollDiceCsReq",
            HexFormat.of().parseHex(hex),
            Retcode.REQ_PARA_INVALID.getVal());
        check(true, "empty-native-request-rejects-" + hex);
      }
      claim(Retcode.QUEST_NOT_ACCEPT.getVal());
      check(jade() == before, "invalid-reports-preserve-jade");
    } else if (mode.equals("first") || mode.equals("finish")) {
      int start = mode.equals("first") ? 0 : 10;
      quest(start, "QUEST_DOING");
      require(before == input.get("initialHcoin").getAsInt(), "Pre-reward jade changed");
      for (int n = start + 1; n <= start + 10; n++) {
        if (n > start + 1) Thread.sleep(1550);
        rawCall("DiceCombatMainPageRollDiceCsReq", new byte[0], 0);
        check(true, "accepted-native-empty-roll-" + n);
        if (n == 1) {
          rawCall("DiceCombatMainPageRollDiceCsReq", new byte[0], Retcode.SYSTEM_BUSY.getVal());
          check(true, "immediate-repeat-does-not-count");
        }
      }
      count = start + 10;
      quest(count, count == 20 ? "QUEST_FINISH" : "QUEST_DOING");
      claim(count == 20 ? 0 : Retcode.QUEST_NOT_FINISH.getVal());
      if (count == 20) {
        check(currencyUpdates > 0, "native-player-currency-update-arrived");
        check(jade() == before + 5, "source-five-jade-actually-arrived");
        claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal());
        quest(20, "QUEST_CLOSE");
        check(jade() == before + 5, "duplicate-claim-keeps-five-jade");
      } else check(jade() == before, "ten-rolls-do-not-pay");
    } else if (mode.equals("resume")) {
      count = 10;
      quest(10, "QUEST_DOING");
      claim(Retcode.QUEST_NOT_FINISH.getVal());
      check(jade() == input.get("initialHcoin").getAsInt(), "reconnect-keeps-unpaid-counter");
    } else if (mode.equals("closed")) {
      count = 20;
      quest(20, "QUEST_CLOSE");
      rawCall("DiceCombatMainPageRollDiceCsReq", new byte[0], Retcode.SHOP_NOT_OPEN.getVal());
      check(true, "closed-window-cannot-count-roll");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal());
      check(
          jade() == input.get("initialHcoin").getAsInt() + 5, "normal-H2-restart-keeps-five-jade");
    } else throw new IllegalArgumentException("Unknown fixture mode");
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("mode", mode);
    result.put("checks", checks);
    result.put("reportedCount", count);
    result.put("initialHcoin", before);
    result.put("hcoin", jade());
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new LoopbackDiceMainPageProbe();
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
                  require(frame != null, "Truncated dice main-page frame");
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
                  null, new IllegalStateException("Dice main-page probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials)) + "\n").getBytes(StandardCharsets.UTF_8));
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
