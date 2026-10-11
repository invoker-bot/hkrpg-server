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

/** Physically read-only stopped H2, two actual Player restorations and original run receipts. */
public final class ChessRogueInitialQueryInspector {
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
  public static void main(String[] args) throws Exception {
    if (args.length==1 && args[0].equals("--verify-storage-codecs")) { verifyStorageCodecs(); return; }
    var root=Path.of("").toRealPath(); var directory=Path.of(args[0]).toRealPath(); var input=Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && directory.getFileName().toString().startsWith("chess-rogue-native-initial-query-")
        && input.startsWith(directory),"Unowned normally stopped initial Query fixture");
    var marker=JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("initialQueryFixture").getAsBoolean()
        && text(marker,"sourceCommit").equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"),"Missing original initial Query fixture marker");
    var expected=JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid=number(expected,"uid"); String mode=text(expected,"mode");
    require(uid==10001 && Set.of("unqualified","unqualified-restart","qualified","qualified-restart").contains(mode),"Wrong initial Query H2 phase");
    var observed=JsonParser.parseString(Files.readString(directory.resolve("initial-query-after-"+mode+".json"))).getAsJsonObject();
    var beforeQuery=JsonParser.parseString(Files.readString(directory.resolve("initial-query-before-"+mode+".json"))).getAsJsonObject();
    require(beforeQuery.equals(observed),"The real native Query changed its actual live/stored baseline");
    var file=directory.resolve("data/database.mv"); var config=directory.resolve("config.json");
    require(Files.isRegularFile(file),"Normally stopped owned H2 is required");
    String dbSHA=sha(file),configSHA=sha(config);
    var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();
    var server=new MongoServer(new H2Backend(mv)); var address=server.bind();
    JsonObject gameplay;
    try (var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var db=client.getDatabase("hkrpg"); var players=db.getCollection("players"); var filter=Filters.eq("_id",uid);
      require(players.countDocuments()==1 && players.countDocuments(filter)==1,"Not exactly one owned actual player");
      var playerBefore=Objects.requireNonNull(players.find(filter).first());
      var extraBefore=documents(db,"lineupsExtra");
      require(extraBefore.equals(observed.get("storedExtraTeamDocuments")),"Normal stop changed actual extra teams");
      var avatarBefore=documents(db,"avatars"); var teamBefore=documents(db,"lineups"); var itemBefore=documents(db,"items");
      require(avatarBefore.equals(observed.get("storedAvatarDocuments")) && teamBefore.equals(observed.get("storedTeamDocuments"))
          && itemBefore.equals(observed.get("storedItemDocuments")),"Normal stop changed actual formal avatar/team/item documents");
      var store=createStore(client,"hkrpg"); store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,QuestState.class,Position.class,GameAvatar.class,PlayerLineup.class,GameItem.class);
      var first=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      var second=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(first!=null && second!=null && first!=second && object(first.getGameplay()).equals(object(second.getGameplay())),
          "Two actual independent Player restorations differ");
      gameplay=object(first.getGameplay());
      require(gameplay.equals(observed.get("gameplay")),"Normally stopped H2 did not retain the exact observed gameplay document");
      var run=first.getGameplay().getChessRogue();
      require(!run.active() && !run.isInside() && object(run).equals(object(new ChessRogueState())),
          "First Query allocated or saved a run/UUID/roster/board/wallet/receipt/miracle");
      boolean qualified=mode.startsWith("qualified"); var mission=first.getGameplay().getMainMissions().get(8013104);
      require(qualified ? mission!=null && mission.getPhase()==QuestState.Phase.CLOSED && mission.getProgress()==1 : mission==null,
          "Actual original qualification changed across this same-UID normal H2 restart");
      require(first.getScoin()==number(observed,"credits") && first.getHcoin()==number(observed,"jades"),"Query or stop changed the actual formal wallet");
      var restoredAvatars=store.find(GameAvatar.class).iterator().toList();
      var restoredTeams=store.find(PlayerLineup.class).iterator().toList();
      require(!restoredAvatars.isEmpty() && restoredTeams.stream().anyMatch(team->team.getOwnerUid()==uid && !team.getAvatars().isEmpty()),
          "Actual formal avatars and fastutil teams could not be restored");
      require(extraBefore.equals(documents(db,"lineupsExtra")) && playerBefore.equals(players.find(filter).first()) && avatarBefore.equals(documents(db,"avatars"))
          && teamBefore.equals(documents(db,"lineups")) && itemBefore.equals(documents(db,"items")),"Read-only H2 inspection changed BSON");
    } finally { server.shutdownNow(); }
    require(dbSHA.equals(sha(file)) && configSHA.equals(sha(config)),"Physically read-only inspection changed database/config bytes");
    var out=new LinkedHashMap<String,Object>(); out.put("passed",true); out.put("uid",uid); out.put("mode",mode);
    out.put("qualified",mode.startsWith("qualified")); out.put("actualPlayerDocumentReads",2); out.put("physicallyReadOnly",true);
    out.put("databaseSHA256",dbSHA); out.put("configSHA256",configSHA); out.put("gameplay",gameplay);
    out.put("queryCreatedOrSavedRun",false); out.put("clientGameplayVerified",false);
    System.out.println(GSON.toJson(out));
  }
}
