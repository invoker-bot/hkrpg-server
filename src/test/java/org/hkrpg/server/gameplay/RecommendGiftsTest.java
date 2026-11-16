package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class RecommendGiftsTest {
  private static final long NOW=Instant.parse("2026-11-17T08:00:00Z").getEpochSecond();
  private static final Map<Integer,ItemExcel> previous=new HashMap<>();
  @BeforeAll static void setup() {
    assertTrue(RecommendGiftCatalog.load(Path.of("resources/4.6")));
    var gson=new Gson();
    for(int id:new int[]{2,3,101,102,201}) {
      previous.put(id,GameData.getItemExcelMap().get(id));
      String type=id==2 || id==3?"Virtual":"Material";
      GameData.getItemExcelMap().add(gson.fromJson("{\"ID\":"+id+",\"ItemMainType\":\""+type+"\",\"PileLimit\":99999}",ItemExcel.class));
    }
  }
  @AfterAll static void restore() {
    previous.forEach((id,item)-> { if(item==null) GameData.getItemExcelMap().keySet().remove(id); else GameData.getItemExcelMap().add(item); });
  }
  static final class Resident extends Player {
    final Inventory inventory=new Inventory(this);
    boolean fail;
    int failAt=-1, saves;
    long login;
    public Inventory getInventory(){return inventory;}
    public long getLastActiveTime(){return login;}
    public void save(){if(fail || ++saves==failAt)throw new IllegalStateException("fixture save failure");}
    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet){}
    public void onSync(emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify proto){}
    Resident(){
      for(int id:new int[]{101,102,201}) {
        var excel=GameData.getItemExcelMap().get(id);
        assertTrue(inventory.addItem(new GameItem(excel,1){public void save(){}}));
      }
    }
    int material(int id){return inventory.getMaterialByItemId(id).getCount()-1;}
  }
  @Test void eachGiftDeliversItsOriginalItemsOnceAndEnforcesItsLimit() {
    for(var offer:RecommendGiftCatalog.offers()) {
      var p=new Resident();
      var first=RecommendGifts.claim(p,offer.productId(),"first",NOW);
      assertEquals(Retcode.SUCC,first.code(),offer.productId()+":"+first.reason());
      assertEquals(offer.initialRewards(),first.items().stream().collect(java.util.stream.Collectors.toMap(GameItem::getItemId,GameItem::getCount)));
      assertEquals(Retcode.SUCC,RecommendGifts.claim(p,offer.productId(),"first",NOW).code());
      assertEquals(offer.max()==1?Retcode.REQ_PARA_INVALID:Retcode.SUCC,RecommendGifts.claim(p,offer.productId(),"second",NOW).code());
      assertEquals(Retcode.REQ_PARA_INVALID,RecommendGifts.claim(p,offer.productId(),"third",NOW).code());
      assertEquals(offer.max(),RecommendGifts.bought(p,offer));
      int factor=offer.max();
      assertEquals(offer.initialRewards().getOrDefault(2,0)*factor,p.getScoin());
      assertEquals(offer.initialRewards().getOrDefault(3,0)*factor,p.getMcoin());
      for(int id:new int[]{101,102,201}) assertEquals(offer.initialRewards().getOrDefault(id,0)*factor,p.material(id));
    }
  }
  @Test void laterRewardsRequireDistinctRealLoginsAndCannotBeReclaimed() {
    var p=new Resident(); p.login=NOW;
    assertEquals(Retcode.SUCC,RecommendGifts.claim(p,"rpgchnyear3tier5","daily",NOW).code());
    assertEquals(Retcode.REQ_PARA_INVALID,RecommendGifts.daily(p,NOW).code());
    long day5=NOW+4*86400;
    assertTrue(RecommendGifts.onLoginQuery(p,day5));
    assertEquals(0,p.getGameplay().getRecommendGifts().getUnlocked(),"elapsed days are not logins");
    p.login=day5;
    assertTrue(RecommendGifts.onLoginQuery(p,day5));
    assertTrue(RecommendGifts.onLoginQuery(p,day5));
    assertEquals(1,p.getGameplay().getRecommendGifts().getUnlocked());
    assertEquals(Retcode.SUCC,RecommendGifts.daily(p,day5).code());
    assertEquals(Retcode.REQ_PARA_INVALID,RecommendGifts.daily(p,day5).code());
    for(int day=5;day<=7;day++) {
      p.login=NOW+day*86400;
      assertTrue(RecommendGifts.onLoginQuery(p,p.login));
      assertEquals(Retcode.SUCC,RecommendGifts.daily(p,p.login).code());
    }
    assertEquals(4,p.getGameplay().getRecommendGifts().getClaimed());
    assertEquals(320,p.getMcoin());
    assertEquals(4,p.material(102));
    assertEquals(10,p.material(201));
    p.login=NOW+11*86400;
    assertEquals(Retcode.REQ_PARA_INVALID,RecommendGifts.daily(p,p.login).code());
  }
  @Test void failedReservationGrantsNothingAndBlocksAmbiguousRetries() {
    var p=new Resident();p.fail=true;
    assertEquals(Retcode.SYSTEM_BUSY,RecommendGifts.claim(p,"rpgchnyear3tier1","id",NOW).code());
    assertEquals(0,p.getScoin());assertEquals(0,p.material(102));
    p.fail=false;
    assertEquals(Retcode.SYSTEM_BUSY,RecommendGifts.claim(p,"rpgchnyear3tier1","id",NOW).code());
  }
  @Test void interruptedDeliveryRemainsReservedAndCompletedReceiptRecoversWithoutRegrant() throws Exception {
    String id=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("recover".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var pending=new Resident();
    pending.getGameplay().getRecommendGifts().getOrders().put(id,new RecommendGiftState.Order("rpgchnyear3tier1",NOW));
    var receipt=new GameplayState.Receipt(0);
    pending.getGameplay().getRewards().put("recommend-gift:"+id,receipt);
    assertEquals(Retcode.SYSTEM_BUSY,RecommendGifts.claim(pending,"rpgchnyear3tier1","recover",NOW).code());
    assertEquals(0,pending.material(102));
    receipt.complete();
    assertEquals(1,RecommendGifts.bought(pending,RecommendGiftCatalog.find("rpgchnyear3tier1")));
    assertTrue(pending.getGameplay().getRecommendGifts().getOrders().get(id).isCompleted());
    assertEquals(Retcode.SUCC,RecommendGifts.claim(pending,"rpgchnyear3tier1","recover",NOW).code());
    assertEquals(0,pending.material(102),"recovery must not deliver twice");
  }
  @Test void originalPriceTiersRemainNonzero() throws Exception {
    var prices=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(Path.of("src/main/resources/sdk-shop-prices.json"))).getAsJsonObject();
    assertEquals(600,prices.get("Tier_1").getAsInt());assertEquals(3000,prices.get("Tier_5").getAsInt());
    assertEquals(9800,prices.get("Tier_15").getAsInt());assertEquals(100,prices.get("Alternate_Tier_A").getAsInt());
  }
  @Test void insufficientCapacityDoesNotReserveAnyOrder() {
    var p=new Resident();
    p.inventory.getMaterialByItemId(102).setCount(99999);
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT,RecommendGifts.claim(p,"rpgchnyear3tier1","full",NOW).code());
    assertTrue(p.getGameplay().getRecommendGifts().getOrders().isEmpty());
    assertTrue(p.getGameplay().getRewards().isEmpty());
    assertEquals(0,p.getScoin());
  }
  @Test void reservationFailureBeforeDeliveryCanResumeAfterReload() {
    var p=new Resident(); p.failAt=2;
    assertEquals(Retcode.SYSTEM_BUSY,RecommendGifts.claim(p,"rpgchnyear3tier1","reserve",NOW).code());
    assertEquals(0,p.material(102)); assertTrue(p.getGameplay().getRewards().isEmpty());
    assertEquals(Retcode.SYSTEM_BUSY,RecommendGifts.claim(p,"rpgchnyear3tier1","reserve",NOW).code());
    var reloaded=new Resident();
    reloaded.getGameplay().getRecommendGifts().getOrders().putAll(p.getGameplay().getRecommendGifts().getOrders());
    assertEquals(Retcode.SUCC,RecommendGifts.claim(reloaded,"rpgchnyear3tier1","reserve",NOW).code());
    assertEquals(1,reloaded.material(102));
    assertEquals(Retcode.SUCC,RecommendGifts.claim(reloaded,"rpgchnyear3tier1","reserve",NOW).code());
    assertEquals(1,reloaded.material(102));
  }
  @Test void simultaneousRetriesDeliverExactlyOnce() throws Exception {
    var p=new Resident();
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var jobs=java.util.stream.IntStream.range(0,20).<java.util.concurrent.Callable<RecommendGifts.Result>>mapToObj(
          i -> () -> RecommendGifts.claim(p,"rpgchnyear3tier1","parallel",NOW)).toList();
      var results=pool.invokeAll(jobs,10,java.util.concurrent.TimeUnit.SECONDS);
      int granted=0;
      for(var f:results) { var r=f.get(); assertEquals(Retcode.SUCC,r.code()); if(!r.items().isEmpty())granted++; }
      assertEquals(1,granted);assertEquals(1,p.material(102));assertEquals(30000,p.getScoin());
    }
  }

}
