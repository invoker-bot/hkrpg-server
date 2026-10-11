package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit prerequisite history fixture; original source rows, NPC and task graph are unchanged. */
@Command(label="npcfirsttalkfixture",requireTarget=false,permission="fixture.console-only",
    desc="仅隔离 G61 验收：原前置历史夹具与初遇只读视图")
public final class NpcFirstTalkFixtureCommand implements CommandHandler {
  @Override public void execute(CommandArgs args) {
    if(args.getSender()!=null || args.getList().size()!=3) return;
    int uid=Integer.parseInt(args.getList().get(0)),nonce=Integer.parseInt(args.getList().get(2));
    String mode=args.getList().get(1);
    if(!Set.of("intro","commission","inspect").contains(mode)) throw new IllegalArgumentException("Wrong fixture mode");
    try {
      var cwd=Path.of("").toRealPath();
      var marker=JsonParser.parseString(Files.readString(cwd.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
      if(!cwd.getFileName().toString().startsWith("daily-message-g61-native-")
          || !marker.get("testFixture").getAsBoolean() || !marker.get("g61NpcFirstTalkFixture").getAsBoolean())
        throw new IllegalStateException("Not an owned isolated G61 fixture");
    } catch(java.io.IOException error) { throw new IllegalStateException(error); }
    var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid);
    if(player==null) throw new IllegalStateException("Fixture player is not online");
    synchronized(player) {
      var gson=new Gson();
      var before=gson.toJsonTree(player.getGameplay()).getAsJsonObject();
      var meetings=before.getAsJsonObject("npcFirstTalks").getAsJsonObject("records");
      if(mode.equals("intro") && !meetings.isEmpty()) throw new IllegalStateException("Intro fixture already observed");
      if(mode.equals("commission") && !meetings.has("90000103")) throw new IllegalStateException("Source intro was not observed");
      var ids=mode.equals("intro")?List.of(1000401):mode.equals("commission")?List.of(1010201,1010403):List.<Integer>of();
      long at=Instant.now().getEpochSecond();
      for(int id:ids) {
        if(player.getGameplay().getMainMissions().containsKey(id))
          throw new IllegalStateException("Prerequisite seed would overwrite existing mission: "+id);
        var state=new QuestState(id);
        if(!state.advance(1,1,at) || !state.reserveReward()) throw new IllegalStateException("Prerequisite fixture failed");
        state.closeReward();
        player.getGameplay().getMainMissions().put(id,state);
      }
      if(!ids.isEmpty()) player.save();
      var view=new TreeMap<String,Object>();
      view.put("uid",uid);view.put("nonce",nonce);view.put("mode",mode);view.put("seededPrerequisiteHistory",ids);
      view.put("npcFirstTalks",gson.toJsonTree(player.getGameplay()).getAsJsonObject().get("npcFirstTalks"));
      view.put("credit",player.getScoin());view.put("sourceRowsModified",false);
      System.out.println("HKRPG_G61_FIXTURE_VIEW:"+gson.toJson(view));
    }
    System.out.println("HKRPG_G61_FIXTURE_ACK:"+uid+":"+mode+":"+nonce);
    System.out.flush();
  }
}
