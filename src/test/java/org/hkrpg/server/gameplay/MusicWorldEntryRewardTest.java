package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** The authored snowfield NPC must expose the same gates as its rewarding start/finish requests. */
class MusicWorldEntryRewardTest {
  static final int FLOOR = 20101001, PLANE = 20101, BADGE = 270003;
  final Gson gson = ResourceLoader.getGson();
  final List<Runnable> restore = new ArrayList<>();
  final List<GameplayDefinitions.Window> windows = new ArrayList<>();
  Actor player;
  MusicManager manager;
  MazePlaneExcel plane;
  GroupInfo group;
  JsonObject records;

  static class Actor extends Player {
    Scene scene;
    GameplayState restored;
    int badges, saves, level = 10;
    boolean failSave;
    final PlayerLineup team = new PlayerLineup(this, 0);
    final Inventory stock = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap map, boolean hint) {
        badges += map.get(BADGE);
        var items = new ArrayList<GameItem>();
        map.forEach(e -> items.add(new GameItem(GameData.getItemExcelMap().get(e.getIntKey()), e.getIntValue())));
        return new GrantResult(items, true);
      }
    };
    public int getLevel() { return level; }
    public PlayerLineup getCurrentLineup() { return team; }
    public int getPlaneId() { return PLANE; }
    public int getFloorId() { return FLOOR; }
    public GameplayState getGameplay() { return restored == null ? super.getGameplay() : restored; }
    public Scene getScene() { return scene; }
    public Inventory getInventory() { return stock; }
    public void sendPacket(BasePacket packet) {}
    public void save() { if (failSave) throw new IllegalStateException("injected save failure"); saves++; }
  }

  @BeforeEach void setup() throws Exception {
    try (var reader = new InputStreamReader(getClass().getResourceAsStream(
        "/fixtures/music-world-entry-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    windows.addAll(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    var panel = GameData.getActivityPanelExcelMap().get(50022);
    restore.add(() -> { if (panel == null) GameData.getActivityPanelExcelMap().keySet().remove(50022); else GameData.getActivityPanelExcelMap().add(panel); });
    GameData.getActivityPanelExcelMap().add(gson.fromJson(records.getAsJsonArray("ExcelOutput/ActivityPanel.json").get(0), ActivityPanelExcel.class));
    for (var row : records.getAsJsonArray("ExcelOutput/MusicRhythmGroup.json")) {
      var parsed = gson.fromJson(row, GameplayDefinitions.MusicGroup.class);
      var old = GameplayDefinitions.musicGroups.put(parsed.ID(), parsed);
      restore.add(() -> { if (old == null) GameplayDefinitions.musicGroups.remove(parsed.ID()); else GameplayDefinitions.musicGroups.put(parsed.ID(), old); });
    }
    for (var row : records.getAsJsonArray("ExcelOutput/MusicRhythmLevel.json")) {
      var parsed = gson.fromJson(row, GameplayDefinitions.MusicLevel.class);
      var old = GameplayDefinitions.musicLevels.put(parsed.ID(), parsed);
      restore.add(() -> { if (old == null) GameplayDefinitions.musicLevels.remove(parsed.ID()); else GameplayDefinitions.musicLevels.put(parsed.ID(), old); });
    }
    var oldItem = GameData.getItemExcelMap().get(BADGE);
    restore.add(() -> { if (oldItem == null) GameData.getItemExcelMap().keySet().remove(BADGE); else GameData.getItemExcelMap().add(oldItem); });
    GameData.getItemExcelMap().add(gson.fromJson(records.getAsJsonArray("ExcelOutput/ItemConfig.json").get(0), ItemExcel.class));
    for (var row : records.getAsJsonArray("ExcelOutput/RewardData.json")) {
      var reward = gson.fromJson(row, RewardExcel.class); reward.onLoad();
      var old = GameData.getRewardExcelMap().get(reward.getId());
      restore.add(() -> { if (old == null) GameData.getRewardExcelMap().keySet().remove(reward.getId()); else GameData.getRewardExcelMap().add(old); });
      GameData.getRewardExcelMap().add(reward);
    }
    var oldFloor = GameData.getFloorInfos().get("P20101_F20101001");
    restore.add(() -> { if (oldFloor == null) GameData.getFloorInfos().remove("P20101_F20101001"); else GameData.getFloorInfos().put("P20101_F20101001", oldFloor); });
    var floor = new FloorInfo(FLOOR);
    group = gson.fromJson(records.get("Config/LevelOutput/SharedRuntimeGroup/Groups_P20101_F20101001/LevelGroup_P20101_F20101001_G102.json"), GroupInfo.class);
    group.setId(102); floor.getGroups().put(102, group);
    GameData.getFloorInfos().put("P20101_F20101001", floor);
    var oldNpc = GameData.getNpcExcelMap().get(3025);
    restore.add(() -> { if (oldNpc == null) GameData.getNpcExcelMap().keySet().remove(3025); else GameData.getNpcExcelMap().add(oldNpc); });
    GameData.getNpcExcelMap().add(gson.fromJson(records.getAsJsonArray("ExcelOutput/NPCData.json").asList().stream()
        .filter(row -> row.getAsJsonObject().get("ID").getAsInt() == 3025).findFirst().orElseThrow(), NpcExcel.class));
    player = new Actor();
    plane = gson.fromJson(records.getAsJsonArray("ExcelOutput/MazePlane.json").asList().stream()
        .filter(row -> row.getAsJsonObject().get("PlaneID").getAsInt() == PLANE).findFirst().orElseThrow(), MazePlaneExcel.class);
    player.scene = new Scene(player, plane, FLOOR);
    manager = new MusicManager(player);
    assertFalse(ActivityAccess.unlocked(player, 50022));
    assertTrue(player.getGameplay().getSubMissions().isEmpty());
  }

  @AfterEach void cleanup() {
    Collections.reverse(restore); restore.forEach(Runnable::run);
    GameplayDefinitions.windows.clear(); GameplayDefinitions.windows.addAll(windows);
  }

  void openWindow() {
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50022, 5002202, 1, 4102444800L));
  }

  void completeStory() {
    var state = new QuestState(802610307); state.advance(1, 1, 123);
    player.getGameplay().getSubMissions().put(802610307, state);
  }

  void qualify() {
    player.level = 21; openWindow(); completeStory();
    player.scene = new Scene(player, plane, FLOOR);
  }

  boolean npcVisible() {
    return player.scene.getEntities().values().stream()
        .anyMatch(e -> e instanceof EntityNpc n && n.getNpcId() == 3025);
  }

  @Test void everyOriginalMusicNpcGroupKeepsTheLevelAndWindowGate() {
    for (var raw : records.getAsJsonArray("ExcelOutput/MusicRhythmGroup.json")) {
      var row = raw.getAsJsonObject();
      var entry = records.getAsJsonArray("ExcelOutput/MapEntrance.json").asList().stream()
          .map(JsonElement::getAsJsonObject).filter(e -> e.get("ID").equals(row.get("EntranceID"))).findFirst().orElseThrow();
      int planeId = entry.get("PlaneID").getAsInt(), floorId = entry.get("FloorID").getAsInt(), groupId = row.get("EntityGroup").getAsInt();
      String key = "P" + planeId + "_F" + floorId;
      var floor = new FloorInfo(floorId);
      var authored = gson.fromJson(records.get("Config/LevelOutput/SharedRuntimeGroup/Groups_" + key + "/LevelGroup_" + key + "_G" + groupId + ".json"), GroupInfo.class);
      authored.setId(groupId); floor.getGroups().put(groupId, authored);
      var old = GameData.getFloorInfos().put(key, floor);
      try {
        var definition = records.getAsJsonArray("ExcelOutput/MazePlane.json").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(p -> p.get("PlaneID").getAsInt() == planeId).findFirst().orElseThrow();
        var scene = new Scene(player, gson.fromJson(definition, MazePlaneExcel.class), floorId);
        assertFalse(scene.getLoadedGroups().contains(groupId), "Unqualified authored entry leaked: " + key + " group " + groupId);
      } finally {
        if (old == null) GameData.getFloorInfos().remove(key); else GameData.getFloorInfos().put(key, old);
      }
    }
  }

  @Test void aLevelTenPlayerCannotSeeOrExplicitlyLoadAnUnrewardingMusicEntry() {
    openWindow(); completeStory();
    player.scene = new Scene(player, plane, FLOOR);
    assertFalse(npcVisible(), "The playable NPC currently leaks past the level-21 start gate");
    player.scene.loadGroup(group);
    assertFalse(npcVisible());
    assertEquals(Retcode.MUSIC_LOCKED, manager.start(601));
    assertEquals(Retcode.MUSIC_LOCKED, manager.finish(3, false));
    assertEquals(0, player.badges);
  }

  @Test void levelTwentyOneStillRequiresTheOriginalGroupStoryAndOpenWindow() {
    player.level = 21; openWindow();
    player.scene = new Scene(player, plane, FLOOR);
    assertFalse(npcVisible(), "The authored 802610307 prerequisite is missing");
    completeStory(); GameplayDefinitions.windows.clear();
    player.scene = new Scene(player, plane, FLOOR);
    assertFalse(npcVisible(), "The activity window is closed");
  }

  @Test void aQualifiedEntryPaysTheActualFirstStarBadges() {
    qualify();
    assertTrue(npcVisible());
    assertEquals(Retcode.SUCC, manager.start(601));
    assertEquals(Retcode.SUCC, manager.finish(3, false));
    assertEquals(3, player.badges);
    assertEquals(3, player.getGameplay().getRhythm().getStars().get(601));
    assertTrue(player.getRewardLedger().granted("music:601:3"));
    assertEquals(Retcode.SUCC, manager.start(602));
    assertEquals(Retcode.SUCC, manager.finish(3, true));
    assertEquals(6, player.badges);
  }

  @Test void replayAndDuplicateFinishCannotIssueTheSameBadgesAgain() {
    qualify();
    assertEquals(Retcode.SUCC, manager.start(601));
    assertEquals(Retcode.SUCC, manager.finish(2, false));
    assertEquals(Retcode.MUSIC_LOCKED, manager.finish(3, false));
    assertEquals(2, player.badges);
    assertEquals(Retcode.SUCC, manager.start(601));
    assertEquals(Retcode.SUCC, manager.finish(3, true));
    assertEquals(3, player.badges);
  }

  @Test void originalHighestDifficultyKeepsItsSeparateUnlockGate() {
    qualify();
    assertEquals(Retcode.MUSIC_LOCKED, manager.start(603));
    assertEquals(0, player.badges);
    assertEquals(0, player.saves);
  }

  @Test void missionRefreshLoadsNewlyUnlockedEntryAndRetiresItWhenEligibilityEnds() {
    assertFalse(npcVisible());
    player.level = 21; openWindow(); completeStory();
    player.scene.refreshMissionGroups();
    assertTrue(npcVisible());
    player.getGameplay().getCancelledSubs().add(802610307);
    player.scene.refreshMissionGroups();
    assertFalse(npcVisible());
    assertFalse(player.scene.getLoadedGroups().contains(102));
  }

  @Test void acceptedEntrySurvivesSerializedPlayerProgressAndRejectsChangedSources() {
    qualify();
    assertEquals(Retcode.SUCC, manager.start(601));
    player.restored = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    assertEquals(Retcode.SUCC, new MusicManager(player).finish(1, false));
    assertEquals(1, player.badges);
    assertEquals(Retcode.SUCC, manager.start(602));
    GameplayDefinitions.musicGroups.put(6, new GameplayDefinitions.MusicGroup(6, 2, 123, List.of(22)));
    assertEquals(Retcode.MUSIC_LOCKED, manager.finish(3, false));
    assertEquals(1, player.badges);
  }

  @Test void aFailedStartSaveLeavesNoPlayableRunOrStaleRewardContext() {
    qualify();
    player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, manager.start(601));
    assertEquals(0, player.getGameplay().getRhythm().getActiveLevel());
    player.failSave = false;
    assertEquals(Retcode.MUSIC_LOCKED, manager.finish(3, false));
    assertEquals(Retcode.SUCC, manager.start(601));
  }
}
