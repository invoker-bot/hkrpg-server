package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.LunarCore;
import emu.lunarcore.command.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConsoleContinuationTest {
  @TempDir Path directory;

  @Command(label = "fixture-throw")
  public static class ThrowingCommand implements CommandHandler {
    public void execute(CommandArgs args) {
      System.out.println("FIXTURE_FAILURE_HANDLER");
      throw new IllegalStateException("isolated command failure");
    }
  }

  @Command(label = "fixture-after")
  public static class AfterCommand implements CommandHandler {
    public void execute(CommandArgs args) {
      System.out.println("FIXTURE_AFTER_HANDLER");
    }
  }

  @Command(label = "fixture-interrupt")
  public static class InterruptCommand implements CommandHandler {
    public void execute(CommandArgs args) {
      System.out.println("FIXTURE_CONTROL_SIGNAL");
      throw new UserInterruptException("controlled command interrupt");
    }
  }

  @Command(label = "fixture-eof")
  public static class EOFCommand implements CommandHandler {
    public void execute(CommandArgs args) {
      System.out.println("FIXTURE_CONTROL_SIGNAL");
      throw new EndOfFileException();
    }
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      System.out.println("CORE_ORIGIN=" + Path.of(LunarCore.class.getProtectionDomain()
          .getCodeSource().getLocation().toURI()).toRealPath());
      var manager = new CommandManager();
      manager.registerCommand(new ThrowingCommand());
      manager.registerCommand(new AfterCommand());
      manager.registerCommand(new InterruptCommand());
      manager.registerCommand(new EOFCommand());
      var field = LunarCore.class.getDeclaredField("commandManager");
      field.setAccessible(true);
      field.set(null, manager);
      var console = LunarCore.class.getDeclaredMethod("startConsole");
      console.setAccessible(true);
      console.invoke(null);
      System.out.println("FIXTURE_CONSOLE_RETURNED");
    }
  }

  String execute(String input) throws Exception {
    var java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    var log = directory.resolve("console.log");
    var runtime = System.getProperty("hkrpg.test.runtimeClasspath");
    assertNotNull(runtime, "Full child test runtime is required");
    var process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8",
        "-Djdk.net.unixdomain.tmpdir=" + directory.resolve("tcp-fallback-nonexistent"),
        "-cp", runtime, Probe.class.getName())
        .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      try (var stream = process.getOutputStream()) {
        stream.write(input.getBytes(StandardCharsets.UTF_8));
        stream.flush();
      }
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Console process did not finish at EOF");
      var output = Files.readString(log, StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      var expected = Path.of(LunarCore.class.getProtectionDomain().getCodeSource()
          .getLocation().toURI()).toRealPath();
      assertTrue(output.contains("CORE_ORIGIN=" + expected), output);
      return output;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Owned console child cleanup failed");
      }
    }
  }

  @Test void blankAndUnknownCommandsKeepConsoleUsableAndEOFStillExits() throws Exception {
    var output = execute("\nfixture-unknown\nfixture-after\n");
    assertTrue(output.contains("FIXTURE_AFTER_HANDLER"), output);
    assertFalse(output.contains("FIXTURE_CONSOLE_RETURNED"), output);
    assertFalse(output.contains("FIXTURE_FAILURE_HANDLER"), output);
  }

  @ParameterizedTest @ValueSource(ints = {1, 2})
  void actualCommandFailuresDoNotDiscardTheNextCommand(int failures) throws Exception {
    var output = execute("fixture-throw\n".repeat(failures) + "fixture-after\n");
    assertEquals(failures, output.split("FIXTURE_FAILURE_HANDLER", -1).length - 1, output);
    assertTrue(output.contains("FIXTURE_AFTER_HANDLER"), output);
    assertFalse(output.contains("FIXTURE_CONSOLE_RETURNED"), output);
  }
  @ParameterizedTest @ValueSource(strings = {"fixture-interrupt", "fixture-eof"})
  void commandRaisedTerminalSignalsPreserveTheExistingExitBoundary(String command) throws Exception {
    var output = execute(command + "\nfixture-after\n");
    assertTrue(output.contains("FIXTURE_CONTROL_SIGNAL"), output);
    assertFalse(output.contains("FIXTURE_AFTER_HANDLER"), output);
    assertFalse(output.contains("FIXTURE_CONSOLE_RETURNED"), output);
  }

}
