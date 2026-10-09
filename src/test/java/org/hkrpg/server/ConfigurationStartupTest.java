package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigurationStartupTest {
  @TempDir Path directory;

  public static class Probe {
    public static void main(String[] args) {
      emu.lunarcore.LunarCore.loadConfig();
      if (emu.lunarcore.LunarCore.getConfig().httpServer.bindAddress == null)
        throw new IllegalStateException("Missing defaults");
    }
  }

  int load() throws Exception {
    var java =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    var process =
        new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("hkrpg.test.runtimeClasspath"),
                Probe.class.getName())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("child.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Configuration initialization timed out");
      return process.exitValue();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"httpServer\":{\"bindPort\":18181}}",
        "{\"operatorNote\":\"保留自定义键与格式\",\"futureSection\":{\"value\":7}}",
        "{\r\n  \"protocol\": {\"wireVersion\": \"4.6.0\"}\r\n}\r\n"
      })
  void startingAndReloadingPreserveEveryOperatorByte(String text) throws Exception {
    var config = directory.resolve("config.json");
    Files.writeString(config, text);
    var original = Files.readAllBytes(config);
    assertEquals(0, load(), Files.readString(directory.resolve("child.log")));
    assertArrayEquals(original, Files.readAllBytes(config));
    assertEquals(0, load());
    assertArrayEquals(original, Files.readAllBytes(config));
  }

  @Test
  void aMissingConfigurationIsInstalledAndCanBeReadAgain() throws Exception {
    assertEquals(0, load());
    var original = Files.readAllBytes(directory.resolve("config.json"));
    assertNotNull(ConfigurationFiles.read(directory.resolve("config.json")));
    assertTrue(original.length > 0);
    assertEquals(0, load());
    assertArrayEquals(original, Files.readAllBytes(directory.resolve("config.json")));
  }

  @Test
  void anInvalidConfigurationStaysIntactAndTheChildFailsBeforeStarting() throws Exception {
    for (String invalid : new String[] {"", "{broken", "null"}) {
      Files.writeString(directory.resolve("config.json"), invalid);
      assertNotEquals(0, load());
      assertEquals(invalid, Files.readString(directory.resolve("config.json")));
    }
  }
}
