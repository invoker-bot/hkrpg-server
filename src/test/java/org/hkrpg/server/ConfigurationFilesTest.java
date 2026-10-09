package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationFilesTest {
  @TempDir Path directory;

  @Test
  void partialSectionsKeepTheirDefaults() throws Exception {
    Path file = directory.resolve("config.json");
    Files.writeString(
        file, "{\"httpServer\":{\"bindPort\":18080},\"gameServer\":{\"name\":\"My Server\"}}");
    var config = ConfigurationFiles.read(file);
    assertEquals(18080, config.httpServer.bindPort);
    assertEquals("127.0.0.1", config.httpServer.bindAddress);
    assertEquals(40, config.gameServer.kcpInterval);
    assertEquals("My Server", config.gameServer.name);
    assertEquals("hkrpg_local", config.gameServer.id);
  }

  @Test
  void invalidConfigurationIsPreserved() throws Exception {
    Path file = directory.resolve("config.json");
    for (String text : new String[] {"null", "{broken", "{\"httpServer\":null}"}) {
      Files.writeString(file, text);
      assertThrows(RuntimeException.class, () -> ConfigurationFiles.read(file));
      assertEquals(text, Files.readString(file));
    }
  }

  @Test
  void defaultsNeverOverwriteExistingOperatorBytesEvenWithUnknownKeys() throws Exception {
    var file = directory.resolve("config.json");
    var text = "{\"operatorNote\":\"保留\"}\r\n";
    Files.writeString(file, text);
    assertFalse(ConfigurationFiles.installDefaults(file, new emu.lunarcore.Config()));
    assertEquals(text, Files.readString(file));
  }

  @Test
  void onlyOneConcurrentInstallerCreatesTheActualDefaultFile() throws Exception {
    var file = directory.resolve("config.json");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    var gate = new java.util.concurrent.CountDownLatch(1);
    var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
    try {
      for (int i = 0; i < 16; i++) {
        final int port = 18080 + i;
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  var config = new emu.lunarcore.Config();
                  config.httpServer.bindPort = port;
                  return ConfigurationFiles.installDefaults(file, config) ? port : 0;
                }));
      }
      gate.countDown();
      var winners = new java.util.ArrayList<Integer>();
      for (var result : results) {
        int port = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
        if (port != 0) winners.add(port);
      }
      assertEquals(1, winners.size());
      assertEquals(
          winners.getFirst().intValue(), ConfigurationFiles.read(file).httpServer.bindPort);
    } finally {
      gate.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void aFailedExplicitSaveDoesNotTruncateThePreviousConfiguration() throws Exception {
    var file = directory.resolve("config.json");
    var text = "{\"httpServer\":{\"bindPort\":18181}}";
    Files.writeString(file, text);
    var invalid = new emu.lunarcore.Config();
    invalid.httpServer = null;
    assertThrows(IllegalArgumentException.class, () -> ConfigurationFiles.save(file, invalid));
    assertEquals(text, Files.readString(file));
  }

  @Test
  void anExplicitSaveWritesTheWholeUtf8ConfigurationAndRemovesItsTemporaryFile() throws Exception {
    var file = directory.resolve("config.json");
    Files.writeString(file, "{}");
    var config = new emu.lunarcore.Config();
    config.gameServer.name = "正式服务器";
    ConfigurationFiles.save(file, config);
    assertEquals("正式服务器", ConfigurationFiles.read(file).gameServer.name);
    try (var files = Files.list(directory)) {
      assertEquals(
          java.util.List.of("config.json"), files.map(p -> p.getFileName().toString()).toList());
    }
  }
}
