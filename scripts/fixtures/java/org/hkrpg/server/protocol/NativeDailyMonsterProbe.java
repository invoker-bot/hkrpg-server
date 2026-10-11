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
 * Original daily monsters, actual accepted battles, two daily payouts and normal H2 recovery.
 */
public final class NativeDailyMonsterProbe {
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
            "scene/entity_group_list/entity_list/inst_id","scene/entity_group_list/entity_list/entity_id",
            "scene/entity_group_list/entity_list/npc_monster/monster_id",
            "scene/entity_group_list/entity_list/npc_monster/event_id",
            "scene/entity_group_list/entity_list/npc_monster/world_level",
            "scene/entity_group_list/entity_list/prop/prop_id")),
        Map.entry("SceneCastSkillCsReq",List.of("cast_entity_id","attacked_by_entity_id","skill_index","hit_target_entity_id_list")),
        Map.entry("SceneCastSkillScRsp",List.of("retcode","cast_entity_id","monster_battle_info/target_monster_entity_id",
            "monster_battle_info/monster_battle_type","battle_info/battle_id","battle_info/stage_id",
            "battle_info/monster_wave_list/battle_stage_id","battle_info/monster_wave_list/monster_list/monster_id")),
        Map.entry("GetCurBattleInfoCsReq",List.<String>of()),
        Map.entry("GetCurBattleInfoScRsp",List.of("retcode","battle_info/battle_id","battle_info/stage_id")),
        Map.entry("PVEBattleResultCsReq",List.of("battle_id","stage_id","end_status")),
        Map.entry("PVEBattleResultScRsp",List.of("retcode")),
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

  private void origin() throws Exception { entry(2000301,20003,20003001); }

  private int floor() { return mainMission == 3000213 ? 20003001 : 20002001; }
  private int group() { return mainMission == 3000211 ? 78 : mainMission == 3000212 ? 79 : 95; }
  private int first() { return mainMission * 100 + 1; }
  private int kill() { return mainMission * 100 + 2; }
  private int following() { return mainMission * 100 + 3; }
  private int last() { return mainMission * 100 + 4; }
  private void targetEntry() throws Exception { entry(floor()==20002001?2000201:2000301,floor()/1000,floor()); }

  private void rebuildTargetEntry() throws Exception {
    int away=floor()==20002001?20003001:20002001;
    entry(away==20002001?2000201:2000301,away/1000,away);
    targetEntry();
  }

  private DynamicMessage currentScene() throws Exception {
    return (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
  }

  private List<DynamicMessage> originalMonsters(DynamicMessage scene) {
    var found = new ArrayList<DynamicMessage>();
    for (var rawGroup : (List<?>) field(scene,"entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) rawGroup,"entity_list")) {
        var entity=(DynamicMessage)raw;
        if(number(entity,"group_id")==group() && number(entity,"inst_id")==200001
            && entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster"))) found.add(entity);
      }
    return found;
  }

  private void originalTalkPropRemains(DynamicMessage scene, String label) {
    var found=new ArrayList<DynamicMessage>();
    for (var rawGroup : (List<?>)field(scene,"entity_group_list"))
      for (var raw : (List<?>)field((DynamicMessage)rawGroup,"entity_list")) {
        var entity=(DynamicMessage)raw;
        if(number(entity,"group_id")==group() && number(entity,"inst_id")==300001
            && entity.hasField(entity.getDescriptorForType().findFieldByName("prop"))) found.add(entity);
      }
    check(found.size()==1 && number((DynamicMessage)field(found.getFirst(),"prop"),"prop_id")==100056,label);
  }

  private void progress(int firstProgress, int killProgress, boolean completed, String label) throws Exception {
    var data=call("GetMissionDataCsReq");
    var mains=new ArrayList<DynamicMessage>();
    for(var raw:(List<?>)field(data,"main_mission_list")) {
      var row=(DynamicMessage)raw; if(number(row,"id")==mainMission) mains.add(row);
    }
    require(mains.size()==1,"Missing original automatically assigned kill commission");
    require(((EnumValueDescriptor)field(mains.getFirst(),"status")).getNumber()==(completed?2:1),"Wrong main phase");
    require(((List<?>)field(data,"finished_main_mission_id_list")).contains(mainMission)==completed,"Wrong main history");
    var subs=new HashMap<Integer,DynamicMessage>();
    for(var raw:(List<?>)field(data,"mission_list")) { var row=(DynamicMessage)raw; subs.put(number(row,"id"),row); }
    require(subs.containsKey(first()) && number(subs.get(first()),"progress")==firstProgress,"Wrong source first talk");
    if(killProgress<0) require(!subs.containsKey(kill()),"Kill goal accepted before first source talk");
    else {
      require(subs.containsKey(kill()) && number(subs.get(kill()),"progress")==killProgress,"Wrong actual kill proof");
      require(((EnumValueDescriptor)field(subs.get(kill()),"status")).getNumber()==(killProgress==1?2:1),"Wrong kill phase");
      if(killProgress==0) require(!subs.containsKey(following()),"Post-kill source talk accepted prematurely");
      else require(subs.containsKey(following()),"Actual kill did not accept original next talk");
    }
    if(completed) require(number(subs.get(following()),"progress")==1 && number(subs.get(last()),"progress")==1,
        "Original tails not completed");
    check(true,label);
  }

  private void victory(JsonObject input) throws Exception {
    var scene=currentScene();
    var found=originalMonsters(scene);
    require(found.size()==1,"Exactly one real source-owned target must exist");
    var target=found.getFirst();
    var npc=(DynamicMessage)field(target,"npc_monster");
    require(number(npc,"monster_id")==input.get("npcMonster").getAsInt()
        && number(npc,"event_id")==input.get("event").getAsInt(),"Original NPC/event binding changed");
    int world=number(npc,"world_level"),entity=number(target,"entity_id"),leader=number(scene,"leader_entity_id");
    require(world==0 && input.get("existingCoreBattleCredit").getAsInt()==36,"Isolated player/source world credit changed");
    var expected=input.getAsJsonObject("stages").getAsJsonObject(Integer.toString(world));
    require(expected!=null && leader>0,"Missing original world stage or actual living caster");
    var attack=call("SceneCastSkillCsReq",Map.of("cast_entity_id",leader,"attacked_by_entity_id",leader,
        "skill_index",0,"hit_target_entity_id_list",List.of(entity)),0);
    require(attack.hasField(attack.getDescriptorForType().findFieldByName("battle_info")),"Actual attack did not create battle");
    var battle=(DynamicMessage)field(attack,"battle_info");
    int id=number(battle,"battle_id"),stage=number(battle,"stage_id");
    var outcomes=(List<?>)field(attack,"monster_battle_info");
    require(id>0 && stage==expected.get("stage").getAsInt() && outcomes.size()==1,"Wrong original primary battle");
    var outcome=(DynamicMessage)outcomes.getFirst();
    check(number(attack,"cast_entity_id")==leader && number(outcome,"target_monster_entity_id")==entity
        && ((EnumValueDescriptor)field(outcome,"monster_battle_type")).getName().equals("MONSTER_BATTLE_TYPE_TRIGGER_BATTLE"),
        "actual-owned-caster-primary-entity-and-trigger-result-"+world);
    var current=(DynamicMessage)field(call("GetCurBattleInfoCsReq"),"battle_info");
    check(number(current,"battle_id")==id && number(current,"stage_id")==stage,"acknowledged-battle-is-current");
    var waves=new ArrayList<List<Integer>>();
    for(var raw:(List<?>)field(battle,"monster_wave_list")) {
      var wave=(DynamicMessage)raw;
      require(number(wave,"battle_stage_id")==stage,"Different source wave stage");
      var values=new ArrayList<Integer>();
      for(var m:(List<?>)field(wave,"monster_list")) values.add(number((DynamicMessage)m,"monster_id"));
      waves.add(values);
    }
    var original=new ArrayList<List<Integer>>();
    for(var raw:expected.getAsJsonArray("waves")) {
      var values=new ArrayList<Integer>(); for(var m:raw.getAsJsonArray()) values.add(m.getAsInt()); original.add(values);
    }
    check(waves.equals(original),"actual-battle-keeps-original-world-stage-and-waves-"+stage);
    progress(1,0,false,"starting-battle-does-not-complete-source-kill");
    var win=protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status")
        .getEnumType().findValueByName("BATTLE_END_WIN");
    call("PVEBattleResultCsReq",Map.of("battle_id",id+1,"stage_id",stage,"end_status",win),Retcode.FAIL.getVal());
    progress(1,0,false,"unaccepted-battle-id-cannot-complete-source-kill");
    call("PVEBattleResultCsReq",Map.of("battle_id",id,"stage_id",stage,"end_status",win),0);
    progress(1,1,false,"accepted-original-battle-victory-completes-actual-kill");
    quest(1,"kill-alone-does-not-finish-active-target"); points(0,"kill-alone-does-not-grant-active-points");
    call("PVEBattleResultCsReq",Map.of("battle_id",id,"stage_id",stage,"end_status",win),Retcode.FAIL.getVal());
    var stale=call("SceneCastSkillCsReq",Map.of("cast_entity_id",leader,"attacked_by_entity_id",leader,
        "skill_index",0,"hit_target_entity_id_list",List.of(entity)),Retcode.REQ_PARA_INVALID.getVal());
    check(!stale.hasField(stale.getDescriptorForType().findFieldByName("battle_info"))
        && ((List<?>)field(stale,"monster_battle_info")).isEmpty(),"defeated-live-scene-id-does-not-create-another-battle");
    progress(1,1,false,"rejected-dead-entity-replay-keeps-original-kill-once");
    rebuildTargetEntry();
    scene=currentScene();
    check(originalMonsters(scene).isEmpty(),"same-period-reentry-does-not-respawn-completed-source-target");
    originalTalkPropRemains(scene,"same-period-reentry-keeps-original-source-talk-prop");
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
    mainMission=credentials.get("mainMission").getAsInt(); dayIndex=credentials.get("dayIndex").getAsInt();
    require(Set.of(3000211,3000212,3000213).contains(mainMission) && Set.of(1,2).contains(dayIndex),"Unknown source case");
    var token=call("PlayerGetTokenCsReq",Map.of("account_uid",credentials.get("accountUid").getAsString(),
        "token",credentials.get("comboToken").getAsString(),"platform",1),0);
    int uid=number(token,"uid");
    var login=call("PlayerLoginCsReq"); call("PlayerLoginFinishCsReq");
    System.out.println("HKRPG_DAILY_CLOCK_FIXTURE_READY:"+uid); System.out.flush();
    require("resume".equals(control.readLine()),"Clock fixture did not resume");
    String phase=credentials.get("phase").getAsString();
    boolean completed=Set.of("unclaimed","claimed").contains(phase);
    boolean unpaidPrior=credentials.get("unpaidPriorRun").getAsBoolean();
    int coreCredit=credentials.get("existingCoreBattleCredit").getAsInt();
    require(coreCredit==36,"Existing core world-zero credit conversion changed");
    int finalCredits=(unpaidPrior?5000:dayIndex*5000)+dayIndex*coreCredit;
    int killedDays=dayIndex-1+(Set.of("first","kill").contains(phase)?0:1);
    int expectedCredits=(unpaidPrior?0:(dayIndex-1)*5000)+(completed?5000:0)+killedDays*coreCredit;
    int wallet=number((DynamicMessage)field(login,"basic_info"),"scoin");
    check(wallet==expectedCredits,"actual-wallet-restored-"+dayIndex+"-"+phase);
    progress(phase.equals("first")?0:1,phase.equals("first")?-1:phase.equals("kill")?0:1,completed,
        "source-assignment-and-objectives-"+dayIndex+"-"+phase);
    quest(phase.equals("claimed")?3:completed?2:1,"actual-active-objective-"+dayIndex+"-"+phase);
    points(phase.equals("claimed")?200:0,"actual-active-points-"+dayIndex+"-"+phase);
    if(phase.equals("first")) {
      if(unpaidPrior && dayIndex==2) {
        var restored=currentScene();
        check(number(restored,"plane_id")==floor()/1000 && number(restored,"floor_id")==floor()
            && number(restored,"entry_id")== (floor()==20002001?2000201:2000301),
            "unpaid-kill-H2-recovery-keeps-original-floor-before-any-departure");
        if(mainMission==3000213)
          check(originalMonsters(restored).size()==1,"G95-unpaid-expiry-loads-new-accepted-run-monster-on-restored-floor");
      }
      origin();
      if(mainMission==3000213) check(originalMonsters(currentScene()).size()==1,"original-G95-main-Accept-loads-before-kill-goal");
      talk(last(),"Mission_"+last(),Retcode.MISSION_NOT_DOING.getVal(),"source-final-talk-before-kill-rejected");
      talk(first(),"wrong-kill-dialogue",Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),"wrong-source-dialogue-rejected");
      talk(first(),"Mission_"+first(),0,"original-first-talk-accepts-kill-goal");
      targetEntry();
      check(originalMonsters(currentScene()).size()==1,"new-accepted-period-loads-actual-original-monster");
      progress(1,0,false,"actual-source-target-load-does-not-complete-kill");
      claim(Retcode.QUEST_NOT_FINISH.getVal(),"unfinished-active-claim-rejected");
    } else if(phase.equals("kill")) {
      victory(credentials);
    } else if(phase.equals("tail")) {
      targetEntry();
      var scene=currentScene();
      check(originalMonsters(scene).isEmpty(),"same-UID-H2-recovery-keeps-source-kill-completed-without-respawn");
      originalTalkPropRemains(scene,"same-UID-H2-recovery-keeps-original-talk-prop");
      talk(following(),"Mission_"+following(),0,"original-post-kill-report-talk"); origin();
      awaitedQuest=2100003;questCompletionNotify=false;mainRewardNotify=false;
      talk(last(),"Mission_"+last(),0,"original-final-talk-completes-main-commission");
      check(questCompletionNotify,"active-completion-notify-before-final-talk-response");
      check(mainRewardNotify && observedWallet==finalCredits,"original-5000-credit-notify-before-final-talk-response");
      awaitedQuest=0;progress(1,1,true,"original-kill-main-and-tails-finished");quest(2,"actual-active-finished");
      points(0,"active-not-auto-claimed");
      talk(last(),"Mission_"+last(),Retcode.MISSION_NOT_DOING.getVal(),"source-final-talk-not-replayed");
      call("AcceptMainMissionCsReq",Map.of("main_mission_id",mainMission),Retcode.MISSION_ALREADY_DONE.getVal());
      check(true,"same-day-source-main-not-restarted");
    } else if(phase.equals("unclaimed")) {
      claim(0,"actual-original-200-active-points-claimed");points(200,"exact-200-active-points");quest(3,"actual-active-closed");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"duplicate-active-claim-rejected");
    } else {
      require(phase.equals("claimed"),"Unknown phase");
      claim(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),"restored-source-active-not-repaid");
      call("AcceptMainMissionCsReq",Map.of("main_mission_id",mainMission),Retcode.MISSION_ALREADY_DONE.getVal());
      check(true,"restored-source-main-not-repaid");
    }
    var result=new LinkedHashMap<String,Object>();
    result.put("passed",true);result.put("uid",uid);result.put("mainMission",mainMission);result.put("dayIndex",dayIndex);
    result.put("phase",phase);result.put("checks",checks);result.put("requests",requests);result.put("loginWallet",wallet);
    result.put("unpaidPriorRun",unpaidPrior);
    result.put("immediateQuestCompletionNotifyVerified",questCompletionNotify);
    result.put("mainRewardNotifyBeforeResponseVerified",mainRewardNotify);
    result.put("constructedAcceptedBattleWinReport",phase.equals("kill"));result.put("clientGameplayVerified",false);
    result.put("wireFieldsVerifiedBeforeConnect",true);
    result.put("existingCoreBattleCreditPerAcceptedWin",coreCredit);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeDailyMonsterProbe();
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
