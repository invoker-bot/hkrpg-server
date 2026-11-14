package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kcp.highway.*;

/** Native cabinet snapshot/replacement; restricted to the runner's isolated account and ports. */
public final class NativePlayerRoomProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private volatile boolean closing;
  private boolean loggedIn;
  private int requests;

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
  private static FieldDescriptor descriptor(DynamicMessage message, String name) {
    var field = message.getDescriptorForType().findFieldByName(name);
    require(field != null, "Missing native field: " + name);
    return field;
  }
  private static Object field(DynamicMessage message, String name) {
    return message.getField(descriptor(message, name));
  }
  private static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }
  private static DynamicMessage child(DynamicMessage message, String name) {
    require(message.hasField(descriptor(message, name)), "Missing native container: " + name);
    return (DynamicMessage) field(message, name);
  }
  private static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(descriptor(message.buildPartial(), name), value);
  }
  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }
  private void send(String name, DynamicMessage.Builder message) {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name),
        new byte[] {8, 1}, message.build().toByteArray()));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
  }
  private DynamicMessage call(String name, DynamicMessage.Builder message) throws Exception {
    return callExpected(name, message, 0);
  }
  private DynamicMessage callExpected(String name, DynamicMessage.Builder message, int expected) throws Exception {
    String response = name.replace("CsReq", "ScRsp");
    send(name, message);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      require(failure.get() == null, "KCP transport failed");
      var frame = inbox.poll(250, TimeUnit.MILLISECONDS);
      if (frame == null) continue;
      if (frame.command == protocol.clientCommand("EnterSceneByServerScNotify")) {
        var scene = child(protocol.clientSchema().parse("EnterSceneByServerScNotify", frame.body), "scene");
        if (number(scene, "floor_id") == 10000003) checkScene(scene);
      }
      if (frame.command != protocol.clientCommand(response)) continue;
      var result = protocol.clientSchema().parse(response, frame.body);
      require(result.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
      require(number(result, "retcode") == expected, "Unexpected native retcode for " + name + ": " + number(result, "retcode") + "; expected " + expected);
      return result;
    }
    throw new IllegalStateException("Timed out waiting for " + response);
  }
  private DynamicMessage call(String name) throws Exception { return call(name, builder(name)); }

  private void checkScene(DynamicMessage scene) {
    require(number(scene, "floor_id") == 10000003 && number(scene, "plane_id") == 10000,
        "Wrong train scene");
  }
  private DynamicMessage profile() throws Exception {
    var response = call("TrainPartyGetDataCsReq");
    return child(child(response, "train_party_data"), "train_party_info");
  }
  private void checkWall(DynamicMessage info, boolean unlocked, boolean placed, int avatarId) {
    boolean found = false;
    for (var raw : (List<?>) field(info, "area_list")) {
      var area = (DynamicMessage) raw;
      if (number(area, "area_id") != 16) continue;
      found = true;
      require(new TreeSet<>((List<Integer>)field(area, "static_prop_id_list"))
          .equals(new TreeSet<>(Set.of(16001, 16002, 16003))), "Missing cabinet slot references");
      require(number(area, "progress") == 0 && ((List<?>)field(area, "step_id_list")).isEmpty(),
          "Invented construction progress");
    }
    require(found == unlocked, "Wrong level gate for cabinet");
    if (!unlocked) return;
    var choices = (List<?>)field(info, "MHABCMLHCGD");
    require(choices.stream().map(x -> number((DynamicMessage)x, "prop_id"))
        .anyMatch(id -> id == 295603), "Actually owned figurine absent from selection cache");
    var display = child(info, "CMCNNJKJNPK");
    var rows = (List<?>)field(display, "GOEJIMNENDC");
    require(rows.size() == (placed ? 1 : 0), "Wrong saved placement count");
    if (placed) {
      var item = (DynamicMessage) rows.getFirst();
      require(number(item, "dice_slot_id") == 1600101 && number(item, "diy_dynamic_id") == 295603,
          "Wrong saved cabinet placement");
    }
    var pictures = (List<?>)field(display, "OFOKOHGPEDO");
    require(pictures.size() == (placed ? 1 : 0), "Wrong saved photo count");
    if (placed) {
      var photo = (DynamicMessage) pictures.getFirst();
      require(number(photo, "slot") == 1600301 && number(photo, "id") == avatarId
          && number(photo, "type") == 1, "Wrong saved avatar photo");
    }
  }
  private DynamicMessage.Builder replace(int item, int avatarId) {
    var data = builder("OIIPJFDNPAH");
    if (item != 0) {
      var row = builder("AreaDynamicInfo");
      set(row, "dice_slot_id", 1600101); set(row, "diy_dynamic_id", item);
      data.addRepeatedField(descriptor(data.buildPartial(), "GOEJIMNENDC"), row.build());
      var photo = builder("CLLBJDEJFNF");
      set(photo, "slot", 1600301); set(photo, "id", avatarId); set(photo, "type", 1);
      data.addRepeatedField(descriptor(data.buildPartial(), "OFOKOHGPEDO"), photo.build());
    }
    var req = builder("TrainPartyBuildSelfDisplayAreaDiyCsReq");
    set(req, "ACLKMFCDMED", data.build());
    return req;
  }
  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token=builder("PlayerGetTokenCsReq");
    set(token,"account_uid",control.get("accountUid").getAsString());
    set(token,"token",control.get("comboToken").getAsString()); set(token,"platform",1);
    int uid=number(call("PlayerGetTokenCsReq",token),"uid");
    call("PlayerLoginCsReq"); loggedIn=true; call("PlayerLoginFinishCsReq");
    var enter=builder("EnterSceneCsReq"); set(enter,"entry_id",100000302);
    call("EnterSceneCsReq",enter); checkScene(child(call("GetCurSceneInfoCsReq"),"scene"));
    var archive = call("GetUpdatedArchiveDataCsReq");
    var archiveData = child(archive, "archive_data");
    var avatars = (List<?>) field(archiveData, "archive_avatar_id_list");
    require(!avatars.isEmpty(), "Actually owned avatar absent from archive");
    int avatarId = (Integer) avatars.getFirst();
    String mode=control.get("mode").getAsString();
    if (mode.equals("locked")) {
      checkWall(profile(), false, false, avatarId);
      callExpected("TrainPartyBuildSelfDisplayAreaDiyCsReq", replace(0, avatarId), 1215);
    } else {
      boolean placed = !mode.equals("place");
      checkWall(profile(), true, placed, avatarId);
      if (mode.equals("place")) {
        callExpected("TrainPartyBuildSelfDisplayAreaDiyCsReq", replace(295604, avatarId), 5);
        checkWall(profile(), true, false, avatarId);
        var response=call("TrainPartyBuildSelfDisplayAreaDiyCsReq", replace(295603, avatarId));
        require(((List<?>)field(child(response, "ACLKMFCDMED"), "GOEJIMNENDC")).size()==1,
            "Missing accepted placement in response");
        checkWall(profile(), true, true, avatarId);
        var auto=builder("TrainPartySetBadgeAutoFillCsReq"); set(auto,"PELJACANAFH",true);
        callExpected("TrainPartySetBadgeAutoFillCsReq",auto,9);
      } else if (mode.equals("restart")) {
        call("TrainPartyBuildSelfDisplayAreaDiyCsReq", replace(0, avatarId));
        checkWall(profile(), true, false, avatarId);
      }
    }
    var result=new LinkedHashMap<String,Object>();
    result.put("passed",true); result.put("playerUid",uid); result.put("mode",mode);
    result.put("avatarPhotoId",avatarId);
    result.put("archiveReplyHasContainer",true); result.put("clientGameplayVerified",false);
    return result;
  }
  private void logout() throws InterruptedException {
    if (!loggedIn || closing) return;
    closing = true;
    send("PlayerLogoutCsReq", builder("PlayerLogoutCsReq"));
    Thread.sleep(250);
  }
  public static void main(String[] args) {
    var probe = new NativePlayerRoomProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
          .getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1200);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(15000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp value) { probe.connected.complete(value); }
        public void handleReceive(ByteBuf bytes, Ukcp value) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated packet");
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
            }
          } catch (Throwable error) { probe.failure.compareAndSet(null, error); }
        }
        public void handleException(Throwable error, Ukcp value) {
          if (!probe.closing) probe.failure.compareAndSet(null, error);
          probe.connected.completeExceptionally(error);
        }
        public void handleClose(Ukcp value) {
          if (!probe.closing)
            probe.failure.compareAndSet(null, new IllegalStateException("Unexpected disconnect"));
        }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      var result = probe.execute(control);
      probe.logout();
      result.put("normalLogoutRequested", true); result.put("nativeRequests", probe.requests);
      System.out.println(new Gson().toJson(result));
      exit = 0;
    } catch (Exception error) {
      System.err.println("Player room probe failed: " + error.getClass().getSimpleName());
      System.err.println("Contract: " + error.getMessage());
    } finally {
      try { probe.logout(); } catch (Exception ignored) { /* Runner verifies cleanup. */ }
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
