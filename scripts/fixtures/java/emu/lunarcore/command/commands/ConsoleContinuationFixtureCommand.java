package emu.lunarcore.command.commands;

import com.google.gson.JsonParser;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.HkrpgServer;

/** Isolated, explicitly registered console fixture. Never shipped in the runtime jar. */
@Command(label="consolecontinuationfixture", requireTarget=false)
public final class ConsoleContinuationFixtureCommand implements CommandHandler {
  private int failures;
  @Override public void execute(CommandArgs args) {
    try {
      if (args.getSender()!=null || args.size()!=2) throw new IllegalArgumentException("Owned console only");
      var control=JsonParser.parseString(Files.readString(Path.of("console-native-control.json"))).getAsJsonObject();
      if (!args.get(1).equals(control.get("nonce").getAsString())) throw new IllegalArgumentException("Foreign console nonce");
      var expected=Path.of(System.getProperty("console.expectedProductionJar")).toRealPath();
      for (var type:List.of(LunarCore.class, HkrpgServer.class, CommandManager.class)) {
        var origin=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!origin.equals(expected)) throw new IllegalStateException("Production CodeSource differs");
      }
      if (args.get(0).equals("throw")) {
        System.out.println("HKRPG_CONSOLE_FAILURE:"+args.get(1)+":"+(++failures));
        throw new IllegalStateException("owned console command failure");
      }
      if (!args.get(0).equals("observe")) throw new IllegalArgumentException("Unknown console fixture action");
      System.out.println("HKRPG_CONSOLE_OBSERVED:"+args.get(1)+":"+failures+":"+expected);
    } catch (RuntimeException failure) { throw failure; }
    catch (Exception failure) { throw new IllegalStateException("Owned console fixture validation failed",failure); }
  }
}
