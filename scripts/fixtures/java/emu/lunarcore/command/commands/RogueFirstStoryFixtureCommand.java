package emu.lunarcore.command.commands;

import com.google.gson.JsonParser;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.Command;
import emu.lunarcore.command.CommandArgs;
import emu.lunarcore.command.CommandHandler;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only setup for the isolated KCP/H2 probe; never included in the production JAR. */
@Command(label = "roguefirststoryfixture", requireTarget = false,
    permission = "fixture.console-only", desc = "仅隔离探针：设置首次宇宙入口前置")
public final class RogueFirstStoryFixtureCommand implements CommandHandler {
  @Override public void execute(CommandArgs args) {
    if (args.getSender() != null || args.getList().size() != 2) return;
    try {
      int uid = Integer.parseInt(args.getList().get(0));
      int nonce = Integer.parseInt(args.getList().get(1));
      var cwd = Path.of("").toRealPath();
      var manifest = JsonParser.parseString(Files.readString(
          cwd.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
      if (uid != 10001 || nonce <= 0 || !cwd.getFileName().toString().startsWith("rogue-first-story-trigger-")
          || !manifest.get("testFixture").getAsBoolean()
          || !manifest.get("rogueFirstStoryTriggerFixture").getAsBoolean())
        throw new IllegalStateException("Not an isolated first-story fixture");
      var player = LunarCore.getGameServer().getOnlinePlayerByUid(uid);
      if (player == null) throw new IllegalStateException("Fixture player is not online");
      synchronized (player) {
        if (player.isInBattle() || player.getGameplay().getRogue().isInside()
            || player.getGameplay().getTrialRun() != null)
          throw new IllegalStateException("Fixture player is not in an ordinary context");
        if (!player.loadScene(80112, 80112001, 8011201,
            new Position(9250, 608, -90), new Position(), false))
          throw new IllegalStateException("Original office scene is unavailable");
        player.getGameplay().getMainMissions().put(4030001, new QuestState(4030001));
        var previous = new QuestState(403000101);
        if (!previous.advance(1, 1, 100)) throw new IllegalStateException("Fixture completion failed");
        player.getGameplay().getSubMissions().put(403000101, previous);
        player.getGameplay().getSubMissions().put(403000102, new QuestState(403000102));
        player.save();
      }
      System.out.println("HKRPG_ROGUE_FIRST_STORY_FIXTURE_ACK:" + uid + ":" + nonce);
      System.out.flush();
    } catch (Exception failure) {
      throw new IllegalStateException("Isolated first-story fixture failed", failure);
    }
  }
}
