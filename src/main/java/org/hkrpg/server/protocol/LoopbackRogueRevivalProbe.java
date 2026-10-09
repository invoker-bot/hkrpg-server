package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.LoopbackPictureProbe.*;

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

/** Real revive console, batch payment and saved clock conversion over isolated UDP/KCP. */
public final class LoopbackRogueRevivalProbe {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  final Map<String, Object> result = new LinkedHashMap<>();
  final List<String> checks = new ArrayList<>();
  static final List<Integer> MEMBERS = List.of(8001, 1202);
  static final List<String> INVALID =
      List.of(
          "duplicate-entity",
          "avatar-alias",
          "entity-alias",
          "avatar-alias64",
          "entity-alias64",
          "tag-alias",
          "length-alias",
          "truncated-packed");
  String mode, normal, expectedNormal;
  int step, console, invalidIndex, priceNotifies, mail;

  DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  void send(Ukcp client, String name, byte[] body, int next) {
    step = next;
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      client.write(bytes);
    } finally {
      bytes.release();
    }
  }

  void query(Ukcp client, String name, int next) {
    send(client, name, request(name).build().toByteArray(), next);
  }

  static DynamicMessage child(DynamicMessage message, String name) {
    return (DynamicMessage) field(message, name);
  }

  int price(DynamicMessage info) {
    var items = (List<?>) field(child(info, "LNIEFKBMIPD"), "item_list");
    require(items.size() == 1, "Unexpected revival price items");
    var pile = child((DynamicMessage) items.getFirst(), "pile_item");
    require(number(pile, "item_id") == 31, "Revival did not quote fragments");
    return number(pile, "item_num");
  }

  void game(DynamicMessage game, boolean revived) {
    var run = child(game, "CFDIKLBINHK");
    int coins = mode.equals("bootstrap") || !revived ? 200 : 999;
    require(number(child(run, "DHEKLJDBNNP"), "rogue_money") == coins, "Revival wallet changed");
    var curioInfo = child(child(run, "DNPCCFFFBHN"), "DNPCCFFFBHN");
    var curios =
        ((List<?>) field(curioInfo, "MAJGMMCOCIG"))
            .stream().map(r -> number((DynamicMessage) r, "LJCEOGPOGBE")).sorted().toList();
    var expected = mode.equals("bootstrap") ? List.<Integer>of() : List.of(revived ? 9 : 70);
    require(curios.equals(expected), "Clock conversion or acquired identity changed");
    var info = child(run, "EIOHFJMFOKF");
    int unit = mode.equals("bootstrap") || revived ? 80 : 100;
    require(price(child(info, "MFEODNJNGDP")) == unit, "Revival unit price changed");
    require(number(child(run, "COOOMDKGODF"), "HPKMGKPPELC") == 306, "Original rest room changed");
    result.put("coins", coins);
    result.put("curios", curios);
    result.put("unitPrice", unit);
  }

  void lineup(DynamicMessage message, boolean revived) {
    require(
        field(message, "extra_lineup_type").toString().equals("ExtraLineupType_LineupRogue"),
        "Missing actual Rogue lineup");
    var rows = (List<?>) field(message, "avatar_list");
    require(rows.size() == 2, "Saved revival roster changed");
    int hp = mode.equals("bootstrap") || revived ? 10000 : 0;
    var actual = new ArrayList<Integer>();
    for (var raw : rows) {
      var row = (DynamicMessage) raw;
      actual.add(number(row, "id"));
      var sp = child(row, "sp_bar");
      require(
          number(row, "hp") == hp && number(sp, "cur_sp") == 5000 && number(sp, "max_sp") == 10000,
          "Actual HP or independent energy changed");
    }
    require(actual.equals(MEMBERS), "Wrong actual revival targets");
    require(
        number(message, "mp") == 5 && number(message, "leader_slot") == 0,
        "Revival changed MP or leader");
    result.put("hp", hp);
    result.put("sp", 5000);
    result.put("members", actual);
  }

  void scene(DynamicMessage message) {
    require(number(message, "entry_id") == 8000103, "Original rest-room entry changed");
    var matches = new ArrayList<DynamicMessage>();
    for (var group : (List<?>) field(message, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var row = (DynamicMessage) raw;
        if (row.hasField(row.getDescriptorForType().findFieldByName("prop"))
            && number(row, "group_id") == 22
            && number(row, "inst_id") == 300019) matches.add(row);
      }
    require(
        matches.size() == 1 && number(child(matches.getFirst(), "prop"), "prop_id") == 1002,
        "Original source revive console missing");
    console = number(matches.getFirst(), "entity_id");
    require(console > 0, "No runtime revive entity");
    result.put("originalReviveConsolePresent", true);
  }

  static void alias64(CodedOutputStream out, int value) throws IOException {
    for (int i = 0; i < 9; i++) out.writeRawByte((int) (((long) value >>> (i * 7)) & 127) | 128);
    out.writeRawByte(2);
  }

  void revive(Ukcp client, String encoding, int next) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeTag(13, 0);
    if (encoding.equals("entity-alias64")) alias64(out, console);
    else out.writeUInt64NoTag(console + (encoding.equals("entity-alias") ? 1L << 32 : 0));
    if (encoding.equals("duplicate-entity")) out.writeUInt32(13, console);
    if (encoding.equals("packed") || encoding.equals("length-alias")) {
      var packed = new ByteArrayOutputStream();
      var values = CodedOutputStream.newInstance(packed);
      for (int id : MEMBERS) values.writeUInt32NoTag(id);
      values.flush();
      out.writeTag(10, 2);
      out.writeUInt64NoTag(packed.size() + (encoding.equals("length-alias") ? 1L << 32 : 0));
      out.writeRawBytes(packed.toByteArray());
    } else {
      out.writeUInt64NoTag(80 + (encoding.equals("tag-alias") ? 1L << 32 : 0));
      if (encoding.equals("avatar-alias64")) alias64(out, MEMBERS.getFirst());
      else
        out.writeUInt64NoTag(MEMBERS.getFirst() + (encoding.equals("avatar-alias") ? 1L << 32 : 0));
      out.writeUInt32(10, MEMBERS.getLast());
    }
    if (encoding.equals("truncated-packed")) {
      out.writeTag(10, 2);
      out.writeUInt32NoTag(2);
      out.writeRawByte(128);
    }
    out.flush();
    send(client, "ReviveRogueAvatarCsReq", buffer.toByteArray(), next);
  }

  void finish() {
    result.put("normalLineups", normal);
    result.put("checks", checks);
    result.put("mode", mode);
    result.put("priceChangeNotifies", priceNotifies);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  void receive(ByteBuf bytes, Ukcp client) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated revival packet");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetMailScRsp",
                  "TakeMailAttachmentScRsp",
                  "GetAllLineupDataScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "GetRogueInfoScRsp",
                  "GetCurLineupDataScRsp",
                  "ReviveRogueAvatarScRsp",
                  "SyncRogueReviveInfoScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown native revival fields");
      if (name.equals("SyncRogueReviveInfoScNotify")) {
        if (step == 30) {
          require(price(child(message, "MFEODNJNGDP")) == 80, "Wrong post-conversion price notify");
          priceNotifies++;
        }
        continue;
      }
      int code = number(message, name.equals("ReviveRogueAvatarScRsp") ? "PLDKENJLKME" : "retcode");
      int expected =
          name.equals("ReviveRogueAvatarScRsp")
              ? (step == 20
                  ? Retcode.REQ_PARA_INVALID.getVal()
                  : step == 34 ? Retcode.ROGUE_PICK_AVATAR_INVALID.getVal() : 0)
              : 0;
      require(code == expected, "Revival reply failed: " + name + "/" + step + "/" + code);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", number(message, "uid"));
          query(client, "PlayerLoginCsReq", 1);
        }
        case "PlayerLoginScRsp" -> query(client, "PlayerLoginFinishCsReq", 2);
        case "PlayerLoginFinishScRsp" ->
            query(client, mode.equals("bootstrap") ? "GetMailCsReq" : "GetAllLineupDataCsReq", 3);
        case "GetMailScRsp" -> {
          var rows =
              ((List<?>) field(message, "mail_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> field(v, "title").equals("Rogue revival avatar fixture"))
                      .toList();
          require(rows.size() == 1, "Missing isolated owned-avatar mail");
          mail = number(rows.getFirst(), "id");
          var req = request("TakeMailAttachmentCsReq");
          req.addRepeatedField(req.getDescriptorForType().findFieldByName("mail_id_list"), mail);
          send(client, "TakeMailAttachmentCsReq", req.build().toByteArray(), 4);
        }
        case "TakeMailAttachmentScRsp" -> {
          require(
              field(message, "succ_mail_id_list").equals(List.of(mail)),
              "Formal avatar was not actually granted");
          query(client, "GetAllLineupDataCsReq", 3);
        }
        case "GetAllLineupDataScRsp" -> {
          String saved =
              ((List<?>) field(message, "lineup_list"))
                  .stream().map(Object::toString).sorted().toList().toString();
          if (step == 40) {
            require(saved.equals(normal), "Revival modified ordinary team/vitals");
            checks.add("普通队伍生命与能量不变");
            finish();
          } else {
            normal = saved;
            if (expectedNormal != null)
              require(normal.equals(expectedNormal), "Saved normal team changed");
            String reqName = mode.equals("bootstrap") ? "StartRogueCsReq" : "EnterRogueCsReq";
            var req = request(reqName);
            set(req, "area_id", 100);
            if (mode.equals("bootstrap"))
              for (int id : MEMBERS)
                req.addRepeatedField(req.getDescriptorForType().findFieldByName("NOEPBNMJFGL"), id);
            send(client, reqName, req.build().toByteArray(), 10);
          }
        }
        case "StartRogueScRsp", "EnterRogueScRsp" -> {
          boolean restored = mode.equals("resume");
          game(child(message, "rogue_game_info"), restored);
          lineup(child(message, "lineup"), restored);
          scene(child(message, "scene"));
          if (mode.equals("bootstrap")) {
            checks.add("原始休息房与复活装置实际加载");
            checks.add("正式双人队伍和初始独立生命能量正确");
            finish();
          } else if (restored) {
            checks.add("同UID恢复已复活生命与独立能量");
            checks.add("999碎片和损毁咕咕钟恢复");
            revive(client, "unpacked", 34);
          } else {
            checks.add("隔离倒地与490额外费用存档已恢复");
            revive(client, INVALID.get(invalidIndex), 20);
          }
        }
        case "ReviveRogueAvatarScRsp" -> {
          require(
              price(child(message, "PHNAANFIFGB")) == (step == 20 ? 100 : 80),
              "Reply unit price differs");
          if (step == 20) query(client, "GetRogueInfoCsReq", 21);
          else if (step == 30) {
            require(priceNotifies == 1, "Missing or duplicated conversion price notify");
            checks.add("实际双人复活扣200并转换奖励999一次");
            checks.add("保存后通知恢复80基础单价");
            query(client, "GetRogueInfoCsReq", 31);
          } else {
            checks.add("重复复活拒绝");
            query(client, "GetRogueInfoCsReq", 35);
          }
        }
        case "GetRogueInfoScRsp" -> {
          game(child(message, "rogue_game_info"), step != 21);
          query(client, "GetCurLineupDataCsReq", step == 21 ? 22 : step == 31 ? 32 : 36);
        }
        case "GetCurLineupDataScRsp" -> {
          lineup(child(message, "lineup"), step != 22);
          if (step == 22) {
            checks.add(INVALID.get(invalidIndex) + "拒绝且碎片/生命/能量/奇物不变");
            if (++invalidIndex < INVALID.size()) revive(client, INVALID.get(invalidIndex), 20);
            else revive(client, "packed", 30);
          } else if (step == 32) {
            checks.add("原生编队确认两人满生命且能量和秘技点保持");
            revive(client, "unpacked", 34);
          } else {
            checks.add("重复请求后实际钱包与生命不变");
            query(client, "GetAllLineupDataCsReq", 40);
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
      var probe = new LoopbackRogueRevivalProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.mode = credentials.get("mode").getAsString();
      probe.expectedNormal =
          credentials.has("normalLineups") ? credentials.get("normalLineups").getAsString() : null;
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
                    new IllegalStateException("Revival probe disconnected"));
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
          "Revival probe failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
