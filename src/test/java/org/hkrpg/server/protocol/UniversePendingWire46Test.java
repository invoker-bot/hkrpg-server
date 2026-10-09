package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Stream;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Native 5676 must not consume selections or currency through protobuf aliases or merging. */
class UniversePendingWire46Test {
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  Gameplay46HandlerTest.Session session;

  @BeforeEach
  void prepare() throws Exception {
    fixture.setup();
    fixture.begin();
    session = fixture.s;
  }

  @AfterEach
  void restore() {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(4);
    GameplayDefinitions.miracleEffects.remove(4);
  }

  void domain(String kind) {
    if (!kind.equals("curio")) return;
    GameplayDefinitions.miracles.put(4, new GameplayDefinitions.Miracle(4, 4, 4));
    GameplayDefinitions.miracleEffects.put(
        4,
        new GameplayDefinitions.MiracleEffect(4, List.of(new GameplayDefinitions.EffectValue(1))));
    session = new Universe46Test.OnlineSession();
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    session.getPlayer().getGameplay().setRogue(run);
    run.offerMiracles(List.of(4));
  }

  byte[] request(String kind, String mode) throws Exception {
    var run = session.getPlayer().getGameplay().getRogue();
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    int batch = run.getSelectionId();
    // The actual reroll button omits batch; zero remains a supported native operation.
    if (!mode.equals("valid") || !kind.equals("reroll")) {
      out.writeTag(5, 0);
      out.writeUInt64NoTag(
          mode.equals("batchAlias")
              ? 4294967296L + batch
              : mode.equals("zeroAlias") ? 4294967296L : batch);
    }
    if (mode.equals("duplicateBatch")) out.writeUInt32(5, batch);
    int action = kind.equals("blessing") ? 1474 : kind.equals("curio") ? 1316 : 51;
    var choiceBytes = new ByteArrayOutputStream();
    var choice = CodedOutputStream.newInstance(choiceBytes);
    if (!kind.equals("reroll")) {
      String type = kind.equals("blessing") ? "BPLNKHGAILK" : "JINFLJMPPHN";
      String name = kind.equals("blessing") ? "BAOFIGFFMLN" : "OJPGDDFJCHB";
      int field =
          session.getProtocol().clientSchema().message(type).findFieldByName(name).getNumber();
      int item = kind.equals("blessing") ? run.getOffers().getFirst() : 4;
      choice.writeTag(field, 0);
      choice.writeUInt64NoTag(mode.equals("choiceAlias") ? 4294967296L + item : item);
      if (mode.equals("duplicateItem")) choice.writeUInt32(field, item);
    }
    choice.flush();
    out.writeByteArray(action, choiceBytes.toByteArray());
    if (mode.equals("duplicateChoice")) out.writeByteArray(action, choiceBytes.toByteArray());
    if (mode.equals("mergedOneof")) {
      if (kind.equals("reroll")) {
        var blessing = Gameplay46.builder(session, "BPLNKHGAILK");
        Gameplay46.set(blessing, "BAOFIGFFMLN", run.getOffers().getFirst());
        out.writeByteArray(1474, blessing.build().toByteArray());
      } else out.writeByteArray(51, new byte[0]);
    }
    if (mode.equals("unknown")) out.writeUInt32(3000, 1);
    if (mode.equals("truncated")) {
      out.writeTag(5, 0);
      out.writeRawByte(0x80);
    }
    if (mode.equals("badWire")) out.writeByteArray(5, new byte[0]);
    if (mode.equals("unterminated")) {
      out.writeTag(5, 0);
      for (int i = 0; i < 11; i++) out.writeRawByte(0x80);
    }
    if (mode.equals("oversize")) out.writeByteArray(3000, new byte[16384]);
    out.flush();
    return bytes.toByteArray();
  }

  static Stream<Arguments> invalidRequests() {
    var cases = new ArrayList<Arguments>();
    for (String kind : List.of("blessing", "curio", "reroll")) {
      for (String mode :
          List.of(
              "batchAlias",
              "zeroAlias",
              "duplicateBatch",
              "duplicateChoice",
              "mergedOneof",
              "unknown",
              "truncated",
              "badWire",
              "unterminated",
              "oversize")) cases.add(Arguments.of(kind, mode));
      if (!kind.equals("reroll"))
        for (String mode : List.of("choiceAlias", "duplicateItem"))
          cases.add(Arguments.of(kind, mode));
    }
    return cases.stream();
  }

  @ParameterizedTest
  @MethodSource("invalidRequests")
  void malformedNativeActionPreservesTheActualMenuCoinsAndAcquiredItems(String kind, String mode)
      throws Exception {
    domain(kind);
    String before = new Gson().toJson(session.getPlayer().getGameplay());
    assertDoesNotThrow(
        () -> assertTrue(VersionedPacketHandler.handle(session, 5676, request(kind, mode))));
    assertEquals("HandleRogueCommonPendingActionScRsp", session.responseName);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertEquals(before, new Gson().toJson(session.getPlayer().getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"blessing", "curio", "reroll"})
  void legitimateNativeOperationsStillCommitTheirActualSelectionOrReroll(String kind)
      throws Exception {
    domain(kind);
    var run = session.getPlayer().getGameplay().getRogue();
    int batch = run.getSelectionId();
    int item = kind.equals("curio") ? 4 : run.getOffers().getFirst();
    assertTrue(VersionedPacketHandler.handle(session, 5676, request(kind, "valid")));
    assertEquals(Retcode.SUCC.getVal(), Gameplay46.number(session.response, "retcode"));
    assertEquals(batch, Gameplay46.number(session.response, "queue_position"));
    if (kind.equals("reroll")) {
      assertEquals(70, run.getCoins());
      assertEquals(1, run.getOfferRerolls());
      assertEquals(3, run.getOffers().size());
      assertFalse(run.getOffers().contains(item));
    } else {
      assertFalse(run.pending());
      assertEquals(100, run.getCoins());
      if (kind.equals("curio")) assertEquals(1, run.getMiracles().get(item).getTotalCount());
      else assertEquals(1, run.getBlessings().get(item));
    }
  }
}
