package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original ride goal and NPC sources; prerequisites and actor placement are controlled fixtures. */
class CavalryActivity46Test {
  private static final int QUEST = 6029860;
  private static final Gson GSON = ResourceLoader.getGson();
  private static final JsonObject SOURCE =
      JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(
          CavalryActivity46Test.class.getResourceAsStream("/fixtures/cavalry-beast-4.6.json")),
          StandardCharsets.UTF_8)).getAsJsonObject();
  @TempDir Path root;
  final Map<ResourceMap, List<ResourceBase>> previous = new IdentityHashMap<>();
  final Map<String, FloorInfo> previousFloors = new HashMap<>();
  final Map<Integer, MissionInfo> previousHeaders = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  final List<GameplayDefinitions.Window> previousWindows = new ArrayList<>();
  String previousRoot;
  Actor player;
  Session session;
  GroupInfo group;
  JsonObject selected;

  static class Actor extends PropTimeline46Test.Actor {
    int world = 501, entryOffset, grants;
    boolean failGrant;
    final List<Map<Integer, Integer>> grantedAmounts = new ArrayList<>();
    final Inventory inventory = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap amounts, boolean hint) {
        grants++;
        assertTrue(containsPhase(persisted.getLast(), QuestState.Phase.CLAIMING.name()),
            "Inventory was called before the reward reservation was saved");
        var copy = new TreeMap<Integer, Integer>();
        for (var entry : amounts.int2IntEntrySet()) copy.put(entry.getIntKey(), entry.getIntValue());
        grantedAmounts.add(copy);
        return new GrantResult(failGrant ? List.of() : amounts.toItemList(), !failGrant);
      }
    };
    @Override public int getWorldId() { return world; }
    @Override public int getEntryId() { return super.getEntryId() + entryOffset; }
    @Override public Inventory getInventory() { return inventory; }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor;
    Session(Actor actor) { this.actor = actor; }
    @Override public Player getPlayer() { return actor; }
  }

  record Table(ResourceMap map, Class<? extends ResourceBase> type) {}

  @SuppressWarnings("unchecked")
  static Collection<ResourceBase> values(ResourceMap map) {
    return map instanceof ExcelMap excel ? excel.values() : ((MultiKeyExcelMap) map).values();
  }

  static Table table(String name) {
    return switch (name) {
      case "QuestData" -> new Table(GameData.getQuestExcelMap(), QuestExcel.class);
      case "FinishWay" -> new Table(GameData.getFinishWayExcelMap(), FinishWayExcel.class);
      case "RewardData" -> new Table(GameData.getRewardExcelMap(), RewardExcel.class);
      case "ItemConfig" -> new Table(GameData.getItemExcelMap(), ItemExcel.class);
      case "ActivityPanel" -> new Table(GameData.getActivityPanelExcelMap(), ActivityPanelExcel.class);
      case "MainMission" -> new Table(GameData.getMainMissionExcelMap(), MainMissionExcel.class);
      case "NPCData" -> new Table(GameData.getNpcExcelMap(), NpcExcel.class);
      case "MazePlane" -> new Table(GameData.getMazePlaneExcelMap(), MazePlaneExcel.class);
      case "MapEntrance" -> new Table(GameData.getMapEntranceExcelMap(), MapEntranceExcel.class);
      default -> null;
    };
  }

  void write(String path, JsonElement data) throws IOException {
    var file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, GSON.toJson(data));
  }

  @BeforeEach @SuppressWarnings("unchecked")
  void load() throws Exception {
    previousRoot = LunarCore.getConfig().getResourceDir();
    TimelineActivity46Test.resourceRoot(root.toString());
    previousHeaders.putAll(GameData.getMissionInfos());
    previousSubs.putAll(GameplayDefinitions.subs);
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    previousWindows.addAll(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
    for (var entry : SOURCE.getAsJsonObject("records").entrySet()) {
      write(entry.getKey(), entry.getValue());
      var target = table(Path.of(entry.getKey()).getFileName().toString().replace(".json", ""));
      if (target == null) continue;
      previous.put(target.map(), new ArrayList<>(values(target.map())));
      values(target.map()).clear();
      for (var value : entry.getValue().getAsJsonArray()) {
        var row = value.deepCopy();
        if (target.type() == MazePlaneExcel.class) {
          var name = row.getAsJsonObject().get("PlaneName");
          if (name != null && name.isJsonObject()) row.getAsJsonObject()
              .addProperty("PlaneName", name.getAsJsonObject().get("Hash").getAsLong());
        }
        var decoded = GSON.fromJson(row, target.type());
        decoded.onLoad();
        target.map().add(decoded);
      }
    }
    for (var entry : SOURCE.getAsJsonObject("groups").entrySet()) write(entry.getKey(), entry.getValue());
    for (var entry : SOURCE.getAsJsonObject("floors").entrySet()) write(entry.getKey(), entry.getValue());
    for (var entry : SOURCE.getAsJsonObject("headers").entrySet()) write(entry.getKey(), entry.getValue());
    for (var entry : SOURCE.getAsJsonObject("headers").entrySet()) {
      if (entry.getKey().endsWith(".partial.json")) continue;
      var merged = entry.getValue().getAsJsonObject().deepCopy();
      var partial = SOURCE.getAsJsonObject("headers").getAsJsonObject(entry.getKey().replace(".json", ".partial.json"));
      for (var field : partial.entrySet()) {
        assertTrue(!merged.has(field.getKey()) || merged.get(field.getKey()).equals(field.getValue()),
            "Conflicting original cavalry prerequisite header");
        merged.add(field.getKey(), field.getValue());
      }
      var header = GSON.fromJson(merged, MissionInfo.class);
      GameData.getMissionInfos().put(header.getMainMissionID(), header);
      for (var sub : header.getSubMissionList()) {
        assertEquals(header.getMainMissionID(), sub.getMainMissionID(), "Original prerequisite owner differs");
        GameplayDefinitions.subs.put(sub.getId(), sub);
      }
    }
    player = new Actor();
    player.setLevel(21);
    var avatar = new GameAvatar(GSON.fromJson("{\"AvatarID\":1001}", AvatarExcel.class));
    avatar.setOwner(player);
    player.lineup.getAvatars().add(1001);
    player.getAvatars().getAvatars().put(1001, avatar);
    player.getGameplay().getMainMissions().put(1000510, completed(1000510, 1));
    player.getGameplay().getMainMissions().put(1040101, completed(1040101, 1));
    player.getGameplay().getSubMissions().put(104010102, completed(104010102,
        Math.max(1, GameplayDefinitions.subs.get(104010102).getProgress())));
    session = new Session(player);
    activate("LevelGroup_P10401_F10401001_G176.json", 0);
  }

  @AfterEach @SuppressWarnings("unchecked")
  void restore() throws Exception {
    previous.forEach((map, rows) -> { values(map).clear(); rows.forEach(map::add); });
    previousFloors.forEach((key, value) -> {
      if (value == null) GameData.getFloorInfos().remove(key);
      else GameData.getFloorInfos().put(key, value);
    });
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(previousHeaders);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(previousSubs);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(previousWindows);
    TimelineActivity46Test.resourceRoot(previousRoot);
  }

  static QuestState completed(int id, int progress) {
    var state = new QuestState(id);
    state.advance(progress, progress, 1);
    state.reserveReward();
    state.closeReward();
    return state;
  }

  void activate(String file, int dimension) {
    selected = SOURCE.getAsJsonArray("bindings").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("path").getAsString().endsWith("/" + file)).findFirst().orElseThrow();
    int plane = selected.get("plane").getAsInt(), floor = selected.get("floor").getAsInt();
    String key = "P" + plane + "_F" + floor;
    var floorDefinition = SOURCE.getAsJsonObject("floors")
        .getAsJsonObject("Config/LevelOutput/RuntimeFloor/" + key + ".json");
    var parsed = GSON.fromJson(floorDefinition, FloorInfo.class);
    group = GSON.fromJson(SOURCE.getAsJsonObject("groups").get(selected.get("path").getAsString()), GroupInfo.class);
    group.setId(selected.get("group").getAsInt());
    for (int i = 0; i < parsed.getSimpleGroupList().size(); i++)
      if (selected.get("path").getAsString().equals(parsed.getSimpleGroupList().get(i).getGroupPath()))
        parsed.registerGroup(i, group);
    parsed.onLoad();
    if (!previousFloors.containsKey(key)) previousFloors.put(key, GameData.getFloorInfos().get(key));
    GameData.getFloorInfos().put(key, parsed);
    if (group.getId() == 172) {
      player.getGameplay().getMainMissions().put(1040103, completed(1040103, 1));
      player.getGameplay().getSubMissions().put(104010320,
          completed(104010320, Math.max(1, GameplayDefinitions.subs.get(104010320).getProgress())));
    }
    if (group.getOwnerMainMissionID() != 0) player.getGameplay().getMainMissions()
        .put(group.getOwnerMainMissionID(), new QuestState(group.getOwnerMainMissionID()));
    player.current = new Scene(player, GameData.getMazePlaneExcelMap().get(plane), floor, dimension);
    int entry = SOURCE.getAsJsonObject("records").getAsJsonArray("ExcelOutput/MapEntrance.json")
        .asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("PlaneID").getAsInt() == plane && row.get("FloorID").getAsInt() == floor)
        .findFirst().orElseThrow().get("ID").getAsInt();
    player.current.setEntryId(entry);
  }

  EntityNpc actor(int instance) {
    var actors = player.current.getEntitiesByGroup(EntityNpc.class, group.getId()).stream()
        .filter(npc -> npc.getInstId() == instance).toList();
    assertEquals(1, actors.size(), "Original source NPC not loaded uniquely");
    return actors.getFirst();
  }

  EntityNpc actor() { return actor(selected.get("instance").getAsInt()); }

  QuestState state() {
    return player.getQuestManager().states().stream().filter(state -> state.getQuestId() == QUEST)
        .findFirst().orElse(null);
  }

  QuestState accepted() {
    var state = state();
    assertNotNull(state, "Original RideCavalryBeastCnt quest is not published through QuestManager");
    return state;
  }

  byte[] request(int group, int instance) {
    var builder = Gameplay46.builder(session, "FinishQuestCsReq");
    Gameplay46.set(builder, "quest_id", QUEST);
    Gameplay46.set(builder, "group_id", group);
    Gameplay46.set(builder, "prop_id", instance);
    return builder.build().toByteArray();
  }

  int finish(byte[] request) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 915, request), "Native cavalry quest request is not handled");
    assertEquals("FinishQuestScRsp", session.responseName);
    return Gameplay46.number(session.response, "PLDKENJLKME");
  }

  int finish() throws Exception { return finish(request(group.getId(), selected.get("instance").getAsInt())); }

  static boolean containsPhase(JsonElement value, String phase) {
    if (value.isJsonObject()) {
      var row = value.getAsJsonObject();
      if (row.has("questId") && row.get("questId").getAsInt() == QUEST
          && row.has("phase") && row.get("phase").getAsString().equals(phase)) return true;
      return row.entrySet().stream().anyMatch(entry -> containsPhase(entry.getValue(), phase));
    }
    return value.isJsonArray() && value.getAsJsonArray().asList().stream().anyMatch(row -> containsPhase(row, phase));
  }

  static Stream<Arguments> townPlacements() {
    return SOURCE.getAsJsonArray("bindings").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("plane").getAsInt() == 10401)
        .flatMap(row -> row.getAsJsonArray("dimensions").asList().stream().map(dimension -> Arguments.of(
            Path.of(row.get("path").getAsString()).getFileName().toString(), dimension.getAsInt())));
  }

  @Test void nativeFieldsAndOriginalRideGoalRemainPinned() {
    var type = session.getProtocol().clientSchema().message("FinishQuestCsReq");
    assertEquals(915, session.getProtocol().clientCommand("FinishQuestCsReq"));
    assertEquals(7, type.findFieldByName("quest_id").getNumber());
    assertEquals(8, type.findFieldByName("prop_id").getNumber());
    assertEquals(11, type.findFieldByName("group_id").getNumber());
    assertEquals("RideCavalryBeastCnt", GameData.getFinishWayExcelMap().get(QUEST).getFinishType());
    assertEquals(1, GameData.getFinishWayExcelMap().get(QUEST).getProgress());
    assertEquals(3185, actor().getNpcId());
  }

  @Test void originalNonperiodicQuestIsPublishedAtZero() {
    assertEquals(0, accepted().getProgress());
    assertEquals(QuestState.Phase.DOING, accepted().getPhase());
  }

  @Test void originalMergedPrerequisiteHeadersKeepTheirActualMainOwners() {
    assertEquals(1040101, GameData.getMissionInfos().get(1040101).getMainMissionID());
    assertEquals(1040103, GameData.getMissionInfos().get(1040103).getMainMissionID());
    assertEquals(1040101, GameplayDefinitions.subs.get(104010102).getMainMissionID());
    assertEquals(1040103, GameplayDefinitions.subs.get(104010320).getMainMissionID());
    assertTrue(player.getMissionManager().finished(1000510));
    assertTrue(player.getMissionManager().finished(1040101));
  }

  @ParameterizedTest @MethodSource("townPlacements")
  void originalTownActorAndActualDimensionCreditOneSavedRide(String file, int dimension) throws Exception {
    activate(file, dimension);
    assertEquals(selected.get("npc").getAsInt(), actor().getNpcId());
    accepted();
    assertEquals(0, finish());
    assertEquals(1, accepted().getProgress());
    assertEquals(QuestState.Phase.FINISHED, accepted().getPhase());
    assertTrue(containsPhase(player.persisted.getLast(), QuestState.Phase.FINISHED.name()));
    assertEquals(0, finish());
    assertEquals(1, accepted().getProgress());
    assertEquals(0, player.grants);
  }

  @Test void rideReportCannotCreateItsOwnMissingAcceptance() throws Exception {
    actor();
    assertNotEquals(0, finish());
    assertEquals(0, accepted().getProgress());
  }

  @ParameterizedTest @ValueSource(strings = {"missingGroup", "missingInstance", "untaggedActor", "wrongNpc",
      "removedActor", "unloadedGroup", "duplicateActor", "movedActor"})
  void actualActorIdentityAndSourceTagAreRequired(String mutation) throws Exception {
    var accepted = accepted();
    var npc = actor();
    int reportGroup = group.getId(), reportInstance = npc.getInstId();
    switch (mutation) {
      case "missingGroup" -> reportGroup = 999999;
      case "missingInstance" -> reportInstance = 999999;
      case "untaggedActor" -> { assertEquals(3183, actor(400002).getNpcId()); reportInstance = 400002; }
      case "wrongNpc" -> npc.setNpcId(3183);
      case "removedActor" -> player.current.removeEntity(npc);
      case "unloadedGroup" -> player.current.getLoadedGroups().remove(group.getId());
      case "duplicateActor" -> player.current.addEntity(new EntityNpc(player.current, group,
          group.getNPCList().stream().filter(row -> row.getID() == npc.getInstId()).findFirst().orElseThrow()), false);
      case "movedActor" -> npc.getPos().add(1000, 0, 0);
      default -> throw new AssertionError(mutation);
    }
    assertNotEquals(0, finish(request(reportGroup, reportInstance)));
    assertEquals(0, accepted.getProgress());
    assertEquals(0, player.grants);
  }

  @ParameterizedTest @ValueSource(strings = {"battle", "instance", "transition", "storyMismatch", "entryMismatch",
      "worldMismatch", "foreignScene", "raid"})
  void reportsRequireTheCurrentTownWorldAndStoryContext(String guard) throws Exception {
    var accepted = accepted();
    switch (guard) {
      case "battle" -> player.battle = true;
      case "instance" -> player.setInstance(new PlayerGameInstance() {
        public void onBattleStart(Battle battle) {}
        public void onBattleFinish(Battle battle, BattleEndStatus result, BattleStatistics stats) {}
        public void onLeave() {}
      });
      case "transition", "storyMismatch" -> {
        var place = new StoryLineState.Place(player);
        player.getGameplay().getStoryLines().begin(19999001, place, place);
        if (guard.equals("storyMismatch")) player.getGameplay().getStoryLines().arrived();
      }
      case "entryMismatch" -> player.entryOffset = 1;
      case "worldMismatch" -> player.world = 101;
      case "foreignScene" -> {
        var foreign = new Actor();
        player.current = new Scene(foreign, GameData.getMazePlaneExcelMap().get(10401), 10401001);
        player.current.setEntryId(1040101);
      }
      case "raid" -> activate("LevelGroup_P40457_F40457001_G32.json", 0);
      default -> throw new AssertionError(guard);
    }
    assertNotEquals(0, finish());
    assertEquals(0, accepted.getProgress());
  }

  @Test void loadedAtmosphereActorIsRejectedWhenTheOriginalGateCloses() throws Exception {
    var accepted = accepted();
    actor();
    player.getGameplay().getStoryValues().put("floor:10401001:FSV_Mission_DisableCavalryBeast", 1);
    assertFalse(player.getMissionManager().shouldLoadGroup(group, player.current));
    assertNotEquals(0, finish());
    assertEquals(0, accepted.getProgress());
  }

  @Test void loadedMissionActorStillRequiresItsOriginalDoingOwner() throws Exception {
    activate("LevelGroup_P10401_F10401001_G825.json", 0);
    actor();
    var accepted = accepted();
    player.getGameplay().getMainMissions().put(5000412, completed(5000412, 1));
    assertFalse(player.getMissionManager().shouldLoadGroup(group, player.current));
    assertNotEquals(0, finish());
    assertEquals(0, accepted.getProgress());
  }

  @Test void unreferencedD1001CannotReplaceTheDimensionSpecificSource() throws Exception {
    var accepted = accepted();
    activate("LevelGroup_P10401_F10401001_G172_D1001.json", 1002);
    assertFalse(selected.getAsJsonArray("dimensions").asList().stream().anyMatch(row -> row.getAsInt() == 1002));
    assertTrue(player.current.getEntitiesByGroup(EntityNpc.class, 172).isEmpty());
    assertNotEquals(0, finish());
    assertEquals(0, accepted.getProgress());
  }

  @ParameterizedTest @ValueSource(strings = {"level", "mainMission", "subMission", "cancelledSub", "window", "duplicateWindow"})
  void originalPrerequisitesAndUniqueLiveWindowControlAcceptance(String guard) {
    switch (guard) {
      case "level" -> player.setLevel(20);
      case "mainMission" -> player.getGameplay().getMainMissions().remove(1000510);
      case "subMission" -> player.getGameplay().getSubMissions().remove(104010102);
      case "cancelledSub" -> player.getGameplay().getCancelledSubs().add(104010102);
      case "window" -> GameplayDefinitions.windows.clear();
      case "duplicateWindow" -> GameplayDefinitions.windows.add(new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
      default -> throw new AssertionError(guard);
    }
    assertNull(state());
  }

  @ParameterizedTest @ValueSource(strings = {"tag", "finishWay", "reward", "cycle", "loadedReward", "loadedNpcDefinition", "loadedFloor"})
  void changedSourceOrLoadedDataCannotCreditAnAcceptedRide(String mutation) throws Exception {
    var accepted = accepted();
    switch (mutation) {
      case "tag" -> {
        var raw = SOURCE.getAsJsonObject("groups").getAsJsonObject(selected.get("path").getAsString()).deepCopy();
        raw.getAsJsonArray("NPCList").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(row -> row.get("ID").getAsInt() == 400001).findFirst().orElseThrow().remove("TagOverrideConfig");
        write(selected.get("path").getAsString(), raw);
      }
      case "finishWay", "reward", "cycle" -> {
        String path = "ExcelOutput/" + switch (mutation) {
          case "finishWay" -> "FinishWay";
          case "reward" -> "RewardData";
          default -> "CycleQuest";
        } + ".json";
        var raw = SOURCE.getAsJsonObject("records").getAsJsonArray(path).deepCopy();
        var row = raw.get(0).getAsJsonObject();
        if (mutation.equals("finishWay")) row.addProperty("Progress", 2);
        else if (mutation.equals("reward")) row.addProperty("Hcoin", 200);
        else row.addProperty("IsNonPeriodic", false);
        write(path, raw);
      }
      case "loadedReward" -> GameData.getRewardExcelMap().get(3129020).getRewards().put(1, 200);
      case "loadedNpcDefinition" -> group.getNPCList().stream().filter(row -> row.getID() == 400001)
          .findFirst().orElseThrow().ID = 400099;
      case "loadedFloor" -> player.current.getFloorInfo().getDimensionList().getFirst().getGroupIndexList().clear();
      default -> throw new AssertionError(mutation);
    }
    assertNotEquals(0, finish());
    assertEquals(0, accepted.getProgress());
    assertEquals(0, player.grants);
  }

  @Test void failedCountSaveLeavesTheSameAcceptanceRetryable() throws Exception {
    accepted();
    var before = new Gson().toJson(player.getGameplay());
    player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), finish());
    player.failSave = false;
    assertEquals(before, new Gson().toJson(player.getGameplay()));
    assertEquals(0, accepted().getProgress());
    assertEquals(0, finish());
    assertEquals(1, accepted().getProgress());
  }

  void reload() throws Exception {
    var saved = new Gson().toJson(player.getGameplay());
    var replacement = new Actor();
    replacement.setLevel(21);
    replacement.lineup.getAvatars().addAll(player.lineup.getAvatars());
    replacement.getAvatars().getAvatars().putAll(player.getAvatars().getAvatars());
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(replacement, GSON.fromJson(saved, GameplayState.class));
    player = replacement;
    session = new Session(replacement);
    activate("LevelGroup_P10401_F10401001_G176.json", 0);
  }

  @Test void savedReloadPreservesProgressAndRepeatReportBoundary() throws Exception {
    accepted();
    assertEquals(0, finish());
    reload();
    assertEquals(1, accepted().getProgress());
    assertEquals(QuestState.Phase.FINISHED, accepted().getPhase());
    assertTrue(QuestCompletionRecords.completed(player, QUEST));
    assertEquals(0, finish());
    assertEquals(1, accepted().getProgress());
    assertEquals(0, player.grants);
  }

  @Test void originalRewardIsReservedThenPaidAndClosedOnlyOnce() throws Exception {
    accepted();
    assertEquals(0, finish());
    assertEquals(Retcode.SUCC, player.getQuestManager().claim(QUEST).retcode());
    assertEquals(List.of(Map.of(1, 20, 213, 4)), player.grantedAmounts);
    assertEquals(QuestState.Phase.CLOSED, accepted().getPhase());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getQuestManager().claim(QUEST).retcode());
    assertEquals(1, player.grants);
    reload();
    assertTrue(QuestCompletionRecords.closed(player, QUEST));
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getQuestManager().claim(QUEST).retcode());
    assertEquals(0, player.grants);
  }

  @Test void incompleteInventoryGrantRetainsClaimingAndCannotPayAgain() throws Exception {
    accepted();
    assertEquals(0, finish());
    player.failGrant = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR, player.getQuestManager().claim(QUEST).retcode());
    assertEquals(QuestState.Phase.CLAIMING, accepted().getPhase());
    assertEquals(Retcode.SYSTEM_BUSY, player.getQuestManager().claim(QUEST).retcode());
    assertEquals(1, player.grants);
  }

  @Test void expiredWindowKeepsCompletionHistoryAndBlocksNewReportsAndClaims() throws Exception {
    accepted();
    assertEquals(0, finish());
    GameplayDefinitions.windows.clear();
    assertNull(state());
    assertTrue(QuestCompletionRecords.completed(player, QUEST));
    assertNotEquals(0, finish());
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(QUEST).retcode());
    assertEquals(0, player.grants);
  }

  @Test void forgedLegacyClosedStateDoesNotOwnTheRideGoalOrReward() {
    player.getQuestStates().put(QUEST, completed(QUEST, 1));
    assertFalse(QuestCompletionRecords.completed(player, QUEST));
    assertEquals(0, accepted().getProgress());
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(QUEST).retcode());
    assertEquals(0, player.grants);
  }

  static byte[] wire(long... fieldsAndValues) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var stream = CodedOutputStream.newInstance(bytes);
    for (int i = 0; i < fieldsAndValues.length; i += 2)
      stream.writeUInt64((int) fieldsAndValues[i], fieldsAndValues[i + 1]);
    stream.flush();
    return bytes.toByteArray();
  }

  @ParameterizedTest @ValueSource(strings = {"missingGroup", "missingInstance", "duplicateQuest", "redirectQuest",
      "duplicateInstance", "unknownField", "overflowInstance"})
  void nativeOwnedWireIsStrictBeforeAnyRideCount(String invalid) throws Exception {
    var accepted = accepted();
    byte[] request = switch (invalid) {
      case "missingGroup" -> wire(7, QUEST, 8, 400001);
      case "missingInstance" -> wire(7, QUEST, 11, 176);
      case "duplicateQuest" -> wire(7, QUEST, 7, QUEST, 8, 400001, 11, 176);
      case "redirectQuest" -> wire(7, QUEST, 7, 19999999, 8, 400001, 11, 176);
      case "duplicateInstance" -> wire(7, QUEST, 8, 400001, 8, 400001, 11, 176);
      case "unknownField" -> wire(7, QUEST, 8, 400001, 11, 176, 15, 1);
      case "overflowInstance" -> wire(7, QUEST, 8, 0x1_0000_0001L, 11, 176);
      default -> throw new AssertionError(invalid);
    };
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), finish(request));
    assertEquals(0, accepted.getProgress());
  }
}
