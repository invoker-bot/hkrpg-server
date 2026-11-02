package emu.lunarcore.command.commands;

import emu.lunarcore.command.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;

/** Only compiled into the disposable smoke classpath, never the production JAR. */
@Command(label="activityreplayfixture",permission="server.activityreplayfixture",requireTarget=true,
    desc="Owned isolated history smoke prerequisites")
public final class ActivityReplayFixtureCommand implements CommandHandler {
  @Override public void execute(CommandArgs args){
    if(!Files.isRegularFile(Path.of("activity-replay-owned-fixture"))||args.getTarget()==null
        ||GameData.getMainMissionExcelMap().get(1000510)==null)throw new IllegalStateException("Not an owned history fixture");
    if(!args.getList().isEmpty()&&!args.getList().equals(List.of("music")))throw new IllegalArgumentException("Unknown fixture prerequisites");
    var player=args.getTarget();player.setLevel(21);
    var prerequisite=new QuestState(1000510);prerequisite.advance(1,1,Instant.now().getEpochSecond());
    player.getGameplay().getMainMissions().put(1000510,prerequisite);
    if(args.getList().equals(List.of("music"))){
      var mains=new TreeSet<Integer>(Set.of(1000510,8026101,8026102,8026103,8026104,8026120));
      var subs=new TreeSet<Integer>();
      GameplayDefinitions.musicGroups.values().forEach(g->subs.add(g.UnlockSubMissionID()));
      GameplayDefinitions.musicTracks.values().forEach(t->subs.add(t.UnlockSubMissionID()));
      GameplayDefinitions.musicSongs.values().forEach(s->{
        if(s.UnlockType()==3)subs.add(s.UnlockTypeParam());
        subs.add(s.SoundEffectUnlockSubMission());
      });
      for(int id:subs)if(id>0){
        var definition=GameplayDefinitions.subs.get(id);if(definition==null)throw new IllegalStateException("Missing original music prerequisite");
        mains.add(definition.getMainMissionID());
        var done=new QuestState(id);done.advance(Math.max(definition.getProgress(),1),Math.max(definition.getProgress(),1),123);
        player.getGameplay().getSubMissions().put(id,done);
      }
      for(int id:mains){
        if(GameData.getMainMissionExcelMap().get(id)==null)throw new IllegalStateException("Missing original music main");
        var done=new QuestState(id);done.advance(1,1,123);done.reserveReward();done.closeReward();player.getGameplay().getMainMissions().put(id,done);
      }
      if(!player.loadScene(20101,20101001,1030617,new emu.lunarcore.util.Position(18380,-467,-88929),new emu.lunarcore.util.Position(),false))
        throw new IllegalStateException("Cannot enter original snowfield");
      args.sendMessage("Music story prerequisites only; no performance, offering, goal progress or rewards seeded. Main="+mains+", sub="+subs);
    }
    player.save();
    args.sendMessage("Owned fixture prerequisites persisted: level 21, main mission 1000510");
  }
}
