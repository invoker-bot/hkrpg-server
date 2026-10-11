package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual original NPC and source costs; prerequisite history and inventory are unit fixtures. */
class KraterOffering46Test {
  @TempDir Path root;
  final Gson gson = ResourceLoader.getGson();
  final JsonObject source =
      JsonParser.parseReader(
              new java.io.InputStreamReader(
                  Objects.requireNonNull(
                      getClass().getResourceAsStream("/fixtures/krater-offering-4.6.json")),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  final Map<ResourceMap, List<ResourceBase>> previous = new IdentityHashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<GameplayDefinitions.Window> oldWindows = new ArrayList<>();
  FloorInfo oldFloor;
  String previousRoot;
  Actor player;
  Session session;

  static class Actor extends MissionFarmingBattleTest.Farmer {
    Scene scene;
    int saves, grants, debits;
    boolean failGrant, failDebit;
    int failOnSave = -1;
    GameplayState snapshot;
    final Map<Integer, GameItem> stock = new HashMap<>();
    final Map<Integer, Integer> delivered = new TreeMap<>();
    final Inventory inventory =
        new Inventory(this) {
          public GameItem getMaterialByItemId(int id) {
            return stock.get(id);
          }

          public boolean consumeForUse(GameItem item, int count, Runnable applied) {
            if (failDebit || item == null || item.getCount() < count) return false;
            debits++;
            item.setCount(item.getCount() - count);
            applied.run();
            return true;
          }

          public GrantResult grantItems(ItemParamMap items, boolean hint) {
            grants++;
            if (failGrant) return new GrantResult(List.of(), false);
            items.forEach(e -> delivered.merge(e.getIntKey(), e.getIntValue(), Math::addExact));
            return new GrantResult(items.toItemList(), true);
          }
        };

    public Inventory getInventory() {
      return inventory;
    }

    public Scene getScene() {
      return scene;
    }

    public int getPlaneId() {
      return 10402;
    }

    public int getFloorId() {
      return 10402001;
    }

    public int getEntryId() {
      return 1040201;
    }

    public int getWorldId() {
      return 501;
    }

    public void sendPacket(BasePacket packet) {}

    public void save() {
      if (saves + 1 == failOnSave)
        throw new IllegalStateException("Injected offering save failure");
      saves++;
      snapshot = new Gson().fromJson(new Gson().toJson(getGameplay()), GameplayState.class);
    }
  }

  static class Session extends GameSession {
    final Actor player;
    final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
    DynamicMessage response;
    String name;

    Session(Actor player) {
      super(null);
      this.player = player;
      setState(SessionState.ACTIVE);
    }

    public Actor getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return protocol;
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        this.name = name;
        response = protocol.clientSchema().parse(name, bytes);
      } catch (java.io.IOException bad) {
        throw new AssertionError(bad);
      }
    }
  }

  record Table(ResourceMap map, Class<? extends ResourceBase> type) {}

  @SuppressWarnings("unchecked")
  static Collection<ResourceBase> values(ResourceMap map) {
    return map instanceof ExcelMap excel ? excel.values() : ((MultiKeyExcelMap) map).values();
  }

  Table table(String name) {
    if (name.startsWith("ItemConfig") || name.equals("ItemPlayerCard"))
      return new Table(GameData.getItemExcelMap(), ItemExcel.class);
    return switch (name) {
      case "QuestData" -> new Table(GameData.getQuestExcelMap(), QuestExcel.class);
      case "FinishWay" -> new Table(GameData.getFinishWayExcelMap(), FinishWayExcel.class);
      case "RewardData" -> new Table(GameData.getRewardExcelMap(), RewardExcel.class);
      case "ActivityPanel" ->
          new Table(GameData.getActivityPanelExcelMap(), ActivityPanelExcel.class);
      case "MainMission" -> new Table(GameData.getMainMissionExcelMap(), MainMissionExcel.class);
      case "MazePlane" -> new Table(GameData.getMazePlaneExcelMap(), MazePlaneExcel.class);
      case "MapEntrance" -> new Table(GameData.getMapEntranceExcelMap(), MapEntranceExcel.class);
      case "NPCData" -> new Table(GameData.getNpcExcelMap(), NpcExcel.class);
      default -> null;
    };
  }

  void write(String name, JsonElement value) throws Exception {
    Path path = root.resolve(name);
    Files.createDirectories(path.getParent());
    Files.writeString(path, gson.toJson(value));
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    previousRoot = LunarCore.getConfig().getResourceDir();
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), root.toString());
    oldSubs.putAll(GameplayDefinitions.subs);
    oldWindows.addAll(GameplayDefinitions.windows);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
    for (var entry : source.getAsJsonObject("records").entrySet()) {
      write(entry.getKey(), entry.getValue());
      var table = table(Path.of(entry.getKey()).getFileName().toString().replace(".json", ""));
      if (table == null) continue;
      if (!previous.containsKey(table.map())) {
        previous.put(table.map(), new ArrayList<>(values(table.map())));
        values(table.map()).clear();
      }
      for (var raw : entry.getValue().getAsJsonArray()) {
        var decoded = gson.fromJson(raw, table.type());
        decoded.onLoad();
        table.map().add(decoded);
      }
    }
    for (var section : List.of("groups", "headers", "floors"))
      for (var entry : source.getAsJsonObject(section).entrySet())
        write(entry.getKey(), entry.getValue());
    var floor =
        gson.fromJson(
            source.getAsJsonObject("floors").entrySet().iterator().next().getValue(),
            FloorInfo.class);
    var group =
        gson.fromJson(
            source.getAsJsonObject("groups").entrySet().iterator().next().getValue(),
            GroupInfo.class);
    group.setId(24);
    floor.getGroups().put(24, group);
    floor.onLoad();
    oldFloor = GameData.getFloorInfos().put("P10402_F10402001", floor);
    var header =
        source
            .getAsJsonObject("headers")
            .getAsJsonObject("Config/Level/Mission/1040104/MissionInfo_1040104.partial.json");
    for (var raw : header.getAsJsonArray("SubMissionList")) {
      var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      if (sub.getId() == 104010403) GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    assertTrue(
        GameplayDefinitions.subs.containsKey(104010403), "Missing actual source prerequisite");
    player = new Actor();
    player.setLevel(21);
    for (int main : new int[] {1000510, 1040104}) {
      var state = new QuestState(main);
      state.advance(1, 1, 1);
      state.reserveReward();
      state.closeReward();
      player.getGameplay().getMainMissions().put(main, state);
    }
    var sub = new QuestState(104010403);
    sub.advance(1, Math.max(GameplayDefinitions.subs.get(104010403).getProgress(), 1), 1);
    player.getGameplay().getSubMissions().put(104010403, sub);
    player.scene = new Scene(player, GameData.getMazePlaneExcelMap().get(10402), 10402001);
    player.scene.setEntryId(1040201);
    session = new Session(player);
    assertTrue(player.scene.getLoadedGroups().contains(24), "Source NPC group did not load");
    assertEquals(
        1,
        player.scene.getEntities().values().stream()
            .filter(e -> e instanceof EntityNpc && e.getGroupId() == 24 && e.getInstId() == 400001)
            .count());
  }

  @AfterEach
  @SuppressWarnings("unchecked")
  void cleanup() throws Exception {
    previous.forEach(
        (map, rows) -> {
          values(map).clear();
          rows.forEach(map::add);
        });
    if (oldFloor == null) GameData.getFloorInfos().remove("P10402_F10402001");
    else GameData.getFloorInfos().put("P10402_F10402001", oldFloor);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
    var field = LunarCore.getConfig().getClass().getDeclaredField("resourceDir");
    field.setAccessible(true);
    field.set(LunarCore.getConfig(), previousRoot);
  }

  int actor() {
    return player.scene.getEntities().values().stream()
        .filter(e -> e instanceof EntityNpc && e.getGroupId() == 24 && e.getInstId() == 400001)
        .findFirst()
        .orElseThrow()
        .getEntityId();
  }

  void stock(int count) {
    player.stock.put(120004, new GameItem(GameData.getItemExcelMap().get(120004), count));
  }

  DynamicMessage request(String name, List<Integer> levels, int entity) throws Exception {
    var descriptor = session.protocol.clientSchema().message(name);
    var b = DynamicMessage.newBuilder(descriptor);
    if (name.equals("GetOfferingInfoCsReq"))
      b.addRepeatedField(descriptor.findFieldByName("offering_id_list"), 6);
    else {
      b.setField(descriptor.findFieldByName("offering_id"), 6);
      b.setField(descriptor.findFieldByName("interacted_prop_entity_id"), entity);
      if (name.equals("TakeOfferingRewardCsReq"))
        for (int level : levels)
          b.addRepeatedField(descriptor.findFieldByName("take_reward_level_list"), level);
    }
    assertTrue(
        VersionedPacketHandler.handle(
            session, session.protocol.clientCommand(name), b.build().toByteArray()));
    assertEquals(name.replace("CsReq", "ScRsp"), session.name);
    return session.response;
  }

  static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  static int number(DynamicMessage m, String name) {
    var value = field(m, name);
    return value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e
        ? e.getNumber()
        : (Integer) value;
  }

  DynamicMessage query() throws Exception {
    var reply = request("GetOfferingInfoCsReq", List.of(), 0);
    assertEquals(0, number(reply, "retcode"));
    var rows = (List<?>) field(reply, "offering_info_list");
    assertEquals(1, rows.size());
    return (DynamicMessage) rows.getFirst();
  }

  void progress(int level, int current, int total) throws Exception {
    var info = query();
    assertEquals(6, number(info, "offering_id"));
    assertEquals(level, number(info, "offering_level"));
    assertEquals(current, number(info, "level_exp"));
    assertEquals(total, number(info, "total_exp"));
  }

  int submit(int entity) throws Exception {
    return number(request("SubmitOfferingItemCsReq", List.of(), entity), "retcode");
  }

  @Test
  void originalChestFinderRewardUsesRealInventoryStorage() {
    var excel = GameData.getItemExcelMap().get(408905);
    var resident = new InventoryGrantDeltaTest.Resident();
    assertTrue(
        resident.inventory.addItem(
            new GameItem(excel, 1) {
              public void save() {}
            }));
    assertEquals(1, resident.inventory.getMaterialByItemId(408905).getCount());
    assertTrue(Inventory.isStackType(excel));
  }

  @Test
  void queryStartsAtZeroAndUsesTheOriginalUnlock() throws Exception {
    progress(0, 0, 0);
    assertEquals(2, number(query(), "offering_state"));
    assertEquals(0, player.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {199, 200, 1000, 1299, 1300, 18500})
  void actualVaryingCostsConsumeOnlyAvailableMaterial(int count) throws Exception {
    stock(count);
    assertEquals(0, submit(actor()));
    assertEquals(0, player.stock.get(120004).getCount());
    int level = 0, remaining = count;
    for (var raw :
        source.getAsJsonObject("records").getAsJsonArray("ExcelOutput/OfferingLevelConfig.json")) {
      var row = raw.getAsJsonObject();
      if (row.get("Level").getAsInt() > 50) continue;
      int cost = row.get("ItemCost").getAsInt();
      if (remaining < cost) break;
      remaining -= cost;
      level++;
    }
    progress(level, remaining, count);
    assertEquals(1, player.debits);
  }

  @Test
  void levelSixPaysItsOriginalEidolonAndCurrenciesOnlyOnce() throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    assertEquals(0, number(request("TakeOfferingRewardCsReq", List.of(6), actor()), "retcode"));
    assertEquals(Map.of(22, 200, 2, 30000, 223, 3, 18007, 1), player.delivered);
    assertEquals(List.of(6), field(query(), "has_taken_reward_id_list"));
    assertNotEquals(0, number(request("TakeOfferingRewardCsReq", List.of(6), actor()), "retcode"));
    assertEquals(1, player.grants);
  }

  @Test
  void allFiftyOriginalLevelRewardsCanBeClaimedAsOneBatch() throws Exception {
    stock(18500);
    assertEquals(0, submit(actor()));
    var levels = java.util.stream.IntStream.rangeClosed(1, 50).boxed().toList();
    assertEquals(0, number(request("TakeOfferingRewardCsReq", levels, actor()), "retcode"));
    var expected = new TreeMap<Integer, Integer>();
    for (var raw :
        source.getAsJsonObject("records").getAsJsonArray("ExcelOutput/RewardData.json")) {
      var row = raw.getAsJsonObject();
      int id = row.get("RewardID").getAsInt();
      if (id < 119101 || id > 119150) continue;
      if (row.has("Hcoin")) expected.merge(1, row.get("Hcoin").getAsInt(), Math::addExact);
      for (var entry : row.entrySet())
        if (entry.getKey().startsWith("ItemID_"))
          expected.merge(
              entry.getValue().getAsInt(),
              row.get("Count_" + entry.getKey().substring(7)).getAsInt(),
              Math::addExact);
    }
    assertEquals(expected, player.delivered);
    assertEquals(levels, field(query(), "has_taken_reward_id_list"));
  }

  @Test
  void longTailRequiresNewPaymentAndDoesNotRepayItsPreviousBatch() throws Exception {
    stock(18700);
    assertEquals(0, submit(actor()));
    progress(50, 200, 18700);
    assertEquals(0, number(request("TakeOfferingRewardCsReq", List.of(51), actor()), "retcode"));
    progress(50, 0, 18700);
    assertTrue(((List<?>) field(query(), "has_taken_reward_id_list")).isEmpty());
    assertNotEquals(0, number(request("TakeOfferingRewardCsReq", List.of(51), actor()), "retcode"));
    stock(100);
    assertEquals(0, submit(actor()));
    assertEquals(0, number(request("TakeOfferingRewardCsReq", List.of(51), actor()), "retcode"));
    progress(50, 0, 18800);
    assertEquals(2, player.grants);
  }

  @Test
  void missingMaterialsDoNotReserveOrSaveProgress() throws Exception {
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), submit(actor()));
    assertEquals(0, player.saves);
    progress(0, 0, 0);
  }

  @Test
  void zeroAndUnboundEntitiesNeverConsumeMaterial() throws Exception {
    stock(1300);
    assertNotEquals(0, submit(0));
    assertNotEquals(0, submit(999999));
    assertEquals(1300, player.stock.get(120004).getCount());
    assertEquals(0, player.saves);
  }

  @Test
  void unfinishedActualSubMissionKeepsTheOfferingLocked() throws Exception {
    player.getGameplay().getSubMissions().remove(104010403);
    stock(1300);
    assertEquals(1, number(query(), "offering_state"));
    assertNotEquals(0, submit(actor()));
    assertEquals(0, player.debits);
  }

  @Test
  void worldOfferingRemainsUsableWithoutAnActivityWindow() throws Exception {
    GameplayDefinitions.windows.clear();
    stock(1300);
    assertEquals(0, submit(actor()));
    progress(6, 0, 1300);
  }

  int claim(int... levels) throws Exception {
    return number(
        request("TakeOfferingRewardCsReq", Arrays.stream(levels).boxed().toList(), actor()),
        "retcode");
  }

  void restore(GameplayState state) {
    player.getGameplay().kraterOffering(state.getKraterOffering());
  }

  @Test
  void initialPaymentSaveFailureRollsBackBeforeAnyDebit() throws Exception {
    stock(1300);
    player.failOnSave = 1;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(actor()));
    assertEquals(1300, player.stock.get(120004).getCount());
    assertTrue(player.getGameplay().getKraterOffering().getPayments().isEmpty());
    assertEquals(0, player.debits);
    player.failOnSave = -1;
    assertEquals(0, submit(actor()));
    progress(6, 0, 1300);
  }

  @Test
  void interruptedDebitCompletionKeepsReservationAcrossRestore() throws Exception {
    stock(1300);
    player.failOnSave = 2;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(actor()));
    assertEquals(0, player.stock.get(120004).getCount());
    assertEquals(1, player.debits);
    restore(player.snapshot);
    assertTrue(player.getGameplay().getKraterOffering().pending());
    progress(0, 0, 0);
    player.failOnSave = -1;
    stock(200);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), submit(actor()));
    assertEquals(200, player.stock.get(120004).getCount());
    assertEquals(1, player.debits);
  }

  @Test
  void failedInventoryDebitCannotTurnReservationIntoPaidProgress() throws Exception {
    stock(1300);
    player.failDebit = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), submit(actor()));
    assertEquals(1300, player.stock.get(120004).getCount());
    assertTrue(player.getGameplay().getKraterOffering().pending());
    progress(0, 0, 0);
    assertEquals(0, player.debits);
  }

  @Test
  void initialClaimSaveFailureDoesNotGrantAndAllowsRetry() throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    player.failOnSave = player.saves + 1;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(6));
    assertEquals(0, player.grants);
    assertTrue(player.getGameplay().getKraterOffering().getClaims().isEmpty());
    player.failOnSave = -1;
    assertEquals(0, claim(6));
    assertEquals(1, player.grants);
  }

  @Test
  void interruptedClaimClosureNeverRepaysAfterRestore() throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    player.failOnSave = player.saves + 2;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(6));
    assertEquals(1, player.grants);
    restore(player.snapshot);
    assertTrue(player.getGameplay().getKraterOffering().pending());
    assertTrue(((List<?>) field(query(), "has_taken_reward_id_list")).isEmpty());
    assertTrue(QuestCompletionRecords.completed(player, 6029857));
    player.failOnSave = -1;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(6));
    assertEquals(1, player.grants);
  }

  @Test
  void incompleteGrantKeepsTheReservedBatchAndBlocksOtherRewards() throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    player.failGrant = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), claim(6));
    restore(player.snapshot);
    assertTrue(player.getGameplay().getKraterOffering().pending());
    player.failGrant = false;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), claim(1));
    assertEquals(1, player.grants);
  }

  @Test
  void originalGoalRewardAndCompletionHistoryRestoreWithoutRepayment() throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    assertEquals(0, player.getQuestManager().claim(6029857).retcode().getVal());
    assertEquals(Map.of(1, 20, 213, 4), player.delivered);
    assertTrue(QuestCompletionRecords.closed(player, 6029857));
    restore(player.snapshot);
    assertTrue(QuestCompletionRecords.closed(player, 6029857));
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getQuestManager().claim(6029857).retcode());
    assertEquals(1, player.grants);
    GameplayDefinitions.windows.clear();
    assertNull(ActivityKraterQuests.state(player, 6029857));
    assertTrue(QuestCompletionRecords.closed(player, 6029857));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, player.getQuestManager().claim(6029857).retcode());
    assertEquals(1, player.grants);
  }

  @Test
  void historicalPaidLevelSatisfiesTheOriginalPredicateWhenWindowOpens() throws Exception {
    GameplayDefinitions.windows.clear();
    stock(1300);
    assertEquals(0, submit(actor()));
    assertNull(player.getGameplay().getKraterOffering().getQuest());
    assertFalse(QuestCompletionRecords.completed(player, 6029857));
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
    assertTrue(ActivityKraterQuests.refresh(player));
    assertTrue(QuestCompletionRecords.completed(player, 6029857));
    assertEquals(1, player.debits);
  }

  @Test
  void copiedLegacyQuestCounterCannotCompleteOrPayTheOwnedGoal() {
    var forged = new QuestState(6029857);
    forged.advance(1, 1, 1);
    player.getQuestStates().put(6029857, forged);
    assertFalse(QuestCompletionRecords.completed(player, 6029857));
    assertNotEquals(Retcode.SUCC, player.getQuestManager().claim(6029857).retcode());
    assertEquals(0, player.grants);
    assertEquals(QuestState.Phase.DOING, ActivityKraterQuests.state(player, 6029857).getPhase());
  }

  @Test
  void sameSizeAndTimestampSourceChangeCannotReuseAnApprovedCache() throws Exception {
    assertNotNull(KraterOfferingRules.load());
    var path = root.resolve("ExcelOutput/OfferingTypeConfig.json");
    var stamp = Files.getLastModifiedTime(path);
    var bytes = Files.readAllBytes(path);
    var table = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonArray();
    for (var raw : table)
      if (raw.getAsJsonObject().get("ID").getAsInt() == 6)
        raw.getAsJsonObject().addProperty("MaxLevel", 49);
    Files.writeString(path, gson.toJson(table));
    assertEquals(bytes.length, Files.size(path));
    Files.setLastModifiedTime(path, stamp);
    assertNull(KraterOfferingRules.load());
    stock(1300);
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), submit(actor()));
    assertEquals(0, player.debits);
  }

  @Test
  void alteredLoadedRewardCannotBypassTheSourceRecipe() throws Exception {
    assertNotNull(KraterOfferingRules.load());
    GameData.getRewardExcelMap().get(119106).getRewards().add(1, 1);
    stock(1300);
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), submit(actor()));
    assertEquals(0, player.debits);
  }

  @Test
  void originalNpcCannotServeAnIncorrectEntryOrMovedActor() throws Exception {
    stock(1300);
    player.scene.setEntryId(1040202);
    assertNotEquals(0, submit(actor()));
    player.scene.setEntryId(1040201);
    player.scene.getEntityById(actor()).getPos().setX(999);
    assertNotEquals(0, submit(actor()));
    assertEquals(0, player.debits);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "owner",
        "entity",
        "dimension",
        "runtime",
        "event",
        "stockAfter",
        "before",
        "amount"
      })
  void corruptPersistedPaymentIsRejected(String key) throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    var json = new Gson().toJsonTree(player.getGameplay().getKraterOffering()).getAsJsonObject();
    var payment = json.getAsJsonArray("payments").get(0).getAsJsonObject();
    if (key.equals("event")) payment.addProperty(key, "not-a-receipt");
    else
      payment.addProperty(
          key,
          key.equals("stockAfter") || key.equals("before")
              ? 1
              : key.equals("dimension") || key.equals("owner") ? 999999 : 0);
    player.getGameplay().kraterOffering(new Gson().fromJson(json, KraterOfferingState.class));
    assertEquals(Retcode.PLAYER_DATA_ERROR, KraterOfferings.snapshot(player).code());
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), claim(6));
    assertEquals(0, player.grants);
  }

  @Test
  void corruptedRewardRecipeAndGoalAcceptanceCannotBeRestored() throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    assertEquals(0, claim(6));
    var clean = new Gson().toJsonTree(player.getGameplay().getKraterOffering()).getAsJsonObject();
    var changed = clean.deepCopy();
    changed
        .getAsJsonArray("claims")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("items")
        .addProperty("2", 30001);
    player.getGameplay().kraterOffering(new Gson().fromJson(changed, KraterOfferingState.class));
    assertEquals(Retcode.PLAYER_DATA_ERROR, KraterOfferings.snapshot(player).code());
    changed = clean.deepCopy();
    changed.getAsJsonObject("goal").addProperty("acceptance", UUID.randomUUID().toString());
    player.getGameplay().kraterOffering(new Gson().fromJson(changed, KraterOfferingState.class));
    assertFalse(QuestCompletionRecords.completed(player, 6029857));
    assertEquals(Retcode.PLAYER_DATA_ERROR, KraterOfferings.snapshot(player).code());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 7, 51, 52})
  void unearnedAndInvalidLevelsCannotPay(int level) throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    assertNotEquals(0, claim(level));
    assertEquals(0, player.grants);
  }

  @Test
  void repeatedLevelInOneBatchIsRejectedWithoutReservation() throws Exception {
    stock(1300);
    assertEquals(0, submit(actor()));
    assertNotEquals(0, claim(6, 6));
    assertEquals(0, player.grants);
    assertTrue(player.getGameplay().getKraterOffering().getClaims().isEmpty());
  }

  @Test
  void maximumPlayerLevelConvertsTheOriginalExperienceOnlyOnce() throws Exception {
    player.setLevel(emu.lunarcore.GameConstants.MAX_TRAILBLAZER_LEVEL);
    stock(1300);
    assertEquals(0, submit(actor()));
    assertEquals(0, claim(6));
    assertEquals(Map.of(2, 32000, 223, 3, 18007, 1), player.delivered);
    restore(player.snapshot);
    assertNotEquals(0, claim(6));
    assertEquals(1, player.grants);
  }

  @Test
  void chestFinderPileLimitRejectsTwoOriginalRewardItems() {
    var resident = new InventoryGrantDeltaTest.Resident();
    var item =
        new GameItem(GameData.getItemExcelMap().get(408905), 1) {
          public void save() {}
        };
    item.setCount(2);
    assertEquals(2, item.getCount(), "Constructor clamps counts before inventory validation");
    assertFalse(resident.inventory.addItem(item));
    assertNull(resident.inventory.getMaterialByItemId(408905));
  }
}
