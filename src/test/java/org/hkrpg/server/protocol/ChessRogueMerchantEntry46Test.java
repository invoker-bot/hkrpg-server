package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ExcelMap;
import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.BattleService;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.battle.skills.MazeSkillAction;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.util.Position;
import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The original 10111 four-battle tutorial leads to 10121's authored Respite cell and DLC G35.
 * Room-to-floor routing, combat credits and prices are declared server policies. This test never
 * creates a classic run or a synthetic merchant actor to bypass first entry, and is not UI evidence.
 */
class ChessRogueMerchantEntry46Test {
  static final String COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b";
  static final String FLOOR = "Config/LevelOutput/RuntimeFloor/P80201_F80201001.json";
  static final String TUTORIAL1 = "Config/Gameplays/RogueDLC/RogueDLC_Tutorial1_1.json";
  static final String TUTORIAL2 = "Config/Gameplays/RogueDLC/RogueDLC_Tutorial1_2.json";
  static final String ACT = "Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act0110003.json";
  static final String POLICY = "data/ChessRogueGuideRoutes.json";
  static final Gson GSON = new Gson();
  static final Gson RESOURCE_GSON = ResourceLoader.getGson();
  static final byte[] EMPTY = new byte[0];
  record LineupNotice(DynamicMessage packet, int committedSaves, JsonObject stateAtSend) {}
  record ClientSend(String name, DynamicMessage packet, int committedSaves, JsonObject stateAtSend) {}
  @TempDir Path root;
  final JsonObject fixture = JsonParser.parseReader(new InputStreamReader(
      Objects.requireNonNull(getClass().getResourceAsStream(
          "/fixtures/chess-rogue-merchant-entry-4.6.json")), StandardCharsets.UTF_8)).getAsJsonObject();
  record StaticValue(Object reference, Object contents) {}
  final Map<java.lang.reflect.Field, StaticValue> staticBefore = new LinkedHashMap<>();
  final Map<ExcelMap<?>, List<?>> excelsBefore = new IdentityHashMap<>();
  final Map<String, FloorInfo> floorsBefore = new HashMap<>();
  String oldResources, oldData;
  Session session;

  static class Explorer extends Player {
    GameplayState state = new GameplayState();
    final Position fixturePos = new Position(), fixtureRot = new Position();
    final LineupManager teams = new LineupManager(this);
    final List<JsonObject> saved = new ArrayList<>();
    final List<LineupNotice> lineupNotices = new ArrayList<>();
    Session activeSession;
    boolean interruptSave, online;
    public GameplayState getGameplay() { return state; }
    public Position getPos() { return fixturePos; }
    public Position getRot() { return fixtureRot; }
    public LineupManager getLineupManager() { return teams; }
    public GameSession getSession() { return activeSession; }
    public boolean isOnline() { return online; }
    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
      if (packet.getCmdId() != emu.lunarcore.server.packet.CmdId.SyncLineupNotify) return;
      try {
        var nativeWire = activeSession.getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
        var nativePacket = activeSession.getProtocol().clientSchema().parse("SyncLineupNotify", nativeWire.body());
        lineupNotices.add(new LineupNotice(nativePacket, saved.size(), snapshot()));
      } catch (Exception malformed) { throw new AssertionError(malformed); }
    }
    public void save() {
      if (interruptSave) throw new IllegalStateException("Injected Chess first-entry save failure");
      saved.add(snapshot());
    }
    JsonObject snapshot() {
      var result = new JsonObject();
      result.add("gameplay", GSON.toJsonTree(state));
      result.addProperty("plane", getPlaneId());
      result.addProperty("floor", getFloorId());
      result.addProperty("entry", getEntryId());
      result.add("pos", GSON.toJsonTree(getPos()));
      result.add("rot", GSON.toJsonTree(getRot()));
      result.add("ordinaryRoster", GSON.toJsonTree(teams.getLineupByIndex(0).getAvatars()));
      return result;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Explorer explorer = new Explorer();
    final Map<String, DynamicMessage> packets = new LinkedHashMap<>();
    final Map<Integer, byte[]> commandPackets = new LinkedHashMap<>();
    final List<ClientSend> clientSends = new ArrayList<>();
    GameServer battleShell;
    Session() { explorer.activeSession = this; }
    public Player getPlayer() { return explorer; }
    public GameServer getServer() { return battleShell; }
    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      packets.put(name, response);
      clientSends.add(new ClientSend(name, response, explorer.saved.size(), explorer.snapshot()));
    }
    public void sendClientPacket(int command, byte[] bytes) {
      // These tests observe named wire responses and the actual authoritative scene independently.
      // A later loopback probe also observes command-only notifications over the real transport.
      commandPackets.put(command, bytes.clone());
    }
  }

  Explorer p() { return session.explorer; }
  JsonObject state() { return GSON.toJsonTree(p().state).getAsJsonObject(); }
  JsonObject chess() {
    assertTrue(state().has("chessRogue"), "A first Chess start needs independent persisted state");
    return state().getAsJsonObject("chessRogue");
  }
  JsonObject file(String path) {
    return fixture.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("path").getAsString().equals(path)).findFirst().orElseThrow();
  }
  byte[] raw(String path) { return Base64.getDecoder().decode(file(path).get("rawBase64").getAsString()); }
  JsonObject original(String path) {
    return JsonParser.parseString(new String(raw(path), StandardCharsets.UTF_8)).getAsJsonObject();
  }
  JsonArray rows(String table) {
    return fixture.getAsJsonArray("rows").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row -> row.get("table").getAsString().equals(table)).findFirst().orElseThrow()
        .getAsJsonArray("rows");
  }

  void snapshotStatics(Class<?> type) throws Exception {
    for (var field : type.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
      field.setAccessible(true);
      var value = field.get(null);
      if (Modifier.isFinal(field.getModifiers()) && !(value instanceof Map || value instanceof Collection)) continue;
      staticBefore.put(field, new StaticValue(value, value instanceof Map<?, ?> map ? new HashMap<>(map)
          : value instanceof Collection<?> list ? new ArrayList<>(list) : value));
    }
  }

  <T extends GameResource> void install(String name, Class<T> type, ExcelMap<T> map) {
    excelsBefore.put(map, new ArrayList<>(map.values()));
    map.values().clear();
    for (var row : rows(name)) {
      var resource = RESOURCE_GSON.fromJson(row, type);
      resource.onLoad();
      map.add(resource);
    }
  }

  FloorInfo loadFloor(String path) {
    var floor = RESOURCE_GSON.fromJson(new String(raw(path), StandardCharsets.UTF_8), FloorInfo.class);
    for (int index : floor.getReferencedGroupIndices()) {
      var simple = floor.getSimpleGroupList().get(index);
      var authored = fixture.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject)
          .filter(row -> row.get("path").getAsString().equals(simple.getGroupPath())).findFirst();
      // The unrelated ordinary-world return fixture retains only its original entry group.
      if (authored.isEmpty()) continue;
      var group = RESOURCE_GSON.fromJson(new String(Base64.getDecoder().decode(
          authored.get().get("rawBase64").getAsString()), StandardCharsets.UTF_8), GroupInfo.class);
      group.setId(simple.getID());
      floor.registerGroup(index, group);
    }
    floor.onLoad();
    var name = Path.of(path).getFileName().toString().replace(".json", "");
    GameData.getFloorInfos().put(name, floor);
    return floor;
  }

  void addAvatar(Explorer player) {
    var avatar = new GameAvatar(GameData.getAvatarExcelMap().get(1001)) {
      public void save() {}
    };
    avatar.setOwner(player);
    player.getAvatars().getAvatars().put(1001, avatar);
    player.teams.getLineupByIndex(0).getAvatars().add(1001);
  }

  void unlock(Explorer player) {
    var completed = new QuestState(8013104);
    assertTrue(completed.advance(1, 1, 1700000000));
    player.state.getMainMissions().put(8013104, completed);
  }

  void ordinaryScene(Explorer player) {
    var floor = GameData.getFloorInfo(20001, 20001001);
    var anchor = floor.getStartAnchorInfo();
    assertNotNull(anchor, "The full original return floor and G2 must resolve its entry anchor");
    assertTrue(player.loadScene(20001, 20001001, 2000101, anchor.getPos(), anchor.getRot(), false));
    player.saved.clear();
  }

  @BeforeEach
  void setup() throws Exception {
    oldResources = LunarCore.getConfig().resourceDir;
    oldData = LunarCore.getConfig().dataDir;
    snapshotStatics(GameplayDefinitions.class);
    for (String type : List.of("StoryChallengeCatalog", "LocalLegendCatalog", "LocalLegendAccess",
        "DiceRollQuests", "WolfBroCatalog", "DailyMissionCatalog", "RogueHandbookCatalog",
        "RogueBlessingQuests", "RogueAeonCatalog", "ExpeditionCatalog", "DailyCycleCatalog",
        "MatchThreeCatalog", "RogueMapPools", "RogueBossEncounters", "FirstTalkDefinitions",
        "StoryLineDefinitions", "HeartDialDefinitions", "RogueDialogueDefinitions",
        "RogueDialogueRewards", "RogueRestMerchantRecipes"))
      snapshotStatics(Class.forName("org.hkrpg.server.gameplay." + type));
    // Snapshot the new catalogue after it exists, without making this RED depend on a Java shell.
    try { snapshotStatics(Class.forName("org.hkrpg.server.gameplay.ChessRogueRoutes")); }
    catch (ClassNotFoundException notImplemented) { }
    floorsBefore.putAll(GameData.getFloorInfos());
    LunarCore.getConfig().resourceDir = root.toString();
    LunarCore.getConfig().dataDir = root.resolve("data").toString();
    Files.createDirectories(root.resolve("data"));
    Files.createDirectories(root.resolve("ExcelOutput"));
    for (var element : fixture.getAsJsonArray("files")) {
      var source = element.getAsJsonObject();
      var path = root.resolve(source.get("path").getAsString());
      Files.createDirectories(path.getParent());
      Files.write(path, Base64.getDecoder().decode(source.get("rawBase64").getAsString()));
    }
    for (var element : fixture.getAsJsonArray("rows")) {
      var source = element.getAsJsonObject();
      Files.writeString(root.resolve(source.get("path").getAsString()), GSON.toJson(source.get("rows")));
    }
    Files.writeString(root.resolve(POLICY), GSON.toJson(fixture.get("route")));
    GameplayDefinitions.load(root);
    install("MapEntrance", MapEntranceExcel.class, GameData.getMapEntranceExcelMap());
    install("MazePlane", MazePlaneExcel.class, GameData.getMazePlaneExcelMap());
    install("AvatarConfig", AvatarExcel.class, GameData.getAvatarExcelMap());
    install("MultiplePathAvatarConfig", MultiplePathAvatarExcel.class, GameData.getMultiplePathAvatarExcelMap());
    install("NPCData", NpcExcel.class, GameData.getNpcExcelMap());
    install("NPCMonsterData", NpcMonsterExcel.class, GameData.getNpcMonsterExcelMap());
    install("MonsterConfig", MonsterExcel.class, GameData.getMonsterExcelMap());
    install("StageConfig", StageExcel.class, GameData.getStageExcelMap());
    install("MazeProp", PropExcel.class, GameData.getPropExcelMap());
    loadFloor(FLOOR);
    loadFloor("Config/LevelOutput/RuntimeFloor/P20001_F20001001.json");
    session = new Session();
    addAvatar(p());
    ordinaryScene(p());
    unlock(p());
  }

  @AfterEach
  @SuppressWarnings({"rawtypes", "unchecked"})
  void cleanup() throws Exception {
    LunarCore.getConfig().resourceDir = oldResources;
    LunarCore.getConfig().dataDir = oldData;
    for (var entry : staticBefore.entrySet()) {
      var field = entry.getKey();
      var saved = entry.getValue();
      if (!Modifier.isFinal(field.getModifiers())) field.set(null, saved.reference());
      var current = saved.reference();
      if (current instanceof Map map && !map.equals(saved.contents())) {
        map.clear(); map.putAll((Map) saved.contents());
      }
      if (current instanceof Collection collection
          && !new ArrayList<>(collection).equals(saved.contents())) {
        collection.clear(); collection.addAll((Collection) saved.contents());
      }
    }
    for (var entry : excelsBefore.entrySet()) {
      ExcelMap map = entry.getKey();
      map.values().clear();
      for (var resource : entry.getValue()) map.add((GameResource) resource);
    }
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(floorsBefore);
  }

  interface Wire { void write(CodedOutputStream out) throws IOException; }
  static byte[] body(Wire writer) throws IOException {
    var buffer = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    writer.write(out);
    out.flush();
    return buffer.toByteArray();
  }
  static byte[] startBody(int area, int aeon, int... roster) throws IOException {
    return body(out -> {
      out.writeUInt32(13, area);
      out.writeUInt32(9, aeon);
      for (int avatar : roster) out.writeUInt32(7, avatar);
    });
  }
  DynamicMessage call(int command, byte[] bytes, String name) throws Exception {
    session.packets.clear();
    session.response = null;
    session.responseName = null;
    assertTrue(VersionedPacketHandler.handle(session, command, bytes),
        "Verified native request " + command + " must reach a behavior handler");
    var response = session.packets.get(name);
    assertNotNull(response, "The real native " + name + " response must be sent");
    return response;
  }
  static Object value(DynamicMessage message, int field) {
    return message.getField(Objects.requireNonNull(message.getDescriptorForType().findFieldByNumber(field)));
  }
  static int number(DynamicMessage message, int field) {
    var value = value(message, field);
    return value instanceof EnumValueDescriptor e ? e.getNumber() : ((Number) value).intValue();
  }
  static DynamicMessage child(DynamicMessage message, int field) {
    return (DynamicMessage) value(message, field);
  }
  @SuppressWarnings("unchecked")
  static List<DynamicMessage> children(DynamicMessage message, int field) {
    return (List<DynamicMessage>) value(message, field);
  }
  static int code(DynamicMessage response) { return Gameplay46.number(response, "retcode"); }
  DynamicMessage start() throws Exception {
    var response = call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp");
    assertEquals(0, code(response), "An unlocked original tutorial is a first start, not a resume");
    return response;
  }
  DynamicMessage query() throws Exception {
    var response = call(5547, EMPTY, "ChessRogueQueryScRsp");
    assertEquals(0, code(response));
    return child(response, 8);
  }
  DynamicMessage board(DynamicMessage game) { return child(child(game, 2), 2); }
  int coins(DynamicMessage game) { return number(child(game, 10), 13); }
  DynamicMessage cell(DynamicMessage game, int id) {
    return children(child(board(game), 9), 7).stream().filter(row -> number(row, 1) == id)
        .findFirst().orElseThrow();
  }
  int status(DynamicMessage game, int id) { return number(cell(game, id), 6); }

  void fullBoard(DynamicMessage game, int layer, int board, int current, String path) {
    assertEquals(201, number(game, 14), "The native human RogueSubMode.ChessRogue is 201");
    assertEquals(6, number(child(game, 1), 14), "Native Aeon sync consumes SelectedAeonID");
    var area = child(game, 2);
    assertEquals(101, number(area, 9));
    assertEquals(layer, number(area, 12), "Native Area sync consumes CurAreaLayerID");
    var wireBoard = child(area, 2);
    assertEquals(board, number(wireBoard, 5));
    assertEquals(current, number(wireBoard, 8));
    var layout = child(wireBoard, 9);
    var authored = original(path);
    assertEquals(authored.get("Width").getAsInt(), number(layout, 15));
    assertEquals(authored.get("Height").getAsInt(), number(layout, 9));
    assertEquals(authored.get("StartGridItemID").getAsInt(), number(layout, 4));
    assertEquals(authored.get("EndGridItemID").getAsInt(), number(layout, 3));
    var originalCells = authored.getAsJsonObject("RogueChestGridItemMap");
    assertEquals(originalCells.size(), children(layout, 7).size(), "Retain every original cell");
    for (var entry : originalCells.entrySet()) {
      var row = cell(game, Integer.parseInt(entry.getKey()));
      assertEquals(entry.getValue().getAsJsonObject().get("PosX").getAsInt(), number(row, 13));
      assertEquals(entry.getValue().getAsJsonObject().get("PosY").getAsInt(), number(row, 7));
    }
  }

  void actualStage(DynamicMessage stage, Set<Integer> groups) {
    assertNotNull(p().getScene());
    assertEquals(8020101, p().getEntryId());
    assertEquals(80201, p().getScene().getPlaneId());
    assertEquals(80201001, p().getScene().getFloorId());
    assertEquals(0, p().getScene().getDimensionId());
    assertEquals(groups, p().getScene().getLoadedGroups().stream().collect(java.util.stream.Collectors.toSet()),
        "The actual loaded-group registry, including original groups without property/state views, is exact");
    var scene = child(stage, 14);
    assertEquals(8020101, number(scene, 8));
    assertEquals(80201001, number(scene, 2));
    assertEquals(80201, number(scene, 10));
    var roster = children(child(stage, 2), 4);
    assertEquals(1, roster.size(), "The actual first-entry lineup must contain the selected formal avatar");
    assertEquals(List.of(1001), p().getCurrentLineup().getAvatars());
    assertEquals(List.of(1001), p().teams.getLineupByIndex(0).getAvatars());
    assertTrue(p().getCurrentLineup().isExtraLineup(), "Chess gameplay must not use the ordinary team");
  }

  DynamicMessage select(int cell) throws Exception {
    return call(5534, body(out -> out.writeUInt32(2, cell)), "ChessRogueSelectCellScRsp");
  }
  DynamicMessage enter(int cell, int monster) throws Exception {
    return call(5468, body(out -> {
      out.writeUInt32(13, cell);
      // The original non-BossSelect Lua path sets only CellID on the default value type.
      // Its native writer omits the zero MonsterGroupID. Server pools are not request fields.
      if (monster != 0) out.writeUInt32(15, monster);
    }),
        "ChessRogueEnterCellScRsp");
  }
  void move(int cell) throws Exception {
    assertEquals(0, code(select(cell)));
    var selected = query();
    assertEquals(cell, number(board(selected), 8));
    assertEquals(1, status(selected, cell));
    var entered = enter(cell, 0);
    assertEquals(0, code(entered));
    assertEquals(2, status(child(entered, 12), cell));
    actualStage(child(entered, 8), cell == 204 ? Set.of(31, 32) : Set.of(24, 25));
  }
  EntityMonster actualMonster() {
    var monsters = p().getScene().getEntities().values().stream().filter(EntityMonster.class::isInstance)
        .map(EntityMonster.class::cast).toList();
    assertEquals(1, monsters.size(), "Each entry loads its original group monster through the production loader");
    var monster = monsters.getFirst();
    int current = p().getScene().getLoadedGroups().contains(32) ? 204 : 201;
    assertEquals(current == 204 ? 32 : 25, monster.getGroupId());
    assertEquals(current == 204 ? 200001 : 200005, monster.getInstId());
    assertEquals(current == 204 ? 80139011 : 80010011, monster.getEventId());
    assertEquals(53, monster.getCustomLevel(), "53 is explicitly selected from the original difficulty row");
    assertSame(GameData.getStageExcelMap().get(monster.getEventId()), monster.getCustomStage());
    assertSame(p().getScene().getResourceGroups().get(monster.getGroupId()).getMonsterById(monster.getInstId()),
        monster.getMonsterInfo(), "Retain the original placement, even when the server selects its combat pool");
    return monster;
  }
  Battle battle(BattleEndStatus result) {
    var monster = actualMonster();
    var battle = new Battle(p(), p().getCurrentLineup(), (Collection<EntityMonster>) List.of(monster));
    assertFalse(battle.getWaves().isEmpty(), "A real original StageExcel must produce combat waves");
    p().setBattle(battle);
    battle.setResult(result);
    battle.setStats(BattleStatistics.newInstance());
    return battle;
  }
  void settle(Battle battle) {
    if (battle.getResult() == BattleEndStatus.BATTLE_END_WIN)
      for (var monster : battle.getNpcMonsters()) assertTrue(p().getScene().defeatMonster(monster));
    // Exercise the retained legacy callback independently. The actual service report path below
    // commits the finite guide before removing its actor and never reaches ordinary drops.
    p().getUniverseManager().onBattleFinish(battle);
    p().setBattle(null);
  }
  void win() { settle(battle(BattleEndStatus.BATTLE_END_WIN)); }

  Battle unreportedBattle() {
    var active = battle(BattleEndStatus.BATTLE_END_WIN);
    active.setResult(null); active.setStats(null);
    return active;
  }

  BattleStatistics reportedBars(double hp, double sp) {
    return BattleStatistics.newInstance().addBattleAvatarList(
        emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance().setId(1001)
            .setAvatarStatus(emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty.newInstance()
                .setMaxHp(100).setLeftHp(hp).setMaxSp(100).setLeftSp(sp)));
  }

  BattleService observedDrops(int[] calls) {
    // Retain the actual service and replace only its inventory/database drop leaf. The finite guide
    // deliberately has its own declared credits and must never reach this ordinary drop callback.
    return new BattleService(null) {
      protected void grantDrops(Battle settled) { calls[0]++; }
    };
  }

  void assertCommittedLineupNotice(int previousNotices, int previousSaves, int hp, int sp) {
    assertEquals(previousNotices + 1, p().lineupNotices.size(), "One successful report publishes one native lineup");
    var notice = p().lineupNotices.getLast();
    assertEquals(previousSaves + 1, notice.committedSaves(), "The authoritative lineup must be sent after the successful save");
    assertEquals(p().saved.getLast(), notice.stateAtSend(), "Notice bars and receipt must already be durable together");
    var lineup = (DynamicMessage) Gameplay46.value(notice.packet(), "lineup");
    var avatars = (List<?>) Gameplay46.value(lineup, "avatar_list"); assertEquals(1, avatars.size());
    var actor = (DynamicMessage) avatars.getFirst(); assertEquals(1001, Gameplay46.number(actor, "id"));
    assertEquals(hp, Gameplay46.number(actor, "hp"));
    var energy = (DynamicMessage) Gameplay46.value(actor, "sp_bar");
    assertEquals(sp, Gameplay46.number(energy, "cur_sp"));
    assertEquals(p().getAvatarById(1001).getMaxSp(), Gameplay46.number(energy, "max_sp"));
  }

  void fourWins() throws Exception {
    start();
    win();
    for (int id : List.of(202, 203, 204)) { move(id); win(); }
    var game = query();
    assertEquals(400, coins(game), "Credits come only from four distinct real victory settlements");
    for (int id : List.of(201, 202, 203, 204)) assertEquals(3, status(game, id));
  }
  DynamicMessage next() throws Exception {
    return call(5447, EMPTY, "ChessRogueEnterNextLayerScRsp");
  }
  EntityNpc merchant() throws Exception {
    fourWins();
    var response = next();
    assertEquals(0, code(response));
    var game = child(response, 11);
    fullBoard(game, 1012, 10121, 201, TUTORIAL2);
    actualStage(child(response, 8), Set.of(34, 35));
    assertEquals(2, number(cell(game, 201), 14), "Original tutorial2's starting block is Respite");
    var npcs = p().getScene().getEntities().values().stream().filter(EntityNpc.class::isInstance)
        .map(EntityNpc.class::cast).filter(n -> n.getNpcId() == 1013).toList();
    assertEquals(1, npcs.size());
    var npc = npcs.getFirst();
    assertEquals(35, npc.getGroupId());
    assertEquals(400001, npc.getInstId());
    assertSame(p().getScene(), npc.getScene());
    assertTrue(p().getScene().getFloorInfo().isLoaded());
    return npc;
  }
  /** The new acquisition checks use actual BattleService reports rather than a fabricated room clear. */
  EntityNpc merchantFromActualReports() throws Exception {
    start(); int[] drops = {0}; var service = observedDrops(drops);
    for (int cell : List.of(201, 202, 203, 204)) {
      if (cell != 201) move(cell);
      var active = unreportedBattle();
      assertSame(active, service.finishBattle(p(), active.getId(), active.getStage().getId(),
          BattleEndStatus.BATTLE_END_WIN, reportedBars(50, 25)));
      assertNull(p().getBattle());
      assertEquals(3, status(query(), cell));
    }
    assertEquals(0, drops[0]); assertEquals(400, coins(query()));
    var next = next(); assertEquals(0, code(next)); actualStage(child(next, 8), Set.of(34, 35));
    var npcs = p().getScene().getEntities().values().stream().filter(EntityNpc.class::isInstance)
        .map(EntityNpc.class::cast).filter(n -> n.getNpcId() == 1013 && n.getGroupId() == 35).toList();
    assertEquals(1, npcs.size()); var npc = npcs.getFirst();
    assertEquals(400001, npc.getInstId()); assertSame(p().getScene(), npc.getScene());
    return npc;
  }
  List<ClientSend> miracleAcquisitionsSince(int index) {
    return session.clientSends.subList(index, session.clientSends.size()).stream()
        .filter(sent -> sent.name().equals("SyncRogueCommonActionResultScNotify")).toList();
  }
  void assertCommittedMiracleAcquisition(int previousSends, int previousSaves) {
    var updates = miracleAcquisitionsSince(previousSends);
    assertEquals(1, updates.size(), "One saved native purchase publishes exactly one acquired Chess miracle");
    var notice = updates.getFirst();
    assertEquals(previousSaves + 1, notice.committedSaves(), "Miracle ownership must be saved before its increment");
    assertEquals(p().saved.getLast(), notice.stateAtSend(), "Wallet, stock and acquired UUID must already be durable together");
    var packet = notice.packet();
    assertEquals(201, number(packet, 1), "The actual consumer must update Chess rather than classic mode101");
    var actions = children(packet, 15); assertEquals(1, actions.size());
    var detail = child(actions.getFirst(), 3);
    assertEquals(Set.of(894), detail.getAllFields().keySet().stream()
        .map(field -> field.getNumber()).collect(java.util.stream.Collectors.toSet()),
        "Only the source-proven AddMiracleItem oneof is admitted");
    var acquired = child(detail, 894);
    var miracle = (DynamicMessage) Gameplay46.value(acquired, "miracle");
    assertEquals(4, number(miracle, 2)); assertEquals(0, number(miracle, 1)); assertEquals(1, number(miracle, 4));
    var events = session.clientSends.subList(previousSends, session.clientSends.size());
    var responses = events.stream().filter(sent -> sent.name().equals("BuyRogueShopMiracleScRsp")).toList();
    assertEquals(1, responses.size());
    assertTrue(events.indexOf(notice) < events.indexOf(responses.getFirst()),
        "The current client's miracle store is updated before the successful shop response");
    assertEquals(0, code(responses.getFirst().packet()));
  }
  DynamicMessage shop(int entity) throws Exception {
    var response = call(5675, body(out -> out.writeUInt32(12, entity)), "GetRogueShopMiracleInfoScRsp");
    assertEquals(0, code(response), "Original G35's MiracleShop page must admit its actual loaded NPC");
    return response;
  }
  DynamicMessage offer(DynamicMessage response) {
    var rows = children(child(response, 8), 5);
    assertEquals(1, rows.size(), "Only the explicitly enabled compatible miracle4 is offered in this isolated catalog");
    return rows.getFirst();
  }
  DynamicMessage buy(int entity, int miracle) throws Exception {
    return call(5656, body(out -> { out.writeUInt32(12, entity); out.writeUInt32(6, miracle); }),
        "BuyRogueShopMiracleScRsp");
  }
  void assertUnchanged(JsonObject before, Scene prior, int saves) {
    assertEquals(before, p().snapshot());
    assertSame(prior, p().getScene());
    assertEquals(saves, p().saved.size());
  }
  void reload(JsonObject saved) {
    var nextSession = new Session();
    var nextPlayer = nextSession.explorer;
    nextPlayer.state = GSON.fromJson(saved.get("gameplay"), GameplayState.class);
    addAvatar(nextPlayer);
    assertTrue(nextPlayer.loadScene(saved.get("plane").getAsInt(), saved.get("floor").getAsInt(),
        saved.get("entry").getAsInt(), GSON.fromJson(saved.get("pos"), Position.class),
        GSON.fromJson(saved.get("rot"), Position.class), false));
    nextPlayer.saved.clear();
    session = nextSession;
  }

  MazeSkill originalAstaWithObservedRelease(int[] releases) throws Exception {
    JsonObject record;
    try (var input = getClass().getResourceAsStream("/fixtures/maze-cast-sequence-4.6.json")) {
      assertNotNull(input);
      var proof = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(COMMIT, proof.get("sourceCommit").getAsString());
      record = proof.getAsJsonArray("records").asList().stream().map(JsonElement::getAsJsonObject)
          .filter(row -> row.get("avatarID").getAsInt() == 1009).findFirst().orElseThrow();
    }
    var excel = RESOURCE_GSON.fromJson(record.get("avatar"), AvatarExcel.class);
    excel.onLoad();
    var config = new JsonObject(); config.add("AbilityList", record.get("abilities"));
    assertTrue(RESOURCE_GSON.fromJson(config, SkillAbilityInfo.class).parse(excel));
    GameData.getAvatarExcelMap().add(excel);
    var avatar = new GameAvatar(excel) { public void save() {} };
    avatar.setOwner(p()); p().getAvatars().getAvatars().put(1009, avatar);
    Files.writeString(root.resolve("ExcelOutput/MazeSkill.json"), GSON.toJson(record.get("mazeRows")));
    var skill = excel.getMazeSkill();
    assertEquals("LocalPlayer_Asta_MazeSkill", skill.getAbilityName());
    assertTrue(skill.isProjectileDeclared(), "The full original ability owns the projectile declaration");
    // This regression observes lineup/UseID identity, not restoration of Asta's original effects.
    skill.getCastActions().clear(); skill.getAttackActions().clear(); skill.setTriggerBattle(false);
    skill.getCastActions().add(new MazeSkillAction() {
      public void onCast(GameAvatar caster, emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo position) {
        releases[0]++;
      }
    });
    var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
    session.battleShell = (GameServer) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(GameServer.class);
    var service = GameServer.class.getDeclaredField("battleService"); service.setAccessible(true);
    service.set(session.battleShell, new BattleService(session.battleShell));
    return skill;
  }

  DynamicMessage nativeTechnique(String name, int use, GameAvatar avatar) throws Exception {
    var request = DynamicMessage.newBuilder(session.getProtocol().clientSchema().message(name));
    var type = request.getDescriptorForType();
    request.setField(type.findFieldByName("cast_entity_id"), use);
    request.setField(type.findFieldByName("attacked_by_entity_id"), avatar.getEntityId());
    request.setField(type.findFieldByName("skill_index"), 1);
    if (name.equals("SceneCastSkillCsReq")) {
      request.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), actualMonster().getEntityId());
      var tags = type.findFieldByName("skill_extra_tags");
      request.addRepeatedField(tags, tags.getEnumType().findValueByNumber(0));
    }
    return call(session.getProtocol().clientCommand(name), request.build().toByteArray(), name.replace("CsReq", "ScRsp"));
  }

  @Test
  void original1408PaymentAnd1460ReleaseKeepOneChessLineupAndSpendOnlyItsOwnMp() throws Exception {
    int[] releases = {0}; var skill = originalAstaWithObservedRelease(releases);
    var ordinary = p().teams.getLineupByIndex(0); ordinary.setMp(5);
    assertEquals(1408, session.getProtocol().clientCommand("SceneCastSkillCostMpCsReq"));
    assertEquals(1460, session.getProtocol().clientCommand("SceneCastSkillCsReq"));
    assertEquals(0, code(call(5426, startBody(101, 6, 1009), "ChessRogueStartScRsp")));
    var run = p().state.getChessRogue(); var lineup = p().getCurrentLineup(); var avatar = p().getAvatarById(1009);
    assertEquals(0, code(nativeTechnique("SceneCastSkillCostMpCsReq", 72141, avatar)));
    assertSame(run, p().state.getChessRogue()); assertSame(lineup, p().getCurrentLineup());
    assertEquals(4, lineup.getMp()); assertEquals(5, ordinary.getMp());
    assertTrue(p().state.getMazeCasts().find(session, 72141).matches(avatar, skill, 1));
    avatar.setCurrentSp(lineup, 2222); // Another saved update must retain the paid lineup object.
    assertEquals(0, code(nativeTechnique("SceneCastSkillCsReq", 72141, avatar)));
    assertEquals(0, code(nativeTechnique("SceneCastSkillCostMpCsReq", 72141, avatar)));
    assertEquals(0, code(nativeTechnique("SceneCastSkillCsReq", 72141, avatar)));
    assertEquals(1, releases[0]); assertEquals(4, lineup.getMp()); assertEquals(5, ordinary.getMp());
    assertSame(lineup, p().getCurrentLineup()); assertSame(run, p().state.getChessRogue());
  }

  @Test
  void failedOriginalTechniquePaymentRestoresMpAndCanRetryItsSameUseIdAndLineup() throws Exception {
    int[] releases = {0}; originalAstaWithObservedRelease(releases);
    p().teams.getLineupByIndex(0).setMp(5);
    assertEquals(0, code(call(5426, startBody(101, 6, 1009), "ChessRogueStartScRsp")));
    var avatar = p().getAvatarById(1009); var lineup = p().getCurrentLineup();
    var before = p().snapshot(); int saves = p().saved.size(); p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> nativeTechnique("SceneCastSkillCostMpCsReq", 72142, avatar));
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size());
    assertSame(lineup, p().getCurrentLineup());
    assertFalse(session.packets.containsKey("SceneCastSkillCostMpScRsp"));
    assertFalse(p().state.getMazeCasts().find(session, 72142).prepaid());
    p().interruptSave = false;
    assertEquals(0, code(nativeTechnique("SceneCastSkillCostMpCsReq", 72142, avatar)));
    assertEquals(0, code(nativeTechnique("SceneCastSkillCsReq", 72142, avatar)));
    assertEquals(4, lineup.getMp()); assertEquals(1, releases[0]);
  }

  @Test
  void chessHpAndSpAreIndependentFromFormalClassicAndGenericExtraAvatarBars() throws Exception {
    var avatar = p().getAvatarById(1001); var ordinary = p().teams.getLineupByIndex(0);
    var generic = p().teams.getExtraLineupByType(13);
    avatar.setCurrentHp(ordinary, 8200); avatar.setCurrentSp(ordinary, 3100);
    avatar.setCurrentHp(generic, 6500); avatar.setCurrentSp(generic, 2900);
    p().state.getRogue().getVitals().put(1001, new GameplayState.RogueVitals(7300, 3700));
    var classic = p().getUniverseManager().lineup();
    start(); var lineup = p().getCurrentLineup();
    assertEquals(8200, avatar.getCurrentHp(lineup)); assertEquals(3100, avatar.getCurrentSp(lineup));
    avatar.setCurrentHp(lineup, 4200); avatar.setCurrentSp(lineup, 1600);
    assertSame(lineup, p().getCurrentLineup());
    assertEquals(4200, avatar.getCurrentHp(lineup)); assertEquals(1600, avatar.getCurrentSp(lineup));
    assertEquals(8200, avatar.getCurrentHp(ordinary)); assertEquals(3100, avatar.getCurrentSp(ordinary));
    assertEquals(7300, avatar.getCurrentHp(classic)); assertEquals(3700, avatar.getCurrentSp(classic));
    assertEquals(6500, avatar.getCurrentHp(generic)); assertEquals(2900, avatar.getCurrentSp(generic));
    var before = p().snapshot(); int saves = p().saved.size(); p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> avatar.setCurrentHp(lineup, 1000));
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size()); assertSame(lineup, p().getCurrentLineup());
    p().interruptSave = false;
    win(); move(202); assertSame(lineup, p().getCurrentLineup());
    assertEquals(4200, avatar.getCurrentHp(lineup)); assertEquals(1600, avatar.getCurrentSp(lineup));
  }

  @Test
  void native704CannotEditAClassicOrFormalRosterThroughTheIndependentChessTeam() throws Exception {
    start(); var before = p().snapshot(); int saves = p().saved.size(); var lineup = p().getCurrentLineup();
    var request = DynamicMessage.newBuilder(session.getProtocol().clientSchema().message("ReplaceLineupCsReq"));
    var type = request.getDescriptorForType();
    var extra = type.findFieldByName("extra_lineup_type");
    request.setField(extra, extra.getEnumType().findValueByNumber(2));
    var slots = type.findFieldByName("lineup_slot_list");
    var row = DynamicMessage.newBuilder(slots.getMessageType());
    row.setField(row.getDescriptorForType().findFieldByName("id"), 1001);
    var kind = row.getDescriptorForType().findFieldByName("avatar_type");
    row.setField(kind, kind.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
    request.addRepeatedField(slots, row.build());
    assertTrue(VersionedPacketHandler.handle(session, 704, request.build().toByteArray()));
    var bytes = session.commandPackets.get(719); assertNotNull(bytes);
    var input = com.google.protobuf.CodedInputStream.newInstance(bytes); int retcode = 0;
    for (int tag; (tag = input.readTag()) != 0;) {
      if (tag == 72) retcode = input.readUInt32(); else assertTrue(input.skipField(tag));
    }
    assertNotEquals(0, retcode, "The original replacement reply must explicitly reject unsupported Chess editing");
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size()); assertSame(lineup, p().getCurrentLineup());
  }

  @Test
  void firstStartCarriesTheOriginalCompleteBoardAndActuallyLoadsItsFirstMonsterRoom() throws Exception {
    var classic = GSON.toJsonTree(p().state.getRogue());
    var response = start();
    fullBoard(child(response, 13), 1011, 10111, 201, TUTORIAL1);
    assertEquals(3, number(cell(child(response, 13), 201), 14));
    assertEquals(2, status(child(response, 13), 201));
    actualStage(child(response, 7), Set.of(24, 25));
    actualMonster();
    assertEquals(classic, GSON.toJsonTree(p().state.getRogue()), "Chess first entry must not forge a classic run");
    assertEquals(1, p().saved.size(), "State, location and selected roster must be saved together before response");
    assertEquals(p().snapshot(), p().saved.getLast());
    assertEquals(0, coins(child(response, 13)));
    assertFalse(chess().get("runId").getAsString().isBlank());
  }

  @Test
  void fourRealBattlesThenNextLayerReachTheOriginalG35ShopAndPayForAnOwnedMiracle() throws Exception {
    var npc = merchant();
    var menu = shop(npc.getEntityId());
    var quote = offer(menu);
    assertEquals(4, Gameplay46.number(quote, "LJCEOGPOGBE"));
    var cost = (DynamicMessage) Gameplay46.value(quote, "cost_data");
    var items = (List<?>) Gameplay46.value(cost, "item_list");
    assertEquals(1, items.size());
    var pile = (DynamicMessage) Gameplay46.value((DynamicMessage) items.getFirst(), "pile_item");
    assertEquals(200, Gameplay46.number(pile, "item_num"), "200 is declared server pricing, not recovered original pricing");
    var purchase = buy(npc.getEntityId(), 4);
    assertEquals(0, code(purchase));
    assertEquals(200, coins(query()));
    assertTrue(chess().getAsJsonObject("miracles").has("4"));
    assertFalse(chess().getAsJsonObject("miracles").getAsJsonObject("4").get("acquisitionId").getAsString().isBlank());
    assertTrue(p().state.getRogue().getMiracles().isEmpty());
    assertTrue(Gameplay46.value(offer(shop(npc.getEntityId())), "BELPGDAGFIH").equals(true));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void nextLayerCannotSkipAnyOfTheFourActualTutorialVictories(int victories) throws Exception {
    start();
    for (int i = 0; i < victories; i++) { if (i > 0) move(201 + i); win(); }
    var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    assertNotEquals(0, code(next()));
    assertUnchanged(before, prior, saves);
  }

  @Test
  void selectCannotBypassTheUnfinishedFirstBattleOrJumpDirectlyToBoss() throws Exception {
    start();
    var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    assertNotEquals(0, code(select(202)));
    assertNotEquals(0, code(select(204)));
    assertUnchanged(before, prior, saves);
  }

  @Test
  void finishingOneBattleExposesOnlyTheDeclaredNextOriginalCellAndItsNativeSelectionNotification() throws Exception {
    start(); win();
    var game = query();
    assertEquals(List.of(202), value(board(game), 15));
    assertEquals(3, status(game, 201));
    assertEquals(100, coins(game));
    assertEquals(0, code(select(202)));
    assertTrue(session.packets.containsKey("ChessRogueUpdateAllowedSelectCellScNotify")
        || session.packets.containsKey("ChessRogueCellUpdateNotify"),
        "Selection changes need an actual native state notification as well as the response");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 201, 204, 303, 999})
  void enterCellRequiresTheSelectedOriginalCellWithTheNativeDefaultMonsterGroup(int cell) throws Exception {
    start(); win(); assertEquals(0, code(select(202)));
    var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    assertNotEquals(0, code(enter(cell, 0)));
    assertUnchanged(before, prior, saves);
  }

  @Test
  void clientCannotPutServerCombatPoolIdsIntoTheDefaultMonsterGroupField() throws Exception {
    start(); win(); assertEquals(0, code(select(202)));
    var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    for (int pool : List.of(11, 13901)) assertNotEquals(0, code(enter(202, pool)));
    assertUnchanged(before, prior, saves);
  }

  @Test
  void duplicateBattleSettlementDoesNotGrantCoinsOrFinishADifferentCell() throws Exception {
    start(); var battle = battle(BattleEndStatus.BATTLE_END_WIN); settle(battle);
    var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    p().getUniverseManager().onBattleFinish(battle);
    assertUnchanged(before, prior, saves);
    assertEquals(100, coins(query()));
    assertEquals(0, status(query(), 202));
  }

  @Test
  void lossCannotAwardCoinsClearTheCurrentCellOrOpenTheNextLayer() throws Exception {
    start(); settle(battle(BattleEndStatus.BATTLE_END_LOSE));
    assertEquals(0, coins(query()));
    assertNotEquals(3, status(query(), 201));
    assertNotEquals(0, code(next()));
  }

  @Test
  void resumeOnAFreshAccountDoesNotStandInForFirstStart() throws Exception {
    var before = p().snapshot(); var prior = p().getScene();
    var response = call(5437, body(out -> out.writeUInt32(8, 101)), "ChessRogueEnterScRsp");
    assertNotEquals(0, code(response));
    assertUnchanged(before, prior, 0);
  }

  @Test
  void samePersistedRunResumesAnUnclearedFirstRoomWithoutRestartingOrGivingCoins() throws Exception {
    start(); var saved = p().saved.getLast(); var expected = chess().deepCopy();
    reload(saved);
    var response = call(5437, body(out -> out.writeUInt32(8, 101)), "ChessRogueEnterScRsp");
    assertEquals(0, code(response));
    fullBoard(child(response, 3), 1011, 10111, 201, TUTORIAL1);
    actualStage(child(response, 6), Set.of(24, 25));
    actualMonster();
    assertEquals(expected, chess());
    assertEquals(0, coins(child(response, 3)));
  }

  @Test
  void persistedFirstVictoryKeepsItsReceiptAndDoesNotRespawnTheClearedMonsterAfterReload() throws Exception {
    start(); win(); var saved = p().saved.getLast(); var expected = chess().deepCopy();
    reload(saved);
    var response = call(5437, body(out -> out.writeUInt32(8, 101)), "ChessRogueEnterScRsp");
    assertEquals(0, code(response));
    assertEquals(expected, chess());
    assertEquals(100, coins(child(response, 3)));
    assertEquals(3, status(child(response, 3), 201));
    assertTrue(p().getScene().getEntities().values().stream().noneMatch(EntityMonster.class::isInstance));
    move(202); actualMonster();
  }

  @Test
  void repeatedFirstStartCannotResetAnExistingRunOrItsBalance() throws Exception {
    start(); win();
    var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    assertNotEquals(0, code(call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, saves);
  }

  @Test
  void originalUnlockMissionMustReallyBeFinishedBeforeFirstEntry() throws Exception {
    p().state.getMainMissions().clear();
    var before = p().snapshot(); var prior = p().getScene();
    assertNotEquals(0, code(call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, 0);
    assertTrue(p().state.getMainMissions().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 102, 199999})
  void unsupportedAreaCannotUseTheGuideRoute(int area) throws Exception {
    var before = p().snapshot(); var prior = p().getScene();
    assertNotEquals(0, code(call(5426, startBody(area, 6, 1001), "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 9, 99})
  void theOriginalGuideAeonCannotBeChangedByAnUnverifiedStartParameter(int aeon) throws Exception {
    var before = p().snapshot(); var prior = p().getScene();
    assertNotEquals(0, code(call(5426, startBody(101, aeon, 1001), "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, 0);
  }

  @Test
  void firstStartNeedsANonemptyUniqueOwnedFormalRoster() throws Exception {
    for (int[] roster : List.of(new int[0], new int[] {1001, 1001}, new int[] {199999})) {
      var before = p().snapshot(); var prior = p().getScene();
      assertNotEquals(0, code(call(5426, startBody(101, 6, roster), "ChessRogueStartScRsp")));
      assertUnchanged(before, prior, 0);
    }
  }

  @Test
  void aClassicRunCannotBeReplacedByChessFirstEntry() throws Exception {
    var classic = new GameplayState.RogueRun(110, 101, Map.of(1, 304));
    classic.enter(1, 304); classic.setInside(true); classic.getRoster().add(1001);
    p().state.setRogue(classic);
    var before = p().snapshot(); var prior = p().getScene();
    assertNotEquals(0, code(call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, 0);
  }

  @Test
  void unsupportedExtraStartFieldsCannotBeSilentlyAccepted() throws Exception {
    var legal = startBody(101, 6, 1001);
    var extra = body(out -> out.writeUInt32(1, 9));
    var wire = new byte[legal.length + extra.length];
    System.arraycopy(legal, 0, wire, 0, legal.length);
    System.arraycopy(extra, 0, wire, legal.length, extra.length);
    var before = p().snapshot(); var prior = p().getScene();
    assertNotEquals(0, code(call(5426, wire, "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, 0);
  }

  @Test
  void firstStartSaveFailureRestoresBothPlayerLocationAndTheUnacknowledgedRun() {
    var before = p().snapshot(); var prior = p().getScene();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, this::start);
    assertUnchanged(before, prior, 0);
    assertFalse(session.packets.containsKey("ChessRogueStartScRsp"));
  }

  @Test
  void failedVictorySaveRestoresCoinsClearAndReceiptAndCanRetryThatActualBattle() throws Exception {
    start(); var battle = battle(BattleEndStatus.BATTLE_END_WIN);
    var before = p().snapshot(); int saves = p().saved.size();
    for (var monster : battle.getNpcMonsters()) assertTrue(p().getScene().defeatMonster(monster));
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> p().getUniverseManager().onBattleFinish(battle));
    assertEquals(before, p().snapshot());
    assertEquals(saves, p().saved.size());
    p().interruptSave = false;
    p().getUniverseManager().onBattleFinish(battle); p().setBattle(null);
    assertEquals(100, coins(query()));
    assertEquals(3, status(query(), 201));
    var once = p().snapshot(); int committed = p().saved.size();
    p().getUniverseManager().onBattleFinish(battle);
    assertEquals(once, p().snapshot()); assertEquals(committed, p().saved.size());
  }

  @Test
  void realBattleServiceSaveFailureRestoresReportedVitalsAndRetriesTheSameSettlementWithoutRepeatingDrops() throws Exception {
    start();
    var actor = p().getAvatarById(1001); var lineup = p().getCurrentLineup();
    var ordinary = p().teams.getLineupByIndex(0);
    int formalHp = actor.getCurrentHp(ordinary), formalSp = actor.getCurrentSp(ordinary);
    var active = unreportedBattle();
    var monster = active.getNpcMonsters().getFirst(); int entity = monster.getEntityId();
    var report = reportedBars(50, 25);
    int[] dropCalls = {0};
    var service = observedDrops(dropCalls);
    var before = p().snapshot(); int saves = p().saved.size(), notices = p().lineupNotices.size(); p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> service.finishBattle(p(), active.getId(),
        active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, report));
    var afterFailure = p().snapshot();
    int failedHp = actor.getCurrentHp(lineup), failedSp = actor.getCurrentSp(lineup);
    assertSame(active, p().getBattle(), "The actual active battle survives an unacknowledged save");
    assertSame(lineup, p().getCurrentLineup()); assertEquals(saves, p().saved.size());
    assertEquals(notices, p().lineupNotices.size(), "Failed report must not expose candidate bars in a native lineup notice");
    assertFalse(active.isSettling()); assertNull(active.getResult()); assertNull(active.getStats());
    assertEquals(entity, monster.getEntityId()); assertSame(monster, p().getScene().getEntities().get(entity));
    p().interruptSave = false;
    var retried = service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, report);
    assertAll("Actual report values, failed player commit and same native settlement retry",
        () -> assertEquals(before, afterFailure, "Coins, receipt, current cell and pre-report HP/SP all roll back"),
        () -> assertEquals(formalHp, failedHp, "Reported 50% HP is not exposed after failed save"),
        () -> assertEquals(formalSp, failedSp, "Reported 25% SP is not exposed after failed save"),
        () -> assertSame(active, retried, "The identical actual service report must remain retryable"),
        () -> assertEquals(0, dropCalls[0], "This finite guide must not borrow generic inventory drops before or during retry"));
    assertNull(p().getBattle()); assertSame(lineup, p().getCurrentLineup());
    assertTrue(active.isSettling()); assertSame(report, active.getStats());
    assertEquals(0, monster.getEntityId()); assertFalse(p().getScene().getEntities().containsKey(entity));
    assertEquals(saves + 1, p().saved.size(), "Only the successful report commits its bars and receipt together");
    assertCommittedLineupNotice(notices, saves, 5000, actor.getMaxSp() / 4);
    assertEquals(5000, actor.getCurrentHp(lineup)); assertEquals(actor.getMaxSp() / 4, actor.getCurrentSp(lineup));
    assertEquals(formalHp, actor.getCurrentHp(ordinary)); assertEquals(formalSp, actor.getCurrentSp(ordinary));
    assertEquals(100, coins(query())); assertEquals(3, status(query(), 201));
    var committed = p().snapshot(); int committedSaves = p().saved.size();
    assertNull(service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, report));
    assertEquals(committed, p().snapshot()); assertEquals(committedSaves, p().saved.size()); assertEquals(0, dropCalls[0]);
    assertEquals(notices + 1, p().lineupNotices.size(), "Duplicate report cannot resend a settlement notice");
  }

  @Test
  void fourActualServiceReportsCommitOriginalMonsterRoomsBeforeTheG35MerchantCanBeReached() throws Exception {
    start(); var run = p().state.getChessRogue(); var lineup = p().getCurrentLineup();
    int[] drops = {0}; var service = observedDrops(drops); int victories = 0;
    for (int cell : List.of(201, 202, 203, 204)) {
      if (cell != 201) move(cell);
      var active = unreportedBattle(); var monster = active.getNpcMonsters().getFirst();
      int entity = monster.getEntityId(), saves = p().saved.size(), notices = p().lineupNotices.size();
      assertSame(active, service.finishBattle(p(), active.getId(), active.getStage().getId(),
          BattleEndStatus.BATTLE_END_WIN, reportedBars(50, 25)));
      assertNull(p().getBattle()); assertEquals(0, monster.getEntityId());
      assertFalse(p().getScene().getEntities().containsKey(entity)); assertEquals(saves + 1, p().saved.size());
      assertCommittedLineupNotice(notices, saves, 5000, p().getAvatarById(1001).getMaxSp() / 4);
      assertSame(run, p().state.getChessRogue()); assertSame(lineup, p().getCurrentLineup());
      assertEquals(++victories * 100, coins(query())); assertEquals(3, status(query(), cell));
      assertEquals(5000, p().getAvatarById(1001).getCurrentHp(lineup));
    }
    var response = next(); assertEquals(0, code(response));
    fullBoard(child(response, 11), 1012, 10121, 201, TUTORIAL2);
    actualStage(child(response, 8), Set.of(34, 35));
    var npc = p().getScene().getEntities().values().stream().filter(EntityNpc.class::isInstance)
        .map(EntityNpc.class::cast).filter(row -> row.getNpcId() == 1013 && row.getGroupId() == 35).findFirst().orElseThrow();
    assertEquals(4, Gameplay46.number(offer(shop(npc.getEntityId())), "LJCEOGPOGBE"));
    assertEquals(0, code(buy(npc.getEntityId(), 4))); assertEquals(200, coins(query()));
    assertEquals(0, drops[0]);
  }

  @Test
  void realServiceRejectsWrongIdentityMalformedBarsAndChangedWholeSourceWithoutConsumingTheBattle() throws Exception {
    start(); var active = unreportedBattle(); var monster = active.getNpcMonsters().getFirst();
    int entity = monster.getEntityId(); var before = p().snapshot(); int saves = p().saved.size();
    int[] drops = {0}; var service = observedDrops(drops); var legal = reportedBars(50, 25);
    assertNull(service.finishBattle(p(), active.getId() + 1, active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, legal));
    assertNull(service.finishBattle(p(), active.getId(), active.getStage().getId() + 1, BattleEndStatus.BATTLE_END_WIN, legal));
    assertNull(service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_QUIT, legal));
    assertNull(service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, reportedBars(101, 25)));
    var duplicate = reportedBars(50, 25).addBattleAvatarList(reportedBars(50, 25).getBattleAvatarList().get(0));
    assertNull(service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, duplicate));
    var wave = active.getWaves().removeLast();
    assertNull(service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, legal));
    active.getWaves().add(wave);
    var original = Files.readAllBytes(root.resolve(ACT));
    Files.write(root.resolve(ACT), new byte[] {0});
    assertNull(service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, legal));
    Files.write(root.resolve(ACT), original);
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size()); assertEquals(0, drops[0]);
    assertSame(active, p().getBattle()); assertFalse(active.isSettling());
    assertNull(active.getStats()); assertNull(active.getResult());
    assertEquals(entity, monster.getEntityId()); assertSame(monster, p().getScene().getEntities().get(entity));
    assertSame(active, service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, legal));
    assertEquals(100, coins(query())); assertEquals(0, drops[0]);
  }

  @Test
  void realLosingReportSaveFailureRestoresTheRunAndRetryCommitsOnlyItsIndependentFailedState() throws Exception {
    start(); var active = unreportedBattle(); var monster = active.getNpcMonsters().getFirst();
    int entity = monster.getEntityId(); var lineup = p().getCurrentLineup(); var actor = p().getAvatarById(1001);
    var ordinary = p().teams.getLineupByIndex(0); int ordinaryHp = actor.getCurrentHp(ordinary), ordinarySp = actor.getCurrentSp(ordinary);
    int[] drops = {0}; var service = observedDrops(drops); var report = reportedBars(0, 0);
    var before = p().snapshot(); int saves = p().saved.size(), notices = p().lineupNotices.size(); p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> service.finishBattle(p(), active.getId(), active.getStage().getId(),
        BattleEndStatus.BATTLE_END_LOSE, report));
    assertEquals(before, p().snapshot()); assertSame(active, p().getBattle()); assertFalse(active.isSettling());
    assertEquals(notices, p().lineupNotices.size(), "Failed LOSE report cannot notify its candidate bars");
    assertNull(active.getStats()); assertNull(active.getResult());
    assertEquals(entity, monster.getEntityId()); assertSame(monster, p().getScene().getEntities().get(entity));
    p().interruptSave = false;
    assertSame(active, service.finishBattle(p(), active.getId(), active.getStage().getId(), BattleEndStatus.BATTLE_END_LOSE, report));
    assertNull(p().getBattle()); assertSame(lineup, p().getCurrentLineup()); assertEquals(saves + 1, p().saved.size());
    assertCommittedLineupNotice(notices, saves, 2000, 0);
    assertTrue(p().state.getChessRogue().isFailed()); assertTrue(p().state.getChessRogue().getVictories().isEmpty());
    assertEquals(0, coins(query())); assertEquals(2, status(query(), 201));
    assertEquals(2000, actor.getCurrentHp(lineup)); assertEquals(0, actor.getCurrentSp(lineup));
    assertEquals(ordinaryHp, actor.getCurrentHp(ordinary)); assertEquals(ordinarySp, actor.getCurrentSp(ordinary));
    assertEquals(entity, monster.getEntityId()); assertEquals(0, drops[0]);
    assertNotEquals(0, code(call(5437, body(out -> out.writeUInt32(8, 101)), "ChessRogueEnterScRsp")));
    assertNotEquals(0, code(next()));
  }

  @Test
  void failedSelectionSaveDoesNotExposeANewCurrentCellOrMutateTheOriginalRoom() throws Exception {
    start(); win(); var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> select(202));
    assertUnchanged(before, prior, saves);
    assertFalse(session.packets.containsKey("ChessRogueSelectCellScRsp"));
    p().interruptSave = false; move(202);
  }

  @Test
  void failedActualSameFloorEntryRestoresSelectedCellSceneAndAvatarEntityIdentity() throws Exception {
    start(); win(); assertEquals(0, code(select(202)));
    var before = p().snapshot(); var prior = p().getScene(); var lineup = p().getCurrentLineup();
    var avatar = p().getAvatarById(1001); int entity = avatar.getEntityId(), saves = p().saved.size();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> enter(202, 0));
    assertUnchanged(before, prior, saves); assertEquals(entity, avatar.getEntityId());
    assertSame(lineup, p().getCurrentLineup()); assertFalse(session.packets.containsKey("ChessRogueEnterCellScRsp"));
    p().interruptSave = false;
    assertEquals(0, code(enter(202, 0))); assertSame(lineup, p().getCurrentLineup()); actualMonster();
  }

  @Test
  void failedNextLayerSaveRestoresTheBossLayerAndCannotPublishARespiteScene() throws Exception {
    fourWins(); var before = p().snapshot(); var prior = p().getScene(); int saves = p().saved.size();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, this::next);
    assertUnchanged(before, prior, saves);
    assertFalse(session.packets.containsKey("ChessRogueEnterNextLayerScRsp"));
    p().interruptSave = false; assertEquals(0, code(next()));
  }

  @ParameterizedTest
  @ValueSource(strings = {TUTORIAL1, TUTORIAL2, FLOOR, ACT})
  void missingWholeOriginalSourceClosesFirstEntryInsteadOfInventingABoard(String source) throws Exception {
    Files.delete(root.resolve(source));
    GameplayDefinitions.load(root);
    var before = p().snapshot(); var prior = p().getScene();
    assertNotEquals(0, code(call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, 0);
  }

  @Test
  void serverRouteCanBeExplicitlyDisabled() throws Exception {
    var policy = fixture.getAsJsonObject("route").deepCopy(); policy.addProperty("enabled", false);
    Files.writeString(root.resolve(POLICY), GSON.toJson(policy)); GameplayDefinitions.load(root);
    var before = p().snapshot(); var prior = p().getScene();
    assertNotEquals(0, code(call(5426, startBody(101, 6, 1001), "ChessRogueStartScRsp")));
    assertUnchanged(before, prior, 0);
  }

  @Test
  void anActorWithTheSameNpcDefinitionOutsideItsActualRespiteContextIsNotAShop() throws Exception {
    start();
    var response = call(5675, body(out -> out.writeUInt32(12, 400001)), "GetRogueShopMiracleInfoScRsp");
    assertNotEquals(0, code(response));
    assertEquals(0, coins(query()));
    assertTrue(p().state.getRogue().getMiracleShops().isEmpty());
  }

  @Test
  void repeatedPurchaseAndRestartKeepTheSameSourceStockAndAcquisitionIdentity() throws Exception {
    var npc = merchant(); shop(npc.getEntityId()); assertEquals(0, code(buy(npc.getEntityId(), 4)));
    var after = p().snapshot(); var acquisition = chess().getAsJsonObject("miracles").getAsJsonObject("4").deepCopy();
    assertNotEquals(0, code(buy(npc.getEntityId(), 4)));
    assertEquals(after, p().snapshot());
    reload(p().saved.getLast());
    var resumed = call(5437, body(out -> out.writeUInt32(8, 101)), "ChessRogueEnterScRsp");
    assertEquals(0, code(resumed));
    var actual = p().getScene().getEntities().values().stream().filter(EntityNpc.class::isInstance)
        .map(EntityNpc.class::cast).filter(row -> row.getNpcId() == 1013 && row.getGroupId() == 35).findFirst().orElseThrow();
    assertTrue((Boolean) Gameplay46.value(offer(shop(actual.getEntityId())), "BELPGDAGFIH"));
    assertEquals(acquisition, chess().getAsJsonObject("miracles").getAsJsonObject("4"));
    assertEquals(200, coins(query()));
    assertNotEquals(0, code(buy(actual.getEntityId(), 4)));
  }

  @Test
  void actualNativePurchasePublishesOneChessMiracleAfterSaveAndBeforeTheShopResponse() throws Exception {
    var npc = merchantFromActualReports(); shop(npc.getEntityId());
    var classic = GSON.toJsonTree(p().state.getRogue());
    int sends = session.clientSends.size(), saves = p().saved.size(); p().online = true;
    assertEquals(0, code(buy(npc.getEntityId(), 4)));
    assertCommittedMiracleAcquisition(sends, saves);
    assertEquals(classic, GSON.toJsonTree(p().state.getRogue()));
    assertEquals(200, coins(query()));
    var acquired = chess().getAsJsonObject("miracles").getAsJsonObject("4");
    assertFalse(acquired.get("acquisitionId").getAsString().isBlank());
    assertEquals(1, acquired.get("totalCount").getAsInt()); assertEquals(0, acquired.get("useCount").getAsInt());
    var once = p().snapshot(); int duplicateSends = session.clientSends.size(), duplicateSaves = p().saved.size();
    assertNotEquals(0, code(buy(npc.getEntityId(), 4)));
    assertTrue(miracleAcquisitionsSince(duplicateSends).isEmpty(), "A rejected duplicate cannot re-add the miracle");
    assertEquals(once, p().snapshot()); assertEquals(duplicateSaves, p().saved.size());
    assertEquals(classic, GSON.toJsonTree(p().state.getRogue()));
  }

  @Test
  void failedNativePaymentCannotPublishItsCandidateMiracleAndTheSamePurchaseRetriesOnce() throws Exception {
    var npc = merchantFromActualReports(); shop(npc.getEntityId());
    var before = p().snapshot(); var classic = GSON.toJsonTree(p().state.getRogue());
    int sends = session.clientSends.size(), saves = p().saved.size(); p().online = true; p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> buy(npc.getEntityId(), 4));
    assertTrue(miracleAcquisitionsSince(sends).isEmpty(), "An unsaved acquisition cannot reach the current client");
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size());
    assertFalse(session.packets.containsKey("BuyRogueShopMiracleScRsp"));
    assertEquals(classic, GSON.toJsonTree(p().state.getRogue()));
    p().interruptSave = false;
    assertEquals(0, code(buy(npc.getEntityId(), 4)));
    assertCommittedMiracleAcquisition(sends, saves);
    assertEquals(classic, GSON.toJsonTree(p().state.getRogue()));
    assertEquals(200, coins(query()));
  }

  @Test
  void aChangedWholeMerchantSourceCannotPublishAnyNativeMiracleAcquisition() throws Exception {
    var npc = merchantFromActualReports(); shop(npc.getEntityId());
    var changed = original(ACT); changed.addProperty("UnverifiedServerTask", true);
    Files.writeString(root.resolve(ACT), GSON.toJson(changed));
    var before = p().snapshot(); var classic = GSON.toJsonTree(p().state.getRogue());
    int sends = session.clientSends.size(), saves = p().saved.size(); p().online = true;
    assertNotEquals(0, code(buy(npc.getEntityId(), 4)));
    assertTrue(miracleAcquisitionsSince(sends).isEmpty());
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size());
    assertEquals(classic, GSON.toJsonTree(p().state.getRogue()));
  }

  @Test
  void failedMerchantStockSaveCannotExposeAMenuAndCanRetry() throws Exception {
    var npc = merchant(); var before = p().snapshot(); int saves = p().saved.size();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> shop(npc.getEntityId()));
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size());
    assertFalse(session.packets.containsKey("GetRogueShopMiracleInfoScRsp"));
    p().interruptSave = false; assertEquals(4, Gameplay46.number(offer(shop(npc.getEntityId())), "LJCEOGPOGBE"));
  }

  @Test
  void failedMerchantPaymentSaveRestoresCoinsStockOwnershipAndUuidBeforeRetry() throws Exception {
    var npc = merchant(); shop(npc.getEntityId()); var before = p().snapshot(); int saves = p().saved.size();
    p().interruptSave = true;
    assertThrows(IllegalStateException.class, () -> buy(npc.getEntityId(), 4));
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size());
    assertFalse(session.packets.containsKey("BuyRogueShopMiracleScRsp"));
    p().interruptSave = false;
    assertEquals(0, code(buy(npc.getEntityId(), 4)));
    assertEquals(200, coins(query()));
    assertNotEquals(0, code(buy(npc.getEntityId(), 4)));
  }

  @Test
  void unchangedIdsCannotHideAChangedOriginalMerchantGraphOnDisk() throws Exception {
    var npc = merchant(); shop(npc.getEntityId());
    var changed = original(ACT); changed.addProperty("UnverifiedServerTask", true);
    Files.writeString(root.resolve(ACT), GSON.toJson(changed));
    var before = p().snapshot(); int saves = p().saved.size();
    assertNotEquals(0, code(buy(npc.getEntityId(), 4)));
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size());
  }

  @Test
  void anEquivalentNpcIdCannotHideAnUnauthoredLoadedG35Tree() throws Exception {
    var npc = merchant(); shop(npc.getEntityId());
    var group = p().getScene().getResourceGroups().get(35);
    var graph = GroupInfo.class.getDeclaredField("LevelGraph");
    graph.setAccessible(true);
    assertTrue(group.getLevelGraph().contains("RogueDLCModule_RestRoom"));
    graph.set(group, group.getLevelGraph().replace("RogueDLCModule_RestRoom", "UnverifiedShop"));
    var before = p().snapshot(); int saves = p().saved.size();
    assertNotEquals(0, code(buy(npc.getEntityId(), 4)));
    assertEquals(before, p().snapshot()); assertEquals(saves, p().saved.size());
  }

  @Test
  void preAuthenticationCannotCreateOrResumeAnyChessRun() throws Exception {
    session.setState(SessionState.WAITING_FOR_TOKEN);
    var before = p().snapshot();
    assertTrue(VersionedPacketHandler.handle(session, 5426, startBody(101, 6, 1001)));
    assertTrue(VersionedPacketHandler.handle(session, 5437, body(out -> out.writeUInt32(8, 101))));
    assertEquals(before, p().snapshot()); assertTrue(session.packets.isEmpty()); assertTrue(p().saved.isEmpty());
  }

  @Test
  void fixtureRetainsAllDlcFloorGroupsAndEverySourceBlobMatchesItsFixedCommitDigest() throws Exception {
    assertEquals(COMMIT, fixture.getAsJsonObject("source").get("commit").getAsString());
    for (var element : fixture.getAsJsonArray("files")) {
      var source = element.getAsJsonObject();
      byte[] bytes = Base64.getDecoder().decode(source.get("rawBase64").getAsString());
      assertEquals(source.get("sourceSHA256").getAsString(),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    var floor = GameData.getFloorInfo(80201, 80201001);
    assertTrue(floor.isLoaded());
    assertEquals(82, floor.getReferencedGroupIndices().size());
    for (int index : floor.getReferencedGroupIndices()) assertNotNull(floor.getGroupInfoByIndex(index));
    assertFalse(fixture.getAsJsonObject("boundary").get("originalClientUIVerified").getAsBoolean());
    assertFalse(fixture.getAsJsonObject("route").get("officialRoomToPhysicalMappingRecovered").getAsBoolean());
  }
}
