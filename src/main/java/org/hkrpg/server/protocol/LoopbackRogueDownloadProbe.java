package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Original rest-room download and actual extra-lineup deployment over isolated UDP/KCP and H2. */
public final class LoopbackRogueDownloadProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, mail, console, initialEntry;
  private String normalLineups, expectedNormal;
  private static final int HERO = emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID;

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

  private static void set(DynamicMessage.Builder b, String name, Object v) {
    b.setField(b.getDescriptorForType().findFieldByName(name), v);
  }

  private static void enumValue(DynamicMessage.Builder b, String name, int v) {
    var f = b.getDescriptorForType().findFieldByName(name);
    b.setField(f, f.getEnumType().findValueByNumber(v));
  }

  private void send(Ukcp c, int command, byte[] raw) {
    var b = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], raw));
    try {
      c.write(b);
    } finally {
      b.release();
    }
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    send(c, protocol.clientCommand(name), b.build().toByteArray());
  }

  private void query(Ukcp c, String name, int next) {
    step = next;
    send(c, name, request(name));
  }

  private void avatars(Ukcp c, int next) {
    var b = request("GetAvatarDataCsReq");
    set(b, "is_get_all", true);
    step = next;
    send(c, "GetAvatarDataCsReq", b);
  }

  private void enter(Ukcp c) {
    var name = resume ? "EnterRogueCsReq" : "StartRogueCsReq";
    var b = request(name);
    set(b, "area_id", 100);
    if (!resume) b.addRepeatedField(b.getDescriptorForType().findFieldByName("NOEPBNMJFGL"), HERO);
    step = 10;
    send(c, name, b);
  }

  private void pick(Ukcp c, String mode, int next) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt32(6, console);
    if (mode.equals("duplicate-entity")) out.writeUInt32(6, console);
    if (mode.equals("empty-extra")) out.writeBytes(11, ByteString.EMPTY);
    long id = mode.equals("unowned") ? 1001 : mode.equals("consumed") ? 1102 : 1202;
    if (mode.equals("avatar-alias")) id += 1L << 32;
    var packed = new ByteArrayOutputStream();
    var values = CodedOutputStream.newInstance(packed);
    values.writeUInt64NoTag(id);
    values.flush();
    out.writeByteArray(14, packed.toByteArray());
    out.flush();
    step = next;
    send(c, 1864, buffer.toByteArray());
  }

  private List<Integer> ids(DynamicMessage lineup) {
    return ((List<?>) field(lineup, "avatar_list"))
        .stream().map(v -> number((DynamicMessage) v, "id")).toList();
  }

  private String lineupState(DynamicMessage lineup) {
    var values = new ArrayList<String>();
    for (var v : (List<?>) field(lineup, "avatar_list")) {
      var a = (DynamicMessage) v;
      var sp = (DynamicMessage) field(a, "sp_bar");
      values.add(
          number(a, "id")
              + ":"
              + number(a, "slot")
              + ":"
              + number(a, "hp")
              + ":"
              + number(sp, "cur_sp")
              + ":"
              + number(sp, "max_sp"));
    }
    return number(lineup, "index")
        + ":"
        + number(lineup, "leader_slot")
        + ":"
        + number(lineup, "mp")
        + ":"
        + number(lineup, "max_mp")
        + ":"
        + field(lineup, "extra_lineup_type")
        + ":"
        + values;
  }

  private void rogueLineup(DynamicMessage lineup, boolean downloaded) {
    require(
        field(lineup, "extra_lineup_type").toString().equals("ExtraLineupType_LineupRogue"),
        "No actual Rogue lineup");
    require(
        ids(lineup).equals(downloaded ? List.of(HERO, 1202) : List.of(HERO)),
        "Download changed deployment unexpectedly");
    require(number(lineup, "leader_slot") == (downloaded ? 1 : 0), "Saved leader slot lost");
    for (var v : (List<?>) field(lineup, "avatar_list")) {
      var avatar = (DynamicMessage) v;
      int id = number(avatar, "id");
      var sp = (DynamicMessage) field(avatar, "sp_bar");
      require(
          number(avatar, "hp") == 10000
              && number(sp, "cur_sp") == 5000
              && number(sp, "max_sp") == 10000,
          "Independent downloaded HP/SP lost: " + id);
    }
    result.put("rogueLineup", lineupState(lineup));
  }

  private void game(DynamicMessage game, boolean downloaded) {
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var info = (DynamicMessage) field(current, "EIOHFJMFOKF");
    var available = (List<?>) field(info, "NOEPBNMJFGL");
    require(
        available.equals(downloaded ? List.of(1202, HERO) : List.of(HERO)),
        "Native available-avatar pool changed: " + available);
    require(
        number((DynamicMessage) field(current, "DHEKLJDBNNP"), "rogue_money") == 50,
        "Free download charged fragments");
    var map = (DynamicMessage) field(current, "COOOMDKGODF");
    require(number(map, "HPKMGKPPELC") == 306, "Isolated rest room changed");
    result.put("availableAvatars", available);
    result.put("rogueCoins", 50);
  }

  private void scene(DynamicMessage scene, boolean used) {
    require(number(scene, "entry_id") == 8000103, "Source rest-room entry lost");
    var found = new ArrayList<DynamicMessage>();
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var v : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var e = (DynamicMessage) v;
        var f = e.getDescriptorForType().findFieldByName("prop");
        if (e.hasField(f) && number(e, "group_id") == 22 && number(e, "inst_id") == 300004)
          found.add(e);
      }
    require(found.size() == 1, "No unique actual source download console");
    var e = found.getFirst();
    var prop = (DynamicMessage) field(e, "prop");
    require(
        number(prop, "prop_id") == 1001 && number(prop, "prop_state") == (used ? 2 : 0),
        "Actual console state differs from saved usage");
    console = number(e, "entity_id");
    require(console > 0, "No runtime console entity");
    result.put("nativeOriginalConsolePresent", true);
    result.put("consoleLocked", used);
  }

  private void deploy(Ukcp c) {
    var b = request("ReplaceLineupCsReq");
    enumValue(b, "extra_lineup_type", 2);
    set(b, "leader_slot", 1);
    set(b, "plane_id", 80001);
    int slot = 0;
    for (int id : List.of(HERO, 1202)) {
      var row = request("LineupSlotData");
      set(row, "id", id);
      set(row, "slot", slot++);
      var type = row.getDescriptorForType().findFieldByName("avatar_type");
      row.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
      b.addRepeatedField(b.getDescriptorForType().findFieldByName("lineup_slot_list"), row.build());
    }
    step = 30;
    send(c, 704, b.build().toByteArray());
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated download frame");
      if (frame.cmdId() == 719) {
        require(step == 30, "Unexpected team response");
        var r = UnknownFieldSet.parseFrom(frame.body());
        require(r.getField(9).getVarintList().isEmpty(), "Downloaded team replacement failed");
        result.put("nativeDownloadedAvatarDeployed", true);
        query(c, "GetCurLineupDataCsReq", 31);
        continue;
      }
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "GetMailScRsp",
                  "TakeMailAttachmentScRsp",
                  "GetAvatarDataScRsp",
                  "GetAllLineupDataScRsp",
                  "GetCurSceneInfoScRsp",
                  "StartRogueScRsp",
                  "EnterRogueScRsp",
                  "PickRogueAvatarScRsp",
                  "GetRogueInfoScRsp",
                  "GetCurLineupDataScRsp",
                  "LeaveRogueScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown native download response fields");
      int code = number(m, "retcode");
      if (name.equals("PickRogueAvatarScRsp") && step != 21) {
        int wanted =
            step == 14
                ? Retcode.ROGUE_PICK_AVATAR_INVALID.getVal()
                : step == 32
                    ? Retcode.SCENE_ENTITY_NOT_EXIST.getVal()
                    : Retcode.REQ_PARA_INVALID.getVal();
        require(
            code == wanted,
            "Invalid/consumed selection accepted or misreported: " + step + "/" + code);
        require(
            ((List<?>) field(m, "NOEPBNMJFGL")).isEmpty(),
            "Failed download echoed successful avatars");
      } else require(code == 0, "Download request failed: " + name + "/" + step + "/" + code);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          result.put("uid", number(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" ->
            send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        case "PlayerLoginFinishScRsp" -> avatars(c, 1);
        case "GetAvatarDataScRsp" -> {
          var owned =
              ((List<?>) field(m, "avatar_list"))
                  .stream().map(v -> number((DynamicMessage) v, "base_avatar_id")).toList();
          require(owned.contains(HERO) && !owned.contains(1001), "Owned-avatar fixture differs");
          require(
              (owned.contains(1202) && owned.contains(1102)) == (resume || step == 3),
              "Formal mail avatars not actually owned");
          if (step == 1 && !resume) query(c, "GetMailCsReq", 2);
          else {
            result.put("nativeActualOwnedFormalAvatars", true);
            query(c, "GetAllLineupDataCsReq", 4);
          }
        }
        case "GetMailScRsp" -> {
          var matches =
              ((List<?>) field(m, "mail_list"))
                  .stream()
                      .map(v -> (DynamicMessage) v)
                      .filter(v -> field(v, "title").equals("Rogue download avatar fixture"))
                      .toList();
          require(matches.size() == 1, "No unique avatar fixture mail");
          mail = number(matches.getFirst(), "id");
          var b = request("TakeMailAttachmentCsReq");
          b.addRepeatedField(b.getDescriptorForType().findFieldByName("mail_id_list"), mail);
          send(c, "TakeMailAttachmentCsReq", b);
        }
        case "TakeMailAttachmentScRsp" -> {
          require(
              field(m, "succ_mail_id_list").equals(List.of(mail)),
              "Actual formal avatar mail unclaimed");
          avatars(c, 3);
        }
        case "GetAllLineupDataScRsp" -> {
          var list =
              ((List<?>) field(m, "lineup_list"))
                  .stream().map(v -> lineupState((DynamicMessage) v)).sorted().toList();
          String value = list.toString();
          if (step == 4) {
            normalLineups = value;
            if (resume)
              require(value.equals(expectedNormal), "Saved normal lineups/vitals changed");
            query(c, "GetCurSceneInfoCsReq", 5);
          } else {
            require(
                step == 41 && value.equals(normalLineups),
                "Download mutated normal lineups or vitals");
            result.put("normalLineups", value);
            result.put("normalLineupsPreserved", true);
            result.put("savedDownloadAndDeploymentRestored", resume);
            result.put("passed", true);
            result.put("clientGameplayVerified", false);
            completed.complete(result);
          }
        }
        case "GetCurSceneInfoScRsp" -> {
          if (step == 5) {
            initialEntry = number((DynamicMessage) field(m, "scene"), "entry_id");
            enter(c);
          } else {
            require(step == 25, "Unexpected scene query");
            scene((DynamicMessage) field(m, "scene"), true);
            deploy(c);
          }
        }
        case "StartRogueScRsp", "EnterRogueScRsp" -> {
          require(step == 10, "Unexpected entry");
          game((DynamicMessage) field(m, "rogue_game_info"), resume);
          scene((DynamicMessage) field(m, "scene"), resume);
          rogueLineup((DynamicMessage) field(m, "lineup"), resume);
          if (resume) {
            result.put("nativeSavedDownloadRestored", true);
            pick(c, "consumed", 32);
          } else pick(c, "duplicate-entity", 11);
        }
        case "PickRogueAvatarScRsp" -> {
          switch (step) {
            case 11 -> {
              result.put("nativeDuplicateEntityRejected", true);
              query(c, "GetRogueInfoCsReq", 12);
            }
            case 13 -> {
              result.put("nativeAvatarUintAliasRejected", true);
              query(c, "GetRogueInfoCsReq", 15);
            }
            case 14 -> {
              result.put("nativeUnownedFormalRejected", true);
              query(c, "GetRogueInfoCsReq", 16);
            }
            case 17 -> {
              result.put("nativeEmptyExtraListRejected", true);
              query(c, "GetRogueInfoCsReq", 18);
            }
            case 21 -> {
              require(
                  field(m, "NOEPBNMJFGL").equals(List.of(1202))
                      && ((List<?>) field(m, "BFEDFBEKOBI")).isEmpty(),
                  "Successful download list changed");
              result.put("nativePackedFormalDownload", true);
              query(c, "GetRogueInfoCsReq", 22);
            }
            case 32 -> {
              result.put("nativeConsumedConsoleRejected", true);
              query(c, "GetRogueInfoCsReq", 33);
            }
            default -> throw new IllegalStateException("Unexpected download response");
          }
        }
        case "GetRogueInfoScRsp" -> {
          game((DynamicMessage) field(m, "rogue_game_info"), step >= 22);
          switch (step) {
            case 12 -> pick(c, "avatar-alias", 13);
            case 15 -> pick(c, "unowned", 14);
            case 16 -> pick(c, "empty-extra", 17);
            case 18 -> pick(c, "valid", 21);
            case 22 -> query(c, "GetCurLineupDataCsReq", 24);
            case 33 -> query(c, "GetCurLineupDataCsReq", 34);
            default -> throw new IllegalStateException("Unexpected rogue query");
          }
        }
        case "GetCurLineupDataScRsp" -> {
          var lineup = (DynamicMessage) field(m, "lineup");
          rogueLineup(lineup, step != 24);
          if (step == 24) {
            result.put("nativeDownloadPreservesDeployment", true);
            query(c, "GetCurSceneInfoCsReq", 25);
          } else if (step == 31) pick(c, "consumed", 32);
          else {
            require(step == 34, "Unexpected lineup");
            result.put("nativeIndependentDownloadedVitals", true);
            query(c, "LeaveRogueCsReq", 40);
          }
        }
        case "LeaveRogueScRsp" -> {
          require(step == 40, "Unexpected leave");
          require(
              number((DynamicMessage) field(m, "scene"), "entry_id") == initialEntry,
              "Original return entry changed");
          require(
              field((DynamicMessage) field(m, "lineup"), "extra_lineup_type")
                  .toString()
                  .equals("ExtraLineupType_LineupNone"),
              "Normal team not restored");
          query(c, "GetAllLineupDataCsReq", 41);
        }
        default -> throw new IllegalStateException(name);
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var p = new LoopbackRogueDownloadProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      p.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      if (p.resume) p.expectedNormal = credentials.get("normalLineups").getAsString();
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
                    new IllegalStateException("Download probe disconnected"));
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
          "Download probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
