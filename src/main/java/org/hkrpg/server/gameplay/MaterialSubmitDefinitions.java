package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Complete retained 4.6 material orders, bound to their original panels and modules. */
public final class MaterialSubmitDefinitions {
  public record Submitter(int id, int module, int mission, int reward, Map<Integer,Integer> costs,
      Map<Integer,Integer> rewards) {}
  private static final Map<String,String> INPUTS = Map.of(
      "MaterialSubmitter","dc29a827bccaaba41809c3d632c183e79cd7e5cb9225156038b8bfdbc7aae4bb",
      "MaterialSubmitterGroup","1d321fb598f7543e35d8f61001d25366017d998f2d37d4b8f9bdb1ea45ddbe31",
      "ActivityConfig","a94d11500502e07789cc38a28211fb3f6cef69400de1be988cc6b8d9634afd3e",
      "ActivityPanel","80a899976fbc5d61fe81483b9e5dd35679c96eed7eb3ef6285c008b475fbc1e4",
      "RewardData","f09132dbc709ce90de0703aedc6cc816dd5f00f9d79cf433dc7d38f20e6a1619",
      "ItemConfig","1f334007198e21f63886acdb6c367ea5c5fa314a5e4a2a71284dee89c8fe2048");
  private static final Map<Integer,MaterialSubmitDefinitions> VERIFIED = new java.util.concurrent.ConcurrentHashMap<>();
  private final int activityId,firstId;
  private final Map<Integer,Submitter> rows;
  private final Map<Integer,JsonObject> items;
  private final String fingerprint;
  private MaterialSubmitDefinitions(int activityId,int firstId,Map<Integer,Submitter> rows,Map<Integer,JsonObject> items,String fingerprint) {
    this.activityId=activityId;this.firstId=firstId;this.rows=Map.copyOf(rows);this.items=Map.copyOf(items);this.fingerprint=fingerprint;
  }
  public int activityId(){return activityId;}
  public int firstId(){return firstId;}
  public int lastId(){return firstId+6;}
  public Map<Integer,Submitter> rows(){return rows;}
  public String fingerprint(){return fingerprint;}
  private static int number(JsonObject row,String key) {
    var value=row.get(key);
    if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[1-9][0-9]*")) throw new IllegalArgumentException(key);
    return value.getAsInt();
  }
  private static JsonObject unique(JsonArray table,String key,int id) {
    var result=table.asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row->row.has(key)&&row.get(key).getAsInt()==id).toList();
    if(result.size()!=1) throw new IllegalArgumentException(key+":"+id);return result.getFirst();
  }
  public static MaterialSubmitDefinitions load(Path root) {
    return load(root,30014);
  }
  public static MaterialSubmitDefinitions load(Path root,int activityId) {
    if(activityId!=30005 && activityId!=30011 && activityId!=30014)return null;
    int firstId=activityId==30005?1:activityId==30011?201:301;
    try {
      var rawTables=new TreeMap<String,byte[]>();var key=new StringBuilder("material-submit-"+activityId+"-4.6-v1\n");
      for(var input:new TreeMap<>(INPUTS).entrySet()) {
        String path="ExcelOutput/"+input.getKey()+".json";
        long size=Files.size(root.resolve(path));if(size<=0 || size>4*1024*1024) return null;
        byte[] raw=Files.readAllBytes(root.resolve(path));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if(!hash.equals(input.getValue())) return null;
        key.append(path).append(':').append(hash).append('\n');rawTables.put(input.getKey(),raw);
      }
      String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(key.toString().getBytes(StandardCharsets.UTF_8)));
      var cached=VERIFIED.get(activityId);if(cached!=null && cached.fingerprint.equals(fingerprint)) return cached;
      var tables=new HashMap<String,JsonArray>();rawTables.forEach((name,raw)->
          tables.put(name,JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonArray()));
      if(tables.get("MaterialSubmitter").size()!=28 || tables.get("MaterialSubmitterGroup").size()!=1
          || tables.get("ActivityConfig").size()!=525 || tables.get("ActivityPanel").size()!=270
          || tables.get("RewardData").size()!=9515 || tables.get("ItemConfig").size()!=2941) return null;
      if(activityId==30014){
        var group=unique(tables.get("MaterialSubmitterGroup"),"ActivityID",30014);
        if(!group.get("Type").getAsString().equals("AmphoreusCurio")
            || !group.getAsJsonArray("SubmitterIDList").equals(JsonParser.parseString("[301,302,303,304,305,306,307]"))) return null;
      }
      var modules=new JsonArray();for(int offset=1;offset<=(activityId==30005?8:7);offset++)modules.add(activityId*100+offset);
      var activity=unique(tables.get("ActivityConfig"),"ActivityID",activityId);
      if(!activity.getAsJsonArray("ActivityModuleIDList").equals(modules)) return null;
      if(activityId==30005) {
        // This historical activity has neither a retained panel nor a group in 4.6.
        // Reading its orders does not make its original client submission UI available.
        if(activity.has("ActivityPanelID")
            || tables.get("ActivityPanel").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(row->row.has("PanelID") && row.get("PanelID").getAsInt()==activityId)
            || tables.get("MaterialSubmitterGroup").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(row->row.has("ActivityID") && row.get("ActivityID").getAsInt()==activityId)) return null;
      } else {
        if(number(activity,"ActivityPanelID")!=activityId) return null;
        var panel=unique(tables.get("ActivityPanel"),"PanelID",activityId);
        if(!panel.get("UnlockConditions").getAsString().equals("[PlayerLevel:21]")) return null;
      }
      var rows=new TreeMap<Integer,Submitter>();var items=new TreeMap<Integer,JsonObject>();
      for(int id=firstId;id<firstId+7;id++) {
        var row=unique(tables.get("MaterialSubmitter"),"ID",id);var costs=new TreeMap<Integer,Integer>();
        if(number(row,"ActivityModuleID")!=activityId*100+id-firstId+1)return null;
        for(var cost:row.getAsJsonArray("MaterialList")) {
          var value=cost.getAsJsonObject();
          if(costs.putIfAbsent(number(value,"ItemID"),number(value,"ItemNum"))!=null) return null;
        }
        if(costs.size()!=(activityId==30005?3:2)) return null;
        var reward=unique(tables.get("RewardData"),"RewardID",number(row,"RewardID"));
        var rewards=new TreeMap<Integer,Integer>();if(reward.has("Hcoin"))rewards.put(1,number(reward,"Hcoin"));
        for(int slot=1;slot<=5;slot++) if(reward.has("ItemID_"+slot))
          if(rewards.putIfAbsent(number(reward,"ItemID_"+slot),number(reward,"Count_"+slot))!=null) return null;
        if(rewards.isEmpty()) return null;
        var submitter=new Submitter(id,number(row,"ActivityModuleID"),number(row,"MissionID"),number(row,"RewardID"),Map.copyOf(costs),Map.copyOf(rewards));
        rows.put(id,submitter);
        var ids=new HashSet<Integer>(costs.keySet());ids.addAll(rewards.keySet());
        for(int item:ids) items.put(item,unique(tables.get("ItemConfig"),"ID",item));
      }
      var value=new MaterialSubmitDefinitions(activityId,firstId,rows,items,fingerprint);VERIFIED.put(activityId,value);return value;
    } catch(Exception invalid){return null;}
  }
  /** Loaded reward/item objects must agree with the independently frozen disk sources. */
  public boolean loaded(Submitter row) {
    var reward=GameData.getRewardExcelMap().get(row.reward());
    if(reward==null || reward.getRewards()==null || reward.getRewards().size()!=row.rewards().size()) return false;
    for(var amount:row.rewards().entrySet()) if(reward.getRewards().get(amount.getKey().intValue())!=amount.getValue()) return false;
    var ids=new HashSet<Integer>(row.costs().keySet());ids.addAll(row.rewards().keySet());
    for(int id:ids) {
      var loaded=GameData.getItemExcelMap().get(id);var original=items.get(id);
      if(loaded==null || loaded.getItemMainType()==null || loaded.getId()!=id
          || !loaded.getItemMainType().name().equals(original.get("ItemMainType").getAsString())
          || loaded.getPileLimit()!=number(original,"PileLimit")) return false;
    }
    return true;
  }
}
