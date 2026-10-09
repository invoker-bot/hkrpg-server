package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.LoopbackPictureProbe.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import kcp.highway.*;

/** Actual original optional dialogue aggregate and monitor over isolated UDP/KCP acceptance. */
public final class LoopbackMissionRelationsProbe {
  static final int MAIN = 1054502, MONITOR = 105450235, WATCHED = 105450232;
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  final Map<String, Object> result = new LinkedHashMap<>();
  final List<String> checks = new ArrayList<>();
  final Deque<Exchange> pending = new ArrayDeque<>();
  boolean resume, bootstrap;
  ByteString initialBag;
  Exchange waiting;

  record Exchange(
      String name, DynamicMessage request, String response, Consumer<DynamicMessage> check) {}

  DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  void send(Ukcp c, String name, DynamicMessage data) {
    var raw =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], data.toByteArray()));
    try {
      c.write(raw);
    } finally {
      raw.release();
    }
  }

  void add(String name, DynamicMessage.Builder req, String rsp, Consumer<DynamicMessage> check) {
    pending.add(new Exchange(name, req.build(), rsp, check));
  }

  void query(String name, Consumer<DynamicMessage> check) {
    add(name, request(name), name.replace("CsReq", "ScRsp"), check);
  }

  void check(boolean valid, String name) {
    require(valid, name);
    checks.add(name);
  }

  void next(Ukcp c) {
    waiting = pending.poll();
    if (waiting == null) {
      result.put("passed", true);
      result.put("checks", checks);
      result.put("clientGameplayVerified", false);
      result.put("savedMonitorRestored", resume);
      completed.complete(result);
    } else send(c, waiting.name(), waiting.request());
  }

  void talk(int id, String text, boolean accepted, String label) {
    var req = request("FinishTalkMissionCsReq");
    set(req, "sub_mission_id", id);
    set(req, "talk_str", text);
    add(
        "FinishTalkMissionCsReq",
        req,
        "FinishTalkMissionScRsp",
        m -> check((number(m, "retcode") == 0) == accepted, label));
  }

  void progress(int watched, int monitor, String label) {
    query(
        "GetMissionDataCsReq",
        m -> {
          require(number(m, "retcode") == 0, "Mission snapshot rejected");
          var found = new HashMap<Integer, DynamicMessage>();
          for (var raw : (List<?>) field(m, "mission_list")) {
            var row = (DynamicMessage) raw;
            int id = number(row, "id");
            if (id == MONITOR || id == WATCHED)
              require(found.put(id, row) == null, "Duplicate monitor objective");
          }
          require(found.size() == 2, "Missing original relation objectives");
          for (int id : List.of(WATCHED, MONITOR)) {
            var row = found.get(id);
            int value = id == WATCHED ? watched : monitor;
            require(number(row, "progress") == value, "Wrong original objective progress " + id);
            String status = ((Descriptors.EnumValueDescriptor) field(row, "status")).getName();
            require(
                status.equals(
                    value == (id == WATCHED ? 2 : 1)
                        ? "MissionStatus_MissionFinish"
                        : "MissionStatus_MissionDoing"),
                "Wrong original objective phase " + id + " " + status);
          }
          require(
              !((List<?>) field(m, "finished_main_mission_id_list")).contains(MAIN),
              "Isolated subset completed the entire official story");
          result.put("watchedProgress", watched);
          result.put("monitorProgress", monitor);
          checks.add(label);
        });
  }

  void buildPlan() {
    if (!resume) {
      var enter = request("EnterSceneCsReq");
      set(enter, "entry_id", 1055101);
      add(
          "EnterSceneCsReq",
          enter,
          "EnterSceneScRsp",
          m -> check(number(m, "retcode") == 0, "进入原始1055101场景"));
    }
    query(
        "GetCurSceneInfoCsReq",
        m -> {
          var scene = (DynamicMessage) field(m, "scene");
          require(
              number(m, "retcode") == 0
                  && number(scene, "entry_id") == 1055101
                  && number(scene, "plane_id") == 10551
                  && number(scene, "floor_id") == 10551001
                  && number(scene, "leader_entity_id") > 0,
              "Wrong original monitor place or actor");
          result.put("entry", 1055101);
          checks.add("当前原始楼层与实际角色确认");
        });
    query(
        "GetBagCsReq",
        m -> {
          require(number(m, "retcode") == 0, "Initial inventory rejected");
          initialBag = m.toByteString();
          checks.add("实际库存初始快照");
        });
    if (bootstrap) return;
    if (resume) {
      progress(2, 1, "恢复原始汇总与监视完成状态");
      talk(MONITOR, "Mission_" + MONITOR, false, "保存后直接完成监视目标拒绝");
      talk(105450234, "Mission_105450234", false, "保存后重复原始对话拒绝");
      progress(2, 1, "重复请求没有改变完成状态");
    } else {
      var accept = request("AcceptMainMissionCsReq");
      set(accept, "main_mission_id", MAIN);
      add(
          "AcceptMainMissionCsReq",
          accept,
          "AcceptMainMissionScRsp",
          m -> check(number(m, "retcode") == 0, "隔离接取前置生效"));
      progress(0, 0, "原始汇总与监视目标初始未完成");
      talk(MONITOR, "Mission_" + MONITOR, false, "客户端不能直接完成监视目标");
      progress(0, 0, "直接回报拒绝后仍未推进");
      talk(105450230, "Mission_105450230", true, "原始前导对话230完成");
      progress(0, 0, "前导对话不替代两个可选目标");
      talk(105450231, "Mission_105450231", true, "原始可选对话231完成");
      progress(1, 0, "一个可选对话只推进汇总一半");
      talk(105450234, "wrong-original-dialogue", false, "错误对话不能完成另一个目标");
      progress(1, 0, "错误对话后汇总与监视保持");
      talk(105450234, "Mission_105450234", true, "第二个原始可选对话234完成");
      progress(2, 1, "原始汇总完成后监视目标推进一次");
      talk(105450234, "Mission_105450234", false, "首次会话重复对话拒绝");
      progress(2, 1, "重复对话不增加进度或重开目标");
    }
    query(
        "GetBagCsReq",
        m ->
            check(number(m, "retcode") == 0 && m.toByteString().equals(initialBag), "剧情子集未制造背包奖励"));
  }

  void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated message frame");
      String name =
          List.of("PlayerGetTokenScRsp", "PlayerLoginScRsp", "PlayerLoginFinishScRsp").stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name != null) {
        var m = protocol.clientSchema().parse(name, frame.body());
        require(number(m, "retcode") == 0, "Login rejected");
        switch (name) {
          case "PlayerGetTokenScRsp" -> {
            result.put("uid", number(m, "uid"));
            send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq").build());
          }
          case "PlayerLoginScRsp" -> {
            int wallet = number((DynamicMessage) field(m, "basic_info"), "scoin");
            require(wallet == 0, "Unexpected fixture wallet");
            result.put("wallet", wallet);
            send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq").build());
          }
          case "PlayerLoginFinishScRsp" -> {
            buildPlan();
            next(c);
          }
        }
      } else if (waiting != null && protocol.clientCommand(waiting.response()) == frame.cmdId()) {
        var m = protocol.clientSchema().parse(waiting.response(), frame.body());
        require(m.getUnknownFields().asMap().isEmpty(), "Unknown message response fields");
        System.err.println("Message check=" + waiting.name() + " ordinal=" + checks.size());
        waiting.check().accept(m);
        next(c);
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackMissionRelationsProbe();
      var input =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = input.get("resume").getAsBoolean();
      probe.bootstrap = input.get("bootstrap").getAsBoolean();
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", input.get("accountUid").getAsString());
      set(token, "token", input.get("comboToken").getAsString());
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
              probe.send(c, "PlayerGetTokenCsReq", token.build());
            }

            public void handleReceive(ByteBuf b, Ukcp c) {
              try {
                probe.receive(b, c);
              } catch (Throwable e) {
                probe.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              probe.completed.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Mission relation probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(30, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println("Mission relation probe failed: " + e);
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
