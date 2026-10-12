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

/** Physically read-only stopped H2, two actual Player restorations and independent explicit roster and real material documents. */
public final class RogueBlessingPersistenceInspector {
  private static final Gson GSON=new Gson();
  private static void require(boolean ok,String why) { if (!ok) throw new IllegalStateException(why); }
  private static String sha(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
  private static int number(JsonObject row,String field) { return row.get(field).getAsInt(); }
  private static String text(JsonObject row,String field) { return row.get(field).getAsString(); }
  private static boolean uuid(String value) {
    try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException bad) { return false; }
  }
  private static JsonArray array(Object value) { return GSON.toJsonTree(value).getAsJsonArray(); }
  private static JsonObject object(Object value) { return GSON.toJsonTree(value).getAsJsonObject(); }
  private static Datastore createStore(MongoClient client,String name) {
    // Match DatabaseManager: a bare Morphia mapper cannot instantiate the stored fastutil interfaces.
    var providers=org.bson.codecs.configuration.CodecRegistries.fromCodecs(
        new emu.lunarcore.database.codecs.IntSetCodec(),new emu.lunarcore.database.codecs.IntListCodec(),
        new emu.lunarcore.database.codecs.Int2IntMapCodec());
    var options=dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false)
        .codecProvider(providers).build();
    return Morphia.createDatastore(client,name,options);
  }
  private static void verifyStorageCodecs() {
    var server=new MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend()); var address=server.bind();
    try (var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var store=createStore(client,"codec_selftest"); store.getMapper().map(PlayerLineup.class);
      var original=new org.bson.Document("_id",new org.bson.types.ObjectId()).append("ownerUid",-731)
          .append("index",0).append("avatars",List.of(8001)).append("leader",0).append("name","codec-only");
      var collection=client.getDatabase("codec_selftest").getCollection("lineups"); collection.insertOne(original);
      var first=store.find(PlayerLineup.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid",-731)).first();
      var second=store.find(PlayerLineup.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid",-731)).first();
      require(first!=null && second!=null && first!=second && first.getAvatars().equals(List.of(8001))
          && object(first).equals(object(second)) && original.equals(collection.find().first()),
          "Actual PlayerLineup fastutil field could not be restored twice without changing BSON");
      System.out.println(GSON.toJson(Map.of("passed",true,"nativeExecuted",false,"actualStoredPlayerLineupReads",2,
          "storedIntListDecoded",true,"operatorOrGameplayDataUsed",false,
          "runtimeFastutilCodecs",List.of("IntSetCodec","IntListCodec","Int2IntMapCodec"))));
    } finally { server.shutdownNow(); }
  }
  private static JsonObject bson(org.bson.Document value) {
    return JsonParser.parseString(value.toJson()).getAsJsonObject();
  }
  private static JsonArray documents(com.mongodb.client.MongoDatabase db,String name) {
    var rows=db.getCollection(name).find().into(new ArrayList<org.bson.Document>());
    rows.sort(Comparator.comparing(row->row.get("_id").toString()));
    var result=new JsonArray(); rows.forEach(row->result.add(bson(row))); return result;
  }


  public static void main(String[] args)throws Exception {
    if(args.length==1&&args[0].equals("--verify-storage-codecs")){verifyStorageCodecs();return;}
    var root=Path.of("").toRealPath();var directory=Path.of(args[0]).toRealPath();var input=Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke"))&&directory.getFileName().toString().startsWith("rogue-blessing-")&&input.startsWith(directory),"Owned stopped fixture required");
    var marker=JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean()&&marker.get("rogueBlessingPersistenceFixture").getAsBoolean()&&text(marker,"sourceCommit").equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"),"Original complete source marker required");
    var control=JsonParser.parseString(Files.readString(input)).getAsJsonObject();int uid=number(control,"uid");String mode=text(control,"mode");require(uid==10001&&Set.of("initial","restart").contains(mode),"Actual owned UID/phase required");
    var observed=JsonParser.parseString(Files.readString(directory.resolve(mode.equals("initial")?"rogue-blessing-after-pending.json":"rogue-blessing-after-final-query.json"))).getAsJsonObject();
    var file=directory.resolve("data/database.mv");var config=directory.resolve("config.json");String databaseSHA=sha(file),configSHA=sha(config);
    var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();require(mv.isReadOnly(),"Physical readonly H2 required");var server=new MongoServer(new H2Backend(mv));var address=server.bind();JsonObject gameplay;
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var db=client.getDatabase("hkrpg");var players=db.getCollection("players");var filter=Filters.eq("_id",uid);require(players.countDocuments()==1,"One actual stored Player required");
      var raw=Objects.requireNonNull(players.find(filter).first());var avatars=documents(db,"avatars");var teams=documents(db,"lineups");var extras=documents(db,"lineupsExtra");var items=documents(db,"items");
      require(avatars.equals(observed.get("storedAvatarDocuments"))&&teams.equals(observed.get("storedTeamDocuments"))&&extras.equals(observed.get("storedExtraTeamDocuments"))&&items.equals(observed.get("storedItemDocuments")),"Normal stop changed actual ordinary documents");
      var store=createStore(client,"hkrpg");store.getMapper().mapPackage("org.hkrpg.server.gameplay");store.getMapper().map(Player.class,QuestState.class,Position.class,GameAvatar.class,PlayerLineup.class,GameItem.class);
      var first=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();var second=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(first!=null&&second!=null&&first!=second&&object(first.getGameplay()).equals(object(second.getGameplay())),"Two independent actual Player restorations must agree");gameplay=object(first.getGameplay());require(gameplay.equals(observed.get("gameplay")),"Stopped H2 did not retain complete last observed Gameplay");
      var run=first.getGameplay().getRogue();require(run.getAreaId()==110&&run.getMapId()==101&&run.getSiteId()==1&&run.getRoomId()==304&&run.getPhase()==GameplayState.RogueRun.Phase.EXPLORING&&run.isInside()&&run.getCleared().equals(Set.of(1))&&run.getProcessedBattles().size()==3,"Actual original first-world route/three WINs did not persist");
      require(run.getTalents().contains(11)&&run.getMiracles().isEmpty()&&run.getEventRewardLease().isEmpty(),"Fixture talent/source ordinary context changed");
      if(mode.equals("initial"))require(run.getOffers().size()==3&&run.getOfferRerolls()==1&&run.getOfferRerollCosts().equals(List.of(30))&&run.getBlessings().isEmpty()&&first.getGameplay().getRogueCollectedBuffs().isEmpty(),"Actual rerolled original-price menu did not persist");
      else require(!run.pending()&&run.getBlessings().size()==1&&run.getBlessings().values().stream().allMatch(level->level==1)&&first.getGameplay().getRogueCollectedBuffs().equals(run.getBlessings().keySet()),"Actual blessing/menu/permanent collection did not persist");
      require(first.getScoin()==number(observed,"credits")&&first.getHcoin()==number(observed,"jades"),"Normal stop changed actual ordinary wallet");
      require(raw.equals(players.find(filter).first())&&avatars.equals(documents(db,"avatars"))&&teams.equals(documents(db,"lineups"))&&extras.equals(documents(db,"lineupsExtra"))&&items.equals(documents(db,"items")),"Readonly inspection changed actual BSON");
    } finally {server.shutdownNow();}
    require(databaseSHA.equals(sha(file))&&configSHA.equals(sha(config)),"Physical readonly H2/config bytes changed");
    var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("uid",uid);out.put("mode",mode);out.put("gameplay",gameplay);out.put("actualPlayerDocumentReads",2);out.put("physicallyReadOnly",true);out.put("databaseSHA256",databaseSHA);out.put("configSHA256",configSHA);
    for(String key:List.of("credits","jades","storedAvatarDocuments","storedTeamDocuments","storedExtraTeamDocuments","storedItemDocuments"))out.put(key,observed.get(key));out.put("clientGameplayVerified",false);System.out.println(GSON.toJson(out));
  }
}
