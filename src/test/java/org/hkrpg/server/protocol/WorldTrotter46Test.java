package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.BattleService;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.hkrpg.server.gameplay.GameplayState;
import org.hkrpg.server.gameplay.MissionBattleKills;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorldTrotter46Test {
  private static final int PLANE = 20001, FLOOR = 20001001, ENTRY = 2000101;
  private static final int GROUP = 28, TROTTER = 8002040, STAGE = 200011110;
  private static final String FLOOR_KEY = "P20001_F20001001";
  private final com.google.gson.Gson gson = ResourceLoader.getGson();
  private final Actor player = new Actor();
  private final BattleService battles = new BattleService(null) {
    protected void grantDrops(Battle battle) {}
  };
  private FloorInfo oldFloor;
  private MazePlaneExcel oldPlane;
  private MapEntranceExcel oldEntry;
  private NpcMonsterExcel oldNpc;
  private StageExcel oldStage;
  private ItemExcel oldJade;
  private FloorInfo floor;

  static class Actor extends Player {
    final LineupManager lineups = new LineupManager(this);
    final PlayerLineup team = new PlayerLineup(this, 0);
    Scene scene;
    boolean failSave;
    String saved;
    int savedJade;
    int saves;

    public String getName() { return "Trotter fixture"; }
    public int getPlaneId() { return scene == null ? 10001 : PLANE; }
    public int getFloorId() { return scene == null ? 10001001 : FLOOR; }
    public int getEntryId() { return scene == null ? 1000101 : ENTRY; }
    public int getWorldId() { return 101; }
    public Scene getScene() { return scene; }
    public LineupManager getLineupManager() { return lineups; }
    public PlayerLineup getCurrentLineup() { return team; }
    public Position getPos() { return new Position(); }
    public Position getRot() { return new Position(); }
    public boolean removeFoodBuffs(int type) { return false; }
    public void sendPacket(BasePacket packet) {}
    public void save() {
      if (failSave) throw new IllegalStateException("test save failed");
      saves++;
      saved = ResourceLoader.getGson().toJson(getGameplay());
      savedJade = getHcoin();
    }
  }

  @BeforeEach
  void install() throws Exception {
    oldFloor = GameData.getFloorInfo(PLANE, FLOOR);
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    oldEntry = GameData.getMapEntranceExcelMap().get(ENTRY);
    oldNpc = GameData.getNpcMonsterExcelMap().get(TROTTER);
    oldStage = GameData.getStageExcelMap().get(STAGE);
    oldJade = GameData.getItemExcelMap().get(1);
    try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/base-zone-trotter-4.6.json")), StandardCharsets.UTF_8)) {
      var source = JsonParser.parseReader(reader).getAsJsonObject();
      GameData.getMazePlaneExcelMap().add(gson.fromJson(source.get("plane"), MazePlaneExcel.class));
      GameData.getMapEntranceExcelMap().add(gson.fromJson(source.get("entry"), MapEntranceExcel.class));
      GameData.getNpcMonsterExcelMap().add(gson.fromJson(source.get("npc"), NpcMonsterExcel.class));
      GameData.getItemExcelMap().add(gson.fromJson(source.get("jade"), ItemExcel.class));
      var stage = gson.fromJson(source.get("stage"), StageExcel.class);
      stage.onLoad();
      GameData.getStageExcelMap().add(stage);
      floor = gson.fromJson("""
          {"FloorID":20001001,"GroupInstanceList":[{"ID":28}],
           "DimensionList":[{"GroupIndexList":[0]}]}
          """, FloorInfo.class);
      var group = gson.fromJson(source.get("group"), GroupInfo.class);
      group.setId(GROUP);
      floor.registerGroup(0, group);
      floor.onLoad();
      GameData.getFloorInfos().put(FLOOR_KEY, floor);
    }
    player.getGameplay().getStoryValues().put("floor:20001001:SceneStateByMainMission", 1);
    player.getDailyManager().ensureDay();
    player.saves = 0;
    enter(player);
  }

  @AfterEach
  void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR_KEY);
    else GameData.getFloorInfos().put(FLOOR_KEY, oldFloor);
    restore(GameData.getMazePlaneExcelMap(), PLANE, oldPlane);
    restore(GameData.getMapEntranceExcelMap(), ENTRY, oldEntry);
    restore(GameData.getNpcMonsterExcelMap(), TROTTER, oldNpc);
    restore(GameData.getStageExcelMap(), STAGE, oldStage);
    restore(GameData.getItemExcelMap(), 1, oldJade);
  }

  private static <T extends emu.lunarcore.data.resource.GameResource> void restore(
      emu.lunarcore.data.resource.ExcelMap<T> map, int id, T value) {
    if (value == null) map.keySet().remove(id);
    else map.add(value);
  }

  private void enter(Actor actor) {
    actor.scene = new Scene(actor, GameData.getMazePlaneExcelMap().get(PLANE), FLOOR);
    actor.scene.setEntryId(ENTRY);
  }

  private List<EntityMonster> actors(Actor actor) {
    return actor.scene.getEntitiesByGroup(EntityMonster.class, GROUP);
  }

  private Battle start() {
    var battle = new Battle(player, player.team, List.of(actors(player).getFirst()));
    assertEquals(STAGE, battle.getStage().getId());
    player.setBattle(battle);
    return battle;
  }

  private Battle finish(Battle battle, BattleEndStatus outcome, List<MissionBattleKills.Monster> report) {
    return battles.finishBattle(player, battle.getId(), STAGE, outcome,
        BattleStatistics.newInstance(), report);
  }

  private DynamicMessage query(Actor actor) throws Exception {
    var session = new Gameplay46HandlerTest.Session() {
      public Player getPlayer() { return actor; }
    };
    var id = Gameplay46.builder(session, "SceneIdentifier");
    Gameplay46.set(id, "floor_id", FLOOR);
    var request = Gameplay46.builder(session, "GetSceneMapInfoCsReq");
    Gameplay46.add(request, "scene_identifiers", id.build());
    assertTrue(SceneMap46.handle(session, session.getProtocol().clientCommand("GetSceneMapInfoCsReq"),
        request.build().toByteArray()));
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    return (DynamicMessage) ((List<?>) Gameplay46.value(session.response, "scene_map_info")).getFirst();
  }

  @Test
  void oneConfirmedKillPaysOnceAndRestoresBothMapAndSceneFromTheSavedDocument() throws Exception {
    var battle = start();
    var evidence = List.of(new MissionBattleKills.Monster(111, TROTTER, STAGE, 1));
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN, evidence));
    assertTrue(actors(player).isEmpty());
    assertEquals(60, player.getHcoin());
    assertEquals(60, battle.getDrops().getFirst().getCount());
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, evidence));
    assertEquals(60, player.getHcoin());
    assertEquals(List.of(1), Gameplay46.value(query(player), "finished_chest_monster_id_list"));
    var restored = new Actor();
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(restored, gson.fromJson(player.saved, GameplayState.class));
    var wallet = Player.class.getDeclaredField("hcoin");
    wallet.setAccessible(true);
    wallet.setInt(restored, player.savedJade);
    assertEquals(60, restored.getHcoin());
    assertEquals(List.of(1), Gameplay46.value(query(restored), "finished_chest_monster_id_list"));
    enter(restored);
    assertTrue(actors(restored).isEmpty());
    var differentPlayer = new Actor();
    enter(differentPlayer);
    assertEquals(1, actors(differentPlayer).size());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3})
  void anEscapedOrUnconfirmedTrotterRemainsAvailableAfterVictory(int cause) throws Exception {
    var battle = start();
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN,
        List.of(new MissionBattleKills.Monster(111, TROTTER, STAGE, cause))));
    assertEquals(1, actors(player).size());
    assertEquals(0, player.getHcoin());
    assertEquals(List.of(), Gameplay46.value(query(player), "finished_chest_monster_id_list"));
  }

  @Test
  void unboundOrDuplicateDeathsCannotSpendAnEncounter() throws Exception {
    var death = new MissionBattleKills.Monster(111, TROTTER, STAGE, 1);
    List<List<MissionBattleKills.Monster>> invalid = List.of(List.of(),
        List.of(new MissionBattleKills.Monster(111, TROTTER, STAGE + 10, 1)),
        List.of(death, new MissionBattleKills.Monster(112, TROTTER, STAGE, 1)),
        List.of(death, new MissionBattleKills.Monster(111, 8002020, STAGE, 1)));
    for (var report : invalid) {
      var battle = start();
      assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN, report));
      assertEquals(1, actors(player).size());
      assertEquals(0, player.getHcoin());
    }
    assertEquals(List.of(), Gameplay46.value(query(player), "finished_chest_monster_id_list"));
  }

  @Test
  void aFailedSaveLeavesTheBattleAndWalletAvailableForTheSameResultToRetry() {
    var battle = start();
    var evidence = List.of(new MissionBattleKills.Monster(111, TROTTER, STAGE, 2));
    player.failSave = true;
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, evidence));
    assertSame(battle, player.getBattle());
    assertEquals(1, actors(player).size());
    assertEquals(0, player.getHcoin());
    assertTrue(battle.getDrops().isEmpty());
    player.failSave = false;
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN, evidence));
    assertEquals(1, player.saves);
    assertEquals(60, player.getHcoin());
  }

  @Test
  void directDefeatUsesTheSameOneTimeRecord() throws Exception {
    var monster = actors(player).getFirst();
    assertTrue(player.scene.defeatMonster(monster));
    assertFalse(player.scene.defeatMonster(monster));
    assertEquals(List.of(1), Gameplay46.value(query(player), "finished_chest_monster_id_list"));
    assertEquals(60, player.getHcoin());
    enter(player);
    assertTrue(actors(player).isEmpty());
  }
}
