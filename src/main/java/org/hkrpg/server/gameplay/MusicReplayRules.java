package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Original parameters shared by activity objectives, replay validation, and tests. */
public final class MusicReplayRules {
  private static final Gson JSON=ResourceLoader.getGson();
  private static final MusicReplayRules SOURCE=read();
  final JsonObject records;
  final Map<Integer,QuestExcel> quests;
  final Map<Integer,FinishWayExcel> finishes;
  private MusicReplayRules(JsonObject records){
    this.records=records;
    quests=rows("QuestData",QuestExcel.class,QuestExcel::getId);
    finishes=rows("FinishWay",FinishWayExcel.class,FinishWayExcel::getId);
    if(quests.size()!=20||finishes.size()!=20||!quests.keySet().equals(finishes.keySet()))
      throw new IllegalStateException("Incomplete original music goals");
  }
  static boolean sourceID(int id){return SOURCE.quests.containsKey(id);}
  private static MusicReplayRules read(){
    try(var stream=MusicReplayRules.class.getResourceAsStream("/gameplay/music-replay-4.6.json")){
      if(stream==null)throw new IOException("Missing music replay definitions");
      var root=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
      if(root.get("schemaVersion").getAsInt()!=1||!root.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b"))
        throw new IOException("Unverified music replay definitions");
      return new MusicReplayRules(root.getAsJsonObject("records"));
    }catch(IOException|RuntimeException invalid){throw new IllegalStateException("Invalid music replay source",invalid);}
  }
  private <T>Map<Integer,T> rows(String table,Class<T> type,ToIntFunction<T> id){
    var result=new LinkedHashMap<Integer,T>();
    for(var raw:records.getAsJsonArray(table)){
      var row=JSON.fromJson(raw,type);if(result.putIfAbsent(id.applyAsInt(row),row)!=null)throw new IllegalStateException("Duplicate music source");
    }
    return Collections.unmodifiableMap(result);
  }
  private <T>boolean matches(String table,Class<T> type,ToIntFunction<T> key,Function<Integer,T> loaded){
    for(var row:rows(table,type,key).entrySet()){
      var actual=loaded.apply(row.getKey());
      if(actual==null||!JSON.toJsonTree(row.getValue()).equals(JSON.toJsonTree(actual)))return false;
    }
    return true;
  }
  private <T>boolean matchesMap(String table,Class<T> type,ToIntFunction<T> key,Map<Integer,T> loaded){
    return loaded.keySet().equals(rows(table,type,key).keySet())&&matches(table,type,key,loaded::get);
  }
  public static boolean available(){return loaded()!=null;}
  static MusicReplayRules loaded(){
    var s=SOURCE;
    if(!s.matches("QuestData",QuestExcel.class,QuestExcel::getId,GameData.getQuestExcelMap()::get)
        ||!s.matches("FinishWay",FinishWayExcel.class,FinishWayExcel::getId,GameData.getFinishWayExcelMap()::get))return null;
    var panel=GameData.getActivityPanelExcelMap().get(50022);
    if(panel==null||!"[PlayerLevel:21]".equals(panel.getUnlockConditions())
        ||!"UI/Quest/Widget/QuestMusicRhythm.prefab".equals(panel.getUIPrefab())
        ||!s.records.getAsJsonArray("ActivityPanel").get(0).getAsJsonObject().get("FinishConditions").getAsString().equals(panel.getFinishConditions()))return null;
    if(!s.matchesMap("MusicRhythmLevel",GameplayDefinitions.MusicLevel.class,GameplayDefinitions.MusicLevel::ID,GameplayDefinitions.musicLevels)
        ||!s.matchesMap("MusicRhythmGroup",GameplayDefinitions.MusicGroup.class,GameplayDefinitions.MusicGroup::ID,GameplayDefinitions.musicGroups)
        ||!s.matchesMap("MusicRhythmPhase",GameplayDefinitions.MusicPhase.class,GameplayDefinitions.MusicPhase::Phase,GameplayDefinitions.musicPhases)
        ||!s.matchesMap("MusicRhythmTrack",GameplayDefinitions.MusicTrack.class,GameplayDefinitions.MusicTrack::ID,GameplayDefinitions.musicTracks)
        ||!s.matchesMap("MusicRhythmSong",GameplayDefinitions.MusicSong.class,GameplayDefinitions.MusicSong::ID,GameplayDefinitions.musicSongs)
        ||!s.matchesMap("MusicRhythmPresetSong",GameplayDefinitions.MusicPreset.class,GameplayDefinitions.MusicPreset::ID,GameplayDefinitions.musicPresets)
        ||!s.matches("MainMission",MainMissionExcel.class,MainMissionExcel::getId,GameData.getMainMissionExcelMap()::get))return null;
    for(var raw:s.records.getAsJsonArray("MusicRhythmConstValueCommon")){
      var row=JSON.fromJson(raw,GameplayDefinitions.MusicConstant.class);
      if(!Objects.equals(GameplayDefinitions.musicConstants.get(row.ConstValueName()),row.Value().IntValue()))return null;
    }
    if(!s.matches("RewardData",RewardExcel.class,RewardExcel::getId,GameData.getRewardExcelMap()::get))return null;
    for(var raw:s.records.getAsJsonArray("RewardData")){
      var expected=JSON.fromJson(raw,RewardExcel.class);expected.onLoad();var actual=GameData.getRewardExcelMap().get(expected.getId());
      if(actual.getRewards()==null||!expected.getRewards().equals(actual.getRewards()))return null;
    }
    for(var raw:s.records.getAsJsonArray("ItemConfig")){
      var expected=JSON.fromJson(raw,ItemExcel.class);var actual=GameData.getItemExcelMap().get(expected.getId());
      if(actual==null||actual.getItemMainType()!=expected.getItemMainType()||actual.getItemSubType()!=expected.getItemSubType()
          ||actual.getPileLimit()!=expected.getPileLimit())return null;
    }
    return s;
  }
  private MusicReplayRules(){throw new AssertionError();}
}
