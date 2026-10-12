package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Exact complete 4.6 originals. Only the explicit Amphoreus group is currently implemented. */
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
  private static volatile MaterialSubmitDefinitions verified;
  private final Map<Integer,Submitter> rows;
  private final Map<Integer,JsonObject> items;
  private final String fingerprint;
  private MaterialSubmitDefinitions(Map<Integer,Submitter> rows,Map<Integer,JsonObject> items,String fingerprint) {
    this.rows=Map.copyOf(rows);this.items=Map.copyOf(items);this.fingerprint=fingerprint;
  }
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
    try {
      var rawTables=new TreeMap<String,byte[]>();var key=new StringBuilder("material-submit-30014-4.6-v1\n");
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
      var cached=verified;if(cached!=null && cached.fingerprint.equals(fingerprint)) return cached;
      var tables=new HashMap<String,JsonArray>();rawTables.forEach((name,raw)->
          tables.put(name,JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonArray()));
      if(tables.get("MaterialSubmitter").size()!=28 || tables.get("MaterialSubmitterGroup").size()!=1
          || tables.get("ActivityConfig").size()!=525 || tables.get("ActivityPanel").size()!=270
          || tables.get("RewardData").size()!=9515 || tables.get("ItemConfig").size()!=2941) return null;
      var group=unique(tables.get("MaterialSubmitterGroup"),"ActivityID",30014);
      if(!group.get("Type").getAsString().equals("AmphoreusCurio")
          || !group.getAsJsonArray("SubmitterIDList").equals(JsonParser.parseString("[301,302,303,304,305,306,307]"))) return null;
      var activity=unique(tables.get("ActivityConfig"),"ActivityID",30014);
      if(number(activity,"ActivityPanelID")!=30014
          || !activity.getAsJsonArray("ActivityModuleIDList").equals(JsonParser.parseString("[3001401,3001402,3001403,3001404,3001405,3001406,3001407]"))) return null;
      var panel=unique(tables.get("ActivityPanel"),"PanelID",30014);
      if(!panel.get("UnlockConditions").getAsString().equals("[PlayerLevel:21]")) return null;
      var rows=new TreeMap<Integer,Submitter>();var items=new TreeMap<Integer,JsonObject>();
      for(int id=301;id<=307;id++) {
        var row=unique(tables.get("MaterialSubmitter"),"ID",id);var costs=new TreeMap<Integer,Integer>();
        for(var cost:row.getAsJsonArray("MaterialList")) {
          var value=cost.getAsJsonObject();
          if(costs.putIfAbsent(number(value,"ItemID"),number(value,"ItemNum"))!=null) return null;
        }
        if(costs.size()!=2) return null;
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
      var value=new MaterialSubmitDefinitions(rows,items,fingerprint);verified=value;return value;
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
