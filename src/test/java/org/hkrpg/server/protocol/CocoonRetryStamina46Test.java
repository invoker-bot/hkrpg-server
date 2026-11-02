package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual retry request with a saved enabled preference, low stamina, and real reserve. */
class CocoonRetryStamina46Test {
  final CocoonChallengeCount46Test resources = new CocoonChallengeCount46Test();
  final Gson gson = new Gson();
  JsonObject observed;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    int saves, notifications, allocations;
    boolean failSave;
    String persisted;
    @Override public LineupManager getLineupManager() { return teams; }
    @Override public int getNextBattleId() { return ++allocations; }
    @Override public void save() {
      saves++;
      if (failSave) throw new IllegalStateException("reserve persistence failed");
      persisted = new Gson().toJson(this, Player.class);
    }
    @Override public void sendPacket(BasePacket packet) { notifications++; }
  }

  class Session extends GameSession {
    final Actor owner = new Actor();
    DynamicMessage response;
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public Actor getPlayer() { return owner; }
    @Override public GameServer getServer() { return resources.server; }
    @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion("4.6.0"); }
    @Override public void sendClientPacket(String name, byte[] body) {
      try { response = getProtocol().clientSchema().parse(name, body); }
      catch (java.io.IOException error) { throw new AssertionError(error); }
    }
  }

  @BeforeEach void load() throws Exception {
    resources.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/cocoon-retry-stamina-4.6.json")) {
      observed = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
  }
  @AfterEach void restore() { resources.restore(); }

  Session player(boolean enabled, int stamina, double reserve) throws Exception {
    var s = new Session();
    for (var entry : java.util.Map.<String, Object>of("quickStaminaRecovery", enabled,
        "stamina", stamina, "staminaReserve", reserve).entrySet()) {
      var field = Player.class.getDeclaredField(entry.getKey());
      field.setAccessible(true); field.set(s.owner, entry.getValue());
    }
    return s;
  }

  void request(Session s, String name) throws Exception {
    var request = builder(s, name);
    set(request, "cocoon_id", 1001); set(request, "HECCOBFBJFI", 24);
    if (name.equals("StartCocoonStageCsReq")) set(request, "prop_entity_id", 33);
    assertTrue(VersionedPacketHandler.handle(s, s.getProtocol().clientCommand(name), request.build().toByteArray()));
  }

  void unchanged(Session s, int stamina, double reserve) {
    assertEquals(stamina, s.owner.getStamina());
    assertEquals(reserve, s.owner.getStaminaReserve());
    assertNull(s.owner.getBattle());
    assertEquals(0, s.owner.notifications);
  }

  @Test void observedTwentyFourChallengeRetryUsesExactlyTheMissingReserve() throws Exception {
    var state = observed.getAsJsonObject("playerState");
    double reserve = state.get("staminaReserve").getAsDouble();
    var s = player(state.get("quickStaminaRecovery").getAsBoolean(), state.get("stamina").getAsInt(), reserve);
    assertTrue(VersionedPacketHandler.handle(s, 1432,
        HexFormat.of().parseHex(observed.getAsJsonObject("observedRequest").get("bodyHex").getAsString())));
    assertEquals(0, number(s.response, "retcode"));
    assertEquals(24, s.owner.getBattle().getWaves().size());
    assertEquals(240, s.owner.getBattle().getStaminaCost());
    assertEquals(240, s.owner.getStamina(), "The 240-point battle charge remains at settlement");
    assertEquals(reserve - 180, s.owner.getStaminaReserve());
    assertEquals(1, s.owner.saves);
    assertEquals(1, s.owner.notifications);
    var persisted = gson.fromJson(s.owner.persisted, Player.class);
    assertEquals(240, persisted.getStamina());
    assertEquals(reserve - 180, persisted.getStaminaReserve());
    assertTrue(persisted.isQuickStaminaRecovery());
  }

  @ParameterizedTest @ValueSource(strings = {"StartCocoonStageCsReq", "QuickStartCocoonStageCsReq"})
  void bothNativeEntriesRefillOnceAndRejectRepeatedEntry(String name) throws Exception {
    var s = player(true, 60, 394.5);
    request(s, name);
    assertEquals(0, number(s.response, "retcode"));
    var battle = s.owner.getBattle();
    request(s, name);
    assertNotEquals(0, number(s.response, "retcode"));
    assertSame(battle, s.owner.getBattle());
    assertEquals(240, s.owner.getStamina());
    assertEquals(214.5, s.owner.getStaminaReserve());
    assertEquals(1, s.owner.saves);
    assertEquals(1, s.owner.notifications);
    assertEquals(1, s.owner.allocations);
  }

  @Test void disabledPreferenceDoesNotAuthorizeReserveUseAndReturnsStaminaError() throws Exception {
    var s = player(false, 60, 394);
    request(s, "StartCocoonStageCsReq");
    assertEquals(Retcode.LACK_STAMINA.getVal(), number(s.response, "retcode"));
    unchanged(s, 60, 394); assertEquals(0, s.owner.saves); assertEquals(0, s.owner.allocations);
  }

  @Test void insufficientFractionalReserveDoesNotPartiallyExchangeOrAllocate() throws Exception {
    var s = player(true, 60, 179.999);
    request(s, "StartCocoonStageCsReq");
    assertEquals(Retcode.LACK_STAMINA.getVal(), number(s.response, "retcode"));
    unchanged(s, 60, 179.999); assertEquals(0, s.owner.saves); assertEquals(0, s.owner.allocations);
  }

  @Test void enoughStaminaDoesNotRefillOrSaveThePlayer() throws Exception {
    var s = player(true, 240, 394);
    request(s, "StartCocoonStageCsReq");
    assertEquals(0, number(s.response, "retcode"));
    assertEquals(240, s.owner.getStamina()); assertEquals(394, s.owner.getStaminaReserve());
    assertEquals(0, s.owner.saves); assertEquals(0, s.owner.notifications);
  }

  @Test void missingStageAndMalformedRequestsCannotExchangeReserve() throws Exception {
    var s = player(true, 60, 394);
    GameData.getStageExcelMap().keySet().remove(resources.fixture.getAsJsonArray("stages").get(0).getAsJsonObject().get("StageID").getAsInt());
    request(s, "StartCocoonStageCsReq");
    assertNotEquals(0, number(s.response, "retcode"));
    unchanged(s, 60, 394); assertEquals(0, s.owner.saves);
    assertTrue(VersionedPacketHandler.handle(s, 1432, HexFormat.of().parseHex("38e907402150187001")));
    assertNotEquals(0, number(s.response, "retcode"));
    unchanged(s, 60, 394); assertEquals(0, s.owner.saves);
  }

  @Test void rejectedActiveActivityCannotExchangeReserve() throws Exception {
    var s = player(true, 60, 394);
    s.owner.getGameplay().getRhythm().start(1);
    request(s, "StartCocoonStageCsReq");
    assertNotEquals(0, number(s.response, "retcode"));
    unchanged(s, 60, 394); assertEquals(0, s.owner.saves); assertEquals(0, s.owner.allocations);
  }

  @Test void failedSaveRestoresBothBalancesAndDoesNotStartOrNotify() throws Exception {
    var s = player(true, 60, 394.5); s.owner.failSave = true;
    request(s, "StartCocoonStageCsReq");
    assertEquals(Retcode.SERVER_INTERNAL_ERROR.getVal(), number(s.response, "retcode"));
    unchanged(s, 60, 394.5); assertEquals(1, s.owner.saves);
    s.owner.failSave = false;
    request(s, "StartCocoonStageCsReq");
    assertEquals(0, number(s.response, "retcode"));
    assertEquals(240, s.owner.getStamina()); assertEquals(214.5, s.owner.getStaminaReserve());
  }

  @Test void legacyFactoryEntryDoesNotAcquireAutomaticReserveRecovery() throws Exception {
    var s = player(true, 60, 394);
    assertNull(resources.server.getBattleService().startCocoon(s.owner, 1001, 0, 24));
    unchanged(s, 60, 394); assertEquals(0, s.owner.saves); assertEquals(0, s.owner.allocations);
  }
}
