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
public final class DailyMessageOriginFixtureInspector {
  private static final int MAIN=3000601,PRE=300060001,FIRST=300060101,GOAL=300060102,SECTION=1211400;
  private static final String SOURCE="4.6:3000601:976d1f4da59fe8249135fbce998c2db3d7750b4354cfd46398c98f21f58661bd:7531517089dcbd63b3fde9889a3923f988e2232a89b075fa865e606840b3e775:a8ad32be0fe86430775eff1a8c1f42a0073bc6eadc9505bc2eb758c32ec23ea6";

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

  private static List<Integer> prefix(int reply,int size) {
    require(Set.of(121140102,121140104,121140106).contains(reply) && Set.of(2,7).contains(size),"Invalid original1 prefix");
    return List.of(121140101,121140111,reply,reply+1,121140108,121140109,121140110).subList(0,size);
  }
  private static List<Integer> options(int size) {
    return switch(size) {case 2->List.of(121140102,121140104,121140106);case 7->List.of();
      default->throw new IllegalStateException("Invalid original1 offer");};
  }
  private static Map<String,Object> run(JsonObject lease,QuestState main,Map<Integer,QuestState> subs,
      long day,boolean completed,int reply,int size) {
    require(lease!=null && lease.get("day").getAsLong()==day && lease.get("mainId").getAsInt()==MAIN
        && lease.get("goalId").getAsInt()==GOAL && lease.get("sectionId").getAsInt()==SECTION
        && SOURCE.equals(lease.get("source").getAsString()),"Wrong stored original1 source/period");
    var mainProof=state(main,MAIN,completed?QuestState.Phase.CLOSED:QuestState.Phase.DOING,day);
    var initialProof=state(subs.get(PRE),PRE,QuestState.Phase.FINISHED,day);
    var firstProof=state(subs.get(FIRST),FIRST,QuestState.Phase.FINISHED,day);
    var goalProof=state(subs.get(GOAL),GOAL,completed?QuestState.Phase.FINISHED:QuestState.Phase.DOING,day);
    for(var pair:Map.of("mainAcceptance",main,"initialAcceptance",subs.get(PRE),"firstAcceptance",subs.get(FIRST),"goalAcceptance",subs.get(GOAL)).entrySet()) {
      String key=lease.get(pair.getKey()).getAsString();
      require(UUID.fromString(key).toString().equals(key) && key.equals(pair.getValue().getObservationKey()),"Wrong stored original1 acceptance: "+pair.getKey());
    }
    var phone=lease.getAsJsonObject("phone");var history=ints(phone.getAsJsonArray("history"));var offered=ints(phone.getAsJsonArray("offered"));
    require(phone.get("id").getAsInt()==SECTION && phone.get("finished").getAsBoolean()==completed && phone.get("refreshed").getAsLong()>0
        && history.equals(prefix(reply,size)) && offered.equals(options(size)),"Stored original1 history differs from actual reply");
    var proof=new TreeMap<String,Object>();proof.put("main",mainProof);proof.put("initial",initialProof);proof.put("first",firstProof);proof.put("goal",goalProof);
    proof.put("history",history);proof.put("offered",offered);proof.put("phoneFinished",completed);proof.put("source",SOURCE);
    for(String key:List.of("mainAcceptance","initialAcceptance","firstAcceptance","goalAcceptance"))proof.put(key,lease.get(key).getAsString());
    return proof;
  }

  private static void reward(GameplayState g,String key,boolean earned) {
    var receipt=g.getRewards().get(key);var plan=g.getMissionRewardPlans().get(key);
    if(!earned) { require(receipt==null && plan==null,"Unpaid period acquired a main reward: "+key);return; }
    require(receipt!=null && receipt.isGranted() && plan!=null && plan.isReady()
        && plan.getRewardIds().equals(List.of(231)) && plan.getChoices().isEmpty()
        && plan.getItems().equals(Map.of(2,5000)),"Source main reward proof differs: "+key);
  }

  private static void controls(JsonObject c) {
    int uid=c.get("uid").getAsInt(),reply=c.get("firstChoice").getAsInt(),size=c.get("phoneSize").getAsInt();
    long firstDay=c.get("firstDay").getAsLong(),day=c.get("runDay").getAsLong();String phase=c.get("phase").getAsString();
    boolean intro=c.get("introOnly").getAsBoolean(),completed=c.get("completed").getAsBoolean();
    require(uid>0 && firstDay>0 && Set.of(121140102,121140104,121140106).contains(reply)
        && Set.of("settled","expired-unpaid").contains(c.get("scenario").getAsString())
        && Set.of("noReplies","allReplies").contains(c.get("expiredAt").getAsString()) && (day==firstDay || day==firstDay+1)
        && Set.of("intro","initial","open","middle","finish","claimed","boundary-reject").contains(phase)
        && intro==phase.equals("intro") && completed==Set.of("finish","claimed").contains(phase)
        && (intro?day==firstDay && size==0:phase.equals("initial")?size==0:Set.of(2,7).contains(size))
        && (!completed || size==7),"Invalid original1 inspection controls");
    require(!Set.of("middle","finish","claimed").contains(phase) || size==7,"Wrong one-reply terminal size");
    String scenario=c.get("scenario").getAsString(),at=c.get("expiredAt").getAsString();
    if(phase.equals("boundary-reject"))
      require(scenario.equals("expired-unpaid") && day==firstDay && size==(at.equals("allReplies")?7:2),
          "Boundary inspection must preserve the unpaid old period and its actual prefix");
    if(phase.equals("open"))
      require(size==(scenario.equals("expired-unpaid") && day==firstDay && at.equals("allReplies")?7:2),
          "Open inspection does not match the actual original1 period prefix");
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
        result.add("history",gson.toJsonTree(size==0?List.of():prefix(control.get("firstChoice").getAsInt(),size)));
        result.add("offered",gson.toJsonTree(size==0?List.of():options(size)));output.add(result);
      }
      System.out.println(output);return;
    }
    var root=Path.of("").toRealPath();var dir=Path.of(args[0]).toRealPath();var input=Path.of(args[1]).toRealPath();
    require(dir.startsWith(root.resolve("build/smoke")) && dir.getFileName().toString().startsWith("daily-message-g61-native-3000601-")
        && input.startsWith(dir),"Unowned stopped fixture");
    var control=JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid=control.get("uid").getAsInt(),reply=control.get("firstChoice").getAsInt();
    long firstDay=control.get("firstDay").getAsLong(),day=control.get("runDay").getAsLong();
    String scenario=control.get("scenario").getAsString(),at=control.get("expiredAt").getAsString();
    int size=control.get("phoneSize").getAsInt();boolean completed=control.get("completed").getAsBoolean(),intro=control.get("introOnly").getAsBoolean();
    String phase=control.get("phase").getAsString();
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
            && !g.getMainMissions().containsKey(MAIN) && !g.getSubMissions().containsKey(PRE) && !g.getSubMissions().containsKey(FIRST)
            && !g.getSubMissions().containsKey(GOAL) && !g.getPhoneSections().containsKey(SECTION),
            "Original G61 introduction invented a 3000601 mission or receipt");
        require(firstRead.getScoin()==0 && g.getDailyPoints()==0 && g.getDailyDay()==day
            && !g.getDailyQuests().containsKey(2100003) && g.getDailyMissionPeriods().getRunDays().isEmpty()
            && g.getDailyMissionPeriods().getArchives().isEmpty() && g.getDailyMissionPeriods().getExpired().isEmpty(),
            "Introduction seeded an active objective, a paid period or a reward");
        reward(g,"mission:"+MAIN,false);
        proof.put("credit",0);proof.put("activePoints",0);proof.put("runDays",Map.of());
        proof.put("persistedPhoneRuns",runs);proof.put("introHasNoCommissionOrPhoneRun",true);
      } else {
      boolean later=day>firstDay,expired=scenario.equals("expired-unpaid"),initialOnly=phase.equals("initial");
      require(runs.size()==(initialOnly?(later?1:0):later?2:1) && !g.getPhoneSections().containsKey(SECTION),"Phone leaked globally or lost an accepted period");
      var periods=g.getDailyMissionPeriods();
      if(initialOnly) {
        require(!runs.has(MAIN+":"+day) && !g.getSubMissions().containsKey(GOAL),"Initial report invented a Phone goal or receipt");
        proof.put("initialStage",state(g.getSubMissions().get(PRE),PRE,QuestState.Phase.FINISHED,day));
        proof.put("firstStage",state(g.getSubMissions().get(FIRST),FIRST,QuestState.Phase.DOING,day));
        proof.put("mainStage",state(g.getMainMissions().get(MAIN),MAIN,QuestState.Phase.DOING,day));
      }else proof.put("currentRun",run(runs.getAsJsonObject(MAIN+":"+day),g.getMainMissions().get(MAIN),g.getSubMissions(),day,completed,reply,size));
      require(periods.getRunDays().equals(later?Map.of(MAIN,day):Map.of())
          && periods.getArchives().size()==(later&&!expired?1:0) && periods.getExpired().size()==(later&&expired?1:0),"Wrong three-stage archive/expiry");
      var goals=Map.of(PRE,1,FIRST,1,GOAL,1);
      if(later) {
        int oldReply=control.get("oldFirstChoice").getAsInt();var oldLease=runs.getAsJsonObject(MAIN+":"+firstDay);
        if(expired) {
          var old=periods.getExpired().get(MAIN+":"+firstDay);
          require(old!=null && old.getMainId()==MAIN && old.getDay()==firstDay && old.getGoals().equals(goals)
              && DailyManager.day(Instant.ofEpochSecond(old.getExpiredAt()))==day,"Wrong stored original1 unpaid expiry");
          proof.put("expiredRun",run(oldLease,old.getMain(),old.getSubs(),firstDay,false,oldReply,at.equals("noReplies")?2:7));proof.put("expiredAt",old.getExpiredAt());
        }else{
          var old=periods.getArchives().get(MAIN+":"+firstDay);
          require(old!=null && old.getMainId()==MAIN && old.getDay()==firstDay && old.getGoals().equals(goals)
              && old.getRewardKeys().equals(List.of("mission:"+MAIN)),"Wrong original1 paid archive");
          proof.put("archivedRun",run(oldLease,old.getMain(),old.getSubs(),firstDay,true,oldReply,7));
        }
        if(!initialOnly){var current=runs.getAsJsonObject(MAIN+":"+day);
          for(String key:List.of("mainAcceptance","initialAcceptance","firstAcceptance","goalAcceptance"))
            require(!current.get(key).equals(oldLease.get(key)),"New day reused original1 acceptance: "+key);}
      }
      var selections=g.getDailyMissionSelections();var days=later?Set.of(Long.toString(firstDay),Long.toString(day)):Set.of(Long.toString(day));
      require(selections.getLatestDay()==day && selections.getDays().keySet().equals(days),"Original1 selection days differ");
      for(var choices:selections.getDays().values()) {require(choices.size()==1,"Source Type1 quota differs");var choice=choices.getFirst();
        require(choice.getId()==MAIN && choice.getType()==1 && choice.getGroup()==30003,"Wrong source original1 selection");}
      reward(g,"mission:"+MAIN,(later&&!expired)||(!later&&completed));reward(g,"daily-mission:"+(firstDay+1)+":mission:"+MAIN,later&&completed);
      int credit=(later&&!expired?5000:0)+(completed?5000:0);
      require(firstRead.getScoin()==credit && g.getDailyDay()==day && g.getDailyPoints()==(completed?200:0),"Stored original1 credit/activity/day differ");
      proof.put("dailyQuest",state(g.getDailyQuests().get(2100003),2100003,completed?QuestState.Phase.CLOSED:QuestState.Phase.DOING,day));
      proof.put("credit",credit);proof.put("activePoints",g.getDailyPoints());proof.put("runDays",periods.getRunDays());proof.put("persistedPhoneRuns",runs);
      }
      require(before.equals(players.find(filter).first()) && players.countDocuments()==1,"Inspector mutated player documents");
      proof.put("independentMorphiaReadCount",2);proof.put("changedPlayerDocumentCount",0);
    } finally { server.shutdownNow();if(!mv.isClosed())mv.close(); }
    require(beforeSha.equals(sha(file)) && configSha.equals(sha(config)),"Read-only H2/config bytes changed");
    proof.put("databaseSHA256",beforeSha);proof.put("databaseFileBytesUnchanged",true);proof.put("mvStoreReadOnly",true);
    proof.put("uid",uid);proof.put("scenario",scenario);proof.put("expiredAt",at);proof.put("runDay",day);
    proof.put("completed",completed);proof.put("introOnly",intro);proof.put("passed",true);System.out.println(new Gson().toJson(proof));
  }
}
