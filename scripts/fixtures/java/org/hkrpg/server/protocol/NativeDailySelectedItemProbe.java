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
 * Original finite 3011605/3011608 inventory submission and normal H2 recovery; client Acts/UI not claimed.
 */
public final class NativeDailySelectedItemProbe {
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
  private Map<String,Object> actorWire=Map.of();
  private Map<String,Object> followupWire=Map.of();
  private int awaitedQuest;
  private boolean questCompletionNotify;

  private static Object field(DynamicMessage m, String name) {
    var descriptor=m.getDescriptorForType().findFieldByName(name);
    require(descriptor!=null,"Undeclared 4.6 field: "+m.getDescriptorForType().getFullName()+"."+name);
    return m.getField(descriptor);
  }

  private Map<String,Object> verifyWireFields() {
    var paths=Map.ofEntries(
        Map.entry("GetFirstTalkByPerformanceNpcCsReq",List.of("performance_id_list")),
        Map.entry("GetFirstTalkByPerformanceNpcScRsp",List.of("retcode","npc_meet_status_list/performance_id","npc_meet_status_list/is_meet")),
        Map.entry("FinishFirstTalkByPerformanceNpcCsReq",List.of("performance_id")),
        Map.entry("FinishFirstTalkByPerformanceNpcScRsp",List.of("retcode","performance_id","reward")),
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
            "scene/leader_entity_id","scene/entity_group_list/entity_list/entity_id","scene/entity_group_list/entity_list/group_id",
            "scene/entity_group_list/entity_list/inst_id","scene/entity_group_list/entity_list/npc/npc_id",
            "scene/entity_group_list/entity_list/prop/prop_id")),
        Map.entry("GetNpcMessageGroupCsReq",List.of("BJIPBBFIBPD")),
        Map.entry("GetNpcMessageGroupScRsp",List.of("retcode","message_group_list/id","message_group_list/status",
            "message_group_list/message_section_list/id","message_group_list/message_section_list/status",
            "message_group_list/message_section_list/item_list/item_id",
            "message_group_list/message_section_list/message_item_list")),
        Map.entry("FinishItemIdCsReq",List.of("item_id","text_id")),
        Map.entry("FinishItemIdScRsp",List.of("retcode","item_id","text_id")),
        Map.entry("FinishSectionIdCsReq",List.of("section_id")),
        Map.entry("FinishSectionIdScRsp",List.of("retcode","section_id")),
        Map.entry("FinishCosumeItemMissionCsReq",List.of("sub_mission_id","item_list/item_list/item_id","item_list/item_list/num")),
        Map.entry("FinishCosumeItemMissionScRsp",List.of("retcode","sub_mission_id")),
        Map.entry("GetBagCsReq",List.<String>of()),
        Map.entry("GetBagScRsp",List.of("retcode","material_list/tid","material_list/num")),
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
    require(protocol.clientCommand("GetFirstTalkByPerformanceNpcCsReq")==2142
        && protocol.clientCommand("GetFirstTalkByPerformanceNpcScRsp")==2139
        && protocol.clientCommand("FinishFirstTalkByPerformanceNpcCsReq")==2161
        && protocol.clientCommand("FinishFirstTalkByPerformanceNpcScRsp")==2108,"Original G61 4.6 command IDs differ");
    require(protocol.clientSchema().message("GetFirstTalkByPerformanceNpcCsReq").findFieldByName("performance_id_list").getNumber()==4
        && protocol.clientSchema().message("FinishFirstTalkByPerformanceNpcCsReq").findFieldByName("performance_id").getNumber()==13,
        "Original G61 native query/finish field numbers differ");
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

  private int selectId(){return mainMission*100+1;}
  private int talkId(){return mainMission*100+2;}
  private void progress(int selected,int talked,boolean completed,String label)throws Exception{
    var data=call("GetMissionDataCsReq");
    var mains=((List<?>)field(data,"main_mission_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==mainMission).toList();
    check(mains.size()==1 && ((EnumValueDescriptor)field(mains.getFirst(),"status")).getNumber()==(completed?2:1)
        && ((List<?>)field(data,"finished_main_mission_id_list")).contains(mainMission)==completed,label+"-main");
    var rows=new HashMap<Integer,DynamicMessage>();
    for(var raw:(List<?>)field(data,"mission_list")){var row=(DynamicMessage)raw;rows.put(number(row,"id"),row);}
    for(var expected:Map.of(selectId(),selected,talkId(),talked).entrySet()){
      var row=rows.get(expected.getKey());check(row!=null && number(row,"progress")==expected.getValue()
          && ((EnumValueDescriptor)field(row,"status")).getNumber()==(expected.getValue()==0?1:2),label+"-"+expected.getKey());
    }
  }
  private void stock(int item,int expected,String label)throws Exception{
    var bag=call("GetBagCsReq");var rows=((List<?>)field(bag,"material_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"tid")==item).toList();
    check(rows.size()<=1 && (rows.isEmpty()?0:number(rows.getFirst(),"num"))==expected,label);
  }
  private void submit(Map<Integer,Integer> choices,int code,String label)throws Exception{
    var fields=new HashMap<String,Object>();fields.put("sub_mission_id",selectId());
    if(choices!=null){
      var list=DynamicMessage.newBuilder(protocol.clientSchema().message("ItemList"));
      for(var choice:choices.entrySet()){
        var item=DynamicMessage.newBuilder(protocol.clientSchema().message("Item"));
        item.setField(item.getDescriptorForType().findFieldByName("item_id"),choice.getKey());
        item.setField(item.getDescriptorForType().findFieldByName("num"),choice.getValue());
        list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"),item.build());
      }
      fields.put("item_list",list.build());
    }
    var result=call("FinishCosumeItemMissionCsReq",fields,code);
    check(number(result,"sub_mission_id")==selectId(),label);
  }
  private void report(int code,String label)throws Exception{
    call("FinishTalkMissionCsReq",Map.of("sub_mission_id",talkId(),"talk_str","Mission_"+talkId()),code);check(true,label);
  }
  private void quest(int expected,String label)throws Exception{
    var data=call("GetQuestDataCsReq");var rows=((List<?>)field(data,"quest_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==2100003).toList();
    check(rows.size()==1 && ((EnumValueDescriptor)field(rows.getFirst(),"status")).getNumber()==expected
        && number(rows.getFirst(),"progress")== (expected==1?0:1)
        && ((Long)field(rows.getFirst(),"finish_time")>0)==(expected!=1),label);
  }
  private void points(int expected,String label)throws Exception{check(number(call("GetDailyActiveInfoCsReq"),"daily_active_point")==expected,label);}
  private void claim(int code,String label)throws Exception{
    var data=call("TakeQuestRewardCsReq",Map.of("ODJIJFJCHHB",List.of(2100003)),code);
    check(((List<?>)field(data,"succ_quest_id_list")).equals(code==0?List.of(2100003):List.of()),label);
  }
  private void actor(JsonObject input,String label)throws Exception{
    int entry=input.get("entry").getAsInt(),plane=input.get("plane").getAsInt(),floor=input.get("floor").getAsInt();
    call("EnterSceneCsReq",Map.of("entry_id",entry),0);
    var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");
    check(number(scene,"entry_id")==entry && number(scene,"plane_id")==plane && number(scene,"floor_id")==floor,label+"-floor");
    var actors=new ArrayList<DynamicMessage>();
    for(var raw:(List<?>)field(scene,"entity_group_list"))for(var row:(List<?>)field((DynamicMessage)raw,"entity_list")){
      var entity=(DynamicMessage)row;if(number(entity,"group_id")==input.get("group").getAsInt())actors.add(entity);
    }
    check(actors.size()==1 && number(actors.getFirst(),"inst_id")==400001
        && number((DynamicMessage)field(actors.getFirst(),"npc"),"npc_id")==input.get("npc").getAsInt(),label+"-NPC");
    actorWire=Map.of("entity",number(actors.getFirst(),"entity_id"),"group",input.get("group").getAsInt(),"inst",400001,"npc",input.get("npc").getAsInt(),"plane",plane,"floor",floor);
  }
  private Map<String,Object> execute(JsonObject input,java.io.BufferedReader control)throws Exception{
    mainMission=input.get("mainMission").getAsInt();dayIndex=input.get("dayIndex").getAsInt();
    String phase=input.get("phase").getAsString();int firstItem=input.get("firstItem").getAsInt(),secondItem=input.get("secondItem").getAsInt();
    require(Set.of(3011605,3011608).contains(mainMission) && Set.of(1,2).contains(dayIndex)
        && Set.of("open","paid","settled","repeat","restored").contains(phase),"Unknown finite selected-item native case");
    int item=dayIndex==1?firstItem:secondItem;
    require(mainMission==3011605?firstItem==401004 && secondItem==401012:firstItem==401007 && secondItem==401009,"Original native choice0 changed");
    var token=call("PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),"token",input.get("comboToken").getAsString(),"platform",1),0);
    int uid=number(token,"uid");var login=call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");
    int wallet=number((DynamicMessage)field(login,"basic_info"),"scoin");
    System.out.println("HKRPG_SELECTED_ITEM_FIXTURE_READY:"+uid);System.out.flush();
    require("resume".equals(control.readLine()),"Controlled fixture did not resume");
    int prior=(dayIndex-1)*5000;int expected=phase.equals("restored")?10000:phase.equals("repeat")?5000:0;
    check(wallet==expected,"same-UID-actual-login-wallet");
    if(phase.equals("repeat")){
      // This actual rejection must precede any query/refresh that could reopen the prior day.
      submit(Map.of(firstItem,1),Retcode.MISSION_NOT_DOING.getVal(),"old-payment-request-rejected-before-current-period-query");
      progress(0,0,false,"actual-next-day-assignment");actor(input,"actual-original-next-day-NPC");
      stock(firstItem,0,"old-day-stock-stays-consumed");stock(secondItem,1,"new-day-explicit-stock-still-owned");
      report(Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),"retired-payment-does-not-authorize-new-talk");
      quest(1,"new-day-active-target-doing");points(0,"new-day-active-points-zero");
      submit(Map.of(item,1),0,"new-day-actual-original-item-debit");stock(item,0,"new-day-original-item-consumed-once");
      progress(1,0,false,"new-day-paid-target-with-unreported-tail");
      report(0,"new-day-original-talk-after-current-item");progress(1,1,true,"new-day-original-main-closed");
      check(mainRewardNotify && observedWallet==prior+5000,"new-day-actual-main-credit-notification");
      quest(2,"new-day-active-target-finished");claim(0,"new-day-actual-active-reward");points(200,"new-day-active-points-200");
    }else if(phase.equals("open")){
      progress(0,0,false,"actual-original-daily-automatic-assignment");actor(input,"actual-original-NPC");
      quest(1,"new-day-active-target-doing");points(0,"initial-active-points-zero");stock(item,1,"explicit-fixture-one-real-food");
      report(Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),"original-tail-cannot-precede-item");
      submit(null,Retcode.REQ_PARA_INVALID.getVal(),"no-choice-does-not-auto-select");
      submit(Map.of(firstItem,1,secondItem,1),Retcode.REQ_PARA_INVALID.getVal(),"multiple-choices-do-not-debit");
      stock(item,1,"invalid-choices-preserve-real-stock");progress(0,0,false,"invalid-requests-preserve-targets");
    }else if(phase.equals("paid")){
      progress(0,0,false,"normal-H2-restored-unpaid-original-targets");actor(input,"normal-H2-restored-source-NPC");stock(item,1,"normal-H2-restored-real-stock");
      submit(Map.of(item,1),0,"actual-1261-original-selected-item");stock(item,0,"actual-1261-consumed-one-real-item");
      progress(1,0,false,"payment-finishes-only-original-item-target");
      submit(Map.of(item,1),Retcode.MISSION_NOT_DOING.getVal(),"same-session-item-is-not-paid-twice");
      quest(1,"unreported-item-has-no-active-completion");points(0,"unreported-item-has-no-active-points");
    }else if(phase.equals("settled")){
      progress(1,0,false,"normal-H2-restored-paid-item-and-unreported-talk");actor(input,"paid-normal-H2-restored-source-NPC");stock(item,0,"normal-H2-restored-original-debit");
      report(0,"actual-1205-after-normal-H2-item-recovery");progress(1,1,true,"source-main-closed-after-real-talk");
      check(mainRewardNotify && observedWallet==5000,"actual-original-5000-credit-notification");
      quest(2,"actual-active-target-finished");claim(0,"actual-200-active-point-claim");points(200,"actual-active-points-200");
      report(Retcode.MISSION_NOT_DOING.getVal(),"same-session-main-reward-not-repaid");
    }else{
      progress(1,1,true,"second-day-normal-H2-restored-complete-original-main");stock(firstItem,0,"restored-first-day-debit");stock(secondItem,0,"restored-second-day-debit");
      quest(3,"normal-H2-restored-active-target-closed");points(200,"normal-H2-restored-active-points");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"restored-active-reward-not-repaid");
      report(Retcode.MISSION_NOT_DOING.getVal(),"restored-main-reward-not-repaid");
    }
    var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("uid",uid);result.put("mainMission",mainMission);
    result.put("dayIndex",dayIndex);result.put("phase",phase);result.put("requests",requests);result.put("checks",checks);result.put("loginWallet",wallet);
    result.put("firstItem",firstItem);result.put("secondItem",secondItem);result.put("actorWireIdentity",actorWire);
    result.put("wireFieldsVerifiedBeforeConnect",true);result.put("mainRewardNotifyBeforeResponseVerified",mainRewardNotify);
    result.put("oldBoundaryRequestBeforeAnyQuery",phase.equals("repeat"));result.put("clientUIVerified",false);result.put("clientActOrCustomStringExecuted",false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeDailySelectedItemProbe();
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
