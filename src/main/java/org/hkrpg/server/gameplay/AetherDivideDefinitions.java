package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Complete fixed 4.6 resources; references and the installed client are never runtime inputs. */
public final class AetherDivideDefinitions {
  public record Spirit(int id, int maxPromotion, int maxSp, int fragmentItem) {}
  private static final Map<String, List<String>> INPUTS = Map.of(
      "AetherDivideSpirit", List.of("97f24d29322476dbf401920a8ed7c838b028e3b011745c0b20afea78abd9b7e4","ebbedb90927924abf157a18e435b5f093d168ff857302af05c6622dcac00bab3"),
      "AetherDivideSpiritPromotion", List.of("c26cb1f2cbd1a856d3c04e7eb6d048c5ce39001d08414819e65ccf2c75082c62","95a3fc16f5208100e456b57b085b5a2bc1d69ea89a722197f698293e5d04b360"),
      "AetherDividePassiveSkill", List.of("178971bd12729855d69da37f8cde0fa9711661c4fcf2313d83ab2f8bd2398f1d","21a73621f2ca68a4bd8f3d0317c8eeeb1164d24598cc1a9284fd4644defc4d58"),
      "AetherDivideTrainerLevel", List.of("3a46224091d58598d9398381fe443a743efc56b195207048cc71842515c88718","0084914822e13bba710f113372ee8e5da7c9c1a82d0fe1389269d5d1880df698"),
      "AetherDivideConstCommon", List.of("30efc5c2466f119a03325d11ffbbe888190aab32ed146f3cc9d885a840260e28","804e180e4195d24479d70ec2522c692131edc9398c13ed32156759863bca8359"),
      "AetherDivideChallengeList", List.of("afd828eac902fac43c33e59e3b6df1fbc4febd427373d177a83db05f42fcf09c","003442617c27fe0ac9eeab456314bf850634b253a4c3148e47c6e8f6a5d44e6d"));
  private final Map<Integer, Spirit> spirits;
  private final Map<Integer, Set<Integer>> promotions;
  private final Set<Integer> skills;
  private final Map<Integer,List<String>> spiritSlotTypes;
  private final Map<Integer,Map<Integer,Integer>> slotUnlocks;
  private final Map<Integer,String> coreTypes;
  private final String fingerprint;
  private AetherDivideDefinitions(Map<Integer,Spirit> spirits, Map<Integer,Set<Integer>> promotions,
      Set<Integer> skills, String fingerprint,Map<Integer,List<String>> spiritSlotTypes,
      Map<Integer,Map<Integer,Integer>> slotUnlocks,Map<Integer,String> coreTypes) {
    this.spirits=Collections.unmodifiableMap(new TreeMap<>(spirits));
    var levels=new TreeMap<Integer,Set<Integer>>(); promotions.forEach((id,values)->levels.put(id,Set.copyOf(values)));
    this.promotions=Collections.unmodifiableMap(levels); this.skills=Set.copyOf(skills); this.fingerprint=fingerprint;
    var slots=new TreeMap<Integer,List<String>>();spiritSlotTypes.forEach((id,types)->slots.put(id,List.copyOf(types)));
    this.spiritSlotTypes=Collections.unmodifiableMap(slots);
    var unlocks=new TreeMap<Integer,Map<Integer,Integer>>();slotUnlocks.forEach((id,values)->unlocks.put(id,Map.copyOf(values)));
    this.slotUnlocks=Collections.unmodifiableMap(unlocks);this.coreTypes=Map.copyOf(coreTypes);
  }
  public Map<Integer,Spirit> spirits() { return spirits; }
  public Set<Integer> skills() { return skills; }
  public String fingerprint() { return fingerprint; }
  public boolean promotion(int id,int value) { return promotions.getOrDefault(id,Set.of()).contains(value); }
  /** Native slots are 1..original list length; only explicit reached Promotion.Slot rows open them. */
  public boolean canEquip(int avatar,int promotion,int slot,int item) {
    var types=spiritSlotTypes.get(avatar);var unlock=slotUnlocks.getOrDefault(avatar,Map.of()).get(slot);
    return types!=null && slot>=1 && slot<=types.size() && unlock!=null && unlock>0 && promotion>=unlock
        && types.get(slot-1).equals(coreTypes.get(item));
  }
  private static String skillType(JsonObject row) {
    var value=row.get("PassiveSkillType");
    // The full native AetherDividePassiveSkillRow constructor is RET. A missing enum field
    // retains zero; independently decoded AetherPassiveSkillType.Defend is exactly zero.
    if(value==null)return "Defend";
    if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("PassiveSkillType");
    return value.getAsString();
  }
  private static int number(JsonObject row,String name) {
    var value=row.get(name);
    if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[0-9]+")) throw new IllegalArgumentException(name);
    return value.getAsInt();
  }
  public static AetherDivideDefinitions load(Path root) {
    try {
      var tables=new TreeMap<String,JsonArray>();
      var fingerprint=new StringBuilder("aether-divide-roster-4.6-v1\n");
      for(var entry:new TreeMap<>(INPUTS).entrySet()) {
        var name="ExcelOutput/"+entry.getKey()+".json";var path=root.resolve(name);
        long size=Files.size(path);if(size<=0 || size>4*1024*1024) return null;
        byte[] raw=Files.readAllBytes(path);
        var sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if(!entry.getValue().contains(sha)) return null;
        tables.put(entry.getKey(),JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonArray());
        // Both known serializations contain the complete same objects. The immutable lease names
        // their fixed Git originals, not an arbitrary reformatted or later resource version.
        fingerprint.append(name).append(':').append(entry.getValue().get(1)).append('\n');
      }
      var spirits=new TreeMap<Integer,Spirit>();
      var slotTypes=new TreeMap<Integer,List<String>>();var knownTypes=Set.of("Defend","Assist","Storm","Trick");
      for(var element:tables.get("AetherDivideSpirit")) {
        var row=element.getAsJsonObject();int id=number(row,"AvatarID");
        int maxSp=number(row.getAsJsonObject("SPMax"),"Value");
        var spirit=new Spirit(id,number(row,"MaxPromotion"),Math.multiplyExact(maxSp,1000),number(row,"ExpItemID"));
        if(id<=0 || maxSp<=0 || spirit.maxPromotion()<=0 || spirits.putIfAbsent(id,spirit)!=null) return null;
        var types=new ArrayList<String>();
        for(var type:row.getAsJsonArray("PassiveSkillSlotList")) {
          if(!type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString() || !knownTypes.contains(type.getAsString()))return null;
          types.add(type.getAsString());
        }
        if(types.isEmpty() || types.size()>4)return null;slotTypes.put(id,types);
      }
      var promotions=new TreeMap<Integer,Set<Integer>>();
      var unlocks=new TreeMap<Integer,Map<Integer,Integer>>();
      for(var element:tables.get("AetherDivideSpiritPromotion")) {
        var row=element.getAsJsonObject();int id=number(row,"AvatarID"),level=number(row,"Promotion");
        if(!spirits.containsKey(id) || !promotions.computeIfAbsent(id,key->new TreeSet<>()).add(level)) return null;
        if(row.has("Slot")) {
          int slot=number(row,"Slot");
          if(slot!=0 && (slot>slotTypes.get(id).size() || unlocks.computeIfAbsent(id,key->new TreeMap<>()).putIfAbsent(slot,level)!=null))return null;
        }
      }
      var skills=new TreeSet<Integer>();
      var coreTypes=new TreeMap<Integer,String>();
      for(var element:tables.get("AetherDividePassiveSkill")) {
        var row=element.getAsJsonObject();int id=number(row,"ItemID");String type=skillType(row);
        if(!skills.add(id) || !knownTypes.contains(type))return null;coreTypes.put(id,type);
      }
      if(spirits.size()!=19 || skills.size()!=32 || tables.get("AetherDivideSpiritPromotion").size()!=114
          || tables.get("AetherDivideTrainerLevel").size()!=5 || tables.get("AetherDivideConstCommon").size()!=5
          || tables.get("AetherDivideChallengeList").size()!=42) return null;
      for(var spirit:spirits.values())
        for(int level=1;level<=spirit.maxPromotion();level++)
          if(!promotions.getOrDefault(spirit.id(),Set.of()).contains(level)) return null;
      var lease=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
      return new AetherDivideDefinitions(spirits,promotions,skills,lease,slotTypes,unlocks,coreTypes);
    } catch(Exception invalid) { return null; }
  }
}
