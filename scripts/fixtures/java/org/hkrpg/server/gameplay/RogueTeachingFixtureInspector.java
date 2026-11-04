package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Only an owned, stopped smoke database may receive explicit prerequisite/run fixtures. */
public final class RogueTeachingFixtureInspector {
  static void require(boolean ok,String why) { if(!ok)throw new IllegalStateException(why); }
  static Document done(int id) {
    var state=new QuestState(id);state.advance(1,1,1);state.reserveReward();state.closeReward();
    return Document.parse(new Gson().toJson(state));
  }
  public static void main(String[] args) throws Exception {
    var root=Path.of("").toRealPath();var directory=Path.of(args[0]).toRealPath();
    String mode=args[2];int uid=Integer.parseInt(args[1]);
    require(directory.startsWith(root.resolve("build/smoke")) && !directory.equals(root.resolve("build/smoke"))
        && Set.of("seed-legacy","seed-fresh","inspect-legacy","inspect-fresh").contains(mode),"Unowned fixture");
    var marker=JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("rogueTeachingFixture").getAsBoolean(),"Missing marker");
    var file=directory.resolve("data/database.mv");require(Files.isRegularFile(file),"Missing stopped H2");
    var backend=new H2Backend(file.toString());var server=new MongoServer(backend);var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var players=client.getDatabase("hkrpg").getCollection("players");var filter=new Document("_id",uid);
      require(players.countDocuments()==1,"Not a unique owned player");
      var before=Objects.requireNonNull(players.find(filter).first());
      if(mode.startsWith("seed")) {
        var gameplay=before.get("gameplay",Document.class);
        require(!gameplay.get("mainMissions",Document.class).containsKey("4010116")
            && gameplay.get("rogue",Document.class).getString("phase").equals("IDLE"),"Existing target progress");
        var update=new Document();
        for(int id:List.of(4030001,4030002,1010403))update.append("gameplay.mainMissions."+id,done(id));
        update.append("gameplay.subMissions.403000222",done(403000222));
        update.append("gameplay.mainMissions.4010116",Document.parse(new Gson().toJson(new QuestState(4010116))));
        update.append("gameplay.subMissions.401011603",Document.parse(new Gson().toJson(new QuestState(401011603))));
        boolean legacy=mode.equals("seed-legacy");
        if(legacy) {
          var run=new GameplayState.RogueRun(110,101,Map.of(1,304,2,302,3,303,4,304,5,305,6,306,7,307));
          run.getRoster().add(8001);run.getVitals().put(8001,new GameplayState.RogueVitals(100,0));
          run.setInside(true);run.setMp(5);run.enter(3,303);run.getCleared().addAll(Set.of(1,2));
          run.getBlessings().put(600000,1);run.addCoins(500-run.getCoins());
          var raw=JsonParser.parseString(new Gson().toJson(run)).getAsJsonObject();
          raw.add("returnTo",JsonParser.parseString("{\"plane\":80112,\"floor\":80112001,\"entry\":8011201,\"pos\":{\"x\":9902,\"y\":689,\"z\":251},\"rot\":{\"x\":0,\"y\":75000,\"z\":0}}"));
          update.append("gameplay.rogue",Document.parse(raw.toString()));
        }
        update.append("entryId",legacy?8000102:8011201).append("planeId",legacy?80001:80112)
            .append("floorId",legacy?80001002:80112001)
            .append("pos",new Document("x",legacy?5550:9902).append("y",legacy?2843:689).append("z",legacy?-100040:251))
            .append("rot",new Document("x",0).append("y",0).append("z",0));
        players.updateOne(filter,new Document("$set",update));backend.commit();backend.getMvStore().sync();
      }
      var after=Objects.requireNonNull(players.find(filter).first());var gameplay=after.get("gameplay",Document.class);
      var run=gameplay.get("rogue",Document.class);var subs=gameplay.get("subMissions",Document.class);
      if(mode.startsWith("inspect")) {
        for(int id:List.of(401011601,401011602,401011603,403000300,403000301)) {
          var state=subs.get(Integer.toString(id),Document.class);
          require(state!=null && state.getInteger("questId")==id && state.getInteger("progress")==1
              && ((Number)state.get("finishTime")).longValue()>0 && !state.getString("phase").equals("DOING"),"Unpersisted goal "+id);
        }
        require(gameplay.get("storyValues",Document.class).getInteger("mission:4010116:OldValue_0")==1,"Wrong persisted branch");
        if(mode.equals("inspect-legacy")) {
          require(run.getInteger("mapId")==101 && run.getInteger("siteId")==3 && run.getInteger("roomId")==303,"Rewrote legacy route");
          require(run.get("blessings",Document.class).getInteger("600000")==2 && run.getInteger("coins")==400,"Repeated or missing enhancement payment");
          require(!subs.get("403000304",Document.class).getString("phase").equals("DOING"),"Unpersisted Herta teaching");
        } else require(run.getInteger("mapId")==3 && run.getInteger("roomId")==301,"Wrong teaching first room");
        require(before.equals(after),"Read-only inspection changed player");
      }
      var output=new LinkedHashMap<String,Object>();output.put("passed",true);output.put("uid",uid);output.put("mode",mode);
      output.put("runId",run.getString("runId"));output.put("rooms",run.get("rooms"));
      output.put("coins",run.get("coins"));output.put("blessings",run.get("blessings"));
      output.put("changedPlayers",mode.startsWith("seed")?1:0);
      output.put("clientGameplayVerified",false);System.out.println(new Gson().toJson(output));
    } finally {server.shutdownNow();}
  }
}
