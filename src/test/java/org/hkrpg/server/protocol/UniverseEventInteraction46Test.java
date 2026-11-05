package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pinned TalkTrigger._CheckRogueDialogue reads NpcRogueGameInfo field 9 to disable F. */
class UniverseEventInteraction46Test {
  @TempDir Path root;
  final UniverseDialogueRewards46Test f = new UniverseDialogueRewards46Test();
  final WireSession wire = new WireSession();

  class WireSession extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<PacketCodec.Frame> sent = new ArrayList<>();
    WireSession() { super(null); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return f.fixture.session.explorer; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override protected void writeTransport(byte[] bytes) {
      var input = Unpooled.wrappedBuffer(bytes);
      try { while (input.isReadable()) sent.add(PacketCodec.decodeNext(input)); }
      finally { input.release(); }
    }
    void request(int command, byte[] bytes) {
      sent.clear();
      var input = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[]{8, 88}, bytes));
      try { onMessage(input); } finally { input.release(); }
      assertEquals(SessionState.ACTIVE, getState());
    }
  }

  @BeforeEach void setup() throws Exception {
    f.root = root; f.setup();
    wire.getPlayer().setSession(wire);
    var online = Player.class.getDeclaredField("loggedIn"); online.setAccessible(true);
    online.setBoolean(wire.getPlayer(), true);
  }
  @AfterEach void cleanup() { f.cleanup(); }

  RogueEventNpc actor() { return RogueDialogue46.active(f.fixture.session.explorer.scene).getFirst(); }
  boolean finished(DynamicMessage info) { return (Boolean) Gameplay46.value(info, "NLAFLKPLPFE"); }
  boolean finished() throws Exception { return finished(f.fixture.nativeEvent(actor())); }
  int choose(int option, boolean acquire) throws Exception {
    int unique = f.unique();
    assertEquals(0, f.choose(unique, option));
    if (option == 14102 && acquire) {
      int batch = f.run().getSelectionId();
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
          wire.getPlayer().getUniverseManager().selectBlessing(batch, 612050));
    }
    return unique;
  }
  void finish(int unique) throws Exception {
    wire.request(5646, UniverseDialogueFinish46Test.request(unique));
    var replies = wire.sent.stream().filter(frame -> frame.cmdId() == 5631).toList();
    assertEquals(1, replies.size());
    assertEquals(0, Gameplay46.number(wire.bridge.clientSchema().parse("LPDPAJGFBMM",
        replies.getFirst().body()), "retcode"));
    assertArrayEquals(new byte[]{8, 88}, replies.getFirst().header());
    for (var frame : wire.sent)
      if (frame != replies.getFirst()) assertEquals(0, frame.header().length);
  }
  void infos(DynamicMessage message, List<DynamicMessage> found) {
    if (message.getDescriptorForType().getName().equals("NpcRogueGameInfo")) found.add(message);
    for (var value : message.getAllFields().values()) {
      if (value instanceof DynamicMessage nested) infos(nested, found);
      else if (value instanceof List<?> rows)
        for (var row : rows) if (row instanceof DynamicMessage nested) infos(nested, found);
    }
  }
  List<DynamicMessage> refreshedNpcs() throws Exception {
    var result = new ArrayList<DynamicMessage>();
    int command = wire.bridge.clientCommand("SceneGroupRefreshScNotify");
    for (var frame : wire.sent)
      if (frame.cmdId() == command)
        infos(wire.bridge.clientSchema().parse("SceneGroupRefreshScNotify", frame.body()), result);
    return result;
  }

  @ParameterizedTest @ValueSource(ints = {14101, 14102, 14103})
  void completedReceiptSetsTheNativeInteractionGateWithoutDeletingTheActor(int option) throws Exception {
    choose(option, true);
    var before = f.snapshot(); int saves = f.fixture.session.explorer.saves;
    assertTrue(finished(), "Completed event still advertises an active F interaction");
    assertTrue(actor().valid());
    assertEquals(before, f.snapshot());
    assertEquals(saves, f.fixture.session.explorer.saves);
  }

  @Test void unselectedAndPendingEventsKeepTheirInteractionAvailable() throws Exception {
    f.unique(); assertFalse(finished());
    choose(14102, false);
    var before = f.snapshot();
    assertFalse(finished());
    assertEquals(before, f.snapshot());
    assertEquals(List.of(612050), f.run().getOffers());
  }

  @ParameterizedTest @ValueSource(ints = {14101, 14102, 14103})
  void restoredCompletedReceiptsDoNotResurrectTheOldFPrompt(int option) throws Exception {
    choose(option, true);
    f.fixture.session.explorer.state = f.fixture.gson.fromJson(f.snapshot(), GameplayState.class);
    f.fixture.reloadScene(); f.unique();
    var before = f.snapshot(); int saves = f.fixture.session.explorer.saves;
    assertTrue(finished());
    assertEquals(before, f.snapshot());
    assertEquals(saves, f.fixture.session.explorer.saves);
  }

  @ParameterizedTest @ValueSource(ints = {14101, 14103})
  void closingACompletedDialoguePublishesItsNpcGateAfterTheReply(int option) throws Exception {
    int unique = choose(option, false);
    var before = f.snapshot(); int saves = f.fixture.session.explorer.saves;
    finish(unique);
    assertEquals(5631, wire.sent.getFirst().cmdId());
    var npcs = refreshedNpcs();
    assertEquals(1, npcs.size(), "Client needs a live NPC completion update, not only a reply");
    assertTrue(finished(npcs.getFirst()));
    assertEquals(unique, Gameplay46.number(npcs.getFirst(), "AIAFEKGBFIF"));
    assertEquals(before, f.snapshot());
    assertEquals(saves, f.fixture.session.explorer.saves);
  }

  @Test void endingBeforeAcquisitionPreservesTheMenuThenAcquisitionClosesTheGate() throws Exception {
    int unique = choose(14102, false), batch = f.run().getSelectionId();
    finish(unique);
    assertTrue(refreshedNpcs().isEmpty());
    assertFalse(finished());
    wire.sent.clear();
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        wire.getPlayer().getUniverseManager().selectBlessing(batch, 612050));
    var npcs = refreshedNpcs();
    assertEquals(1, npcs.size()); assertTrue(finished(npcs.getFirst()));
    assertEquals(Map.of(612050, 1), f.run().getBlessings());
    var before = f.snapshot();
    finish(unique); // The retained actor must still acknowledge a delayed/repeated End.
    assertEquals(before, f.snapshot());
  }

  @ParameterizedTest @ValueSource(strings = {"source", "cleared", "ownership", "collection"})
  void invalidCompletionEvidenceCannotDisableAnEvent(String kind) throws Exception {
    choose(14102, true);
    if (kind.equals("source")) {
      var state = actor().binding().getRewardState();
      var field = RogueDialogueRewardState.class.getDeclaredField("source");
      field.setAccessible(true); field.set(state, "invalid");
    } else if (kind.equals("cleared")) f.run().getCleared().clear();
    else if (kind.equals("ownership")) f.run().getBlessings().clear();
    else wire.getPlayer().getGameplay().getRogueCollectedBuffs().clear();
    var before = f.snapshot();
    assertFalse(finished());
    assertEquals(before, f.snapshot());
  }
}
