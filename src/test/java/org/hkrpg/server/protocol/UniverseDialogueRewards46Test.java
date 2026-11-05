package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original 40398 choices: fragments, one random blessing, and no reward. */
class UniverseDialogueRewards46Test {
  @TempDir Path root;
  final UniverseDialogueEntry46Test fixture = new UniverseDialogueEntry46Test();
  final List<MazeBuffExcel> oldBuffs = new ArrayList<>();
  String oldData;

  @BeforeEach
  void setup() throws Exception {
    oldData = LunarCore.getConfig().dataDir;
    LunarCore.getConfig().dataDir = root.resolve("data").toString();
    Files.createDirectories(root.resolve("data"));
    Files.writeString(root.resolve("data/RogueDialogueRewards.json"),
        "{\"schema\":1,\"eventId\":40398,\"fragmentReward\":100}");
    fixture.root = root;
    fixture.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-dialogue-rewards-4.6.json")) {
      var source = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject();
      for (var element : source.getAsJsonArray("files")) {
        var file = element.getAsJsonObject();
        var target = root.resolve(file.get("path").getAsString());
        Files.createDirectories(target.getParent());
        Files.write(target, Base64.getDecoder().decode(file.get("rawBase64").getAsString()));
      }
    }
    GameplayDefinitions.load(root);
    oldBuffs.addAll(GameData.getMazeBuffExcelMap().values());
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-blessing-selection-4.6.json")) {
      var source = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject();
      for (var element : source.getAsJsonArray("rows")) {
        var row = element.getAsJsonObject();
        if (row.get("table").getAsString().equals("RogueMazeBuff"))
          GameData.getMazeBuffExcelMap().add(fixture.gson.fromJson(row.get("row"), MazeBuffExcel.class));
        else GameplayDefinitions.blessings.add(
            fixture.gson.fromJson(row.get("row"), GameplayDefinitions.Blessing.class));
      }
    }
    GameplayDefinitions.maps.put(199959, List.of(
        new GameplayDefinitions.Site(199959, 1, true, List.of(2), 3),
        new GameplayDefinitions.Site(199959, 2, false, List.of(), 5)));
    fixture.reloadScene();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    LunarCore.getConfig().dataDir = oldData;
    GameData.getMazeBuffExcelMap().values().clear();
    oldBuffs.forEach(GameData.getMazeBuffExcelMap()::add);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.maps.remove(199959);
  }

  GameplayState.RogueRun run() { return fixture.session.explorer.state.getRogue(); }

  int unique() throws Exception {
    return fixture.unique(fixture.dialogues(fixture.session.query()).getFirst());
  }

  int choose(int id, int option) throws Exception {
    return Gameplay46.number(fixture.session.choose(id, option), "retcode");
  }

  JsonElement snapshot() { return fixture.gson.toJsonTree(fixture.session.explorer.state); }

  @Test
  void originalAuthoredOptionsBecomeUsableWhenTheServerRecipeIsInstalled() throws Exception {
    var dialogue = fixture.dialogues(fixture.session.query()).getFirst();
    var options = (List<?>) Gameplay46.value(dialogue, "KFNMIEMBJGD");
    assertEquals(3, options.size());
    for (var raw : options) {
      var option = (DynamicMessage) raw;
      assertEquals(Boolean.TRUE, Gameplay46.value(option, "is_valid"));
      assertEquals(Boolean.FALSE, Gameplay46.value(option, "is_selected"));
    }
  }

  @Test
  void fragmentChoiceCreditsOnceAndClearsTheActualEventRoom() throws Exception {
    int id = unique(), before = run().getCoins();
    assertEquals(0, choose(id, 14101));
    assertEquals(before + 100, run().getCoins());
    assertEquals(Set.of(1), run().getCleared());
    assertFalse(run().pending());
    assertEquals(0, choose(id, 14101));
    assertNotEquals(0, choose(id, 14103));
    assertEquals(before + 100, run().getCoins());
    assertTrue(run().getMiracles().isEmpty());
  }

  @Test
  void noRewardChoiceStillCompletesTheEventWithoutCreatingInventory() throws Exception {
    int id = unique(), before = run().getCoins();
    assertEquals(0, choose(id, 14103));
    assertEquals(before, run().getCoins());
    assertEquals(Set.of(1), run().getCleared());
    assertTrue(run().getBlessings().isEmpty());
    assertTrue(run().getMiracles().isEmpty());
    assertFalse(run().pending());
  }

  @Test
  void blessingChoiceUsesOneRealPendingCandidateAndClearsOnlyAfterAcquisition() throws Exception {
    int id = unique();
    assertEquals(0, choose(id, 14102));
    assertEquals(List.of(612050), run().getOffers());
    assertTrue(run().pending());
    assertTrue(run().getCleared().isEmpty());
    assertTrue(run().getBlessings().isEmpty());
    int batch = run().getSelectionId();
    assertEquals(Retcode.REQ_PARA_INVALID,
        fixture.session.explorer.getUniverseManager().rerollBlessings(batch));
    assertEquals(Retcode.SUCC,
        fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050));
    assertEquals(Map.of(612050, 1), run().getBlessings());
    assertEquals(Set.of(1), run().getCleared());
    assertFalse(run().pending());
    assertEquals(Retcode.SUCC,
        fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050));
  }

  @ParameterizedTest
  @ValueSource(ints = {14101, 14102, 14103})
  void selectingTheEventRollsBackOnSaveFailureAndCanBeRetried(int option) throws Exception {
    int id = unique(), before = run().getCoins(), batch = run().getSelectionId();
    var saved = snapshot();
    var lease = RogueDialogue46.active(fixture.session.explorer.scene).getFirst().binding();
    fixture.session.explorer.interruptSave = true;
    assertThrows(IllegalStateException.class, () -> choose(id, option));
    assertEquals(before, run().getCoins());
    assertEquals(batch, run().getSelectionId());
    assertFalse(run().pending());
    assertTrue(run().getCleared().isEmpty());
    assertTrue(run().getBlessings().isEmpty());
    assertEquals(saved, snapshot(), "Every touched run field and receipt must roll back");
    assertSame(lease, RogueDialogue46.active(fixture.session.explorer.scene).getFirst().binding());
    fixture.session.explorer.interruptSave = false;
    assertEquals(0, choose(id, option));
    assertEquals(0, choose(id, option));
  }

  @Test
  void blessingAcquisitionRollsBackTheMenuReceiptCollectionAndClearance() throws Exception {
    assertEquals(0, choose(unique(), 14102));
    int batch = run().getSelectionId();
    var saved = snapshot();
    fixture.session.explorer.interruptSave = true;
    assertThrows(IllegalStateException.class,
        () -> fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050));
    assertEquals(List.of(612050), run().getOffers());
    assertTrue(run().getCleared().isEmpty());
    assertTrue(run().getBlessings().isEmpty());
    assertTrue(fixture.session.explorer.state.getRogueCollectedBuffs().isEmpty());
    assertEquals(saved, snapshot(), "Acquisition must restore the entire advertised menu");
    fixture.session.explorer.interruptSave = false;
    assertEquals(Retcode.SUCC,
        fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050));
    assertEquals(Map.of(612050, 1), run().getBlessings());
    assertEquals(Set.of(1), run().getCleared());
  }

  @Test
  void changingTheServerAmountDoesNotRepriceAnAlreadyExposedEvent() throws Exception {
    int id = unique(), before = run().getCoins();
    Files.writeString(root.resolve("data/RogueDialogueRewards.json"),
        "{\"schema\":1,\"eventId\":40398,\"fragmentReward\":999}");
    RogueDialogueRewards.load(root, root.resolve("data/RogueDialogueRewards.json"));
    assertEquals(0, choose(id, 14101));
    assertEquals(before + 100, run().getCoins());
  }

  @Test
  void anAlreadyPersistedOldLeaseSavesItsNewRecipeBeforePublishingTheMenu() throws Exception {
    int id = unique();
    var actor = RogueDialogue46.active(fixture.session.explorer.scene).getFirst();
    var field = GameplayState.RogueDialogue.class.getDeclaredField("rewardState");
    field.setAccessible(true);
    field.set(actor.binding(), null); // Old Player documents have no recipe field.
    assertFalse(actor.needsPersistence());
    int before = fixture.session.explorer.saves;
    fixture.session.response = null;
    fixture.session.explorer.interruptSave = true;
    assertThrows(IllegalStateException.class, fixture.session::query);
    assertNull(fixture.session.response, "The unsaved menu must not leave the server");
    assertTrue(actor.needsPersistence());
    assertEquals(before, fixture.session.explorer.saves);
    fixture.session.explorer.interruptSave = false;
    assertEquals(id, fixture.unique(fixture.dialogues(fixture.session.query()).getFirst()));
    assertNotNull(actor.binding().getRewardState());
    assertEquals(before + 1, fixture.session.explorer.saves);
    fixture.session.query();
    assertEquals(before + 1, fixture.session.explorer.saves);
  }

  @Test
  void aDirectSelectionOfAnOldLeaseAlsoRequiresTheRecipeSaveToSucceed() throws Exception {
    int id = unique();
    var actor = RogueDialogue46.active(fixture.session.explorer.scene).getFirst();
    var field = GameplayState.RogueDialogue.class.getDeclaredField("rewardState");
    field.setAccessible(true);
    field.set(actor.binding(), null);
    fixture.session.response = null;
    fixture.session.explorer.interruptSave = true;
    assertThrows(IllegalStateException.class, () -> choose(id, 14101));
    assertNull(fixture.session.response);
    assertEquals(100, run().getCoins());
    assertTrue(run().getCleared().isEmpty());
    fixture.session.explorer.interruptSave = false;
    assertEquals(0, choose(id, 14101));
    assertEquals(200, run().getCoins());
  }

  @Test
  void concurrentSelectionsApplyTheEventOnce() throws Exception {
    unique();
    var actor = RogueDialogue46.active(fixture.session.explorer.scene).getFirst();
    int saves = fixture.session.explorer.saves;
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> RogueDialogueRewards.select(fixture.session.explorer, actor, 14101));
      var second = workers.submit(() -> RogueDialogueRewards.select(fixture.session.explorer, actor, 14101));
      assertEquals(2, List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
          .stream().filter(code -> code == Retcode.SUCC).count());
    }
    assertEquals(200, run().getCoins());
    assertEquals(Set.of(1), run().getCleared());
    assertEquals(saves + 1, fixture.session.explorer.saves);
  }

  byte[] wire(long id, long option, boolean duplicate, boolean unknown) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt64(3, id);
    out.writeUInt64(12, option);
    if (duplicate) out.writeUInt64(12, option);
    if (unknown) out.writeUInt64(1, 1);
    out.flush();
    return bytes.toByteArray();
  }

  @Test
  void duplicateOverflowUnknownAndTruncatedWireCannotSpendOrClearTheEvent() throws Exception {
    int id = unique();
    var saved = snapshot();
    var normal = wire(id, 14101, false, false);
    for (byte[] bytes : List.of(wire(id, 14101, true, false),
        wire(id, 0x100003715L, false, false), wire(0x100000000L + id, 14101, false, false),
        wire(id, 14101, false, true), Arrays.copyOf(normal, normal.length - 1))) {
      assertTrue(VersionedPacketHandler.handle(fixture.session,
          fixture.session.getProtocol().clientCommand("SelectRogueCommonDialogueOptionCsReq"), bytes));
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.session.response, "retcode"));
      assertEquals(saved, snapshot());
    }
  }

  @Test
  void anOperatorCanDisableTheRecipeWithoutRewritingExistingLeases() throws Exception {
    int id = unique();
    Files.writeString(root.resolve("data/RogueDialogueRewards.json"),
        "{\"schema\":1,\"eventId\":40398,\"fragmentReward\":100,\"enabled\":false}");
    RogueDialogueRewards.load(root, root.resolve("data/RogueDialogueRewards.json"));
    assertNotEquals(0, choose(id, 14101));
    assertTrue(run().getCleared().isEmpty());
    assertEquals(100, run().getCoins());
  }

  @Test
  void alteringTheOriginalEffectlessOptionFileDisablesTheRecipeWithoutCompletingTheRoom() throws Exception {
    int id = unique(), before = run().getCoins();
    Files.writeString(root.resolve("Config/Level/Rogue/RogueDialogue/Event40398/Opt40398.json"),
        "{\"OptionList\":[{\"OptionID\":14101,\"DisplayID\":14101}]}");
    RogueDialogueRewards.load(root, root.resolve("data/RogueDialogueRewards.json"));
    assertNotEquals(0, choose(id, 14101));
    assertEquals(before, run().getCoins());
    assertTrue(run().getCleared().isEmpty());
  }

  @Test
  void missingRealBlessingRowsLeaveThatChoiceUnavailableAndOtherChoicesUsable() throws Exception {
    GameData.getMazeBuffExcelMap().values().clear();
    int id = unique();
    assertNotEquals(0, choose(id, 14102));
    assertFalse(run().pending());
    assertTrue(run().getCleared().isEmpty());
    assertEquals(0, choose(id, 14103));
    assertEquals(Set.of(1), run().getCleared());
  }

  @Test
  void deletingTheDialogueReceiptDoesNotTurnAnOrphanedEventMenuIntoAnOrdinaryReward() throws Exception {
    assertEquals(0, choose(unique(), 14102));
    int batch = run().getSelectionId();
    run().getDialogues().clear();
    fixture.reloadScene();
    assertEquals(Retcode.REQ_PARA_INVALID,
        fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050));
    assertEquals(List.of(612050), run().getOffers());
    assertTrue(run().getBlessings().isEmpty());
    assertTrue(run().getCleared().isEmpty());
  }

  @Test
  void pausedAndStaleInstancesCannotAcquireThePendingEventBlessing() throws Exception {
    assertEquals(0, choose(unique(), 14102));
    int batch = run().getSelectionId();
    run().setInside(false);
    assertEquals(Retcode.REQ_PARA_INVALID,
        fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050));
    run().setInside(true);
    run().enter(2, 305);
    assertEquals(Retcode.REQ_PARA_INVALID,
        fixture.session.explorer.getUniverseManager().selectBlessing(batch, 612050));
    assertTrue(run().getBlessings().isEmpty());
  }

  @Test
  void theThirdPathBlessingRollsBackEvenAfterARealResonanceMenuWasQueued() throws Exception {
    var aeons = RogueAeonCatalog.class.getDeclaredField("catalogue");
    var events = RogueResonance.class.getDeclaredField("events");
    aeons.setAccessible(true);
    events.setAccessible(true);
    var oldAeons = aeons.get(null);
    var oldEvents = events.get(null);
    var oldGroups = new HashMap<>(GameplayDefinitions.rogueBuffGroups);
    try {
      JsonObject proof;
      try (var input = getClass().getResourceAsStream("/fixtures/rogue-resonance-4.6.json")) {
        proof = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
            StandardCharsets.UTF_8)).getAsJsonObject();
      }
      GameplayDefinitions.blessings.clear();
      GameplayDefinitions.rogueBuffGroups.clear();
      for (var raw : proof.getAsJsonArray("groups")) {
        var group = fixture.gson.fromJson(raw, GameplayDefinitions.RogueBuffGroup.class);
        GameplayDefinitions.rogueBuffGroups.put(group.GMLOGNJAIGI(), group);
      }
      var normals = new ArrayList<Integer>();
      for (String table : List.of("buffs", "normalBuffs")) {
        for (var raw : proof.getAsJsonArray(table)) {
          var row = fixture.gson.fromJson(raw, GameplayDefinitions.Blessing.class);
          if (row.RogueBuffType() != 120 || table.equals("normalBuffs") && normals.size() >= 3) continue;
          GameplayDefinitions.blessings.add(row);
          GameData.getMazeBuffExcelMap().add(fixture.gson.fromJson(
              "{\"ID\":" + row.MazeBuffID() + ",\"Lv\":1}", MazeBuffExcel.class));
          if (table.equals("normalBuffs")) normals.add(row.MazeBuffID());
        }
      }
      for (var source : Map.of("RogueAeon", "aeons", "BattleEventData", "events",
          "BattleEventConfig", "eventConfigs", "BattleEventSkillConfig", "skills").entrySet())
        Files.writeString(root.resolve("ExcelOutput/" + source.getKey() + ".json"),
            proof.get(source.getValue()).toString());
      RogueAeonCatalog.load(root);
      var run = new GameplayState.RogueRun(100, 199959, Map.of(1, 305), null, 1);
      assertNotNull(run.getResonance());
      assertTrue(run.getResonance().valid(1));
      run.enter(1, 305);
      run.setInside(true);
      run.getBlessings().put(normals.get(0), 1);
      run.getBlessings().put(normals.get(1), 1);
      fixture.session.explorer.state.setRogue(run);
      fixture.session.explorer.state.getRogueCollectedBuffs().addAll(normals.subList(0, 2));
      fixture.reloadScene();
      assertEquals(0, choose(unique(), 14102));
      assertEquals(List.of(normals.get(2)), run.getOffers());
      int batch = run.getSelectionId();
      var before = snapshot();
      var plan = run.getResonance();
      fixture.session.explorer.interruptSave = true;
      assertThrows(IllegalStateException.class, () -> fixture.session.explorer.getUniverseManager()
          .selectBlessing(batch, normals.get(2)));
      assertEquals(before, snapshot(), "Event acquisition and queued resonance must both roll back");
      assertSame(plan, run.getResonance(), "The existing immutable resonance plan remains bound");
      fixture.session.explorer.interruptSave = false;
      assertEquals(Retcode.SUCC, fixture.session.explorer.getUniverseManager().selectBlessing(batch, normals.get(2)));
      assertEquals(List.of(plan.getBasic()), run.getOffers());
      assertEquals(batch + 1, run.getSelectionId());
      assertEquals(Set.of(1), run.getCleared());
      assertTrue(run.getEventRewardLease().isEmpty());
      assertTrue(run.getDialogues().values().iterator().next().getRewardState().isComplete());
    } finally {
      aeons.set(null, oldAeons);
      events.set(null, oldEvents);
      GameplayDefinitions.rogueBuffGroups.clear();
      GameplayDefinitions.rogueBuffGroups.putAll(oldGroups);
    }
  }

  @Test
  void defaultInstallationPreservesAnOperatorRecipeIncludingItsDisabledSwitch() throws Exception {
    var data = root.resolve("new-default-install");
    org.hkrpg.server.DefaultData.install(data);
    var file = data.resolve("RogueDialogueRewards.json");
    var defaultRecipe = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    assertEquals(40398, defaultRecipe.get("eventId").getAsInt());
    assertTrue(defaultRecipe.get("enabled").getAsBoolean());
    var operator = "{\"schema\":1,\"eventId\":40398,\"enabled\":false,\"fragmentReward\":777}\n";
    Files.writeString(file, operator);
    org.hkrpg.server.DefaultData.install(data);
    assertEquals(operator, Files.readString(file));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"schema\":1,\"eventId\":40398,\"fragmentReward\":2147483648}",
      "{\"schema\":1,\"eventId\":40398,\"fragmentReward\":100,\"enabled\":\"false\"}",
      "{\"schema\":1,\"eventId\":40399,\"fragmentReward\":100}",
      "{\"schema\":1,\"eventId\":40398,\"fragmentReward\":1.5}",
      "{\"schema\":1,\"eventId\":40398,\"fragmentReward\":100,\"miracle\":9}"})
  void malformedOrUnauthoredServerRecipesFailBeforeTheServerCanAdvertiseThem(String json) throws Exception {
    Files.writeString(root.resolve("data/RogueDialogueRewards.json"), json);
    assertThrows(IllegalStateException.class,
        () -> RogueDialogueRewards.load(root, root.resolve("data/RogueDialogueRewards.json")));
  }

  @ParameterizedTest
  @ValueSource(ints = {14101, 14102, 14103})
  void actualPlayerMorphiaReloadPreservesTheEventAndNeverRegrantsItsChoice(int option) throws Exception {
    int id = unique(), before = run().getCoins();
    assertEquals(0, choose(id, option));
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue_dialogue_rewards");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, emu.lunarcore.util.Position.class);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      gameplay.setAccessible(true);
      uid.setAccessible(true);
      var document = new emu.lunarcore.game.player.Player();
      uid.setInt(document, 19995401);
      for (int iteration = 0; iteration < 2; iteration++) {
        gameplay.set(document, fixture.session.explorer.state);
        store.save(document);
        var restored = store.find(emu.lunarcore.game.player.Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 19995401)).first();
        assertNotNull(restored);
        fixture.session.explorer.state = restored.getGameplay();
        fixture.reloadScene();
        assertEquals(id, unique());
        assertEquals(0, choose(id, option));
        if (option == 14102 && iteration == 0) {
          assertEquals(List.of(612050), run().getOffers());
          assertEquals(Retcode.SUCC, fixture.session.explorer.getUniverseManager()
              .selectBlessing(run().getSelectionId(), 612050));
        }
      }
      assertEquals(before + (option == 14101 ? 100 : 0), run().getCoins());
      assertEquals(Set.of(1), run().getCleared());
      assertEquals(option == 14102 ? Map.of(612050, 1) : Map.of(), run().getBlessings());
    } finally { server.shutdownNow(); }
  }
}
