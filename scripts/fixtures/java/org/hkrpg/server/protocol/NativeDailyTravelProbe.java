package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
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
 * Original daily travel assignment, actual entry transitions, two daily payouts and H2 recovery.
 */
public final class NativeDailyTravelProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private int mainMission, dayIndex, requests;
  private boolean mainRewardNotify;
  private int observedWallet = -1;
  private int awaitedQuest;
  private boolean questCompletionNotify;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  private void check(boolean value, String reason) {
    require(value, reason);
    checks.add(reason);
  }

  private DynamicMessage.Builder request(String name, Map<String, Object> fields) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    return b;
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int code) throws Exception {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name),
                new byte[0],
                request(name, fields).build().toByteArray()));
    try {
      channel.write(packet);
      requests++;
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    int command = protocol.clientCommand(response);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("KCP connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (awaitedQuest != 0 && frame.command() == protocol.clientCommand("GetQuestDataScRsp")) {
        var data = protocol.clientSchema().parse("GetQuestDataScRsp", frame.body());
        require(data.getUnknownFields().asMap().isEmpty(), "Unknown quest notification fields");
        for (var raw : (List<?>) field(data, "quest_list")) {
          var row = (DynamicMessage) raw;
          if (number(row, "id") == awaitedQuest
              && number(row, "progress") == 1
              && ((EnumValueDescriptor) field(row, "status")).getNumber() == 2)
            questCompletionNotify = true;
        }
      }
      if (frame.command() == protocol.clientCommand("MissionRewardScNotify")) {
        var notify = protocol.clientSchema().parse("MissionRewardScNotify", frame.body());
        require(notify.getUnknownFields().asMap().isEmpty(), "Unknown main reward fields");
        if (number(notify, "main_mission_id") != mainMission) continue;
        var reward = (DynamicMessage) field(notify, "reward");
        var items = (List<?>) field(reward, "item_list");
        require(items.size() == 1, "Wrong daily main reward item count");
        var item = (DynamicMessage) items.getFirst();
        require(
            number(item, "item_id") == 2 && number(item, "num") == 5000,
            "Wrong original daily credit notification");
        mainRewardNotify = true;
      }
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var notify = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        var basic = notify.getDescriptorForType().findFieldByName("basic_info");
        if (basic != null && notify.hasField(basic))
          observedWallet = number((DynamicMessage) notify.getField(basic), "scoin");
      }
      if (frame.command() != command) continue;
      var m = protocol.clientSchema().parse(response, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      require(
          number(m, "retcode") == code,
          "Response rejected: " + response + " code=" + number(m, "retcode"));
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private void talk(int id, String text, int code, String label) throws Exception {
    call("FinishTalkMissionCsReq", Map.of("sub_mission_id", id, "talk_str", text), code);
    check(true, label);
  }

  private void entry(int id, int plane, int floor) throws Exception {
    call("EnterSceneCsReq", Map.of("entry_id", id), 0);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    check(number(scene, "entry_id") == id && number(scene, "plane_id") == plane
        && number(scene, "floor_id") == floor && number(scene, "leader_entity_id") > 0,
        "actual-original-scene-" + id);
  }

  private void origin() throws Exception {
    if (mainMission == 3012001) entry(1010201,10102,10102001);
    else entry(1020204,10202,10202004);
  }

  private void destination() throws Exception {
    if (mainMission == 3012001) entry(1010203,10102,10102003);
    else entry(1020201,10202,10202001);
  }

  private int first() { return mainMission == 3012001 ? 301200101 : 302020401; }
  private int second() { return mainMission == 3012001 ? 301200107 : 302020402; }
  private int travel() { return mainMission == 3012001 ? 301200108 : 302020403; }
  private int last() { return mainMission == 3012001 ? 301200109 : 302020404; }

  private void progress(boolean completed, boolean partial, String label) throws Exception {
    var data = call("GetMissionDataCsReq");
    var mains = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(data,"main_mission_list")) {
      var row=(DynamicMessage) raw;
      if (number(row,"id") == mainMission) mains.add(row);
    }
    require(mains.size()==1,"Missing original automatic commission");
    require(((EnumValueDescriptor)field(mains.getFirst(),"status")).getNumber()==(completed?2:1),"Wrong main state");
    require(((List<?>)field(data,"finished_main_mission_id_list")).contains(mainMission)==completed,"Wrong main completion history");
    var subs=new HashMap<Integer,DynamicMessage>();
    for (var raw : (List<?>)field(data,"mission_list")) {
      var row=(DynamicMessage)raw;
      subs.put(number(row,"id"),row);
    }
    require(subs.containsKey(first()),"Original first objective not accepted");
    require(number(subs.get(first()),"progress")==((partial||completed)?1:0),"Wrong first talk progress");
    if (partial||completed) {
      require(number(subs.get(second()),"progress")==1,"Wrong second original talk progress");
      require(subs.containsKey(travel()) && number(subs.get(travel()),"progress")== (completed?1:0),"Wrong actual travel progress");
      if (completed) require(number(subs.get(last()),"progress")==1,"Final original talk did not complete");
      else require(!subs.containsKey(last()),"Final talk accepted before actual travel");
    } else require(!subs.containsKey(travel()),"Travel accepted before original prerequisites");
    check(true,label);
  }

  private void traveled(String label) throws Exception {
    var data=call("GetMissionDataCsReq");
    var rows=((List<?>)field(data,"mission_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==travel()).toList();
    check(rows.size()==1 && number(rows.getFirst(),"progress")==1
        && ((EnumValueDescriptor)field(rows.getFirst(),"status")).getNumber()==2,label);
  }

  private void quest(int status, String label) throws Exception {
    var data = call("GetQuestDataCsReq");
    var matches = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(data, "quest_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "id") == 2100003) matches.add(row);
    }
    require(matches.size() == 1, "Missing or duplicate original daily active target");
    var row = matches.getFirst();
    check(
        ((EnumValueDescriptor) field(row, "status")).getNumber() == status
            && number(row, "progress") == (status == 1 ? 0 : 1)
            && ((Long) field(row, "finish_time") > 0) == (status != 1),
        label);
  }

  private void points(int expected, String label) throws Exception {
    var data = call("GetDailyActiveInfoCsReq");
    check(number(data, "daily_active_point") == expected, label);
  }

  private void claim(int code, String label) throws Exception {
    var data = call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", List.of(2100003)), code);
    check(
        ((List<?>) field(data, "succ_quest_id_list"))
            .equals(code == 0 ? List.of(2100003) : List.of()),
        label);
  }

  private Map<String,Object> execute(JsonObject credentials, java.io.BufferedReader control) throws Exception {
    mainMission=credentials.get("mainMission").getAsInt();
    dayIndex=credentials.get("dayIndex").getAsInt();
    require(Set.of(3012001,3020204).contains(mainMission) && Set.of(1,2).contains(dayIndex),"Unknown source case");
    var token=call("PlayerGetTokenCsReq",Map.of("account_uid",credentials.get("accountUid").getAsString(),
        "token",credentials.get("comboToken").getAsString(),"platform",1),0);
    int uid=number(token,"uid");
    var login=call("PlayerLoginCsReq"); call("PlayerLoginFinishCsReq");
    System.out.println("HKRPG_DAILY_CLOCK_FIXTURE_READY:"+uid); System.out.flush();
    require("resume".equals(control.readLine()),"Clock fixture did not resume");
    String phase=credentials.get("phase").getAsString();
    boolean completed=Set.of("unclaimed","claimed").contains(phase);
    int expectedCredits=(dayIndex-1)*5000+(completed?5000:0);
    int wallet=number((DynamicMessage)field(login,"basic_info"),"scoin");
    check(wallet==expectedCredits,"actual-wallet-restored-"+dayIndex+"-"+phase);
    progress(completed,!phase.equals("first"),"source-assignment-and-objectives-"+dayIndex+"-"+phase);
    quest(phase.equals("claimed")?3:completed?2:1,"actual-active-objective-"+dayIndex+"-"+phase);
    points(phase.equals("claimed")?200:0,"actual-active-points-"+dayIndex+"-"+phase);
    if (phase.equals("first")) {
      destination(); progress(false,false,"arrival-before-prerequisites-cannot-complete-travel"); origin();
      talk(last(),"Mission_"+last(),Retcode.MISSION_NOT_DOING.getVal(),"final-talk-before-actual-travel-rejected");
      talk(first(),"wrong-travel-dialogue",Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),"wrong-source-dialogue-rejected");
      talk(first(),"Mission_"+first(),0,"source-first-talk"); talk(second(),"Mission_"+second(),0,"source-second-talk");
      origin(); progress(false,true,"same-floor-entry-keeps-travel-unfinished");
      claim(Retcode.QUEST_NOT_FINISH.getVal(),"unfinished-daily-active-claim-rejected");
    } else if (phase.equals("finish")) {
      destination(); traveled("actual-original-destination-completes-travel");
      if(mainMission==3020204) origin();
      awaitedQuest=2100003; questCompletionNotify=false; mainRewardNotify=false;
      talk(last(),"Mission_"+last(),0,"original-final-talk-completes-daily");
      check(questCompletionNotify,"active-completion-notify-before-final-talk-response");
      check(mainRewardNotify && observedWallet==dayIndex*5000,"source-credit-notify-before-final-talk-response");
      awaitedQuest=0; progress(true,true,"original-main-and-travel-finished"); quest(2,"actual-daily-active-finished");
      points(0,"daily-active-not-auto-claimed");
      talk(last(),"Mission_"+last(),Retcode.MISSION_NOT_DOING.getVal(),"source-final-talk-not-replayed");
      call("AcceptMainMissionCsReq",Map.of("main_mission_id",mainMission),Retcode.MISSION_ALREADY_DONE.getVal());
      check(true,"same-day-source-commission-not-restarted");
    } else if(phase.equals("unclaimed")) {
      claim(0,"actual-source-200-active-points-claimed"); points(200,"exact-200-active-points"); quest(3,"actual-source-quest-closed");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"duplicate-active-claim-rejected");
    } else {
      require(phase.equals("claimed"),"Unknown probe phase");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"restored-source-claim-not-repaid");
      call("AcceptMainMissionCsReq",Map.of("main_mission_id",mainMission),Retcode.MISSION_ALREADY_DONE.getVal());
      check(true,"restored-main-not-repaid");
    }
    var result=new LinkedHashMap<String,Object>();
    result.put("passed",true);result.put("uid",uid);result.put("mainMission",mainMission);result.put("dayIndex",dayIndex);
    result.put("phase",phase);result.put("checks",checks);result.put("requests",requests);result.put("loginWallet",wallet);
    result.put("immediateQuestCompletionNotifyVerified",questCompletionNotify);
    result.put("mainRewardNotifyBeforeResponseVerified",mainRewardNotify);result.put("clientGameplayVerified",false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeDailyTravelProbe();
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
                  require(frame != null, "Truncated mission frame");
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
                  null, new IllegalStateException("Mission probe disconnected"));
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
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
