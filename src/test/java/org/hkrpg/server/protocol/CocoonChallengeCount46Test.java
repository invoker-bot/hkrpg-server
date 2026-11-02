package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.CocoonExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.BattleService;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Observed CN4.6 click, pinned original 1001 stages, and the real cocoon battle factory. */
class CocoonChallengeCount46Test {
  final List<CocoonExcel> oldCocoons = new ArrayList<>();
  final List<StageExcel> oldStages = new ArrayList<>();
  JsonObject fixture;
  GameServer server;

  static class Farmer extends Player {
    final LineupManager teams = new LineupManager(this);
    int stamina = 300;
    int allocatedBattles;

    @Override public LineupManager getLineupManager() { return teams; }
    @Override public int getStamina() { return stamina; }
    @Override public int getNextBattleId() { return ++allocatedBattles; }
  }

  class Session extends GameSession {
    final Farmer owner = new Farmer();
    DynamicMessage response;
    String responseName;

    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public Farmer getPlayer() { return owner; }
    @Override public GameServer getServer() { return server; }
    @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion("4.6.0"); }
    @Override public void sendClientPacket(String name, byte[] body) {
      try {
        responseName = name;
        response = getProtocol().clientSchema().parse(name, body);
      } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
  }

  @BeforeEach void setup() throws Exception {
    oldCocoons.addAll(GameData.getCocoonExcelMap().values());
    oldStages.addAll(GameData.getStageExcelMap().values());
    GameData.getCocoonExcelMap().values().clear();
    GameData.getStageExcelMap().values().clear();
    try (var input = getClass().getResourceAsStream("/fixtures/cocoon-challenge-count-4.6.json")) {
      fixture = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
    var gson = emu.lunarcore.data.ResourceLoader.getGson();
    GameData.getCocoonExcelMap().add(gson.fromJson(fixture.get("cocoon"), CocoonExcel.class));
    for (var raw : fixture.getAsJsonArray("stages")) {
      var stage = gson.fromJson(raw, StageExcel.class);
      stage.onLoad();
      GameData.getStageExcelMap().add(stage);
    }
    // Allocate only the service holder; no socket, timer, operator config, or database is started.
    var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    singleton.setAccessible(true);
    server = (GameServer) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(GameServer.class);
    var service = GameServer.class.getDeclaredField("battleService");
    service.setAccessible(true);
    service.set(server, new BattleService(null));
  }

  @AfterEach void restore() {
    GameData.getCocoonExcelMap().values().clear();
    oldCocoons.forEach(GameData.getCocoonExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
  }

  DynamicMessage.Builder request(Session s, String name, String countField, int count) {
    var b = DynamicMessage.newBuilder(s.getProtocol().clientSchema().message(name));
    set(b, "cocoon_id", 1001);
    set(b, countField, count);
    if (name.equals("StartCocoonStageCsReq")) set(b, "prop_entity_id", 33);
    return b;
  }

  void dispatch(Session s, String name, byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, s.getProtocol().clientCommand(name), bytes));
    assertEquals(name.replace("CsReq", "ScRsp"), s.responseName);
    assertTrue(s.response.getUnknownFields().asMap().isEmpty());
  }

  void accepted(Session s, int count) {
    assertEquals(0, number(s.response, "retcode"));
    var battle = s.owner.getBattle();
    assertNotNull(battle);
    assertEquals(1001, battle.getCocoonId());
    assertEquals(count, battle.getCocoonWave());
    assertEquals(count * 10, battle.getStaminaCost());
    assertEquals(count, battle.getWaves().size());
    assertEquals(300, s.owner.stamina, "Entry must keep the existing settlement-time charge");
    assertEquals(1, s.owner.allocatedBattles);
    var info = (DynamicMessage) value(s.response, "battle_info");
    assertEquals(battle.getId(), number(info, "battle_id"));
    assertEquals(count, ((List<?>) value(info, "monster_wave_list")).size());
    assertEquals(1001, number(s.response, "cocoon_id"));
    assertEquals(count, number(s.response, "wave"));
  }

  @Test void actualGoldenCalyxClickStartsItsRequestedBattleInsteadOfMessageError() throws Exception {
    var s = new Session();
    var observed = fixture.getAsJsonObject("observedRequest");
    dispatch(s, "StartCocoonStageCsReq", HexFormat.of().parseHex(observed.get("bodyHex").getAsString()));
    accepted(s, 1);
    assertEquals(1, number(s.response, "challenge_cnt"));
    assertEquals(33, number(s.response, "prop_entity_id"));
  }

  @ParameterizedTest @ValueSource(strings = {"StartCocoonStageCsReq", "QuickStartCocoonStageCsReq"})
  void nativeCountWorksForSingleAndResourceMaximumBatch(String name) throws Exception {
    for (int count : new int[] {1, 24}) {
      var s = new Session();
      dispatch(s, name, request(s, name, "HECCOBFBJFI", count).build().toByteArray());
      accepted(s, count);
      if (name.equals("StartCocoonStageCsReq")) {
        assertEquals(count, number(s.response, "challenge_cnt"));
        assertEquals(33, number(s.response, "prop_entity_id"));
      }
    }
  }

  @ParameterizedTest @ValueSource(strings = {"StartCocoonStageCsReq", "QuickStartCocoonStageCsReq"})
  void previouslySupportedWaveOnlyRequestsStillCreateTheRequestedBatch(String name) throws Exception {
    var s = new Session();
    dispatch(s, name, request(s, name, "wave", 3).build().toByteArray());
    accepted(s, 3);
  }

  @ParameterizedTest @ValueSource(strings = {"StartCocoonStageCsReq", "QuickStartCocoonStageCsReq"})
  void missingExcessiveAndMixedCountsDoNotAllocateOrChargeABattle(String name) throws Exception {
    for (int count : new int[] {0, 25, -1}) {
      var s = new Session();
      dispatch(s, name, request(s, name, "HECCOBFBJFI", count).build().toByteArray());
      rejected(s);
    }
    for (int wave : new int[] {1, 2}) {
      var s = new Session();
      var b = request(s, name, "HECCOBFBJFI", 1);
      set(b, "wave", wave);
      dispatch(s, name, b.build().toByteArray());
      rejected(s);
    }
  }

  @Test void duplicateWideUnknownAndUnimplementedNestedOptionsRemainRejected() throws Exception {
    String[] bodies = {
        "38e907402150015002", // Duplicate new count.
        "38e9074021508180808010", // 2^32+1 must not alias one challenge.
        "38e907402150017001", // Unknown field 14.
        "38e907402150017a00", // Unimplemented nested context.
        "38e90740215080" // Truncated new count.
    };
    for (String hex : bodies) {
      var s = new Session();
      dispatch(s, "StartCocoonStageCsReq", HexFormat.of().parseHex(hex));
      rejected(s);
    }
  }

  @Test void nativeCountKeepsStaminaAuthenticationAndActiveBattleChecks() throws Exception {
    var s = new Session();
    s.owner.stamina = 9;
    dispatch(s, "StartCocoonStageCsReq", request(s, "StartCocoonStageCsReq", "HECCOBFBJFI", 1).build().toByteArray());
    assertNotEquals(0, number(s.response, "retcode"));
    assertNull(s.owner.getBattle());
    assertEquals(0, s.owner.allocatedBattles);
    assertEquals(9, s.owner.stamina);

    s = new Session();
    s.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(s, 1432, HexFormat.of().parseHex("38e90740215001")));
    assertNull(s.response);
    assertNull(s.owner.getBattle());
    assertEquals(0, s.owner.allocatedBattles);

    s = new Session();
    var bytes = request(s, "StartCocoonStageCsReq", "HECCOBFBJFI", 1).build().toByteArray();
    dispatch(s, "StartCocoonStageCsReq", bytes);
    accepted(s, 1);
    var existing = s.owner.getBattle();
    dispatch(s, "StartCocoonStageCsReq", bytes);
    assertNotEquals(0, number(s.response, "retcode"));
    assertSame(existing, s.owner.getBattle());
    assertEquals(1, s.owner.allocatedBattles);
  }

  void rejected(Session s) {
    assertNotEquals(0, number(s.response, "retcode"));
    assertNull(s.owner.getBattle());
    assertEquals(0, s.owner.allocatedBattles);
    assertEquals(300, s.owner.stamina);
    assertFalse(s.response.hasField(s.response.getDescriptorForType().findFieldByName("battle_info")));
  }
}
