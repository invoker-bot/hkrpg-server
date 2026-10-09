package emu.lunarcore.game.account;

import static org.junit.jupiter.api.Assertions.*;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.util.Snowflake32;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.*;

class AccountCreationTest {
  MongoServer backend;
  DatabaseManager database;
  final Map<Field, Object> saved = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    for (String name : List.of("config", "accountDatabase")) {
      var f = LunarCore.class.getDeclaredField(name);
      f.setAccessible(true);
      saved.put(f, f.get(null));
    }
    var settings = new Config();
    saved.keySet().stream()
        .filter(f -> f.getName().equals("config"))
        .findFirst()
        .orElseThrow()
        .set(null, settings);
    backend = new MongoServer(new MemoryBackend());
    var address = backend.bind();
    var info = new Config.DatabaseInfo();
    info.useInternal = false;
    info.uri = "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true";
    database = new DatabaseManager(info, LunarCore.ServerType.DISPATCH);
    saved.keySet().stream()
        .filter(f -> f.getName().equals("accountDatabase"))
        .findFirst()
        .orElseThrow()
        .set(null, database);
  }

  @AfterEach
  void cleanup() throws Exception {
    if (database != null) database.close();
    if (backend != null) backend.shutdownNow();
    for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
  }

  static void set(Object target, String name, Object value) throws Exception {
    var f = Account.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  @Test
  void anExistingIdIsNeverOverwrittenWhenAnAllocatorRepeatsIt() throws Exception {
    int anchor = Snowflake32.newUid();
    var originals = new ArrayList<Account>();
    for (int i = 1; i <= 512; i++) {
      var old = new Account();
      set(old, "uid", Integer.toString(Math.addExact(anchor, i)));
      set(old, "username", "preserved-" + i);
      set(old, "comboToken", "preserved-token-" + i);
      set(old, "dispatchToken", "preserved-dispatch-" + i);
      set(old, "permissions", new HashSet<>(Set.of("preserved.permission")));
      old.setReservedPlayerUid(20000 + i);
      database.getDatastore().insert(old);
      originals.add(old);
    }
    var created = AccountHelper.createAccount("newcomer", null, 30000);
    assertNotNull(created);
    assertEquals(513, database.getDatastore().find(Account.class).count());
    assertEquals(30000, created.getReservedPlayerUid());
    for (var old : originals) {
      var restored = database.getObjectByField(Account.class, "_id", old.getUid());
      assertEquals(old.getUsername(), restored.getUsername());
      assertEquals(old.getComboToken(), restored.getComboToken());
      assertEquals(old.getDispatchToken(), restored.getDispatchToken());
      assertEquals(old.getReservedPlayerUid(), restored.getReservedPlayerUid());
      assertEquals(old.getPermissions(), restored.getPermissions());
    }
  }

  @Test
  void aDuplicateUsernamePreservesTheOriginalIdentityAndTokens() {
    var first = AccountHelper.createAccount("same-name", null, 25000);
    assertNotNull(first);
    String token = first.generateComboToken();
    assertNull(AccountHelper.createAccount("same-name", null, 31000));
    var restored = database.getObjectByField(Account.class, "username", "same-name");
    assertEquals(first.getUid(), restored.getUid());
    assertEquals(token, restored.getComboToken());
    assertEquals(25000, restored.getReservedPlayerUid());
    assertEquals(1, database.getDatastore().find(Account.class).count());
  }

  @Test
  void aConcurrentDuplicateUsernameCreatesOneAccount() throws Exception {
    var created = new java.util.concurrent.atomic.AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var jobs = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 24; i++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  if (AccountHelper.createAccount("concurrent", null, 26000) != null)
                    created.incrementAndGet();
                  return null;
                }));
      start.countDown();
      for (var job : jobs) job.get();
    }
    assertEquals(1, created.get());
    assertEquals(1, database.getDatastore().find(Account.class).count());
    assertEquals(
        26000,
        database.getObjectByField(Account.class, "username", "concurrent").getReservedPlayerUid());
  }

  @Test
  void denseAccountCreationKeepsEveryPreviouslyIssuedToken() {
    var originals = new ArrayList<Account>();
    for (int i = 0; i < 48; i++) {
      var account = AccountHelper.createAccount("burst-" + i, null, 27000 + i);
      assertNotNull(account);
      account.generateDispatchToken();
      account.generateComboToken();
      originals.add(account);
    }
    assertEquals(48, database.getDatastore().find(Account.class).count());
    assertEquals(48, originals.stream().map(Account::getUid).distinct().count());
    for (var original : originals) {
      var restored = database.getObjectByField(Account.class, "_id", original.getUid());
      assertEquals(original.getUsername(), restored.getUsername());
      assertEquals(original.getDispatchToken(), restored.getDispatchToken());
      assertEquals(original.getComboToken(), restored.getComboToken());
      assertEquals(original.getReservedPlayerUid(), restored.getReservedPlayerUid());
    }
  }
}
