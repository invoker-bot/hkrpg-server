package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Reads, claims and deletes isolated mail through the packaged server's actual UDP/KCP path. */
public final class LoopbackMailProbe {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  final Map<String, Object> result = new LinkedHashMap<>();
  final List<String> checks = new ArrayList<>();
  String mode;
  int step, mailId, wallet, initialWallet, expectedWallet;
  Set<Integer> expectedGear = Set.of();

  static Object field(DynamicMessage value, String name) {
    return value.getField(value.getDescriptorForType().findFieldByName(name));
  }

  static int number(DynamicMessage value, String name) {
    return (Integer) field(value, name);
  }

  static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(message.getDescriptorForType().findFieldByName(name), value);
  }

  void send(Ukcp connection, String name, byte[] body, int next) {
    step = next;
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      connection.write(bytes);
    } finally {
      bytes.release();
    }
  }

  void query(Ukcp connection, String name, int next) {
    send(connection, name, request(name).build().toByteArray(), next);
  }

  void mailRequest(Ukcp connection, String name, int next, int optional) {
    var message = request(name);
    String id =
        name.equals("MarkReadMailCsReq")
            ? "id"
            : name.equals("DelMailCsReq") ? "id_list" : "mail_id_list";
    var field = message.getDescriptorForType().findFieldByName(id);
    if (field.isRepeated()) message.addRepeatedField(field, mailId);
    else message.setField(field, mailId);
    if (optional != 0) set(message, "optional_reward_id", optional);
    send(connection, name, message.build().toByteArray(), next);
  }

  Map<Integer, Integer> rewards(DynamicMessage list) {
    var amounts = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(list, "item_list")) {
      var item = (DynamicMessage) raw;
      require(item.getUnknownFields().asMap().isEmpty(), "Unknown actual attachment fields");
      amounts.merge(number(item, "item_id"), number(item, "num"), Math::addExact);
    }
    return amounts;
  }

  void mail(DynamicMessage message, boolean claimed) {
    var rows = (List<?>) field(message, "mail_list");
    require(rows.size() == 1 && number(message, "total_num") == 1, "Isolated mail count changed");
    var row = (DynamicMessage) rows.getFirst();
    require(field(row, "title").equals("领取状态网络夹具"), "Wrong source of isolated mail");
    mailId = number(row, "id");
    require(mailId > 0, "Invalid mail ID");
    require((boolean) field(row, "is_read") == (claimed || step != 1), "Read status changed");
    require(
        rewards((DynamicMessage) field(row, "attachment"))
            .equals(claimed ? Map.of() : Map.of(2, 1000, 235, 3, 20000, 2)),
        "Claimable attachment quantity changed");
    result.put("mailId", mailId);
  }

  void bag(DynamicMessage message, boolean claimed) {
    int stock = 0;
    for (var raw : (List<?>) field(message, "material_list")) {
      var pile = (DynamicMessage) raw;
      if (number(pile, "tid") == 235) stock += number(pile, "num");
    }
    var gear = new TreeSet<Integer>();
    for (var raw : (List<?>) field(message, "equipment_list")) {
      var item = (DynamicMessage) raw;
      if (number(item, "tid") == 20000) {
        int uid = number(item, "unique_id");
        require(uid > 0 && gear.add(uid), "Duplicate actual gear identity");
      }
    }
    require(
        stock == (claimed ? 3 : 0) && gear.size() == (claimed ? 2 : 0),
        "Actual inventory amount changed");
    require(
        wallet
            == (claimed
                ? mode.equals("first") ? initialWallet + 1000 : expectedWallet
                : initialWallet),
        "Actual mail wallet changed");
    if (claimed && !expectedGear.isEmpty())
      require(gear.equals(expectedGear), "Saved gear IDs changed");
    result.put("material235", stock);
    result.put("equipment20000Uids", gear);
    result.put("wallet", wallet);
  }

  void finish() {
    result.put("passed", true);
    result.put("checks", checks);
    result.put("mode", mode);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  void receive(ByteBuf bytes, Ukcp connection) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated mail frame");
      String name = null;
      for (var candidate :
          List.of(
              "PlayerGetTokenScRsp",
              "PlayerLoginScRsp",
              "PlayerLoginFinishScRsp",
              "GetMailScRsp",
              "GetBagScRsp",
              "MarkReadMailScRsp",
              "TakeMailAttachmentScRsp",
              "DelMailScRsp",
              "PlayerSyncScNotify"))
        if (frame.cmdId() == protocol.clientCommand(candidate)) {
          name = candidate;
          break;
        }
      if (name == null) continue;
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown mail response fields");
      if (name.equals("PlayerSyncScNotify")) {
        var field = message.getDescriptorForType().findFieldByName("basic_info");
        if (message.hasField(field))
          wallet = number((DynamicMessage) message.getField(field), "scoin");
        continue;
      }
      int code = 0;
      if (Set.of(2, 8).contains(step) && name.equals("TakeMailAttachmentScRsp"))
        code = Retcode.REQ_PARA_INVALID.getVal();
      if (Set.of(12, 31).contains(step) && name.equals("TakeMailAttachmentScRsp"))
        code = Retcode.MAIL_NO_MAIL_TAKE_ATTACHMENT.getVal();
      if (Set.of(6, 35).contains(step) && name.equals("DelMailScRsp"))
        code = Retcode.MAIL_NO_MAIL_TO_DEL.getVal();
      require(
          number(message, "retcode") == code,
          "Mail reply rejected: " + name + " step=" + step + " code=" + number(message, "retcode"));
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", field(message, "uid"));
          query(connection, "PlayerLoginCsReq", 0);
        }
        case "PlayerLoginScRsp" -> {
          wallet = number((DynamicMessage) field(message, "basic_info"), "scoin");
          initialWallet = wallet;
          query(connection, "PlayerLoginFinishCsReq", 0);
        }
        case "PlayerLoginFinishScRsp" ->
            query(connection, "GetMailCsReq", mode.equals("first") ? 1 : 30);
        case "GetMailScRsp" -> {
          switch (step) {
            case 1 -> {
              mail(message, false);
              checks.add("原始配置的信用点、材料与两个独立光锥数量保持");
              mailRequest(connection, "TakeMailAttachmentCsReq", 2, 1);
            }
            case 5 -> {
              mail(message, false);
              checks.add("已读仍保留可领取附件");
              mailRequest(connection, "DelMailCsReq", 6, 0);
            }
            case 7 -> {
              mail(message, false);
              checks.add("已读未领附件不能删除");
              var output = new ByteArrayOutputStream();
              var encoded = CodedOutputStream.newInstance(output);
              encoded.writeUInt64(2, (1L << 32) + mailId);
              encoded.flush();
              send(connection, "TakeMailAttachmentCsReq", output.toByteArray(), 8);
            }
            case 11 -> {
              mail(message, true);
              checks.add("真实领取后查询不再暴露附件");
              mailRequest(connection, "TakeMailAttachmentCsReq", 12, 0);
            }
            case 30 -> {
              mail(message, true);
              checks.add("同UID恢复已读与已领状态");
              mailRequest(connection, "TakeMailAttachmentCsReq", 31, 0);
            }
            case 34 -> {
              require(
                  ((List<?>) field(message, "mail_list")).isEmpty()
                      && number(message, "total_num") == 0,
                  "Claimed mail was not deleted");
              checks.add("已领邮件删除后查询为空");
              mailRequest(connection, "DelMailCsReq", 35, 0);
            }
            default -> throw new IllegalStateException("Unexpected mail query");
          }
        }
        case "GetBagScRsp" -> {
          switch (step) {
            case 3 -> {
              bag(message, false);
              checks.add("可选奖励请求被拒且未发普通附件");
              mailRequest(connection, "MarkReadMailCsReq", 4, 0);
            }
            case 9 -> {
              bag(message, false);
              checks.add("uint32截断别名被拒且库存与钱包不变");
              mailRequest(connection, "TakeMailAttachmentCsReq", 10, 0);
            }
            case 13 -> {
              bag(message, true);
              checks.add("材料3、独立光锥2和信用点1000实际到账一次");
              finish();
            }
            case 32 -> {
              bag(message, true);
              checks.add("重连或重启库存与光锥身份保持且不重发");
              if (mode.equals("delete")) mailRequest(connection, "DelMailCsReq", 33, 0);
              else finish();
            }
            case 36 -> {
              bag(message, true);
              checks.add("删除和重复删除均不改变库存或钱包");
              finish();
            }
            default -> throw new IllegalStateException("Unexpected mail inventory query");
          }
        }
        case "MarkReadMailScRsp" -> {
          require(step == 4 && number(message, "id") == mailId, "Mail read ID was not echoed");
          checks.add("读取响应回显实际邮件ID");
          query(connection, "GetMailCsReq", 5);
        }
        case "TakeMailAttachmentScRsp" -> {
          var successes = (List<?>) field(message, "succ_mail_id_list");
          var amount = rewards((DynamicMessage) field(message, "attachment"));
          if (step == 10) {
            require(
                successes.equals(List.of(mailId))
                    && amount.equals(Map.of(2, 1000, 235, 3, 20000, 2)),
                "Success response differs from actual attachment plan");
            checks.add("成功列表与实际领取数量一致");
            query(connection, "GetMailCsReq", 11);
          } else {
            require(
                successes.isEmpty() && amount.isEmpty(),
                "Rejected mail returned success or rewards");
            switch (step) {
              case 2 -> query(connection, "GetBagCsReq", 3);
              case 8 -> query(connection, "GetBagCsReq", 9);
              case 12 -> {
                checks.add("首次会话重复领取拒绝");
                query(connection, "GetBagCsReq", 13);
              }
              case 31 -> {
                checks.add("保存后重复领取拒绝");
                query(connection, "GetBagCsReq", 32);
              }
              default -> throw new IllegalStateException("Unexpected attachment response");
            }
          }
        }
        case "DelMailScRsp" -> {
          require(
              ((List<?>) field(message, "id_list"))
                  .equals(step == 33 ? List.of(mailId) : List.of()),
              "Deletion result changed");
          if (step == 6) query(connection, "GetMailCsReq", 7);
          else if (step == 33) query(connection, "GetMailCsReq", 34);
          else {
            checks.add("重复删除拒绝");
            query(connection, "GetBagCsReq", 36);
          }
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackMailProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.mode = credentials.get("mode").getAsString();
      probe.expectedWallet =
          credentials.has("expectedWallet") ? credentials.get("expectedWallet").getAsInt() : 0;
      var gear = new TreeSet<Integer>();
      if (credentials.has("expectedGear"))
        for (var id : credentials.getAsJsonArray("expectedGear")) gear.add(id.getAsInt());
      probe.expectedGear = Set.copyOf(gear);
      var token = probe.request("PlayerGetTokenCsReq");
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
            public void onConnected(Ukcp channel) {
              probe.send(channel, "PlayerGetTokenCsReq", token.build().toByteArray(), 0);
            }

            public void handleReceive(ByteBuf bytes, Ukcp channel) {
              try {
                probe.receive(bytes, channel);
              } catch (Throwable error) {
                probe.completed.completeExceptionally(error);
              }
            }

            public void handleException(Throwable error, Ukcp channel) {
              probe.completed.completeExceptionally(error);
            }

            public void handleClose(Ukcp channel) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Mail probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(25, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception error) {
      System.err.println(
          "Mail probe failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
