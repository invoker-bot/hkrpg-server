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
 * Original 3000601 three stages, one actual Phone reply, and independent H2 recovery; no client Act/UI claim.
 */
public final class NativeDailyOriginEffectsProbe {
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
  private List<Map<String,Object>> initialActors=List.of();
  private boolean initialReportInFlight;
  private final List<Integer> initialNpcRemovalsBeforeAck=new ArrayList<>();
  private final List<Map<String,Object>> effectSceneViews=new ArrayList<>();
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
        Map.entry("GetQuestDataCsReq",List.<String>of()),
        Map.entry("GetQuestDataScRsp",List.of("retcode","quest_list/id","quest_list/progress","quest_list/status","quest_list/finish_time")),
        Map.entry("GetDailyActiveInfoCsReq",List.<String>of()),
        Map.entry("GetDailyActiveInfoScRsp",List.of("retcode","daily_active_point")),
        Map.entry("TakeQuestRewardCsReq",List.of("ODJIJFJCHHB")),
        Map.entry("TakeQuestRewardScRsp",List.of("retcode","succ_quest_id_list")),
        Map.entry("SceneGroupRefreshScNotify",List.of("floor_id","dimension_id","group_refresh_list/group_id",
            "group_refresh_list/refresh_entity/delete_entity")),
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
    require(protocol.clientCommand("FinishTalkMissionCsReq")==1205
        && protocol.clientSchema().message("FinishTalkMissionCsReq").findFieldByName("sub_mission_id").getNumber()==6
        && protocol.clientSchema().message("FinishTalkMissionCsReq").findFieldByName("talk_str").getNumber()==2,
        "Original 4.6 initial/Talk command fields differ");
    require(protocol.clientCommand("SceneGroupRefreshScNotify")==1433
        && protocol.clientSchema().message("SceneGroupRefreshScNotify").findFieldByName("group_refresh_list").getNumber()==7
        && protocol.clientSchema().message("GroupRefreshInfo").findFieldByName("group_id").getNumber()==10
        && protocol.clientSchema().message("GroupRefreshInfo").findFieldByName("refresh_entity").getNumber()==6
        && protocol.clientSchema().message("SceneEntityRefreshInfo").findFieldByName("delete_entity").getNumber()==14,
        "Original 4.6 actor removal fields differ");
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
      if(initialReportInFlight && frame.command()==protocol.clientCommand("SceneGroupRefreshScNotify")) {
        var notify=protocol.clientSchema().parse("SceneGroupRefreshScNotify",frame.body());
        require(notify.getUnknownFields().asMap().isEmpty(),"Unknown actor removal notification fields");
        require(number(notify,"floor_id")==20002001 && number(notify,"dimension_id")==0,"Wrong actor removal scene");
        for(var raw:(List<?>)field(notify,"group_refresh_list")) {
          var group=(DynamicMessage)raw;if(number(group,"group_id")!=84)continue;
          for(var refresh:(List<?>)field(group,"refresh_entity")) {
            var entity=(DynamicMessage)refresh;var deletion=entity.getDescriptorForType().findFieldByName("delete_entity");
            require(entity.hasField(deletion),"Original report published a non-removal G84 effect");
            initialNpcRemovalsBeforeAck.add(number(entity,"delete_entity"));
          }
        }
      }
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

  private static final int MAIN=3000601,PRE=300060001,FIRST=300060101,GOAL=300060102,SECTION=1211400;

  private void progress(int initial, int first, int goal, boolean completed, String label) throws Exception {
    var data=call("GetMissionDataCsReq");
    var mains=((List<?>)field(data,"main_mission_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==MAIN).toList();
    check(mains.size()==1 && ((EnumValueDescriptor)field(mains.getFirst(),"status")).getNumber()==(completed?2:1)
        && ((List<?>)field(data,"finished_main_mission_id_list")).contains(MAIN)==completed,label+"-main");
    var subs=new HashMap<Integer,DynamicMessage>();
    for(var raw:(List<?>)field(data,"mission_list")) { var row=(DynamicMessage)raw;subs.put(number(row,"id"),row); }
    for(var stage:Map.of(PRE,initial,FIRST,first,GOAL,goal).entrySet()) {
      int id=stage.getKey(),expected=stage.getValue();
      check(expected<0?!subs.containsKey(id):subs.containsKey(id) && number(subs.get(id),"progress")==expected
          && ((EnumValueDescriptor)field(subs.get(id),"status")).getNumber()==(expected==0?1:2),label+"-stage-"+id);
    }
  }

  private DynamicMessage phone() throws Exception {
    var response=call("GetNpcMessageGroupCsReq",Map.of("BJIPBBFIBPD",List.of(48)),0);
    var groups=((List<?>)field(response,"message_group_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==11125).toList();
    require(groups.size()==1,"Missing or duplicate original Phone group 11125");
    var sections=((List<?>)field(groups.getFirst(),"message_section_list")).stream().map(DynamicMessage.class::cast)
        .filter(row->number(row,"id")==SECTION).toList();
    require(sections.size()<=1,"Duplicate original Phone section 1211400");
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

  private List<Integer> prefix(int reply, int size) {
    require(Set.of(121140102,121140104,121140106).contains(reply) && Set.of(2,7).contains(size),
        "Invalid original single-reply prefix");
    return List.of(121140101,121140111,reply,reply+1,121140108,121140109,121140110).subList(0,size);
  }

  private List<Integer> choices(int size) {
    return switch(size) {case 2->List.of(121140102,121140104,121140106);case 7->List.of();
      default->throw new IllegalStateException("Invalid original single-reply offer");};
  }

  private void initialGroup() throws Exception {
    var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");
    check(number(scene,"plane_id")==20002 && number(scene,"floor_id")==20002001,"original-initial-floor");
    var records=new ArrayList<Map<String,Object>>();var npcIds=new HashSet<Integer>();var npcInstances=new HashSet<Integer>();var propInstances=new HashSet<Integer>();
    for(var rawGroup:(List<?>)field(scene,"entity_group_list")) for(var raw:(List<?>)field((DynamicMessage)rawGroup,"entity_list")) {
      var entity=(DynamicMessage)raw;if(number(entity,"group_id")!=84)continue;
      int instance=number(entity,"inst_id");var npc=entity.getDescriptorForType().findFieldByName("npc");var prop=entity.getDescriptorForType().findFieldByName("prop");
      if(entity.hasField(npc)) {int id=number((DynamicMessage)field(entity,"npc"),"npc_id");npcIds.add(id);npcInstances.add(instance);
        records.add(Map.of("kind","npc","id",id,"instance",instance,"entity",number(entity,"entity_id")));}
      if(entity.hasField(prop)) {int id=number((DynamicMessage)field(entity,"prop"),"prop_id");check(id==100,"source-G84-prop100-instance-"+instance);propInstances.add(instance);
        records.add(Map.of("kind","prop","id",id,"instance",instance,"entity",number(entity,"entity_id")));}
    }
    check(records.size()==5 && npcIds.equals(Set.of(10209,10215)) && npcInstances.equals(Set.of(400001,400002))
        && propInstances.equals(Set.of(300001,300002,300003)),"real-source-G84-two-NPC-three-Prop-instances");
    initialActors=records;
  }

  private void reportEffects(boolean propsRemain,String label) throws Exception {
    var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");
    var npcs=new ArrayList<Integer>();var props=new ArrayList<Integer>();
    for(var rawGroup:(List<?>)field(scene,"entity_group_list"))for(var raw:(List<?>)field((DynamicMessage)rawGroup,"entity_list")) {
      var entity=(DynamicMessage)raw;if(number(entity,"group_id")!=84)continue;
      if(entity.hasField(entity.getDescriptorForType().findFieldByName("npc")))npcs.add(number(entity,"inst_id"));
      if(entity.hasField(entity.getDescriptorForType().findFieldByName("prop")))props.add(number(entity,"inst_id"));
    }
    check(npcs.isEmpty() && (propsRemain?props.size()==3 && new HashSet<>(props).equals(Set.of(300001,300002,300003)):props.isEmpty()),label);
    effectSceneViews.add(Map.of("label",label,"npcInstances",npcs,"propInstances",props,"floor",number(scene,"floor_id")));
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
    actorWire=Map.of("entity",number(found.getFirst(),"entity_id"),"group",61,"instance",400001,"npc",10206,
        "plane",20002,"floor",20002001,"entry",200020102);
  }

  private void seen(boolean expected,String label) throws Exception {
    var data=call("GetFirstTalkByPerformanceNpcCsReq",Map.of("performance_id_list",List.of(90000103)),0);
    var rows=(List<?>)field(data,"npc_meet_status_list");
    check(rows.size()==1 && number((DynamicMessage)rows.getFirst(),"performance_id")==90000103
        && field((DynamicMessage)rows.getFirst(),"is_meet").equals(expected),label);
  }

  private void firstMeet(int code,String label) throws Exception {
    var data=call("FinishFirstTalkByPerformanceNpcCsReq",Map.of("performance_id",90000103),code);
    check(number(data,"performance_id")==90000103
        && !data.hasField(data.getDescriptorForType().findFieldByName("reward")),label);
  }

  private void noCommission(String label) throws Exception {
    var data=call("GetMissionDataCsReq");
    check(((List<?>)field(data,"main_mission_list")).stream().map(DynamicMessage.class::cast)
        .noneMatch(row->number(row,"id")==MAIN)
        && ((List<?>)field(data,"mission_list")).stream().map(DynamicMessage.class::cast)
        .noneMatch(row->number(row,"id")==PRE || number(row,"id")==FIRST || number(row,"id")==GOAL),label);
    check(!mainRewardNotify && observedWallet<=0,label+"-no-main-credit");
  }

  private void originalMenu(JsonObject input) throws Exception {
    var source=JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(input.get("originalMenuPack").getAsString()))).getAsJsonObject();
    var data=call("GetMissionDataCsReq");var states=new HashMap<Integer,Integer>();
    for(var raw:(List<?>)field(data,"mission_list")) {
      var row=(DynamicMessage)raw;states.put(number(row,"id"),((EnumValueDescriptor)field(row,"status")).getNumber());
    }
    var candidates=new ArrayList<Integer>();
    var tasks=source.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList");
    for(var raw:tasks) {
      var task=raw.getAsJsonObject();
      if(!task.get("$type").getAsString().equals("RPG.GameCore.PredicateTaskList")) continue;
      var predicate=task.getAsJsonObject("Predicate");
      require(predicate.get("$type").getAsString().equals("RPG.GameCore.ByCompareSubMissionState")
          && predicate.get("SubMissionState").getAsString().equals("Started"),"Original finite source menu predicate differs");
      int id=predicate.get("SubMissionID").getAsInt();
      require(Set.of(300060101,300060201,300060301).contains(id),"Unexpected original menu mission");
      if(states.getOrDefault(id,0)==1) {
        var menu=task.getAsJsonArray("SuccessTaskList").get(0).getAsJsonObject();
        require(menu.get("MenuItemID").getAsInt()==id && menu.get("MissionID").getAsInt()==id,
            "Original menu identity differs from its Started mission");
        candidates.add(id);
      }
    }
    check(candidates.equals(List.of(FIRST)),"original-source-menu-evaluated-against-actual-native-Started-mission");
  }

  private Map<String,Object> execute(JsonObject input, java.io.BufferedReader control) throws Exception {
    mainMission=MAIN;dayIndex=input.get("dayIndex").getAsInt();
    String phase=input.get("phase").getAsString(),scenario=input.get("scenario").getAsString(),at=input.get("expiredAt").getAsString();
    int selected=input.get("firstChoice").getAsInt();
    require(Set.of(1,2).contains(dayIndex) && Set.of("intro","initial","open","middle","finish","claimed","boundary-reject").contains(phase)
        && Set.of("settled","expired-unpaid").contains(scenario) && Set.of("noReplies","allReplies").contains(at)
        && Set.of(121140102,121140104,121140106).contains(selected),"Invalid finite original 3000601 case");
    var token=call("PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),"token",input.get("comboToken").getAsString(),"platform",1),0);
    int uid=number(token,"uid");var login=call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");
    int wallet=number((DynamicMessage)field(login,"basic_info"),"scoin");
    System.out.println("HKRPG_G61_FIXTURE_READY:"+uid);System.out.flush();
    require("resume".equals(control.readLine()),"Controlled clock did not resume");
    int prior=scenario.equals("settled")?(dayIndex-1)*5000:0;
    check(wallet==(phase.equals("claimed")?prior+5000:prior),"actual-login-wallet-"+dayIndex+"-"+phase);
    if(phase.equals("boundary-reject")) {
      require(dayIndex==2 && scenario.equals("expired-unpaid"),"Boundary request belongs to an unpaid old run");
      // No mission/Phone/scene/first-meet query may precede this real old request.
      if(at.equals("allReplies"))finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"old-ending-rejected-before-any-query");
      else reply(selected,0,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"old-reply-rejected-before-any-query");
    }else{
      seen(!phase.equals("intro"),"original-global-initial-meeting-before-phase");
      if(!phase.equals("intro")){firstMeet(0,"initial-meeting-deduplicated-after-H2");seen(true,"original-initial-meeting-remains-global");}
      switch(phase){
        case "intro" -> {
          require(dayIndex==1 && wallet==0,"Intro requires a real fresh D0");
          firstMeet(Retcode.MISSION_SCENE_NOT_MATCH.getVal(),"intro-rejected-before-original-NPC-load");noCommission("intro-no-synthetic-daily-goal-before-observation");
          origin();seen(false,"loaded-source-NPC-not-yet-observed");
          call("FinishFirstTalkByPerformanceNpcCsReq",Map.of("performance_id",90000104),Retcode.MISSION_NOT_EXIST.getVal());
          firstMeet(0,"real-source-initial-meeting-without-daily-objective");seen(true,"real-source-initial-meeting-saved");
          firstMeet(0,"same-session-initial-meeting-not-rewarded-again");noCommission("intro-no-synthetic-initial-or-Phone-objective-after-observation");
        }
        case "initial" -> {
          progress(0,-1,-1,false,"actual-automatic-original-three-stage-assignment");
          check(phone()==null,"initial-does-not-borrow-old-global-Phone");quest(1,"same-D0-real-late-unlock-active-target");points(0,"new-day-zero-activity");
          origin();initialGroup();
          call("FinishTalkMissionCsReq",Map.of("sub_mission_id",FIRST,"talk_str","Mission_300060101"),Retcode.MISSION_NOT_DOING.getVal());
          progress(0,-1,-1,false,"G61-talk-cannot-skip-original-G84-initial-stage");
          call("FinishTalkMissionCsReq",Map.of("sub_mission_id",PRE,"talk_str","Mission_300060101"),Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal());
          initialReportInFlight=true;
          call("FinishTalkMissionCsReq",Map.of("sub_mission_id",PRE,"talk_str","Mission_300060001"),0);
          initialReportInFlight=false;
          var originalNpcIds=initialActors.stream().filter(row->row.get("kind").equals("npc")).map(row->(Integer)row.get("entity")).toList();
          check(initialNpcRemovalsBeforeAck.size()==2 && new HashSet<>(initialNpcRemovalsBeforeAck).equals(new HashSet<>(originalNpcIds)),
              "both-actual-source-NPC-removals-before-original-initial-ACK");
          reportEffects(true,"original-initial-ACK-removed-only-two-NPCs-retained-three-props");
          call("FinishTalkMissionCsReq",Map.of("sub_mission_id",PRE,"talk_str","Mission_300060001"),Retcode.MISSION_NOT_DOING.getVal());
          reportEffects(true,"duplicate-report-keeps-source-NPC-removal-without-another-completion");
          progress(1,0,-1,false,"source-initial-report-opens-only-G61");originalMenu(input);
          check(phone()==null && !mainRewardNotify,"source-initial-report-does-not-open-Phone-or-pay");
        }
        case "open" -> {
          progress(1,0,-1,false,"same-UID-source-initial-completion-restored");
          check(phone()==null,"Phone-not-open-before-real-G61-report");quest(1,"restored-active-target-doing");points(0,"restored-active-points-zero");
          origin();reportEffects(true,"actual-H2-initial-reentry-retains-source-NPC-removal");originalMenu(input);
          call("FinishTalkMissionCsReq",Map.of("sub_mission_id",FIRST,"talk_str","foreign-phone-talk"),Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal());
          call("FinishTalkMissionCsReq",Map.of("sub_mission_id",FIRST,"talk_str","Mission_300060101"),0);
          progress(1,1,0,false,"real-G61-report-accepts-only-original-Phone-goal");
          phone(prefix(selected,2),choices(2),false,"fresh-original-two-NPC-opening");
          finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"ending-cannot-skip-original-single-reply");
          reply(121140202,0,Retcode.MESSAGE_SECTION_NOT_TAKE.getVal(),"sibling-choice-cannot-enter-original-Phone");
          reply(selected,1,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"unverified-text-override-rejected");
          if(scenario.equals("expired-unpaid") && dayIndex==1 && at.equals("allReplies")){
            reply(selected,0,0,"unpaid-actual-original-player-reply");phone(prefix(selected,7),choices(7),false,"actual-unsubmitted-original-ending");
          }
          claim(Retcode.QUEST_NOT_FINISH.getVal(),"unpaid-active-claim-rejected");check(!mainRewardNotify,"no-credit-before-submitted-ending");
        }
        case "middle" -> {
          progress(1,1,0,false,"same-UID-original-opening-restored");phone(prefix(selected,2),choices(2),false,"normal-H2-opening-restored");
          quest(1,"original-reply-still-needed");points(0,"actual-points-before-reply");
          reply(selected,0,0,"actual-original-single-player-reply");phone(prefix(selected,7),choices(7),false,"original-ending-still-unsubmitted");
          progress(1,1,0,false,"reply-alone-does-not-pay-main");claim(Retcode.QUEST_NOT_FINISH.getVal(),"unsubmitted-ending-cannot-claim");check(!mainRewardNotify,"no-credit-after-only-player-reply");
        }
        case "finish" -> {
          progress(1,1,0,false,"same-UID-ending-and-three-source-states-restored");phone(prefix(selected,7),choices(7),false,"normal-H2-unsubmitted-ending-restored");
          quest(1,"ending-needs-actual-section-submission");points(0,"restored-points-before-submission");
          reply(selected,0,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"single-reply-cannot-replay-after-H2");
          awaitedQuest=2100003;questCompletionNotify=false;mainRewardNotify=false;
          finish(SECTION,0,"real-final-section-completes-own-accepted-period");
          check(questCompletionNotify,"active-target-notified-before-ending-response");
          check(mainRewardNotify && observedWallet==prior+5000,"real-main-5000-credit-before-ending-response");
          awaitedQuest=0;progress(1,1,1,true,"original-main-and-three-goals-finished");phone(prefix(selected,7),choices(7),true,"finished-original-history");
          reportEffects(false,"actual-completed-owner-unloads-whole-original-G84");
          quest(2,"activity-finished-before-separate-claim");points(0,"points-not-auto-claimed");
          claim(0,"actual-original-200-activity-claim");points(200,"exact-original-200-activity");quest(3,"actual-activity-closed");
          claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"activity-not-paid-twice");finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"ending-not-paid-twice");
          reply(selected,0,Retcode.MESSAGE_ITEM_CAN_NOT_FINISH.getVal(),"finished-reply-not-paid-twice");
          call("AcceptMainMissionCsReq",Map.of("main_mission_id",MAIN),Retcode.MISSION_ALREADY_DONE.getVal());check(true,"same-period-main-not-reopened");
        }
        default -> {
          progress(1,1,1,true,"normal-H2-source-main-and-three-goals-finished");phone(prefix(selected,7),choices(7),true,"normal-H2-original-final-history");
          origin();reportEffects(false,"actual-completed-H2-reentry-keeps-original-owner-unloaded");
          quest(3,"normal-H2-activity-closed");points(200,"normal-H2-activity-points");claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"normal-H2-reward-not-repaid");
          finish(SECTION,Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),"normal-H2-ending-not-repaid");call("AcceptMainMissionCsReq",Map.of("main_mission_id",MAIN),Retcode.MISSION_ALREADY_DONE.getVal());
        }
      }
    }
    var result=new LinkedHashMap<String,Object>();
    result.put("passed",true);result.put("uid",uid);result.put("mainMission",MAIN);result.put("dayIndex",dayIndex);
    result.put("phase",phase);result.put("scenario",scenario);result.put("expiredAt",at);result.put("firstChoice",selected);result.put("loginWallet",wallet);
    result.put("checks",checks);result.put("requests",requests);result.put("wireFieldsVerifiedBeforeConnect",true);
    result.put("mainRewardNotifyBeforeResponseVerified",mainRewardNotify);result.put("immediateQuestCompletionNotifyVerified",questCompletionNotify);
    result.put("originalInitialNpcRemovalWireIdsBeforeAck",initialNpcRemovalsBeforeAck);
    result.put("originalInitialEffectsSceneViews",effectSceneViews);
    result.put("actorWireIdentity",actorWire);result.put("originalInitialGroupActorWireIdentities",initialActors);result.put("originalNPCPerformance",90000103);
    result.put("oldBoundaryRequestBeforeAnyQuery",phase.equals("boundary-reject"));result.put("originalSourceMenuPredicateAgainstNativeMissionVerified",Set.of("initial","open").contains(phase));
    result.put("clientCustomStringVerified",false);result.put("clientActVerified",false);result.put("clientMenuUIVerified",false);
    result.put("sourceClientScriptExecutorVerified",false);result.put("clientGameplayVerified",false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeDailyOriginEffectsProbe();
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
