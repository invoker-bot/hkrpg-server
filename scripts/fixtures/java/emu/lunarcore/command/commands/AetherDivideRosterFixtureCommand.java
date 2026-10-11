package emu.lunarcore.command.commands;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.AetherDivideRosterFixtureSupport;

/** Explicit console-only fixture. No production source or operator data installation. */
@Command(label="aetherrosterfixture",requireTarget=false,permission="fixture.console-only",desc="仅独占隔离 Aether roster 与实际库存只读观察")
public final class AetherDivideRosterFixtureCommand implements CommandHandler {
  private static final List<String> OPERATIONS=List.of("before-empty","after-empty","seed","before-owned","after-owned","before-rejects","after-rejects",
      "before-mutations","after-mutations","before-repeat","after-repeat","before-final-query","after-final-query","before-restored","after-restored");
  private static void require(boolean ok,String why) { if(!ok)throw new IllegalStateException(why); }
  private static JsonObject read(Path path) throws java.io.IOException { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
  @Override public void execute(CommandArgs args) {
    if(args.getSender()!=null || args.getList().size()!=3)return;
    int uid=Integer.parseInt(args.getList().get(0)),nonce=Integer.parseInt(args.getList().get(2));String operation=args.getList().get(1);
    try {
      var cwd=Path.of("").toRealPath();var marker=read(cwd.resolve("resources-fixture/resource-manifest.json"));var control=read(cwd.resolve("aether-native-control.json"));
      require(cwd.getFileName().toString().startsWith("aether-divide-roster-") && marker.get("testFixture").getAsBoolean()
          && marker.get("aetherRosterFixture").getAsBoolean() && marker.get("sourceCommit").getAsString().equals("724b139d8c9c32d12552eb95745a4fee72bfe48b")
          && uid==10001 && uid==control.get("uid").getAsInt() && nonce==control.get("nonce").getAsInt(),"Unowned fixture directory/UID/nonce");
      int index=OPERATIONS.indexOf(operation);require(index>=0,"Unknown roster operation");
      var receipt=cwd.resolve("aether-roster-operation-"+operation+".json");require(!Files.exists(receipt),"Duplicate fixture observation");
      for(int previous=0;previous<index;previous++)require(Files.isRegularFile(cwd.resolve("aether-roster-operation-"+OPERATIONS.get(previous)+".json")),"Out-of-order roster operation");
      var player=LunarCore.getGameServer().getOnlinePlayerByUid(uid);require(player!=null,"Isolated player must be online");
      synchronized(player) {
        AetherDivideRosterFixtureSupport.execute(player,cwd,operation);
        Files.writeString(receipt,new Gson().toJson(Map.of("uid",uid,"nonce",nonce,"operation",operation)),StandardOpenOption.CREATE_NEW);
      }
      System.out.println("HKRPG_AETHER_ROSTER_ACK:"+uid+":"+operation+":"+nonce);System.out.flush();
    } catch(java.io.IOException failure) {throw new IllegalStateException(failure);}
  }
}
