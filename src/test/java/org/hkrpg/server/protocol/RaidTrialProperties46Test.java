package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Actual raid storage must retain declared trial data; property execution remains a separate check.
 */
class RaidTrialProperties46Test {
  @TempDir Path root;
  final RaidTrialCandidates46Test candidates = new RaidTrialCandidates46Test();

  @BeforeEach
  void setup() throws Exception {
    candidates.root = root;
    candidates.setup();
    candidates.candidates(40475);
  }

  @AfterEach
  void cleanup() throws Exception {
    candidates.cleanup();
  }

  JsonObject source(int id) {
    return candidates.source.getAsJsonArray("specials").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(r -> r.get("SpecialAvatarID").getAsInt() == id)
        .findFirst()
        .orElseThrow();
  }

  void declared(int id, String saved) {
    var actual = JsonParser.parseString(saved).getAsJsonObject();
    // Compare only fields the fixed source actually declares, allowing omitted default zeros.
    for (var entry : source(id).entrySet()) {
      assertTrue(actual.has(entry.getKey()), "Lost declared field " + entry.getKey());
      assertEquals(entry.getValue(), actual.get(entry.getKey()), "Changed " + entry.getKey());
    }
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        1011415, 1021402, 1021405, 1021407, 1011412, 1031406, 1021414, 1021413, 1021410, 1021409,
        1021404, 1021403, 1031408
      })
  void persistedSelectedTrialsKeepTheSourceRelicAndAbilityDeclarations(int id) throws Exception {
    var session = candidates.data.fixture.ready();
    var selected = id == 1011415 ? candidates.choices(id) : candidates.choices(1011415, id);
    assertEquals(0, candidates.data.fixture.choicesStart(session, selected));
    var run = new MissionRaid(session.trial).current();
    declared(id, run.getTrialParty().getConfigs().get(id));
    assertEquals(id, session.trial.getAvatarById(id).getBattleAvatarId());
    assertEquals(
        0,
        session
            .trial
            .getAvatarById(id)
            .toBattleProto(session.trial.getCurrentLineup(), 0)
            .getRelicList()
            .length());
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void activeAndPausedRestoreRetainDeclarationsWithoutInventingOwnedRelics(
      boolean active, boolean morphia) throws Exception {
    if (morphia) {
      // Explicit synthetic persistence case: the fixed source has no nonempty OverrideProperty.
      // These declarations are retained as data; no ability or property effect is executed here.
      var row = source(1011415);
      row.add(
          "OverrideProperty",
          JsonParser.parseString("[{\"PropertyType\":\"Attack\",\"Value\":123.5}]"));
      row.add("AbilityNameList", JsonParser.parseString("[\"HKRPG_TEST_ONLY\"]"));
      GameData.getSpecialAvatarExcelMap().add(new Gson().fromJson(row, SpecialAvatarExcel.class));
    }
    var session = candidates.data.fixture.ready();
    var selected = candidates.choices(1011415, 1031408);
    assertEquals(0, candidates.data.fixture.choicesStart(session, selected));
    if (!active)
      assertEquals(0, candidates.data.fixture.hybrid.fixture.leave(MissionRaid46Test.RAID, false));
    var gson = new Gson();
    var saved = gson.fromJson(gson.toJson(session.trial.getGameplay()), GameplayState.class);
    if (morphia) saved = stored(saved);
    var restored = candidates.data.fixture.reload(session.trial, saved);
    if (active) assertTrue(new MissionRaid(restored.trial).restoreOnLogin());
    else assertEquals(0, candidates.data.fixture.choicesStart(restored, selected));
    for (int id : List.of(1011415, 1031408)) {
      declared(id, new MissionRaid(restored.trial).current().getTrialParty().getConfigs().get(id));
      assertEquals(
          source(id).get("AvatarID").getAsInt(), restored.trial.getAvatarById(id).getExcelId());
      declared(
          id,
          gson.toJson(
              ((emu.lunarcore.game.trial.TrialAvatar) restored.trial.getAvatarById(id))
                  .getSpecialAvatarExcel()));
      assertEquals(
          0,
          restored
              .trial
              .getAvatarById(id)
              .toBattleProto(restored.trial.getCurrentLineup(), 0)
              .getRelicList()
              .length());
    }
    assertTrue(new MissionRaid(restored.trial).activeFor(MissionRaid46Test.MAIN));
  }

  GameplayState stored(GameplayState data) throws Exception {
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "trialproperties");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var saved = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(saved, 19997839);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(saved, data);
      store.save(saved);
      return Objects.requireNonNull(store.find(Player.class).first()).getGameplay();
    } finally {
      mongo.shutdownNow();
    }
  }
}
