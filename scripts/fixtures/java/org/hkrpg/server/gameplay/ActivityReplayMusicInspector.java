package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Independent read of exactly one owned disposable player; no update operations are exposed. */
public final class ActivityReplayMusicInspector {
  public static void main(String[] args)throws Exception{
    var input=JsonParser.parseReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).getAsJsonObject();
    var directory=Path.of(input.get("directory").getAsString()).toRealPath();
    if(!directory.getFileName().toString().startsWith("gm-music-replay-")
        ||!directory.getParent().getFileName().toString().equals("smoke")
        ||!directory.getParent().getParent().getFileName().toString().equals("build")
        ||!Files.isRegularFile(directory.resolve("activity-replay-owned-fixture")))throw new IllegalArgumentException("Not an owned replay fixture");
    int uid=input.get("uid").getAsInt();
    var marker=JsonParser.parseString(Files.readString(directory.resolve("music-replay-fixture.json"))).getAsJsonObject();
    if(!marker.get("testFixture").getAsBoolean()||marker.get("uid").getAsInt()!=uid)throw new IllegalArgumentException("Fixture owner mismatch");
    var config=JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var mongo=config.getAsJsonObject("internalMongoServer");int port=mongo.get("port").getAsInt();
    if(!mongo.get("address").getAsString().equals("127.0.0.1")||Set.of(27017,27079).contains(port)
        ||config.getAsJsonObject("httpServer").get("bindPort").getAsInt()==18080
        ||config.getAsJsonObject("gameServer").get("bindPort").getAsInt()==23311)throw new IllegalArgumentException("Refusing operator service");
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+port+"/?directConnection=true")){
      var db=client.getDatabase("hkrpg");
      if(db.getCollection("players").countDocuments()!=1||db.getCollection("accounts").countDocuments()!=1)throw new IllegalArgumentException("Not disposable player data");
      var player=db.getCollection("players").find(new Document("_id",uid)).first();if(player==null)throw new IllegalArgumentException("Player missing");
      var root=player.get("gameplay",Document.class);var replays=root.get("activityReplays",Document.class);
      var wallet=new Document();for(String key:List.of("hcoin","scoin","exp","level"))wallet.put(key,player.get(key));
      var items=new Document();
      for(int id:List.of(270003,121003,236,213,223,233,110202,241)){
        var row=db.getCollection("items").find(new Document("ownerUid",uid).append("itemId",id)).first();items.put(Integer.toString(id),row==null?0:row.getInteger("count"));
      }
      var rewards=root.get("rewards",Document.class);var legacyRewards=new Document();
      if(rewards!=null)for(var entry:rewards.entrySet())if(!entry.getKey().startsWith("activity-replay:"))legacyRewards.put(entry.getKey(),entry.getValue());
      var result=new Document("passed",true).append("uid",uid).append("wallet",wallet).append("materials",items)
          .append("legacyRhythm",root.get("rhythm")).append("legacyOfferings",root.get("offerings"))
          .append("legacyRewards",legacyRewards).append("musicRuns",replays==null?null:replays.get("musicRuns"))
          .append("lightCones",db.getCollection("items").countDocuments(new Document("ownerUid",uid).append("itemId",22003)));
      System.out.println(result.toJson());
    }
  }
}
