package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;

/** Explicit original qualification/talent ownership; complete observations do not save. */
public final class RogueBlessingPersistenceFixtureSupport {
  private static final Gson GSON=new Gson();
  private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
  private static JsonObject read(Path path)throws java.io.IOException{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
  private static void write(Path path,JsonObject value)throws java.io.IOException{Files.writeString(path,GSON.toJson(value),StandardOpenOption.CREATE_NEW);}
  private static JsonObject object(Object value){return GSON.toJsonTree(value).getAsJsonObject();}
  private static JsonObject bson(org.bson.Document value){return JsonParser.parseString(value.toJson()).getAsJsonObject();}
  private static JsonArray documents(String name){var values=LunarCore.getGameDatabase().getDatabase().getCollection(name).find().into(new ArrayList<org.bson.Document>());values.sort(Comparator.comparing(value->value.get("_id").toString()));var out=new JsonArray();values.forEach(value->out.add(bson(value)));return out;}
  public static JsonObject snapshot(Player player) {
    var out=new JsonObject();out.add("livePlayer",object(player));out.add("storedPlayerDocument",bson(Objects.requireNonNull(LunarCore.getGameDatabase().getDatabase().getCollection("players").find(com.mongodb.client.model.Filters.eq("_id",player.getUid())).first())));
    out.add("gameplay",object(player.getGameplay()));out.add("ordinaryInventory",GSON.toJsonTree(player.getInventory().getItems()));
    out.add("ordinaryTeams",GSON.toJsonTree(player.getLineupManager().getLineups()));out.add("ordinaryExtraTeams",GSON.toJsonTree(player.getLineupManager().getExtraLineups()));out.add("ordinaryAvatars",GSON.toJsonTree(player.getAvatars().getAvatars()));
    out.add("storedAvatarDocuments",documents("avatars"));out.add("storedTeamDocuments",documents("lineups"));out.add("storedExtraTeamDocuments",documents("lineupsExtra"));out.add("storedItemDocuments",documents("items"));
    out.addProperty("credits",player.getScoin());out.addProperty("jades",player.getHcoin());return out;
  }
  private static void ordinaryUnchanged(JsonObject a,JsonObject b) {
    for(String key:List.of("ordinaryInventory","ordinaryTeams","ordinaryExtraTeams","ordinaryAvatars","storedAvatarDocuments","storedTeamDocuments","storedExtraTeamDocuments","storedItemDocuments","credits","jades"))require(a.get(key).equals(b.get(key)),"Blessing operation changed ordinary state: "+key);
  }
  private static JsonObject delta(JsonObject a,JsonObject b) {
    var out=new JsonObject();var keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());
    for(String key:keys)if(!Objects.equals(a.get(key),b.get(key))){var change=new JsonObject();change.add("before",a.get(key));change.add("after",b.get(key));out.add(key,change);}return out;
  }
  private static void onlyChanges(JsonObject a,JsonObject b,Set<String> allowed,String label) {
    require(allowed.containsAll(delta(a,b).keySet()),"Unexpected complete "+label+" delta: "+delta(a,b).keySet());
  }
  private static void seed(Player player,Path cwd)throws java.io.IOException {
    var before=snapshot(player);var state=player.getGameplay();require(state.getRogue().getPhase()==GameplayState.RogueRun.Phase.IDLE,"Fresh classic run required");
    require(GameplayDefinitions.rogueTalents.get(11)!=null&&GameplayDefinitions.rogueTalents.get(11).EffectDescParamList().isEmpty(),"Original talent11 definition required");
    for(int id:List.of(4030001,4030002,1010403)) {
      var qualification=new QuestState(id);require(qualification.advance(1,1,System.currentTimeMillis()/1000)&&qualification.reserveReward(),"Valid explicit completed source mission fixture required");qualification.closeReward();
      state.getMainMissions().put(id,qualification);state.getCancelledMissions().remove(id);
    }
    state.getRogueTalents().add(11);player.save();
    var after=snapshot(player);var report=new JsonObject();report.add("before",before);report.add("after",after);report.add("completeGameplayDelta",delta(before.getAsJsonObject("gameplay"),after.getAsJsonObject("gameplay")));write(cwd.resolve("rogue-blessing-fixture-setup-observed.json"),report);
    ordinaryUnchanged(before,after);onlyChanges(before.getAsJsonObject("gameplay"),after.getAsJsonObject("gameplay"),Set.of("mainMissions","cancelledMissions","rogueTalents"),"explicit fixture qualification/ownership");
    require(RogueUnlocks.open(player,12001)&&RogueTalentEffects.enabled(player,11),"Actual first-world gate and original reroll eligibility must open");
    report.addProperty("productionStoryCompletionVerified",false);report.addProperty("talentPurchaseVerified",false);report.add("sourceMainMissions",GSON.toJsonTree(List.of(4030001,4030002,1010403)));report.addProperty("explicitSavedTalent",11);write(cwd.resolve("rogue-blessing-fixture-setup.json"),report);
  }
  private static void checkpoint(Player player,Path cwd)throws java.io.IOException {
    var before=snapshot(player);write(cwd.resolve("rogue-blessing-checkpoint-restored-before.json"),before);player.save();var after=snapshot(player);write(cwd.resolve("rogue-blessing-checkpoint-restored-after.json"),after);
    require(before.get("livePlayer").equals(after.get("livePlayer")),"Fixture checkpoint changed live Player");
    var expected=before.getAsJsonObject("storedPlayerDocument").deepCopy();expected.add("lastActiveTime",before.getAsJsonObject("livePlayer").get("lastActiveTime"));
    require(expected.equals(after.get("storedPlayerDocument")),"Checkpoint persisted another field besides pending genuine login time");
    for(String key:before.keySet())if(!key.equals("storedPlayerDocument"))require(before.get(key).equals(after.get(key)),"Checkpoint changed full observation: "+key);
    var report=new JsonObject();report.addProperty("passed",true);report.addProperty("realPlayerSaveCalls",1);report.addProperty("countedAsBlessingBehavior",false);report.addProperty("countedAsReadOnlyBarrier",false);report.add("completeStoredPlayerDelta",delta(before.getAsJsonObject("storedPlayerDocument"),after.getAsJsonObject("storedPlayerDocument")));write(cwd.resolve("rogue-blessing-checkpoint-restored-result.json"),report);
  }
  private static void validateMutation(Player player,JsonObject before,JsonObject after,String phase) {
    ordinaryUnchanged(before,after);var a=before.getAsJsonObject("gameplay");var b=after.getAsJsonObject("gameplay");
    onlyChanges(a,b,phase.equals("reroll")?Set.of("rogue"):Set.of("rogue","rogueCollectedBuffs"),"Gameplay");
    var old=a.getAsJsonObject("rogue");var now=b.getAsJsonObject("rogue");var run=player.getGameplay().getRogue();
    require(run.getAreaId()==110&&run.getMapId()==101&&run.getSiteId()==1&&run.getRoomId()==304&&run.isInside()&&run.getPhase()==GameplayState.RogueRun.Phase.EXPLORING,"Original route/run context changed");
    require(run.getCleared().equals(Set.of(1))&&run.getProcessedBattles().size()==3&&run.getMiracles().isEmpty()&&run.getEventRewardLease().isEmpty(),"Must be an actual ordinary three-battle victory menu");
    if(phase.equals("reroll")) {
      onlyChanges(old,now,Set.of("coins","offers","offerExcluded","offerRerolls"),"Rogue reroll");
      require(run.getCoins()==old.get("coins").getAsInt()-30&&run.getOfferRerolls()==1&&run.getOffers().size()==3&&run.getSelectionId()==old.get("selectionId").getAsInt(),"Wrong actual frozen 30-price reroll");
      require(run.getOfferRerollCosts().equals(List.of(30)),"Original saved talent11 price changed");
      onlyChanges(before.getAsJsonObject("livePlayer"),after.getAsJsonObject("livePlayer"),Set.of("gameplay"),"live Player reroll");
      onlyChanges(before.getAsJsonObject("storedPlayerDocument"),after.getAsJsonObject("storedPlayerDocument"),Set.of("gameplay"),"stored Player reroll");
    } else {
      onlyChanges(old,now,Set.of("blessings","offers","offerPool","offerLevels","offerWeights","offerRerollCosts","offerFragmentCosts","offerExcluded","offerChoiceTotal","offerChoiceIndex","offerOptionLimit","offerEnhancementCount","offerBaseLevels","offerEnhanceable"),"Rogue selection");
      int buff=old.getAsJsonArray("offers").get(0).getAsInt();require(run.getBlessings().equals(Map.of(buff,1))&&!run.pending()&&run.getCoins()==old.get("coins").getAsInt(),"Actual single selected blessing/menu/currency mismatch");
      require(player.getGameplay().getRogueCollectedBuffs().equals(Set.of(buff)),"Actual single permanent blessing collection missing");
      // The real existing source handbook callback may advance source-linked quests, never rewards.
      onlyChanges(before.getAsJsonObject("livePlayer"),after.getAsJsonObject("livePlayer"),Set.of("gameplay","questStates"),"live Player collection callback");
      onlyChanges(before.getAsJsonObject("storedPlayerDocument"),after.getAsJsonObject("storedPlayerDocument"),Set.of("gameplay","questStates"),"stored Player collection callback");
      var quests=delta(before.getAsJsonObject("livePlayer").getAsJsonObject("questStates"),after.getAsJsonObject("livePlayer").getAsJsonObject("questStates"));
      for(String id:quests.keySet())require(RogueBlessingQuests.belongs(emu.lunarcore.data.GameData.getQuestExcelMap().get(Integer.parseInt(id))),"Unrelated quest changed on source blessing collection: "+id);
    }
  }
  public static void execute(Player player,Path cwd,String operation)throws java.io.IOException {
    if(operation.equals("seed")){seed(player,cwd);return;}if(operation.equals("checkpoint-restored")){checkpoint(player,cwd);return;}
    boolean before=operation.startsWith("before-");require(before||operation.startsWith("after-"),"Fixed source observation only");String phase=operation.substring(before?7:6);
    var observed=snapshot(player);write(cwd.resolve("rogue-blessing-"+operation+".json"),observed); // Preserve full observation before assertions.
    if(!before) {
      var baseline=read(cwd.resolve("rogue-blessing-before-"+phase+".json"));
      if(Set.of("reroll","select").contains(phase))validateMutation(player,baseline,observed,phase);else require(baseline.equals(observed),"Query/rejection changed complete live/BSON observation: "+phase);
      var report=new JsonObject();report.add("completeGameplayDelta",delta(baseline.getAsJsonObject("gameplay"),observed.getAsJsonObject("gameplay")));report.add("completeLivePlayerDelta",delta(baseline.getAsJsonObject("livePlayer"),observed.getAsJsonObject("livePlayer")));report.add("completeStoredPlayerDelta",delta(baseline.getAsJsonObject("storedPlayerDocument"),observed.getAsJsonObject("storedPlayerDocument")));write(cwd.resolve("rogue-blessing-delta-"+phase+".json"),report);
    }
  }
  private RogueBlessingPersistenceFixtureSupport(){}
}
