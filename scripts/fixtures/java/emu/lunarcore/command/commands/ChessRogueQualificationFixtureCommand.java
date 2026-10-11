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

/** Console-only finite corrupt eligibility records, observations and separately attributed qualification refresh. */
@Command(label="chessqualfixture",requireTarget=false,permission="fixture.console-only",
    desc="仅独占隔离首次Query：完整只读观察与原8013104资格结算")
public final class ChessRogueQualificationFixtureCommand implements CommandHandler {
  private static final Gson GSON=new Gson();
  private static final List<String> OPERATIONS=List.of("seed-null","before-null","after-null",
      "seed-progress","before-progress","after-progress","qualify","before-valid","after-valid","before-start","after-start");
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
    write(cwd.resolve("qualification-integrity-legal-qualification.json"),result);
  }
  private static void seedBad(Player player,Path cwd,String mode) throws java.io.IOException {
    noRun(player); var before=snapshot(player);
    var row=new JsonObject(); row.addProperty("questId",8013104); row.addProperty("progress",mode.equals("null")?1:0);
    row.addProperty("finishTime",Instant.now().getEpochSecond());
    if(mode.equals("null")) row.add("phase",JsonNull.INSTANCE); else row.addProperty("phase","FINISHED");
    player.getGameplay().getMainMissions().put(8013104,GSON.fromJson(row,QuestState.class));
    player.getGameplay().getCancelledMissions().remove(8013104); player.save();
    var after=snapshot(player); noRun(player);
    for(String key:List.of("credits","jades","ordinaryInventory","ordinaryTeams","ordinaryExtraTeams","currentExtraIndex",
        "ordinaryAvatars","storedAvatarDocuments","storedTeamDocuments","storedExtraTeamDocuments","storedItemDocuments"))
      require(before.get(key).equals(after.get(key)),"Corrupt record fixture changed an unrelated wallet/item/team: "+key);
    var beforeGame=before.getAsJsonObject("gameplay").deepCopy(); beforeGame.remove("mainMissions");
    var afterGame=after.getAsJsonObject("gameplay").deepCopy(); afterGame.remove("mainMissions");
    require(beforeGame.equals(afterGame),"Corrupt record fixture changed something outside original eligibility history");
    var out=new JsonObject(); out.add("before",before); out.add("after",after); out.add("isolatedRecord",row);
    out.addProperty("fixturePlayerSave",true); out.addProperty("sourceMissionId",8013104); out.addProperty("rewardGranted",false);
    write(cwd.resolve("qualification-integrity-setup-"+mode+".json"),out);
  }
  @Override public void execute(CommandArgs args) {
    if(args.getSender()!=null || args.getList().size()!=3) return;
    int uid=Integer.parseInt(args.getList().get(0)),nonce=Integer.parseInt(args.getList().get(2));
    String operation=args.getList().get(1);
    try {
      var cwd=Path.of("").toRealPath(); var marker=read(cwd.resolve("resources-fixture/resource-manifest.json"));
      var control=read(cwd.resolve("qualification-native-control.json"));
      require(cwd.getFileName().toString().startsWith("chess-rogue-native-qualification-")
          && marker.get("qualificationIntegrityFixture").getAsBoolean() && marker.get("testFixture").getAsBoolean()
          && marker.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b")
          && uid==10001 && uid==control.get("uid").getAsInt() && nonce==control.get("nonce").getAsInt(),"Unowned qualification directory/UID/nonce");
      int index=OPERATIONS.indexOf(operation); require(index>=0,"Unsupported qualification operation");
      var receipt=cwd.resolve("qualification-integrity-operation-"+operation+".json");
      require(!Files.exists(receipt),"Duplicate qualification operation");
      for(int i=0;i<index;i++) require(Files.isRegularFile(cwd.resolve("qualification-integrity-operation-"+OPERATIONS.get(i)+".json")),
          "Out-of-order qualification operation");
      var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid); require(player!=null,"Owned player is not online");
      synchronized(player) {
        if(operation.startsWith("seed-")) seedBad(player,cwd,operation.substring(5));
        else if(operation.equals("qualify")) {
          noRun(player); var before=snapshot(player); player.getGameplay().getMainMissions().remove(8013104);
          player.getGameplay().getCancelledMissions().remove(8013104); player.save(); var cleared=snapshot(player);
          var cleanup=new JsonObject(); cleanup.add("before",before); cleanup.add("after",cleared);
          cleanup.addProperty("isolatedCorruptRecordCleanup",true); write(cwd.resolve("qualification-integrity-corrupt-cleanup.json"),cleanup);
          qualification(player,cwd,nonce);
        } else {
          boolean before=operation.startsWith("before-"); String mode=operation.substring(before?7:6);
          var state=player.getGameplay().getMainMissions().get(8013104); require(state!=null,"Missing explicit fixture eligibility record");
          if(mode.equals("null")) require(state.getPhase()==null,"Wrong null-phase fixture");
          else if(mode.equals("progress")) require(state.getPhase()==QuestState.Phase.FINISHED && state.getProgress()==0,"Wrong damaged-progress fixture");
          else require(state.getQuestId()==8013104 && state.getPhase()==QuestState.Phase.CLOSED && state.getProgress()==1
              && state.getFinishTime()>0,"Valid production-settled qualification was lost");
          if(!mode.equals("start") || before) noRun(player);
          var observed=snapshot(player);
          if(!before && !mode.equals("start")) require(observed.equals(read(cwd.resolve("qualification-integrity-before-"+mode+".json"))),
              "Query/rejectedStart changed live/BSON/wallet/items/ordinary teams/run state");
          if(!before && mode.equals("start")) {
            var run=player.getGameplay().getChessRogue();
            require(run.active() && run.isInside() && run.getArea()==101 && run.getLayerIndex()==0 && run.getCurrentCell()==201
                && run.getEnteredCell()==201 && ChessRogueRoutes.valid(run)
                && ChessRogueRoutes.sceneSource(player.getScene(),run,true),"Legal firstStart did not load the complete original source room");
            var baseline=read(cwd.resolve("qualification-integrity-before-start.json"));
            for(String key:List.of("credits","jades","ordinaryInventory","ordinaryTeams","ordinaryExtraTeams","currentExtraIndex",
                "ordinaryAvatars","storedAvatarDocuments","storedTeamDocuments","storedExtraTeamDocuments","storedItemDocuments"))
              require(baseline.get(key).equals(observed.get(key)),"Legal firstStart changed unrelated formal wallet/item/team: "+key);
            var a=baseline.getAsJsonObject("gameplay").deepCopy(); a.remove("chessRogue");
            var b=observed.getAsJsonObject("gameplay").deepCopy(); b.remove("chessRogue");
            require(a.equals(b),"Legal firstStart changed non-Chess gameplay state");
          }
          write(cwd.resolve("qualification-integrity-"+operation+".json"),observed);
        }
        var done=new JsonObject(); done.addProperty("uid",uid); done.addProperty("nonce",nonce); done.addProperty("operation",operation);
        write(receipt,done);
      }
      System.out.println("HKRPG_CHESS_QUALIFICATION_ACK:"+uid+":"+operation+":"+nonce); System.out.flush();
    } catch(java.io.IOException error) { throw new IllegalStateException(error); }
  }
}
