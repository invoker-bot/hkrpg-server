package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.util.*;

/** Build-only explicit source-known roster fixture and complete read-only observation barriers. */
public final class MaterialSubmitFixtureSupport {
  private static final Gson GSON=new Gson();
  private static MaterialSubmitDefinitions definitions(){var d=MaterialSubmitDefinitions.load(Path.of(LunarCore.getConfig().getResourceDir()));require(d!=null,"Original full material resource lease required");return d;}
  private static Set<Integer> watched(){var ids=new TreeSet<Integer>();for(var row:definitions().rows().values()){ids.addAll(row.costs().keySet());ids.addAll(row.rewards().keySet());}ids.remove(1);ids.remove(2);return ids;}
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
    var materials=new JsonObject();for(int id:watched()) {
      var value=player.getInventory().getMaterialByItemId(id);materials.addProperty(Integer.toString(id),value==null?0:value.getCount());
    }
    out.add("actualMaterials",materials);
    require(live.equals(json(player)) && out.get("storedPlayerDocument").equals(bson(LunarCore.getGameDatabase().getDatabase()
        .getCollection("players").find(com.mongodb.client.model.Filters.eq("_id",player.getUid())).first())),"Snapshot mutated live or actual BSON Player");return out;
  }
  private static void ordinaryUnchanged(JsonObject a,JsonObject b) {
    for(String key:List.of("ordinaryLineups","ordinaryTeams","ordinaryExtraTeams","currentExtraIndex","ordinaryAvatars","storedAvatarDocuments","storedTeamDocuments","storedExtraTeamDocuments"))require(a.get(key).equals(b.get(key)),"Material flow changed unrelated ordinary state: "+key);
  }
  private static void windows(Path cwd,boolean seed) throws java.io.IOException {
    require(GameplayDefinitions.windows.isEmpty(),"No operator windows may be silently replaced");var before=new JsonArray();var after=new JsonArray();long now=java.time.Instant.now().getEpochSecond();
    for(var row:new TreeMap<>(definitions().rows()).values()){var w=new GameplayDefinitions.Window(30014,row.module(),now-60,now+3600);GameplayDefinitions.windows.add(w);after.add(GSON.toJsonTree(w));}
    var value=new JsonObject();value.add("before",before);value.add("after",after);value.addProperty("explicitIsolatedWindows",true);value.addProperty("operatorWindowsChanged",false);write(cwd.resolve(seed?"material-seed-windows.json":"material-restart-windows.json"),value);
  }
  private static void seed(Player p,Path cwd) throws java.io.IOException {
    var before=snapshot(p);require(p.getLevel()==21 && p.getGameplay().getMaterialSubmission().receipts().isEmpty(),"Pristine source-level fixture required");
    var costs=new TreeSet<Integer>();for(var row:definitions().rows().values())costs.addAll(row.costs().keySet());
    for(int id:costs){require(before.getAsJsonObject("actualMaterials").get(Integer.toString(id)).getAsInt()==0,"Source cost stock must start empty");require(p.getInventory().addItem(id,4),"Real fixture source material grant failed: "+id);}
    windows(cwd,true);var after=snapshot(p);ordinaryUnchanged(before,after);require(before.get("credits").equals(after.get("credits")) && before.get("jades").equals(after.get("jades")),"Fixture must not grant wallets");
    var out=new JsonObject();out.add("before",before);out.add("after",after);out.add("explicitCostItemIDs",GSON.toJsonTree(costs));out.addProperty("explicitAmountPerCostItem",4);out.addProperty("productionMaterialAcquisitionVerified",false);
    var delta=new JsonObject();var a=before.getAsJsonObject("gameplay");var b=after.getAsJsonObject("gameplay");var keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());for(String key:keys)if(!Objects.equals(a.get(key),b.get(key))){var row=new JsonObject();row.add("before",a.get(key));row.add("after",b.get(key));delta.add(key,row);}out.add("completeFixtureGameplayDelta",delta);write(cwd.resolve("material-fixture-setup.json"),out);
  }
  private static void expected(JsonObject observed,JsonObject baseline,int last) {
    var expected=baseline.getAsJsonObject("actualMaterials").deepCopy();int credits=baseline.get("credits").getAsInt(),jades=baseline.get("jades").getAsInt();
    for(int id=301;id<=last;id++){var row=definitions().rows().get(id);row.costs().forEach((item,n)->expected.addProperty(item.toString(),expected.get(item.toString()).getAsInt()-n));for(var reward:row.rewards().entrySet()){if(reward.getKey()==1)jades+=reward.getValue();else if(reward.getKey()==2)credits+=reward.getValue();else expected.addProperty(reward.getKey().toString(),expected.get(reward.getKey().toString()).getAsInt()+reward.getValue());}}
    require(expected.equals(observed.get("actualMaterials")) && credits==observed.get("credits").getAsInt() && jades==observed.get("jades").getAsInt(),"Actual original source material debit or one-time full reward differs through "+last);
    var receipts=observed.getAsJsonObject("gameplay").getAsJsonObject("materialSubmission").getAsJsonObject("receipts");require(receipts.size()==last-300,"Wrong number of actual saved receipts");
    for(int id=301;id<=last;id++){var receipt=receipts.getAsJsonObject(Integer.toString(id));var row=definitions().rows().get(id);require(receipt.get("id").getAsInt()==id && receipt.get("module").getAsInt()==row.module() && receipt.get("phase").getAsString().equals("SUBMITTED") && receipt.get("reservedAt").getAsLong()>0 && receipt.get("costs").equals(GSON.toJsonTree(row.costs())),"Full persisted original submission receipt changed");var paid=observed.getAsJsonObject("gameplay").getAsJsonObject("rewards").getAsJsonObject(MaterialSubmitManager.rewardKey(id));require(paid!=null && paid.get("rewardId").getAsInt()==row.reward() && paid.get("granted").getAsBoolean(),"Actual source reward receipt not granted once");}
  }
  public static void execute(Player player,Path cwd,String operation) throws java.io.IOException {
    if(operation.equals("seed")){seed(player,cwd);return;}if(operation.equals("windows")){var a=snapshot(player);windows(cwd,false);require(a.equals(snapshot(player)),"Window restore changed actual Player/item state");return;}
    boolean before=operation.startsWith("before-");require(before || operation.startsWith("after-"),"Only fixed material fixture observations accepted");String mode=operation.substring(before?7:6);var observed=snapshot(player);require(player.getGameplay().getMaterialSubmission().valid(definitions()),"Persisted submission invalid");
    if(!before){var a=read(cwd.resolve("material-before-"+mode+".json"));if(mode.equals("first")||mode.equals("complete")){ordinaryUnchanged(a,observed);var seeded=read(cwd.resolve("material-fixture-setup.json")).getAsJsonObject("after");expected(observed,seeded,mode.equals("first")?301:307);}else require(a.equals(observed),"Readonly/rejected/idempotent material request changed complete live/BSON/stock/wallet: "+mode);}
    write(cwd.resolve("material-"+operation+".json"),observed);
  }
  private MaterialSubmitFixtureSupport(){}
}
