package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Read only: exactly one owned local disposable account and its original/replay signin records. */
public final class ActivityReplaySigninInspector {
  public static void main(String[] args)throws Exception{
    var input=JsonParser.parseReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).getAsJsonObject();
    var directory=Path.of(input.get("directory").getAsString()).toRealPath();int uid=input.get("uid").getAsInt();
    if(!directory.getFileName().toString().startsWith("gm-signin-gifts-")
        ||!directory.getParent().getFileName().toString().equals("smoke")
        ||!directory.getParent().getParent().getFileName().toString().equals("build")
        ||!Files.isRegularFile(directory.resolve("activity-replay-owned-fixture")))throw new IllegalArgumentException("Not an owned gift fixture");
    var owner=JsonParser.parseString(Files.readString(directory.resolve("signin-gift-replay-fixture.json"))).getAsJsonObject();
    if(!owner.get("testFixture").getAsBoolean()||owner.get("uid").getAsInt()!=uid)throw new IllegalArgumentException("Fixture owner mismatch");
    var config=JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var mongo=config.getAsJsonObject("internalMongoServer");int port=mongo.get("port").getAsInt();
    if(!mongo.get("address").getAsString().equals("127.0.0.1")||Set.of(27017,27079).contains(port)
        ||config.getAsJsonObject("httpServer").get("bindPort").getAsInt()==18080
        ||config.getAsJsonObject("gameServer").get("bindPort").getAsInt()==23311)throw new IllegalArgumentException("Refusing operator service");
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+port+"/?directConnection=true")){
      var db=client.getDatabase("hkrpg");
      if(db.getCollection("players").countDocuments()!=1||db.getCollection("accounts").countDocuments()!=1)throw new IllegalArgumentException("Not disposable data");
      var player=db.getCollection("players").find(new Document("_id",uid)).first();if(player==null)throw new IllegalArgumentException("Player missing");
      var root=player.get("gameplay",Document.class);var replays=root.get("activityReplays",Document.class);
      var materials=new Document();for(int id:List.of(102,201,236,238,263,281,110101)){
        var item=db.getCollection("items").find(new Document("ownerUid",uid).append("itemId",id)).first();
        materials.put(Integer.toString(id),item==null?0:item.getInteger("count"));
      }
      var legacyRewards=new Document();var rewards=root.get("rewards",Document.class);
      if(rewards!=null)rewards.forEach((key,value)->{if(!key.startsWith("activity-replay:"))legacyRewards.put(key,value);});
      System.out.println(new Document("uid",uid).append("hcoin",player.get("hcoin")).append("materials",materials)
          .append("avatars",db.getCollection("avatars").find(new Document("ownerUid",uid))
              .into(new ArrayList<>()).stream().map(a->a.getInteger("avatarId")).sorted().toList())
          .append("legacyDays",root.get("activityDays")).append("legacyRewards",legacyRewards)
          .append("loginRuns",replays==null?null:replays.get("loginRuns")).toJson());
    }
  }
}
