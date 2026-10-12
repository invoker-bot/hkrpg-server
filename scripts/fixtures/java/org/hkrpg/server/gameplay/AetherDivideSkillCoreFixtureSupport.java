package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.util.*;

/** Build-only explicit source-known roster fixture and complete read-only observation barriers. */
public final class AetherDivideSkillCoreFixtureSupport {
  private static final Gson GSON=new Gson();
  private static final Map<Integer,Integer> STOCK=Map.of(250300,7,250304,2,250309,9,250201,3,250202,4,250204,2);
  private static void require(boolean ok,String why) { if(!ok)throw new IllegalStateException(why); }
  public static JsonObject json(Object value) { return GSON.toJsonTree(value).getAsJsonObject(); }
  private static JsonObject read(Path path) throws java.io.IOException { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
  private static void write(Path path,JsonObject value) throws java.io.IOException {
    Files.writeString(path,GSON.toJson(value),StandardOpenOption.CREATE_NEW);
  }
  private static JsonObject bson(org.bson.Document value) { return JsonParser.parseString(value.toJson()).getAsJsonObject(); }
  private static JsonArray documents(String name) {
    var rows=LunarCore.getGameDatabase().getDatabase().getCollection(name).find().into(new ArrayList<org.bson.Document>());
    rows.sort(Comparator.comparing(value->value.get("_id").toString()));var out=new JsonArray();rows.forEach(value->out.add(bson(value)));return out;
  }
  public static JsonObject snapshot(Player player) {
    var live=json(player);var stored=LunarCore.getGameDatabase().getDatabase().getCollection("players")
        .find(com.mongodb.client.model.Filters.eq("_id",player.getUid())).first();require(stored!=null,"Actual stored Player required");
    var out=new JsonObject();out.add("livePlayer",live);out.add("storedPlayerDocument",bson(stored));out.add("gameplay",json(player.getGameplay()));
    out.add("ordinaryInventory",GSON.toJsonTree(player.getInventory().getItems()));out.add("ordinaryLineups",GSON.toJsonTree(player.getLineupManager()));
    out.add("ordinaryTeams",GSON.toJsonTree(player.getLineupManager().getLineups()));
    out.add("ordinaryExtraTeams",GSON.toJsonTree(player.getLineupManager().getExtraLineups()));
    out.addProperty("currentExtraIndex",player.getLineupManager().getCurrentExtraIndex());
    out.add("ordinaryAvatars",GSON.toJsonTree(player.getAvatars().getAvatars()));
    out.add("storedAvatarDocuments",documents("avatars"));out.add("storedTeamDocuments",documents("lineups"));
    out.add("storedExtraTeamDocuments",documents("lineupsExtra"));out.add("storedItemDocuments",documents("items"));
    out.addProperty("credits",player.getScoin());out.addProperty("jades",player.getHcoin());
    var materials=new JsonObject();for(int id:new TreeSet<>(STOCK.keySet())) {
      var value=player.getInventory().getMaterialByItemId(id);materials.addProperty(Integer.toString(id),value==null?0:value.getCount());
    }
    out.add("actualMaterials",materials);
    require(live.equals(json(player)) && out.get("storedPlayerDocument").equals(bson(LunarCore.getGameDatabase().getDatabase()
        .getCollection("players").find(com.mongodb.client.model.Filters.eq("_id",player.getUid())).first())),"Snapshot mutated live or actual BSON Player");return out;
  }
  private static void ordinaryUnchanged(JsonObject before,JsonObject after,boolean items) {
    for(String key:List.of("credits","jades","ordinaryLineups","ordinaryTeams","ordinaryExtraTeams","currentExtraIndex",
        "ordinaryAvatars","storedAvatarDocuments","storedTeamDocuments","storedExtraTeamDocuments"))
      require(before.get(key).equals(after.get(key)),"Roster operation leaked into ordinary wallet/avatar/team: "+key);
    if(items)for(String key:List.of("ordinaryInventory","storedItemDocuments","actualMaterials"))
      require(before.get(key).equals(after.get(key)),"Roster request changed actual item inventory: "+key);
  }
  private static void withoutAether(JsonObject before,JsonObject after) {
    for(String key:List.of("livePlayer","storedPlayerDocument")) {
      var a=before.getAsJsonObject(key).deepCopy();var b=after.getAsJsonObject(key).deepCopy();
      require(a.has("gameplay") && b.has("gameplay"),"Missing actual gameplay storage shape");
      a.getAsJsonObject("gameplay").remove("aetherDivide");b.getAsJsonObject("gameplay").remove("aetherDivide");
      require(a.equals(b),"Roster mutation changed Player fields outside its owned state: "+key);
    }
    var a=before.getAsJsonObject("gameplay").deepCopy();var b=after.getAsJsonObject("gameplay").deepCopy();
    a.remove("aetherDivide");b.remove("aetherDivide");require(a.equals(b),"Roster mutation changed another gameplay state");
  }
  private static void seed(Player player,Path cwd) throws java.io.IOException {
    var definitions=AetherDivideDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()));require(definitions!=null,"Full fixed resources required");
    var proof=read(cwd.resolve("aether-source-proof.json"));require(proof.getAsJsonObject("files").size()==6,"All six complete original source tables required");
    require(definitions.spirits().get(6001).fragmentItem()==250300 && definitions.spirits().get(6005).fragmentItem()==250304
        && definitions.spirits().get(6010).fragmentItem()==250309,"Original ExpItemID mapping changed");
    var before=snapshot(player);require(json(player.getGameplay().getAetherDivide()).equals(json(new AetherDivideState())),"Fixture requires pristine unowned roster");
    var state=json(new AetherDivideState());state.addProperty("sourceFingerprint",definitions.fingerprint());
    var spirits=state.getAsJsonObject("spirits");
    for(int id:List.of(6001,6005)) {
      var spirit=new JsonObject();spirit.addProperty("id",id);spirit.addProperty("promotion",id==6001?2:3);spirit.addProperty("exp",0);
      spirit.addProperty("fragmentCount",id==6001?11:0);spirit.addProperty("curSp",id==6001?1000:500);spirit.add("skills",new JsonObject());
      spirits.add(Integer.toString(id),spirit);
    }
    var core=new JsonObject();core.addProperty("itemId",250201);core.addProperty("count",1);core.addProperty("dressedSpirit",0);
    state.getAsJsonObject("skillCores").add("250201",core);var restored=GSON.fromJson(state,AetherDivideState.class);
    require(restored.valid(definitions),"Explicit isolated saved roster violates original definitions");
    for(var entry:new TreeMap<>(STOCK).entrySet()) {
      int old=before.getAsJsonObject("actualMaterials").get(entry.getKey().toString()).getAsInt();require(old==0,"Fixture materials already present");
      require(player.getInventory().addItem(entry.getKey(),entry.getValue()),"Real isolated material delivery failed: "+entry.getKey());
    }
    player.getGameplay().aetherDivide(restored);player.save();var after=snapshot(player);ordinaryUnchanged(before,after,false);
    for(var entry:STOCK.entrySet())require(after.getAsJsonObject("actualMaterials").get(entry.getKey().toString()).getAsInt()==entry.getValue(),"Incorrect real material fixture stock");
    require(after.getAsJsonObject("gameplay").getAsJsonObject("aetherDivide").equals(state),"Explicit roster did not persist exactly");
    var out=new JsonObject();out.add("before",before);out.add("after",after);out.add("explicitSavedRoster",state);out.add("explicitRealMaterialGrants",GSON.toJsonTree(STOCK));
    var delta=new JsonObject();var a=before.getAsJsonObject("gameplay");var b=after.getAsJsonObject("gameplay");
    var keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());for(String key:keys)if(!Objects.equals(a.get(key),b.get(key))) {
      var row=new JsonObject();row.add("before",a.get(key));row.add("after",b.get(key));delta.add(key,row);
    }
    out.add("completeFixtureGameplayDelta",delta);out.addProperty("productionAcquisitionVerified",false);out.addProperty("originalResourcesModified",false);
    write(cwd.resolve("aether-core-fixture-setup.json"),out);
  }
  public static void execute(Player player,Path cwd,String operation) throws java.io.IOException {
    if(operation.equals("seed")) {seed(player,cwd);return;}
    if(operation.equals("checkpoint-restored")) {checkpoint(player,cwd);return;}
    boolean before=operation.startsWith("before-");require(before || operation.startsWith("after-"),"Only fixed fixture operations supported");
    String mode=operation.substring(before?7:6);var observed=snapshot(player);
    var definitions=AetherDivideDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()));
    require(definitions!=null && player.getGameplay().getAetherDivide().valid(definitions),"Complete resource lease and valid persisted roster required");
    // Preserve the actual complete live/BSON observation even when any following assertion fails.
    write(cwd.resolve("aether-core-"+operation+".json"),observed);
    if(!before) {
      var baseline=read(cwd.resolve("aether-core-before-"+mode+".json"));
      if(Set.of("equip","off","transfer").contains(mode)) {
        ordinaryUnchanged(baseline,observed,true);withoutAether(baseline,observed);
        int wanted=mode.equals("equip")?6001:mode.equals("transfer")?6005:0;
        var state=player.getGameplay().getAetherDivide();
        require(state.getCurrentSlot()==1,"Core request changed selected lineup slot");
        for(int slot=1;slot<=6;slot++)require(state.getLineups().get(slot).equals(List.of(0,0,0,0)),"Core request changed lineup positions");
        require(state.getSkillCores().get(250202).getDressedSpirit()==wanted,"Actual saved core owner mismatch");
        for(int id:List.of(6001,6005))require(state.getSpirits().get(id).getSkills().equals(
            wanted==id?Map.of(id==6001?1:2,250202):Map.of()),"Actual saved passive binding mismatch");
        // Compare every field, allowing exactly two binding fields and the new hint entry on first equip.
        var a=baseline.getAsJsonObject("gameplay").getAsJsonObject("aetherDivide").deepCopy();
        var b=observed.getAsJsonObject("gameplay").getAsJsonObject("aetherDivide").deepCopy();
        for(int id:List.of(6001,6005)) {
          a.getAsJsonObject("spirits").getAsJsonObject(Integer.toString(id)).add("skills",new JsonObject());
          b.getAsJsonObject("spirits").getAsJsonObject(Integer.toString(id)).add("skills",new JsonObject());
        }
        a.getAsJsonObject("skillCores").remove("250202");b.getAsJsonObject("skillCores").remove("250202");
        require(a.equals(b),"Core mutation changed unrelated stored fields");
        require(state.getSkillCores().get(250202).getCount()==4,"New core hint not frozen to first real inventory count");
      } else require(baseline.equals(observed),"Query/rejection/idempotent operation wrote live/BSON/inventory/wallet/ordinary team: "+mode);
    }
  }
  private static void checkpoint(Player player,Path cwd) throws java.io.IOException {
    var before=snapshot(player);write(cwd.resolve("aether-core-checkpoint-restored-before.json"),before);
    player.save();var after=snapshot(player);write(cwd.resolve("aether-core-checkpoint-restored-after.json"),after);
    var a=before.getAsJsonObject("storedPlayerDocument");var b=after.getAsJsonObject("storedPlayerDocument");
    var delta=new JsonObject();var keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());
    for(String key:keys)if(!Objects.equals(a.get(key),b.get(key))) {
      var entry=new JsonObject();entry.add("before",a.get(key));entry.add("after",b.get(key));delta.add(key,entry);
    }
    var raw=new JsonObject();raw.add("before",before);raw.add("after",after);raw.add("completeStoredPlayerDelta",delta);
    raw.addProperty("realPlayerSaveCalls",1);raw.addProperty("countedAsCoreBehavior",false);raw.addProperty("countedAsReadOnlyBarrier",false);
    write(cwd.resolve("aether-core-checkpoint-restored-raw.json"),raw);
    require(before.get("livePlayer").equals(after.get("livePlayer")),"Fixture checkpoint changed live Player");
    var expected=a.deepCopy();expected.add("lastActiveTime",before.getAsJsonObject("livePlayer").get("lastActiveTime").deepCopy());
    require(expected.equals(b),"Fixture checkpoint persisted an unknown non-login field");
    for(String key:before.keySet())if(!key.equals("storedPlayerDocument"))
      require(before.get(key).equals(after.get(key)),"Fixture checkpoint changed observation: "+key);
    require(delta.keySet().stream().allMatch("lastActiveTime"::equals),"Checkpoint may attribute only actual source login watermark");
    var result=new JsonObject();result.addProperty("passed",true);result.addProperty("realPlayerSaveCalls",1);result.add("completeStoredPlayerDelta",delta);
    write(cwd.resolve("aether-core-checkpoint-restored-result.json"),result);
  }
  private AetherDivideSkillCoreFixtureSupport() {}
}
