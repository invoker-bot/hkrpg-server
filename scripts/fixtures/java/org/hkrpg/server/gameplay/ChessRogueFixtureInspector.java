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
public final class ChessRogueFixtureInspector {
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
  public static void main(String[] args) throws Exception {
    if (args.length==1 && args[0].equals("--verify-storage-codecs")) { verifyStorageCodecs(); return; }
    var root=Path.of("").toRealPath(); var directory=Path.of(args[0]).toRealPath(); var input=Path.of(args[1]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && directory.getFileName().toString().startsWith("chess-rogue-native-")
        && input.startsWith(directory),"Unowned normally stopped Chess fixture");
    var marker=JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("chessRogueFixture").getAsBoolean()
        && text(marker,"sourceCommit").equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"),"Missing original-source Chess fixture marker");
    var expected=JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid=number(expected,"uid"); String mode=text(expected,"mode");
    require(uid==10001 && Set.of("sequence","resume","restored").contains(mode),"Wrong owned Chess inspection phase");
    var initialBaseline=JsonParser.parseString(Files.readString(directory.resolve("fixture-baseline.json"))).getAsJsonObject();
    var baseline=JsonParser.parseString(Files.readString(directory.resolve("fixture-observed-start.json"))).getAsJsonObject();
    require(number(initialBaseline,"uid")==uid && number(baseline,"uid")==uid
        && text(initialBaseline,"phase").equals("before-qualification") && text(baseline,"phase").equals("start")
        && number(baseline,"battleId")==0 && number(baseline,"chessCoins")==0 && number(baseline,"actualWinReceiptCount")==0
        && number(baseline,"credits")-number(initialBaseline,"credits")==5000
        && number(baseline,"jades")==number(initialBaseline,"jades")
        && baseline.get("livePlayerUnchangedByObservation").getAsBoolean()
        && baseline.get("storedPlayerUnchangedByObservation").getAsBoolean(),
        "Missing observed actual Start before first battle and original qualification reward baseline");
    var observations=new JsonArray();
    for (int cell=201;cell<=204;cell++) {
      var observed=JsonParser.parseString(Files.readString(directory.resolve("fixture-observed-win"+cell+".json"))).getAsJsonObject();
      observations.add(observed);
      var delta=observed.getAsJsonObject("deltaFromObservedBaseline");
      require(number(observed,"uid")==uid && number(observed,"nonce")==number(baseline,"nonce")
          && text(observed,"phase").equals("win"+cell) && text(observed,"runId").equals(text(baseline,"runId"))
          && number(observed,"battleId")>0 && number(observed,"chessCoins")==100*(cell-200)
          && number(observed,"actualWinReceiptCount")==cell-200 && number(delta,"credits")==0 && number(delta,"jades")==0
          && observed.get("livePlayerUnchangedByObservation").getAsBoolean()
          && observed.get("storedPlayerUnchangedByObservation").getAsBoolean(),"Missing owned actual per-battle currency delta: "+cell);
      for (String field:List.of("classic","daily","ordinaryInventory","ordinaryLineups","ordinaryTeams","ordinaryAvatars"))
        require(observed.get(field).equals(baseline.get(field)) && delta.get(field+"Unchanged").getAsBoolean(),
            "Generic state changed in actual per-battle observation: "+cell+"/"+field);
    }
    var file=directory.resolve("data/database.mv"); var config=directory.resolve("config.json");
    require(Files.isRegularFile(file),"Normally stopped owned H2 file is missing");
    String dbSHA=sha(file), configSHA=sha(config);
    var mv=new MVStore.Builder().fileName(file.toString()).readOnly().open();
    var server=new MongoServer(new H2Backend(mv)); var address=server.bind();
    JsonObject chess;
    try (var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var db=client.getDatabase("hkrpg"); var collection=db.getCollection("players"); var filter=Filters.eq("_id",uid);
      require(collection.countDocuments()==1 && collection.countDocuments(filter)==1,"Not one owned Chess Player");
      var before=Objects.requireNonNull(collection.find(filter).first());
      var avatarBefore=db.getCollection("avatars").find().into(new ArrayList<org.bson.Document>());
      var lineupBefore=db.getCollection("lineups").find().into(new ArrayList<org.bson.Document>());
      var itemsBefore=db.getCollection("items").find().into(new ArrayList<org.bson.Document>());
      var store=createStore(client,"hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,QuestState.class,Position.class,GameAvatar.class,PlayerLineup.class,GameItem.class);
      var first=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      var second=store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first();
      require(first!=null && second!=null && first!=second && GSON.toJsonTree(first.getGameplay()).equals(GSON.toJsonTree(second.getGameplay())),
          "Two independent actual Player document restorations differ");
      var state=object(first.getGameplay()); chess=state.getAsJsonObject("chessRogue");
      require(chess!=null && number(chess,"schema")==1 && uuid(text(chess,"runId"))
          && text(chess,"sourceCommit").equals("724b139d8c9c32d12552eb95745a4fee72bfe48b")
          && text(chess,"routeRule").equals("hkrpg-chess-guide-original-merchant-v1")
          && chess.get("inside").getAsBoolean() && !chess.get("failed").getAsBoolean()
          && number(chess,"area")==101 && number(chess,"aeon")==6 && number(chess,"layerIndex")==1
          && number(chess,"currentCell")==201 && number(chess,"enteredCell")==201
          && number(chess,"coins")==200 && number(chess,"coinsPerVictory")==100
          && number(chess,"miraclePrice")==200 && number(chess,"stockLimit")==3 && number(chess,"monsterLevel")==53,
          "Independent original-source Chess identity, position or configured wallet differs");
      require(chess.getAsJsonArray("roster").equals(array(List.of(8001))) && number(chess,"leader")==0
          && number(chess,"mp")==5 && chess.getAsJsonObject("vitals").keySet().equals(Set.of("8001")),
          "Chess roster or exploration points were seeded or changed");
      var vital=chess.getAsJsonObject("vitals").getAsJsonObject("8001");
      require(number(vital,"hp")==5000 && number(vital,"sp")==2500,
          "The actual controlled native report did not persist in the independent Chess HP/SP document");
      var victories=chess.getAsJsonObject("victories");
      require(victories.keySet().equals(Set.of("0:201","0:202","0:203","0:204")),"Missing actual four source-cell WIN receipts");
      var settlements=new TreeSet<String>(); var battleIds=new TreeSet<Integer>();
      for (int cell=201;cell<=204;cell++) {
        var receipt=victories.getAsJsonObject("0:"+cell); boolean boss=cell==204;
        require(text(receipt,"runId").equals(text(chess,"runId")) && number(receipt,"layer")==0 && number(receipt,"cell")==cell
            && number(receipt,"stage")== (boss?80139011:80010011) && number(receipt,"group")== (boss?32:25)
            && number(receipt,"instance")== (boss?200001:200005) && number(receipt,"battle")>0
            && uuid(text(receipt,"settlementId")) && settlements.add(text(receipt,"settlementId")) && battleIds.add(number(receipt,"battle")),
            "Actual single-source combat receipt lost its independent identity: "+cell);
        require(number(receipt,"battle")==number(observations.get(cell-201).getAsJsonObject(),"battleId"),
            "Per-battle read-only delta was not bound to the actual persisted battle: "+cell);
        if (mode.equals("sequence")) {
          int observedCell=cell;
          var encounter=expected.getAsJsonArray("encounters").asList().stream().map(JsonElement::getAsJsonObject)
              .filter(row->number(row,"cell")==observedCell).findFirst().orElseThrow();
          require(number(receipt,"battle")==number(encounter,"battleId") && number(receipt,"stage")==number(encounter,"stage")
              && number(receipt,"group")==number(encounter,"group") && number(receipt,"instance")==number(encounter,"instance"),
              "Persisted WIN receipt differs from the actual KCP battle: "+cell);
        }
      }
      var shop=chess.getAsJsonObject("shop"); var miracles=chess.getAsJsonObject("miracles");
      require(shop!=null && text(shop,"runId").equals(text(chess,"runId"))
          && text(shop,"sourceCommit").equals(text(chess,"sourceCommit")) && text(shop,"rule").equals(text(chess,"routeRule"))
          && number(shop,"layer")==1 && number(shop,"cell")==201 && number(shop,"room")==1310601
          && number(shop,"group")==35 && number(shop,"instance")==400001 && number(shop,"npc")==1013 && number(shop,"price")==200
          && shop.getAsJsonArray("ids").equals(array(List.of(4))) && shop.getAsJsonArray("purchased").equals(array(List.of(4)))
          && miracles.keySet().equals(Set.of("4")),"Owned one-unit G35 miracle4 shop receipt changed");
      var miracle=miracles.getAsJsonObject("4");
      require(number(miracle,"id")==4 && uuid(text(miracle,"acquisitionId")) && number(miracle,"totalCount")==1
          && number(miracle,"useCount")==0 && miracle.getAsJsonArray("effectParameters").equals(array(List.of(1.0))),
          "Single acquired original miracle counters or acquisition UUID changed");
      var actualIsolation=new LinkedHashMap<String,Object>();
      actualIsolation.put("actualClassic",state.get("rogue")); actualIsolation.put("baselineClassic",baseline.get("classic"));
      actualIsolation.put("actualOrdinaryLineupManager",object(first.getLineupManager()));
      actualIsolation.put("baselineOrdinaryLineupManager",baseline.get("ordinaryLineups"));
      actualIsolation.put("actualCredits",first.getScoin()); actualIsolation.put("baselineCredits",number(baseline,"credits"));
      actualIsolation.put("actualJades",first.getHcoin()); actualIsolation.put("baselineJades",number(baseline,"jades"));
      Files.writeString(directory.resolve("observed-player-before-isolation-"+mode+".json"),before.toJson(),StandardOpenOption.CREATE_NEW);
      System.out.println("CHESS_ACTUAL_ISOLATION="+GSON.toJson(actualIsolation)); System.out.flush();
      require(state.get("rogue").equals(baseline.get("classic"))
          && object(first.getLineupManager()).equals(baseline.get("ordinaryLineups"))
          && first.getScoin()==number(baseline,"credits") && first.getHcoin()==number(baseline,"jades"),
          "Chess borrowed classic progression, ordinary MP or ordinary currency");
      var daily=new JsonObject();
      for (var field:state.entrySet()) if (field.getKey().startsWith("daily")) daily.add(field.getKey(),field.getValue().deepCopy());
      require(daily.equals(baseline.get("daily")),"Chess battle settlement mutated generic daily state");
      var items=store.find(GameItem.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid",uid)).iterator().toList();
      var sortedItems=new JsonArray(); items.stream().sorted(Comparator.comparingInt(GameItem::getItemId)).forEach(item->sortedItems.add(GSON.toJsonTree(item)));
      require(sortedItems.equals(baseline.get("ordinaryInventory")),"Chess battle settlement manufactured generic drops or inventory grants");
      var qualification=first.getGameplay().getMainMissions().get(8013104);
      var qualificationReward=state.getAsJsonObject("rewards").getAsJsonObject("mission:8013104");
      require(qualification!=null && qualification.getPhase()==QuestState.Phase.CLOSED && qualification.getProgress()==1
          && qualification.getFinishTime()>0 && object(qualification).equals(baseline.get("qualification"))
          && qualificationReward!=null && number(qualificationReward,"rewardId")==18013104
          && qualificationReward.get("granted").getAsBoolean() && qualificationReward.equals(baseline.get("qualificationRewardReceipt")),
          "Observed original eligibility auto reward was changed by Chess battle/shop/restart");
      require(first.getPlaneId()==80201 && first.getFloorId()==80201001 && first.getEntryId()==8020101,
          "Actual source Respite scene was not persisted");
      var returnTo=chess.getAsJsonObject("returnTo");
      require(number(returnTo,"plane")==20001 && number(returnTo,"floor")==20001001 && number(returnTo,"entry")==2000101,
          "Original ordinary return location disappeared");
      var persistedTeams=store.find(PlayerLineup.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid",uid)).iterator().toList();
      require(persistedTeams.size()==baseline.getAsJsonArray("ordinaryTeams").size(),"Chess created/deleted a real persisted ordinary team");
      for (var raw:baseline.getAsJsonArray("ordinaryTeams")) {
        var original=raw.getAsJsonObject(); int index=number(original,"index");
        var team=store.find(PlayerLineup.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid",uid),
            dev.morphia.query.filters.Filters.eq("index",index)).first();
        require(team!=null && object(team).equals(original),"Ordinary team changed during Chess: "+index);
      }
      var avatars=store.find(GameAvatar.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid",uid)).iterator().toList();
      require(avatars.size()==baseline.getAsJsonArray("ordinaryAvatars").size(),"Chess created an extra formal avatar");
      for (var raw:baseline.getAsJsonArray("ordinaryAvatars")) {
        var original=raw.getAsJsonObject(); int id=number(original,"avatarId");
        var actual=avatars.stream().filter(avatar->avatar.getAvatarId()==id).findFirst().orElseThrow(); var restored=object(actual);
        for (String bar:List.of("currentHp","currentSp","extraLineupHp","extraLineupSp"))
          require(restored.get(bar).equals(original.get(bar)),"Ordinary/generic extra avatar bar changed: "+id+"/"+bar);
      }
      if (expected.has("expectedChessDocument")) require(chess.equals(expected.get("expectedChessDocument")),"Run, four settlement UUIDs, acquired UUID or shop changed across normally restarted H2 sessions");
      require(before.equals(collection.find(filter).first()) && avatarBefore.equals(db.getCollection("avatars").find().into(new ArrayList<org.bson.Document>()))
          && lineupBefore.equals(db.getCollection("lineups").find().into(new ArrayList<org.bson.Document>()))
          && itemsBefore.equals(db.getCollection("items").find().into(new ArrayList<org.bson.Document>())),"Read-only inspector changed Player/avatar/team/item collections");
      Files.writeString(directory.resolve("saved-player-"+mode+".json"),before.toJson(),StandardOpenOption.CREATE_NEW);
    } finally { server.shutdownNow(); if (!mv.isClosed()) mv.close(); }
    require(dbSHA.equals(sha(file)) && configSHA.equals(sha(config)),"Physically read-only inspection changed H2/config bytes");
    System.out.println(GSON.toJson(Map.of("passed",true,"uid",uid,"mode",mode,"chessDocument",chess,
        "independentMorphiaReads",2,"changedPlayers",0,"databaseSHA256",dbSHA,"databaseFileBytesUnchanged",true,"mvStoreReadOnly",true)));
  }
}
