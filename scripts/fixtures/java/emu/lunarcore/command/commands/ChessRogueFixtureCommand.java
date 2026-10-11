package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Original qualification history plus read-only observations; never creates a Chess run or victory/wallet. */
@Command(label="chessroguefixture",requireTarget=false,permission="fixture.console-only",
    desc="仅隔离 Chess 验收：原8013104资格历史与首战/逐战只读观察")
public final class ChessRogueFixtureCommand implements CommandHandler {
  private static final Gson GSON=new Gson();
  private static void require(boolean ok,String why) { if (!ok) throw new IllegalStateException(why); }
  private static JsonObject json(Object value) { return GSON.toJsonTree(value).getAsJsonObject(); }
  private static JsonObject isolation(Player player) {
    var gameplay=json(player.getGameplay()); var result=new JsonObject();
    result.add("classic",gameplay.get("rogue").deepCopy());
    var daily=new JsonObject();
    for (var field:gameplay.entrySet()) if (field.getKey().startsWith("daily")) daily.add(field.getKey(),field.getValue().deepCopy());
    result.add("daily",daily);
    var inventory=new JsonArray();
    player.getInventory().getItems().values().stream().sorted(Comparator.comparingInt(emu.lunarcore.game.inventory.GameItem::getItemId))
        .forEach(item->inventory.add(GSON.toJsonTree(item)));
    result.add("ordinaryInventory",inventory);
    result.add("ordinaryLineups",GSON.toJsonTree(player.getLineupManager()));
    var teams=new JsonArray();
    // The ordinary lineup manager also holds unsaved empty templates. Only real stored teams are a DB baseline.
    try (var stored=LunarCore.getGameDatabase().getObjects(PlayerLineup.class,"ownerUid",player.getUid())) {
      stored.sorted(Comparator.comparingInt(PlayerLineup::getIndex)).forEach(team->teams.add(GSON.toJsonTree(team)));
    }
    result.add("ordinaryTeams",teams);
    result.addProperty("credits",player.getScoin()); result.addProperty("jades",player.getHcoin());
    var avatars=new JsonArray();
    player.getAvatars().getAvatars().values().stream().sorted(Comparator.comparingInt(emu.lunarcore.game.avatar.GameAvatar::getAvatarId))
        .forEach(avatar->avatars.add(GSON.toJsonTree(avatar)));
    result.add("ordinaryAvatars",avatars);
    return result;
  }
  private static JsonObject read(Path path) throws java.io.IOException {
    return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
  }
  private static void write(Path path,JsonObject value) throws java.io.IOException {
    Files.writeString(path,GSON.toJson(value),StandardOpenOption.CREATE_NEW);
  }
  private static void observe(Player player,Path cwd,String phase,int battle,int nonce) throws java.io.IOException {
    int count=phase.equals("start")?0:Integer.parseInt(phase.substring(3))-200;
    require(count>=0 && count<=4 && (count==0)==(battle==0),"Invalid actual observation phase/battle");
    var before=json(player); var storedBefore=json(LunarCore.getGameDatabase().getObjectByUid(Player.class,player.getUid()));
    var gameplay=json(player.getGameplay()); var chess=gameplay.getAsJsonObject("chessRogue");
    require(chess!=null && chess.get("inside").getAsBoolean() && chess.get("area").getAsInt()==101
        && chess.get("layerIndex").getAsInt()==0 && chess.get("currentCell").getAsInt()==(count==0?201:200+count)
        && chess.get("coins").getAsInt()==count*100
        && chess.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"),
        "Read-only observation does not match the actual source run and cell");
    var victories=chess.getAsJsonObject("victories"); var expected=new TreeSet<String>();
    for (int cell=201;cell<=200+count;cell++) expected.add("0:"+cell);
    require(victories.keySet().equals(expected),"Observation cannot substitute for the actual complete WIN receipts");
    if (count>0) require(victories.getAsJsonObject("0:"+(200+count)).get("battle").getAsInt()==battle,
        "Observation did not bind to the actual native battle ID");
    var state=player.getGameplay().getMainMissions().get(8013104);
    var receipt=gameplay.getAsJsonObject("rewards").getAsJsonObject("mission:8013104");
    var original=GameData.getRewardExcelMap().get(18013104);
    require(original!=null && original.getRewards().size()==1 && original.getRewards().get(2)==5000
        && state!=null && state.getPhase()==QuestState.Phase.CLOSED && state.getProgress()==1 && state.getFinishTime()>0
        && receipt!=null && receipt.get("rewardId").getAsInt()==18013104 && receipt.get("granted").getAsBoolean(),
        "Actual original qualification auto reward was not independently observed");
    var snapshot=isolation(player); snapshot.addProperty("uid",player.getUid()); snapshot.addProperty("nonce",nonce);
    snapshot.addProperty("phase",phase); snapshot.addProperty("battleId",battle);
    snapshot.addProperty("runId",chess.get("runId").getAsString()); snapshot.addProperty("chessCoins",count*100);
    snapshot.addProperty("actualWinReceiptCount",count); snapshot.add("qualification",GSON.toJsonTree(state));
    snapshot.add("qualificationRewardReceipt",receipt.deepCopy());
    var baseline=read(cwd.resolve(count==0?"fixture-baseline.json":"fixture-observed-start.json"));
    require(baseline.get("uid").getAsInt()==player.getUid() && baseline.get("nonce").getAsInt()==nonce,
        "Wrong owned observation baseline");
    var delta=new JsonObject();
    delta.addProperty("credits",snapshot.get("credits").getAsInt()-baseline.get("credits").getAsInt());
    delta.addProperty("jades",snapshot.get("jades").getAsInt()-baseline.get("jades").getAsInt());
    for (String field:List.of("classic","daily","ordinaryInventory","ordinaryLineups","ordinaryTeams","ordinaryAvatars"))
      delta.addProperty(field+"Unchanged",snapshot.get(field).equals(baseline.get(field)));
    snapshot.add("deltaFromObservedBaseline",delta);
    boolean playerUnchanged=before.equals(json(player));
    boolean storedUnchanged=storedBefore.equals(json(LunarCore.getGameDatabase().getObjectByUid(Player.class,player.getUid())));
    snapshot.addProperty("livePlayerUnchangedByObservation",playerUnchanged);
    snapshot.addProperty("storedPlayerUnchangedByObservation",storedUnchanged);
    write(cwd.resolve("fixture-observed-"+phase+".json"),snapshot);
    System.out.println("HKRPG_CHESS_OBSERVATION:"+GSON.toJson(snapshot)); System.out.flush();
    require(playerUnchanged && storedUnchanged,"Read-only observation changed the live/stored player");
    if (count==0) {
      require(delta.get("credits").getAsInt()==5000 && delta.get("jades").getAsInt()==0,
          "Observed original qualification reward differs from source Item2/5000");
    } else {
      require(snapshot.get("runId").equals(baseline.get("runId")) && delta.get("credits").getAsInt()==0
          && delta.get("jades").getAsInt()==0,"Chess battle manufactured ordinary currency");
      for (String field:List.of("classic","daily","ordinaryInventory","ordinaryLineups","ordinaryTeams","ordinaryAvatars"))
        require(delta.get(field+"Unchanged").getAsBoolean(),"Actual Chess battle mutated generic state: "+field);
    }
  }
  @Override public void execute(CommandArgs args) {
    if (args.getSender()!=null || !Set.of(3,4).contains(args.getList().size())) return;
    int uid=Integer.parseInt(args.getList().get(0)), nonce=Integer.parseInt(args.getList().getLast());
    String operation=args.getList().get(1); boolean unlock=operation.equals("unlock");
    require(uid==10001 && nonce>0 && (unlock && args.getList().size()==3
        || args.getList().size()==4 && Set.of("observe-start","observe-win201","observe-win202","observe-win203","observe-win204").contains(operation)),
        "Invalid owned Chess fixture command");
    Path cwd;
    try {
      cwd=Path.of("").toRealPath();
      var marker=JsonParser.parseString(Files.readString(cwd.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
      require(cwd.getFileName().toString().startsWith("chess-rogue-native-")
          && marker.get("testFixture").getAsBoolean() && marker.get("chessRogueFixture").getAsBoolean()
          && marker.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"),"Not an owned Chess fixture");
      var row=GameData.getMainMissionExcelByID(8013104);
      require(row!=null && row.getMainMissionID()==8013104 && row.getRewardID()==18013104,
          "Original source qualification is not loaded");
    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid);
    require(player!=null && player.getUid()==uid,"Owned new Chess player is not online");
    synchronized (player) {
      if (!unlock) {
        int battle=Integer.parseInt(args.getList().get(2));
        try { observe(player,cwd,operation.substring(8),battle,nonce); }
        catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
        System.out.println("HKRPG_CHESS_FIXTURE_ACK:"+uid+":"+operation+":"+battle+":"+nonce); System.out.flush();
        return;
      }
      var gameplay=player.getGameplay();
      // Reflection keeps this independent helper compilable against the frozen pre-feature E jar.
      var before=json(gameplay);
      require(!before.has("chessRogue") || before.getAsJsonObject("chessRogue").get("runId").getAsString().isEmpty(),
          "Qualification cannot overwrite an already started Chess run");
      require(!gameplay.getMainMissions().containsKey(8013104),"Qualification already exists");
      var baseline=isolation(player); baseline.addProperty("uid",uid); baseline.addProperty("nonce",nonce);
      baseline.addProperty("phase","before-qualification");
      var state=new QuestState(8013104);
      require(state.advance(1,1,Instant.now().getEpochSecond()) && state.getPhase()==QuestState.Phase.FINISHED,
          "Original qualification history did not finish");
      gameplay.getMainMissions().put(8013104,state);
      var after=json(gameplay);
      before.remove("mainMissions"); after.remove("mainMissions");
      require(before.equals(after) && player.getScoin()==baseline.get("credits").getAsInt()
          && player.getHcoin()==baseline.get("jades").getAsInt(),"History fixture changed gameplay or currency");
      try { write(cwd.resolve("fixture-baseline.json"),baseline); }
      catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
      player.save();
      System.out.println("HKRPG_CHESS_FIXTURE_VIEW:"+GSON.toJson(Map.of("uid",uid,"nonce",nonce,
          "seededOriginalQualification",8013104,"chessRunSeeded",false,"coinsSeeded",false,
          "battleWinSeeded",false,"rewardOrAcquisitionSeeded",false,"entrySeeded",false)));
    }
    System.out.println("HKRPG_CHESS_FIXTURE_ACK:"+uid+":unlock:"+nonce); System.out.flush();
  }
}
