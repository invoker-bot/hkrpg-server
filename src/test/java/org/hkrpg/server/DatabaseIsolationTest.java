package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.database.DatabaseManager;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.*;

class DatabaseIsolationTest {
  Config previousConfig;
  MongoServer previousServer;
  final Map<Field, Object> previousOwnership = new HashMap<>();
  Field configField, serverField;

  @BeforeEach
  void setup() throws Exception {
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    serverField = DatabaseManager.class.getDeclaredField("server");
    serverField.setAccessible(true);
    previousConfig = (Config) configField.get(null);
    previousServer = DatabaseManager.getServer();
    for (Field field : DatabaseManager.class.getDeclaredFields()) {
      if (field.getName().equals("ownedSettings")) {
        field.setAccessible(true);
        previousOwnership.put(field, field.get(null));
        field.set(null, null);
      }
    }
    serverField.set(null, null);
    var config = new Config();
    config.internalMongoServer.address = "127.0.0.1";
    config.internalMongoServer.filePath = "";
    configField.set(null, config);
  }

  @AfterEach
  void cleanup() throws Exception {
    var owned = DatabaseManager.getServer();
    if (owned != null && owned != previousServer) owned.shutdownNow();
    serverField.set(null, previousServer);
    configField.set(null, previousConfig);
    for (var entry : previousOwnership.entrySet()) entry.getKey().set(null, entry.getValue());
  }

  @Test
  void anOccupiedInternalPortMustFailWithoutOpeningTheConfiguredExternalDatabase()
      throws Exception {
    var external = new MongoServer(new MemoryBackend());
    external.bind("127.0.0.1", 0);
    var accidental = new AtomicReference<DatabaseManager>();
    try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      LunarCore.getConfig().internalMongoServer.port = occupied.getLocalPort();
      var info = new Config.DatabaseInfo();
      info.uri = external.getConnectionString();
      assertThrows(
          IllegalStateException.class,
          () -> accidental.set(new DatabaseManager(info, LunarCore.ServerType.DISPATCH)));
      assertNull(DatabaseManager.getServer());
    } finally {
      if (accidental.get() != null) accidental.get().close();
      external.shutdownNow();
    }
  }

  @Test
  void separateAccountAndGameManagersReuseTheOwnedInternalBackend() throws Exception {
    var external = new MongoServer(new MemoryBackend());
    external.bind("127.0.0.1", 0);
    try {
      try (var available = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        LunarCore.getConfig().internalMongoServer.port = available.getLocalPort();
      }
      var info = new Config.DatabaseInfo();
      info.uri = external.getConnectionString();
      var account = new DatabaseManager(info, LunarCore.ServerType.DISPATCH);
      try {
        var backend = DatabaseManager.getServer();
        account.getDatabase().getCollection("isolation_marker").insertOne(new Document("value", 7));
        var game = new DatabaseManager(info, LunarCore.ServerType.GAME);
        try {
          assertSame(backend, DatabaseManager.getServer());
          assertEquals(
              7,
              Objects.requireNonNull(
                      game.getDatabase().getCollection("isolation_marker").find().first())
                  .getInteger("value"));
        } finally {
          game.close();
        }
      } finally {
        account.close();
      }
    } finally {
      external.shutdownNow();
    }
  }

  @Test
  void aFailedBindDoesNotPublishAnUnboundInternalServer() throws Exception {
    try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      var info = LunarCore.getConfig().internalMongoServer;
      info.port = occupied.getLocalPort();
      assertThrows(RuntimeException.class, () -> DatabaseManager.startInternalMongoServer(info));
      assertNull(DatabaseManager.getServer());
    }
  }
}
