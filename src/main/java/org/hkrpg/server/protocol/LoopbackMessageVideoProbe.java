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

/** Original video message graphs through isolated acceptance rules and actual packaged UDP/KCP. */
public final class LoopbackMessageVideoProbe {
  static final int MAIN = 199936;
  static final List<Integer> SECTIONS = List.of(1219000, 1219100, 1504201);
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  final Map<String, Object> result = new LinkedHashMap<>();
  final List<String> checks = new ArrayList<>();
  final Deque<Exchange> pending = new ArrayDeque<>();
  final Map<Integer, List<Integer>> paths = new TreeMap<>(),
      reports = new TreeMap<>(),
      initial = new TreeMap<>(),
      offeredItems = new TreeMap<>();
  final Map<Integer, Integer> groups = Map.of(1219000, 12190, 1219100, 12191, 1504201, 15042),
      videos = Map.of(1219000, 121900015, 1219100, 121910005, 1504201, 150420110);
  boolean resume;
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
      result.put("savedVideoReportsRestored", resume);
      completed.complete(result);
    } else send(c, waiting.name(), waiting.request());
  }

  DynamicMessage.Builder data(int section, List<Integer> values) {
    var data = request("FPDBFGBJOFD");
    set(data, "MGPGDLIJKLM", section);
    for (int id : values) {
      var item = request("MessageItem");
      set(item, "item_id", id);
      data.addRepeatedField(
          data.getDescriptorForType().findFieldByName("DPPAFBAHEFH"), item.build());
    }
    return data;
  }

  void performance(int section, List<Integer> values, boolean accepted, String label) {
    var req = request("FinishPerformSectionIdCsReq");
    set(req, "HBMLBLAJKKL", data(section, values).build());
    add(
        "FinishPerformSectionIdCsReq",
        req,
        "FinishPerformSectionIdScRsp",
        m -> {
          require(
              number(m, "retcode")
                  == (accepted ? 0 : Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal()),
              "Wrong video report result: " + label + " actual=" + number(m, "retcode"));
          if (accepted) {
            var echo = (DynamicMessage) field(m, "MIINDKFMBBM");
            require(number(echo, "MGPGDLIJKLM") == section, "Wrong completed section");
            require(
                ((List<?>) field(echo, "DPPAFBAHEFH"))
                    .stream()
                        .map(v -> number((DynamicMessage) v, "item_id"))
                        .toList()
                        .equals(values),
                "Wrong completion records");
          }
          checks.add(label);
        });
  }

  void phone(Set<Integer> finished, String label) {
    var req = request("GetNpcMessageGroupCsReq");
    for (int contact : List.of(105, 222))
      req.addRepeatedField(req.getDescriptorForType().findFieldByName("BJIPBBFIBPD"), contact);
    add(
        "GetNpcMessageGroupCsReq",
        req,
        "GetNpcMessageGroupScRsp",
        m -> {
          require(number(m, "retcode") == 0, "Phone query rejected");
          var found = new TreeMap<Integer, List<Integer>>();
          for (var value : (List<?>) field(m, "message_group_list")) {
            var group = (DynamicMessage) value;
            for (var raw : (List<?>) field(group, "message_section_list")) {
              var section = (DynamicMessage) raw;
              int id = number(section, "id");
              if (!SECTIONS.contains(id)) continue;
              require(number(group, "id") == groups.get(id), "Wrong original group");
              var history =
                  ((List<?>) field(section, "item_list"))
                      .stream().map(v -> number((DynamicMessage) v, "item_id")).toList();
              require(found.put(id, history) == null, "Duplicate section");
              boolean done = finished.contains(id);
              require(
                  history.equals(done ? paths.get(id) : initial.get(id)),
                  "Original video history changed: " + id);
              require(
                  ((Descriptors.EnumValueDescriptor) field(section, "status")).getNumber()
                      == (done ? 2 : 1),
                  "Wrong section phase");
              var offered = (List<?>) field(section, "message_item_list");
              require(
                  offered.equals(done ? List.of() : offeredItems.get(id)),
                  "Wrong pending video/choice");
            }
          }
          require(found.size() == 3, "Missing accepted video sections");
          result.put("histories", found);
          result.put("finishedSections", new TreeSet<>(finished));
          checks.add(label);
        });
  }

  void objectives(Set<Integer> finished, String label) {
    query(
        "GetMissionDataCsReq",
        m -> {
          var found = new HashSet<Integer>();
          for (var value : (List<?>) field(m, "mission_list")) {
            var row = (DynamicMessage) value;
            int id = number(row, "id");
            if (SECTIONS.contains(id)) {
              require(found.add(id), "Duplicate message objective");
              require(
                  number(row, "progress") == (finished.contains(id) ? 1 : 0),
                  "Video omission advanced objective");
            }
          }
          require(found.size() == 3, "Missing acceptance objectives");
          require(
              ((List<?>) field(m, "finished_main_mission_id_list")).contains(MAIN)
                  == (finished.size() == 3),
              "Parent completion changed");
          result.put("objectiveProgress", finished.size());
          checks.add(label);
        });
  }

  void completedSections() {
    query(
        "GetMissionMessageCsReq",
        m -> {
          require(number(m, "retcode") == 0, "Mission message query rejected");
          check(((List<?>) field(m, "HNIPIODEBNN")).containsAll(SECTIONS), "已完成剧情目录包含三个原始段落");
        });
  }

  void buildPlan() {
    query(
        "GetCurSceneInfoCsReq",
        m -> {
          var scene = (DynamicMessage) field(m, "scene");
          check(
              number(m, "retcode") == 0
                  && number(scene, "entry_id") > 0
                  && number(scene, "leader_entity_id") > 0,
              "实际场景与角色已建立");
        });
    query(
        "GetBagCsReq",
        m -> {
          require(number(m, "retcode") == 0, "Initial bag query rejected");
          initialBag = m.toByteString();
          checks.add("消息提交前实际背包快照");
        });
    if (resume) {
      var done = Set.copyOf(SECTIONS);
      phone(done, "重连恢复原始分支和视频历史");
      objectives(done, "重连恢复三个完成目标");
      completedSections();
      for (int id : SECTIONS) performance(id, reports.get(id), false, "保存后的重复剧情拒绝 " + id);
      phone(done, "重复提交不修改保存历史");
    } else {
      var accept = request("AcceptMainMissionCsReq");
      set(accept, "main_mission_id", MAIN);
      add(
          "AcceptMainMissionCsReq",
          accept,
          "AcceptMainMissionScRsp",
          m -> check(number(m, "retcode") == 0 && number(m, "main_mission_id") == MAIN, "隔离前置已接受"));
      phone(Set.of(), "视频未回报前不写入完成历史");
      objectives(Set.of(), "三个视频目标初始进度为零");
      var batch = request("FinishMultiPerformSectionIdCsReq");
      var list = batch.getDescriptorForType().findFieldByName("CGKBNIMCFKH");
      batch.addRepeatedField(list, data(1219000, reports.get(1219000)).build());
      batch.addRepeatedField(list, data(1219100, List.of()).build());
      add(
          "FinishMultiPerformSectionIdCsReq",
          batch,
          "FinishMultiPerformSectionIdScRsp",
          m ->
              check(
                  number(m, "PLDKENJLKME") == Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),
                  "批量缺视频整批拒绝"));
      phone(Set.of(), "失败整批没有保存首个段落");
      var done = new HashSet<Integer>();
      for (int id : SECTIONS) {
        var before = Set.copyOf(done);
        var omitted = reports.get(id).stream().filter(v -> !v.equals(videos.get(id))).toList();
        performance(id, omitted, false, "缺少原始视频记录拒绝 " + id);
        phone(before, "缺视频后历史不变 " + id);
        objectives(before, "缺视频后目标不变 " + id);
        var wrong = new ArrayList<>(reports.get(id));
        wrong.set(
            wrong.indexOf(videos.get(id)),
            videos.get(SECTIONS.stream().filter(v -> v != id).findFirst().orElseThrow()));
        performance(id, wrong, false, "其他段落视频不能替代 " + id);
        performance(id, reports.get(id), true, "原始顺序和视频记录接受 " + id);
        done.add(id);
        var after = Set.copyOf(done);
        phone(after, "完成历史精确等于原始路径 " + id);
        objectives(after, "仅对应目标完成 " + id);
        performance(id, reports.get(id), false, "首次会话重复剧情拒绝 " + id);
      }
      completedSections();
    }
    query(
        "GetBagCsReq",
        m -> {
          check(number(m, "retcode") == 0 && m.toByteString().equals(initialBag), "消息回报没有修改实际背包");
        });
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
      var probe = new LoopbackMessageVideoProbe();
      var input =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = input.get("resume").getAsBoolean();
      for (int id : SECTIONS) {
        for (var binding :
            List.of(
                Map.entry("paths", probe.paths),
                Map.entry("reports", probe.reports),
                Map.entry("initial", probe.initial),
                Map.entry("offered", probe.offeredItems))) {
          var values = new ArrayList<Integer>();
          for (var v : input.getAsJsonObject(binding.getKey()).getAsJsonArray(Integer.toString(id)))
            values.add(v.getAsInt());
          binding.getValue().put(id, List.copyOf(values));
        }
      }
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
                    new IllegalStateException("Video probe disconnected"));
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
      System.err.println("Video message probe failed: " + e);
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
