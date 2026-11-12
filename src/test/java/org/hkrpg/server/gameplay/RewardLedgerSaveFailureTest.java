package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RewardLedgerSaveFailureTest {
  static final int ITEM=198869199, REWARD=198869198;
  static final String KEY="audit-reward:198869198";
  private ItemExcel oldItem;
  private RewardExcel oldReward;
  @TempDir Path directory;
  static class Actor extends Player {
    int saves, failAt, attempts, delivered;
    boolean partial, throwDelivery;
    Runnable beforeSave, afterSave, afterGrant;
    GameplayState durable;
    final Inventory inventory=new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap items,boolean hint) {
        attempts++;assertNotNull(durable.getRewards().get(KEY));
        assertFalse(durable.getRewards().get(KEY).isGranted());
        delivered+=partial?1:items.get(ITEM);
        if(afterGrant!=null)afterGrant.run();
        if(throwDelivery)throw new IllegalStateException("Fixture delivery interrupted");
        return new GrantResult(List.of(new GameItem(ITEM,partial?1:items.get(ITEM))),!partial);
      }
    };
    @Override public Inventory getInventory(){return inventory;}
    @Override public void save(){
      saves++;if(beforeSave!=null)beforeSave.run();
      if(saves==failAt)throw new IllegalStateException("Fixture save rejected");
      var gson=new Gson();durable=gson.fromJson(gson.toJson(getGameplay()),GameplayState.class);
      if(afterSave!=null)afterSave.run();
    }
  }
  @BeforeEach void setup(){
    var gson=new Gson();oldItem=GameData.getItemExcelMap().get(ITEM);oldReward=GameData.getRewardExcelMap().get(REWARD);
    GameData.getItemExcelMap().add(gson.fromJson("{\"ID\":"+ITEM+",\"ItemMainType\":\"Material\",\"PileLimit\":99}",ItemExcel.class));
    var row=gson.fromJson("{\"RewardID\":"+REWARD+",\"ItemID_1\":"+ITEM+",\"Count_1\":7}",RewardExcel.class);row.onLoad();GameData.getRewardExcelMap().add(row);
  }
  @AfterEach void restore(){
    GameData.getItemExcelMap().keySet().remove(ITEM);if(oldItem!=null)GameData.getItemExcelMap().add(oldItem);
    GameData.getRewardExcelMap().keySet().remove(REWARD);if(oldReward!=null)GameData.getRewardExcelMap().add(oldReward);
  }
  private RewardLedger.Claim claim(Actor actor,boolean table){
    if(table)return actor.getRewardLedger().claim(KEY,REWARD);
    var items=new ItemParamMap();items.add(ITEM,7);return actor.getRewardLedger().claimItems(KEY,items);
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void rejectedReservationHasNoDeliveryAndTheExactRequestCanRetry(boolean table){
    var actor=new Actor();actor.failAt=1;
    assertThrows(IllegalStateException.class,()->claim(actor,table));
    assertFalse(actor.getGameplay().getRewards().containsKey(KEY));assertEquals(0,actor.attempts);
    actor.failAt=0;assertEquals(Retcode.SUCC,claim(actor,table).retcode());
    assertEquals(7,actor.delivered);assertTrue(actor.getRewardLedger().granted(KEY));
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN,claim(actor,table).retcode());assertEquals(1,actor.attempts);
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void rejectedFinalSaveNeverReportsGrantedOrAutomaticallyDeliversAgain(boolean table){
    var actor=new Actor();actor.failAt=2;
    assertThrows(IllegalStateException.class,()->claim(actor,table));
    assertEquals(7,actor.delivered);assertFalse(actor.getRewardLedger().granted(KEY));
    assertFalse(actor.durable.getRewards().get(KEY).isGranted());
    actor.failAt=0;assertEquals(Retcode.SYSTEM_BUSY,claim(actor,table).retcode());
    actor.save();assertFalse(actor.durable.getRewards().get(KEY).isGranted());assertEquals(1,actor.attempts);
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void partialOrThrowingDeliveryRetainsItsSavedReservation(boolean throwsAfterDelivery){
    var actor=new Actor();actor.partial=!throwsAfterDelivery;actor.throwDelivery=throwsAfterDelivery;
    if(throwsAfterDelivery)assertThrows(IllegalStateException.class,()->claim(actor,false));
    else assertEquals(Retcode.PLAYER_DATA_ERROR,claim(actor,false).retcode());
    assertNotNull(actor.getGameplay().getRewards().get(KEY));assertFalse(actor.getRewardLedger().granted(KEY));
    assertEquals(Retcode.SYSTEM_BUSY,claim(actor,false).retcode());assertEquals(1,actor.attempts);
  }
  @Test void grantedQueryCannotObserveAnUncommittedCompletion() throws Exception {
    var actor=new Actor();actor.failAt=2;
    var saving=new CountDownLatch(1);var release=new CountDownLatch(1);var querying=new CountDownLatch(1);
    actor.beforeSave=()->{
      if(actor.saves!=2)return;saving.countDown();
      try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture gate timed out");}
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    };
    var pool=Executors.newFixedThreadPool(2);
    try{
      var claim=pool.submit(()->assertThrows(IllegalStateException.class,()->claim(actor,false)));
      assertTrue(saving.await(5,TimeUnit.SECONDS));
      var query=pool.submit(()->{querying.countDown();return actor.getRewardLedger().granted(KEY);});
      assertTrue(querying.await(5,TimeUnit.SECONDS));
      assertThrows(TimeoutException.class,()->query.get(200,TimeUnit.MILLISECONDS));
      release.countDown();claim.get(5,TimeUnit.SECONDS);assertFalse(query.get(5,TimeUnit.SECONDS));
    }finally{release.countDown();pool.shutdownNow();}
  }

  @ParameterizedTest @ValueSource(ints={1,2,3})
  void receiptAndActualInventoryRemainConsistentAcrossNormalH2Restart(int mode) throws Exception {
    var path=directory.resolve("reward-database").toString();var actor=new Actor();
    var identity=Player.class.getDeclaredField("uid");identity.setAccessible(true);identity.setInt(actor,198869100+mode);
    try(var database=new Database(path)){
      var document=new Player();identity.setInt(document,actor.getUid());
      var gameplay=Player.class.getDeclaredField("gameplay");gameplay.setAccessible(true);gameplay.set(document,actor.getGameplay());
      database.store.save(document);
      actor.afterSave=()->database.store.save(document);
      actor.afterGrant=()->{var item=new GameItem(ITEM,7);item.setOwner(actor);database.store.save(item);};
      if(mode==3)actor.throwDelivery=true;else actor.failAt=mode;
      assertThrows(IllegalStateException.class,()->claim(actor,false));
      if(mode==1)assertFalse(actor.getGameplay().getRewards().containsKey(KEY));
      else assertFalse(actor.getRewardLedger().granted(KEY));
      actor.failAt=0;actor.save();
    }
    try(var database=new Database(path)){
      var restored=database.store.find(Player.class).first();assertNotNull(restored);
      var items=database.store.find(GameItem.class).iterator().toList();
      if(mode==1){assertFalse(restored.getGameplay().getRewards().containsKey(KEY));assertTrue(items.isEmpty());}
      else{
        assertFalse(restored.getRewardLedger().granted(KEY));
        assertEquals(Retcode.SYSTEM_BUSY,restored.getRewardLedger().claim(KEY,REWARD).retcode());
        assertEquals(1,items.size());assertEquals(7,items.getFirst().getCount());assertEquals(actor.getUid(),items.getFirst().getOwnerUid());
      }
    }
  }
  static final class Database implements AutoCloseable {
    final de.bwaldvogel.mongo.backend.h2.H2Backend backend;
    final de.bwaldvogel.mongo.MongoServer server;
    final com.mongodb.client.MongoClient client;
    final dev.morphia.Datastore store;
    Database(String path){
      backend=new de.bwaldvogel.mongo.backend.h2.H2Backend(path);server=new de.bwaldvogel.mongo.MongoServer(backend);
      var address=server.bind();client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort());
      store=dev.morphia.Morphia.createDatastore(client,"reward-save-failure");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");store.getMapper().map(Player.class,emu.lunarcore.game.quest.QuestState.class,GameItem.class);
    }
    @Override public void close(){backend.commit();backend.getMvStore().sync();client.close();server.shutdownNow();}
  }
}
