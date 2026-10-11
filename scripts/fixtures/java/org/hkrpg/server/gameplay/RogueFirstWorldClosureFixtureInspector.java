package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Reads the owned probe's real Player document after a normal stop; never saves a Player. */
public final class RogueFirstWorldClosureFixtureInspector {
  private static void require(boolean ok,String why) { if(!ok) throw new IllegalStateException(why); }
  public static void main(String[] args) throws Exception {
    var root=Path.of("").toRealPath();
    var directory=Path.of(args[0]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && !directory.equals(root.resolve("build/smoke")),"Not an owned smoke directory");
    var marker=JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("rogueFirstWorldClosureFixture").getAsBoolean(),"Missing closure marker");
    int uid=Integer.parseInt(args[1]);
    String mode=args[2];
    require(uid>0 && Set.of("pending","finished").contains(mode),"Invalid inspector arguments");
    var source=JsonParser.parseString(Files.readString(directory.resolve("actors.json"))).getAsJsonObject();
    var file=directory.resolve("data/database.mv");
    require(Files.isRegularFile(file),"Missing normally stopped H2 database");
    var server=new MongoServer(new H2Backend(file.toString()));
    var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var store=dev.morphia.Morphia.createDatastore(client,"hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,QuestState.class,emu.lunarcore.util.Position.class);
      require(store.find(Player.class).count()==1,"Not a unique fixture player");
      var player=Objects.requireNonNull(store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first());
      var state=player.getGameplay(); var run=state.getRogue();
      boolean pending=mode.equals("pending");
      require(run.getAreaId()==110 && run.getMapId()==101 && run.getRooms().equals(Map.of(1,304,2,302,3,303,4,304,5,305,6,306,7,307))
          && run.getRoster().equals(List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)) && run.getReturnTo()!=null,"Original map and lineup did not persist");
      require(run.getDialogues().size()==1,"Expected one original event lease");
      var lease=run.getDialogues().values().iterator().next();
      var reward=Objects.requireNonNull(lease.getRewardState());
      require(lease.getUniqueId()>0 && lease.key().equals("5:305:21:400001") && lease.getDialogue()==40398 && lease.getProgress()==0
          && reward.getOption()==14102 && reward.getBatch()>0 && reward.getBlessing()>0 && reward.isComplete()!=pending,"Event source, reward and receipt did not persist");
      if(pending) {
        require(run.getPhase()==GameplayState.RogueRun.Phase.EXPLORING && run.isInside() && run.getSiteId()==5 && run.getRoomId()==305
            && player.getEntryId()==8000103 && run.getCleared().equals(Set.of(1,2,3,4)) && run.getProcessedBattles().size()==7
            && run.getOffers().equals(List.of(reward.getBlessing())) && run.getSelectionId()==reward.getBatch()
            && run.getEventRewardLease().equals(lease.key()) && !run.getBlessings().containsKey(reward.getBlessing())
            && state.getRogueResults().isEmpty(),"Unconfirmed event reward or exit was altered in H2");
      } else {
        require(run.getPhase()==GameplayState.RogueRun.Phase.FINISHED && !run.isInside() && run.getSiteId()==7 && run.getRoomId()==307
            && player.getEntryId()!=8000103 && run.getCleared().equals(Set.of(1,2,3,4,5,6,7)) && run.getProcessedBattles().size()==8
            && !run.pending() && run.getEventRewardLease().isEmpty() && run.getBlessings().get(reward.getBlessing())==1
            && state.getRogueResults().size()==1,"Seven-site completion did not persist");
        var result=Objects.requireNonNull(state.getRogueResults().get(run.getRunId()));
        require(result.getOutcome()==GameplayState.RogueRun.Phase.FINISHED && result.getPassedRooms()==7 && result.getReachedRooms()==7
            && result.getBlessings().containsKey(reward.getBlessing()),"Frozen final result differs from the run");
      }
      require(run.getMiracles().isEmpty(),"Original event acquired an unauthored curio");
      for(var entry:run.getMonsterSelections().entrySet()) {
        var key=entry.getKey().split(":");
        require(key.length==5,"Wrong saved source actor key");
        var expected=source.getAsJsonObject(key[1]).getAsJsonObject(key[4]);
        var choice=entry.getValue();
        require(expected!=null && choice.getContent()==expected.get("content").getAsInt()
            && new Gson().toJsonTree(choice.getWeights()).equals(expected.get("weights")),"Source content weights changed");
        boolean found=false;
        for(var raw:expected.getAsJsonArray("choices")) {
          var row=raw.getAsJsonObject();
          if(choice.getMonster()==row.get("monster").getAsInt() && choice.getNpc()==row.get("npc").getAsInt()
              && choice.getStage()==row.get("stage").getAsInt()) found=true;
        }
        require(found,"Saved actor differs from original weighted choices");
      }
      var out=new LinkedHashMap<String,Object>();
      out.put("passed",true); out.put("uid",uid); out.put("mode",mode); out.put("runId",run.getRunId());
      out.put("unique",lease.getUniqueId()); out.put("batch",reward.getBatch()); out.put("buff",reward.getBlessing());
      out.put("coins",run.getCoins()); out.put("credits",player.getScoin()); out.put("cleared",run.getCleared());
      out.put("sourceChoices",run.getMonsterSelections()); out.put("eventReward",reward); out.put("changedPlayers",0);
      System.out.println(new Gson().toJson(out));
    } finally { server.shutdownNow(); }
  }
  private RogueFirstWorldClosureFixtureInspector() {}
}
