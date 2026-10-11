package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.ChessRogueRoutes;
import org.hkrpg.server.gameplay.ChessRogueState;

/** Console-only initial Query observations and separately attributed original qualification refresh. */
@Command(label="chessqueryfixture",requireTarget=false,permission="fixture.console-only",
    desc="仅独占隔离首次Query：完整只读观察与原8013104资格结算")
public final class ChessRogueInitialQueryFixtureCommand implements CommandHandler {
  private static final Gson GSON=new Gson();
  private static final Set<String> MODES=Set.of("unqualified","unqualified-restart","qualified","qualified-restart");
  private static void require(boolean ok,String why) { if (!ok) throw new IllegalStateException(why); }
  private static JsonObject json(Object value) { return GSON.toJsonTree(value).getAsJsonObject(); }
  private static JsonObject read(Path path) throws java.io.IOException {
    return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
  }
  private static void write(Path path,JsonObject value) throws java.io.IOException {
    Files.writeString(path,GSON.toJson(value),StandardOpenOption.CREATE_NEW);
  }
  private static JsonObject bson(org.bson.Document doc) { return JsonParser.parseString(doc.toJson()).getAsJsonObject(); }
  private static JsonArray documents(String collection) {
    var rows=LunarCore.getGameDatabase().getDatabase().getCollection(collection).find().into(new ArrayList<org.bson.Document>());
    rows.sort(Comparator.comparing(row->row.get("_id").toString()));
    var result=new JsonArray(); rows.forEach(row->result.add(bson(row))); return result;
  }
  private static JsonObject snapshot(Player player) {
    var before=json(player);
    var stored=LunarCore.getGameDatabase().getDatabase().getCollection("players")
        .find(com.mongodb.client.model.Filters.eq("_id",player.getUid())).first();
    require(stored!=null,"Actual stored player is required for a read-only Query observation");
    var result=new JsonObject(); result.add("livePlayer",before);
    result.add("storedPlayerDocument",bson(stored)); result.add("gameplay",json(player.getGameplay()));
    result.add("ordinaryInventory",GSON.toJsonTree(player.getInventory().getItems()));
    result.add("ordinaryLineups",GSON.toJsonTree(player.getLineupManager()));
    result.add("ordinaryTeams",GSON.toJsonTree(player.getLineupManager().getLineups()));
    result.add("ordinaryExtraTeams",GSON.toJsonTree(player.getLineupManager().getExtraLineups()));
    result.addProperty("currentExtraIndex",player.getLineupManager().getCurrentExtraIndex());
    result.add("ordinaryAvatars",GSON.toJsonTree(player.getAvatars().getAvatars()));
    result.add("storedAvatarDocuments",documents("avatars")); result.add("storedTeamDocuments",documents("lineups"));
    result.add("storedItemDocuments",documents("items"));
    result.add("storedExtraTeamDocuments",documents("lineupsExtra"));
    result.addProperty("credits",player.getScoin()); result.addProperty("jades",player.getHcoin());
    require(before.equals(json(player)),"Snapshot changed the actual live Player");
    require(result.get("storedPlayerDocument").equals(bson(LunarCore.getGameDatabase().getDatabase()
        .getCollection("players").find(com.mongodb.client.model.Filters.eq("_id",player.getUid())).first())),
        "Snapshot changed the actual stored Player");
    return result;
  }
  private static void noRun(Player player) {
    var run=player.getGameplay().getChessRogue();
    require(!run.active() && !run.isInside() && json(run).equals(json(new ChessRogueState())),
        "Query fixture must not allocate, replace or preload any Chess run/roster/coin/receipt/miracle");
    require(ChessRogueRoutes.available(),"Complete original source graph and actual loaded floor are required");
  }
  private static void qualification(Player player,Path cwd,int nonce) throws java.io.IOException {
    noRun(player); require(!player.getGameplay().getMainMissions().containsKey(8013104),"Original qualification history already exists");
    var main=GameData.getMainMissionExcelByID(8013104); var reward=GameData.getRewardExcelMap().get(18013104);
    require(main!=null && main.getRewardID()==18013104 && reward!=null && reward.getRewards().size()==1
        && reward.getRewards().get(2)==5000,"Actual original qualification/reward rows are not loaded");
    var proof=read(cwd.resolve("qualification-source-proof.json"));
    require(proof.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b")
        && proof.getAsJsonArray("completeOriginalRows").size()==2,"Whole source qualification proof is required");
    var before=snapshot(player); var history=new QuestState(8013104);
    require(history.advance(1,1,Instant.now().getEpochSecond()) && history.getPhase()==QuestState.Phase.FINISHED,
        "Explicit isolated original qualification history did not finish");
    player.getGameplay().getMainMissions().put(8013104,history); player.save();
    var seeded=snapshot(player); player.getMissionManager().refresh(); var settled=snapshot(player); noRun(player);
    var receipt=player.getGameplay().getRewards().get("mission:8013104");
    require(history.getPhase()==QuestState.Phase.CLOSED && receipt!=null && receipt.getRewardId()==18013104 && receipt.isGranted(),
        "Production refresh did not settle the actual original18013104 reward");
    require(settled.get("credits").getAsInt()-before.get("credits").getAsInt()==5000
        && settled.get("jades").equals(before.get("jades"))
        && seeded.get("credits").equals(before.get("credits")) && seeded.get("jades").equals(before.get("jades")),
        "The only wallet increment must be source item2/5000 from production qualification refresh");
    for (String key:List.of("ordinaryInventory","ordinaryLineups","ordinaryTeams","ordinaryExtraTeams","currentExtraIndex",
        "ordinaryAvatars","storedAvatarDocuments","storedTeamDocuments","storedExtraTeamDocuments","storedItemDocuments"))
      require(before.get(key).equals(seeded.get(key)) && before.get(key).equals(settled.get(key)),"Qualification changed unrelated items/team/vitals: "+key);
    var beforeGame=before.getAsJsonObject("gameplay"); var afterGame=settled.getAsJsonObject("gameplay");
    var rewardBefore=beforeGame.getAsJsonObject("rewards"); var rewardAfter=afterGame.getAsJsonObject("rewards");
    var rewardKeys=new TreeSet<>(rewardBefore.keySet()); rewardKeys.add("mission:8013104");
    require(rewardAfter.keySet().equals(rewardKeys),"Qualification generated another unaccounted reward receipt");
    for (String key:rewardBefore.keySet()) require(rewardBefore.get(key).equals(rewardAfter.get(key)),"Qualification changed another existing receipt");
    var changed=new JsonObject(); var keys=new TreeSet<>(beforeGame.keySet()); keys.addAll(afterGame.keySet());
    for (String key:keys) if (!Objects.equals(beforeGame.get(key),afterGame.get(key))) {
      var delta=new JsonObject(); delta.add("before",beforeGame.get(key)); delta.add("after",afterGame.get(key)); changed.add(key,delta);
    }
    // Complete ordinary task progression is recorded, not silently relabeled as a Query side effect.
    var result=new JsonObject(); result.addProperty("uid",player.getUid()); result.addProperty("nonce",nonce);
    result.add("sourceProof",proof); result.add("before",before); result.add("seededFinished",seeded); result.add("productionSettled",settled);
    result.add("completeGameplayDelta",changed); result.addProperty("sourceHistorySeeded",8013104);
    result.addProperty("productionQualificationReward",18013104); result.addProperty("creditDelta",5000);
    result.addProperty("firstReward109011Paid",false); result.addProperty("qualificationStoryVerified",false);
    write(cwd.resolve("initial-query-qualification.json"),result);
  }
  @Override public void execute(CommandArgs args) {
    if (args.getSender()!=null || args.getList().size()!=3) return;
    int uid=Integer.parseInt(args.getList().get(0)),nonce=Integer.parseInt(args.getList().get(2));
    String operation=args.getList().get(1); require(uid==10001 && nonce>0,"Wrong owned Query account/nonce");
    try {
      var cwd=Path.of("").toRealPath(); var marker=read(cwd.resolve("resources-fixture/resource-manifest.json"));
      require(cwd.getFileName().toString().startsWith("chess-rogue-native-initial-query-")
          && marker.get("initialQueryFixture").getAsBoolean() && marker.get("testFixture").getAsBoolean()
          && marker.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"),"Unowned first Query directory");
      var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid); require(player!=null,"Owned Query player is not online");
      synchronized (player) {
        if (operation.equals("qualify")) qualification(player,cwd,nonce);
        else {
          require(operation.startsWith("before-") || operation.startsWith("after-"),"Unsupported initial Query observation");
          boolean before=operation.startsWith("before-"); String mode=operation.substring(before?7:6);
          require(MODES.contains(mode),"Unknown initial Query observation phase"); noRun(player);
          var state=player.getGameplay().getMainMissions().get(8013104); boolean qualified=mode.startsWith("qualified");
          require(qualified ? state!=null && state.getPhase()==QuestState.Phase.CLOSED : state==null,"Original eligibility does not match this actual Query phase");
          var observed=snapshot(player);
          if (!before) require(observed.equals(read(cwd.resolve("initial-query-before-"+mode+".json"))),
              "Actual Query changed live/stored Player, wallets, items, team, progress or run allocation");
          write(cwd.resolve("initial-query-"+operation+".json"),observed);
        }
      }
      System.out.println("HKRPG_CHESS_INITIAL_QUERY_ACK:"+uid+":"+operation+":"+nonce); System.out.flush();
    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
  }
}
