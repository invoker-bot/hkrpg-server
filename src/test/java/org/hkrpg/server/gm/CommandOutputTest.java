package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.command.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CommandOutputTest {
  @Command(label = "gm_echo")
  public static class Echo implements CommandHandler {
    public void execute(CommandArgs args) {
      args.sendMessage(args.getRaw());
    }
  }

  @Command(label = "gm_target", requireTarget = true)
  public static class Target implements CommandHandler {
    public void execute(CommandArgs args) {
      fail("Missing target must not execute");
    }
  }

  @Test
  void capturesEachInvocationWithoutGlobalLogInterception() throws Exception {
    var manager = new CommandManager().registerCommand(new Echo()).registerCommand(new Target());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> manager.invokeConsole("/gm_echo first"));
      var second = executor.submit(() -> manager.invokeConsole("  !GM_ECHO    second  "));
      assertEquals(List.of("first"), first.get().output());
      assertEquals(List.of("second"), second.get().output());
      assertTrue(first.get().accepted());
    }
    assertFalse(manager.invokeConsole("does_not_exist").accepted());
    assertEquals(List.of("Invalid Command!"), manager.invokeConsole("").output());
    var target = manager.invokeConsole("gm_target @2147483000");
    assertFalse(target.accepted());
    assertTrue(target.output().getFirst().contains("offline"));
  }
}
