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
 * Original 3000602 Phone choices, accepted periods and normal H2 recovery; no client UI claim.
 */
public final class NativeDailyMessageProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private final Map<String, Object> wireFields = verifyWireFields();
  private Ukcp channel;
  private int mainMission, dayIndex, requests;
  private boolean mainRewardNotify;
  private int observedWallet = -1;
  private int awaitedQuest;
  private boolean questCompletionNotify;

  private static Object field(DynamicMessage m, String name) {
    var descriptor=m.getDescriptorForType().findFieldByName(name);
    require(descriptor!=null,"Undeclared 4.6 field: "+m.getDescriptorForType().getFullName()+"."+name);
    return m.getField(descriptor);
  }

  private Map<String,Object> verifyWireFields() {
    var paths=Map.ofEntries(
        Map.entry("PlayerGetTokenCsReq",List.of("account_uid","token","platform")),
        Map.entry("PlayerGetTokenScRsp",List.of("retcode","uid")),
        Map.entry("PlayerLoginCsReq",List.<String>of()),
        Map.entry("PlayerLoginScRsp",List.of("retcode","basic_info/scoin")),
        Map.entry("PlayerLoginFinishCsReq",List.<String>of()),
        Map.entry("PlayerLoginFinishScRsp",List.of("retcode")),
        Map.entry("FinishTalkMissionCsReq",List.of("sub_mission_id","talk_str")),
        Map.entry("FinishTalkMissionScRsp",List.of("retcode")),
        Map.entry("AcceptMainMissionCsReq",List.of("main_mission_id")),
        Map.entry("AcceptMainMissionScRsp",List.of("retcode")),
        Map.entry("GetMissionDataCsReq",List.<String>of()),
        Map.entry("GetMissionDataScRsp",List.of("retcode","main_mission_list/id","main_mission_list/status",
            "finished_main_mission_id_list","mission_list/id","mission_list/status","mission_list/progress")),
        Map.entry("EnterSceneCsReq",List.of("entry_id")),
        Map.entry("EnterSceneScRsp",List.of("retcode")),
        Map.entry("GetCurSceneInfoCsReq",List.<String>of()),
        Map.entry("GetCurSceneInfoScRsp",List.of("retcode","scene/entry_id","scene/plane_id","scene/floor_id",
            "scene/leader_entity_id","scene/entity_group_list/entity_list/group_id",
            "scene/entity_group_list/entity_list/inst_id","scene/entity_group_list/entity_list/npc/npc_id")),
        Map.entry("GetNpcMessageGroupCsReq",List.of("BJIPBBFIBPD")),
        Map.entry("GetNpcMessageGroupScRsp",List.of("retcode","message_group_list/id","message_group_list/status",
            "message_group_list/message_section_list/id","message_group_list/message_section_list/status",
            "message_group_list/message_section_list/item_list/item_id",
            "message_group_list/message_section_list/message_item_list")),
        Map.entry("FinishItemIdCsReq",List.of("item_id","text_id")),
        Map.entry("FinishItemIdScRsp",List.of("retcode","item_id","text_id")),
        Map.entry("FinishSectionIdCsReq",List.of("section_id")),
        Map.entry("FinishSectionIdScRsp",List.of("retcode","section_id")),
        Map.entry("GetQuestDataCsReq",List.<String>of()),
        Map.entry("GetQuestDataScRsp",List.of("retcode","quest_list/id","quest_list/progress","quest_list/status","quest_list/finish_time")),
        Map.entry("GetDailyActiveInfoCsReq",List.<String>of()),
        Map.entry("GetDailyActiveInfoScRsp",List.of("retcode","daily_active_point")),
        Map.entry("TakeQuestRewardCsReq",List.of("ODJIJFJCHHB")),
        Map.entry("TakeQuestRewardScRsp",List.of("retcode","succ_quest_id_list")),
        Map.entry("MissionRewardScNotify",List.of("main_mission_id","reward/item_list/item_id","reward/item_list/num")),
        Map.entry("PlayerSyncScNotify",List.of("basic_info/scoin")));
    var proof=new TreeMap<String,Object>();
    for(var entry:paths.entrySet()) {
      var fields=new TreeMap<String,Object>();
      for(var path:entry.getValue()) {
        var type=protocol.clientSchema().message(entry.getKey());
        var numbers=new ArrayList<Integer>();
        com.google.protobuf.Descriptors.FieldDescriptor terminal=null;
        var names=path.split("/");
        for(int i=0;i<names.length;i++) {
          terminal=type.findFieldByName(names[i]);
          require(terminal!=null,"Undeclared 4.6 path: "+entry.getKey()+"."+path);
          numbers.add(terminal.getNumber());
          if(i+1<names.length) type=terminal.getMessageType();
        }
        fields.put(path,Map.of("tags",numbers,"type",terminal.getType().name(),"repeated",terminal.isRepeated()));
      }
      proof.put(entry.getKey(),Map.of("command",protocol.clientCommand(entry.getKey()),"fields",fields));
    }
    require(protocol.clientSchema().message("Mission").findFieldByName("finish_time")==null,
        "The frozen 4.6 Mission wire shape changed; inspect its declared fields");
    proof.put("MissionTimestampDeclared",false);
    return proof;
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

  private static final int MAIN=3000602,FIRST=300060201,GOAL=300060202,SECTION=1211401;

  private void progress(int first, int goal, boolean completed, String label) throws Exception {
    var data=call("GetMissionDataCsReq");
    var mains=((List<?>)field(data,"main_mission_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==MAIN).toList();
    check(mains.size()==1 && ((EnumValueDescriptor)field(mains.getFirst(),"status")).getNumber()==(completed?2:1)
        && ((List<?>)field(data,"finished_main_mission_id_list")).contains(MAIN)==completed,label+"-main");
    var subs=new HashMap<Integer,DynamicMessage>();
    for(var raw:(List<?>)field(data,"mission_list")) { var row=(DynamicMessage)raw;subs.put(number(row,"id"),row); }
    check(subs.containsKey(FIRST) && number(subs.get(FIRST),"progress")==first
        && ((EnumValueDescriptor)field(subs.get(FIRST),"status")).getNumber()==(first==0?1:2),label+"-first-talk");
    check(goal<0?!subs.containsKey(GOAL):subs.containsKey(GOAL) && number(subs.get(GOAL),"progress")==goal
        && ((EnumValueDescriptor)field(subs.get(GOAL),"status")).getNumber()==(goal==0?1:2),label+"-phone-goal");
  }

  private DynamicMessage phone() throws Exception {
    var response=call("GetNpcMessageGroupCsReq",Map.of("BJIPBBFIBPD",List.of(48)),0);
    var groups=((List<?>)field(response,"message_group_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==11125).toList();
    require(groups.size()==1,"Missing or duplicate original Phone group 11125");
    var sections=((List<?>)field(groups.getFirst(),"message_section_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==SECTION).toList();
    require(sections.size()<=1,"Duplicate original Phone section 1211401");
    return sections.isEmpty()?null:sections.getFirst();
  }

  private static List<Integer> history(DynamicMessage phone) {
    return ((List<?>)field(phone,"item_list")).stream().map(DynamicMessage.class::cast)
        .map(row->number(row,"item_id")).toList();
  }
  @SuppressWarnings("unchecked")
  private static List<Integer> offered(DynamicMessage phone) { return (List<Integer>)field(phone,"message_item_list"); }

  private void phone(List<Integer> history, List<Integer> choices, boolean finished, String label) throws Exception {
    var value=phone();
    check(value!=null && history(value).equals(history) && offered(value).equals(choices)
        && ((EnumValueDescriptor)field(value,"status")).getNumber()==(finished?2:1),label);
  }

  private void reply(int id, int text, int code, String label) throws Exception {
    var data=call("FinishItemIdCsReq",Map.of("item_id",id,"text_id",text),code);
    check(number(data,"item_id")==id && number(data,"text_id")==text,label);
  }

  private void finish(int id, int code, String label) throws Exception {
    var data=call("FinishSectionIdCsReq",Map.of("section_id",id),code);
    check(number(data,"section_id")==id,label);
  }

  private List<Integer> prefix(int first) { return List.of(121140201,121140212,121140213,first,121140204); }
  private List<Integer> end(int first, int second) {
    return List.of(121140201,121140212,121140213,first,121140204,second,
        second==121140205?121140206:121140208,121140209,121140210);
  }

  private void origin() throws Exception {
    call("EnterSceneCsReq",Map.of("entry_id",200020102),0);
    var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");
    check(number(scene,"entry_id")==200020102 && number(scene,"plane_id")==20002 && number(scene,"floor_id")==20002001,
        "original-Mission-entrance-and-independent-finish-gate");
    var found=new ArrayList<DynamicMessage>();
    for(var rawGroup:(List<?>)field(scene,"entity_group_list")) for(var raw:(List<?>)field((DynamicMessage)rawGroup,"entity_list")) {
      var row=(DynamicMessage)raw;
      if(number(row,"group_id")==61 && number(row,"inst_id")==400001
          && row.hasField(row.getDescriptorForType().findFieldByName("npc"))) found.add(row);
    }
    check(found.size()==1 && number((DynamicMessage)field(found.getFirst(),"npc"),"npc_id")==10206,
        "actual-source-shared-G61-NPC10206-instance400001");
  }

  private Map<String,Object> execute(JsonObject input, java.io.BufferedReader control) throws Exception {
    mainMission=MAIN;dayIndex=input.get("dayIndex").getAsInt();
    String phase=input.get("phase").getAsString(),scenario=input.get("scenario").getAsString();
    String at=input.get("expiredAt").getAsString();
    int first=input.get("firstChoice").getAsInt(),second=input.get("secondChoice").getAsInt();
    require(Set.of(1,2).contains(dayIndex) && Set.of("open","finish","claimed","boundary-reject").contains(phase)
        && Set.of("settled","expired-unpaid").contains(scenario)
        && Set.of("noReplies","oneReply","bothReplies").contains(at)
        && Set.of(121140202,121140203).contains(first) && Set.of(121140205,121140207).contains(second),"Invalid finite Phone case");
    var token=call("PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),
        "token",input.get("comboToken").getAsString(),"platform",1),0);
    int uid=number(token,"uid");var login=call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");
    int wallet=number((DynamicMessage)field(login,"basic_info"),"scoin");
    System.out.println("HKRPG_DAILY_CLOCK_FIXTURE_READY:"+uid);System.out.flush();
    require("resume".equals(control.readLine()),"Controlled clock did not resume");
    int prior=scenario.equals("settled")?(dayIndex-1)*5000:0;
    check(wallet==(phase.equals("claimed")?prior+5000:prior),"actual-login-wallet-"+dayIndex+"-"+phase);
    if(phase.equals("boundary-reject")) {
      // This first post-clock request must not trigger a query/refresh/expiry before rejection.
      require(dayIndex==2 && scenario.equals("expired-unpaid"),"Boundary case must be an unpaid previous run");
      if(at.equals("bothReplies")) finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"old-finish-rejected-before-any-mission-query");
      else reply(at.equals("noReplies")?first:second,0,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"old-reply-rejected-before-any-mission-query");
    } else if(phase.equals("open")) {
      progress(0,-1,false,"actual-automatic-source-assignment");
      check(phone()==null,"new-goal-does-not-borrow-prior-Phone-before-source-talk");
      quest(1,"new-active-target-doing");points(0,"new-period-active-points-zero");
      origin();
      call("FinishTalkMissionCsReq",Map.of("sub_mission_id",FIRST,"talk_str","foreign-phone-talk"),Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal());
      call("FinishTalkMissionCsReq",Map.of("sub_mission_id",FIRST,"talk_str","Mission_300060201"),0);
      check(true,"original-first-talk-accepts-source-Phone-goal");
      progress(1,0,false,"source-first-talk-does-not-complete-Phone");
      phone(List.of(121140201,121140212,121140213),List.of(121140202,121140203),false,"fresh-original-three-NPC-opening");
      finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"section-ending-cannot-skip-original-choices");
      reply(121140205,0,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"second-choice-cannot-skip-first-choice");
      reply(first,1,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"unverified-text-override-does-not-change-original-Phone");
      boolean oldUnpaid=scenario.equals("expired-unpaid") && dayIndex==1;
      if(!oldUnpaid || !at.equals("noReplies")) {
        reply(first,0,0,"actual-original-first-player-reply");
        phone(prefix(first),List.of(121140205,121140207),false,"original-half-Phone-is-saved");
      }
      if(oldUnpaid && at.equals("bothReplies")) {
        reply(second,0,0,"unpaid-original-second-reply");
        phone(end(first,second),List.of(),false,"unpaid-original-end-is-not-a-section-completion");
      }
      claim(Retcode.QUEST_NOT_FINISH.getVal(),"unfinished-active-claim-rejected");
      progress(1,0,false,"unsubmitted-section-keeps-main-and-goal-doing");
    } else if(phase.equals("finish")) {
      progress(1,0,false,"same-UID-half-Phone-goal-restored");
      phone(prefix(first),List.of(121140205,121140207),false,"same-UID-half-Phone-history-and-second-options-restored");
      quest(1,"partial-Phone-keeps-active-target-doing");points(0,"partial-Phone-keeps-points-zero");
      reply(first,0,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"first-reply-is-not-replayed-after-H2-or-reconnect");
      reply(second,0,0,"actual-original-second-player-reply");
      phone(end(first,second),List.of(),false,"original-nine-message-end-still-requires-final-section-request");
      progress(1,0,false,"both-replies-alone-do-not-pay-main-commission");
      check(!mainRewardNotify,"no-main-credit-before-final-section-request");
      awaitedQuest=2100003;questCompletionNotify=false;mainRewardNotify=false;
      finish(SECTION,0,"actual-final-section-completes-only-this-accepted-period");
      check(questCompletionNotify,"active-target-completion-notification-before-section-response");
      check(mainRewardNotify && observedWallet==prior+5000,"original-main-5000-credit-notify-and-wallet-before-section-response");
      awaitedQuest=0;progress(1,1,true,"actual-source-main-and-goal-finished");
      phone(end(first,second),List.of(),true,"finished-Phone-query-preserves-actual-two-player-choices");
      quest(2,"active-target-finished-before-claim");points(0,"active-points-not-auto-claimed");
      claim(0,"actual-original-active-200-claimed");points(200,"exact-original-200-active-points");quest(3,"actual-active-target-closed");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"active-reward-cannot-pay-twice");
      finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"final-section-cannot-pay-twice");
      reply(first,0,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"finished-reply-cannot-pay-twice");
      call("AcceptMainMissionCsReq",Map.of("main_mission_id",MAIN),Retcode.MISSION_ALREADY_DONE.getVal());
      check(true,"same-period-main-not-reopened");
    } else {
      progress(1,1,true,"normal-H2-restored-main-and-goal-stay-finished");
      phone(end(first,second),List.of(),true,"normal-H2-restored-two-actual-choices");
      quest(3,"normal-H2-restored-active-target-closed");points(200,"normal-H2-restored-active-points");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"normal-H2-restored-active-reward-not-repaid");
      finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"normal-H2-restored-section-not-repaid");
      call("AcceptMainMissionCsReq",Map.of("main_mission_id",MAIN),Retcode.MISSION_ALREADY_DONE.getVal());
      check(true,"normal-H2-restored-main-not-repaid");
    }
    var result=new LinkedHashMap<String,Object>();
    result.put("passed",true);result.put("uid",uid);result.put("mainMission",MAIN);result.put("dayIndex",dayIndex);
    result.put("phase",phase);result.put("scenario",scenario);result.put("expiredAt",at);
    result.put("firstChoice",first);result.put("secondChoice",second);result.put("loginWallet",wallet);
    result.put("checks",checks);result.put("requests",requests);result.put("wireFieldsVerifiedBeforeConnect",true);
    result.put("mainRewardNotifyBeforeResponseVerified",mainRewardNotify);
    result.put("immediateQuestCompletionNotifyVerified",questCompletionNotify);
    result.put("oldBoundaryRequestBeforeAnyQuery",phase.equals("boundary-reject"));
    result.put("sourceClientScriptExecutorVerified",false);result.put("clientGameplayVerified",false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeDailyMessageProbe();
    if(args.length==1 && args[0].equals("--wire-fields")) {
      System.out.println(new Gson().toJson(probe.wireFields));
      return;
    }
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
