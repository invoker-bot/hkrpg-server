package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native bytes and original table fixture; no official client execution or battle input. */
class LocalLegendData46Test {
  @TempDir Path root;
  Path sourceRoot;
  Session session;

  static class Actor extends Player {
    int saves;

    @Override
    public void save() {
      saves++;
    }
  }

  static class Session extends GameSession {
    final Actor actor = new Actor();
    DynamicMessage response;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
      actor.resetPosition();
    }

    @Override
    public Player getPlayer() {
      return actor;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    @Override
    public void send(BasePacket packet) {}

    @Override
    public void sendClientPacket(String name, byte[] body) {
      try {
        response = getProtocol().clientSchema().parse(name, body);
      } catch (java.io.IOException ex) {
        throw new AssertionError(ex);
      }
    }
  }

  @BeforeEach
  void load() throws Exception {
    sourceRoot = Path.of(emu.lunarcore.LunarCore.getConfig().getResourceDir());
    var directory = root.resolve("ExcelOutput");
    Files.createDirectories(directory);
    var rows =
        JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass()
                                .getResourceAsStream("/fixtures/local-legend-catalog-4.6.json"))
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("tables");
    for (String table :
        List.of(
            "LocalLegendStageConfig",
            "ActivityLocalLegendGroup",
            "ActivityLocalLegendReward",
            "BattleTargetConfig")) {
      var values = rows.getAsJsonObject(table).getAsJsonArray("rows");
      assertFalse(values.isEmpty());
      Files.writeString(directory.resolve(table + ".json"), values.toString());
    }
    LocalLegendCatalog.load(root);
    session = new Session();
  }

  @AfterEach
  void restore() {
    LocalLegendCatalog.load(sourceRoot);
  }

  int retcode() {
    return (Integer)
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode"));
  }

  Map<String, Integer> masks() {
    var result = new HashMap<String, Integer>();
    var type = session.response.getDescriptorForType();
    for (var value : (List<?>) session.response.getField(type.findFieldByName("level_data_list"))) {
      var row = (DynamicMessage) value;
      var r = row.getDescriptorForType();
      var key =
          row.getField(r.findFieldByName("group_id"))
              + ":"
              + row.getField(r.findFieldByName("DBKHHCGGKNN"));
      assertNull(result.put(key, (Integer) row.getField(r.findFieldByName("FFGFINDLMCF"))));
      assertTrue(row.getUnknownFields().asMap().isEmpty());
    }
    return result;
  }

  @Test
  void emptyOriginalQueryReturnsFifteenZeroMasksWithoutCreatingAnyProgressOrQuests()
      throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 6600, new byte[0]));
    assertEquals(0, retcode());
    var masks = masks();
    assertEquals(15, masks.size());
    assertTrue(masks.values().stream().allMatch(v -> v == 0));
    assertTrue(session.actor.getGameplay().getLocalLegendProgress().entries().isEmpty());
    assertTrue(session.actor.getQuestStates().isEmpty());
    assertEquals(0, session.actor.saves);
  }

  @Test
  void nativeQueryPreservesIndependentActualBitsRatherThanTheNumberOfFinishedGoals()
      throws Exception {
    var history = session.actor.getGameplay().getLocalLegendProgress();
    history.merge(LocalLegendCatalog.level(1, 3), 4, 100);
    history.merge(LocalLegendCatalog.level(3, 2), 2, 101);
    assertTrue(VersionedPacketHandler.handle(session, 6600, new byte[0]));
    assertEquals(0, retcode());
    var actual = masks();
    assertEquals(15, actual.size());
    assertEquals(4, actual.get("1:3"));
    assertEquals(2, actual.get("3:2"));
    actual.remove("1:3");
    actual.remove("3:2");
    assertTrue(actual.values().stream().allMatch(v -> v == 0));
    assertEquals(2, history.entries().size());
    assertEquals(0, session.actor.saves);
  }

  @Test
  void corruptedStoredBindingReturnsDataErrorWithoutInventingZeroProgressOrRemovingHistory()
      throws Exception {
    var history = session.actor.getGameplay().getLocalLegendProgress();
    history.merge(LocalLegendCatalog.level(1, 3), 4, 100);
    var entry = history.entries().getFirst();
    var field = entry.getClass().getDeclaredField("resourceIdentity");
    field.setAccessible(true);
    field.set(entry, "other source");
    assertTrue(VersionedPacketHandler.handle(session, 6600, new byte[0]));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), retcode());
    assertTrue(masks().isEmpty());
    assertEquals(4, entry.mask());
    assertEquals(1, history.entries().size());
    assertEquals(0, session.actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0801", "08010802", "0a00", "80", "0000", "0b0c"})
  void unknownDuplicateAndTruncatedQueryBytesAreRejectedWithoutMutation(String hex)
      throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 6600, HexFormat.of().parseHex(hex)));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), retcode());
    assertTrue(masks().isEmpty());
    assertEquals(0, session.actor.saves);
    assertTrue(session.actor.getQuestStates().isEmpty());
    assertTrue(session.actor.getGameplay().getLocalLegendProgress().entries().isEmpty());
  }

  @Test
  void unauthenticatedQueryCannotPublishSavedActivityProgress() throws Exception {
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 6600, new byte[0]));
    assertNull(session.response);
    assertEquals(0, session.actor.saves);
  }
}
