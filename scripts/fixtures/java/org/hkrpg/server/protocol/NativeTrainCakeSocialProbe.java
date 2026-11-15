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
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Native multi-session probe; its runner supplies only isolated, synthetic accounts and ports. */
public final class NativeTrainCakeSocialProbe {
  private record Frame(int command, byte[] body) {}
  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
  private static FieldDescriptor descriptor(DynamicMessage message, String name) {
    var field = message.getDescriptorForType().findFieldByName(name);
    require(field != null, "Missing field " + name); return field;
  }
  private static Object value(DynamicMessage message, String name) {
    return message.getField(descriptor(message, name));
  }
  private static int number(DynamicMessage message, String name) { return (Integer) value(message, name); }
  private static DynamicMessage child(DynamicMessage message, String name) {
    require(message.hasField(descriptor(message, name)), "Missing container " + name);
    return (DynamicMessage) value(message, name);
  }
  private static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(descriptor(message.buildPartial(), name), value);
  }

  private static final class Client implements AutoCloseable {
    final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
    final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
    final KcpClient transport = new KcpClient();
    Ukcp channel;
    boolean closing, loggedIn;
    int uid, requests;

    Client(int port) throws Exception {
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1200);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(15000); config.setAckNoDelay(true);
      transport.init(config, new KcpListener() {
        public void onConnected(Ukcp value) { connected.complete(value); }
        public void handleReceive(ByteBuf bytes, Ukcp value) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated native frame");
              inbox.add(new Frame(frame.cmdId(), frame.body()));
            }
          } catch (Throwable error) { failure.compareAndSet(null, error); }
        }
        public void handleException(Throwable error, Ukcp value) {
          if (!closing) failure.compareAndSet(null, error);
          connected.completeExceptionally(error);
        }
        public void handleClose(Ukcp value) {
          if (!closing) failure.compareAndSet(null, new IllegalStateException("Disconnected"));
        }
      });
      transport.connect(new InetSocketAddress("127.0.0.1", port), config);
      channel = connected.get(10, TimeUnit.SECONDS);
    }
    DynamicMessage.Builder builder(String name) {
      return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    }
    void send(String name, DynamicMessage.Builder body) {
      var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name),
          new byte[] {8, 1}, body.build().toByteArray()));
      try { channel.write(bytes); requests++; } finally { bytes.release(); }
    }
    DynamicMessage await(String name) throws Exception {
      int id = protocol.clientCommand(name);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (System.nanoTime() < deadline) {
        require(failure.get() == null, "Native transport failed");
        var frame = inbox.poll(250, TimeUnit.MILLISECONDS);
        if (frame != null && frame.command == id) return protocol.clientSchema().parse(name, frame.body);
      }
      throw new IllegalStateException("Timed out waiting for " + name);
    }
    DynamicMessage call(String request, DynamicMessage.Builder body, String response) throws Exception {
      send(request, body);
      var result = await(response);
      require(result.getUnknownFields().asMap().isEmpty(), "Unknown fields for " + response);
      require(number(result, "retcode") == 0, request + " retcode=" + number(result, "retcode"));
      return result;
    }
    DynamicMessage call(String name, DynamicMessage.Builder body) throws Exception {
      return call(name, body, name.replace("CsReq", "ScRsp"));
    }
    DynamicMessage call(String name) throws Exception { return call(name, builder(name)); }
    void login(JsonObject account) throws Exception {
      var token = builder("PlayerGetTokenCsReq");
      set(token, "account_uid", account.get("accountUid").getAsString());
      set(token, "token", account.get("comboToken").getAsString()); set(token, "platform", 1);
      uid = number(call("PlayerGetTokenCsReq", token), "uid");
      call("PlayerLoginCsReq"); loggedIn = true; call("PlayerLoginFinishCsReq");
    }
    void train() throws Exception {
      var req = builder("EnterSceneCsReq"); set(req, "entry_id", 100000302); call("EnterSceneCsReq", req);
      var scene = child(call("GetCurSceneInfoCsReq"), "scene");
      require(number(scene, "plane_id") == 10000 && number(scene, "floor_id") == 10000003,
          "Wrong ordinary train scene");
    }
    void visit(int owner) throws Exception {
      int entry = uid == owner ? 100000399 : 100000353;
      var check = builder("SocialPlayPreCheckEnterRoomCsReq");
      set(check, "entry_id", entry); set(check, "teleport_id", owner);
      call("SocialPlayPreCheckEnterRoomCsReq", check);
      var tp = builder("TeleportInfo"); set(tp, "teleport_id", owner); set(tp, "reason", 2);
      var id = builder("SceneIdentifier"); set(id, "floor_id", 10000003); set(id, "teleport_info", tp.build());
      var req = builder("EnterSceneCsReq"); set(req, "entry_id", entry); set(req, "scene_identifier", id.build());
      call("EnterSceneCsReq", req);
      var scene = child(call("GetCurSceneInfoCsReq"), "scene");
      require(number(scene, "plane_id") == 10000 && number(scene, "floor_id") == 10000003,
          "Wrong room scene");
    }
    void leave() throws Exception {
      var req = builder("EnterSceneCsReq"); set(req, "entry_id", 100000302); call("EnterSceneCsReq", req);
    }
    void allowRoom() throws Exception {
      var social = builder("JPGACKPLGCD");
      set(social, "KALACJBAPOA", true); set(social, "FOKJIPGMKAN", true); set(social, "IBLGOCFBOCO", true);
      var setting = builder("UpdatePlayerSetting"); set(setting, "CMFCOOEEECB", social.build());
      var update = builder("UpdatePlayerSettingCsReq"); set(update, "player_setting", setting.build());
      var response = call("UpdatePlayerSettingCsReq", update);
      var echoed = child(child(response, "player_setting"), "CMFCOOEEECB");
      require((Boolean) value(echoed, "KALACJBAPOA") && (Boolean) value(echoed, "FOKJIPGMKAN")
          && (Boolean) value(echoed, "IBLGOCFBOCO"), "Room permissions were not saved");
    }
    void search(int cat) throws Exception {
      var req = builder("TrainCakeCatchSearchCsReq"); set(req, "PCPDHEIEJFO", cat);
      var result = call("TrainCakeCatchSearchCsReq", req);
      require(number(child(result, "NIELDCLPOLA"), "count") == 1, "Incorrect capture total");
    }
    void verifyProfile(int expectedOwner) throws Exception {
      var profile = child(call("SocialPlayGetDataCsReq"), "ELDIONMAAHO");
      var counts = (List<?>) value(profile, "NIELDCLPOLA");
      var actual = new TreeMap<Integer, Integer>();
      for (var raw : counts) {
        var row = (DynamicMessage) raw;
        actual.put(number(row, "PCPDHEIEJFO"), number(row, "count"));
      }
      require(actual.equals(uid == expectedOwner ? Map.of(10, 1, 21, 1) : Map.of(10, 1)),
          "Capture was not persisted");
      var records = (List<?>) value(profile, "CEMFBJPOLNP");
      require(records.size() == 1 && number((DynamicMessage) records.getFirst(), "PCPDHEIEJFO") == expectedOwner,
          "Wrong persisted room receipt");
      if (uid == expectedOwner) {
        var layout = child(profile, "NLJBJMDPBGN");
        require(((List<?>) value(layout, "PNHLAAMNGFE")).size() == 1
            && ((List<?>) value(layout, "PKMHMGAEAGO")).size() == 1, "Layout was not persisted");
      }
    }
    public void close() {
      if (closing) return;
      try {
        if (loggedIn) { send("PlayerLogoutCsReq", builder("PlayerLogoutCsReq")); Thread.sleep(350); }
      } catch (Exception ignored) { }
      closing = true;
      if (channel != null) channel.close();
      transport.stop();
    }
  }

  public static void main(String[] args) {
    var clients = new ArrayList<Client>(); int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
      var accounts = control.getAsJsonArray("accounts");
      require(accounts.size() == 3 && args.length == 3, "Three isolated clients are required");
      String mode = control.get("mode").getAsString();
      for (int i = 0; i < 3; i++) clients.add(new Client(Integer.parseInt(args[i])));
      var owner = clients.get(0); var a = clients.get(1); var b = clients.get(2);
      if (mode.equals("bootstrap")) {
        for (int i = 0; i < 3; i++) clients.get(i).login(accounts.get(i).getAsJsonObject());
      } else if (mode.equals("configure")) {
        owner.login(accounts.get(0).getAsJsonObject());
        owner.allowRoom();
      } else if (mode.equals("exercise")) {
        a.login(accounts.get(1).getAsJsonObject()); b.login(accounts.get(2).getAsJsonObject());
        a.train(); b.train(); int ownerUid = control.get("ownerUid").getAsInt();
        a.visit(ownerUid); b.visit(ownerUid);
        var room = a.call("SocialPlayGetOnlineRoomDataCsReq");
        require((Boolean) value(room, "OJPDDAOJJKC"), "Missing online room flag");
        require(((List<?>) value(child(room, "DJMHHMMABJL"), "player_info")).size() == 2,
            "Wrong visitor count");
        var opened = a.call("TrainCakeCatchOpenBoxCsReq");
        require(((List<?>) value(opened, "BJPHDKEBOBK")).size() == 3, "Wrong batch size");
        a.search(10); b.search(10); a.search(10);
        b.allowRoom(); a.visit(b.uid);
        var switched = child(a.call("SocialPlayGetOnlineRoomDataCsReq"), "DJMHHMMABJL");
        require(number(switched, "room_owner_uid") == b.uid, "Room switch chose the wrong owner");
        a.visit(ownerUid);
        var point = a.builder("Vector"); set(point, "x", 111); set(point, "y", 222); set(point, "z", 333);
        var motion = a.builder("JOLAHHGIFHA"); set(motion, "pos", point.build());
        var move = a.builder("JGAMELLGLGE"); set(move, "NJDCPMENIFG", motion.build());
        a.call("JGAMELLGLGE", move, "JKILAALMFIB");
        var notify = b.await("SocialPlayRoomPlayerMoveScNotify");
        require(number(notify, "room_owner_uid") == ownerUid && number(notify, "op_uid") == a.uid,
            "Wrong movement source");
        owner.login(accounts.get(0).getAsJsonObject()); owner.train();
        owner.call("TrainCakeCatchOpenBoxCsReq"); owner.search(10); owner.search(21);
        var frame = owner.builder("HJACHMJFEAH"); set(frame, "PCPDHEIEJFO", 10); set(frame, "slot", 1);
        var ground = owner.builder("IIONNFELFEF"); set(ground, "dice_slot_id", 1);
        ground.addRepeatedField(descriptor(ground.buildPartial(), "DMIOBEKLDED"), 21);
        var layout = owner.builder("BFAKMCIJFCB");
        layout.addRepeatedField(descriptor(layout.buildPartial(), "PNHLAAMNGFE"), frame.build());
        layout.addRepeatedField(descriptor(layout.buildPartial(), "PKMHMGAEAGO"), ground.build());
        var diy = owner.builder("TrainCakeCatchDiyCsReq"); set(diy, "NLJBJMDPBGN", layout.build());
        owner.call("TrainCakeCatchDiyCsReq", diy);
        var visitorRoom = b.call("SocialPlayGetOnlineRoomDataCsReq");
        var data = child(child(child(visitorRoom, "DJMHHMMABJL"), "LNMGOFCOMED"), "PKHFMJDNKOE");
        require(layout.build().equals(child(data, "NLJBJMDPBGN")), "Visitor saw the wrong layout");
        owner.visit(ownerUid);
        var full = owner.call("SocialPlayGetOnlineRoomDataCsReq");
        require(((List<?>) value(child(full, "DJMHHMMABJL"), "player_info")).size() == 3,
            "Wrong room membership");
        owner.leave(); b.leave();
        // A logs out inside the room. Login must restore its ordinary train location.
      } else if (mode.equals("verify")) {
        int ownerUid = control.get("ownerUid").getAsInt();
        for (int i = 0; i < 3; i++) {
          var client = clients.get(i); client.login(accounts.get(i).getAsJsonObject());
          client.verifyProfile(ownerUid);
          var scene = child(client.call("GetCurSceneInfoCsReq"), "scene");
          require(number(scene, "plane_id") == 10000 && number(scene, "floor_id") == 10000003,
              "Login did not restore the train");
        }
      } else throw new IllegalArgumentException("Unknown probe mode");
      var ids = clients.stream().map(client -> client.uid).toList();
      int requests = clients.stream().mapToInt(client -> client.requests).sum();
      for (var client : clients) client.close();
      System.out.println(new Gson().toJson(Map.of("passed", true, "mode", mode, "playerUids", ids,
          "nativeRequests", requests, "clientGameplayVerified", false)));
      exit = 0;
    } catch (Exception error) {
      System.err.println("Social probe failed: " + error.getClass().getSimpleName());
      System.err.println("Contract: " + error.getMessage());
    } finally { for (var client : clients) client.close(); }
    System.exit(exit);
  }
  private NativeTrainCakeSocialProbe() {}
}
