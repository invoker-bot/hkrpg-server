package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.mail.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WelcomeMailQuantityTest {
  final List<Runnable> restorations = new ArrayList<>();
  Config.WelcomeMail oldMail;
  Mail captured;
  final Player player =
      new Player() {
        final Inventory actual =
            new Inventory(this) {
              public List<GameItem> addItems(List<GameItem> values, boolean hint) {
                var delivery = new ArrayList<GameItem>();
                for (var v : values)
                  delivery.add(
                      new GameItem(v.getExcel(), v.getCount()) {
                        public void save() {}
                      });
                return super.addItems(delivery, hint);
              }
            };

        public Inventory getInventory() {
          return actual;
        }

        public void save() {}

        public void sendPacket(BasePacket packet) {}
      };
  final Mailbox mailbox =
      new Mailbox(player) {
        public void sendMail(Mail mail) {
          captured = mail;
        }
      };

  <T extends GameResource> void install(ExcelMap<T> map, T row) {
    var old = map.get(row.getId());
    restorations.add(
        () -> {
          map.keySet().remove(row.getId());
          if (old != null) map.add(old);
        });
    map.add(row);
  }

  @BeforeEach
  void setup() throws Exception {
    oldMail = LunarCore.getConfig().getServerOptions().welcomeMail;
    try (var in = getClass().getResourceAsStream("/fixtures/item-sale-4.6.json")) {
      assertNotNull(in);
      for (var value :
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()) {
        var r = value.getAsJsonObject();
        var raw = r.getAsJsonObject("row");
        switch (r.get("kind").getAsString()) {
          case "item" ->
              install(
                  GameData.getItemExcelMap(),
                  ResourceLoader.getGson().fromJson(raw, ItemExcel.class));
          case "equipment" -> {
            var row = ResourceLoader.getGson().fromJson(raw, EquipmentExcel.class);
            install(GameData.getEquipExcelMap(), row);
            row.onLoad();
          }
          case "relic" -> {
            var row = ResourceLoader.getGson().fromJson(raw, RelicExcel.class);
            install(GameData.getRelicExcelMap(), row);
            row.onLoad();
          }
        }
      }
    }
  }

  @AfterEach
  void cleanup() {
    LunarCore.getConfig().getServerOptions().welcomeMail = oldMail;
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
  }

  void send(ItemParam... attachments) {
    var config = new Config.WelcomeMail();
    config.title = "Quantity fixture";
    config.sender = "Isolated test";
    config.content = "Original item quantities";
    config.attachments = List.of(attachments);
    LunarCore.getConfig().getServerOptions().welcomeMail = config;
    mailbox.sendWelcomeMail();
    assertNotNull(captured);
    assertEquals("Quantity fixture", captured.getTitle());
    assertEquals("Isolated test", captured.getSender());
  }

  Map<Integer, Integer> amounts() {
    var result = new TreeMap<Integer, Integer>();
    for (var item : captured.getAttachments())
      result.merge(item.getItemId(), item.getCount(), Math::addExact);
    return result;
  }

  static List<Arguments> quantities() {
    return List.of(
        Arguments.of(61011, 2),
        Arguments.of(61011, 3),
        Arguments.of(20000, 2),
        Arguments.of(20000, 3));
  }

  @ParameterizedTest
  @MethodSource("quantities")
  void configuredUniqueItemQuantityCreatesThatManyIndependentAttachments(int id, int count) {
    send(new ItemParam(id, count));
    assertEquals(count, captured.getAttachments().size());
    assertEquals(Map.of(id, count), amounts());
    var identities = Collections.newSetFromMap(new IdentityHashMap<GameItem, Boolean>());
    for (var item : captured.getAttachments()) {
      assertTrue(identities.add(item));
      assertEquals(id, item.getItemId());
      assertEquals(1, item.getCount());
    }
    var proto = captured.toProto().getAttachment();
    assertEquals(count, proto.getItemList().length());
    for (var item : proto.getItemList()) assertEquals(1, item.getNum());
  }

  @Test
  void mixedUniqueAndMaterialQuantitiesAreAllPreserved() {
    send(new ItemParam(61011, 2), new ItemParam(20000, 2), new ItemParam(231, 100));
    assertEquals(Map.of(61011, 2, 20000, 2, 231, 100), amounts());
    assertEquals(5, captured.getAttachments().size());
  }

  @Test
  void repeatedConfiguredUniqueItemRowsKeepTheirWholeCombinedQuantity() {
    send(new ItemParam(61011, 1), new ItemParam(61011, 2));
    assertEquals(Map.of(61011, 3), amounts());
  }

  @Test
  void ordinaryMaterialQuantityRemainsOnePile() {
    send(new ItemParam(231, 100));
    assertEquals(Map.of(231, 100), amounts());
    assertEquals(1, captured.getAttachments().size());
  }

  @Test
  void anEmptyConfiguredGiftStillProducesTheConfiguredMessage() {
    send();
    assertTrue(captured.getAttachments() == null || captured.getAttachments().isEmpty());
  }

  @Test
  void disabledWelcomeMailCreatesNothing() {
    LunarCore.getConfig().getServerOptions().welcomeMail = null;
    mailbox.sendWelcomeMail();
    assertNull(captured);
  }

  @ParameterizedTest
  @ValueSource(ints = {61011, 20000})
  void actualInventoryDeliveryAllocatesTwoDistinctOwnedUniqueItems(int id) {
    send(new ItemParam(id, 2));
    var received = player.getInventory().addItems(captured.getAttachments());
    assertEquals(2, received.size());
    assertEquals(2, player.getInventory().getItems().size());
    assertNotEquals(received.get(0).getInternalUid(), received.get(1).getInternalUid());
    for (var item : received) {
      assertSame(item, player.getInventory().getItemByUid(item.getInternalUid()));
      assertEquals(player.getUid(), item.getOwnerUid());
    }
  }
}
