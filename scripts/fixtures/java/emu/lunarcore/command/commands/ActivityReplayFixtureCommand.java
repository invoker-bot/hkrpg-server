package emu.lunarcore.command.commands;

import emu.lunarcore.command.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;

/** Only compiled into the disposable smoke classpath, never the production JAR. */
@Command(label="activityreplayfixture",permission="server.activityreplayfixture",requireTarget=true,
    desc="Owned isolated history smoke prerequisites")
public final class ActivityReplayFixtureCommand implements CommandHandler {
  @Override public void execute(CommandArgs args){
    if(!Files.isRegularFile(Path.of("activity-replay-owned-fixture"))||args.getTarget()==null
        ||GameData.getMainMissionExcelMap().get(1000510)==null)throw new IllegalStateException("Not an owned history fixture");
    var player=args.getTarget();player.setLevel(21);
    var prerequisite=new QuestState(1000510);prerequisite.advance(1,1,Instant.now().getEpochSecond());
    player.getGameplay().getMainMissions().put(1000510,prerequisite);player.save();
    args.sendMessage("Owned fixture prerequisites persisted: level 21, main mission 1000510");
  }
}
