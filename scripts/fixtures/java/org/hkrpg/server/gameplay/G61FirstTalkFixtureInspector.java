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
import java.util.*;
import org.h2.mvstore.MVStore;

/** Two independent persisted Player reads, with all original NPC observation identity fields intact. */
public final class G61FirstTalkFixtureInspector {
  private static void require(boolean value,String why) { if(!value)throw new IllegalStateException(why); }
  private static String sha(Path path)throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  public static void main(String[] args)throws Exception {
    if(args.length==2 && args[0].equals("--source-identity")) {
      var root=Path.of("").toRealPath();var source=Path.of(args[1]).toRealPath();
      require(source.startsWith(root.resolve("resources/4.6")) || source.startsWith(root.resolve("build/smoke")),"Unowned read-only source identity root");
      String intro="Config/Level/NPC/W0/NPC_DefaultStory_500001301.json";
      String main="Config/Level/NPCDialogue/P20002/F20002001_G61/DialogueMain_F20002001_G61_N400001.json";
      String pack="Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json";
      String group="Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G61.json";
      var values=new HashMap<String,JsonElement>();var pins=new HashMap<String,String>();
      for(String name:List.of(intro,main,pack,group)) {
        var file=source.resolve(name).toRealPath();require(file.startsWith(source),"Source identity path escaped its root");
        var value=JsonParser.parseString(Files.readString(file));values.put(name,value);pins.put(name,TimelineActivityRules.fingerprint(value));
      }
      System.out.println(new Gson().toJson(Map.of("source","4.6:G61:N10206:90000103:"+pins.get(intro)+":"+pins.get(main)+":"+pins.get(pack)+":"+pins.get(group),
          "npcSource",TimelineActivityRules.fingerprint(values.get(group).getAsJsonObject().getAsJsonArray("NPCList").get(0)),
          "groupSource",pins.get(group),"scriptSource",pins.get(intro))));return;
    }
    var root=Path.of("").toRealPath();var dir=Path.of(args[0]).toRealPath();var input=Path.of(args[1]).toRealPath();
    require(dir.startsWith(root.resolve("build/smoke"))
        && dir.getFileName().toString().startsWith("daily-message-g61-native-") && input.startsWith(dir),"Unowned stopped G61 fixture");
    var control=JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid=control.get("uid").getAsInt(),credit=control.get("credit").getAsInt();
    require(uid>0 && Set.of(0,5000,10000).contains(credit),"Invalid stopped G61 inspection control");
    var marker=JsonParser.parseString(Files.readString(dir.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("g61NpcFirstTalkFixture").getAsBoolean(),"Wrong G61 fixture marker");
    var config=dir.resolve("config.json");var file=dir.resolve("data/database.mv");
    require(Files.isRegularFile(file),"Normal stopped H2 database missing");
    String configSha=sha(config),beforeSha=sha(file);
    var proof=new TreeMap<String,Object>();
    var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();
    var server=new MongoServer(new H2Backend(mv));var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var players=client.getDatabase("hkrpg").getCollection("players");var filter=Filters.eq("_id",uid);
      require(players.countDocuments()==1 && players.countDocuments(filter)==1,"Not one isolated G61 player");
      var before=Objects.requireNonNull(players.find(filter).first());
      var store=Morphia.createDatastore(client,"hkrpg");store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,QuestState.class,Position.class);
      var first=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      var second=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(first!=null && second!=null && first!=second,"Two independent actual G61 Player reads failed");
      var gson=new Gson();var g=first.getGameplay();
      var json=gson.toJsonTree(g).getAsJsonObject();
      require(gson.toJsonTree(second.getGameplay()).equals(json) && first.getScoin()==second.getScoin(),"Two independent G61 Player reads differ");
      require(first.getUid()==uid && first.getScoin()==credit,"Stored UID or credit differs");
      var state=json.getAsJsonObject("npcFirstTalks");
      require(state!=null && state.get("version").getAsInt()==1,"NPC observation domain did not survive actual Morphia");
      var records=state.getAsJsonObject("records");
      require(records.size()==1 && records.has("90000103"),"Lost or additional NPC first meeting");
      var record=records.getAsJsonObject("90000103");
      var identity=Map.of("uid",uid,"performance",90000103,"plane",20002,"floor",20002001,"entry",200020102,
          "dimension",0,"story",0,"group",61,"instance",400001,"npc",10206);
      for(var part:identity.entrySet())require(record.get(part.getKey()).getAsInt()==part.getValue(),"Stored NPC identity differs: "+part.getKey());
      require(record.get("entity").getAsInt()>0 && record.get("runtime").getAsLong()>0
          && record.get("at").getAsLong()>0 && record.get("prerequisiteFinish").getAsLong()>0
          && record.get("prerequisiteFinish").getAsLong()<=record.get("at").getAsLong(),"Stored actual NPC runtime/timestamps invalid");
      require(g.getMainMissions().get(1000401).getFinishTime()==record.get("prerequisiteFinish").getAsLong(),
          "NPC first meeting lost the actual original prerequisite finish identity");
      require(UUID.fromString(record.get("event").getAsString()).toString().equals(record.get("event").getAsString()),"Stored first meeting event is invalid");
      for(String key:List.of("source","npcSource","groupSource","scriptSource"))
        require(record.get(key).getAsString().equals(control.getAsJsonObject("sourceIdentity").get(key).getAsString()),
            "Stored fixed original NPC source identity differs: "+key);
      if(control.has("expectedObservation"))require(record.equals(control.get("expectedObservation")),"Normal restart or daily period changed original global first meeting identity");
      if(control.has("actorWireIdentity")) {
        var actor=control.getAsJsonObject("actorWireIdentity");
        require(actor.get("entity").equals(record.get("entity")),"Stored NPC does not match the actually observed KCP entity");
      }
      require(!g.getFirstTalkReports().containsKey(90000103) && !g.getMainMissions().containsKey(90000103)
          && !g.getSubMissions().containsKey(90000103) && !g.getPhoneSections().containsKey(1211401),"NPC intro forged a mission goal or reused global Phone history");
      if(control.get("introOnly").getAsBoolean()) {
        require(!g.getMainMissions().containsKey(3000602) && !g.getSubMissions().containsKey(300060201)
            && !g.getSubMissions().containsKey(300060202) && !g.getRewards().containsKey("mission:3000602"),
            "Standalone first meeting completed or invented a commission objective");
      }
      require(before.equals(players.find(filter).first()) && players.countDocuments()==1,"Inspector changed persisted Player documents");
      proof.put("observation",record);proof.put("credit",first.getScoin());proof.put("uid",uid);
      proof.put("independentMorphiaReadCount",2);proof.put("changedPlayerDocumentCount",0);
    } finally {server.shutdownNow();if(!mv.isClosed())mv.close();}
    require(beforeSha.equals(sha(file)) && configSha.equals(sha(config)),"Read-only stopped H2/config bytes changed");
    proof.put("databaseSHA256",beforeSha);proof.put("databaseFileBytesUnchanged",true);proof.put("mvStoreReadOnly",true);
    proof.put("passed",true);System.out.println(new Gson().toJson(proof));
  }
}
