package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Console-only history/clock fixture. It never seeds a gift, choice, payment or completed target. */
@Command(label="recoverdeliveryfixture",requireTarget=false,permission="fixture.console-only",desc="隔离原回收物日常：原前置与受控时钟")
public final class RecoverDeliveryFixtureCommand implements CommandHandler {
  @Override public void execute(CommandArgs args) {
    if(args.getSender()!=null || args.getList().size()!=4)return;
    int uid=Integer.parseInt(args.getList().get(0)),nonce=Integer.parseInt(args.getList().get(3));
    long epoch=Long.parseLong(args.getList().get(1));String mode=args.getList().get(2);
    if(!Set.of("initial","inspect").contains(mode))throw new IllegalArgumentException("Unknown fixture mode");
    try {
      var cwd=Path.of("").toRealPath();var marker=JsonParser.parseString(Files.readString(cwd.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
      if(!cwd.getFileName().toString().startsWith("daily-recover-delivery-native-") || !marker.get("testFixture").getAsBoolean()
          || !marker.get("recoverDeliveryFixture").getAsBoolean() || marker.get("originalMission").getAsInt()!=3011014)throw new IllegalStateException("Unowned recovery fixture");
    }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid);if(player==null)throw new IllegalStateException("Fixture player not online");
    synchronized(player) {
      var g=player.getGameplay();var gson=new Gson();String before=gson.toJson(g);var mains=new LinkedHashSet<Integer>();var subs=new LinkedHashSet<Integer>();
      if(mode.equals("initial")) {
        mains.addAll(List.of(1010201,1010403,1011501,1010501,1010902,2010501));
        for(int entry:List.of(1010201,2012101,2012301,1010203)) {
          var original=GameData.getMapEntranceExcelMap().get(entry);if(original==null)throw new IllegalStateException("Missing original entrance "+entry);
          for(int id:original.getFinishMainMissionList())mains.add(id);for(int id:original.getFinishSubMissionList())subs.add(id);
        }
        if(mains.contains(3011014) || subs.stream().anyMatch(id->id>=301101401 && id<=301101412))throw new IllegalStateException("Fixture cannot seed current objectives");
        for(int id:mains) {
          if(g.getMainMissions().containsKey(id))throw new IllegalStateException("Would overwrite original prerequisite "+id);
          var state=new QuestState(id);if(!state.advance(1,1,epoch) || !state.reserveReward())throw new IllegalStateException("Original history fixture failed");
          state.closeReward();g.getMainMissions().put(id,state);
        }
        for(int id:subs) {
          if(g.getSubMissions().containsKey(id))throw new IllegalStateException("Would overwrite entrance prerequisite "+id);
          var state=new QuestState(id);if(!state.advance(1,1,epoch))throw new IllegalStateException("Original sub history fixture failed");g.getSubMissions().put(id,state);
        }
        player.save();
      } else if(!before.equals(gson.toJson(g)))throw new IllegalStateException("Inspect changed gameplay");
      var clock=Clock.fixed(Instant.ofEpochSecond(epoch),ZoneOffset.UTC);player.getMissionManager().setClock(clock);player.getDailyManager().setClock(clock);
      System.out.println("HKRPG_RECOVER_DELIVERY_FIXTURE_VIEW:"+gson.toJson(Map.of("uid",uid,"nonce",nonce,"mode",mode,"epoch",epoch,
          "prerequisiteMainHistory",mains.stream().sorted().toList(),"prerequisiteSubHistory",subs.stream().sorted().toList(),"credit",player.getScoin(),"giftChoicePaymentOrGoalSeeded",false)));
    }
    System.out.println("HKRPG_RECOVER_DELIVERY_FIXTURE_ACK:"+uid+":"+mode+":"+nonce);System.out.flush();
  }
}
