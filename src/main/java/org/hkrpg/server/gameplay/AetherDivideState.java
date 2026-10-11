package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Saved owned spirits and six native lineups. A query cannot manufacture captures or rewards. */
@Entity(useDiscriminator=false)
@Getter
public final class AetherDivideState {
  private int version=1;
  private String sourceFingerprint="";
  private int currentSlot=1,trainer,infiniteMission;
  private Map<Integer,Spirit> spirits=new TreeMap<>();
  private Map<Integer,SkillCore> skillCores=new TreeMap<>();
  private Map<Integer,List<Integer>> lineups=new TreeMap<>();
  public AetherDivideState() { for(int slot=1;slot<=6;slot++) lineups.put(slot,new ArrayList<>(List.of(0,0,0,0))); }
  @Entity(useDiscriminator=false) @Getter
  public static final class Spirit {
    private int id,promotion,exp,fragmentCount,curSp;
    private Map<Integer,Integer> skills=new TreeMap<>();
    public Spirit() {}
    private Spirit(Spirit original) {
      id=original.id;promotion=original.promotion;exp=original.exp;fragmentCount=original.fragmentCount;curSp=original.curSp;
      skills=new TreeMap<>(original.skills);
    }
  }
  @Entity(useDiscriminator=false) @Getter
  public static final class SkillCore {
    private int itemId,count,dressedSpirit;
    public SkillCore() {}
    private SkillCore(SkillCore original) { itemId=original.itemId;count=original.count;dressedSpirit=original.dressedSpirit; }
  }
  AetherDivideState copy() {
    var result=new AetherDivideState();result.version=version;result.sourceFingerprint=sourceFingerprint;
    result.currentSlot=currentSlot;result.trainer=trainer;result.infiniteMission=infiniteMission;
    spirits.forEach((id,value)->result.spirits.put(id,new Spirit(value)));
    skillCores.forEach((id,value)->result.skillCores.put(id,new SkillCore(value)));
    lineups.forEach((slot,ids)->result.lineups.put(slot,new ArrayList<>(ids)));return result;
  }
  void lineup(int slot,List<Integer> ids,String fingerprint) { lineups.put(slot,new ArrayList<>(ids));sourceFingerprint=fingerprint; }
  void currentSlot(int slot,String fingerprint) { currentSlot=slot;sourceFingerprint=fingerprint; }
  public boolean valid(AetherDivideDefinitions definitions) {
    try {
      if(definitions==null || version!=1 || sourceFingerprint==null || spirits==null || skillCores==null || lineups==null
          || currentSlot<1 || currentSlot>6 || lineups.size()!=6 || trainer!=0 || infiniteMission!=0) return false;
      if(!definitions.fingerprint().equals(sourceFingerprint)) {
        if(!sourceFingerprint.isEmpty() || !spirits.isEmpty() || !skillCores.isEmpty() || currentSlot!=1) return false;
      }
      var dressed=new HashMap<Integer,Integer>();
      for(var entry:spirits.entrySet()) {
        var value=entry.getValue();var definition=definitions.spirits().get(entry.getKey());
        if(value==null || definition==null || value.id!=entry.getKey() || !definitions.promotion(value.id,value.promotion)
            || value.exp<0 || value.fragmentCount<0 || value.curSp<0 || value.curSp>definition.maxSp() || value.skills==null) return false;
        for(var skill:value.skills.entrySet()) {
          // The passive mutation interpreter has not been connected in this lane. Preserve only
          // source-known saved cores with distinct bounded native indices; never create a core.
          if(skill.getKey()==null || skill.getKey()<0 || skill.getKey()>3 || skill.getValue()==null
              || !definitions.skills().contains(skill.getValue()) || dressed.putIfAbsent(skill.getValue(),value.id)!=null) return false;
        }
      }
      for(var entry:skillCores.entrySet()) {
        var value=entry.getValue();
        if(value==null || !definitions.skills().contains(entry.getKey()) || value.itemId!=entry.getKey() || value.count<=0
            || value.dressedSpirit!=dressed.getOrDefault(value.itemId,0)) return false;
      }
      if(!skillCores.keySet().containsAll(dressed.keySet())) return false;
      for(int slot=1;slot<=6;slot++) {
        var ids=lineups.get(slot);
        if(ids==null || ids.size()!=4) return false;
        var seen=new HashSet<Integer>();
        for(Integer id:ids)
          if(id==null || id<0 || id!=0 && (!spirits.containsKey(id) || !seen.add(id))) return false;
      }
      return true;
    } catch(RuntimeException invalid) { return false; }
  }
}
