package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** All 27 original performances, 16 offering levels, and 20 source-linked activity rewards. */
class MusicReplayGoalsTest {
  @TempDir Path root;
  final Gson gson=ResourceLoader.getGson();
  final List<Runnable> restore=new ArrayList<>();
  MusicOfferingRewardTest fixture;
  JsonObject records;
  @BeforeEach @SuppressWarnings({"rawtypes","unchecked"}) void prepare()throws Exception{
    fixture=new MusicOfferingRewardTest();fixture.root=root;fixture.setup();fixture.qualify();
    try(var reader=new InputStreamReader(getClass().getResourceAsStream("/gameplay/music-replay-4.6.json"),StandardCharsets.UTF_8)){
      records=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for(var entry:records.entrySet()){
      var table=fixture.query.fixture.table(entry.getKey());if(table==null)continue;
      var old=new ArrayList<>(KraterOffering46Test.values(table.map()));
      restore.add(()->{KraterOffering46Test.values(table.map()).clear();for(var row:old)table.map().add(row);});
      for(var row:entry.getValue().getAsJsonArray()){
        var parsed=gson.fromJson(row,table.type());parsed.onLoad();table.map().add(parsed);
      }
    }
    load("MusicRhythmGroup",GameplayDefinitions.musicGroups,GameplayDefinitions.MusicGroup.class,GameplayDefinitions.MusicGroup::ID);
    load("MusicRhythmLevel",GameplayDefinitions.musicLevels,GameplayDefinitions.MusicLevel.class,GameplayDefinitions.MusicLevel::ID);
    load("MusicRhythmPhase",GameplayDefinitions.musicPhases,GameplayDefinitions.MusicPhase.class,GameplayDefinitions.MusicPhase::Phase);
    load("MusicRhythmSong",GameplayDefinitions.musicSongs,GameplayDefinitions.MusicSong.class,GameplayDefinitions.MusicSong::ID);
    load("MusicRhythmTrack",GameplayDefinitions.musicTracks,GameplayDefinitions.MusicTrack.class,GameplayDefinitions.MusicTrack::ID);
    load("MusicRhythmPresetSong",GameplayDefinitions.musicPresets,GameplayDefinitions.MusicPreset.class,GameplayDefinitions.MusicPreset::ID);
    var constants=new HashMap<>(GameplayDefinitions.musicConstants);restore.add(()->{GameplayDefinitions.musicConstants.clear();GameplayDefinitions.musicConstants.putAll(constants);});
    GameplayDefinitions.musicConstants.clear();
    for(var row:records.getAsJsonArray("MusicRhythmConstValueCommon")){
      var parsed=gson.fromJson(row,GameplayDefinitions.MusicConstant.class);GameplayDefinitions.musicConstants.put(parsed.ConstValueName(),parsed.Value().IntValue());
    }
    var player=fixture.query.fixture.player;player.setLevel(21);
    for(var row:records.getAsJsonArray("MainMission")){
      int id=row.getAsJsonObject().get("MainMissionID").getAsInt();var completed=new QuestState(id);completed.advance(1,1,123);player.getGameplay().getMainMissions().put(id,completed);
    }
    var subs=new HashSet<Integer>();GameplayDefinitions.musicGroups.values().forEach(g->subs.add(g.UnlockSubMissionID()));GameplayDefinitions.musicTracks.values().forEach(t->subs.add(t.UnlockSubMissionID()));
    for(int id:subs)if(id>0){var completed=new QuestState(id);completed.advance(1,1,123);player.getGameplay().getSubMissions().put(id,completed);}
    cycle(UUID.randomUUID().toString());
  }
  <T>void load(String name,Map<Integer,T> map,Class<T> type,ToIntFunction<T> key){
    var old=new HashMap<>(map);restore.add(()->{map.clear();map.putAll(old);});map.clear();
    for(var row:records.getAsJsonArray(name)){var value=gson.fromJson(row,type);map.put(key.applyAsInt(value),value);}
  }
  @AfterEach void cleanup()throws Exception{Collections.reverse(restore);restore.forEach(Runnable::run);if(fixture!=null)fixture.cleanup();}
  void cycle(String id){GameplayDefinitions.replaceActivityWindows(List.of(
      new GameplayDefinitions.Window(50022,5002201,1,4102444800L,id,"2.6-0175c1cdbc5c"),
      new GameplayDefinitions.Window(50022,5002202,1,4102444800L,id,"2.6-0175c1cdbc5c")));}
  List<QuestState> goals(){return fixture.query.fixture.player.getQuestManager().states().stream().filter(q->q.getQuestId()>=6029201&&q.getQuestId()<=6029236).toList();}
  void completeAndClaim()throws Exception{
    var player=fixture.query.fixture.player;var manager=new MusicManager(player);
    int badgesBefore=player.delivered.getOrDefault(270003,0);
    for(var level:GameplayDefinitions.musicLevels.values().stream().sorted(Comparator.comparingInt(GameplayDefinitions.MusicLevel::Difficulty).thenComparingInt(GameplayDefinitions.MusicLevel::ID)).toList()){
      assertEquals(Retcode.SUCC,manager.start(level.ID()),"Start "+level.ID());
      assertEquals(Retcode.SUCC,manager.finish(3,true),"Finish "+level.ID());
    }
    assertEquals(54,player.delivered.get(270003)-badgesBefore);
    int held=player.stock.containsKey(270003)?player.stock.get(270003).getCount():0;
    fixture.stock(held+54);fixture.query.info(5);assertEquals(0,fixture.submit());
    assertEquals(16,player.getGameplay().getOfferings().get(5).getLevel());
    assertEquals(0,fixture.claim(java.util.stream.IntStream.rangeClosed(1,16).boxed().toArray(Integer[]::new)));
    var all=goals();assertEquals(20,all.size());assertTrue(all.stream().allMatch(q->q.getPhase()==QuestState.Phase.FINISHED));
    for(var quest:all){
      assertEquals(Retcode.SUCC,player.getQuestManager().claim(quest.getQuestId()).retcode(),"Goal "+quest.getQuestId());
      assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN,player.getQuestManager().claim(quest.getQuestId()).retcode());
    }
    assertTrue(goals().stream().allMatch(q->q.getPhase()==QuestState.Phase.CLOSED));
  }
  @Test void allSourceGoalsAndRewardsCanBeCompletedInTwoIndependentCycles()throws Exception{
    var player=fixture.query.fixture.player;
    assertEquals(20,goals().size());
    var star=goals().stream().filter(q->q.getQuestId()==6029231).findFirst().orElseThrow();assertEquals(0,star.getProgress());
    String first=GameplayDefinitions.windows.getFirst().replayId();completeAndClaim();
    assertEquals(1380,player.delivered.get(1));assertEquals(960000,player.delivered.get(2));
    assertEquals(1,player.delivered.get(22003));assertEquals(4,player.delivered.get(121003));assertEquals(1,player.delivered.get(236));
    cycle(UUID.randomUUID().toString());
    assertFalse(player.getGameplay().getOfferings().containsKey(5));assertTrue(player.getGameplay().getRhythm().getStars().isEmpty());
    assertEquals(0,goals().stream().filter(q->q.getQuestId()==6029231).findFirst().orElseThrow().getProgress());
    completeAndClaim();assertEquals(2760,player.delivered.get(1));assertEquals(12,player.stock.get(270003).getCount());
    var saved=gson.toJsonTree(player.getGameplay());assertEquals(saved,gson.toJsonTree(gson.fromJson(saved,GameplayState.class)));
    cycle(first);assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN,player.getQuestManager().claim(6029231).retcode());
    assertEquals(2760,player.delivered.get(1));assertTrue(saved.getAsJsonObject().getAsJsonObject("offerings").entrySet().isEmpty());
  }
  @Test void starsWithoutThisCyclesPerformanceReceiptsCannotManufactureActivityRewards(){
    var player=fixture.query.fixture.player;
    player.getGameplay().getRhythm().getStars().put(101,3);player.getGameplay().getRhythm().getStars().put(102,3);
    assertTrue(goals().isEmpty());
    assertNotEquals(Retcode.SUCC,player.getQuestManager().claim(6029231).retcode());
    assertFalse(player.delivered.containsKey(1));
  }
}
