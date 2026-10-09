package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
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
 * External-only native lifecycle traffic; no game UI, movement replay or group script simulation.
 */
public final class NativeWolfBroLifecycleProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int queries, notifications, starts, archives, restores, quits, rejections;
  private int sceneMotionQueries;
  private int controlQueries, controlRequests, controlNotifications;
  private final List<Integer> controlNotificationStates = new ArrayList<>();
  private final List<String> checks = new ArrayList<>();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private DynamicMessage playerMotion() throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    sceneMotionQueries++;
    int leader = number(scene, "leader_entity_id");
    for (var group : (List<?>) field(scene, "entity_group_list")) {
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "entity_id") == leader) return (DynamicMessage) field(entity, "motion");
      }
    }
    throw new IllegalStateException("Actual scene leader missing");
  }

  private void observe(Frame f) throws Exception {
    if (f.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
      var m = protocol.clientSchema().parse("SceneGroupRefreshScNotify", f.body());
      if (number(m, "floor_id") == 10301001)
        for (var raw : (List<?>) field(m, "group_refresh_list")) {
          var group = (DynamicMessage) raw;
          if (number(group, "group_id") == 156) {
            controlNotifications++;
            controlNotificationStates.add(number(group, "state"));
          }
        }
    }
    if (f.command() == 6548) {
      var m = protocol.clientSchema().parse("WolfBroGameDataChangeScNotify", f.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown WolfBro notification");
      var d = (DynamicMessage) field(m, "game_data");
      require(number(d, "id") == 3, "Wrong WolfBro notification level");
      require(
          number(d, "CHCOPKAKBNO") == 0 && !((Boolean) field(d, "is_bullet_activated")),
          "Unbacked free bullets");
      notifications++;
    }
  }

  private void controlState(int expected) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    controlQueries++;
    var matches =
        ((List<?>) field(scene, "entity_group_list"))
            .stream()
                .map(DynamicMessage.class::cast)
                .filter(g -> number(g, "group_id") == 156)
                .toList();
    require(
        matches.size() == 1 && number(matches.getFirst(), "state") == expected,
        "Actual source control state mismatch: " + expected);
  }

  private void changeControl(int target) throws Exception {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message("GroupStateInfo"));
    b.setField(b.getDescriptorForType().findFieldByName("group_id"), 156);
    b.setField(b.getDescriptorForType().findFieldByName("group_state"), target);
    var g = b.build();
    var r = call("GroupStateChangeCsReq", Map.of("group_state_info", g));
    require(
        number(r, "retcode") == 0 && g.equals(field(r, "group_state_info")),
        "Original control transition rejected");
    controlRequests++;
    controlState(target);
  }

  private DynamicMessage point(int x, int y, int z) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message("Vector"));
    b.setField(b.getDescriptorForType().findFieldByName("x"), x);
    b.setField(b.getDescriptorForType().findFieldByName("y"), y);
    b.setField(b.getDescriptorForType().findFieldByName("z"), z);
    return b.build();
  }

  private DynamicMessage motion(boolean checkpoint) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message("MotionInfo"));
    b.setField(
        b.getDescriptorForType().findFieldByName("pos"),
        checkpoint ? point(150076, -37037, 192426) : point(127547, -30482, 158066));
    b.setField(b.getDescriptorForType().findFieldByName("rot"), point(0, -90000, 0));
    return b.build();
  }

  private DynamicMessage originalStart() throws Exception {
    var g = DynamicMessage.newBuilder(protocol.clientSchema().message("GroupStateInfo"));
    g.setField(g.getDescriptorForType().findFieldByName("group_id"), 156);
    g.setField(g.getDescriptorForType().findFieldByName("group_state"), 1);
    return call(
        "StartWolfBroGameCsReq",
        Map.of("id", 3, "motion", motion(false), "IDANCEPOOEI", g.build()));
  }

  private DynamicMessage game(DynamicMessage response, boolean active, boolean checkpoint) {
    require(number(response, "retcode") == 0, "Original WolfBro operation rejected");
    var d = (DynamicMessage) field(response, "game_data");
    require(
        number(d, "id") == 3 && d.getUnknownFields().asMap().isEmpty(),
        "Wrong source level or unknown data fields");
    require(!((String) field(d, "uuid")).isBlank() == active, "Wrong active identity");
    require(
        number(d, "CHCOPKAKBNO") == 0
            && !((Boolean) field(d, "is_bullet_activated"))
            && ((List<?>) field(d, "PAOEJKABFEP")).isEmpty(),
        "Seeded unsupported ammunition");
    var a = (DynamicMessage) field(d, "BBEMIGELGJC");
    require(
        motion(checkpoint).equals(field(a, "motion")),
        "Signed source checkpoint coordinates changed");
    require(
        number(a, "CHCOPKAKBNO") == 0
            && !((Boolean) field(a, "is_bullet_activated"))
            && ((List<?>) field(a, "PAOEJKABFEP")).isEmpty(),
        "Seeded unsupported archived ammunition");
    return d;
  }

  private static String hash(DynamicMessage d) throws Exception {
    return HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(d.toByteArray()));
  }

  private void reject(DynamicMessage response) {
    require(number(response, "retcode") != 0, "Stale or unrelated operation accepted");
    rejections++;
  }

  private DynamicMessage query(boolean active, boolean checkpoint) throws Exception {
    queries++;
    return game(call("GetWolfBroGameDataCsReq", Map.of("id", 3)), active, checkpoint);
  }

  private Map<String, Object> execute(JsonObject credentials, java.io.BufferedReader unused)
      throws Exception {
    String mode = credentials.get("mode").getAsString();
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1));
    require(number(token, "retcode") == 0, "Token rejected");
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    require(number(login, "retcode") == 0, "Login rejected");
    int jades = number((DynamicMessage) field(login, "basic_info"), "hcoin");
    require(jades == 0, "Checkpoint flow manufactured jades");
    call("PlayerLoginFinishCsReq");
    if (credentials.has("restoredCoordinatesExpected")
        && credentials.get("restoredCoordinatesExpected").getAsBoolean()) {
      require(playerMotion().equals(motion(true)), "Saved player coordinates lost before re-entry");
      controlState(2);
      checks.add("saved-player-position-and-rotation-retained-before-original-re-entry");
    }
    require(
        number(call("EnterSceneCsReq", Map.of("entry_id", 1030102)), "retcode") == 0,
        "Original entrance rejected");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require(
        number(scene, "plane_id") == 10301
            && number(scene, "floor_id") == 10301001
            && number(scene, "entry_id") == 1030102,
        "Wrong original scene");
    checks.add("actual-original-1030102-entrance-and-10301-10301001-scene");
    DynamicMessage d;
    if (mode.equals("checkpoint")) {
      queries++;
      var before = call("GetWolfBroGameDataCsReq", Map.of("id", 3));
      require(
          number(before, "retcode") == 0
              && ((String) field((DynamicMessage) field(before, "game_data"), "uuid")).isEmpty(),
          "Query started an unplayed game");
      d = game(originalStart(), true, false);
      starts++;
      controlState(1);
      changeControl(2);
      require(notifications == 1, "Start notification missing or duplicated");
      d =
          game(
              call("ArchiveWolfBroGameCsReq", Map.of("id", 3, "motion", motion(true))), true, true);
      archives++;
      changeControl(1);
      require(notifications == 2, "Archive notification missing or duplicated");
      reject(originalStart());
      reject(call("GetWolfBroGameDataCsReq", Map.of("id", 999999)));
      require(notifications == 2, "Rejected requests mutated data");
      require(d.equals(query(true, true)), "Repeated query changed checkpoint");
      checks.add("actual-original-start-and-signed-checkpoint-without-free-ammunition-or-rewards");
    } else if (mode.equals("resume")) {
      d = query(true, true);
      require(
          hash(d).equals(credentials.get("activeDataHash").getAsString()),
          "Active checkpoint changed after reconnect or normal H2 restart");
      require(
          d.equals(game(call("RestoreWolfBroGameArchiveCsReq", Map.of("id", 3)), true, true)),
          "Restored checkpoint response changed");
      restores++;
      controlState(2);
      changeControl(2);
      require(
          playerMotion().equals(motion(true)),
          "Restore response did not update actual scene leader");
      checks.add("restore-actually-updates-signed-player-position-and-rotation");
      reject(originalStart());
      require(
          d.equals(query(true, true)) && notifications == 0,
          "Resume restarted or changed the actual game");
      checks.add("actual-active-identity-and-signed-checkpoint-restored-without-reset");
    } else if (mode.equals("quit")) {
      d = query(true, true);
      require(
          hash(d).equals(credentials.get("activeDataHash").getAsString()),
          "Checkpoint lost before quit");
      String prior = (String) field(d, "uuid");
      d = game(call("QuitWolfBroGameCsReq", Map.of("id", 3)), false, true);
      quits++;
      controlState(0);
      require(notifications == 1, "Quit notification missing");
      reject(call("RestoreWolfBroGameArchiveCsReq", Map.of("id", 3)));
      reject(call("QuitWolfBroGameCsReq", Map.of("id", 3)));
      var fresh = game(originalStart(), true, false);
      starts++;
      controlState(1);
      require(!prior.equals(field(fresh, "uuid")), "Quit/restart reused an old identity");
      game(call("ArchiveWolfBroGameCsReq", Map.of("id", 3, "motion", motion(true))), true, true);
      archives++;
      d = game(call("QuitWolfBroGameCsReq", Map.of("id", 3)), false, true);
      quits++;
      controlState(0);
      require(
          d.equals(query(false, true)) && notifications == 4, "Fresh game/quit snapshot changed");
      checks.add("quit-retains-checkpoint-and-fresh-start-creates-a-new-actual-identity");
    } else {
      require(mode.equals("closed"), "Unknown checkpoint mode");
      d = query(false, true);
      require(
          hash(d).equals(credentials.get("closedDataHash").getAsString()),
          "Quit checkpoint changed after normal H2 restart");
      reject(call("RestoreWolfBroGameArchiveCsReq", Map.of("id", 3)));
      reject(call("QuitWolfBroGameCsReq", Map.of("id", 3)));
      require(
          d.equals(query(false, true)) && notifications == 0, "Closed query restarted the game");
      controlState(0);
      checks.add("quit-checkpoint-survives-normal-h2-restart-and-queries-remain-read-only");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("mode", mode);
    result.put("dataHash", hash(d));
    result.put("uuid", field(d, "uuid"));
    result.put("actualQueries", queries);
    result.put("actualStarts", starts);
    result.put("actualArchives", archives);
    result.put("actualRestores", restores);
    result.put("actualQuits", quits);
    result.put("actualSceneMotionQueries", sceneMotionQueries);
    result.put("playerCoordinatesRestoredAndQueried", mode.equals("resume"));
    result.put("actualControlQueries", controlQueries);
    result.put("actualControlRequests", controlRequests);
    result.put("controlNotifications", controlNotifications);
    result.put("controlNotificationStates", controlNotificationStates);
    result.put("controlStateRestoredAndQueried", mode.equals("resume"));
    result.put("notifications", notifications);
    result.put("rejections", rejections);
    result.put("jades", jades);
    result.put("checks", checks);
    result.put("clientGameplayVerified", false);
    result.put("movementAndGroupStateRestored", false);
    result.put("bulletsAndFullScriptsVerified", false);
    return result;
  }

  private Frame raw(int command, byte[] payload, int response) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], payload));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Native WolfBro connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    String response = name.replace("CsReq", "ScRsp");
    var frame =
        raw(
            protocol.clientCommand(name),
            b.build().toByteArray(),
            protocol.clientCommand(response));
    var m = protocol.clientSchema().parse(response, frame.body());
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown WolfBro response fields");

    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeWolfBroLifecycleProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
          new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var credentials = JsonParser.parseString(control.readLine()).getAsJsonObject();
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
                  require(frame != null, "Truncated WolfBro frame");
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
                  null, new IllegalStateException("WolfBro probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials, control)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      // stop() already closes all channels; a second close races the KCP listener cleanup.
      client.stop();
    }
    System.exit(exit);
  }
}
