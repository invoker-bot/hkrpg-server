package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Read-only Player checks after normal stop, using the owned original-seven-site fixture. */
public final class RogueRestMerchantFixtureInspector {
  private static final Gson GSON = new Gson();
  private static void require(boolean ok,String why) { if(!ok) throw new IllegalStateException(why); }

  public static void main(String[] args) throws Exception {
    var root=Path.of("").toRealPath();
    var directory=Path.of(args[0]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke"))
        && directory.getFileName().toString().startsWith("rogue-rest-merchants-"),"Not an owned merchant smoke directory");
    var marker=JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("rogueRestMerchantFixture").getAsBoolean()
        && marker.get("sourceCommit").getAsString().equals(RogueRestMerchantRecipes.COMMIT),"Missing pinned merchant fixture marker");
    int uid=Integer.parseInt(args[1]), selected=Integer.parseInt(args[3]), beforeCoins=Integer.parseInt(args[4]);
    String mode=args[2];
    require(uid>0 && selected>0 && beforeCoins>=200 && Set.of("stock","bought","finished").contains(mode),"Invalid inspector arguments");
    boolean purchased=!mode.equals("stock"), finished=mode.equals("finished");
    var source=JsonParser.parseString(Files.readString(directory.resolve("actors.json"))).getAsJsonObject();
    var unlocks=JsonParser.parseString(Files.readString(directory.resolve("handbook-unlocks.json"))).getAsJsonObject();
    int unlock=unlocks.get(Integer.toString(selected)).getAsInt();
    var file=directory.resolve("data/database.mv");
    require(Files.isRegularFile(file),"Missing normally stopped H2 database");
    var server=new MongoServer(new H2Backend(file.toString()));
    var address=server.bind();
    try(var client=MongoClients.create("mongodb://127.0.0.1:"+address.getPort()+"/?directConnection=true")) {
      var documents=client.getDatabase("hkrpg").getCollection("players");
      var documentsBefore=documents.find().into(new ArrayList<org.bson.Document>());
      var store=dev.morphia.Morphia.createDatastore(client,"hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class,QuestState.class,emu.lunarcore.util.Position.class);
      require(documentsBefore.size()==1 && store.find(Player.class).count()==1,"Not a unique fixture player");
      var player=Objects.requireNonNull(store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first());
      var second=Objects.requireNonNull(store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id",uid)).first());
      require(GSON.toJsonTree(player.getGameplay()).equals(GSON.toJsonTree(second.getGameplay())),"Two independent Player reads differ");
      var state=player.getGameplay(); var run=state.getRogue();
      require(run.getAreaId()==110 && run.getMapId()==101
          && run.getRooms().equals(Map.of(1,304,2,302,3,303,4,304,5,305,6,306,7,307))
          && run.getRoster().equals(List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)) && run.getReturnTo()!=null,
          "Original seven-site map and lineup did not persist");
      require(run.getDialogues().size()==1,"Expected one original event lease");
      var dialogue=run.getDialogues().values().iterator().next();
      var reward=Objects.requireNonNull(dialogue.getRewardState());
      require(dialogue.getUniqueId()>0 && dialogue.key().equals("5:305:21:400001")
          && dialogue.getDialogue()==40398 && dialogue.getProgress()==0 && reward.getOption()==14102
          && reward.getBatch()>0 && reward.getBlessing()>0 && reward.isComplete()
          && run.getBlessings().getOrDefault(reward.getBlessing(),0)>0 && run.getEventRewardLease().isEmpty() && !run.pending(),
          "Original 40398 blessing and completion receipt did not persist");
      if(finished) {
        require(run.getPhase()==GameplayState.RogueRun.Phase.FINISHED && !run.isInside()
            && run.getSiteId()==7 && run.getRoomId()==307 && player.getEntryId()!=8000103
            && run.getCleared().equals(Set.of(1,2,3,4,5,6,7)) && run.getProcessedBattles().size()==8
            && state.getRogueResults().size()==1,"Seven-site completion did not persist");
        var result=Objects.requireNonNull(state.getRogueResults().get(run.getRunId()));
        require(result.getOutcome()==GameplayState.RogueRun.Phase.FINISHED && result.getPassedRooms()==7
            && result.getReachedRooms()==7 && result.getBlessings().containsKey(reward.getBlessing())
            && result.getMiracles().contains(selected),"Frozen final result lost the acquired merchant curio");
      } else {
        require(run.getPhase()==GameplayState.RogueRun.Phase.EXPLORING && run.isInside()
            && run.getSiteId()==6 && run.getRoomId()==306 && player.getEntryId()==8000103
            && run.getCleared().equals(Set.of(1,2,3,4,5,6)) && run.getProcessedBattles().size()==7
            && state.getRogueResults().isEmpty() && run.getCoins()==beforeCoins-(purchased?200:0),
            "Sixth-site wallet or clearance differs from purchase phase");
      }
      require(run.getMiracleShops().keySet().equals(Set.of("6:306:22:400001")),"Merchant stock is not bound to original G22");
      var plan=run.getMiracleShops().get("6:306:22:400001");
      var lease=Objects.requireNonNull(plan.getRestMerchant());
      require(lease.getSchema()==1 && lease.getRuleId().equals(RogueRestMerchantRecipes.RULE)
          && lease.getRunId().equals(run.getRunId()) && lease.getSourceCommit().equals(RogueRestMerchantRecipes.COMMIT)
          && lease.getGroupPath().equals(RogueRestMerchantRecipes.GROUP)
          && lease.getGroupNormalizedSHA256().equals(RogueRestMerchantRecipes.GROUP_SHA)
          && lease.getDialoguePath().equals(RogueRestMerchantRecipes.DIALOGUE)
          && lease.getDialogueNormalizedSHA256().equals(RogueRestMerchantRecipes.DIALOGUE_SHA)
          && lease.getArea()==110 && lease.getMap()==101 && lease.getSite()==6 && lease.getRoom()==306
          && lease.getEntry()==8000103 && lease.getPlane()==80001 && lease.getFloor()==80001003
          && lease.getGroup()==22 && lease.getNpc()==1013 && lease.getInstance()==400001
          && lease.getBasePrice()==200 && lease.getStockLimit()==3,"Merchant source or configured policy changed in H2");
      require(!plan.getOffers().isEmpty() && plan.getOffers().size()<=3 && plan.getOffers().containsKey(selected),"Empty or changed frozen stock");
      var stock=new TreeMap<Integer,Integer>();
      for(var entry:plan.getOffers().entrySet()) {
        var offer=entry.getValue();
        require(offer.getLevel()==1 && offer.getCost()==200 && offer.debitPlan()!=null && offer.debitPlan().valid()
            && offer.isPurchased()==(purchased && entry.getKey()==selected),"Stock cost or purchase flag changed");
        stock.put(entry.getKey(),offer.getCost());
      }
      require(run.getMiracles().keySet().equals(purchased?Set.of(selected):Set.of())
          && state.getRogueHandbook().getMiracles().equals(purchased && unlock>0?Set.of(unlock):Set.of()),
          "Current curio or explicit source handbook unlock differs from purchase phase");
      String acquisition="";
      if(purchased) {
        acquisition=run.getMiracles().get(selected).getAcquisitionId();
        require(RogueLotteryState.uuid(acquisition) && lease.getPurchases().keySet().equals(Set.of(selected)),"Invalid or duplicate purchase UUID");
        var receipt=lease.getPurchases().get(selected);
        require(receipt.valid() && receipt.getCost()==200 && receipt.getAcquisitions().equals(List.of(acquisition)),"Purchase receipt lost its debit or acquired UUID");
      } else require(lease.getPurchases().isEmpty(),"Unpurchased stock already has a receipt");
      for(var entry:run.getMonsterSelections().entrySet()) {
        var key=entry.getKey().split(":"); require(key.length==5,"Wrong saved source actor key");
        var expected=source.getAsJsonObject(key[1]).getAsJsonObject(key[4]); var choice=entry.getValue();
        require(expected!=null && choice.getContent()==expected.get("content").getAsInt()
            && GSON.toJsonTree(choice.getWeights()).equals(expected.get("weights")),"Source content weights changed");
        boolean found=false;
        for(var raw:expected.getAsJsonArray("choices")) {
          var row=raw.getAsJsonObject();
          if(choice.getMonster()==row.get("monster").getAsInt() && choice.getNpc()==row.get("npc").getAsInt()
              && choice.getStage()==row.get("stage").getAsInt()) found=true;
        }
        require(found,"Saved actor differs from original weighted choices");
      }
      var documentsAfter=documents.find().into(new ArrayList<org.bson.Document>());
      require(documentsBefore.equals(documentsAfter),"Read-only inspection changed a Player document");
      var out=new LinkedHashMap<String,Object>();
      out.put("passed",true); out.put("uid",uid); out.put("mode",mode); out.put("runId",run.getRunId());
      out.put("unique",dialogue.getUniqueId()); out.put("batch",reward.getBatch()); out.put("buff",reward.getBlessing());
      out.put("selected",selected); out.put("stock",stock); out.put("acquisitionUUID",acquisition);
      out.put("merchantLease",lease); out.put("handbook",state.getRogueHandbook().getMiracles());
      out.put("coins",run.getCoins()); out.put("credits",player.getScoin()); out.put("cleared",run.getCleared());
      out.put("sourceChoices",run.getMonsterSelections()); out.put("eventReward",reward);
      out.put("independentPlayerReads",2); out.put("comparedPlayerDocuments",documentsBefore.size()); out.put("changedPlayers",0);
      System.out.println(GSON.toJson(out));
    } finally { server.shutdownNow(); }
  }
  private RogueRestMerchantFixtureInspector() {}
}
