package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original resource rows, explicit LunarCore-derived project thresholds, no official UI claim. */
class RogueResonance46Test {
  @TempDir Path root;
  final Gson gson = new Gson();
  JsonObject proof;
  Object oldAeons;
  Object oldEvents;
  final List<GameplayDefinitions.Blessing> oldBuffs = new ArrayList<>();
  final Map<Integer, GameplayDefinitions.RogueBuffGroup> oldGroups = new HashMap<>();
  final Map<Integer, MazeBuffExcel> oldMaze = new HashMap<>();
  final Set<Integer> installedMaze = new HashSet<>();
  GameplayDefinitions.Room oldRoom;

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-resonance-4.6.json")) {
      assertNotNull(in);
      proof =
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    var field = RogueAeonCatalog.class.getDeclaredField("catalogue");
    field.setAccessible(true);
    oldAeons = field.get(null);
    try {
      var f = Class.forName("org.hkrpg.server.gameplay.RogueResonance").getDeclaredField("events");
      f.setAccessible(true);
      oldEvents = f.get(null);
    } catch (ClassNotFoundException ignored) {
    }
    oldBuffs.addAll(GameplayDefinitions.blessings);
    oldRoom = GameplayDefinitions.rooms.get(19997301);
    oldGroups.putAll(GameplayDefinitions.rogueBuffGroups);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.rogueBuffGroups.clear();
    for (var raw : proof.getAsJsonArray("groups")) {
      var row = gson.fromJson(raw, GameplayDefinitions.RogueBuffGroup.class);
      GameplayDefinitions.rogueBuffGroups.put(row.GMLOGNJAIGI(), row);
    }
    for (String table : List.of("buffs", "normalBuffs"))
      for (var raw : proof.getAsJsonArray(table)) {
        var row = gson.fromJson(raw, GameplayDefinitions.Blessing.class);
        GameplayDefinitions.blessings.add(row);
        if (installedMaze.add(row.MazeBuffID()))
          oldMaze.put(row.MazeBuffID(), GameData.getMazeBuffExcelMap().get(row.MazeBuffID(), 1));
        GameData.getMazeBuffExcelMap()
            .add(gson.fromJson("{\"ID\":" + row.MazeBuffID() + ",\"Lv\":1}", MazeBuffExcel.class));
      }
    var dir = Files.createDirectories(root.resolve("ExcelOutput"));
    for (var entry :
        Map.of(
                "RogueAeon",
                "aeons",
                "BattleEventData",
                "events",
                "BattleEventConfig",
                "eventConfigs",
                "BattleEventSkillConfig",
                "skills")
            .entrySet())
      Files.writeString(
          dir.resolve(entry.getKey() + ".json"), proof.get(entry.getValue()).toString());
    RogueAeonCatalog.load(root);
  }

  @AfterEach
  void cleanup() throws Exception {
    var f = RogueAeonCatalog.class.getDeclaredField("catalogue");
    f.setAccessible(true);
    f.set(null, oldAeons);
    if (oldEvents != null) {
      var e = Class.forName("org.hkrpg.server.gameplay.RogueResonance").getDeclaredField("events");
      e.setAccessible(true);
      e.set(null, oldEvents);
    }
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.addAll(oldBuffs);
    GameplayDefinitions.rogueBuffGroups.clear();
    GameplayDefinitions.rogueBuffGroups.putAll(oldGroups);
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(v -> installedMaze.contains(v.getID()) && v.getLv() == 1);
    oldMaze.values().stream().filter(Objects::nonNull).forEach(GameData.getMazeBuffExcelMap()::add);
    if (oldRoom == null) GameplayDefinitions.rooms.remove(19997301);
    else GameplayDefinitions.rooms.put(19997301, oldRoom);
  }

  List<Integer> normals(int path) {
    int type = RogueAeonCatalog.find(path).orElseThrow().buffType();
    return GameplayDefinitions.blessings.stream()
        .filter(
            r -> r.RogueBuffType() == type && r.AeonID() == 0 && r.BattleEventBuffType() == null)
        .map(GameplayDefinitions.Blessing::MazeBuffID)
        .distinct()
        .sorted()
        .toList();
  }

  int basic(int path) {
    return RogueTalentEffects.group(RogueAeonCatalog.find(path).orElseThrow().basicGroup())
        .orElseThrow()
        .iterator()
        .next();
  }

  List<Integer> enhancements(int path) {
    return RogueTalentEffects.group(RogueAeonCatalog.find(path).orElseThrow().enhanceGroup())
        .orElseThrow()
        .stream()
        .sorted()
        .toList();
  }

  Player player(int path) {
    var p =
        new Player() {
          public void save() {}
        };
    p.getGameplay().setRogue(new GameplayState.RogueRun(100, 1, Map.of(1, 2), null, path));
    return p;
  }

  void normalMenu(GameplayState.RogueRun run, List<Integer> ids, int choices) {
    var levels = new TreeMap<Integer, Integer>();
    var weights = new TreeMap<Integer, Integer>();
    ids.forEach(
        id -> {
          levels.put(id, 1);
          weights.put(id, 1);
        });
    run.offer(ids, ids, List.of(), levels, choices, weights);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
  void thirdPathBlessingEarnsOnlyOriginalBasicResonance(int path) {
    var p = player(path);
    var run = p.getGameplay().getRogue();
    var ids = normals(path);
    run.getBlessings().put(ids.get(0), 1);
    run.getBlessings().put(ids.get(1), 1);
    normalMenu(run, List.of(ids.get(2)), 1);
    int batch = run.getSelectionId();
    assertEquals(Retcode.SUCC, p.getUniverseManager().selectBlessing(batch, ids.get(2)));
    assertEquals(
        List.of(basic(path)),
        run.getOffers(),
        "Third earned normal blessing must open resonance menu");
    assertEquals(batch + 1, run.getSelectionId());
    assertEquals(
        Retcode.REQ_PARA_INVALID, p.getUniverseManager().selectBlessing(batch, basic(path)));
    assertTrue(p.getUniverseManager().blessingRerollCost().isEmpty());
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(run.getSelectionId(), basic(path)));
    assertFalse(run.pending());
    assertEquals(1, run.getBlessings().get(basic(path)));
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 6, 10, 14})
  void projectThresholdsOfferOnlyStillUnownedOriginalEnhancements(int threshold) {
    var p = player(1);
    var run = p.getGameplay().getRogue();
    var ids = normals(1);
    var enh = enhancements(1);
    int acquired = List.of(3, 6, 10, 14).indexOf(threshold);
    for (int i = 0; i < threshold - 1; i++) run.getBlessings().put(ids.get(i), 1);
    if (acquired > 0) run.getBlessings().put(basic(1), 1);
    for (int i = 0; i < acquired - 1; i++) run.getBlessings().put(enh.get(i), 1);
    normalMenu(run, List.of(ids.get(threshold - 1)), 1);
    assertEquals(
        Retcode.SUCC,
        p.getUniverseManager().selectBlessing(run.getSelectionId(), ids.get(threshold - 1)));
    assertEquals(acquired == 0 ? List.of(basic(1)) : enh.subList(acquired - 1, 3), run.getOffers());
    assertEquals(1, run.offerChoiceRemaining());
    assertEquals(100, run.getCoins());
  }

  @Test
  void resonanceWaitsUntilTheLastDoubleChoice() {
    var p = player(1);
    var run = p.getGameplay().getRogue();
    var ids = normals(1);
    for (int i = 0; i < 2; i++) run.getBlessings().put(ids.get(i), 1);
    normalMenu(run, List.of(ids.get(2), ids.get(3)), 2);
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(run.getSelectionId(), ids.get(2)));
    assertEquals(List.of(ids.get(3)), run.getOffers());
    assertEquals(1, run.offerChoiceRemaining());
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(run.getSelectionId(), ids.get(3)));
    assertEquals(List.of(basic(1)), run.getOffers());
  }

  @Test
  void crossPathBlessingsDoNotEarnTheSelectedResonance() {
    var p = player(1);
    var run = p.getGameplay().getRogue();
    var ids = normals(2);
    ids.subList(0, 13).forEach(id -> run.getBlessings().put(id, 1));
    normalMenu(run, List.of(ids.get(13)), 1);
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(run.getSelectionId(), ids.get(13)));
    assertFalse(run.pending());
    assertFalse(run.getBlessings().containsKey(basic(1)));
  }

  @Test
  void frozenResonanceAndPendingMenuSurviveResourceLossAndJsonRoundTrip() {
    var p = player(7);
    var run = p.getGameplay().getRogue();
    var ids = normals(7);
    int basic = basic(7);
    run.getBlessings().put(ids.get(0), 1);
    run.getBlessings().put(ids.get(1), 1);
    normalMenu(run, List.of(ids.get(2)), 1);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.rogueBuffGroups.clear();
    var restored = gson.fromJson(gson.toJson(run), GameplayState.RogueRun.class);
    p.getGameplay().setRogue(restored);
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(restored.getSelectionId(), ids.get(2)));
    assertEquals(List.of(basic), restored.getOffers());
    var again = gson.fromJson(gson.toJson(restored), GameplayState.RogueRun.class);
    p.getGameplay().setRogue(again);
    assertEquals(
        Retcode.SUCC, p.getUniverseManager().selectBlessing(again.getSelectionId(), basic));
    assertFalse(again.pending());
    assertEquals(1, again.getBlessings().get(basic));
  }

  emu.lunarcore.game.battle.Battle battle(Player p) {
    var run = p.getGameplay().getRogue();
    run.enter(1, 19997301);
    GameplayDefinitions.rooms.put(19997301, new GameplayDefinitions.Room(19997301, 1, 777, 10));
    var stage =
        gson.fromJson(
            "{\"StageID\":19997302,\"StageType\":\"Event\"}",
            emu.lunarcore.data.excel.StageExcel.class);
    return new emu.lunarcore.game.battle.Battle(
        p, new emu.lunarcore.game.player.lineup.PlayerLineup(p, 0), stage, false);
  }

  Player battlePlayer(int path, int energy) {
    var p =
        new Player() {
          public void save() {}

          public int getEntryId() {
            return 777;
          }

          final emu.lunarcore.game.player.lineup.LineupManager lineups =
              new emu.lunarcore.game.player.lineup.LineupManager(this);

          public emu.lunarcore.game.player.lineup.LineupManager getLineupManager() {
            return lineups;
          }

          public boolean removeFoodBuffs(int type) {
            return false;
          }
        };
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, 19997301), null, path);
    run.getBlessings().put(basic(path), 1);
    var raw = gson.toJsonTree(run).getAsJsonObject();
    raw.addProperty("resonanceSp", energy);
    p.getGameplay().setRogue(gson.fromJson(raw, GameplayState.RogueRun.class));
    return p;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
  void ownedResonanceAttachesExactlyTheOriginalClassicEventWithReservedEnergy(int path) {
    var p = battlePlayer(path, 2500);
    var created = battle(p);
    var expected =
        proof.getAsJsonArray("events").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(
                r ->
                    r.get("Config")
                        .getAsString()
                        .endsWith(
                            "RogueBattleevent"
                                + RogueAeonCatalog.find(path).orElseThrow().buffType()
                                + "_Config.json"))
            .findFirst()
            .orElseThrow()
            .get("BattleEventID")
            .getAsInt();
    var proto = created.toProto();
    assertEquals(1, proto.getEventBattleInfoList().length());
    assertEquals(expected, proto.getEventBattleInfoList().get(0).getBattleEventId());
    assertEquals(2500, proto.getEventBattleInfoList().get(0).getStatus().getSpBar().getCurSp());
    assertEquals(10000, proto.getEventBattleInfoList().get(0).getStatus().getSpBar().getMaxSp());
    assertFalse(created.getBattleEvents().contains(96121));
    assertFalse(created.getBattleEvents().contains(96123));
  }

  @Test
  void unearnedResonanceDoesNotAttachAnEvent() {
    var p = battlePlayer(1, 2500);
    p.getGameplay().getRogue().getBlessings().clear();
    assertEquals(0, battle(p).toProto().getEventBattleInfoList().length());
  }

  @ParameterizedTest
  @ValueSource(strings = {"merchant", "firewax"})
  void ordinaryMerchantAndImmediateMiracleCannotBypassResonanceEarning(String source)
      throws Exception {
    var p = player(1);
    int id = basic(1);
    if (source.equals("merchant")) {
      var method = RogueBuffShop.class.getDeclaredMethod("resource", Player.class, int.class);
      method.setAccessible(true);
      assertNull(method.invoke(null, p, id));
    } else {
      var method =
          RogueMiracles.class.getDeclaredMethod("immediateBlessingPool", Player.class, int.class);
      method.setAccessible(true);
      var pool =
          (List<?>) method.invoke(null, p, RogueAeonCatalog.find(1).orElseThrow().buffType());
      assertFalse(pool.contains(id));
      assertTrue(enhancements(1).stream().noneMatch(pool::contains));
    }
  }

  static class Service extends emu.lunarcore.game.battle.BattleService {
    int grants;

    Service() {
      super(null);
    }

    protected void grantDrops(emu.lunarcore.game.battle.Battle battle) {
      grants++;
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"win", "lose", "quit", "zero", "full", "fraction"})
  void acceptedActualBattleOutcomeStoresEnergyOnceAndRestoresItForNextBattle(String mode) {
    var p = battlePlayer(1, 2500);
    var first = battle(p);
    p.setBattle(first);
    var service = new Service();
    int current =
        mode.equals("zero") ? 0 : mode.equals("full") ? 100 : mode.equals("fraction") ? 1 : 25;
    int max = mode.equals("fraction") ? 3 : 100;
    int normalized = (int) Math.round((double) current / max * 10000);
    var status =
        mode.equals("lose")
            ? emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_LOSE
            : mode.equals("quit")
                ? emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_QUIT
                : emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN;
    var row = new RogueResonance.Energy(41001, current, max);
    assertNotNull(
        service.finishBattle(
            p,
            first.getId(),
            first.getStage().getId(),
            status,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance(),
            List.of(),
            List.of(row)));
    assertEquals(normalized, p.getGameplay().getRogue().getResonanceSp());
    assertNull(
        service.finishBattle(
            p,
            first.getId(),
            first.getStage().getId(),
            status,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance(),
            List.of(),
            List.of(new RogueResonance.Energy(41001, 100, 100))));
    assertEquals(
        normalized,
        battle(p).toProto().getEventBattleInfoList().get(0).getStatus().getSpBar().getCurSp());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foreign",
        "duplicate",
        "negative",
        "over-max",
        "zero-max",
        "wrong-battle",
        "wrong-stage",
        "changed-run",
        "changed-site",
        "changed-plan",
        "removed-event"
      })
  void invalidEventReportCannotConsumeTheBattleOrChangeEnergy(String mode) {
    var p = battlePlayer(1, 2500);
    var first = battle(p);
    p.setBattle(first);
    var service = new Service();
    var rows = new ArrayList<>(List.of(new RogueResonance.Energy(41001, 25, 100)));
    if (mode.equals("foreign")) rows.set(0, new RogueResonance.Energy(96121, 25, 100));
    if (mode.equals("duplicate")) rows.add(rows.get(0));
    if (mode.equals("negative")) rows.set(0, new RogueResonance.Energy(41001, -2, 100));
    if (mode.equals("over-max")) rows.set(0, new RogueResonance.Energy(41001, 101, 100));
    if (mode.equals("zero-max")) rows.set(0, new RogueResonance.Energy(41001, 0, 0));
    var original = p.getGameplay().getRogue();
    if (mode.equals("changed-run"))
      p.getGameplay().setRogue(gson.fromJson(gson.toJson(original), GameplayState.RogueRun.class));
    if (mode.equals("changed-site")) original.enter(2, 19997301);
    if (mode.equals("changed-plan")) {
      var raw = gson.toJsonTree(original).getAsJsonObject();
      raw.getAsJsonObject("resonance").addProperty("policy", "unknown");
      p.getGameplay().setRogue(gson.fromJson(raw, GameplayState.RogueRun.class));
    }
    if (mode.equals("removed-event")) first.getBattleEvents().clear();
    assertNull(
        service.finishBattle(
            p,
            first.getId() + (mode.equals("wrong-battle") ? 1 : 0),
            first.getStage().getId() + (mode.equals("wrong-stage") ? 1 : 0),
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance(),
            List.of(),
            rows));
    assertSame(first, p.getBattle());
    assertEquals(2500, original.getResonanceSp());
    assertEquals(0, service.grants);
    assertTrue(first.beginSettlement(), "Invalid report must not consume the settlement guard");
  }

  @Test
  void absentEnergyReportKeepsTheReservedValue() {
    var p = battlePlayer(1, 2500);
    var first = battle(p);
    p.setBattle(first);
    var service = new Service();
    assertNotNull(
        service.finishBattle(
            p,
            first.getId(),
            first.getStage().getId(),
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_QUIT,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance(),
            List.of(),
            List.of(new RogueResonance.Energy(41001, -1, -1))));
    assertEquals(2500, p.getGameplay().getRogue().getResonanceSp());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 7})
  void actualPlayerMorphiaReadsKeepFrozenResonanceMenuAndReservedEnergy(int path) throws Exception {
    var p = battlePlayer(path, 2500);
    var run = p.getGameplay().getRogue();
    var ids = normals(path);
    for (int i = 0; i < 6; i++) run.getBlessings().put(ids.get(i), 1);
    assertTrue(RogueResonance.queue(run));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-resonance");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123459001);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, p.getGameplay());
      store.save(document);
      for (int read = 0; read < 2; read++) {
        var restored =
            Objects.requireNonNull(store.find(Player.class).first()).getGameplay().getRogue();
        assertEquals(run.getRunId(), restored.getRunId());
        assertEquals(2500, restored.getResonanceSp());
        assertEquals(run.getOffers(), restored.getOffers());
        assertTrue(restored.getResonance().earned(restored));
        assertEquals(RogueResonance.POLICY, restored.getResonance().getPolicy());
        assertEquals(9, restored.getResonance().getSourceRows().size());
      }
    } finally {
      mongo.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "duplicate",
        "wrong-config",
        "wrong-skill",
        "unreserved",
        "wrong-cost",
        "wrong-team",
        "partial"
      })
  void eventPlannerDisablesMissingResourcesAndRejectsMalformedPresentClassicRows(String mode)
      throws Exception {
    var file = root.resolve("ExcelOutput/BattleEventData.json");
    if (mode.equals("missing")) {
      Files.delete(file);
      RogueResonance.load(root);
      assertNull(RogueResonance.freeze(1));
      return;
    }
    var rows = proof.getAsJsonArray("events").deepCopy();
    var first = rows.get(0).getAsJsonObject();
    if (mode.equals("duplicate")) rows.add(first.deepCopy());
    if (mode.equals("wrong-config")) first.addProperty("Config", "Config/Unverified.json");
    if (mode.equals("wrong-skill"))
      first.getAsJsonArray("SkillIDList").set(0, new JsonPrimitive(4100102));
    if (mode.equals("unreserved")) first.addProperty("IsSPReserved", false);
    if (mode.equals("partial")) rows.remove(0);
    Files.writeString(file, rows.toString());
    if (mode.equals("wrong-cost")) {
      var skills = proof.getAsJsonArray("skills").deepCopy();
      skills.get(0).getAsJsonObject().getAsJsonObject("SPNeed").addProperty("Value", 99);
      Files.writeString(root.resolve("ExcelOutput/BattleEventSkillConfig.json"), skills.toString());
    }
    if (mode.equals("wrong-team")) {
      var events = proof.getAsJsonArray("eventConfigs").deepCopy();
      events.get(0).getAsJsonObject().addProperty("Team", "TeamPlayer");
      Files.writeString(root.resolve("ExcelOutput/BattleEventConfig.json"), events.toString());
    }
    assertThrows(IllegalStateException.class, () -> RogueResonance.load(root));
    assertNull(RogueResonance.freeze(1), "Failed load must clear earlier catalogue");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown-policy",
        "foreign-selected",
        "duplicate-enhancement",
        "overlap-normal",
        "invalid-sp"
      })
  void malformedSavedPlanCannotOfferOrAttachResonance(String mode) {
    var p = battlePlayer(1, 2500);
    var raw = gson.toJsonTree(p.getGameplay().getRogue()).getAsJsonObject();
    var plan = raw.getAsJsonObject("resonance");
    if (mode.equals("unknown-policy")) plan.addProperty("policy", "unknown");
    if (mode.equals("foreign-selected")) raw.addProperty("selectedAeon", 2);
    if (mode.equals("duplicate-enhancement"))
      plan.getAsJsonArray("enhancements").set(1, plan.getAsJsonArray("enhancements").get(0));
    if (mode.equals("overlap-normal")) plan.getAsJsonArray("normals").add(plan.get("basic"));
    if (mode.equals("invalid-sp")) raw.addProperty("resonanceSp", 10001);
    var restored = gson.fromJson(raw, GameplayState.RogueRun.class);
    p.getGameplay().setRogue(restored);
    if (!mode.equals("invalid-sp")) assertFalse(RogueResonance.queue(restored));
    assertEquals(0, battle(p).toProto().getEventBattleInfoList().length());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foreign-buff-type",
        "foreign-event",
        "foreign-basic",
        "foreign-enhancement",
        "source-path-swapped",
        "source-event-swapped",
        "source-active-swapped",
        "source-passive-cost",
        "source-buff-aeon",
        "source-buff-kind",
        "source-buff-level",
        "source-null",
        "source-invalid-json",
        "source-oversized",
        "source-duplicate-buff"
      })
  void restoredPlanMustMatchItsOwnFrozenSourceBindings(String mode) {
    var p = battlePlayer(1, 2500);
    var run = p.getGameplay().getRogue();
    normals(1).stream().limit(6).forEach(id -> run.getBlessings().put(id, 1));
    var raw = gson.toJsonTree(run).getAsJsonObject();
    var plan = raw.getAsJsonObject("resonance");
    var source = plan.getAsJsonArray("sourceRows");
    if (mode.equals("foreign-buff-type")) plan.addProperty("buffType", 121);
    if (mode.equals("foreign-event")) plan.addProperty("event", 41009);
    if (mode.equals("foreign-basic")) plan.addProperty("basic", 612620);
    if (mode.equals("foreign-enhancement"))
      plan.getAsJsonArray("enhancements").set(0, new JsonPrimitive(612121));
    if (mode.equals("source-path-swapped"))
      source.set(0, new JsonPrimitive(proof.getAsJsonArray("aeons").get(1).toString()));
    if (mode.equals("source-event-swapped"))
      source.set(2, new JsonPrimitive(proof.getAsJsonArray("eventConfigs").get(1).toString()));
    if (mode.equals("source-active-swapped"))
      source.set(3, new JsonPrimitive(proof.getAsJsonArray("skills").get(2).toString()));
    if (mode.equals("source-passive-cost")) {
      var row = JsonParser.parseString(source.get(4).getAsString()).getAsJsonObject();
      row.add("SPNeed", JsonParser.parseString("{\"Value\":100}"));
      source.set(4, new JsonPrimitive(row.toString()));
    }
    if (mode.startsWith("source-buff-")) {
      var row = JsonParser.parseString(source.get(5).getAsString()).getAsJsonObject();
      if (mode.equals("source-buff-aeon")) row.addProperty("AeonID", 9);
      if (mode.equals("source-buff-kind")) row.addProperty("BattleEventBuffType", "Ordinary");
      if (mode.equals("source-buff-level")) row.addProperty("MazeBuffLevel", 2);
      source.set(5, new JsonPrimitive(row.toString()));
    }
    if (mode.equals("source-null")) source.set(5, JsonNull.INSTANCE);
    if (mode.equals("source-invalid-json")) source.set(5, new JsonPrimitive("{"));
    if (mode.equals("source-oversized")) source.set(5, new JsonPrimitive(" ".repeat(16385)));
    if (mode.equals("source-duplicate-buff")) source.set(8, source.get(5));
    var restored = gson.fromJson(raw, GameplayState.RogueRun.class);
    p.getGameplay().setRogue(restored);
    assertFalse(
        restored.getResonance().valid(1), "Frozen identities must agree with their source rows");
    var before = gson.toJson(restored);
    assertFalse(RogueResonance.queue(restored));
    assertEquals(before, gson.toJson(restored));
    assertEquals(0, battle(p).toProto().getEventBattleInfoList().length());
    assertEquals(2500, restored.getResonanceSp());
  }
}
