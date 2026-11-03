package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CN4.6 ordinary promotion is 505/575; the colliding 563 request is quick growth. */
class LightConePromotion46Test {
  final ProgressionGatesTest gates = new ProgressionGatesTest();
  final DailyUpgradeTest f = gates.f;
  final Session session = new Session();
  static final byte[] HEADER = {8, 77};

  class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<PacketCodec.Frame> sent = new ArrayList<>();

    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return f.player; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override protected void writeTransport(byte[] bytes) {
      var buffer = Unpooled.wrappedBuffer(bytes);
      try {
        while (buffer.isReadable()) sent.add(PacketCodec.decodeNext(buffer));
      } finally { buffer.release(); }
    }
  }

  @BeforeEach void setup() throws Exception {
    gates.setup();
    try (var in = getClass().getResourceAsStream("/fixtures/lightcone-promotion-4.6.json")) {
      var fixture = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
      for (var file : fixture.getAsJsonArray("files")) {
        String name = file.getAsJsonObject().get("path").getAsString();
        for (var record : file.getAsJsonObject().getAsJsonArray("rows")) {
          var raw = record.getAsJsonObject().getAsJsonObject("row").deepCopy();
          if (name.endsWith("EquipmentConfig.json")) {
            f.install(GameData.getEquipExcelMap(), f.gson.fromJson(raw, EquipmentExcel.class));
          } else if (name.endsWith("EquipmentPromotionConfig.json")) {
            var row = f.gson.fromJson(raw, EquipmentPromotionExcel.class);
            row.onLoad();
            f.install(GameData.getEquipmentPromotionExcelMap(), row);
          } else {
            raw.addProperty("ItemName", raw.getAsJsonObject("ItemName").get("Hash").getAsLong());
            f.install(GameData.getItemExcelMap(), f.gson.fromJson(raw, ItemExcel.class));
          }
        }
      }
    }
    GameData.getEquipExcelMap().get(23004).onLoad();
    f.target = f.item(23004, 1);
    f.target.setLevel(20);
    f.item(112001, 32);
    f.item(110151, 10);
  }

  @AfterEach void cleanup() { gates.cleanup(); }

  static byte[] number(int field, int value) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(bytes);
    wire.writeUInt32(field, value);
    wire.flush();
    return bytes.toByteArray();
  }

  static byte[] join(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (var part : parts) out.writeBytes(part);
    return out.toByteArray();
  }

  void request(byte[] body) {
    var input = Unpooled.wrappedBuffer(PacketCodec.encode(505, HEADER, body));
    try { session.onMessage(input); } finally { input.release(); }
  }

  void reply(boolean success) throws Exception {
    assertEquals(1, session.sent.size(), "Every ordinary promotion must finish its pending request");
    var frame = session.sent.getFirst();
    assertEquals(575, frame.cmdId());
    assertArrayEquals(HEADER, frame.header());
    var response = session.bridge.clientSchema().parse("PromoteEquipmentScRsp", frame.body());
    assertTrue(response.getUnknownFields().asMap().isEmpty());
    assertEquals(success, (Integer) response.getField(response.getDescriptorForType().findFieldByNumber(1)) == 0);
    assertEquals(SessionState.ACTIVE, session.getState());
  }

  @Test void ordinaryFiveStarPromotionChargesOriginalCostsAndCompletesTheClientWait() throws Exception {
    int coins = f.player.getScoin();
    // Protecting the target does not prevent cultivating it.
    f.target.setLocked(true);
    request(number(10, f.target.getInternalUid()));
    assertEquals(1, f.target.getPromotion());
    assertEquals(20, f.target.getLevel());
    assertEquals(coins - 5000, f.player.getScoin());
    assertEquals(24, f.player.getInventory().getMaterialByItemId(112001).getCount());
    reply(true);
    var after = gates.state();
    session.sent.clear();
    request(number(10, f.target.getInternalUid()));
    reply(false);
    assertEquals(after, gates.state());
  }

  @Test void clientReportedCostsCannotMakePromotionFree() throws Exception {
    int coins = f.player.getScoin();
    // A present, empty ItemCostData (field 13) cannot override authoritative costs.
    request(join(number(10, f.target.getInternalUid()), new byte[] {106, 0}));
    reply(true);
    assertEquals(coins - 5000, f.player.getScoin());
    assertEquals(24, f.player.getInventory().getMaterialByItemId(112001).getCount());
  }

  @ParameterizedTest @ValueSource(strings = {"level", "player-level", "world-level", "materials", "credits", "unknown", "wrong-kind"})
  void failedPromotionRepliesWithoutChangingInventoryOrCurrency(String mode) throws Exception {
    int id = f.target.getInternalUid();
    switch (mode) {
      case "level" -> f.target.setLevel(19);
      case "player-level" -> f.player.setLevel(14);
      case "world-level" -> { f.target.setPromotion(1); f.target.setLevel(30); }
      case "materials" -> f.player.getInventory().getMaterialByItemId(112001).setCount(7);
      case "credits" -> f.player.addSCoin(4999 - f.player.getScoin());
      case "unknown" -> id = Integer.MAX_VALUE;
      case "wrong-kind" -> id = f.player.getInventory().getMaterialByItemId(112001).getInternalUid();
    }
    var before = gates.state();
    request(number(10, id));
    reply(false);
    assertEquals(before, gates.state());
  }

  @ParameterizedTest @ValueSource(strings = {"truncated", "unknown", "duplicate-target", "wrong-wire", "unknown-cost"})
  void invalidWireReceivesFailureBeforeAnyCharge(String mode) throws Exception {
    byte[] id = number(10, f.target.getInternalUid());
    byte[] wire = switch (mode) {
      case "truncated" -> new byte[] {106, 100};
      case "unknown" -> join(id, number(15, 1));
      case "duplicate-target" -> join(id, id);
      case "wrong-wire" -> new byte[] {82, 0};
      case "unknown-cost" -> join(id, new byte[] {106, 2, 120, 1});
      default -> throw new AssertionError(mode);
    };
    var before = gates.state();
    request(wire);
    reply(false);
    assertEquals(before, gates.state());
  }

  @Test void responseCorrelationUsesTheOrdinaryPairInsteadOfTheCollidingEnumName() {
    assertEquals(505, session.bridge.clientCommand("PromoteEquipmentCsReq"));
    assertEquals(575, session.bridge.clientResponseCommand(505));
    assertEquals(0, session.bridge.clientResponseCommand(563));
    assertEquals(0, ProtocolBridge.forVersion("4.2.0").clientResponseCommand(505));
  }

  @Test void quickGrowthCannotBeMistakenForAnOrdinaryPromotion() throws Exception {
    var before = gates.state();
    assertFalse(VersionedPacketHandler.handle(session, 563, number(10, f.target.getInternalUid())));
    assertTrue(session.sent.isEmpty());
    assertEquals(before, gates.state());
  }

  @Test void inactiveSessionCannotPromoteOrReceiveSuccess() throws Exception {
    session.setState(SessionState.WAITING_FOR_LOGIN);
    var before = gates.state();
    assertTrue(VersionedPacketHandler.handle(session, 505, number(10, f.target.getInternalUid())));
    assertTrue(session.sent.isEmpty());
    assertEquals(before, gates.state());
  }
}
