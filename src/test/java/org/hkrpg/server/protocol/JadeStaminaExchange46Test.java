package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Native empty request, original eight prices, actual balance persistence and reply identity. */
class JadeStaminaExchange46Test {
  @TempDir Path directory;
  String oldResources;
  final Map<Integer, ItemExcel> previous = new HashMap<>();
  JsonObject fixture;
  static final Gson JSON = new Gson();

  static class Actor extends Player {
    int saves, notifications;
    boolean failSave, failNotify;
    String persisted;
    @Override public void save() {
      saves++;
      if (failSave) throw new IllegalStateException("purchase storage failed");
      persisted = JSON.toJson(this, Player.class);
    }
    @Override public void sendPacket(BasePacket packet) {
      notifications++;
      if (failNotify) throw new IllegalStateException("notification transport failed");
    }
  }
  static class Session extends GameSession {
    Player owner = new Actor();
    String version = "4.6.0", responseName;
    DynamicMessage response;
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return owner; }
    @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion(version); }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try { responseName = name; response = getProtocol().clientSchema().parse(name, bytes); }
      catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    Actor actor() { return (Actor) owner; }
  }

  @BeforeEach void load() throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/jade-stamina-exchange-4.6.json")) {
      fixture = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    Files.createDirectories(directory.resolve("ExcelOutput"));
    Files.writeString(directory.resolve("ExcelOutput/StaminaSaleConfig.json"), fixture.get("sales").toString());
    oldResources = LunarCore.getConfig().getResourceDir();
    resourceRoot(directory.toString());
    for (var raw : fixture.getAsJsonArray("items")) {
      var item = emu.lunarcore.data.ResourceLoader.getGson().fromJson(raw, ItemExcel.class);
      previous.put(item.getId(), GameData.getItemExcelMap().get(item.getId()));
      GameData.getItemExcelMap().add(item);
    }
  }
  @AfterEach void restore() throws Exception {
    resourceRoot(oldResources);
    previous.forEach((id, row) -> {
      if (row == null) GameData.getItemExcelMap().keySet().remove(id);
      else GameData.getItemExcelMap().add(row);
    });
  }
  static void resourceRoot(String path) throws Exception {
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true); field.set(LunarCore.getConfig(), path);
  }
  static void set(Player p, String name, Object value) throws Exception {
    var field = Player.class.getDeclaredField(name); field.setAccessible(true); field.set(p, value);
  }
  Session player(int jade, int stamina) throws Exception {
    var s = new Session(); set(s.owner, "hcoin", jade); set(s.owner, "stamina", stamina);
    set(s.owner, "name", "isolated-jade");
    s.owner.getDailyManager().setClock(Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneOffset.UTC));
    return s;
  }
  DynamicMessage purchase(Session s, int id, byte[] body) throws Exception {
    s.response = null;
    var bytes = new java.io.ByteArrayOutputStream();
    var output = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    if (id != 0) output.writeUInt32(1, id);
    output.flush(); byte[] header = bytes.toByteArray();
    try (var ignored = s.getReplyCorrelation().enter(s.getProtocol(), 8, header)) {
      assertTrue(VersionedPacketHandler.handle(s, 8, body), "The native purchase must receive response68");
    }
    assertEquals("ExchangeStaminaScRsp", s.responseName);
    assertEquals(68, s.getProtocol().clientCommand(s.responseName));
    assertNotNull(s.response); assertTrue(s.response.getUnknownFields().asMap().isEmpty());
    return s.response;
  }
  int count(Session s) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 80, new byte[0]));
    return number(s.response, "exchange_times");
  }
  void unchanged(Session s, int jade, int stamina, int count) throws Exception {
    assertEquals(jade, s.owner.getHcoin()); assertEquals(stamina, s.owner.getStamina());
    assertEquals(count, count(s));
  }

  @Test void actualEmptyNativeRequestDeductsJadeAddsPowerAndRepliesWithTheActualCost() throws Exception {
    var s = player(1000, 0); var response = purchase(s, 1, new byte[0]);
    assertEquals(0, number(response, "retcode"));
    assertEquals(60, number(response, "stamina_add")); assertEquals(1, number(response, "exchange_times"));
    var rows = (List<?>) value(response, "item_cost_list"); assertEquals(1, rows.size());
    var pile = (DynamicMessage) value((DynamicMessage) rows.getFirst(), "pile_item");
    assertEquals(1, number(pile, "item_id")); assertEquals(50, number(pile, "item_num"));
    unchanged(s, 950, 60, 1); assertEquals(1, s.actor().saves); assertEquals(2, s.actor().notifications);
  }
  @Test void originalEightStepPricesAndTheDailyLimitAreEnforced() throws Exception {
    var s = player(1000, 0); int spent = 0;
    for (int index = 0; index < 8; index++) {
      int price = fixture.getAsJsonArray("sales").get(index).getAsJsonObject().getAsJsonObject("Price").get("1").getAsInt();
      var response = purchase(s, index + 1, new byte[0]); assertEquals(0, number(response, "retcode"));
      spent += price; unchanged(s, 1000 - spent, (index + 1) * 60, index + 1);
    }
    assertEquals(900, spent);
    assertEquals(Retcode.LACK_EXCHANGE_STAMINA_TIMES.getVal(), number(purchase(s, 9, new byte[0]), "retcode"));
    unchanged(s, 100, 480, 8); assertEquals(8, s.actor().saves); assertEquals(16, s.actor().notifications);
  }
  @Test void replayingTheSameRequestDoesNotChargeAgainButNewConfirmationsDo() throws Exception {
    var s = player(1000, 0); var first = purchase(s, 7, new byte[0]);
    assertEquals(first, purchase(s, 7, new byte[0])); unchanged(s, 950, 60, 1);
    assertEquals(0, number(purchase(s, 8, new byte[0]), "retcode"));
    assertEquals(first, purchase(s, 7, new byte[0])); unchanged(s, 875, 120, 2);
    assertEquals(2, s.actor().saves); assertEquals(4, s.actor().notifications);
  }
  @Test void insufficientJadeAndStorageLimitNeverPartiallyChargeOrGrant() throws Exception {
    var s = player(49, 0);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), number(purchase(s, 1, new byte[0]), "retcode"));
    unchanged(s, 49, 0, 0); assertEquals(0, s.actor().saves); assertEquals(0, s.actor().notifications);
    s = player(1000, 99950);
    assertEquals(Retcode.STAMINA_FULL.getVal(), number(purchase(s, 1, new byte[0]), "retcode"));
    unchanged(s, 1000, 99950, 0); assertEquals(0, s.actor().saves); assertEquals(0, s.actor().notifications);
  }
  @Test void failedSaveRestoresBalancesAndQuotaAndTheFailureReplyIsStable() throws Exception {
    var s = player(1000, 0); s.actor().failSave = true;
    var failed = purchase(s, 1, new byte[0]); assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), number(failed, "retcode"));
    unchanged(s, 1000, 0, 0); assertEquals(0, s.actor().notifications);
    s.actor().failSave = false;
    assertEquals(failed, purchase(s, 1, new byte[0])); unchanged(s, 1000, 0, 0);
    assertEquals(0, number(purchase(s, 2, new byte[0]), "retcode")); unchanged(s, 950, 60, 1);
  }
  @Test void dailyResetUsesTheExistingFourAmBoundaryAndClockRollbackDoesNotResetQuota() throws Exception {
    var s = player(1000, 0);
    s.owner.getDailyManager().setClock(Clock.fixed(Instant.parse("2026-11-01T19:59:59Z"), ZoneOffset.UTC));
    purchase(s, 1, new byte[0]); unchanged(s, 950, 60, 1);
    s.owner.getDailyManager().setClock(Clock.fixed(Instant.parse("2026-11-01T20:00:00Z"), ZoneOffset.UTC));
    assertEquals(0, count(s)); assertEquals(1, s.actor().saves, "A query cannot mutate day or balances");
    purchase(s, 2, new byte[0]); unchanged(s, 900, 120, 1);
    s.owner.getDailyManager().setClock(Clock.fixed(Instant.parse("2026-11-01T19:59:59Z"), ZoneOffset.UTC));
    purchase(s, 3, new byte[0]); unchanged(s, 825, 180, 2);
  }
  @Test void committedPurchaseStillRepliesAndCannotChargeAgainIfNotificationFails() throws Exception {
    var s = player(1000, 0); s.actor().failNotify = true;
    assertThrows(IllegalStateException.class, () -> purchase(s, 1, new byte[0]));
    var response = s.response; assertNotNull(response); assertEquals(0, number(response, "retcode"));
    unchanged(s, 950, 60, 1); s.actor().failNotify = false;
    assertEquals(response, purchase(s, 1, new byte[0])); unchanged(s, 950, 60, 1);
    assertEquals(1, s.actor().saves);
  }
  @Test void aFullReceiptCacheRejectsNewChargesRatherThanForgettingOldResponses() throws Exception {
    var s = player(0, 0);
    for (int id = 1; id <= 256; id++)
      assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), number(purchase(s, id, new byte[0]), "retcode"));
    set(s.owner, "hcoin", 1000);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), number(purchase(s, 257, new byte[0]), "retcode"));
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), number(purchase(s, 1, new byte[0]), "retcode"));
    unchanged(s, 1000, 0, 0); assertEquals(0, s.actor().saves);
  }
  @Test void malformedOrUnidentifiedRequestsAndUnauthenticatedSessionsCannotSpendJade() throws Exception {
    var s = player(1000, 0);
    for (byte[] body : List.of(new byte[] {8, 1}, new byte[] {(byte) 0x80}))
      assertEquals(5, number(purchase(s, 1, body), "retcode"));
    assertEquals(5, number(purchase(s, 0, new byte[0]), "retcode"));
    unchanged(s, 1000, 0, 0); assertEquals(0, s.actor().saves);
    s.response = null; s.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(s, 8, new byte[0])); assertNull(s.response);
    s.version = "4.2.0"; assertFalse(Profile46.handle(s, 8, new byte[0]));
  }
  @Test void missingOrInvalidWholePriceTablesCannotCreateFreePurchases() throws Exception {
    var path = directory.resolve("ExcelOutput/StaminaSaleConfig.json");
    for (String body : List.of("[]", "{}", "[{}]", "[null]", "[", "[{\"Times\":1,\"Price\":{\"1\":-1},\"ToStamina\":60}]")) {
      Files.writeString(path, body); var s = player(1000, 0);
      assertNotEquals(0, number(purchase(s, 1, new byte[0]), "retcode"));
      unchanged(s, 1000, 0, 0); assertEquals(0, s.actor().saves); assertEquals(0, s.actor().notifications);
    }
    Files.delete(path); var s = player(1000, 0);
    assertNotEquals(0, number(purchase(s, 1, new byte[0]), "retcode")); unchanged(s, 1000, 0, 0);
    for (String value : List.of("0", "1.5", "4294967297")) {
      var rows = fixture.getAsJsonArray("sales").deepCopy();
      rows.get(0).getAsJsonObject().getAsJsonObject("Price").add("1", JsonParser.parseString(value));
      Files.writeString(path, rows.toString()); s = player(1000, 0);
      assertNotEquals(0, number(purchase(s, 1, new byte[0]), "retcode")); unchanged(s, 1000, 0, 0);
    }
  }
  @Test void persistedPurchaseRestoresItsQuotaAndBalancesAndOldPlayersStartAtZero() throws Exception {
    var s = player(1000, 0); purchase(s, 1, new byte[0]);
    var restored = new Session(); restored.owner = JSON.fromJson(s.actor().persisted, Actor.class);
    restored.owner.getDailyManager().setClock(Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneOffset.UTC));
    unchanged(restored, 950, 60, 1);
    purchase(restored, 1, new byte[0]); unchanged(restored, 875, 120, 2);
    assertEquals(0, count(player(1000, 0)));
  }
}
