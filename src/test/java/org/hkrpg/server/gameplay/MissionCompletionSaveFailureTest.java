package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;

class MissionCompletionSaveFailureTest {
  static final int MAIN=1988691,SUB=198869101,ITEM=198869197,REWARD=198869196,INTRO=198869195;
  static final String PLAN="submission:"+SUB, CHOICE="mission:"+MAIN+":choice";
  final Gson gson=new Gson();
  final GameplayProgressTest fixture=new GameplayProgressTest();
  ItemExcel oldItem;RewardExcel oldReward;
  static class Actor extends GameplayProgressTest.TestPlayer {
    int saves,failAt,delivered,debits;
    GameplayState durable;
    GameItem stock;
    final Inventory inventory=new Inventory(this){
      @Override public GameItem getMaterialByItemId(int id){return id==ITEM?stock:null;}
      @Override public boolean removeItem(GameItem item,int count){
        assertSame(stock,item);if(item.getCount()<count)return false;
        item.setCount(item.getCount()-count);debits+=count;return true;
      }
      @Override public GrantResult grantItems(ItemParamMap items,boolean hint){
        delivered+=items.get(ITEM);return new GrantResult(List.of(),true);
      }
    };
    @Override public Inventory getInventory(){return inventory;}
    @Override public int getPlaneId(){return 10001;}
    @Override public int getFloorId(){return 10001001;}
    @Override public void save(){
      if(++saves==failAt)throw new IllegalStateException("Fixture document write rejected");
      var gson=new Gson();durable=gson.fromJson(gson.toJson(getGameplay()),GameplayState.class);
    }
  }
  @BeforeEach void setup(){
    oldItem=GameData.getItemExcelMap().get(ITEM);oldReward=GameData.getRewardExcelMap().get(REWARD);
    GameData.getItemExcelMap().add(gson.fromJson("{\"ID\":"+ITEM+",\"ItemMainType\":\"Material\",\"PileLimit\":99}",ItemExcel.class));
    var reward=gson.fromJson("{\"RewardID\":"+REWARD+",\"ItemID_1\":"+ITEM+",\"Count_1\":7}",RewardExcel.class);reward.onLoad();GameData.getRewardExcelMap().add(reward);
    fixture.mission(MAIN,"Manual",Integer.toString(SUB+9),"[{\"ID\":"+SUB+",\"MainMissionID\":"+MAIN+",\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"earned\",\"SubRewardID\":"+REWARD+"},{\"ID\":"+(SUB+9)+",\"MainMissionID\":"+MAIN+",\"TakeType\":\"Auto\",\"FinishType\":\"UnsupportedCondition\"}]");
  }
  @AfterEach void cleanup(){
    fixture.reset();GameData.getMissionInfos().remove(MAIN);GameData.getMainMissionExcelMap().keySet().remove(MAIN);FirstTalkDefinitions.performances.remove(INTRO);
    GameData.getItemExcelMap().keySet().remove(ITEM);if(oldItem!=null)GameData.getItemExcelMap().add(oldItem);
    GameData.getRewardExcelMap().keySet().remove(REWARD);if(oldReward!=null)GameData.getRewardExcelMap().add(oldReward);
  }
  Actor accepted(){var actor=new Actor();assertEquals(Retcode.SUCC,actor.getMissionManager().accept(MAIN));return actor;}
  void stillDoing(Actor actor,QuestState original){
    assertSame(original,actor.getMissionManager().sub(SUB));assertEquals(QuestState.Phase.DOING,original.getPhase());
    assertEquals(0,original.getProgress());assertEquals(0,original.getFinishTime());
    assertFalse(actor.getGameplay().getMissionRewardPlans().containsKey(PLAN));assertEquals(0,actor.delivered);
  }
  @Test void failedTalkCompletionKeepsTheSameAcceptanceAndCanRetry(){
    var actor=accepted();var original=actor.getMissionManager().sub(SUB);original.captureObservationKey();actor.save();
    String observation=original.getObservationKey();actor.failAt=actor.saves+1;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().finishTalk(0,"earned"));
    stillDoing(actor,original);assertEquals(observation,original.getObservationKey());
    actor.failAt=0;assertEquals(Retcode.SUCC,actor.getMissionManager().finishTalk(0,"earned"));assertEquals(7,actor.delivered);
  }
  @Test void failedTalkRestoresAbsentAndExistingChoicesAndRetainsSavedActions(){
    var info=GameData.getMissionInfos().get(MAIN);var raw=gson.toJsonTree(info).getAsJsonObject();
    raw.add("MissionCustomValueList",com.google.gson.JsonParser.parseString("[{\"Name\":\"choice\",\"Type\":\"Former\",\"ValidValueParamList\":[0,1,2]}]"));
    info=gson.fromJson(raw,MissionInfo.class);GameData.getMissionInfos().put(MAIN,info);
    var sub=GameplayDefinitions.subs.get(SUB);sub.setCustomValueList(new String[]{"choice"});
    sub.setFinishActionList(gson.fromJson("[{\"FinishActionType\":\"AddCustomValue\",\"FinishActionParaString\":[\"action\",\"5\"]}]",MissionInfo.FinishAction[].class));
    var actor=accepted();var original=actor.getMissionManager().sub(SUB);actor.failAt=actor.saves+3;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().finishTalk(SUB,"earned",Map.of(0,2)));
    stillDoing(actor,original);assertFalse(actor.getGameplay().getStoryValues().containsKey(CHOICE));
    assertEquals(5,actor.getGameplay().getStoryValues().get("mission:"+MAIN+":action"));
    assertEquals(1,actor.getGameplay().getStoryActions().get(SUB).getNext());
    actor.getGameplay().getStoryValues().put(CHOICE,1);actor.save();actor.failAt=actor.saves+1;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().finishTalk(SUB,"earned",Map.of(0,2)));
    stillDoing(actor,original);assertEquals(1,actor.getGameplay().getStoryValues().get(CHOICE));
    actor.failAt=0;assertEquals(Retcode.SUCC,actor.getMissionManager().finishTalk(SUB,"earned",Map.of(0,2)));
    assertEquals(2,actor.getGameplay().getStoryValues().get(CHOICE));assertEquals(5,actor.getGameplay().getStoryValues().get("mission:"+MAIN+":action"));assertEquals(7,actor.delivered);
  }
  @Test void paidSubmissionCompletionCanRetryWithoutDebitingAgain(){
    var sub=GameplayDefinitions.subs.get(SUB);sub.setFinishType("ConsumeMissionItem");sub.setParamType("Equal");sub.setParamStr1("");sub.setParamItemList(List.of(new MissionInfo.MissionItemCost(ITEM,2)));
    var actor=accepted();actor.stock=new GameItem(ITEM,5);actor.stock.setOwner(actor);
    var original=actor.getMissionManager().sub(SUB);actor.failAt=actor.saves+3;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().submitItems(SUB,null));stillDoing(actor,original);
    assertEquals(2,actor.debits);assertEquals(3,actor.stock.getCount());assertEquals(1,actor.getGameplay().getMissionPayments().get(SUB).getNext());
    actor.failAt=0;assertEquals(Retcode.SUCC,actor.getMissionManager().submitItems(SUB,null));assertEquals(2,actor.debits);assertEquals(7,actor.delivered);
  }
  @Test void explicitFinishActionCannotLeaveAnUnsavedCompletedObjective(){
    var actor=accepted();var original=actor.getMissionManager().sub(SUB);actor.failAt=actor.saves+1;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().finishFromAction(SUB));stillDoing(actor,original);
    actor.failAt=0;assertEquals(Retcode.SUCC,actor.getMissionManager().finishFromAction(SUB));assertTrue(MissionStateRecords.subCompleted(actor,SUB));
  }
  void intro(){
    var sub=GameplayDefinitions.subs.get(SUB);sub.setFinishType("FinishFirstTalkPerformance");sub.setParamType("ListContain");sub.setParamStr1("");sub.setParamIntList(new Integer[]{INTRO});sub.setLevelPlaneID(10001);sub.setLevelFloorID(10001001);
    FirstTalkDefinitions.performances.put(INTRO,new FirstTalkDefinitions.Performance(INTRO,"Config/Level/Mission/test.json",Set.of(),true));
  }
  @Test void failedIntroReservationDoesNotRetainAnUnstoredTargetList(){
    intro();var actor=accepted();actor.failAt=actor.saves+1;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().finishFirstTalkPerformance(INTRO));
    assertFalse(actor.getGameplay().getFirstTalkReports().containsKey(INTRO));stillDoing(actor,actor.getMissionManager().sub(SUB));
    actor.failAt=0;assertEquals(Retcode.SUCC,actor.getMissionManager().finishFirstTalkPerformance(INTRO));assertEquals(7,actor.delivered);
  }
  @Test void failedIntroObjectiveWriteKeepsItsSavedReportForRetry(){
    intro();var actor=accepted();var original=actor.getMissionManager().sub(SUB);actor.failAt=actor.saves+2;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().finishFirstTalkPerformance(INTRO));stillDoing(actor,original);
    assertNotNull(actor.durable.getFirstTalkReports().get(INTRO));assertFalse(actor.getMissionManager().hasSeenFirstTalk(INTRO));
    actor.failAt=0;assertEquals(Retcode.SUCC,actor.getMissionManager().finishFirstTalkPerformance(INTRO));assertEquals(7,actor.delivered);
  }
  @Test void failedIntroFinalWriteDoesNotPublishCompletionAndRetryGrantsOnlyOnce(){
    intro();var actor=accepted();actor.failAt=actor.saves+3;
    assertThrows(IllegalStateException.class,()->actor.getMissionManager().finishFirstTalkPerformance(INTRO));
    assertTrue(MissionStateRecords.subCompleted(actor,SUB));assertFalse(actor.getMissionManager().hasSeenFirstTalk(INTRO));assertEquals(0,actor.delivered);
    actor.failAt=0;assertEquals(Retcode.SUCC,actor.getMissionManager().finishFirstTalkPerformance(INTRO));assertTrue(actor.getMissionManager().hasSeenFirstTalk(INTRO));assertEquals(7,actor.delivered);
    assertEquals(Retcode.SUCC,actor.getMissionManager().finishFirstTalkPerformance(INTRO));assertEquals(7,actor.delivered);
  }
}
