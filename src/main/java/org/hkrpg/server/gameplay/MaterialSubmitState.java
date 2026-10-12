package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

@Entity(useDiscriminator=false)
public final class MaterialSubmitState {
  private int version=1;
  private String fingerprint="";
  private Map<Integer,Receipt> receipts=new TreeMap<>();
  @Entity(useDiscriminator=false)
  public static final class Receipt {
    private int id;
    private int module;
    private Map<Integer,Integer> costs;
    private String phase;
    private long reservedAt;
    private Receipt(){}
    Receipt(MaterialSubmitDefinitions.Submitter row,long at) {
      id=row.id();module=row.module();costs=new TreeMap<>(row.costs());phase="RESERVED";reservedAt=at;
    }
    Receipt copy(){var next=new Receipt();next.id=id;next.module=module;next.costs=costs==null?null:new TreeMap<>(costs);next.phase=phase;next.reservedAt=reservedAt;return next;}
    public boolean submitted(){return "SUBMITTED".equals(phase);}
    void complete(){phase="SUBMITTED";}
    boolean valid(MaterialSubmitDefinitions.Submitter row) {
      return row!=null && id==row.id() && module==row.module() && costs!=null && costs.equals(row.costs())
          && reservedAt>0 && phase!=null && Set.of("RESERVED","SUBMITTED").contains(phase);
    }
  }
  public Map<Integer,Receipt> receipts(){return receipts==null?null:Collections.unmodifiableMap(receipts);}
  public boolean valid(MaterialSubmitDefinitions definitions) {
    if(version!=1 || fingerprint==null || receipts==null || receipts.size()>7
        || !(receipts.isEmpty()&&fingerprint.isEmpty() || fingerprint.equals(definitions.fingerprint()))) return false;
    for(var entry:receipts.entrySet()) {
      if(entry.getKey()==null || entry.getValue()==null || !entry.getValue().valid(definitions.rows().get(entry.getKey()))) return false;
      if(entry.getKey()>301) {var previous=receipts.get(entry.getKey()-1);if(previous==null || !previous.submitted())return false;}
    }
    return true;
  }
  MaterialSubmitState copy() {
    var next=new MaterialSubmitState();next.version=version;next.fingerprint=fingerprint;
    receipts.forEach((id,receipt)->next.receipts.put(id,receipt.copy()));return next;
  }
  void reserve(MaterialSubmitDefinitions definitions,MaterialSubmitDefinitions.Submitter row,long at) {
    fingerprint=definitions.fingerprint();receipts.put(row.id(),new Receipt(row,at));
  }
  void complete(int id){receipts.get(id).complete();}
}
