package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.List;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.gameplay.RecommendGiftCatalog;
import org.hkrpg.server.gameplay.RecommendGiftState;
import org.hkrpg.server.gameplay.RecommendGifts;

/** The recommended gifts use native 4.6 packets, never an external payment order. */
final class RecommendGift46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (command != 8370 && command != 8377) return false;
    if (session.getState()!=SessionState.ACTIVE) return true;
    var player=session.getPlayer();
    boolean identity=player!=null && player.getSession()==session;
    long now=System.currentTimeMillis()/1000;
    if (command==8370) {
      var response=Gameplay46.builder(session,"GetRechargeGiftInfoScRsp");
      if (!identity || bytes==null || bytes.length!=0 || !RecommendGifts.onLoginQuery(player,now)) {
        Gameplay46.set(response,"retcode",Retcode.REQ_PARA_INVALID.getVal());
        GameDiagnostics.outcome(session,"GetRechargeGiftInfoCsReq",0,Retcode.REQ_PARA_INVALID,"recommend-gift-query-rejected","invalid-request-or-state");
      } else {
        var state=player.getGameplay().getRecommendGifts();
        if (state.getDailyStart()>0) {
          var field=response.getDescriptorForType().findFieldByName("OFIHOCCHNCN");
          response.addRepeatedField(field,status(field.getMessageType(),state));
        }
      }
      session.sendClientPacket("GetRechargeGiftInfoScRsp",response.build().toByteArray());
      return true;
    }
    var response=Gameplay46.builder(session,"TakeRechargeGiftRewardScRsp");
    RecommendGifts.Result result=null;
    try {
      if (identity && bytes!=null && bytes.length<=128) {
        var req=session.getProtocol().clientSchema().parse("TakeRechargeGiftRewardCsReq",bytes);
        int type=(int)req.getField(req.getDescriptorForType().findFieldByName("gift_type"));
        if (req.getUnknownFields().asMap().isEmpty() && type==16) result=RecommendGifts.daily(player,now);
      }
    } catch (java.io.IOException | RuntimeException ignored) {}
    if (result==null) result=new RecommendGifts.Result(Retcode.REQ_PARA_INVALID,List.of(),"invalid-request");
    if (result.code()!=Retcode.SUCC) {
      Gameplay46.set(response,"retcode",result.code().getVal());
      GameDiagnostics.outcome(session,"TakeRechargeGiftRewardCsReq",16,result.code(),"recommend-gift-daily-rejected",result.reason());
    } else {
      var reward=response.getDescriptorForType().findFieldByName("reward");
      response.setField(reward,itemList(reward.getMessageType(),result.items()));
      var gift=response.getDescriptorForType().findFieldByName("KJBDNIIBGNE");
      response.setField(gift,status(gift.getMessageType(),player.getGameplay().getRecommendGifts()));
    }
    session.sendClientPacket("TakeRechargeGiftRewardScRsp",response.build().toByteArray());
    return true;
  }
  static boolean claimFromSession(GameSession session,String product) {
    synchronized(session) {
      var player=session.getPlayer();
      String request=session.localDreamOfferRequest(product);
      var result=player==null || player.getSession()!=session || request==null
          ? new RecommendGifts.Result(Retcode.REQ_PARA_INVALID,List.of(),"missing-session-offer")
          : RecommendGifts.claim(player,product,request,System.currentTimeMillis()/1000);
      var response=Gameplay46.builder(session,"CancelAutoConversionCacheScRsp");
      if (result.code()!=Retcode.SUCC) {
        Gameplay46.set(response,"retcode",result.code().getVal());
        GameDiagnostics.outcome(session,"CancelAutoConversionCacheCsReq",0,result.code(),"recommend-gift-claim-rejected",result.reason());
      } else if (!result.items().isEmpty()) {
        try { notifyGrant(player,product,result.items()); }
        catch(RuntimeException failed) { GameDiagnostics.outcome(session,"CancelAutoConversionCacheCsReq",0,Retcode.SYSTEM_BUSY,"recommend-gift-notification-failed",""); }
      }
      session.sendClientPacket("CancelAutoConversionCacheScRsp",response.build().toByteArray());
      return true;
    }
  }
  private static void notifyGrant(Player player,String product,List<GameItem> items) {
    var session=player.getSession();
    var response=Gameplay46.builder(session,"RechargeSuccNotify");
    Gameplay46.set(response,"product_id",product);
    Gameplay46.set(response,"price_tier",RecommendGiftCatalog.find(product).tier());
    var field=response.getDescriptorForType().findFieldByName("item_list");
    response.setField(field,itemList(field.getMessageType(),items));
    session.markLocalRechargeNotice(product);
    session.sendClientPacket("RechargeSuccNotify",response.build().toByteArray());
  }
  private static DynamicMessage itemList(com.google.protobuf.Descriptors.Descriptor descriptor,List<GameItem> items) {
    var list=DynamicMessage.newBuilder(descriptor);
    var entries=descriptor.findFieldByName("item_list");
    for (var item:items) {
      var row=DynamicMessage.newBuilder(entries.getMessageType());
      Gameplay46.set(row,"item_id",item.getItemId());
      Gameplay46.set(row,"num",item.getCount());
      list.addRepeatedField(entries,row.build());
    }
    return list.build();
  }
  private static DynamicMessage status(com.google.protobuf.Descriptors.Descriptor descriptor,RecommendGiftState state) {
    var gift=DynamicMessage.newBuilder(descriptor);
    Gameplay46.set(gift,"gift_type",16);
    Gameplay46.set(gift,"start_time",state.getDailyStart());
    Gameplay46.set(gift,"end_time",RecommendGifts.expiry(state));
    var field=descriptor.findFieldByName("status_list");
    for (int i=0; i<=4; i++) {
      var row=DynamicMessage.newBuilder(field.getMessageType());
      Gameplay46.set(row,"index",i==0?0:i+1);
      var status=row.getDescriptorForType().findFieldByName("status");
      int value=i==0 || i<=state.getClaimed()?1:i<=state.getUnlocked()?2:0;
      row.setField(status,status.getEnumType().findValueByNumber(value));
      gift.addRepeatedField(field,row.build());
    }
    return gift.build();
  }
  private RecommendGift46() {}
}
