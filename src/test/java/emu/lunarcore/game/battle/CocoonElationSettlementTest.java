package emu.lunarcore.game.battle;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.CocoonExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo;
import emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.GameSession;
import it.unimi.dsi.fastutil.ints.IntList;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayState;
import org.hkrpg.server.gameplay.RogueResonance;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real 1029 resources and the exact rejected 70001 / 0 / 100000 client report. */
class CocoonElationSettlementTest {
  final List<CocoonExcel> oldCocoons = new ArrayList<>();
  final List<StageExcel> oldStages = new ArrayList<>();
  final BattleSettlementTest.Service service = new BattleSettlementTest.Service();
  final Farmer player = new Farmer();
  JsonObject fixture;

  static class Farmer extends BattleSettlementTest.TestPlayer {
    String version = "4.6.0";
    int stamina = 300, spends, nextBattle;
    final GameSession session = new GameSession(null) {
      @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion(version); }
    };
    @Override public GameSession getSession() { return session; }
    @Override public int getStamina() { return stamina; }
    @Override public int getNextBattleId() { return ++nextBattle; }
    @Override public void spendStamina(int amount) { stamina -= amount; spends++; }
  }

  @BeforeEach void load() throws Exception {
    oldCocoons.addAll(GameData.getCocoonExcelMap().values());
    oldStages.addAll(GameData.getStageExcelMap().values());
    GameData.getCocoonExcelMap().values().clear();
    GameData.getStageExcelMap().values().clear();
    try (var input = getClass().getResourceAsStream("/fixtures/cocoon-elation-settlement-4.6.json")) {
      fixture = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    var gson = ResourceLoader.getGson();
    GameData.getCocoonExcelMap().add(gson.fromJson(fixture.get("cocoon"), CocoonExcel.class));
    for (var raw : fixture.getAsJsonArray("stages")) {
      var stage = gson.fromJson(raw, StageExcel.class);
      stage.onLoad();
      GameData.getStageExcelMap().add(stage);
    }
    for (int id : new int[] {1003, 1101, 1102, 1211}) {
      var avatar = new GameAvatar(new Gson().fromJson("{\"AvatarID\":" + id + "}", AvatarExcel.class)) {
        @Override public void save() {}
        @Override public int getMaxSp() { return 1000; }
      };
      avatar.setOwner(player);
      player.getAvatars().getAvatars().put(id, avatar);
      player.getCurrentLineup().getAvatars().add(id);
    }
  }

  @AfterEach void restore() {
    GameData.getCocoonExcelMap().values().clear();
    oldCocoons.forEach(GameData.getCocoonExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
  }

  Battle start(int count) {
    var result = service.startCocoonWithStaminaRecovery(player, 1029, 0, count);
    assertEquals(0, result.retcode().getVal());
    assertNotNull(result.battle());
    assertEquals(Set.of(1003, 1101, 1102, 1211), result.battle().getParticipantIds());
    assertTrue(result.battle().getBattleEvents().isEmpty());
    assertTrue(result.battle().getActorBattleEvents().isEmpty());
    return result.battle();
  }

  BattleStatistics stats() {
    var stats = BattleStatistics.newInstance();
    for (int id : new int[] {1211, 1102, 1003, 1101})
      stats.addBattleAvatarList(AvatarBattleInfo.newInstance().setId(id).setAvatarStatus(
          AvatarProperty.newInstance().setMaxHp(1000).setLeftHp(800).setMaxSp(100).setLeftSp(0)));
    return stats;
  }

  List<RogueResonance.Energy> observed() {
    var report = fixture.getAsJsonObject("observed");
    return List.of(new RogueResonance.Energy(report.get("eventId").getAsInt(),
        report.get("current").getAsInt(), report.get("maximum").getAsInt()));
  }

  Battle finish(Battle battle, BattleEndStatus end, List<RogueResonance.Energy> rows) {
    return service.finishBattle(player, battle.getId(), battle.getStage().getId(), end, stats(), List.of(), rows);
  }

  @ParameterizedTest @ValueSource(ints = {1, 24})
  void actualDormantElationReportSettlesOneOrTwentyFourChallengesExactlyOnce(int count) {
    int entry = player.getEntryId();
    var visits = new HashMap<>(player.getGameplay().getStoryValues());
    var battle = start(count);
    assertEquals(300, player.getStamina());
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, observed()));
    assertEquals(300 - count * 10, player.getStamina());
    assertEquals(1, player.spends);
    assertEquals(1, service.grants);
    assertNull(player.getBattle());
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, observed()));
    assertEquals(1, player.spends);
    assertEquals(1, service.grants);
    assertEquals(entry, player.getEntryId(), "Direct farming must not unlock or teleport the world map");
    assertEquals(visits, player.getGameplay().getStoryValues());
    assertTrue(battle.getBattleEvents().isEmpty(), "Telemetry must not spawn another Elation event");
  }

  @Test void invalidEventsDoNotConsumeTheBattleAndTheObservedReportRemainsRetryable() {
    var battle = start(1);
    for (var rows : List.of(
        List.of(new RogueResonance.Energy(70002, 0, 100000)),
        List.of(new RogueResonance.Energy(70001, 1, 100000)),
        List.of(new RogueResonance.Energy(70001, 0, 99999)),
        List.of(new RogueResonance.Energy(70001, -1, -1)),
        List.of(new RogueResonance.Energy(70001, 100001, 100000)),
        List.of(new RogueResonance.Energy(70001, 0, 0)),
        List.of(new RogueResonance.Energy(70001, 0, 100000), new RogueResonance.Energy(70001, 0, 100000)))) {
      assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, rows));
      assertSame(battle, player.getBattle());
      assertFalse(battle.isSettling());
      assertEquals(300, player.getStamina());
      assertEquals(0, service.grants);
    }
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, observed()));
  }

  @Test void battleAndStageIdentityStillApplyBeforeAcceptingTheCounter() {
    var battle = start(1);
    assertNull(service.finishBattle(player, battle.getId() + 1, battle.getStage().getId(),
        BattleEndStatus.BATTLE_END_WIN, stats(), List.of(), observed()));
    assertNull(service.finishBattle(player, battle.getId(), battle.getStage().getId() + 1,
        BattleEndStatus.BATTLE_END_WIN, stats(), List.of(), observed()));
    assertEquals(0, service.grants);
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, observed()));
  }

  @Test void legacyClientsDoNotGainTheNativeCounterAndEmptyReportsStillWork() {
    player.version = "4.2.0";
    var battle = start(1);
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, observed()));
    assertEquals(300, player.getStamina());
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, List.of()));
  }

  @Test void otherStageTypesDoNotGainThisCocoonCompatibility() {
    BattleStage stage = new BattleStage() {
      public int getId() { return 1024810; }
      public StageType getStageType() { return StageType.Mainline; }
      public List<IntList> getMonsterWaves() { return List.of(); }
    };
    var battle = new Battle(player, player.getCurrentLineup(), stage);
    battle.setCocoonId(1029);
    battle.setCocoonWave(1);
    battle.setMappingInfoId(1029);
    battle.setStaminaCost(10);
    player.setBattle(battle);
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, observed()));
    assertSame(battle, player.getBattle());
    assertEquals(0, service.grants);
  }

  @Test void missingResourceOrMismatchedCocoonContextRemainsRejected() {
    var battle = start(1);
    battle.setCocoonId(999999);
    assertFalse(RogueResonance.validReport(battle, observed()));
    battle.setCocoonId(1029);
    battle.setMappingInfoId(1001);
    assertFalse(RogueResonance.validReport(battle, observed()));
    battle.setMappingInfoId(1029);
    battle.setStaminaCost(0);
    assertFalse(RogueResonance.validReport(battle, observed()));
    battle.setStaminaCost(10);
    assertTrue(RogueResonance.validReport(battle, observed()));
  }

  @ParameterizedTest @ValueSource(strings = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void losingOrQuittingWithTheDormantCounterDoesNotSpendOrGrant(String end) {
    var battle = start(1);
    assertNotNull(finish(battle, BattleEndStatus.valueOf(end), observed()));
    assertEquals(300, player.getStamina());
    assertEquals(0, player.spends);
    assertEquals(0, service.grants);
    assertNull(player.getBattle());
  }

  @Test void dormantCounterDoesNotOverwriteRogueResonanceEnergy() {
    player.getGameplay().setRogue(new Gson().fromJson("{\"resonanceSp\":2500}", GameplayState.RogueRun.class));
    var battle = start(1);
    assertNotNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, observed()));
    assertEquals(2500, player.getGameplay().getRogue().getResonanceSp());
  }
}
