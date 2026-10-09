package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.JsonUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueHandbookReward46Test {
  @TempDir Path temp;
  final RogueHandbook46Test collection = new RogueHandbook46Test();
  final Session session = new Session();
  RewardExcel beforeReward;
  ItemExcel beforeItem;

  static class Actor extends Player {
    int saves, deliveries;
    boolean rejectDelivery, rejectSecond, throwAfterDelivery;
    RuntimeException deliveryFailure;
    final Inventory actual =
        new Inventory(this) {
          @Override
          public GrantResult grantItems(ItemParamMap amounts, boolean hint) {
            deliveries++;
            if (rejectDelivery || rejectSecond && deliveries == 2)
              return new GrantResult(List.of(), false);
            GrantResult result;
            try {
              result = super.grantItems(amounts, hint);
            } catch (RuntimeException failure) {
              deliveryFailure = failure;
              throw failure;
            }
            if (throwAfterDelivery)
              throw new IllegalStateException(
                  "Injected interruption after actual currency addition");
            return result;
          }
        };

    @Override
    public Inventory getInventory() {
      return actual;
    }

    @Override
    public void save() {
      saves++;
    }

    @Override
    public void sendPacket(BasePacket packet) {}
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();

    @Override
    public Player getPlayer() {
      return actor;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    collection.temp = temp;
    collection.load();
    assertTrue(session.actor.setNickname("HandbookReward"));
    session.actor.saves = 0;
    session.actor.getGameplay().setRogue(new GameplayState.RogueRun(100, 1, Map.of()));
    beforeReward = GameData.getRewardExcelMap().get(106011);
    beforeItem = GameData.getItemExcelMap().get(1);
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-handbook-reward-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(4, rows.size());
      for (var element : rows) {
        var row = element.getAsJsonObject();
        if (row.get("kind").getAsString().equals("reward")) {
          var value = ResourceLoader.getGson().fromJson(row.get("row"), RewardExcel.class);
          value.onLoad();
          GameData.getRewardExcelMap().add(value);
        } else if (row.get("kind").getAsString().equals("miracle")) {
          var value =
              ResourceLoader.getGson().fromJson(row.get("row"), GameplayDefinitions.Miracle.class);
          GameplayDefinitions.miracles.put(value.MiracleID(), value);
        } else if (row.get("kind").getAsString().equals("effect")) {
          var value =
              ResourceLoader.getGson()
                  .fromJson(row.get("row"), GameplayDefinitions.MiracleEffect.class);
          GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
        } else {
          var raw = row.getAsJsonObject("row").deepCopy();
          raw.addProperty("ItemName", raw.getAsJsonObject("ItemName").get("Hash").getAsLong());
          GameData.getItemExcelMap().add(ResourceLoader.getGson().fromJson(raw, ItemExcel.class));
        }
      }
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    collection.restore();
    GameData.getRewardExcelMap().keySet().remove(106011);
    if (beforeReward != null) GameData.getRewardExcelMap().add(beforeReward);
    GameData.getItemExcelMap().keySet().remove(1);
    if (beforeItem != null) GameData.getItemExcelMap().add(beforeItem);
  }

  byte[] body(List<Integer> ids, boolean packed) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    if (packed) {
      var list = new ByteArrayOutputStream();
      var values = CodedOutputStream.newInstance(list);
      for (int id : ids) values.writeUInt32NoTag(id);
      values.flush();
      out.writeByteArray(2, list.toByteArray());
    } else for (int id : ids) out.writeUInt32(2, id);
    out.flush();
    return bytes.toByteArray();
  }

  DynamicMessage claim(byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 5641, bytes));
    assertEquals("TakeRogueMiracleHandbookRewardScRsp", session.responseName);
    return session.response;
  }

  DynamicMessage claim(List<Integer> ids) throws Exception {
    return claim(body(ids, true));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void realAcquisitionClaimsOriginalThirtyJadesAndReportsOnlyActualClaimedIdentity(boolean packed)
      throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    var response = claim(body(List.of(4), packed));
    assertEquals(
        0, Gameplay46.number(response, "retcode"), String.valueOf(session.actor.deliveryFailure));
    assertEquals(30, session.actor.getHcoin());
    assertEquals(List.of(4), Gameplay46.value(response, "CNDLCGEFLCJ"));
    var reward = (DynamicMessage) Gameplay46.value(response, "reward");
    var items = (List<?>) Gameplay46.value(reward, "item_list");
    assertEquals(1, items.size());
    var item = (DynamicMessage) items.getFirst();
    assertEquals(1, Gameplay46.number(item, "item_id"));
    assertEquals(30, Gameplay46.number(item, "num"));
  }

  @Test
  void aRepeatedClaimCannotPayAgainOrLoseTheTakenQueryFlag() throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    assertEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    assertNotEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    assertEquals(30, session.actor.getHcoin());
    assertEquals(1, session.actor.deliveries);
    assertTrue(VersionedPacketHandler.handle(session, 5684, new byte[0]));
    var data = (DynamicMessage) Gameplay46.value(session.response, "HAMKPLCBEBG");
    var row = (DynamicMessage) ((List<?>) Gameplay46.value(data, "MAJGMMCOCIG")).getFirst();
    assertEquals(true, Gameplay46.value(row, "ALAJJMKOMML"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void invalidBatchNeverReservesOrPaysTheEarlierValidCollectedEntry(int variant) throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    int savesBeforeClaim = session.actor.saves;
    var lists =
        List.of(List.of(4, 4), List.of(4, 99999), List.of(4, 7), List.of(4, 0), List.of(4, -1));
    String before = JsonUtils.encode(session.actor.getGameplay());
    assertNotEquals(0, Gameplay46.number(claim(lists.get(variant)), "retcode"));
    assertEquals(0, session.actor.getHcoin());
    assertEquals(savesBeforeClaim, session.actor.saves);
    assertEquals(before, JsonUtils.encode(session.actor.getGameplay()));
  }

  @Test
  void currentOwnershipAndOfferedItemsDoNotQualifyAsCollected() throws Exception {
    session.actor.getGameplay().getRogue().getMiracles().put(4, new GameplayState.RogueMiracle(1));
    assertNotEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    assertEquals(0, session.actor.getHcoin());
    assertEquals(0, session.actor.deliveries);
  }

  @Test
  void anEmptyRequestDoesNotClaimEveryConfiguredMiracle() throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    assertNotEquals(0, Gameplay46.number(claim(new byte[0]), "retcode"));
    assertEquals(0, session.actor.getHcoin());
  }

  void twoOriginalAcquisitions() {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    assertTrue(RogueMiracles.acquire(session.actor, 7));
  }

  @Test
  void twoOriginalCollectedMiraclesActuallyPaySixtyAndMarkBothTaken() throws Exception {
    twoOriginalAcquisitions();
    var response = claim(List.of(4, 7));
    assertEquals(0, Gameplay46.number(response, "retcode"));
    assertEquals(List.of(4, 7), Gameplay46.value(response, "CNDLCGEFLCJ"));
    assertEquals(60, session.actor.getHcoin());
    assertTrue(RogueHandbookRewards.taken(session.actor, 4));
    assertTrue(RogueHandbookRewards.taken(session.actor, 7));
  }

  @Test
  void previouslyClaimedIdentitiesAreSkippedWhileAnUnclaimedIdentityPaysOnce() throws Exception {
    twoOriginalAcquisitions();
    assertEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    var response = claim(List.of(4, 7));
    assertEquals(0, Gameplay46.number(response, "retcode"));
    assertEquals(List.of(7), Gameplay46.value(response, "CNDLCGEFLCJ"));
    assertEquals(60, session.actor.getHcoin());
    assertEquals(2, session.actor.deliveries);
  }

  byte[] unsigned(java.math.BigInteger value) {
    var result = new ByteArrayOutputStream();
    while (value.compareTo(java.math.BigInteger.valueOf(127)) > 0) {
      result.write(value.intValue() & 127 | 128);
      value = value.shiftRight(7);
    }
    result.write(value.intValue());
    return result.toByteArray();
  }

  byte[] join(byte[]... values) {
    var result = new ByteArrayOutputStream();
    for (var value : values) result.writeBytes(value);
    return result.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14})
  void unknownTruncatedOversizedAndUInt32OrUInt64AliasBodiesCannotPay(int variant)
      throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    var big32 = java.math.BigInteger.ONE.shiftLeft(32);
    var big64 = java.math.BigInteger.ONE.shiftLeft(64);
    var body =
        switch (variant) {
          case 0 -> new byte[] {0};
          case 1 -> new byte[] {8, 4};
          case 2 -> new byte[] {21, 0, 0, 0, 0};
          case 3 -> new byte[] {18, 2, 4};
          case 4 -> new byte[] {16, (byte) 128};
          case 5 -> new byte[] {18, 2, 4, 4};
          case 6 -> join(unsigned(big32.add(java.math.BigInteger.valueOf(16))), new byte[] {4});
          case 7 -> join(new byte[] {16}, unsigned(big32.add(java.math.BigInteger.valueOf(4))));
          case 8 ->
              join(new byte[] {18}, unsigned(big32.add(java.math.BigInteger.ONE)), new byte[] {4});
          case 9 -> join(unsigned(big64.add(java.math.BigInteger.valueOf(16))), new byte[] {4});
          case 10 -> join(new byte[] {16}, unsigned(big64.add(java.math.BigInteger.valueOf(4))));
          case 11 ->
              join(new byte[] {18}, unsigned(big64.add(java.math.BigInteger.ONE)), new byte[] {4});
          case 12 -> new byte[] {16, (byte) 132, (byte) 128, (byte) 128, (byte) 128, (byte) 128, 0};
          case 13 -> body(Collections.nCopies(101, 4), true);
          case 14 -> new byte[4097];
          default -> throw new AssertionError();
        };
    String before = JsonUtils.encode(session.actor.getGameplay());
    assertEquals(5, Gameplay46.number(claim(body), "retcode"));
    assertEquals(0, session.actor.getHcoin());
    assertEquals(0, session.actor.deliveries);
    assertEquals(before, JsonUtils.encode(session.actor.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void missingOrChangedRewardResourcesAndDamagedHistoryDoNotReserveOrPay(int variant)
      throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    int savesBeforeClaim = session.actor.saves;
    switch (variant) {
      case 0 -> GameData.getRewardExcelMap().keySet().remove(106011);
      case 1 -> GameData.getRewardExcelMap().get(106011).getRewards().clear();
      case 2 ->
          GameData.getItemExcelMap()
              .add(
                  new Gson()
                      .fromJson(
                          "{\"ID\":1,\"ItemMainType\":\"Unknown\",\"PileLimit\":999999999}",
                          ItemExcel.class));
      case 3 -> GameData.getItemExcelMap().keySet().remove(1);
      case 4 -> GameData.getRewardExcelMap().get(106011).getRewards().put(1, 29);
      case 5 -> {
        var path = temp.resolve("ExcelOutput/RogueHandbookMiracle.json");
        var rows = JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonArray();
        for (var raw : rows)
          if (raw.getAsJsonObject().get("MiracleHandbookID").getAsInt() == 4)
            raw.getAsJsonObject().addProperty("MiracleReward", 106099);
        java.nio.file.Files.writeString(path, rows.toString());
        RogueHandbookCatalog.load(temp);
      }
      case 6 -> {
        var field = RogueHandbookState.class.getDeclaredField("miracles");
        field.setAccessible(true);
        field.set(session.actor.getGameplay().getRogueHandbook(), null);
      }
      default -> throw new AssertionError();
    }
    String before = JsonUtils.encode(session.actor.getGameplay());
    assertNotEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    assertEquals(0, session.actor.getHcoin());
    assertEquals(savesBeforeClaim, session.actor.saves);
    assertEquals(before, JsonUtils.encode(session.actor.getGameplay()));
  }

  @Test
  void theWholeBatchCapacityIsCheckedBeforeAnyClaimReceipt() throws Exception {
    twoOriginalAcquisitions();
    int savesBeforeClaim = session.actor.saves;
    var field = Player.class.getDeclaredField("hcoin");
    field.setAccessible(true);
    field.setInt(session.actor, 999999960);
    assertNotEquals(0, Gameplay46.number(claim(List.of(4, 7)), "retcode"));
    assertEquals(999999960, session.actor.getHcoin());
    assertEquals(savesBeforeClaim, session.actor.saves);
    assertTrue(session.actor.getGameplay().getRewards().isEmpty());
  }

  @Test
  void concurrentClaimsActuallyDeliverOneThirtyJadeReward() throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    try (var workers = Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<Callable<RogueHandbookRewards.Result>>();
      for (int i = 0; i < 24; i++)
        calls.add(() -> RogueHandbookRewards.claim(session.actor, List.of(4)));
      int successes = 0;
      for (var value : workers.invokeAll(calls))
        if (value.get().code() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
    }
    assertEquals(30, session.actor.getHcoin());
    assertEquals(1, session.actor.deliveries);
  }

  @Test
  void failedSecondPaymentKeepsTheFirstConfirmedClaimAndHoldsThePendingSecond() throws Exception {
    twoOriginalAcquisitions();
    session.actor.rejectSecond = true;
    assertNotEquals(0, Gameplay46.number(claim(List.of(4, 7)), "retcode"));
    assertEquals(30, session.actor.getHcoin());
    assertTrue(RogueHandbookRewards.taken(session.actor, 4));
    assertFalse(RogueHandbookRewards.taken(session.actor, 7));
    session.actor.rejectSecond = false;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(claim(List.of(7)), "retcode"));
    assertEquals(30, session.actor.getHcoin());
    assertEquals(2, session.actor.deliveries);
  }

  @Test
  void interruptionAfterActualPaymentHoldsReceiptWithoutAutomaticallyRegranting() throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    session.actor.throwAfterDelivery = true;
    assertNotEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    assertEquals(30, session.actor.getHcoin());
    assertFalse(RogueHandbookRewards.taken(session.actor, 4));
    session.actor.throwAfterDelivery = false;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(claim(List.of(4)), "retcode"));
    assertEquals(30, session.actor.getHcoin());
    assertEquals(1, session.actor.deliveries);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void unauthenticatedRewardRequestsCannotClaim(int index) throws Exception {
    session.setState(
        List.of(
                SessionState.WAITING_FOR_TOKEN,
                SessionState.WAITING_FOR_LOGIN,
                SessionState.INACTIVE)
            .get(index));
    assertTrue(VersionedPacketHandler.handle(session, 5641, body(List.of(4), true)));
    assertNull(session.response);
    assertEquals(0, session.actor.deliveries);
  }

  @Test
  void completedExplorationStillAllowsItsActuallyCollectedUnclaimedReward() throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    session.actor.getGameplay().setRogue(new GameplayState().getRogue());
    assertEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    assertEquals(30, session.actor.getHcoin());
  }

  @Test
  void actualPlayerTwoMorphiaReloadsKeepTakenFlagWalletAndEndedRun() throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    assertEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "handbook-rewards");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456782);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, session.actor.getGameplay());
      var balance = Player.class.getDeclaredField("hcoin");
      balance.setAccessible(true);
      balance.setInt(document, session.actor.getHcoin());
      store.save(document);
      var first = store.find(Player.class).first();
      assertEquals(30, first.getHcoin());
      assertTrue(RogueHandbookRewards.taken(first, 4));
      first.getGameplay().setRogue(new GameplayState().getRogue());
      store.save(first);
      var second = store.find(Player.class).first();
      assertEquals(30, second.getHcoin());
      assertTrue(RogueHandbookRewards.taken(second, 4));
      gameplay.set(session.actor, second.getGameplay());
      balance.setInt(session.actor, second.getHcoin());
      assertNotEquals(0, Gameplay46.number(claim(List.of(4)), "retcode"));
      assertEquals(30, session.actor.getHcoin());
      assertEquals(1, session.actor.deliveries);
    } finally {
      mongo.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void damagedClaimReceiptsCannotBeReportedAsUnclaimedOrAutomaticallyRegranted(int kind)
      throws Exception {
    assertTrue(RogueMiracles.acquire(session.actor, 4));
    if (kind == 0) {
      var field = GameplayState.class.getDeclaredField("rewards");
      field.setAccessible(true);
      field.set(session.actor.getGameplay(), null);
    } else session.actor.getGameplay().getRewards().put(RogueHandbookRewards.key(4), null);
    String before = JsonUtils.encode(session.actor.getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, 5684, new byte[0]));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(session.response, "retcode"));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(claim(List.of(4)), "retcode"));
    assertEquals(0, session.actor.getHcoin());
    assertEquals(0, session.actor.deliveries);
    assertEquals(before, JsonUtils.encode(session.actor.getGameplay()));
  }
}
