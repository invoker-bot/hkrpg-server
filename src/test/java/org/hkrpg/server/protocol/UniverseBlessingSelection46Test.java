package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Exact pending tier must still have its own battle row; rejection preserves the whole menu. */
class UniverseBlessingSelection46Test {
  static final int BUFF = 612050;
  final Gson gson = new Gson();
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  final List<MazeBuffExcel> oldRows = new ArrayList<>();
  final List<GameplayDefinitions.Blessing> oldBlessings = new ArrayList<>();
  final Map<Integer, MazeBuffExcel> rows = new HashMap<>();
  Gameplay46HandlerTest.Session session;

  @BeforeEach
  void setup() throws Exception {
    oldRows.addAll(GameData.getMazeBuffExcelMap().values());
    oldBlessings.addAll(GameplayDefinitions.blessings);
    fixture.setup();
    GameplayDefinitions.blessings.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-blessing-selection-4.6.json")) {
      assertNotNull(in);
      var source =
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var element : source.getAsJsonArray("rows")) {
        var spec = element.getAsJsonObject();
        if (spec.get("table").getAsString().equals("RogueMazeBuff")) {
          var row = gson.fromJson(spec.get("row"), MazeBuffExcel.class);
          GameData.getMazeBuffExcelMap().add(row);
          rows.put(row.getLv(), row);
        } else
          GameplayDefinitions.blessings.add(
              gson.fromJson(spec.get("row"), GameplayDefinitions.Blessing.class));
      }
    }
    // Native start and server-generated level-one reward menu from the sole original row.
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    fixture.s.explorer.getGameplay().getRogue().clear();
    assertEquals(List.of(BUFF), fixture.s.explorer.getUniverseManager().offerBlessings());
    session = fixture.s;
    assertEquals(List.of(BUFF), run().getOffers());
  }

  @AfterEach
  void restore() {
    fixture.cleanup();
    GameData.getMazeBuffExcelMap().values().clear();
    oldRows.forEach(GameData.getMazeBuffExcelMap()::add);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.addAll(oldBlessings);
  }

  GameplayState.RogueRun run() {
    return session.getPlayer().getGameplay().getRogue();
  }

  static Stream<Arguments> variants() {
    return Stream.of(1, 2)
        .flatMap(
            level ->
                Stream.of("missing", "wrongId", "wrongLevel", "ownedLower", "ownedHigher")
                    .flatMap(
                        mode ->
                            Stream.of(false, true)
                                .map(nativeRequest -> Arguments.of(level, mode, nativeRequest))));
  }

  int choose(boolean nativeRequest, int batch) throws Exception {
    if (!nativeRequest)
      return session.getPlayer().getUniverseManager().selectBlessing(batch, BUFF).getVal();
    var choice = Gameplay46.builder(session, "BPLNKHGAILK");
    Gameplay46.set(choice, "BAOFIGFFMLN", BUFF);
    var request = Gameplay46.builder(session, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", batch);
    Gameplay46.set(request, "DLKIKBIDJDB", choice.build());
    assertTrue(VersionedPacketHandler.handle(session, 5676, request.build().toByteArray()));
    assertEquals("HandleRogueCommonPendingActionScRsp", session.responseName);
    int code = Gameplay46.number(session.response, "retcode");
    assertEquals(
        batch, Gameplay46.number(session.response, code == 0 ? "queue_position" : "BFNEPPCNOOI"));
    return code;
  }

  void reload() throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        session.getPlayer(),
        gson.fromJson(gson.toJson(session.getPlayer().getGameplay()), GameplayState.class));
  }

  void corrupt(int level, String mode) throws Exception {
    var row = rows.get(level);
    if (mode.equals("missing")) GameData.getMazeBuffExcelMap().values().removeIf(x -> x == row);
    else if (mode.equals("wrongId") || mode.equals("wrongLevel")) {
      var field = MazeBuffExcel.class.getDeclaredField(mode.equals("wrongId") ? "ID" : "Lv");
      field.setAccessible(true);
      field.setInt(row, mode.equals("wrongId") ? BUFF + 1 : 3 - level);
    } else run().getBlessings().put(BUFF, mode.equals("ownedLower") ? 1 : 2);
  }

  @ParameterizedTest
  @MethodSource("variants")
  void exactTierIdentityAndFreshOwnershipAreCheckedBeforeAnySelectionMutation(
      int level, String mode, boolean nativeRequest) throws Exception {
    if (level == 2)
      run()
          .offer(
              List.of(BUFF),
              List.of(BUFF),
              List.of(),
              Map.of(BUFF, 2)); // Explicit enhanced-menu fixture, not a claimed beacon acquisition.
    reload();
    int batch = run().getSelectionId();
    corrupt(level, mode);
    String before = gson.toJsonTree(session.getPlayer().getGameplay()).toString();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), choose(nativeRequest, batch));
    assertEquals(
        JsonParser.parseString(before),
        gson.toJsonTree(session.getPlayer().getGameplay()),
        "Rejected choice must preserve menu, wallet, ownership and permanent handbook history");
    // Restore only the isolated precondition, then exercise the same still-pending request.
    if (mode.startsWith("owned")) run().getBlessings().remove(BUFF);
    else {
      GameData.getMazeBuffExcelMap().values().removeIf(x -> x == rows.get(level));
      var id = MazeBuffExcel.class.getDeclaredField("ID");
      id.setAccessible(true);
      id.setInt(rows.get(level), BUFF);
      var lv = MazeBuffExcel.class.getDeclaredField("Lv");
      lv.setAccessible(true);
      lv.setInt(rows.get(level), level);
      GameData.getMazeBuffExcelMap().add(rows.get(level));
    }
    assertEquals(Retcode.SUCC.getVal(), choose(nativeRequest, batch));
    assertEquals(level, run().getBlessings().get(BUFF));
    assertTrue(session.getPlayer().getGameplay().getRogueCollectedBuffs().contains(BUFF));
    assertFalse(run().pending());
    String accepted = gson.toJsonTree(session.getPlayer().getGameplay()).toString();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), choose(nativeRequest, batch));
    assertEquals(
        JsonParser.parseString(accepted), gson.toJsonTree(session.getPlayer().getGameplay()));
    reload();
    assertEquals(level, run().getBlessings().get(BUFF));
    assertFalse(run().pending());
  }
}
