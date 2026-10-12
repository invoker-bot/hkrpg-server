package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.RogueBlessingPersistenceFixtureSupport;

/** Explicit console-only fixture. No production source or operator data installation. */
@Command(label="rogueblessingfixture",requireTarget=false,permission="fixture.console-only",desc="仅独占隔离经典战后菜单保存与原价观察")
public final class RogueBlessingPersistenceFixtureCommand implements CommandHandler {
  private static final List<String> OPERATIONS=List.of("seed","before-query","after-query","before-reroll","after-reroll","before-rejects","after-rejects","before-pending","after-pending","checkpoint-restored","before-restored-query","after-restored-query","before-select","after-select","before-repeat","after-repeat","before-final-query","after-final-query");
  private static void require(boolean ok,String why) { if(!ok)throw new IllegalStateException(why); }
  private static JsonObject read(Path path) throws java.io.IOException { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
  @Override public void execute(CommandArgs args) {
    if(args.getSender()!=null || args.getList().size()!=3)return;
    int uid=Integer.parseInt(args.getList().get(0)),nonce=Integer.parseInt(args.getList().get(2));String operation=args.getList().get(1);
    try {
      var cwd=Path.of("").toRealPath();var marker=read(cwd.resolve("resources-fixture/resource-manifest.json"));var control=read(cwd.resolve("rogue-blessing-native-control.json"));
      require(cwd.getFileName().toString().startsWith("rogue-blessing-") && marker.get("testFixture").getAsBoolean()
          && marker.get("rogueBlessingPersistenceFixture").getAsBoolean() && marker.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b")
          && uid==10001 && uid==control.get("uid").getAsInt() && nonce==control.get("nonce").getAsInt(),"Unowned fixture directory/UID/nonce");
      int index=OPERATIONS.indexOf(operation);require(index>=0,"Unknown roster operation");
      var receipt=cwd.resolve("rogue-blessing-operation-"+operation+".json");require(!Files.exists(receipt),"Duplicate fixture observation");
      for(int previous=0;previous<index;previous++)require(Files.isRegularFile(cwd.resolve("rogue-blessing-operation-"+OPERATIONS.get(previous)+".json")),"Out-of-order roster operation");
      var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid);require(player!=null,"Isolated player must be online");
      synchronized(player) {
        RogueBlessingPersistenceFixtureSupport.execute(player,cwd,operation);
        Files.writeString(receipt,new Gson().toJson(Map.of("uid",uid,"nonce",nonce,"operation",operation)),StandardOpenOption.CREATE_NEW);
      }
      System.out.println("HKRPG_ROGUE_BLESSING_ACK:"+uid+":"+operation+":"+nonce);System.out.flush();
    } catch(java.io.IOException | RuntimeException failure) {
      // Return an explicit failure to the wrapper, preserving the actual server console for normal stop.
      try {
        var owned=Path.of("").toRealPath();
        if(owned.getFileName().toString().startsWith("rogue-blessing-") && Files.isRegularFile(owned.resolve("rogue-blessing-native-control.json"))) {
          var control=read(owned.resolve("rogue-blessing-native-control.json"));
          if(uid==10001 && uid==control.get("uid").getAsInt() && nonce==control.get("nonce").getAsInt())
            Files.writeString(owned.resolve("rogue-blessing-fixture-command-failure.json"),new Gson().toJson(Map.of(
                "uid",uid,"nonce",nonce,"operation",operation,"exception",failure.getClass().getName(),"message",failure.toString())),StandardOpenOption.CREATE_NEW);
        }
      } catch(Exception evidenceFailure) {failure.addSuppressed(evidenceFailure);}
      System.out.println("HKRPG_ROGUE_BLESSING_FAILURE:"+uid+":"+operation+":"+nonce);failure.printStackTrace(System.out);System.out.flush();
    }
  }
}
