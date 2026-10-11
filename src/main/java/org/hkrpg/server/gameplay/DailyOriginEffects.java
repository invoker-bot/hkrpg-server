package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Finite common Act tail after its validated original report; client strings/options are not executed. */
public final class DailyOriginEffects {
  private static final int MAIN=3000601,INITIAL=300060001,GROUP=84;
  private static final Gson GSON=ResourceLoader.getGson();
  private static final String ACT="Config/Level/Mission/3000600/Act/Act300060001.json";
  private static final String GROUP_PATH="Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G84.json";
  private static final String ACT_PIN="8af56c38d4877a2487493bee1429f280a3d2bc36a9132948e9893c99d5064732";
  private static final String GROUP_PIN="b500b37a5edc7d4a323a6536f698cc46d68eaf82a79e183735792433e7b69ca2";
  private static final String PERF_PIN="d725736c61d8e7b9663fb716581291455860064d102015a25c1f75ec97c943e4";
  private static final Map<String,String> FILES=Map.of(
      "Config/Level/Mission/3000601/MissionInfo_3000601.json","89e387e0630cdc95cb08da50da02d839dc7e1803128d7ef70d955e3d4965bc90",
      "Config/Level/Mission/3000601/MissionInfo_3000601.partial.json","f7d458b5ac841a1a5df69987b0511c5f3a60a54890b2379193d3af2519c7f282",
      "Config/Level/Mission/3000600/Mission_300060001.json","a8ad32be0fe86430775eff1a8c1f42a0073bc6eadc9505bc2eb758c32ec23ea6",
      ACT,ACT_PIN,GROUP_PATH,GROUP_PIN);
  static final String SOURCE="4.6:300060001:report-common-tail:"+ACT_PIN+":"+GROUP_PIN+":"+PERF_PIN;

  public static boolean handles(int sub) { return sub==INITIAL; }

  private static JsonElement read(Path root, String relative) throws Exception {
    var file=root.resolve(relative).normalize();
    if(!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file)>4*1024*1024
        || !file.toRealPath().startsWith(root.toRealPath()))return null;
    return JsonParser.parseString(Files.readString(file));
  }

  private static boolean source() {
    try {
      var goal=GameplayDefinitions.subs.get(300060102);
      if(!DailyMissionMessageRules.repeatable(goal) || !DailyMissionMessageOriginRules.source())return false;
      var root=Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      for(var file:FILES.entrySet())if(!file.getValue().equals(TimelineActivityRules.fingerprint(read(root,file.getKey()))))return false;
      var table=read(root,"ExcelOutput/PerformanceE.json");
      if(table==null || !table.isJsonArray() || table.getAsJsonArray().size()>32768)return false;
      JsonObject performance=null;
      for(var value:table.getAsJsonArray()) {
        var row=value.getAsJsonObject();var number=row.get("PerformanceID");
        if(number==null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber())return false;
        if(number.getAsBigDecimal().intValueExact()!=INITIAL)continue;
        if(performance!=null || !PERF_PIN.equals(TimelineActivityRules.fingerprint(row)))return false;
        performance=row;
      }
      return performance!=null;
    }catch(Exception invalid){return false;}
  }

  private static String actorSource(NpcInfo npc) {
    return TimelineActivityRules.fingerprint(GSON.toJsonTree(npc));
  }

  /** Called only after MissionManager validates the original Doing Talk key and its scene gate. */
  public static Retcode finishInitial(Player player, MissionInfo.SubMissionInfo config,
      QuestState accepted, Map<Integer,Integer> values, long now) {
    synchronized(player) {
      if(config!=GameplayDefinitions.subs.get(INITIAL) || config.getId()!=INITIAL
          || accepted!=player.getGameplay().getSubMissions().get(INITIAL)
          || !MissionStateRecords.doing(accepted,INITIAL,1))return Retcode.MISSION_NOT_DOING;
      if(values==null || !values.isEmpty())return Retcode.MISSION_CUSTOM_VALUE_NOT_VALID;
      if(now<=0 || !source() || !DailyMissionMessageRules.initialTalk(player))return Retcode.MISSION_FINISH_WAY_NOT_MATCH;
      var scene=player.getScene();var gameplay=player.getGameplay();
      var main=gameplay.getMainMissions().get(MAIN);var previous=gameplay.getDailyOriginEffects();
      long day=DailyManager.day(Instant.ofEpochSecond(now));String key=MAIN+":"+day;
      if(player.getUid()<=0 || scene.getEntryId()<=0 || !previous.valid()
          || previous.getRecords().containsKey(key) || previous.getRecords().size()>=4096)return Retcode.PLAYER_DATA_ERROR;
      if(!DailyMissionExpiry.current(player,MAIN,Instant.ofEpochSecond(now))
          || day!=DailyManager.day(player.getMissionManager().currentInstant()))return Retcode.MISSION_NOT_DOING;
      var actors=List.copyOf(scene.getEntitiesByGroup(EntityNpc.class,GROUP));
      var placements=scene.getResourceGroups().get(GROUP).getNPCList();
      String previousMainKey=main.getObservationKey(),mainKey=null;
      if(previousMainKey!=null && !DailyOriginEffectsState.uuid(previousMainKey)
          || accepted.getObservationKey()!=null && !DailyOriginEffectsState.uuid(accepted.getObservationKey()))return Retcode.PLAYER_DATA_ERROR;
      var completed=GSON.fromJson(GSON.toJson(accepted),QuestState.class);
      var next=new DailyOriginEffectsState(previous);
      try {
        String initialKey=completed.captureObservationKey();
        if(!completed.advance(1,1,now))return Retcode.MISSION_NOT_DOING;
        mainKey=main.captureObservationKey();
        var frozenActors=new TreeMap<Integer,DailyOriginEffectsState.Actor>();
        for(var actor:actors){
          var info=placements.stream().filter(row->row.getID()==actor.getInstId()).findFirst().orElseThrow();
          frozenActors.put(info.getID(),new DailyOriginEffectsState.Actor(info.getID(),info.getNPCID(),actor.getEntityId(),actorSource(info)));
        }
        var receipt=new DailyOriginEffectsState.Receipt(player.getUid(),scene.getEntryId(),day,now,mainKey,initialKey,SOURCE,frozenActors);
        if(!receipt.valid())throw new IllegalStateException("Invalid original daily actor receipt");
        next.getRecords().put(key,receipt);
        gameplay.getSubMissions().put(INITIAL,completed);gameplay.dailyOriginEffects(next);
        player.save();
      }catch(RuntimeException failure){
        gameplay.getSubMissions().put(INITIAL,accepted);gameplay.dailyOriginEffects(previous);
        if(mainKey!=null)main.restoreObservationKey(mainKey,previousMainKey);
        throw failure;
      }
      // No removal/notification is published until progress and its acceptance-bound receipt save.
      for(var actor:actors)scene.removeEntity(actor);
      return Retcode.SUCC;
    }
  }

  /** Pure load-time check: a passed Scene cannot create or save an execution receipt. */
  public static boolean suppresses(Scene scene, GroupInfo group, NpcInfo npc) {
    if(scene==null || group==null || npc==null || group.getId()!=GROUP || !Set.of(400001,400002).contains(npc.getID()))return false;
    var player=scene.getPlayer();if(player==null)return false;
    synchronized(player) {
      try {
        var gameplay=player.getGameplay();var main=gameplay.getMainMissions().get(MAIN);var initial=gameplay.getSubMissions().get(INITIAL);
        if(scene.getPlaneId()!=20002 || scene.getFloorId()!=20002001 || scene.getDimensionId()!=0 || scene.getStoryLineId()!=0
            || scene.getFloorInfo()!=GameData.getFloorInfo(20002,20002001)
            || scene.getResourceGroups().get(GROUP)!=group || group.getNPCList()==null || !group.getNPCList().contains(npc)
            || !MissionStateRecords.mainAccepted(player,MAIN) || !MissionStateRecords.completed(initial,INITIAL,1)
            || !source())return false;
        long day=DailyManager.day(player.getMissionManager().currentInstant());var state=gameplay.getDailyOriginEffects();
        if(!state.valid() || !DailyMissionExpiry.current(player,MAIN,player.getMissionManager().currentInstant()))return false;
        var receipt=state.getRecords().get(MAIN+":"+day);
        if(receipt==null || receipt.getUid()!=player.getUid() || receipt.getDay()!=day || receipt.getAt()!=initial.getFinishTime()
            || !Objects.equals(receipt.getMainAcceptance(),main.getObservationKey())
            || !Objects.equals(receipt.getInitialAcceptance(),initial.getObservationKey()))return false;
        var actor=receipt.getActors().get(npc.getID());
        return actor!=null && actor.getNpc()==npc.getNPCID() && actorSource(npc).equals(actor.getSource());
      }catch(RuntimeException invalid){return false;}
    }
  }

  private DailyOriginEffects() {}
}
