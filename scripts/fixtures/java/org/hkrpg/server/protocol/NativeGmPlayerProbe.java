package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** A real isolated KCP player kept online for the GM HTTP/browser acceptance flow. */
public final class NativeGmPlayerProbe {
  private record Frame(int command, byte[] header, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final Map<String, Object> basic = new LinkedHashMap<>();
  private final List<Map<String, Object>> equipmentSyncs = new ArrayList<>();
  private final List<Map<String, Object>> trialNotifications = new ArrayList<>();
  private final List<Map<String, Object>> trialStateChanges = new ArrayList<>();
  private final List<Map<String, Object>> petChanges = new ArrayList<>();
  private Ukcp channel;
  private int requests;
  private byte[] lastReplyHeader = new byte[0];

  private static Object field(DynamicMessage message, String name) {
    return message.getField(
        Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name), name));
  }

  private static Map<String, Object> project(DynamicMessage message) {
    var result = new LinkedHashMap<String, Object>();
    message
        .getAllFields()
        .forEach((descriptor, value) -> result.put(descriptor.getName(), projectValue(value)));
    return result;
  }

  private static Object projectValue(Object value) {
    if (value instanceof DynamicMessage message) return project(message);
    if (value instanceof List<?> list)
      return list.stream().map(NativeGmPlayerProbe::projectValue).toList();
    if (value instanceof Descriptors.EnumValueDescriptor enumeration) return enumeration.getName();
    if (value instanceof ByteString bytes)
      return Base64.getEncoder().encodeToString(bytes.toByteArray());
    return value;
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var request = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) ->
            request.setField(request.getDescriptorForType().findFieldByName(key), value));
    return callWire(name, request.build().toByteArray(), true);
  }

  private DynamicMessage callWire(String name, byte[] payload, boolean success) throws Exception {
    return callWire(name, payload, success, new byte[0]);
  }

  private DynamicMessage callWire(String name, byte[] payload, boolean success, byte[] header) throws Exception {
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                name.equals("GetServerPrefsDataCsReq") ? 6105
                    : protocol.clientCommand(name.replace("UnlockSkillTree", "UnlockSkilltree")), header, payload));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    requests++;
    String response = name.equals("UpdateServerPrefsCsReq") ? "UpdateServerPrefsDataScRsp"
        : name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("KCP failure", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("PetDataChangeScNotify")) {
        if (frame.header().length > 0)
          throw new IllegalStateException("Pet notification inherited a request packet ID");
        petChanges.add(project(protocol.clientSchema().parse("PetDataChangeScNotify", frame.body())));
      }
      if (frame.command() == protocol.clientCommand("CurTrialActivityScNotify"))
        trialNotifications.add(project(protocol.clientSchema().parse("CurTrialActivityScNotify", frame.body())));
      if (frame.command() == protocol.clientCommand("TrialActivityDataChangeScNotify")) {
        if (header.length > 0 && frame.header().length > 0)
          throw new IllegalStateException("Trial notification inherited a request packet ID");
        trialStateChanges.add(project(protocol.clientSchema().parse("TrialActivityDataChangeScNotify", frame.body())));
      }
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        if (header.length > 0 && frame.header().length > 0)
          throw new IllegalStateException("Notification inherited a request packet ID");
        var sync = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        basic.putAll(project((DynamicMessage) field(sync, "basic_info")));
        if (!((List<?>) field(sync, "equipment_list")).isEmpty()
            || !((List<?>) field(sync, "relic_list")).isEmpty()
            || !((DynamicMessage) field(sync, "avatar_sync")).getAllFields().isEmpty()) equipmentSyncs.add(project(sync));
      }
      if (frame.command() != protocol.clientCommand(response.replace("UnlockSkillTree", "UnlockSkilltree"))) continue;
      lastReplyHeader = frame.header();
      if (header.length > 0 && !Arrays.equals(header, lastReplyHeader))
        throw new IllegalStateException("Response did not complete the matching async request: " + response);
      var result = protocol.clientSchema().parse(response, frame.body());
      String retcodeField = Set.of("PromoteAvatarScRsp", "PromoteEquipmentScRsp", "RankUpAvatarScRsp",
          "DressAvatarScRsp", "TakeOffEquipmentScRsp").contains(response) ? "PLDKENJLKME" : "retcode";
      if (!result.getUnknownFields().asMap().isEmpty() || ((Integer) field(result, retcodeField) == 0) != success)
        throw new IllegalStateException("Unexpected response: " + response + " " + project(result));
      return result;
    }
    throw new IllegalStateException("Timeout: " + response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  private void run(int port, Path credentials, Path directory) throws Exception {
    var control = JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                control.get("accountUid").getAsString(),
                "token",
                control.get("comboToken").getAsString(),
                "platform",
                1));
    int uid = (Integer) field(token, "uid");
    basic.putAll(project((DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info")));
    call("PlayerLoginFinishCsReq");
    String lastPullId = "";
    Map<String, Object> lastPull = null;
    String lastEquipmentId = "";
    Map<String, Object> lastEquipment = null;
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(40);
    while (!Files.exists(directory.resolve("quit")) && System.nanoTime() < deadline) {
      // Optional owned smoke control. No production endpoint or runtime hook is installed.
      Path pullRequest = directory.resolve("gacha-pull-request.json");
      if (Files.exists(pullRequest)) {
        var requested = JsonParser.parseString(Files.readString(pullRequest)).getAsJsonObject();
        String id = requested.get("requestId").getAsString();
        if (!id.equals(lastPullId)) {
          int count = requested.get("count").getAsInt();
          if (count != 1 && count != 10) throw new IllegalArgumentException("Invalid smoke pull count");
          int banner = requested.has("gachaId") ? requested.get("gachaId").getAsInt() : 1001;
          if (banner != 1001 && banner != 4001) throw new IllegalArgumentException("Invalid owned smoke banner");
          boolean success = !requested.has("success") || requested.get("success").getAsBoolean();
          var request = DynamicMessage.newBuilder(protocol.clientSchema().message("DoGachaCsReq"));
          request.setField(request.getDescriptorForType().findFieldByName("gacha_id"), banner);
          request.setField(request.getDescriptorForType().findFieldByName("gacha_num"), count);
          var result = callWire("DoGachaCsReq", request.build().toByteArray(), success);
          lastPull = Map.of("requestId",id,"count",count,"response",project(result));
          lastPullId = id;
        }
      }
      Path equipmentRequest = directory.resolve("equipment-request.json");
      if (Files.exists(equipmentRequest)) {
        var requested = JsonParser.parseString(Files.readString(equipmentRequest)).getAsJsonObject();
        String id = requested.get("requestId").getAsString();
        if (!id.equals(lastEquipmentId)) {
          var sequence = requested.has("commands")
              ? requested.getAsJsonArray("commands").asList() : List.<JsonElement>of(requested);
          if (sequence.isEmpty() || sequence.size() > 64)
            throw new IllegalArgumentException("Invalid owned smoke sequence length");
          var replies = new ArrayList<Map<String, Object>>();
          for (var value : sequence) {
            var step = value.getAsJsonObject();
            String command = step.get("command").getAsString();
            if (!Set.of("ExpUpEquipmentCsReq", "ExpUpRelicCsReq", "RankUpEquipmentCsReq",
                "PromoteAvatarCsReq", "PromoteEquipmentCsReq", "UnlockSkillTreeCsReq", "LockEquipmentCsReq", "LockRelicCsReq",
                "RankUpAvatarCsReq", "TakePromotionRewardCsReq", "BuyGoodsCsReq",
                "GetShopListCsReq", "GetPlayerBoardDataCsReq", "GetPhoneDataCsReq", "SetPersonalCardCsReq",
                "GetPamSkinDataCsReq", "SelectPamSkinCsReq", "GetPetDataCsReq", "SummonPetCsReq", "RecallPetCsReq",
                "GetBigDataRecommendCsReq", "DressAvatarCsReq", "TakeOffEquipmentCsReq", "SetAvatarPathCsReq",
                "GetGachaCeilingCsReq", "ExchangeGachaCeilingCsReq",
                "GetLevelRewardCsReq", "GetLevelRewardTakenListCsReq",
                "GetActivityScheduleConfigCsReq", "GetTrialActivityDataCsReq", "StartTrialActivityCsReq", "LeaveTrialActivityCsReq",
                "GetLoginActivityCsReq", "TakeLoginActivityRewardCsReq",
                "GetMaterialSubmitActivityDataCsReq", "SubmitMaterialSubmitActivityMaterialCsReq", "TakeMaterialSubmitActivityRewardCsReq",
                "TakeTrialActivityRewardCsReq", "GetCurSceneInfoCsReq", "GetCurLineupDataCsReq",
                "SceneCastSkillCsReq", "GetCurBattleInfoCsReq", "PVEBattleResultCsReq", "QuitBattleCsReq",
                "GetAllServerPrefsDataCsReq", "GetServerPrefsDataCsReq", "UpdateServerPrefsCsReq").contains(command))
              throw new IllegalArgumentException("Unsupported owned equipment smoke request");
            equipmentSyncs.clear();
            petChanges.clear();
            byte[] header = new byte[0];
            if (step.has("packetId")) {
              int packetId = step.get("packetId").getAsInt();
              if (packetId <= 0) throw new IllegalArgumentException("Invalid smoke packet ID");
              header = new byte[CodedOutputStream.computeUInt32Size(1, packetId)];
              var encoded = CodedOutputStream.newInstance(header);
              encoded.writeUInt32(1, packetId);
              encoded.checkNoSpaceLeft();
            }
            byte[] payload;
            if (step.has("stageId")) {
              var request = DynamicMessage.newBuilder(protocol.clientSchema().message(command));
              request.setField(request.getDescriptorForType().findFieldByName("stage_id"), step.get("stageId").getAsInt());
              payload = request.build().toByteArray();
            } else payload = Base64.getDecoder().decode(step.get("wire").getAsString());
            var result = callWire(command, payload,
                step.get("success").getAsBoolean(), header);
            replies.add(Map.of("command", command, "response", project(result), "syncs", List.copyOf(equipmentSyncs),
                "petChanges", List.copyOf(petChanges), "replyHeader", Base64.getEncoder().encodeToString(lastReplyHeader)));
          }
          lastEquipment = requested.has("commands")
              ? Map.of("requestId", id, "responses", List.copyOf(replies))
              : Map.of("requestId", id, "response", replies.getFirst().get("response"), "syncs", replies.getFirst().get("syncs"),
              "petChanges", replies.getFirst().get("petChanges"),
              "replyHeader", Base64.getEncoder().encodeToString(lastReplyHeader));
          lastEquipmentId = id;
        }
      }
      call("PlayerHeartBeatCsReq", Map.of("client_time_ms", System.currentTimeMillis()));
      var snapshot = new LinkedHashMap<String, Object>();
      snapshot.put("uid", uid);
      snapshot.put("bag", project(call("GetBagCsReq")));
      snapshot.put("avatars", project(call("GetAvatarDataCsReq", Map.of("is_get_all", true))));
      snapshot.put("mail", project(call("GetMailCsReq")));
      var gacha = call("GetGachaInfoCsReq");
      snapshot.put("gacha", project(gacha));
      snapshot.put("gachaWire", Base64.getEncoder().encodeToString(gacha.toByteArray()));
      snapshot.put("basic", Map.copyOf(basic));
      snapshot.put("requests", requests);
      snapshot.put("trialNotifications", List.copyOf(trialNotifications));
      snapshot.put("trialStateChanges", List.copyOf(trialStateChanges));
      if (lastPull != null) snapshot.put("gachaPull",lastPull);
      if (lastEquipment != null) snapshot.put("equipmentReply", lastEquipment);
      Path temporary = directory.resolve("snapshot.tmp");
      Files.writeString(temporary, new Gson().toJson(snapshot));
      for(int attempt=0;;attempt++){
        try{
          Files.move(temporary,directory.resolve("snapshot.json"),StandardCopyOption.REPLACE_EXISTING);
          break;
        }catch(java.nio.file.FileSystemException busy){
          // Windows readers may briefly hold the old snapshot without FILE_SHARE_DELETE.
          if(attempt>=9)throw busy;
          Thread.sleep(25L*(attempt+1));
        }
      }
      Thread.sleep(1500);
    }
  }

  public static void main(String[] args) {
    var probe = new NativeGmPlayerProbe();
    var client = new KcpClient();
    int exit = 1;
    try {
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(40000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp channel) {
              probe.connected.complete(channel);
            }

            public void handleReceive(ByteBuf bytes, Ukcp channel) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  if (frame == null) throw new IllegalStateException("Truncated GM frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.header(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp channel) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp channel) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("GM player disconnected"));
            }
          });
      int port = Integer.parseInt(args[0]);
      client.connect(new InetSocketAddress("127.0.0.1", port), config);
      probe.channel = probe.connected.get(15, TimeUnit.SECONDS);
      probe.run(port, Path.of(args[1]), Path.of(args[2]));
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      client.stop();
    }
    System.exit(exit);
  }
}
