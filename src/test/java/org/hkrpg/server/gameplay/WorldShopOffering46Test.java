package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.data.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original NPC and offering-10 rows; prerequisite completion and stock are explicit fixtures. */
class WorldShopOffering46Test {
  @TempDir Path root;
  final OfferingQuery46Test query = new OfferingQuery46Test();
  final Gson gson = ResourceLoader.getGson();
  final JsonObject source =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/world-shop-offering-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  KraterOffering46Test f;

  @BeforeEach
  void setup() throws Exception {
    query.root = root;
    query.setup();
    f = query.fixture;
    for (var entry : source.getAsJsonObject("records").entrySet()) {
      String table = Path.of(entry.getKey()).getFileName().toString().replace(".json", "");
      String keys = source.getAsJsonObject("keys").get(table).getAsString();
      var merged =
          JsonParser.parseString(Files.readString(root.resolve(entry.getKey()))).getAsJsonArray();
      var all = new HashMap<String, JsonElement>();
      for (var row : merged) all.put(key(row, keys), row);
      for (var row : entry.getValue().getAsJsonArray()) {
        var old = all.get(key(row, keys));
        if (old != null) assertEquals(old, row, "Conflicting original source row");
        else {
          merged.add(row.deepCopy());
          all.put(key(row, keys), row);
        }
      }
      f.write(entry.getKey(), merged);
      var mapping = f.table(table);
      if (mapping == null) continue;
      for (var row : entry.getValue().getAsJsonArray()) {
        var excel = gson.fromJson(row, mapping.type());
        excel.onLoad();
        mapping.map().add(excel);
      }
    }
    for (String section : List.of("groups", "headers", "floors"))
      for (var entry : source.getAsJsonObject(section).entrySet())
        f.write(entry.getKey(), entry.getValue());
  }

  static String key(JsonElement row, String keys) {
    return String.join(
        ":",
        Arrays.stream(keys.split(","))
            .map(k -> row.getAsJsonObject().get(k).getAsString())
            .toList());
  }

  @AfterEach
  void cleanup() throws Exception {
    query.cleanup();
  }

  DynamicMessage request(String name, int id, List<Integer> levels, int entity) throws Exception {
    var descriptor = f.session.protocol.clientSchema().message(name);
    var b = DynamicMessage.newBuilder(descriptor);
    if (name.equals("GetOfferingInfoCsReq"))
      b.addRepeatedField(descriptor.findFieldByName("offering_id_list"), id);
    else {
      b.setField(descriptor.findFieldByName("offering_id"), id);
      b.setField(descriptor.findFieldByName("interacted_prop_entity_id"), entity);
      if (name.equals("TakeOfferingRewardCsReq"))
        for (int level : levels)
          b.addRepeatedField(descriptor.findFieldByName("take_reward_level_list"), level);
    }
    assertTrue(
        VersionedPacketHandler.handle(
            f.session, f.session.protocol.clientCommand(name), b.build().toByteArray()));
    return f.session.response;
  }

  int submit() throws Exception {
    return code(request("SubmitOfferingItemCsReq", 10, List.of(), f.actor()));
  }

  int claim(List<Integer> levels) throws Exception {
    return code(request("TakeOfferingRewardCsReq", 10, levels, f.actor()));
  }

  static int code(DynamicMessage m) {
    return KraterOffering46Test.number(m, "retcode");
  }

  void unlock() {
    query.completeMain(1040505);
  }

  void stock(int count) {
    f.player.stock.put(120005, new GameItem(GameData.getItemExcelMap().get(120005), count));
  }

  DynamicMessage info() throws Exception {
    var result = request("GetOfferingInfoCsReq", 10, List.of(), 0);
    assertEquals(0, code(result));
    return OfferingQuery46Test.infos(result).getFirst();
  }

  void progress(int level, int exp, int total) throws Exception {
    var row = info();
    assertEquals(10, KraterOffering46Test.number(row, "offering_id"));
    assertEquals(level, KraterOffering46Test.number(row, "offering_level"));
    assertEquals(exp, KraterOffering46Test.number(row, "level_exp"));
    assertEquals(total, KraterOffering46Test.number(row, "total_exp"));
  }

  Map<Integer, Integer> recipe(List<Integer> levels, int tails) {
    var rewards = new HashMap<Integer, JsonObject>();
    source
        .getAsJsonObject("records")
        .getAsJsonArray("ExcelOutput/RewardData.json")
        .forEach(
            r -> rewards.put(r.getAsJsonObject().get("RewardID").getAsInt(), r.getAsJsonObject()));
    var out = new TreeMap<Integer, Integer>();
    for (var raw :
        source.getAsJsonObject("records").getAsJsonArray("ExcelOutput/OfferingLevelConfig.json")) {
      var row = raw.getAsJsonObject();
      int level = row.get("Level").getAsInt();
      if (!levels.contains(level)) continue;
      int multiplier = level == 51 ? tails : 1;
      var reward = rewards.get(row.get("RewardID").getAsInt());
      if (reward.has("Hcoin"))
        out.merge(1, reward.get("Hcoin").getAsInt() * multiplier, Math::addExact);
      for (var entry : reward.entrySet())
        if (entry.getKey().startsWith("ItemID_"))
          out.merge(
              entry.getValue().getAsInt(),
              reward.get("Count_" + entry.getKey().substring(7)).getAsInt() * multiplier,
              Math::addExact);
    }
    return out;
  }

  @Test
  void exactOriginalMainMissionGateBlocksConsumptionUntilFinished() throws Exception {
    stock(200);
    assertEquals(Retcode.SHOP_NOT_OPEN.getVal(), submit());
    assertEquals(200, f.player.stock.get(120005).getCount());
    unlock();
    assertEquals(0, submit());
    progress(1, 0, 200);
    assertEquals(0, f.player.stock.get(120005).getCount());
  }

  @ParameterizedTest
  @ValueSource(ints = {199, 200, 1199, 1200, 13500, 13800})
  void sourceCostsConsumeRealMaterialAndRetainTheSourceLongTail(int amount) throws Exception {
    unlock();
    stock(amount);
    assertEquals(0, submit());
    int level = 0, remaining = amount;
    for (var raw :
        source.getAsJsonObject("records").getAsJsonArray("ExcelOutput/OfferingLevelConfig.json")) {
      var row = raw.getAsJsonObject();
      if (row.get("Level").getAsInt() > 50) continue;
      int cost = row.get("ItemCost").getAsInt();
      if (remaining < cost) break;
      remaining -= cost;
      level++;
    }
    progress(level, remaining, amount);
    assertEquals(0, f.player.stock.get(120005).getCount());
    assertEquals(1, f.player.debits);
  }

  @Test
  void allFiftySourceRewardsAreDeliveredOnceAndDoNotTouchOfferingSix() throws Exception {
    unlock();
    stock(13500);
    assertEquals(0, submit());
    var levels = IntStream.rangeClosed(1, 50).boxed().toList();
    assertEquals(0, claim(levels));
    assertEquals(recipe(levels, 0), f.player.delivered);
    assertEquals(levels, KraterOffering46Test.field(info(), "has_taken_reward_id_list"));
    assertNotEquals(0, claim(levels));
    assertEquals(1, f.player.grants);
    assertEquals(0, KraterOffering46Test.number(f.query(), "total_exp"));
    assertNull(f.player.getGameplay().getKraterOffering().getQuest());
  }

  @Test
  void repeatableTailUsesActualExcessAndDoesNotRepeatNormalRewards() throws Exception {
    unlock();
    stock(13800);
    assertEquals(0, submit());
    assertEquals(0, claim(List.of(51)));
    assertEquals(recipe(List.of(51), 3), f.player.delivered);
    progress(50, 0, 13800);
    assertNotEquals(0, claim(List.of(51)));
    stock(100);
    assertEquals(0, submit());
    assertEquals(0, claim(List.of(51)));
    assertEquals(recipe(List.of(51), 4), f.player.delivered);
    progress(50, 0, 13900);
    assertEquals(List.of(), KraterOffering46Test.field(info(), "has_taken_reward_id_list"));
  }

  @Test
  void sourceNpcAndEntityValidationRunBeforeAnyInventoryMutation() throws Exception {
    unlock();
    stock(200);
    for (int entity : new int[] {0, -1, 999999})
      assertNotEquals(0, code(request("SubmitOfferingItemCsReq", 10, List.of(), entity)));
    assertEquals(200, f.player.stock.get(120005).getCount());
    assertEquals(0, f.player.saves);
    assertEquals(0, submit());
  }

  @Test
  void cancelledMainRecordDoesNotUnlockTheWorldShop() throws Exception {
    unlock();
    f.player.getGameplay().getCancelledMissions().add(1040505);
    stock(200);
    assertEquals(Retcode.SHOP_NOT_OPEN.getVal(), submit());
    assertEquals(0, f.player.debits);
  }

  @Test
  void mixedAllTypeQueryKeepsTheTwoIndependentLedgers() throws Exception {
    f.stock(1300);
    assertEquals(0, f.submit(f.actor()));
    unlock();
    stock(200);
    assertEquals(0, submit());
    var reply = query.query(IntStream.rangeClosed(1, 15).boxed().toList());
    assertEquals(0, code(reply));
    var rows = OfferingQuery46Test.infos(reply);
    assertEquals(1300, KraterOffering46Test.number(rows.get(5), "total_exp"));
    assertEquals(200, KraterOffering46Test.number(rows.get(9), "total_exp"));
  }

  @Test
  void unearnedAndDuplicateBatchRewardsDoNotPartiallyGrant() throws Exception {
    unlock();
    stock(200);
    assertEquals(0, submit());
    assertNotEquals(0, claim(List.of(1, 2)));
    assertNotEquals(0, claim(List.of(1, 1)));
    assertNotEquals(0, claim(List.of(51)));
    assertEquals(0, f.player.grants);
    assertEquals(List.of(), KraterOffering46Test.field(info(), "has_taken_reward_id_list"));
  }

  @Test
  void failedPreparedSaveDoesNotConsumeMaterialAndCanBeRetried() throws Exception {
    unlock();
    stock(200);
    f.player.failOnSave = f.player.saves + 1;
    assertNotEquals(0, submit());
    assertEquals(200, f.player.stock.get(120005).getCount());
    assertEquals(0, f.player.debits);
    f.player.failOnSave = -1;
    assertEquals(0, submit());
    progress(1, 0, 200);
  }

  @Test
  void uncertainDebitCannotBeRetriedOrBorrowTheOtherOfferingJournal() throws Exception {
    unlock();
    stock(200);
    f.player.failDebit = true;
    assertNotEquals(0, submit());
    f.player.failDebit = false;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit());
    assertEquals(0, f.player.debits);
    f.stock(200);
    assertEquals(0, f.submit(f.actor()));
    assertEquals(1, f.player.debits);
  }

  @Test
  void failedGrantRetainsAnUnpaidDeliveryAndPreventsAutomaticReplay() throws Exception {
    unlock();
    stock(200);
    assertEquals(0, submit());
    f.player.failGrant = true;
    assertNotEquals(0, claim(List.of(1)));
    f.player.failGrant = false;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(List.of(1)));
    assertEquals(1, f.player.grants);
    assertEquals(List.of(), KraterOffering46Test.field(info(), "has_taken_reward_id_list"));
  }

  @Test
  void serializedIndependentLedgersRestoreTheirOwnCostsAndSourceRecipes() throws Exception {
    f.stock(1300);
    assertEquals(0, f.submit(f.actor()));
    unlock();
    stock(13500);
    assertEquals(0, submit());
    assertEquals(0, claim(List.of(1)));
    var restored = gson.fromJson(gson.toJson(f.player.getGameplay()), GameplayState.class);
    assertTrue(restored.getKraterOffering().valid(KraterOfferingRules.load(6), f.player));
    assertTrue(restored.getWorldShopOffering().valid(KraterOfferingRules.load(10), f.player));
    assertEquals(1300, restored.getKraterOffering().paid());
    assertEquals(13500, restored.getWorldShopOffering().paid());
    assertEquals(Set.of(1), restored.getWorldShopOffering().taken());
    assertEquals(
        recipe(List.of(1), 0), restored.getWorldShopOffering().getClaims().getFirst().getItems());
    assertNull(restored.getWorldShopOffering().getQuest());
    assertNull(restored.getWorldShopOffering().getGoal());
  }

  @Test
  void copiedOfferingSixJournalCannotBeUsedAsWorldShopProgress() throws Exception {
    f.stock(1300);
    assertEquals(0, f.submit(f.actor()));
    unlock();
    f.player
        .getGameplay()
        .worldShopOffering(
            gson.fromJson(
                gson.toJson(f.player.getGameplay().getKraterOffering()),
                KraterOfferingState.class));
    var response = query.query(List.of(6, 10, 12));
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code(response));
    assertTrue(OfferingQuery46Test.infos(response).isEmpty());
    assertEquals(1300, f.player.getGameplay().getKraterOffering().paid());
  }

  @Test
  void foreignActivityGoalCannotBeAttachedToTheWorldShopJournal() throws Exception {
    unlock();
    stock(200);
    assertEquals(0, submit());
    var json = gson.toJsonTree(f.player.getGameplay().getWorldShopOffering()).getAsJsonObject();
    json.add("quest", gson.toJsonTree(new emu.lunarcore.game.quest.QuestState(6029857)));
    f.player.getGameplay().worldShopOffering(gson.fromJson(json, KraterOfferingState.class));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        code(request("GetOfferingInfoCsReq", 10, List.of(), 0)));
  }

  @Test
  void interruptedAppliedDebitRemainsPendingAfterRealMaterialWasConsumed() throws Exception {
    unlock();
    stock(200);
    f.player.failOnSave = f.player.saves + 2;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit());
    assertEquals(0, f.player.stock.get(120005).getCount());
    f.player.failOnSave = -1;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit());
    assertEquals(1, f.player.debits);
    assertEquals(0, f.player.getGameplay().getWorldShopOffering().paid());
    assertTrue(f.player.getGameplay().getWorldShopOffering().pending());
  }

  @Test
  void interruptedAppliedGrantDoesNotAutomaticallyDeliverRewardsAgain() throws Exception {
    unlock();
    stock(200);
    assertEquals(0, submit());
    f.player.failOnSave = f.player.saves + 2;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(List.of(1)));
    f.player.failOnSave = -1;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(List.of(1)));
    assertEquals(1, f.player.grants);
    assertEquals(recipe(List.of(1), 0), f.player.delivered);
    assertTrue(f.player.getGameplay().getWorldShopOffering().pending());
    assertEquals(List.of(), KraterOffering46Test.field(info(), "has_taken_reward_id_list"));
  }

  @Test
  void missingLoadedSourceMainDefinitionRejectsNewPayment() throws Exception {
    unlock();
    stock(200);
    GameData.getMainMissionExcelMap().keySet().remove(1040505);
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), submit());
    assertEquals(0, f.player.debits);
  }

  @Test
  void changedWorldShopRewardCannotChangeThePriceOrAwardRecipe() throws Exception {
    unlock();
    stock(200);
    Path path = root.resolve("ExcelOutput/RewardData.json");
    var all = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    int reward =
        source
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/OfferingLevelConfig.json")
            .get(0)
            .getAsJsonObject()
            .get("RewardID")
            .getAsInt();
    var row =
        all.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("RewardID").getAsInt() == reward)
            .findFirst()
            .orElseThrow();
    row.addProperty("Count_1", row.get("Count_1").getAsInt() + 1);
    Files.writeString(path, gson.toJson(all));
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), submit());
    assertEquals(0, f.player.debits);
    f.stock(200);
    assertEquals(0, f.submit(f.actor()));
  }

  @Test
  void staleOriginalNpcEntityCannotConsumeWorldShopMaterials() throws Exception {
    unlock();
    stock(200);
    int entity = f.actor();
    f.player.scene.getEntities().remove(entity);
    assertNotEquals(0, code(request("SubmitOfferingItemCsReq", 10, List.of(), entity)));
    assertEquals(0, f.player.debits);
    assertEquals(200, f.player.stock.get(120005).getCount());
  }
}
