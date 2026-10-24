package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.*;
import emu.lunarcore.game.player.lineup.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

/** Original sources and real avatar/lineup documents in a disposable Mongo fixture, not game UI. */
class StoryParty46Test {
  static DatabaseManager database, oldDatabase;
  static de.bwaldvogel.mongo.MongoServer mongo;
  final Gson gson = new Gson();
  final Map<Object, List<?>> oldRows = new LinkedHashMap<>();
  Map<Integer, MissionInfo> oldMissions;
  Map<Integer, MissionInfo.SubMissionInfo> oldSubs;
  Actor player;

  static class Actor extends Player {
    final List<BasePacket> packets = new ArrayList<>();
    int saves, failAtSave = -1;
    boolean failAfterAvatar;

    Actor() {
      MissionTrialProvisioning46Test.set(this, "uid", 19996789);
      MissionTrialProvisioning46Test.set(this, "name", "Story fixture");
      MissionTrialProvisioning46Test.set(this, "pos", new Position());
      MissionTrialProvisioning46Test.set(this, "rot", new Position());
      MissionTrialProvisioning46Test.set(this, "lineupManager", new LineupManager(this));
      MissionTrialProvisioning46Test.set(this, "avatars", new GrantStorage(this));
      var unlocks = database.getObjectByField(PlayerUnlockData.class, "ownerUid", getUid());
      if (unlocks == null) unlocks = new PlayerUnlockData(this);
      else {
        try {
          var owner = PlayerUnlockData.class.getDeclaredField("owner");
          owner.setAccessible(true); owner.set(unlocks, this);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
      }
      MissionTrialProvisioning46Test.set(this, "unlocks", unlocks);
    }

    @Override public void sendPacket(BasePacket packet) { packets.add(packet); }

    @Override public void save() {
      if (++saves == failAtSave) throw new IllegalStateException("injected player save failure");
      var stored = new Player();
      MissionTrialProvisioning46Test.set(stored, "uid", getUid());
      MissionTrialProvisioning46Test.set(stored, "gameplay", getGameplay());
      MissionTrialProvisioning46Test.set(stored, "curAvatarPaths", new HashMap<>(getCurAvatarPaths()));
      MissionTrialProvisioning46Test.set(stored, "gender", getGender());
      database.save(stored);
    }
  }

  static class GrantStorage extends emu.lunarcore.game.avatar.AvatarStorage {
    GrantStorage(Actor owner) { super(owner); }
    @Override public boolean addAvatar(GameAvatar avatar) {
      boolean result = super.addAvatar(avatar);
      var owner = (Actor) getPlayer();
      if (owner.failAfterAvatar) {
        owner.failAfterAvatar = false;
        // Simulate a durable write followed by interruption before the live cache was installed.
        getAvatars().remove(avatar.getAvatarId());
        getAvatarObjectIdMap().remove(avatar.getId());
        throw new IllegalStateException("injected interruption after avatar write");
      }
      return result;
    }
  }

  @BeforeAll static void storage() throws Exception {
    mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    var info = new Config.DatabaseInfo();
    info.uri = "mongodb://127.0.0.1:" + address.getPort();
    info.collection = "story_party_owned_fixture";
    info.useInternal = false;
    oldDatabase = LunarCore.getGameDatabase();
    database = new DatabaseManager(info, LunarCore.ServerType.GAME);
    BookPickup46Test.setDatabase(database);
  }

  @AfterAll static void close() throws Exception {
    BookPickup46Test.setDatabase(oldDatabase);
    if (database != null) database.close();
    if (mongo != null) mongo.shutdownNow();
  }

  @BeforeEach void load() throws Exception {
    oldMissions = new HashMap<>(GameData.getMissionInfos());
    oldSubs = new HashMap<>(GameplayDefinitions.subs);
    for (var map : List.of(GameData.getMainMissionExcelMap(), GameData.getAvatarExcelMap(),
        GameData.getItemExcelMap(), GameData.getRewardExcelMap(), GameData.getMultiplePathAvatarExcelMap())) {
      oldRows.put(map, new ArrayList<>(map.values()));
      map.values().clear();
    }
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    JsonObject records;
    try (var input = getClass().getResourceAsStream("/fixtures/story-party-4.6.json")) {
      records = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonObject("records");
    }
    for (var entry : records.entrySet()) {
      var path = entry.getKey();
      var value = entry.getValue();
      if (path.endsWith(".partial.json")) {
        for (var sub : gson.fromJson(value, MissionInfo.class).getSubMissionList()) {
          var task = records.get(sub.getMissionJsonPath());
          if (task != null) {
            var definition = gson.fromJson(task, MissionTaskInfo.class);
            definition.retainSourceDefinition(task);
            sub.setTask(definition);
          }
          GameplayDefinitions.subs.put(sub.getId(), sub);
        }
      } else if (path.contains("/MissionInfo_")) {
        var main = gson.fromJson(value, MissionInfo.class);
        GameData.getMissionInfos().put(main.getMainMissionID(), main);
      } else if (path.startsWith("ExcelOutput/")) {
        for (var row : value.getAsJsonArray()) {
          if (path.endsWith("/MainMission.json")) GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          else if (path.endsWith("/AvatarConfig.json")) GameData.getAvatarExcelMap().add(ResourceLoader.getGson().fromJson(row, AvatarExcel.class));
          else if (path.endsWith("/MultiplePathAvatarConfig.json")) GameData.getMultiplePathAvatarExcelMap().add(gson.fromJson(row, MultiplePathAvatarExcel.class));
          else if (path.endsWith("/RewardData.json")) {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          } else GameData.getItemExcelMap().add(ResourceLoader.getGson().fromJson(row, ItemExcel.class));
        }
      }
    }
    player = new Actor();
    player.getAvatars().validateMultiPaths();
    assertTrue(player.addAvatar(new GameAvatar(8001)));
    player.getCurrentLineup().getAvatars().add(8001);
    player.getCurrentLineup().save();
  }

  @AfterEach @SuppressWarnings({"rawtypes", "unchecked"}) void restore() {
    for (var entry : oldRows.entrySet()) {
      var map = (emu.lunarcore.data.resource.ExcelMap) entry.getKey();
      map.values().clear();
      for (var row : entry.getValue()) map.add((emu.lunarcore.data.resource.GameResource) row);
    }
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissions);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    database.getDatabase().drop();
  }

  void completedSub(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 100);
    player.getGameplay().getSubMissions().put(id, state);
  }

  void wake() {
    player.getGameplay().getStoryValues().put("profile:character-path", 8002);
    player.getCurAvatarPaths().put(8001, 8002);
    MissionTrialProvisioning46Test.set(player, "gender", PlayerGender.GENDER_WOMAN);
    player.getAvatarById(8001).setMultiPath(player.getAvatars().getMultiPathById(8002));
    var main = new QuestState(1000101);
    main.advance(1, 1, 100);
    main.reserveReward(); main.closeReward();
    player.getGameplay().getMainMissions().put(1000101, main);
    completedSub(100020103);
  }

  Actor reload() {
    var stored = database.getObjectByUid(Player.class, player.getUid());
    var next = new Actor();
    MissionTrialProvisioning46Test.set(next, "gameplay", stored.getGameplay());
    MissionTrialProvisioning46Test.set(next, "curAvatarPaths", new HashMap<>(stored.getCurAvatarPaths()));
    MissionTrialProvisioning46Test.set(next, "gender", stored.getGender());
    next.getAvatars().loadFromDatabase();
    next.getLineupManager().loadFromDatabase();
    player = next;
    return next;
  }

  void finishElevator() {
    wake();
    completedSub(100020109);
    player.getGameplay().getMainMissions().put(1000201, new QuestState(1000201));
    player.getMissionManager().refresh();
  }

  @Test void completedWakingDialogueRepairsMissingMarchAndJoinsHerWithoutRewindingStory() {
    wake();
    String before = gson.toJson(player.getGameplay().getSubMissions().get(100020103));
    player.getAvatarById(8001).setLevel(12);
    player.getAvatarById(8001).setExp(6026);
    player.getMissionManager().refresh();
    assertNotNull(player.getAvatarById(1001), "Completed wake dialogue must deliver March 7th");
    assertEquals(List.of(8001, 1001), player.getCurrentLineup().getAvatars());
    assertEquals(before, gson.toJson(player.getGameplay().getSubMissions().get(100020103)));
    assertEquals(12, player.getAvatarById(8001).getLevel());
    assertEquals(6026, player.getAvatarById(8001).getExp());
  }

  @Test void loginRepairPersistsTheCharacterHeadIconBeforeCompletingTheGrant() {
    wake();
    assertFalse(player.isLoggedIn(), "Login reconciliation runs before publishing the session");
    player.getMissionManager().refresh();
    int icon = player.getAvatarById(1001).getHeadIconId();
    assertTrue(player.getUnlocks().getHeadIcons().contains(icon));
    var stored = database.getObjectByField(PlayerUnlockData.class, "ownerUid", player.getUid());
    assertTrue(stored.getHeadIcons().contains(icon),
        "A completed formal grant must not leave its head icon only in the login cache");
  }

  @Test void originalDanHengRewardAlsoJoinsThePartyAfterTheElevatorDialogue() {
    finishElevator();
    assertNotNull(player.getAvatarById(1002));
    assertEquals(List.of(8001, 1001, 1002), player.getCurrentLineup().getAvatars());
  }

  @Test void twoDocumentReloadsAndRepeatedRefreshDoNotRepeatGrantsOrUndoManualEdits() {
    finishElevator();
    assertEquals(2, player.getInventory().getMaterialByItemId(211).getCount());
    var march = player.getAvatarById(1001);
    march.setLevel(7); march.setExp(321); march.save();
    player.getCurrentLineup().getAvatars().remove(Integer.valueOf(1001));
    player.getCurrentLineup().setLeader(1);
    player.getCurrentLineup().save();
    for (int n = 0; n < 2; n++) {
      reload();
      player.getInventory().loadFromDatabase();
      player.getMissionManager().refresh();
      assertEquals(List.of(8001, 1002), player.getCurrentLineup().getAvatars());
      assertEquals(1, player.getCurrentLineup().getLeader());
      assertEquals(7, player.getAvatarById(1001).getLevel());
      assertEquals(321, player.getAvatarById(1001).getExp());
      assertEquals(8002, player.getAvatarById(8001).getMultiPath().getExcelId());
      assertTrue(player.getUnlocks().getHeadIcons().contains(march.getHeadIconId()));
      assertEquals(2, player.getInventory().getMaterialByItemId(211).getCount());
      assertEquals(3, database.getObjects(GameAvatar.class, "ownerUid", player.getUid()).count());
    }
  }

  @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4})
  void eachCrossDocumentSaveBoundaryCanResumeAfterReload(int boundary) {
    wake(); player.save();
    player.failAtSave = player.saves + boundary;
    assertThrows(IllegalStateException.class, () -> new StoryParties(player).reconcile());
    reload();
    new StoryParties(player).reconcile();
    assertEquals(List.of(8001, 1001), player.getCurrentLineup().getAvatars());
    assertEquals(2, database.getObjects(GameAvatar.class, "ownerUid", player.getUid()).count());
    reload(); new StoryParties(player).reconcile();
    assertEquals(List.of(8001, 1001), player.getCurrentLineup().getAvatars());
  }

  @Test void acknowledgedAvatarWriteIsLoadedByReservedIdentityWithoutDuplicateOrReset() {
    wake(); player.save(); player.failAfterAvatar = true;
    assertThrows(IllegalStateException.class, () -> new StoryParties(player).reconcile());
    assertNull(player.getAvatarById(1001));
    var written = database.getObjects(GameAvatar.class, "ownerUid", player.getUid())
        .filter(a -> a.getAvatarId() == 1001).findFirst().orElseThrow();
    written.setLevel(6); written.save();
    new StoryParties(player).reconcile();
    assertEquals(written.getId(), player.getAvatarById(1001).getId());
    assertEquals(6, player.getAvatarById(1001).getLevel());
    assertEquals(2, database.getObjects(GameAvatar.class, "ownerUid", player.getUid()).count());
  }

  @Test void alreadyOwnedMissionCharacterDoesNotBlockOtherRewardsOrResetItsLevel() {
    assertTrue(player.addAvatar(new GameAvatar(1002)));
    player.getAvatarById(1002).setLevel(9); player.getAvatarById(1002).save();
    finishElevator();
    assertTrue(player.getRewardLedger().granted("mission:1000201"));
    assertEquals(9, player.getAvatarById(1002).getLevel());
    assertEquals(2, player.getInventory().getMaterialByItemId(211).getCount());
    assertEquals(List.of(8001, 1001, 1002), player.getCurrentLineup().getAvatars());
  }

  @Test void originalHertaRewardDoesNotLoseItsSixthItemSlot() {
    assertEquals(1, GameData.getRewardExcelMap().get(14030001).getRewards().get(20006),
        "The same reward that grants Herta also contains a light cone in slot six");
  }

  @ParameterizedTest @CsvSource({"1000201,1002", "1010801,1105", "4030001,1013"})
  void everyInstalledFormalMissionRewardGrantsOnceIncludingPreownedCharacters(int main, int avatar) {
    assertTrue(player.addAvatar(new GameAvatar(avatar)));
    var identity = player.getAvatarById(avatar).getId();
    var state = new QuestState(main); state.advance(1, 1, 100);
    player.getGameplay().getMainMissions().put(main, state);
    var rewards = new MissionRewards(player);
    rewards.freezeMain(GameData.getMainMissionExcelMap().get(main)); player.save();
    assertTrue(rewards.settle("mission:" + main, state));
    assertEquals(identity, player.getAvatarById(avatar).getId());
    assertTrue(player.getRewardLedger().granted("mission:" + main));
    assertEquals(QuestState.Phase.CLOSED, state.getPhase());
    int credits = player.getScoin(), jade = player.getHcoin();
    long equipment = player.getInventory().getItems().values().stream().filter(i -> i.getItemId() == 20006).count();
    if (main == 4030001) { assertEquals(1, equipment); assertEquals(10000, credits); assertEquals(50, jade); }
    assertTrue(rewards.settle("mission:" + main, state));
    assertEquals(credits, player.getScoin()); assertEquals(jade, player.getHcoin());
    assertEquals(equipment, player.getInventory().getItems().values().stream().filter(i -> i.getItemId() == 20006).count());
    assertEquals(2, database.getObjects(GameAvatar.class, "ownerUid", player.getUid()).count());
  }

  @ParameterizedTest @ValueSource(strings = {"unfinished", "cancelled", "sourceChanged", "wrongIdentity", "unselected"})
  void noCharacterIsGrantedWithoutValidEarnedSource(String invalid) {
    wake();
    switch (invalid) {
      case "unfinished" -> player.getGameplay().getSubMissions().put(100020103, new QuestState(100020103));
      case "cancelled" -> player.getGameplay().getCancelledSubs().add(100020103);
      case "sourceChanged" -> GameplayDefinitions.subs.get(100020103).setParamStr1("another_performance");
      case "wrongIdentity" -> player.getGameplay().getSubMissions().put(100020103, new QuestState(100020102));
      case "unselected" -> player.getGameplay().getStoryValues().remove("profile:character-path");
    }
    new StoryParties(player).reconcile();
    assertNull(player.getAvatarById(1001));
    assertTrue(player.getGameplay().getStoryParty().getGrants().isEmpty());
  }

  @Test void changedSourceCannotResumeAnInterruptedGrant() {
    wake(); player.save(); player.failAtSave = player.saves + 2;
    assertThrows(IllegalStateException.class, () -> new StoryParties(player).reconcile());
    reload();
    var sub = GameplayDefinitions.subs.get(100020103);
    sub.setSubRewardID(12345);
    new StoryParties(player).reconcile();
    assertEquals(List.of(8001), player.getCurrentLineup().getAvatars());
    assertFalse(player.getGameplay().getStoryParty().getGrants().get("wake:100020103").isComplete());
  }

  @Test void fullPartyIsNotEvictedAndDoesNotAutoFillAfterPlayerLaterRemovesSomeone() {
    wake();
    for (int id : List.of(1002, 1003, 1004)) assertTrue(player.addAvatar(new GameAvatar(id)));
    player.getCurrentLineup().getAvatars().addAll(List.of(1002, 1003, 1004));
    new StoryParties(player).reconcile();
    assertNotNull(player.getAvatarById(1001));
    assertEquals(List.of(8001, 1002, 1003, 1004), player.getCurrentLineup().getAvatars());
    player.getCurrentLineup().getAvatars().remove(Integer.valueOf(1004));
    new StoryParties(player).reconcile();
    assertEquals(List.of(8001, 1002, 1003), player.getCurrentLineup().getAvatars());
  }

  @Test void originalThreePersonActionAndItsInterruptedCompletionRecoverWithoutAnotherClientEvent() {
    finishElevator();
    MissionTrialProvisioning46Test.set(player, "planeId", 20002);
    MissionTrialProvisioning46Test.set(player, "floorId", 20002001);
    var plane = gson.fromJson("{\"PlaneID\":20002,\"PlaneType\":\"Town\"}", MazePlaneExcel.class);
    var scene = new emu.lunarcore.game.scene.Scene(player, plane, 20002001);
    MissionTrialProvisioning46Test.set(player, "scene", scene);
    var sub = GameplayDefinitions.subs.get(100020133);
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(100020133).getPhase());
    assertEquals(Retcode.SUCC, new StoryActions(player).execute(sub));
    assertEquals(List.of(8001, 1001, 1002), player.getCurrentLineup().getAvatars());
    assertEquals(1, player.getCurrentLineup().getLeader());
    // The action is durable but the final objective write was interrupted.
    reload();
    MissionTrialProvisioning46Test.set(player, "planeId", 20002);
    MissionTrialProvisioning46Test.set(player, "floorId", 20002001);
    MissionTrialProvisioning46Test.set(player, "scene", new emu.lunarcore.game.scene.Scene(player, plane, 20002001));
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player, 100020133));
    assertEquals(1, player.getCurrentLineup().getLeader());
    assertEquals(List.of(8001, 1001, 1002), player.getCurrentLineup().getAvatars());
    assertEquals(Set.of(8001, 1001, 1002), player.getScene().getAvatars().keySet());
  }

  @Test void temporaryPartyDefersJoiningWithoutOverwritingTheNormalRoster() {
    wake();
    player.getLineupManager().setCurrentExtraLineup(1, false);
    new StoryParties(player).reconcile();
    assertNotNull(player.getAvatarById(1001));
    assertEquals(List.of(8001), player.getLineupManager().getLineups()[0].getAvatars());
    assertTrue(player.getGameplay().getStoryParty().getChanges().isEmpty());
    player.getLineupManager().setCurrentExtraLineup(0, false);
    new StoryParties(player).reconcile();
    assertEquals(List.of(8001, 1001), player.getCurrentLineup().getAvatars());
  }
}
