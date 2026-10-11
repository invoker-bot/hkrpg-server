package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Matched native 4.6 roster snapshots and six four-place lineups; other activities stay separate. */
public final class AetherDivide46 {
  private static final Set<String> REQUESTS=Set.of("GetAetherDivideInfoCsReq","SetAetherDivideLineUpCsReq","SwitchAetherDivideLineUpSlotCsReq");
  public static boolean handle(GameSession session,int command,byte[] bytes) throws IOException {
    if(!session.getProtocol().experimental()) return false;
    String name=REQUESTS.stream().filter(value->session.getProtocol().clientCommand(value)==command).findFirst().orElse(null);
    if(name==null) return false;if(session.getState()!=SessionState.ACTIVE) return true;
    synchronized(session.getPlayer()) {
      var response=builder(session,name.replace("CsReq","ScRsp"));
      var result=new AetherDivideManager.Result(Retcode.REQ_PARA_INVALID,null,0);
      var manager=new AetherDivideManager(session.getPlayer());
      var type=session.getProtocol().clientSchema().message(name);
      if(name.equals("GetAetherDivideInfoCsReq")) {
        if(bytes.length==0) result=manager.snapshot();
      } else if(NativeUnsignedActionWire.valid(type,bytes,0)) {
        var req=session.getProtocol().clientSchema().parse(name,bytes);
        if(name.equals("SwitchAetherDivideLineUpSlotCsReq")) {
          var field=type.findFieldByNumber(4);
          if(req.hasField(field)) result=manager.switchSlot((Integer)req.getField(field));
        } else {
          var field=type.findFieldByNumber(11);
          if(req.hasField(field)) {
            var row=(DynamicMessage)req.getField(field);
            @SuppressWarnings("unchecked") var ids=(List<Integer>)row.getField(row.getDescriptorForType().findFieldByNumber(10));
            int slot=(Integer)row.getField(row.getDescriptorForType().findFieldByNumber(2));
            result=manager.lineup(slot,ids);
          }
        }
      }
      set(response,"retcode",result.code().getVal());
      if(result.code()==Retcode.SUCC) {
        var state=result.state();
        if(name.equals("GetAetherDivideInfoCsReq")) {
          for(int slot=1;slot<=6;slot++) addByNumber(response,4,lineup(session,state,slot));
          setByNumber(response,10,state.getCurrentSlot());
          setByNumber(response,12,state.getInfiniteMission());setByNumber(response,13,state.getTrainer());
          for(var core:result.stock().cores().values()) {
            var row=builder(session,"DMGDLEHEDFB");setByNumber(row,6,core.itemId());
            setByNumber(row,8,core.dressedSpirit());setByNumber(row,13,core.count());addByNumber(response,14,row.build());
          }
          // This immutable definition belongs to this request's complete SHA validation.
          // Every later request reads and checks its own resource bytes again.
          var definitions=result.definitions();
          for(var spirit:new TreeMap<>(state.getSpirits()).values()) {
            var row=builder(session,"GHBICGMEFPL");setByNumber(row,10,spirit.getId());setByNumber(row,3,spirit.getPromotion());
            // The native inventory consumer treats field 4 as current fragment stock. It is
            // independent of the saved roster and cannot create an owned spirit.
            setByNumber(row,4,result.stock().fragments().get(spirit.getId()));setByNumber(row,11,spirit.getExp());
            var sp=builder(session,"SpBarInfo");set(sp,"cur_sp",spirit.getCurSp());set(sp,"max_sp",definitions.spirits().get(spirit.getId()).maxSp());
            setByNumber(row,6,sp.build());
            for(var entry:new TreeMap<>(spirit.getSkills()).entrySet()) {
              var skill=builder(session,"POPJNEHGMIH");setByNumber(skill,2,entry.getValue());setByNumber(skill,5,entry.getKey());
              addByNumber(row,13,skill.build());
            }
            addByNumber(response,15,row.build());
          }
        } else if(name.equals("SetAetherDivideLineUpCsReq")) setByNumber(response,1,lineup(session,state,result.slot()));
        else setByNumber(response,12,result.slot());
      }
      session.sendClientPacket(name.replace("CsReq","ScRsp"),response.build().toByteArray());return true;
    }
  }
  private static DynamicMessage lineup(GameSession session,AetherDivideState state,int slot) {
    var row=builder(session,"OCCAABPFBLF");setByNumber(row,2,slot);
    for(int id:state.getLineups().get(slot)) addByNumber(row,10,id);return row.build();
  }
  private static void setByNumber(DynamicMessage.Builder row,int number,Object value) {
    row.setField(row.getDescriptorForType().findFieldByNumber(number),value);
  }
  private static void addByNumber(DynamicMessage.Builder row,int number,Object value) {
    row.addRepeatedField(row.getDescriptorForType().findFieldByNumber(number),value);
  }
  private AetherDivide46() {}
}
