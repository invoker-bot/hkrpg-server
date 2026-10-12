package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Matched material submission request/response fields, without guessed state field 9. */
public final class MaterialSubmit46 {
  private static final Set<String> REQUESTS=Set.of("GetMaterialSubmitActivityDataCsReq",
      "SubmitMaterialSubmitActivityMaterialCsReq","TakeMaterialSubmitActivityRewardCsReq");
  public static boolean handle(GameSession session,int command,byte[] bytes)throws IOException {
    if(!session.getProtocol().experimental())return false;
    String name=REQUESTS.stream().filter(n->session.getProtocol().clientCommand(n)==command).findFirst().orElse(null);
    if(name==null)return false;if(session.getState()!=SessionState.ACTIVE)return true;
    synchronized(session.getPlayer()) {
      var type=session.getProtocol().clientSchema().message(name);
      var manager=new MaterialSubmitManager(session.getPlayer());
      var result=new MaterialSubmitManager.Result(Retcode.REQ_PARA_INVALID);int id=0;
      boolean query=name.equals("GetMaterialSubmitActivityDataCsReq");
      boolean submit=name.equals("SubmitMaterialSubmitActivityMaterialCsReq");
      if(query) {if(bytes.length==0)result=manager.query();}
      else if(NativeUnsignedActionWire.valid(type,bytes,0)) {
        var request=session.getProtocol().clientSchema().parse(name,bytes);
        id=(Integer)request.getField(type.findFieldByNumber(submit?8:7));
        if(id>0)result=submit?manager.submit(id):manager.take(id);
      }
      var response=builder(session,name.replace("CsReq","ScRsp"));set(response,"retcode",result.code().getVal());
      if(result.code()==Retcode.SUCC) {
        if(query)for(var entry:new TreeMap<>(result.state().receipts()).entrySet())
          response.addRepeatedField(response.getDescriptorForType().findFieldByNumber(8),state(session,entry.getKey(),entry.getValue()));
        else if(submit)response.setField(response.getDescriptorForType().findFieldByNumber(8),state(session,id,result.state().receipts().get(id)));
        else {
          response.setField(response.getDescriptorForType().findFieldByNumber(3),id);
          // The native success consumer caches a non-null ItemList for its later presentation.
          response.setField(response.getDescriptorForType().findFieldByNumber(15),items(session,result.items()));
        }
      }
      session.sendClientPacket(name.replace("CsReq","ScRsp"),response.build().toByteArray());return true;
    }
  }
  private static DynamicMessage state(GameSession session,int id,MaterialSubmitState.Receipt receipt) {
    var row=builder(session,"KPMAKCHCDMH");row.setField(row.getDescriptorForType().findFieldByNumber(15),id);
    row.setField(row.getDescriptorForType().findFieldByNumber(8),receipt.submitted());
    row.setField(row.getDescriptorForType().findFieldByNumber(13),session.getPlayer().getRewardLedger().granted(MaterialSubmitManager.rewardKey(id)));
    return row.build();
  }
  private MaterialSubmit46(){}
}
