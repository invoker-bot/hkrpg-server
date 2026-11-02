package org.hkrpg.server.gameplay;

import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;

/** Twenty original time-limited goals, read from this cycle's real performances and offering. */
public final class MusicActivityQuests {
  public static boolean owns(int id){return MusicReplayRules.sourceID(id);}
  public static boolean belongs(QuestExcel definition){return definition!=null&&owns(definition.getId());}
  private static boolean open(Player p){
    return p.getLoginActivityManager().activeWindows().stream().anyMatch(w->w.panelId()==50022&&w.moduleId()==5002201);
  }
  private static Map<Integer,Integer> counts(Player p,MusicReplayRules rules){
    if(rules==null)return null;
    var state=p.getGameplay().getRhythm();var stars=state.getStars();var quests=state.getActivityQuests();
    if(stars==null||quests==null||!GameplayDefinitions.musicLevels.keySet().containsAll(stars.keySet())
        ||stars.entrySet().stream().anyMatch(e->e.getValue()==null||e.getValue()<0||e.getValue()>3)
        ||!rules.quests.keySet().containsAll(quests.keySet())||!Offerings.valid(p,5))return null;
    for(var performance:stars.entrySet())for(int star=1;star<=performance.getValue();star++){
      String key=ActivityReplayScope.rewardKey(p.getGameplay(),50022,"music:"+performance.getKey()+":"+star);
      if(!p.getRewardLedger().granted(key))return null;
    }
    int total=GameplayDefinitions.musicLevels.values().stream().filter(l->l.Difficulty()<=2)
        .mapToInt(l->stars.getOrDefault(l.ID(),0)).sum();
    var tracks=new HashSet<Integer>();GameplayDefinitions.musicGroups.values().forEach(g->tracks.addAll(g.RewardTrackIDList()));
    int unlocked=(int)tracks.stream().filter(new MusicComposition(p)::trackUnlocked).count();
    var offering=p.getGameplay().getOfferings().get(5);int level=offering==null?0:offering.getLevel();
    var result=new TreeMap<Integer,Integer>();
    for(var quest:rules.quests.values()){
      var rule=rules.finishes.get(quest.getFinishWayID());
      int measured=switch(rule.getFinishType()){
        case "MusicRhythmLevelStarCnt"->total;
        case "MusicRhythmUnlockTrackCnt"->unlocked;
        case "OfferingLevel"->level>=rule.getParamInt2()?1:0;
        // Original story prerequisites remain shared. Only the activity's claim cycle repeats.
        case "FinishMission"->(int)rule.getParamIntList().stream().filter(id->MissionStateRecords.mainCompleted(p,id)).count();
        case "FinishQuest"->0;
        default->-1;
      };
      if(measured<0)return null;result.put(quest.getId(),Math.min(measured,rule.getProgress()));
    }
    for(var quest:rules.quests.values()){
      var rule=rules.finishes.get(quest.getFinishWayID());if(!"FinishQuest".equals(rule.getFinishType()))continue;
      int done=(int)rule.getParamIntList().stream().filter(id->{
        var own=quests.get(id);var previous=rules.finishes.get(id);
        return previous!=null&&MissionStateRecords.completed(own,id,previous.getProgress())&&own.getProgress()<=result.get(id);
      }).count();result.put(quest.getId(),Math.min(done,rule.getProgress()));
    }
    return result;
  }
  public static boolean refresh(Player p){
    synchronized(p){return ActivityReplayScope.in(p.getGameplay(),50022,()->{
      if(!open(p))return false;
      var rules=MusicReplayRules.loaded();var observed=counts(p,rules);if(observed==null)return false;
      var states=p.getGameplay().getRhythm().getActivityQuests();boolean changed=false;
      for(int id:rules.quests.keySet())if(!states.containsKey(id)){states.put(id,new QuestState(id));changed=true;}
      for(int pass=0;pass<2;pass++){
        observed=counts(p,rules);
        for(int id:rules.quests.keySet()){
          var state=states.get(id);int goal=rules.finishes.get(id).getProgress();
          if(MissionStateRecords.doing(state,id,goal)&&state.getProgress()<=observed.get(id))
            changed|=state.advance(observed.get(id)-state.getProgress(),goal,Instant.now().getEpochSecond());
        }
      }
      return changed;
    });}
  }
  private static QuestState checked(Player p,int id,MusicReplayRules rules,Map<Integer,Integer> counts){
    if(rules==null||counts==null||!rules.quests.containsKey(id))return null;
    var state=p.getGameplay().getRhythm().getActivityQuests().get(id);int goal=rules.finishes.get(id).getProgress();
    return (MissionStateRecords.doing(state,id,goal)||MissionStateRecords.completed(state,id,goal))
        &&state.getProgress()<=counts.get(id)?state:null;
  }
  public static QuestState completionState(Player p,int id){
    synchronized(p){return ActivityReplayScope.in(p.getGameplay(),50022,()->{
      var rules=MusicReplayRules.loaded();return checked(p,id,rules,counts(p,rules));
    });}
  }
  public static Map<Integer,QuestState> states(Player p){
    synchronized(p){return ActivityReplayScope.in(p.getGameplay(),50022,()->{
      if(!open(p))return Map.of();var rules=MusicReplayRules.loaded();var observed=counts(p,rules);
      if(observed==null)return Map.of();var result=new TreeMap<Integer,QuestState>();
      for(int id:rules.quests.keySet()){var state=checked(p,id,rules,observed);if(state!=null)result.put(id,state);}
      return Map.copyOf(result);
    });}
  }
  public static QuestState state(Player p,int id){return states(p).get(id);}
  private MusicActivityQuests(){}
}
