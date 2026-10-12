package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import dev.morphia.Datastore;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.h2.mvstore.MVStore;

/** Two independent actual Player reads from physically read-only normally stopped owned H2. */
public final class MaterialSubmitInspector {
  private static final Gson GSON=new Gson();
  private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
  private static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
  private static JsonObject object(Object value){return GSON.toJsonTree(value).getAsJsonObject();}
  private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
  private static JsonObject bson(org.bson.Document value){return JsonParser.parseString(value.toJson()).getAsJsonObject();}
  private static JsonArray documents(com.mongodb.client.MongoDatabase db,String name){
    var rows=db.getCollection(name).find().into(new ArrayList<org.bson.Document>());rows.sort(Comparator.comparing(row->row.get("_id").toString()));var out=new JsonArray();rows.forEach(row->out.add(bson(row)));return out;
  }
  private static Datastore store(MongoClient client,String name){
    var providers=org.bson.codecs.configuration.CodecRegistries.fromCodecs(new emu.lunarcore.database.codecs.IntSetCodec(),new emu.lunarcore.database.codecs.IntListCodec(),new emu.lunarcore.database.codecs.Int2IntMapCodec());
    return Morphia.createDatastore(client,name,dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(providers).build());
  }
  private static void codecs(){
    var server=new MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")){
      var datastore=store(client,"material_codec_control");datastore.getMapper().map(Player.class);var p=GSON.fromJson("{\"uid\":-733,\"accountUid\":\"-733\"}",Player.class);datastore.save(p);
      var collection=client.getDatabase("material_codec_control").getCollection("players");var original=collection.find().first();
      var first=datastore.find(Player.class).first();var second=datastore.find(Player.class).first();
      require(first!=null && second!=null && first!=second && object(first.getGameplay()).equals(object(second.getGameplay())) && first.getGameplay().getMaterialSubmission().receipts().isEmpty() && original.equals(collection.find().first()),"Actual Player codec control could not restore two independent records without writes");
      System.out.println(GSON.toJson(Map.of("passed",true,"nativeExecuted",false,"actualStoredPlayerReads",2,"storedMaterialSubmissionEmpty",true)));
    }finally{server.shutdownNow();}
  }
  public static void main(String[] args)throws Exception{
    if(args.length==1 && args[0].equals("--verify-storage-codecs")){codecs();return;}
    var root=Path.of("").toRealPath();var directory=Path.of(args[0]).toRealPath();var input=Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && directory.getFileName().toString().startsWith("material-submit-") && input.startsWith(directory),"Unowned normally stopped material H2 fixture");
    var marker=read(directory.resolve("resources-fixture/resource-manifest.json"));require(marker.get("testFixture").getAsBoolean() && marker.get("materialSubmitFixture").getAsBoolean() && marker.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"),"Complete original material source marker missing");
    var control=read(input);int uid=control.get("uid").getAsInt();String mode=control.get("mode").getAsString();require(uid==10001 && Set.of("initial","restart").contains(mode),"Wrong owned material UID/phase");
    var observed=read(directory.resolve(mode.equals("initial")?"material-after-final-query.json":"material-after-complete-query.json"));
    var definitions=MaterialSubmitDefinitions.load(directory.resolve("resources-fixture"));require(definitions!=null,"Full material source lease missing from physical stopped fixture");
    var file=directory.resolve("data/database.mv");var config=directory.resolve("config.json");String dbSHA=sha(file),configSHA=sha(config);var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();var server=new MongoServer(new H2Backend(mv));var address=server.bind();JsonObject gameplay;
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")){
      var db=client.getDatabase("hkrpg");var players=db.getCollection("players");var filter=Filters.eq("_id",uid);require(players.countDocuments()==1 && players.countDocuments(filter)==1,"Only one actual owned Player permitted");
      var before=Objects.requireNonNull(players.find(filter).first());var avatars=documents(db,"avatars");var teams=documents(db,"lineups");var extra=documents(db,"lineupsExtra");var items=documents(db,"items");
      require(avatars.equals(observed.get("storedAvatarDocuments")) && teams.equals(observed.get("storedTeamDocuments")) && extra.equals(observed.get("storedExtraTeamDocuments")) && items.equals(observed.get("storedItemDocuments")),"Normal stop changed actual ordinary or material item documents");
      var datastore=store(client,"hkrpg");datastore.getMapper().mapPackage("org.hkrpg.server.gameplay");datastore.getMapper().map(Player.class,QuestState.class,Position.class,GameAvatar.class,PlayerLineup.class,GameItem.class);
      var first=datastore.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();var second=datastore.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(first!=null && second!=null && first!=second && object(first.getGameplay()).equals(object(second.getGameplay())),"Independent actual Player restorations differ");gameplay=object(first.getGameplay());require(gameplay.equals(observed.get("gameplay")),"Actual H2 did not retain complete observed gameplay");
      var state=first.getGameplay().getMaterialSubmission();require(state.valid(definitions) && state.receipts().size()==(mode.equals("initial")?1:7),"Actual saved complete material receipts missing");
      for(int id=301;id<=(mode.equals("initial")?301:307);id++){require(state.receipts().get(id).submitted(),"Actual submitted receipt missing");var paid=first.getGameplay().getRewards().get(MaterialSubmitManager.rewardKey(id));require(paid!=null && paid.getRewardId()==definitions.rows().get(id).reward() && paid.isGranted(),"Actual paid source receipt missing");}
      var expected=observed.getAsJsonObject("actualMaterials");var counts=new JsonObject();for(String id:expected.keySet())counts.addProperty(id,0);
      for(var row:db.getCollection("items").find(Filters.eq("ownerUid",uid))){var id=row.get("itemId");var count=row.get("count");if(id instanceof Number n && expected.has(Integer.toString(n.intValue()))){require(count instanceof Number && counts.get(Integer.toString(n.intValue())).getAsInt()==0,"Duplicate or invalid actual material document");counts.addProperty(Integer.toString(n.intValue()),((Number)count).intValue());}}
      require(counts.equals(expected),"Actual separate item documents do not match source debits and rewards");
      if(mode.equals("restart"))require(counts.get("180005").getAsInt()==0 && db.getCollection("items").countDocuments(Filters.and(Filters.eq("ownerUid",uid),Filters.eq("itemId",180005)))==0,"Depleted original shared material was not actually deleted");
      require(first.getScoin()==observed.get("credits").getAsInt() && first.getHcoin()==observed.get("jades").getAsInt(),"Actual source wallet reward not stored");require(datastore.find(PlayerLineup.class).iterator().toList().stream().anyMatch(p->p.getOwnerUid()==uid && !p.getAvatars().isEmpty()),"Actual fastutil ordinary lineup unavailable");
      require(before.equals(players.find(filter).first()) && avatars.equals(documents(db,"avatars")) && teams.equals(documents(db,"lineups")) && extra.equals(documents(db,"lineupsExtra")) && items.equals(documents(db,"items")),"Readonly actual H2 inspection wrote BSON");
    }finally{server.shutdownNow();}
    require(dbSHA.equals(sha(file)) && configSHA.equals(sha(config)),"Physically read-only H2 changed database/config bytes");
    var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("uid",uid);out.put("mode",mode);out.put("gameplay",gameplay);out.put("actualPlayerDocumentReads",2);out.put("physicallyReadOnly",true);out.put("databaseSHA256",dbSHA);out.put("configSHA256",configSHA);out.put("expectedMaterials",observed.get("actualMaterials"));out.put("credits",observed.get("credits"));out.put("jades",observed.get("jades"));out.put("actualItemDocuments",observed.get("storedItemDocuments"));out.put("ordinaryAvatarDocuments",observed.get("storedAvatarDocuments"));out.put("ordinaryTeamDocuments",observed.get("storedTeamDocuments"));out.put("ordinaryExtraTeamDocuments",observed.get("storedExtraTeamDocuments"));out.put("clientGameplayVerified",false);out.put("crashAtomicityVerified",false);System.out.println(GSON.toJson(out));
  }
}
