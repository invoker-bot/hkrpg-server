package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.BuyGoodsCsReqOuterClass.BuyGoodsCsReq;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Honey fe00bdb CmdShopType: native 1505 fields 1/3/9/13/15, response 1575. */
class ShopPurchase46Test {
  @ParameterizedTest
  @CsvSource({"999,999001,101,0", "999,999002,102,0", "1000,1000001,101,0", "1000,1000002,102,0",
      "999,999001,101,789", "999,999002,102,789", "1000,1000001,101,789", "1000,1000002,102,789"})
  void ticketExchangeRequestAndBothResponsesUseTheNativeFields(int shop,int goods,int item,int entity) throws Exception {
    var protocol=ProtocolBridge.forVersion("4.6.0");
    byte[] wire=join(scalar(1,shop),scalar(3,9),scalar(9,goods),scalar(13,item),
        entity==0?new byte[0]:scalar(15,entity));
    var converted=protocol.inbound(1505,wire);
    assertEquals(CmdId.BuyGoodsCsReq,converted.cmdId());
    var request=BuyGoodsCsReq.parseFrom(converted.body());
    assertEquals(shop,request.getShopId()); assertEquals(goods,request.getGoodsId());
    assertEquals(item,request.getItemId()); assertEquals(9,request.getGoodsNum());
    assertEquals(entity,request.getServerEntityId());

    var excel=new Gson().fromJson("{\"ID\":"+item+",\"ItemMainType\":\"Material\",\"PileLimit\":99999}",ItemExcel.class);
    var session=new ProgressionGates46Test.CheckedSession();
    ShopPurchase46.reply(session,request,List.of(new GameItem(excel,9)),Retcode.SUCC);
    assertEquals(1575,protocol.clientCommand(session.responseName));
    var message=session.response;
    assertEquals(0,field(message,"retcode")); assertEquals(9,field(message,"goods_buy_times"));
    assertEquals(shop,field(message,"shop_id")); assertEquals(goods,field(message,"goods_id"));
    var reward=(DynamicMessage)field(message,"return_item_list");
    var row=(DynamicMessage)((List<?>)field(reward,"item_list")).getFirst();
    assertEquals(item,field(row,"item_id")); assertEquals(9,field(row,"num"));

    ShopPurchase46.reply(session,request,null,Retcode.REQ_PARA_INVALID);
    assertEquals(1575,protocol.clientCommand(session.responseName));
    assertNotEquals(0,field(session.response,"retcode"));
    assertEquals(0,field(session.response,"goods_buy_times"));
    assertEquals(List.of(),field((DynamicMessage)field(session.response,"return_item_list"),"item_list"));
  }

  @Test void legacyEntityFieldKeepsItsOriginalMeaning() throws Exception {
    var source=BuyGoodsCsReq.newInstance().setShopId(999).setGoodsId(999001).setGoodsNum(9)
        .setItemId(101).setServerEntityId(789);
    var converted=ProtocolBridge.forVersion("4.2.0").inbound(CmdId.BuyGoodsCsReq,source.toByteArray());
    assertEquals(source,BuyGoodsCsReq.parseFrom(converted.body()));
  }

  @Test void invalidPurchasesReceiveAResponseWithoutDisconnecting() throws Exception {
    byte[] valid=join(scalar(1,999),scalar(3,9),scalar(9,999001),scalar(13,101));
    for (byte[] input : List.of(new byte[0], new byte[]{90,100}, new byte[16385],
        join(valid,scalar(3,9)), join(valid,scalar(4,123)),
        join(scalar(1,999),scalar(3,0),scalar(9,999001)),
        join(scalar(1,999),scalar(3,-1),scalar(9,999001)))) {
      var session=new ProgressionGates46Test.CheckedSession();
      assertTrue(VersionedPacketHandler.handle(session,1505,input));
      assertEquals("BuyGoodsScRsp",session.responseName);
      assertNotEquals(0,field(session.response,"retcode"));
      assertEquals(emu.lunarcore.server.packet.SessionState.ACTIVE,session.getState());
    }
  }

  private static Object field(DynamicMessage message,String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }
}
