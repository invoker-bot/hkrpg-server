package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.h2.mvstore.MVStore;

/** Two independent Player reads from a stopped, owned fixture MVStore opened read-only. */
public final class DailyFollowupFixtureInspector {
  private static final int MAIN=3000603,FIRST=300060301,GOAL=300060302,SECTION=1211402;
  private static final String SOURCE="4.6:3000603:5f5e23dad522a9f1f22643206d85f84709432c065fb2328cd26f2d84c5feb4d7:7531517089dcbd63b3fde9889a3923f988e2232a89b075fa865e606840b3e775";

  private static void require(boolean value,String why) { if(!value) throw new IllegalStateException(why); }
  private static String sha(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
  private static List<Integer> ints(JsonArray rows) { return rows.asList().stream().map(JsonElement::getAsInt).toList(); }

  private static Map<String,Object> state(QuestState state,int id,QuestState.Phase phase,long day) {
    require(state!=null && state.getQuestId()==id && state.getPhase()==phase,"Wrong stored state: "+id+" expected="+phase);
    boolean done=phase!=QuestState.Phase.DOING;
    require(state.getProgress()==(done?1:0),"Wrong stored progress: "+id);
    require(done?state.getFinishTime()>0 && DailyManager.day(Instant.ofEpochSecond(state.getFinishTime()))==day
        :state.getFinishTime()==0,"Stored finishTime is outside the accepted day: "+id);
    var value=new TreeMap<String,Object>();
    value.put("id",id);value.put("phase",phase.name());value.put("progress",state.getProgress());
    value.put("finishTime",state.getFinishTime());value.put("runDay",day);
    if(state.getObservationKey()!=null) value.put("acceptance",state.getObservationKey());
    return value;
  }

  private static List<Integer> prefix(int first,int second,int third,int size) {
    require(Set.of(121140302,121140303).contains(first) && Set.of(121140306,121140308).contains(second)
        && Set.of(121140311,121140312).contains(third) && Set.of(3,6,9,13).contains(size),"Invalid original 3000603 prefix");
    var full=List.of(121140301,121140316,121140317,first,121140304,121140305,second,
        second==121140306?121140307:121140309,121140310,third,121140313,121140314,121140315);
    return full.subList(0,size);
  }

  private static List<Integer> options(int size) {
    return switch(size) {
      case 3 -> List.of(121140302,121140303);
      case 6 -> List.of(121140306,121140308);
      case 9 -> List.of(121140311,121140312);
      case 13 -> List.of();
      default -> throw new IllegalStateException("Invalid original 3000603 offer");
    };
  }

  private static Map<String,Object> run(JsonObject lease,QuestState main,Map<Integer,QuestState> subs,
      long day,boolean completed,int first,int second,int third,int size) {
    require(lease!=null && lease.get("day").getAsLong()==day && lease.get("mainId").getAsInt()==MAIN
        && lease.get("goalId").getAsInt()==GOAL && lease.get("sectionId").getAsInt()==SECTION
        && SOURCE.equals(lease.get("source").getAsString()),"Wrong persisted Phone source/run identity");
    var mainProof=state(main,MAIN,completed?QuestState.Phase.CLOSED:QuestState.Phase.DOING,day);
    var firstProof=state(subs.get(FIRST),FIRST,QuestState.Phase.FINISHED,day);
    var goalProof=state(subs.get(GOAL),GOAL,completed?QuestState.Phase.FINISHED:QuestState.Phase.DOING,day);
    String mainKey=lease.get("mainAcceptance").getAsString(),goalKey=lease.get("goalAcceptance").getAsString();
    require(UUID.fromString(mainKey).toString().equals(mainKey) && UUID.fromString(goalKey).toString().equals(goalKey)
        && !mainKey.equals(goalKey) && mainKey.equals(main.getObservationKey())
        && goalKey.equals(subs.get(GOAL).getObservationKey()),"Phone does not bind the actual persisted Main/Goal acceptances");
    var phone=lease.getAsJsonObject("phone");
    var history=ints(phone.getAsJsonArray("history"));var offered=ints(phone.getAsJsonArray("offered"));
    var options=options(size);
    require(phone.get("id").getAsInt()==SECTION && phone.get("finished").getAsBoolean()==completed
        && phone.get("refreshed").getAsLong()>0 && history.equals(prefix(first,second,third,size)) && offered.equals(options),
        "Stored actual choices/Phone prefix differ");
    return Map.of("main",mainProof,"first",firstProof,"goal",goalProof,"history",history,"offered",offered,
        "phoneFinished",completed,"source",SOURCE,"mainAcceptance",mainKey,"goalAcceptance",goalKey);
  }

  private static void reward(GameplayState g,String key,boolean earned) {
    var receipt=g.getRewards().get(key);var plan=g.getMissionRewardPlans().get(key);
    if(!earned) { require(receipt==null && plan==null,"Unpaid period acquired a main reward: "+key);return; }
    require(receipt!=null && receipt.isGranted() && plan!=null && plan.isReady()
        && plan.getRewardIds().equals(List.of(231)) && plan.getChoices().isEmpty()
        && plan.getItems().equals(Map.of(2,5000)),"Source main reward proof differs: "+key);
  }

  private static void controls(JsonObject control) {
    int uid=control.get("uid").getAsInt(),first=control.get("firstChoice").getAsInt(),second=control.get("secondChoice").getAsInt(),third=control.get("thirdChoice").getAsInt();
    long firstDay=control.get("firstDay").getAsLong(),day=control.get("runDay").getAsLong();
    String scenario=control.get("scenario").getAsString(),at=control.get("expiredAt").getAsString();
    int size=control.get("phoneSize").getAsInt();boolean completed=control.get("completed").getAsBoolean(),intro=control.get("introOnly").getAsBoolean();
    require(uid>0 && firstDay>0 && Set.of(121140302,121140303).contains(first)
        && Set.of(121140306,121140308).contains(second) && Set.of(121140311,121140312).contains(third)
        && Set.of("settled","expired-unpaid").contains(scenario)
        && Set.of("noReplies","oneReply","twoReplies","allReplies").contains(at)
        && (day==firstDay || day==firstDay+1)
        && (intro?day==firstDay && !completed && size==0:Set.of(3,6,9,13).contains(size))
        && (!completed || size==13),"Invalid inspection controls");
  }


  private static Map<String,Object> followup(GameplayState g,JsonObject control,long day){
    int stage=control.get("followupStage").getAsInt();require(stage>=0&&stage<=3,"Invalid original followup stage");
    var main=g.getMainMissions().get(3000604);var enter=g.getSubMissions().get(300060400);var talk=g.getSubMissions().get(300060401);
    require(!g.getDailyMissionPeriods().getRunDays().containsKey(3000604)
        && g.getDailyMissionPeriods().getArchives().values().stream().noneMatch(row->row.getMainId()==3000604)
        && g.getDailyMissionPeriods().getExpired().values().stream().noneMatch(row->row.getMainId()==3000604),"Non-pool sequel gained an invented daily period");
    for(var choices:g.getDailyMissionSelections().getDays().values())require(choices.stream().noneMatch(row->row.getId()==3000604),"Non-pool sequel consumed an original pool slot");
    if(stage==0){require(main==null&&enter==null&&talk==null,"Partial parent Phone created the sequel early");reward(g,"mission:3000604",false);return Map.of("stage",0,"absent",true);}
    var mainProof=state(main,3000604,stage==3?QuestState.Phase.CLOSED:QuestState.Phase.DOING,day);
    var enterProof=state(enter,300060400,stage==1?QuestState.Phase.DOING:QuestState.Phase.FINISHED,day);
    var proof=new TreeMap<String,Object>();proof.put("stage",stage);proof.put("main",mainProof);proof.put("enter",enterProof);
    if(stage==1)require(talk==null,"Original EnterFloor has not yet made Talk eligible");
    else proof.put("talk",state(talk,300060401,stage==3?QuestState.Phase.FINISHED:QuestState.Phase.DOING,day));
    reward(g,"mission:3000604",stage==3);proof.put("mainCreditPaid",stage==3?5000:0);proof.put("poolSlotConsumed",false);
    return proof;
  }

  public static void main(String[] args) throws Exception {
    if(args.length==2 && args[0].equals("--verify-controls")) {
      var input=Path.of(args[1]).toRealPath();
      require(input.startsWith(Path.of("").toRealPath().resolve("build")),"Unowned control verification file");
      var values=JsonParser.parseString(Files.readString(input)).getAsJsonArray();var output=new JsonArray();
      for(var raw:values) {
        var control=raw.getAsJsonObject();controls(control);var result=new JsonObject();
        int size=control.get("phoneSize").getAsInt();
        var gson=new Gson();
        result.add("history",gson.toJsonTree(size==0?List.of():prefix(control.get("firstChoice").getAsInt(),
            control.get("secondChoice").getAsInt(),control.get("thirdChoice").getAsInt(),size)));
        result.add("offered",gson.toJsonTree(size==0?List.of():options(size)));output.add(result);
      }
      System.out.println(output);return;
    }
    var root=Path.of("").toRealPath();var dir=Path.of(args[0]).toRealPath();var input=Path.of(args[1]).toRealPath();
    require(dir.startsWith(root.resolve("build/smoke")) && dir.getFileName().toString().startsWith("daily-message-g61-native-3000604-followup-")
        && input.startsWith(dir),"Unowned stopped fixture");
    var control=JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid=control.get("uid").getAsInt(),first=control.get("firstChoice").getAsInt(),second=control.get("secondChoice").getAsInt(),third=control.get("thirdChoice").getAsInt();
    long firstDay=control.get("firstDay").getAsLong(),day=control.get("runDay").getAsLong();
    String scenario=control.get("scenario").getAsString(),at=control.get("expiredAt").getAsString();
    int size=control.get("phoneSize").getAsInt();boolean completed=control.get("completed").getAsBoolean(),intro=control.get("introOnly").getAsBoolean();
    controls(control);
    var marker=JsonParser.parseString(Files.readString(dir.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("dailyMessageFixture").getAsBoolean()
        && marker.get("originalMission").getAsInt()==MAIN,"Wrong fixture marker");
    var config=dir.resolve("config.json");var file=dir.resolve("data/database.mv");
    require(Files.isRegularFile(file),"Stopped H2 database missing");
    String configSha=sha(config),beforeSha=sha(file);var proof=new TreeMap<String,Object>();
    var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();
    var server=new MongoServer(new H2Backend(mv));var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var players=client.getDatabase("hkrpg").getCollection("players");var filter=Filters.eq("_id",uid);
      require(players.countDocuments()==1 && players.countDocuments(filter)==1,"Not one isolated player");
      var before=Objects.requireNonNull(players.find(filter).first());
      var store=Morphia.createDatastore(client,"hkrpg");store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,QuestState.class,Position.class);
      var firstRead=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      var secondRead=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(firstRead!=null && secondRead!=null && firstRead!=secondRead,"Independent Morphia Player reads failed");
      var gson=new Gson();var g=firstRead.getGameplay();
      require(gson.toJson(g).equals(gson.toJson(secondRead.getGameplay())) && firstRead.getScoin()==secondRead.getScoin(),
          "Independent stored Player views differ");
      // This compiles on the old protocol jar, but the new domain must actually survive Morphia at runtime.
      var json=gson.toJsonTree(g).getAsJsonObject();
      require(json.has("dailyMissionMessages"),"Runtime does not preserve the accepted Phone domain");
      var runs=json.getAsJsonObject("dailyMissionMessages").getAsJsonObject("runs");
      if(intro) {
        require(day==firstDay && !completed && size==0 && runs.isEmpty()
            && !g.getMainMissions().containsKey(MAIN) && !g.getSubMissions().containsKey(FIRST)
            && !g.getSubMissions().containsKey(GOAL) && !g.getPhoneSections().containsKey(SECTION),
            "Original G61 introduction invented a 3000603 mission or receipt");
        require(firstRead.getScoin()==0 && g.getDailyPoints()==0 && g.getDailyDay()==day
            && !g.getDailyQuests().containsKey(2100003) && g.getDailyMissionPeriods().getRunDays().isEmpty()
            && g.getDailyMissionPeriods().getArchives().isEmpty() && g.getDailyMissionPeriods().getExpired().isEmpty(),
            "Introduction seeded an active objective, a paid period or a reward");
        reward(g,"mission:"+MAIN,false);
        proof.put("credit",0);proof.put("activePoints",0);proof.put("runDays",Map.of());
        proof.put("persistedPhoneRuns",runs);proof.put("introHasNoCommissionOrPhoneRun",true);
      } else {
      boolean later=day>firstDay,expired=scenario.equals("expired-unpaid");
      require(runs.size()==(later?2:1) && !g.getPhoneSections().containsKey(SECTION),"Phone leaked into global history or lost an accepted run");
      proof.put("currentRun",run(runs.getAsJsonObject(MAIN+":"+day),g.getMainMissions().get(MAIN),g.getSubMissions(),
          day,completed,first,second,third,size));
      var periods=g.getDailyMissionPeriods();
      require(periods.getRunDays().equals(later?Map.of(MAIN,day):Map.of())
          && periods.getArchives().size()==(later&&!expired?1:0) && periods.getExpired().size()==(later&&expired?1:0),
          "Old period was not correctly archived/expired");
      var goals=Map.of(FIRST,1,GOAL,1);
      if(later) {
        int oldFirst=control.get("oldFirstChoice").getAsInt(),oldSecond=control.get("oldSecondChoice").getAsInt(),oldThird=control.get("oldThirdChoice").getAsInt();
        var oldLease=runs.getAsJsonObject(MAIN+":"+firstDay);
        if(expired) {
          var old=periods.getExpired().get(MAIN+":"+firstDay);
          require(old!=null && old.getMainId()==MAIN && old.getDay()==firstDay && old.getGoals().equals(goals)
              && DailyManager.day(Instant.ofEpochSecond(old.getExpiredAt()))==day,"Wrong stored unpaid expiry");
          int oldSize=switch(at) {case "noReplies"->3;case "oneReply"->6;case "twoReplies"->9;case "allReplies"->13;default->throw new IllegalStateException("Invalid unpaid prefix");};
          proof.put("expiredRun",run(oldLease,old.getMain(),old.getSubs(),firstDay,false,oldFirst,oldSecond,oldThird,oldSize));
          proof.put("expiredAt",old.getExpiredAt());
        } else {
          var old=periods.getArchives().get(MAIN+":"+firstDay);
          require(old!=null && old.getMainId()==MAIN && old.getDay()==firstDay && old.getGoals().equals(goals)
              && old.getRewardKeys().equals(List.of("mission:"+MAIN)),"Wrong stored paid archive");
          proof.put("archivedRun",run(oldLease,old.getMain(),old.getSubs(),firstDay,true,oldFirst,oldSecond,oldThird,13));
        }
        var current=runs.getAsJsonObject(MAIN+":"+day);
        require(!current.get("mainAcceptance").equals(oldLease.get("mainAcceptance"))
            && !current.get("goalAcceptance").equals(oldLease.get("goalAcceptance")),"New accepted day reused old Main/Goal identity");
      }
      var selections=g.getDailyMissionSelections();var days=later?Set.of(Long.toString(firstDay),Long.toString(day)):Set.of(Long.toString(day));
      require(selections.getLatestDay()==day && selections.getDays().keySet().equals(days),"Stored source selections differ");
      for(var choices:selections.getDays().values()) {
        require(choices.size()==1,"Source daily quota is not one");var choice=choices.getFirst();
        require(choice.getId()==MAIN && choice.getType()==1 && choice.getGroup()==30003,"Wrong source commission selection");
      }
      reward(g,"mission:"+MAIN,(later&&!expired)||(!later&&completed));
      reward(g,"daily-mission:"+(firstDay+1)+":mission:"+MAIN,later&&completed);
      int credits=(later&&!expired?5000:0)+(completed?5000:0)+(control.get("followupStage").getAsInt()==3?5000:0);
      require(firstRead.getScoin()==credits && g.getDailyDay()==day && g.getDailyPoints()==(completed?200:0),
          "Stored credit/active points/day differ: "+firstRead.getScoin()+"/"+g.getDailyPoints()+"/"+g.getDailyDay());
      proof.put("dailyQuest",state(g.getDailyQuests().get(2100003),2100003,completed?QuestState.Phase.CLOSED:QuestState.Phase.DOING,day));
      proof.put("credit",credits);proof.put("activePoints",g.getDailyPoints());proof.put("runDays",periods.getRunDays());
      proof.put("persistedPhoneRuns",runs);
      }
      proof.put("followup",followup(g,control,day));
      require(before.equals(players.find(filter).first()) && players.countDocuments()==1,"Inspector mutated player documents");
      proof.put("independentMorphiaReadCount",2);proof.put("changedPlayerDocumentCount",0);
    } finally { server.shutdownNow();if(!mv.isClosed())mv.close(); }
    require(beforeSha.equals(sha(file)) && configSha.equals(sha(config)),"Read-only H2/config bytes changed");
    proof.put("databaseSHA256",beforeSha);proof.put("databaseFileBytesUnchanged",true);proof.put("mvStoreReadOnly",true);
    proof.put("uid",uid);proof.put("scenario",scenario);proof.put("expiredAt",at);proof.put("runDay",day);
    proof.put("completed",completed);proof.put("introOnly",intro);proof.put("passed",true);System.out.println(new Gson().toJson(proof));
  }
}
