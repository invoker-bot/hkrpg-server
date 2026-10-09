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

/** Original boxing phone goals through an owned player and the actual packaged UDP/KCP service. */
public final class LoopbackBoxingClubMessagesProbe {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  final Map<String, Object> result = new LinkedHashMap<>();
  final List<String> checks = new ArrayList<>();
  final Deque<Exchange> pending = new ArrayDeque<>();
  static final List<Integer> SECTIONS = List.of(1110000, 1110700, 1301100);
  static final Map<Integer, Integer> GOALS =
      Map.of(1110000, 201070100, 1110700, 201070202, 1301100, 801520201);
  static final Map<Integer, Integer> GROUPS =
      Map.of(1110000, 11100, 1110700, 11107, 1301100, 13011);
  static final Map<Integer, List<Integer>> INITIAL =
      Map.of(
          1110000,
          List.of(111000000),
          1110700,
          List.of(111070000),
          1301100,
          List.of(130110000, 130110001));
  static final Map<Integer, List<Integer>> PARTIAL =
      Map.of(
          1110000, List.of(111000000, 111000001, 111000004, 111000007),
          1110700, List.of(111070000, 111070002, 111070004, 111070005),
          1301100,
              List.of(
                  130110000, 130110001, 130110003, 130110004, 130110005, 130110006, 130110007,
                  130110008));
  static final Map<Integer, List<Integer>> FINISHED =
      Map.of(
          1110000, PARTIAL.get(1110000),
          1110700, List.of(111070000, 111070002, 111070004, 111070005, 111070007, 111070009),
          1301100, PARTIAL.get(1301100));
  static final Map<Integer, List<Integer>> OFFERS =
      Map.of(
          1110000,
          List.of(111000001, 111000002, 111000003),
          1110700,
          List.of(111070001, 111070002),
          1301100,
          List.of(130110002, 130110003));
  String mode;
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

  void add(String name, DynamicMessage.Builder req, Consumer<DynamicMessage> check) {
    pending.add(new Exchange(name, req.build(), name.replace("CsReq", "ScRsp"), check));
  }

  void query(String name, Consumer<DynamicMessage> check) {
    add(name, request(name), check);
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
      result.put("mode", mode);
      result.put("clientGameplayVerified", false);
      completed.complete(result);
    } else send(c, waiting.name(), waiting.request());
  }

  void operation(String name, String key, int id, Retcode code, String label) {
    var req = request(name);
    set(req, key, id);
    add(
        name,
        req,
        m -> check(number(m, "retcode") == code.getVal() && number(m, key) == id, label));
  }

  void choice(int id, Retcode code, String label) {
    operation("FinishItemIdCsReq", "item_id", id, code, label);
  }

  void finish(int id, Retcode code, String label) {
    operation("FinishSectionIdCsReq", "section_id", id, code, label);
  }

  void phone(String phase, Set<Integer> done, String label) {
    var req = request("GetNpcMessageGroupCsReq");
    for (int contact : List.of(2, 12))
      req.addRepeatedField(req.getDescriptorForType().findFieldByName("BJIPBBFIBPD"), contact);
    add(
        "GetNpcMessageGroupCsReq",
        req,
        m -> {
          require(number(m, "retcode") == 0, "Phone query rejected");
          var histories = new TreeMap<Integer, List<Integer>>();
          var offers = new TreeMap<Integer, List<Integer>>();
          for (var value : (List<?>) field(m, "message_group_list")) {
            var group = (DynamicMessage) value;
            for (var raw : (List<?>) field(group, "message_section_list")) {
              var s = (DynamicMessage) raw;
              int id = number(s, "id");
              if (!SECTIONS.contains(id)) continue;
              require(!phase.equals("bootstrap"), "Original phone published before prerequisites");
              if (phase.equals("beforeTalk") && id == 1110700)
                throw new IllegalStateException("Sequence phone published early");
              require(number(group, "id") == GROUPS.get(id), "Wrong original message group");
              var expected =
                  phase.equals("initial") || phase.equals("beforeTalk")
                      ? INITIAL.get(id)
                      : done.contains(id) || phase.equals("answered")
                          ? FINISHED.get(id)
                          : PARTIAL.get(id);
              var history =
                  ((List<?>) field(s, "item_list"))
                      .stream().map(v -> number((DynamicMessage) v, "item_id")).toList();
              require(
                  history.equals(expected) && histories.put(id, history) == null,
                  "Original phone history changed: " + id);
              var offer =
                  done.contains(id) || phase.equals("answered")
                      ? List.<Integer>of()
                      : phase.equals("initial") || phase.equals("beforeTalk")
                          ? OFFERS.get(id)
                          : id == 1110700 ? List.of(111070006, 111070007) : List.<Integer>of();
              require(field(s, "message_item_list").equals(offer), "Wrong offered branch: " + id);
              require(
                  ((Descriptors.EnumValueDescriptor) field(s, "status")).getNumber()
                      == (done.contains(id) ? 2 : 1),
                  "Wrong section phase");
              offers.put(id, offer);
            }
          }
          require(
              histories.size()
                  == (phase.equals("bootstrap") ? 0 : phase.equals("beforeTalk") ? 2 : 3),
              "Missing original phone sections");
          result.put("histories", histories);
          result.put("offered", offers);
          result.put("finishedSections", new TreeSet<>(done));
          checks.add(label);
        });
  }

  void objectives(boolean accepted, Set<Integer> done, String label) {
    query(
        "GetMissionDataCsReq",
        m -> {
          require(number(m, "retcode") == 0, "Mission query rejected");
          var progress = new TreeMap<Integer, Integer>();
          for (var raw : (List<?>) field(m, "mission_list")) {
            var row = (DynamicMessage) raw;
            int id = number(row, "id");
            if (!GOALS.containsValue(id)) continue;
            require(
                accepted && progress.put(id, number(row, "progress")) == null,
                "Unexpected original message goal");
            int section =
                GOALS.entrySet().stream()
                    .filter(e -> e.getValue() == id)
                    .findFirst()
                    .orElseThrow()
                    .getKey();
            require(
                number(row, "progress") == (done.contains(section) ? 1 : 0),
                "Wrong original message progress");
          }
          require(progress.size() == (accepted ? 3 : 0), "Missing accepted original message goals");
          var finished = (List<?>) field(m, "finished_main_mission_id_list");
          require(
              finished.contains(2010702) == done.contains(1110700),
              "Wrong original parent completion");
          require(
              !finished.contains(2010701) && !finished.contains(8015202),
              "Phone completed an unfinished story parent");
          result.put("objectiveProgress", progress);
          checks.add(label);
        });
  }

  void buildPlan() {
    require(
        Set.of("bootstrap", "partial", "partial-resume", "finish", "resume").contains(mode),
        "Unknown phone phase");
    query(
        "GetBagCsReq",
        m -> {
          require(number(m, "retcode") == 0, "Initial bag rejected");
          initialBag = m.toByteString();
        });
    if (mode.equals("bootstrap")) {
      phone("bootstrap", Set.of(), "原始前置未满足时不发布三条拳馆短信");
      objectives(false, Set.of(), "未预写短信目标");
    } else if (mode.equals("partial")) {
      phone("beforeTalk", Set.of(), "原始前置只发布第一季入口和第二季邀请");
      var talk = request("FinishTalkMissionCsReq");
      set(talk, "sub_mission_id", 201070201);
      set(talk, "talk_str", "Mission_201070201");
      add("FinishTalkMissionCsReq", talk, m -> check(number(m, "retcode") == 0, "原始演出完成键接取后续短信目标"));
      phone("initial", Set.of(), "三条原始短信保留起始文本和可选分支");
      objectives(true, Set.of(), "完成接取前置不冒充短信完成");
      for (int id : SECTIONS) finish(id, Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, "尚有选项时不能完成 " + id);
      choice(111000007, Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, "不可越过选择直接提交末尾文本");
      choice(999990041, Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, "未从起始图到达的系统文本拒绝");
      for (int id : List.of(111000001, 111070002, 130110003))
        choice(id, Retcode.SUCC, "原始首组选项保存 " + id);
      phone("partial", Set.of(), "保留三条已回复而未确认完成的原始路径");
      objectives(true, Set.of(), "未确认完成时三个目标保持零");
    } else if (mode.equals("partial-resume")) {
      phone("partial", Set.of(), "重连恢复原始回复路径和第二组未选分支");
      objectives(true, Set.of(), "重连不自动完成已回复短信");
      for (int id : List.of(111000001, 111070002, 130110003))
        choice(id, Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, "已选回复重放拒绝 " + id);
    } else if (mode.equals("finish")) {
      phone("partial", Set.of(), "H2重启恢复已选回复和未确认完成状态");
      objectives(true, Set.of(), "H2重启后未完成目标仍为零");
      finish(1110700, Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, "第二组回复尚未提交时保持未完成");
      choice(111070007, Retcode.SUCC, "原始第二组回复保存");
      var done = new HashSet<Integer>();
      for (int id : SECTIONS) {
        finish(id, Retcode.SUCC, "原始消息图完成确认 " + id);
        done.add(id);
        var after = Set.copyOf(done);
        phone("answered", after, "仅确认过的短信完成且原始历史不变 " + id);
        objectives(true, after, "只推进对应的原始子任务 " + id);
        finish(id, Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, "完成确认不能重复 " + id);
      }
    } else {
      phone("partial", Set.copyOf(SECTIONS), "完成后三条原始历史和确认状态恢复");
      objectives(true, Set.copyOf(SECTIONS), "完成后的任务与原始父任务状态恢复");
      for (int id : SECTIONS)
        finish(id, Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, "重连或H2重启不能重复完成 " + id);
    }
    query(
        "GetBagCsReq",
        m ->
            check(number(m, "retcode") == 0 && m.toByteString().equals(initialBag), "短信操作不修改实际背包"));
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
      var probe = new LoopbackBoxingClubMessagesProbe();
      var input =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.mode = input.get("mode").getAsString();
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
                    new IllegalStateException("Boxing phone probe disconnected"));
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
      System.err.println("Boxing phone probe failed: " + e);
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
