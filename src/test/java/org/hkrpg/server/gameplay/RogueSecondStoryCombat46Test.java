package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original second-tutorial placements/waves, with a synthetic owned actor and blessing pool. */
class RogueSecondStoryCombat46Test {
  static class Explorer extends UniverseCombatFragmentsTest.Explorer {
    Scene actual;
    boolean failSave;
    Runnable persistence = () -> {};
    Session session;
    @Override public emu.lunarcore.server.game.GameSession getSession() { return session; }
    @Override public boolean isOnline() { return session != null; }
    @Override public Scene getScene() { return actual; }
    @Override public int getPlaneId() { return 80001; }
    @Override public int getFloorId() { return 80001002; }
    @Override public int getEntryId() { return 8000102; }
    @Override public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id) {
      var formal = super.getAvatarById(id);
      return formal != null ? formal : getGameplay().getRogue().isInside()
          ? getUniverseManager().downloadedTrial(id) : null;
    }
    @Override public void save() {
      if (failSave) throw new IllegalStateException("fixture persistence failure");
      persistence.run();
      super.save();
    }
  }

  static class Session extends emu.lunarcore.server.game.GameSession {
    final Explorer player;
    final org.hkrpg.server.protocol.ProtocolBridge protocol = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    final Map<String, com.google.protobuf.DynamicMessage> messages = new HashMap<>();
    Session(Explorer p) { super(null); player = p; setState(emu.lunarcore.server.packet.SessionState.ACTIVE); }
    @Override public emu.lunarcore.game.player.Player getPlayer() { return player; }
    @Override public org.hkrpg.server.protocol.ProtocolBridge getProtocol() { return protocol; }
    @Override public void sendClientPacket(String name, byte[] bytes) {
      try { messages.put(name, protocol.clientSchema().parse(name, bytes)); }
      catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
  }

  final Gson gson = ResourceLoader.getGson();
  final List<Runnable> undo = new ArrayList<>();
  final Explorer player = new Explorer();
  FloorInfo floor;
  MazePlaneExcel plane;
  JsonObject records;
  final BattleService service = new BattleService(null) {
    @Override protected void grantDrops(Battle battle) {}
  };

  @BeforeEach void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-second-story-combat-4.6.json")) {
      records = JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonObject("records");
    }
    saveMaps(GameplayDefinitions.rooms, GameplayDefinitions.maps, GameplayDefinitions.areas, GameplayDefinitions.rogueMonsters,
        GameplayDefinitions.rogueMonsterGroups);
    var oldFloor = GameData.getFloorInfos().get("P80001_F80001002");
    undo.add(() -> { if (oldFloor == null) GameData.getFloorInfos().remove("P80001_F80001002");
      else GameData.getFloorInfos().put("P80001_F80001002", oldFloor); });
    saveTables();
    for (var r : records.getAsJsonArray("RogueRoom.json")) {
      var room = gson.fromJson(r, GameplayDefinitions.Room.class);
      GameplayDefinitions.rooms.put(room.RogueRoomID(), room);
    }
    var area = gson.fromJson(records.getAsJsonArray("RogueAreaConfig.json").get(0), GameplayDefinitions.Area.class);
    GameplayDefinitions.areas.put(area.RogueAreaID(), area);
    for (var r : records.getAsJsonArray("RogueMonster.json")) {
      var mob = gson.fromJson(r, GameplayDefinitions.RogueMonster.class);
      GameplayDefinitions.rogueMonsters.put(mob.RogueMonsterID(), mob);
    }
    for (var r : records.getAsJsonArray("RogueMonsterGroup.json")) {
      var group = gson.fromJson(r, GameplayDefinitions.RogueMonsterGroup.class);
      GameplayDefinitions.rogueMonsterGroups.put(group.RogueMonsterGroupID(), group);
    }
    GameplayDefinitions.maps.put(2, List.of(new GameplayDefinitions.Site(2, 1, true, List.of(2), 0),
        new GameplayDefinitions.Site(2, 2, false, List.of(3), 0),
        new GameplayDefinitions.Site(2, 3, false, List.of(), 0)));
    plane = gson.fromJson(flat(records.getAsJsonArray("MazePlane.json").get(0)), MazePlaneExcel.class);
    for (var r : records.getAsJsonArray("MapEntrance.json"))
      GameData.getMapEntranceExcelMap().add(gson.fromJson(r, MapEntranceExcel.class));
    for (var r : records.getAsJsonArray("NPCMonsterData.json"))
      GameData.getNpcMonsterExcelMap().add(gson.fromJson(flat(r), NpcMonsterExcel.class));
    for (var r : records.getAsJsonArray("StageConfig.json")) {
      var stage = gson.fromJson(flat(r), StageExcel.class); stage.onLoad(); GameData.getStageExcelMap().add(stage);
    }
    for (var r : records.getAsJsonArray("MonsterConfig.json"))
      GameData.getMonsterExcelMap().add(gson.fromJson(flat(r), MonsterExcel.class));
    for (var r : records.getAsJsonArray("MazeProp.json")) {
      var prop = gson.fromJson(flat(r), PropExcel.class); prop.onLoad(); GameData.getPropExcelMap().add(prop);
    }
    var oldBlessings = List.copyOf(GameplayDefinitions.blessings);
    undo.add(() -> { GameplayDefinitions.blessings.clear(); GameplayDefinitions.blessings.addAll(oldBlessings); });
    GameplayDefinitions.blessings.clear();
    for (int i = 0; i < 8; i++) {
      int id = 19997200 + i;
      var old = GameData.getMazeBuffExcelMap().get(id, 1);
      undo.add(() -> { GameData.getMazeBuffExcelMap().values().removeIf(b -> b.getID() == id);
        if (old != null) GameData.getMazeBuffExcelMap().add(old); });
      GameData.getMazeBuffExcelMap().add(gson.fromJson("{\"ID\":" + id + ",\"Lv\":1}", MazeBuffExcel.class));
      GameplayDefinitions.blessings.add(new GameplayDefinitions.Blessing(id, 1, 100, "Common", 1, List.of()));
    }
    floor = gson.fromJson(records.get("floor"), FloorInfo.class);
    for (int group : List.of(3, 4, 6, 7, 18, 19, 29)) {
      var source = gson.fromJson(records.get(Integer.toString(group)), GroupInfo.class);
      source.setId(group); floor.getGroups().put(group, source);
    }
    GameData.getFloorInfos().put("P80001_F80001002", floor);
    floor.onLoad();
    var run = new GameplayState.RogueRun(100, 2, Map.of(1, 201, 2, 202, 3, 203));
    run.setupTeam(player, List.of(199961)); run.enter(1, 201);
    player.getGameplay().setRogue(run);
    reloadScene();
  }

  @SafeVarargs private final void saveMaps(Map<?, ?>... maps) {
    for (var map : maps) saveMap(map);
  }
  private <K,V> void saveMap(Map<K,V> map) {
    var old = new HashMap<>(map); undo.add(() -> { map.clear(); map.putAll(old); });
  }
  private void saveTables() {
    var entries = new ArrayList<>(GameData.getMapEntranceExcelMap().values());
    var npcs = new ArrayList<>(GameData.getNpcMonsterExcelMap().values());
    var stages = new ArrayList<>(GameData.getStageExcelMap().values());
    var mobs = new ArrayList<>(GameData.getMonsterExcelMap().values());
    var props = new ArrayList<>(GameData.getPropExcelMap().values());
    undo.add(() -> {
      GameData.getMapEntranceExcelMap().values().clear(); entries.forEach(GameData.getMapEntranceExcelMap()::add);
      GameData.getNpcMonsterExcelMap().values().clear(); npcs.forEach(GameData.getNpcMonsterExcelMap()::add);
      GameData.getStageExcelMap().values().clear(); stages.forEach(GameData.getStageExcelMap()::add);
      GameData.getMonsterExcelMap().values().clear(); mobs.forEach(GameData.getMonsterExcelMap()::add);
      GameData.getPropExcelMap().values().clear(); props.forEach(GameData.getPropExcelMap()::add);
    });
  }
  private JsonObject flat(JsonElement raw) {
    var copy = raw.getAsJsonObject().deepCopy();
    for (var key : Set.copyOf(copy.keySet()))
      if (copy.get(key).isJsonObject() && copy.getAsJsonObject(key).keySet().equals(Set.of("Hash")))
        copy.addProperty(key, copy.getAsJsonObject(key).get("Hash").getAsLong());
    return copy;
  }
  @AfterEach void cleanup() { Collections.reverse(undo); undo.forEach(Runnable::run); }
  void reloadScene() {
    player.actual = new Scene(player, plane, 80001002); player.actual.setEntryId(8000102);
  }
  GameplayState.RogueRun run() { return player.getGameplay().getRogue(); }
  EntityProp prop(int group, int id) {
    return player.actual.getEntities(EntityProp.class).stream()
        .filter(p -> p.getGroupId() == group && p.getInstId() == id).findFirst().orElseThrow();
  }
  String victory(int group, int instance) {
    return run().getSiteId() + ":" + run().getRoomId() + ":80001:80001002:" + group + ":" + instance;
  }
  void savedVictory(int group, int instance) { run().getDefeatedRogueMonsters().add(victory(group, instance)); }
  void roundTrip() {
    var copy = gson.fromJson(gson.toJson(run()), GameplayState.RogueRun.class);
    player.getGameplay().setRogue(copy); reloadScene();
  }
  int select() {
    assertFalse(run().getOffers().isEmpty(), "An earned encounter must offer a blessing");
    int id = run().getOffers().getFirst();
    assertEquals(Retcode.SUCC, player.getUniverseManager().selectBlessing(run().getSelectionId(), id));
    return id;
  }

  @Test void secondStoryBossRoomSpawnsTheAuthoredLevelEightEncounter() {
    var run = run();
    run.getCleared().addAll(List.of(1, 2));
    run.enter(3, 203);
    String originalRun = run.getRunId();
    reloadScene();
    var monsters = player.actual.getEntities(EntityMonster.class);
    assertEquals(1, monsters.size(), "The boss room must contain its single authored encounter");
    var boss = monsters.getFirst();
    assertEquals(29, boss.getGroupId());
    assertEquals(200001, boss.getInstId());
    assertEquals(8003020, boss.getExcel().getId());
    assertEquals(80021011, boss.getCustomStage().getId());
    assertEquals(8, GameData.getStageExcelMap().get(80021011).getLevel());
    assertEquals(List.of(8001010, 8003020, 8001010),
        new ArrayList<>(boss.getCustomStage().getMonsterWaves().getFirst()));
    roundTrip();
    assertEquals(originalRun, run().getRunId());
    assertEquals(Set.of(1, 2), run().getCleared());
    assertEquals(8003020, player.actual.getEntities(EntityMonster.class).getFirst().getExcel().getId());
  }

  @Test void malformedBossSourceNeverInventsAnEncounterOrOverwritesOriginalContent() {
    var run = run();
    run.getCleared().addAll(List.of(1, 2)); run.enter(3, 203);
    GameplayDefinitions.rogueMonsterGroups.remove(2101);
    reloadScene(); assertTrue(player.actual.getEntities(EntityMonster.class).isEmpty());
    var fallback = gson.fromJson(records.getAsJsonArray("RogueMonsterGroup.json").asList().stream()
        .filter(e -> e.getAsJsonObject().get("RogueMonsterGroupID").getAsInt() == 2101)
        .findFirst().orElseThrow(), GameplayDefinitions.RogueMonsterGroup.class);
    GameplayDefinitions.rogueMonsterGroups.put(2101, fallback);
    GameplayDefinitions.rogueMonsterGroups.put(2007, new GameplayDefinitions.RogueMonsterGroup(2007, Map.of(999999, 1)));
    reloadScene(); assertTrue(player.actual.getEntities(EntityMonster.class).isEmpty());
    assertFalse(run.getCleared().contains(3));
    assertTrue(run.getMonsterSelections().keySet().stream().noneMatch(key -> key.startsWith("3:203:")));
  }

  void prepareBossMission() {
    saveMap(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    for (var raw : records.getAsJsonArray("missionBossChain")) {
      var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    var gameplay = player.getGameplay();
    var first = new emu.lunarcore.game.quest.QuestState(4030001);
    first.advance(1, 1, 1);
    gameplay.getMainMissions().put(4030001, first);
    gameplay.getMainMissions().put(4030002, new emu.lunarcore.game.quest.QuestState(4030002));
    var previous = new emu.lunarcore.game.quest.QuestState(403000210);
    previous.advance(1, 1, 1);
    gameplay.getSubMissions().put(403000210, previous);
    gameplay.getSubMissions().put(403000211, new emu.lunarcore.game.quest.QuestState(403000211));
    var run = run();
    run.getCleared().add(1);
    run.enter(2, 202); reloadScene(); // Capture the same source choices recorded during play.
    run.getDefeatedRogueMonsters().addAll(Set.of("2:202:80001:80001002:6:200001",
        "2:202:80001:80001002:7:200001", "2:202:80001:80001002:18:200005"));
    run.getCleared().add(2);
    run.enter(3, 203); reloadScene();
    player.getMissionManager().refresh();
  }

  @Test void pastSecondRoomReceiptsRecoverTheBossObjectiveWithoutInventingVictory() {
    prepareBossMission();
    var gameplay = player.getGameplay();
    var run = run();
    for (int id : List.of(403000211,403000212,403000214,403000217,403000218))
      assertEquals(emu.lunarcore.game.quest.QuestState.Phase.FINISHED,
          gameplay.getSubMissions().get(id).getPhase(), "Saved room progress should reconcile " + id);
    assertEquals(emu.lunarcore.game.quest.QuestState.Phase.DOING,
        gameplay.getSubMissions().get(403000219).getPhase());
    assertFalse(run.getDefeatedRogueMonsters().contains("3:203:80001:80001002:29:200001"));
  }

  @Test void missionRecoveryRequiresTheCorrectSavedRoomAndActualEncounterReceipt() {
    prepareBossMission();
    var objective = GameplayDefinitions.subs.get(403000212);
    assertTrue(RogueSecondStoryProgress.satisfied(player, objective));
    var run = run();
    String receipt = "2:202:80001:80001002:6:200001";
    run.getDefeatedRogueMonsters().remove(receipt);
    assertFalse(RogueSecondStoryProgress.satisfied(player, objective));
    run.getDefeatedRogueMonsters().add(receipt);
    run.getMonsterSelections().remove("2:202:80001:80001002:6");
    assertFalse(RogueSecondStoryProgress.satisfied(player, objective));
    objective.setParamInt2(999999);
    assertFalse(RogueSecondStoryProgress.satisfied(player, objective));
  }

  @Test void firstTutorialVictoryCannotFinishSecondTutorialBeforeBossSettlement() {
    prepareBossMission();
    var gameplay = player.getGameplay();
    gameplay.getRogueWins().put(100, 1); // The first tutorial also uses area 100.
    gameplay.getSubMissions().get(403000219).advance(1, 1, 1);
    player.getMissionManager().refresh();
    assertEquals(emu.lunarcore.game.quest.QuestState.Phase.DOING,
        gameplay.getSubMissions().get(403000220).getPhase());
  }

  @Test void secondTutorialBossVictorySettlesOnceAndPersistsItsOwnResult() {
    prepareBossMission();
    var run = run();
    var actor = player.actual.getEntities(EntityMonster.class).stream()
        .filter(m -> m.getGroupId() == 29).findFirst().orElseThrow();
    var battle = new Battle(player, player.getCurrentLineup(), List.of(actor));
    player.setBattle(battle);
    assertNotNull(service.finishBattle(player, battle.getId(), 80021011,
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance(), List.of()));
    assertTrue(run.getDefeatedRogueMonsters().contains("3:203:80001:80001002:29:200001"));
    assertTrue(run.getCleared().contains(3));
    assertEquals(GameplayState.RogueRun.Phase.FINISHED, run.getPhase());
    assertEquals(emu.lunarcore.game.quest.QuestState.Phase.FINISHED,
        player.getGameplay().getSubMissions().get(403000219).getPhase());
    assertEquals(emu.lunarcore.game.quest.QuestState.Phase.FINISHED,
        player.getGameplay().getSubMissions().get(403000220).getPhase());
    assertEquals(1, player.getGameplay().getRogueResults().size());
    String id = run.getRunId();
    roundTrip();
    assertEquals(id, run().getRunId());
    assertEquals(GameplayState.RogueRun.Phase.FINISHED, run().getPhase());
    assertTrue(player.actual.getEntities(EntityMonster.class).isEmpty());
    assertEquals(1, player.getGameplay().getRogueResults().size());
  }

  @Test void bossEncounterAndVictorySurviveH2Restarts(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    prepareBossMission();
    String runId = run().getRunId();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    for (int cycle = 0; cycle < 3; cycle++) {
      var mongo = new de.bwaldvogel.mongo.MongoServer(
          new de.bwaldvogel.mongo.backend.h2.H2Backend(directory.resolve("boss.mv").toString()));
      var address = mongo.bind();
      try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var providers = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
            new emu.lunarcore.database.codecs.IntSetCodec(), new emu.lunarcore.database.codecs.IntListCodec(),
            new emu.lunarcore.database.codecs.Int2IntMapCodec());
        var options = dev.morphia.mapping.MapperOptions.builder().storeEmpties(true)
            .storeNulls(false).codecProvider(providers).build();
        var store = dev.morphia.Morphia.createDatastore(client, "second_tutorial_boss", options);
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(emu.lunarcore.game.player.Player.class,
            emu.lunarcore.game.quest.QuestState.class, emu.lunarcore.util.Position.class);
        var document = cycle == 0 ? new emu.lunarcore.game.player.Player()
            : store.find(emu.lunarcore.game.player.Player.class).first();
        assertNotNull(document);
        if (cycle == 0) {
          var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
          uid.setAccessible(true); uid.setInt(document, 19997832);
          gameplay.set(document, player.getGameplay()); store.save(document);
        }
        gameplay.set(player, document.getGameplay());
        var savedDocument = document;
        player.persistence = () -> store.save(savedDocument);
        reloadScene();
        assertEquals(runId, run().getRunId());
        if (cycle < 2) {
          assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run().getPhase());
          var actor = player.actual.getEntities(EntityMonster.class).stream()
              .filter(m -> m.getGroupId() == 29).findFirst().orElseThrow();
          if (cycle == 1) {
            var battle = new Battle(player, player.getCurrentLineup(), List.of(actor));
            player.setBattle(battle);
            assertNotNull(service.finishBattle(player, battle.getId(), 80021011,
                BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance(), List.of()));
          }
        } else {
          assertEquals(GameplayState.RogueRun.Phase.FINISHED, run().getPhase());
          assertTrue(player.actual.getEntities(EntityMonster.class).isEmpty());
          assertTrue(run().getDefeatedRogueMonsters().contains("3:203:80001:80001002:29:200001"));
          assertEquals(emu.lunarcore.game.quest.QuestState.Phase.FINISHED,
              player.getGameplay().getSubMissions().get(403000220).getPhase());
          assertEquals(1, player.getGameplay().getRogueResults().size());
        }
        var loaded = store.find(emu.lunarcore.game.player.Player.class).first();
        assertEquals(gson.toJsonTree(player.getGameplay()), gson.toJsonTree(loaded.getGameplay()));
      } finally { player.persistence = () -> {}; mongo.shutdownNow(); }
    }
  }

  @Test void actualFirstEncounterOffersBeforeRoomClearAndOpensOnlyItsBarrier() {
    assertEquals(3, player.actual.getEntities(EntityMonster.class).size());
    var monster = player.actual.getEntities(EntityMonster.class).stream().filter(m -> m.getGroupId() == 3).findFirst().orElseThrow();
    assertEquals(200002, monster.getInstId());
    var battle = new Battle(player, player.getCurrentLineup(), List.of(monster)); player.setBattle(battle);
    assertEquals(80020011, battle.getStage().getId());
    var reports = List.of(new MissionBattleKills.Monster(5011, 8011020, 80020011, 1),
        new MissionBattleKills.Monster(5012, 8011010, 80020011, 2),
        new MissionBattleKills.Monster(5013, 8011020, 80020011, 3));
    assertNotNull(service.finishBattle(player, battle.getId(), 80020011,
        BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance(), reports));
    assertTrue(run().getDefeatedRogueMonsters().contains(victory(3, 200002)));
    assertFalse(run().getOffers().isEmpty(), "Do not wait for the two enemies behind the barrier");
    assertFalse(run().getCleared().contains(1));
    assertEquals(PropState.Closed, prop(19, 300004).getState());
    int batch = run().getSelectionId(); int buff = select();
    assertEquals(PropState.Open, prop(3, 300001).getState());
    assertEquals(PropState.Open, prop(19, 300004).getState());
    assertEquals(PropState.Closed, prop(19, 300005).getState(), "Downloader gate must not open on the first blessing");
    assertEquals(Retcode.REQ_PARA_INVALID, player.getUniverseManager().selectBlessing(batch, buff));
    roundTrip();
    assertEquals(PropState.Open, prop(19, 300004).getState());
    assertEquals(1, run().getBlessings().size());
  }

  @Test void normalizedFloorKeepsAllAuthoredSecondTutorialBarrierLinks() {
    for (var pair : List.of(new int[]{19,300004}, new int[]{19,300005}, new int[]{18,300008}, new int[]{18,300009})) {
      var info = floor.getGroups().get(pair[0]).getPropList().stream().filter(p -> p.getID() == pair[1]).findFirst().orElseThrow();
      assertNotNull(info.getValueSource(), "Floor normalization must preserve the authored marker link");
    }
  }

  @Test void deployedOldRunRecoversUnclaimedVictoryWithoutResettingRunOrReplayingBattle() {
    String id = run().getRunId(); var roster = List.copyOf(run().getRoster());
    savedVictory(3, 200002); roundTrip();
    player.getUniverseManager().restoreFirstStoryBlessings();
    assertFalse(run().getOffers().isEmpty());
    int selection = run().getSelectionId(); var offers = List.copyOf(run().getOffers());
    player.getUniverseManager().restoreFirstStoryBlessings();
    assertEquals(selection, run().getSelectionId()); assertEquals(offers, run().getOffers());
    roundTrip(); player.getUniverseManager().restoreFirstStoryBlessings();
    assertEquals(selection, run().getSelectionId()); select(); roundTrip();
    assertEquals(id, run().getRunId()); assertEquals(roster, run().getRoster());
    assertEquals(PropState.Open, prop(19, 300004).getState());
    player.getUniverseManager().restoreFirstStoryBlessings();
    assertTrue(run().getOffers().isEmpty()); assertEquals(1, run().getBlessings().size());
  }

  @Test void nativePendingActionIsPublishedAndSelectionAcknowledged() throws Exception {
    savedVictory(3, 200002); roundTrip();
    player.session = new Session(player);
    player.getUniverseManager().restoreFirstStoryBlessings();
    var pending = player.session.messages.get("SyncRogueCommonPendingActionScNotify");
    assertNotNull(pending);
    var action = (com.google.protobuf.DynamicMessage) pending.getField(pending.getDescriptorForType().findFieldByName("action"));
    assertEquals(run().getSelectionId(), action.getField(action.getDescriptorForType().findFieldByName("queue_position")));
    var descriptor = player.session.protocol.clientSchema().message("OFEDNNJIJFL");
    var request = com.google.protobuf.DynamicMessage.newBuilder(descriptor);
    request.setField(descriptor.findFieldByName("MPLGEEGOAMC"), run().getSelectionId());
    var field = descriptor.findFieldByName("DLKIKBIDJDB");
    var choice = com.google.protobuf.DynamicMessage.newBuilder(field.getMessageType());
    choice.setField(field.getMessageType().findFieldByName("BAOFIGFFMLN"), run().getOffers().getFirst());
    request.setField(field, choice.build());
    assertTrue(org.hkrpg.server.protocol.Universe46.handle(player.session,
        org.hkrpg.server.protocol.Universe46.PENDING_ACTION_COMMAND, request.build().toByteArray()));
    var reply = player.session.messages.get("HandleRogueCommonPendingActionScRsp");
    assertNotNull(reply);
    assertEquals(0, reply.getField(reply.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(run().getSelectionId(), reply.getField(reply.getDescriptorForType().findFieldByName("queue_position")));
    assertEquals(PropState.Open, prop(19, 300004).getState());
  }

  @Test void oldPlayerDocumentAndPendingChoiceSurviveTwoH2BackendRestarts(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    savedVictory(3, 200002); roundTrip();
    String runId = run().getRunId(); int selection = 0; List<Integer> candidates = List.of();
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay"); gameplay.setAccessible(true);
    for (int cycle = 0; cycle < 3; cycle++) {
      var mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(directory.resolve("player.mv").toString()));
      var address = mongo.bind();
      try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var providers = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
            new emu.lunarcore.database.codecs.IntSetCodec(), new emu.lunarcore.database.codecs.IntListCodec(),
            new emu.lunarcore.database.codecs.Int2IntMapCodec());
        var options = dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(providers).build();
        var store = dev.morphia.Morphia.createDatastore(client, "second_tutorial", options);
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class, emu.lunarcore.util.Position.class);
        var document = cycle == 0 ? new emu.lunarcore.game.player.Player() : store.find(emu.lunarcore.game.player.Player.class).first();
        assertNotNull(document);
        if (cycle == 0) {
          var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid"); uid.setAccessible(true); uid.setInt(document, 19997831);
          gameplay.set(document, player.getGameplay()); store.save(document);
          // Explicit isolated old-schema fixture: production records are never edited by this test.
          client.getDatabase("second_tutorial").getCollection("players").updateOne(new org.bson.Document("_id",19997831),
              new org.bson.Document("$unset",new org.bson.Document("gameplay.rogue.secondStoryBlessingReceipts", "")));
          document = store.find(emu.lunarcore.game.player.Player.class).first();
        }
        gameplay.set(player, document.getGameplay()); reloadScene();
        var savedDocument = document;
        player.persistence = () -> store.save(savedDocument);
        assertEquals(runId, run().getRunId());
        assertEquals(List.of(199961), run().getRoster());
        if (cycle > 0) assertEquals(selection, run().getSelectionId());
        if (cycle == 1) assertEquals(candidates, run().getOffers());
        player.getUniverseManager().restoreFirstStoryBlessings();
        if (cycle == 0) { assertFalse(run().getOffers().isEmpty()); selection = run().getSelectionId(); candidates = List.copyOf(run().getOffers()); }
        if (cycle == 1) select();
        if (cycle > 0) {
          assertTrue(run().getOffers().isEmpty()); assertEquals(1, run().getBlessings().size());
          assertEquals(PropState.Open, prop(19, 300004).getState());
          assertEquals(-1, run().getSecondStoryBlessingReceipts().get(3));
        }
        var loaded = store.find(emu.lunarcore.game.player.Player.class).first();
        assertEquals(gson.toJsonTree(player.getGameplay()), gson.toJsonTree(loaded.getGameplay()));
      } finally { player.persistence = () -> {}; mongo.shutdownNow(); }
    }
  }

  @Test void savedGenerationAndSelectionFailuresCannotLoseOffersOrOpenBarriers() {
    savedVictory(3, 200002); roundTrip();
    player.failSave = true;
    assertThrows(IllegalStateException.class, () -> player.getUniverseManager().restoreFirstStoryBlessings());
    assertTrue(run().getOffers().isEmpty());
    assertEquals(PropState.Closed, prop(19, 300004).getState());
    player.failSave = false; player.getUniverseManager().restoreFirstStoryBlessings();
    var choices = List.copyOf(run().getOffers()); int selection = run().getSelectionId();
    player.failSave = true;
    assertThrows(IllegalStateException.class, () -> player.getUniverseManager().selectBlessing(selection, choices.getFirst()));
    assertEquals(choices, run().getOffers()); assertTrue(run().getBlessings().isEmpty());
    assertEquals(PropState.Closed, prop(19, 300004).getState());
    player.failSave = false; select();
    assertEquals(PropState.Open, prop(19, 300004).getState());
  }

  @Test void secondRoomHasSeparateEncounterReceiptsAndBarrierProgress() {
    savedVictory(3, 200002); roundTrip(); player.getUniverseManager().restoreFirstStoryBlessings(); select();
    run().enter(2, 202); reloadScene();
    assertEquals(PropState.Closed, prop(18, 300008).getState());
    assertEquals(PropState.Closed, prop(18, 300009).getState());
    savedVictory(6, 200001); roundTrip(); player.getUniverseManager().restoreFirstStoryBlessings(); select();
    assertEquals(PropState.Open, prop(18, 300008).getState());
    assertEquals(PropState.Closed, prop(18, 300009).getState());
    savedVictory(7, 200001); roundTrip(); player.getUniverseManager().restoreFirstStoryBlessings(); select();
    roundTrip();
    assertEquals(PropState.Open, prop(18, 300008).getState());
    assertEquals(PropState.Open, prop(18, 300009).getState());
    assertEquals(3, run().getBlessings().size());
  }

  @Test void threeSavedVictoriesOfferOneMenuEachAndNoExtraRoomBlessing() {
    savedVictory(3, 200002); savedVictory(4, 200001); savedVictory(19, 200005); roundTrip();
    player.getUniverseManager().restoreFirstStoryBlessings();
    for (int i = 0; i < 3; i++) { select(); roundTrip(); player.getUniverseManager().restoreFirstStoryBlessings(); }
    assertTrue(run().getOffers().isEmpty()); assertEquals(3, run().getBlessings().size());
    assertTrue(run().getCleared().contains(1));
    assertTrue(player.getUniverseManager().offerBlessings().isEmpty(), "No duplicate room-end reward");
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run().getPhase());
    assertEquals(PropState.Closed, prop(19, 300005).getState(), "A download-gated barrier needs its own completion");
  }

  @ParameterizedTest @ValueSource(strings = {"wrong-instance", "wrong-room", "wrong-site", "missing-trigger"})
  void unrelatedOrUnverifiableVictoriesDoNotCreateBlessings(String variant) {
    String key = victory(3, variant.equals("wrong-instance") ? 200001 : 200002);
    if (variant.equals("wrong-room")) key = key.replace(":201:", ":202:");
    if (variant.equals("wrong-site")) key = "2" + key.substring(1);
    if (variant.equals("missing-trigger")) floor.getGroups().get(3).getPropList().clear();
    run().getDefeatedRogueMonsters().add(key); roundTrip();
    player.getUniverseManager().restoreFirstStoryBlessings();
    assertTrue(run().getOffers().isEmpty()); assertTrue(run().getBlessings().isEmpty());
    assertEquals(PropState.Closed, prop(19, 300004).getState());
  }
}
