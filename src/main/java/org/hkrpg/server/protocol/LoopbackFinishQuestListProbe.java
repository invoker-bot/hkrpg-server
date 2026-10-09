package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
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

/**
 * Actual original client-report quest completion advances the original story objective before the
 * response.
 */
public final class LoopbackFinishQuestListProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private boolean completionNotify;

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

  private DynamicMessage.Builder request(String name, Map<String, Object> fields) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    return b;
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int code) throws Exception {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name),
                new byte[0],
                request(name, fields).build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    int command = protocol.clientCommand(response);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("KCP connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      observe(frame);
      if (frame.command() != command) continue;
      var m = protocol.clientSchema().parse(response, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      String codeField = response.equals("FinishQuestScRsp") ? "PLDKENJLKME" : "retcode";
      require(
          number(m, codeField) == code,
          "Response rejected: " + response + " code=" + number(m, codeField));
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private void observe(Frame frame) throws Exception {
    if (frame.command() != protocol.clientCommand("PlayerSyncScNotify")) return;
    var packet = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
    require(packet.getUnknownFields().asMap().isEmpty(), "Unknown sync fields");
    var field = packet.getDescriptorForType().findFieldByName("mission_sync");
    if (!packet.hasField(field)) return;
    var missions = (DynamicMessage) packet.getField(field);
    for (var raw : (List<?>) field(missions, "mission_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "id") == 401014301
          && number(row, "progress") == 1
          && ((EnumValueDescriptor) field(row, "status")).getNumber() == 2) completionNotify = true;
    }
  }

  private void objective(int progress, String label) throws Exception {
    var data = call("GetMissionDataCsReq");
    var rows =
        ((List<?>) field(data, "mission_list"))
            .stream()
                .map(DynamicMessage.class::cast)
                .filter(row -> number(row, "id") == 401014301)
                .toList();
    check(
        rows.size() == 1
            && number(rows.getFirst(), "progress") == progress
            && ((EnumValueDescriptor) field(rows.getFirst(), "status")).getNumber()
                == (progress == 1 ? 2 : 1),
        label);
    check(
        !((List<?>) field(data, "finished_main_mission_id_list")).contains(4010143),
        "later-original-message-and-dialogue-remain-unfinished");
  }

  private void quest(int status, String label) throws Exception {
    var data = call("GetQuestDataCsReq");
    var rows =
        ((List<?>) field(data, "quest_list"))
            .stream()
                .map(DynamicMessage.class::cast)
                .filter(row -> number(row, "id") == 4045407)
                .toList();
    check(
        rows.size() == 1
            && number(rows.getFirst(), "progress") == (status == 1 ? 0 : 1)
            && ((EnumValueDescriptor) field(rows.getFirst(), "status")).getNumber() == status,
        label);
  }

  private Map<String, Object> execute(JsonObject credentials) throws Exception {
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
    call("PlayerLoginFinishCsReq");
    check(number((DynamicMessage) field(login, "basic_info"), "scoin") == 0, "unchanged-wallet");
    var bag = call("GetBagCsReq").toByteString();
    boolean resume = credentials.get("resume").getAsBoolean();
    if (!resume) {
      call("AcceptMainMissionCsReq", Map.of("main_mission_id", 4010143), 0);
      check(true, "isolated-original-main-acceptance");
      quest(1, "original-client-report-quest-is-accepted-doing");
      objective(0, "original-list-objective-is-pending");
      call(
          "FinishTalkMissionCsReq",
          Map.of("sub_mission_id", 401014301, "talk_str", "Mission_401014301"),
          Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal());
      check(true, "list-objective-cannot-be-completed-as-talk");
      call("FinishQuestCsReq", Map.of("quest_id", 2200801), Retcode.QUEST_STATUS_ERROR.getVal());
      check(true, "non-client-finish-rule-cannot-be-forged");
      objective(0, "rejected-reports-keep-list-pending");
      completionNotify = false;
      call("FinishQuestCsReq", Map.of("quest_id", 4045407), 0);
      check(completionNotify, "actual-mission-completion-notify-arrived-before-quest-response");
    }
    quest(2, "actual-completed-original-quest-history");
    objective(1, "original-quest-completion-advances-story-list");
    call("FinishQuestCsReq", Map.of("quest_id", 4045407), Retcode.QUEST_STATUS_ERROR.getVal());
    check(true, "completed-client-report-quest-not-replayed");
    objective(1, "duplicate-report-keeps-original-list-completed");
    check(
        call("GetBagCsReq").toByteString().equals(bag),
        "completion-does-not-claim-or-repeat-inventory-rewards");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("resume", resume);
    result.put("checks", checks);
    result.put("mission", 4010143);
    result.put("objective", 401014301);
    result.put("quest", 4045407);
    result.put("progress", 1);
    result.put("immediateCompletionNotifyVerified", !resume && completionNotify);
    result.put("wholeOfficialStoryVerified", false);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new LoopbackFinishQuestListProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
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
                  require(frame != null, "Truncated mission frame");
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
                  null, new IllegalStateException("Mission probe disconnected"));
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
