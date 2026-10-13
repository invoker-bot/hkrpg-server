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
 * Original 3011014 three choices, actual grants/debits and normal H2 recovery; client Acts/UI not claimed.
 */
public final class NativeDailyRecoverDeliveryProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private final Map<String, Object> wireFields = verifyWireFields();
  private boolean performances;
  private Ukcp channel;
  private int mainMission, dayIndex, requests;
  private boolean mainRewardNotify;
  private int observedWallet = -1;
  private Map<String,Object> actorWire=Map.of();
  private int awaitedQuest;
  private boolean questCompletionNotify;

  private static Object field(DynamicMessage m, String name) {
    var descriptor=m.getDescriptorForType().findFieldByName(name);
    require(descriptor!=null,"Undeclared 4.6 field: "+m.getDescriptorForType().getFullName()+"."+name);
    return m.getField(descriptor);
  }

  private Map<String,Object> verifyWireFields() {
    var paths=Map.ofEntries(
        Map.entry("StarPerformanceRelayCsReq",List.of("performance_id","performance_type")),
        Map.entry("StarPerformanceRelayScRsp",List.of("retcode")),
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
          (code < 0 ? number(m, "retcode") != 0 : number(m, "retcode") == code),
          "Response rejected: " + response + " code=" + number(m, "retcode"));
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }


  private static final int MAIN=3011014,FIRST=301101401,MENU=301101402;
  private static final int[] ENTRIES={2012101,2012301,1010203};
  private static final int[] PLANES={20121,20123,10102},FLOORS={20121001,20123001,10102003};
  private static final int[] GROUPS={93,76,23},NPCS={11104,11205,11403};
  private void relay(int id)throws Exception{
    if(!performances)return;
    call("StarPerformanceRelayCsReq",Map.of("performance_id",id,"performance_type",4),0);check(true,"original-E-relay-"+id);
    call("StarPerformanceRelayCsReq",Map.of("performance_id",id,"performance_type",4),0);check(true,"duplicate-E-relay-"+id);
  }
  private void temporary(int choice,boolean present,String label)throws Exception{
    if(!performances)return;
    int group=choice<0?122:new int[]{94,75,23}[choice],instance=choice==2?400002:400001;
    var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");var matches=new ArrayList<DynamicMessage>();
    for(var raw:(List<?>)field(scene,"entity_group_list"))for(var value:(List<?>)field((DynamicMessage)raw,"entity_list")){
      var entity=(DynamicMessage)value;if(number(entity,"group_id")==group && number(entity,"inst_id")==instance)matches.add(entity);
    }
    check(matches.size()==(present?1:0) && (!present || number((DynamicMessage)field(matches.getFirst(),"npc"),"npc_id")==11302),label);
  }
  private void report(int id,int code,String label)throws Exception{
    call("FinishTalkMissionCsReq",Map.of("sub_mission_id",id,"talk_str","Mission_"+id),code);check(true,label);
  }
  private void stock(int item,int expected,String label)throws Exception{
    var bag=call("GetBagCsReq");var rows=((List<?>)field(bag,"material_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"tid")==item).toList();
    check(rows.size()<=1 && (rows.isEmpty()?0:number(rows.getFirst(),"num"))==expected,label);
  }
  private void submit(int id,Map<Integer,Integer> choices,int code,String label)throws Exception{
    var list=DynamicMessage.newBuilder(protocol.clientSchema().message("ItemList"));
    for(var choice:choices.entrySet()){
      var item=DynamicMessage.newBuilder(protocol.clientSchema().message("Item"));
      item.setField(item.getDescriptorForType().findFieldByName("item_id"),choice.getKey());
      item.setField(item.getDescriptorForType().findFieldByName("num"),choice.getValue());
      list.addRepeatedField(list.getDescriptorForType().findFieldByName("item_list"),item.build());
    }
    var response=call("FinishCosumeItemMissionCsReq",Map.of("sub_mission_id",id,"item_list",list.build()),code);
    check(number(response,"sub_mission_id")==id,label);
  }
  private void missions(boolean completed,Map<Integer,Integer> expected,String label)throws Exception{
    var data=call("GetMissionDataCsReq");
    var mains=((List<?>)field(data,"main_mission_list")).stream().map(DynamicMessage.class::cast).filter(row->number(row,"id")==MAIN).toList();
    check(mains.size()==1 && ((EnumValueDescriptor)field(mains.getFirst(),"status")).getNumber()==(completed?2:1)
        && ((List<?>)field(data,"finished_main_mission_id_list")).contains(MAIN)==completed,label+"-main");
    var rows=new HashMap<Integer,DynamicMessage>();for(var raw:(List<?>)field(data,"mission_list")){var row=(DynamicMessage)raw;rows.put(number(row,"id"),row);}
    for(var entry:expected.entrySet()){
      var row=rows.get(entry.getKey());check(row!=null && number(row,"progress")==entry.getValue()
          && ((EnumValueDescriptor)field(row,"status")).getNumber()==(entry.getValue()==0?1:2),label+"-"+entry.getKey());
    }
  }
  private List<DynamicMessage> scene(int entry,int plane,int floor)throws Exception{
    call("EnterSceneCsReq",Map.of("entry_id",entry),0);var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");
    check(number(scene,"entry_id")==entry && number(scene,"plane_id")==plane && number(scene,"floor_id")==floor,"original-source-scene-"+entry);
    var result=new ArrayList<DynamicMessage>();for(var group:(List<?>)field(scene,"entity_group_list"))for(var entity:(List<?>)field((DynamicMessage)group,"entity_list"))result.add((DynamicMessage)entity);return result;
  }
  private void contact(int choice)throws Exception{
    var entities=scene(ENTRIES[choice],PLANES[choice],FLOORS[choice]);
    var npcs=entities.stream().filter(e->number(e,"group_id")==GROUPS[choice] && number(e,"inst_id")==400001).toList();
    check(npcs.size()==1 && number((DynamicMessage)field(npcs.getFirst(),"npc"),"npc_id")==NPCS[choice],"original-recipient-npc-"+choice);
    actorWire=Map.of("plane",PLANES[choice],"floor",FLOORS[choice],"group",GROUPS[choice],"inst",400001,"npc",NPCS[choice]);
  }
  private void quest(int expected,String label)throws Exception{
    var data=call("GetQuestDataCsReq");var rows=((List<?>)field(data,"quest_list")).stream().map(DynamicMessage.class::cast).filter(row->number(row,"id")==2100003).toList();
    check(rows.size()==1 && ((EnumValueDescriptor)field(rows.getFirst(),"status")).getNumber()==expected
        && number(rows.getFirst(),"progress")== (expected==1?0:1),label);
  }
  private void points(int expected)throws Exception{check(number(call("GetDailyActiveInfoCsReq"),"daily_active_point")==expected,"original-active-points-"+expected);}
  private void claim(int code)throws Exception{
    var data=call("TakeQuestRewardCsReq",Map.of("ODJIJFJCHHB",List.of(2100003)),code);
    check(((List<?>)field(data,"succ_quest_id_list")).equals(code==0?List.of(2100003):List.of()),"active-reward-retcode-"+code);
  }
  private void give(int choice,boolean menuFirst)throws Exception{
    var entities=scene(1010201,10102,10102001);
    var npc=entities.stream().filter(e->number(e,"group_id")==99 && number(e,"inst_id")==400002).toList();
    check(npc.size()==1 && number((DynamicMessage)field(npc.getFirst(),"npc"),"npc_id")==11302,"actual-original-initial-npc");
    relay(FIRST);report(FIRST,0,"actual-original-initial-talk");
    var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");var props=new ArrayList<DynamicMessage>();
    for(var group:(List<?>)field(scene,"entity_group_list"))for(var raw:(List<?>)field((DynamicMessage)group,"entity_list")){
      var entity=(DynamicMessage)raw;if(number(entity,"group_id")==122 && Set.of(300001,300002).contains(number(entity,"inst_id")))props.add(entity);
    }
    check(props.size()==2 && props.stream().allMatch(e->number((DynamicMessage)field(e,"prop"),"prop_id")==100),"actual-both-original-menu-props");
    temporary(-1,false,"menu-actor-absent-before-relay");relay(MENU);temporary(-1,true,"menu-actor-created-by-relay");
    stock(140146+choice,0,"no-choice-item-prewritten");
    if(menuFirst)report(MENU,0,"actual-menu-before-parallel-option");
    report(301101403+choice,0,"actual-original-option-grants-one-item");stock(140146+choice,1,"actual-original-gift-owned");
    report(301101403+(choice+1)%3,-1,"another-original-option-rejected");stock(140146+(choice+1)%3,0,"rejected-option-gives-no-item");
    if(!menuFirst)report(MENU,0,"actual-menu-after-parallel-option");
    temporary(-1,false,"menu-actor-released-after-report");
    missions(false,Map.of(FIRST,1,MENU,1,301101403+choice,1,301101406+choice,0),"actual-gift-and-accepted-consume");
  }
  private void settle(int choice,int wallet)throws Exception{
    int item=140146+choice,consume=301101406+choice,feedback=301101409+choice;
    contact(choice);report(feedback,-1,"unpaid-feedback-rejected");stock(item,1,"unpaid-feedback-keeps-real-gift");
    temporary(choice,false,"recipient-actor-absent-before-relay");relay(consume);temporary(choice,true,"recipient-actor-created-by-relay");
    submit(consume,Map.of(item,2),-1,"wrong-quantity-does-not-debit");stock(item,1,"bad-debit-keeps-real-gift");
    submit(consume,Map.of(item,1),0,"actual-one-original-item-debit");stock(item,0,"real-gift-consumed-once");
    temporary(choice,false,"recipient-actor-released-after-debit");relay(feedback);temporary(choice,true,"feedback-actor-created-by-relay");
    awaitedQuest=2100003;report(feedback,0,"actual-paid-original-feedback");
    temporary(choice,false,"feedback-actor-released-after-report");
    missions(true,Map.of(consume,1,feedback,1,301101412,1),"one-feedback-finishes-original-main");
    check(mainRewardNotify && observedWallet==wallet,"actual-original-main-credit-notification-"+wallet);
    quest(2,"actual-daily-target-finished");claim(0);points(200);
    submit(consume,Map.of(item,1),-1,"settled-debit-not-repeated");report(feedback,-1,"settled-credit-not-repeated");claim(-1);
  }
  private Map<String,Object> execute(JsonObject input,java.io.BufferedReader control)throws Exception{
    performances=input.has("performances") && input.get("performances").getAsBoolean();
    mainMission=MAIN;dayIndex=input.get("dayIndex").getAsInt();int firstChoice=input.get("caseIndex").getAsInt(),secondChoice=(firstChoice+1)%3;
    String phase=input.get("phase").getAsString();require(firstChoice>=0 && firstChoice<3 && Set.of("grant","settled","repeat","restored").contains(phase),"Unowned recovery lane");
    var token=call("PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),"token",input.get("comboToken").getAsString(),"platform",1),0);
    int uid=number(token,"uid");var login=call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");int wallet=number((DynamicMessage)field(login,"basic_info"),"scoin");
    System.out.println("HKRPG_RECOVER_DELIVERY_FIXTURE_READY:"+uid);System.out.flush();require("resume".equals(control.readLine()),"Controlled fixture did not resume");
    check(wallet==(phase.equals("repeat")?5000:phase.equals("restored")?10000:0),"actual-login-wallet");
    if(phase.equals("grant")){
      missions(false,Map.of(FIRST,0),"actual-original-assignment");give(firstChoice,firstChoice==1);quest(1,"given-but-unpaid-daily-doing");points(0);
    }else if(phase.equals("settled")){
      missions(false,Map.of(FIRST,1,MENU,1,301101403+firstChoice,1,301101406+firstChoice,0),"normal-H2-restored-real-gift");stock(140146+firstChoice,1,"normal-H2-restored-inventory");settle(firstChoice,5000);
    }else if(phase.equals("repeat")){
      report(301101403+firstChoice,-1,"old-day-option-rejected-before-any-query");
      missions(false,Map.of(FIRST,0),"actual-next-day-assignment");stock(140146+firstChoice,0,"old-day-item-remains-consumed");quest(1,"next-day-active-target-doing");points(0);
      give(secondChoice,true);settle(secondChoice,10000);
    }else{
      missions(true,Map.of(301101406+secondChoice,1,301101409+secondChoice,1,301101412,1),"second-day-normal-H2-complete-main");
      stock(140146+firstChoice,0,"restored-first-item-debit");stock(140146+secondChoice,0,"restored-second-item-debit");quest(3,"restored-active-target-closed");points(200);
      claim(-1);report(301101409+secondChoice,-1,"restored-main-credit-not-repaid");
    }
    var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("uid",uid);result.put("mainMission",MAIN);result.put("dayIndex",dayIndex);result.put("phase",phase);
    result.put("caseIndex",firstChoice);result.put("firstChoice",firstChoice);result.put("secondChoice",secondChoice);result.put("requests",requests);result.put("checks",checks);result.put("loginWallet",wallet);
    result.put("actorWireIdentity",actorWire);result.put("mainRewardNotify",mainRewardNotify);result.put("clientUIVerified",false);result.put("clientActOrCustomStringExecuted",false);return result;
  }
  public static void main(String[] args) {
    var probe = new NativeDailyRecoverDeliveryProbe();
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
