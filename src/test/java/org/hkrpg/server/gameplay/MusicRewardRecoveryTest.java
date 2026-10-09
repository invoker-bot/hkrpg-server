package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.time.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;

class MusicRewardRecoveryTest {
  static final int LEVEL = 19996927, GROUP = 19996927, ITEM = 19996927, REWARD = 19996928;
  final Gson gson = new Gson();
  ActivityPanelExcel previousPanel;
  GameplayDefinitions.Window window;
  Performer player;
  MusicManager manager;

  static class Performer extends Player {
    int granted, calls, failCall;
    final Inventory inventory =
        new Inventory(this) {
          public GrantResult grantItems(ItemParamMap map, boolean hint) {
            calls++;
            if (calls == failCall) return new GrantResult(List.of(), false);
            granted += map.get(ITEM);
            var items = new ArrayList<GameItem>();
            for (var entry : map)
              items.add(
                  new GameItem(
                      GameData.getItemExcelMap().get(entry.getIntKey()), entry.getIntValue()));
            return new GrantResult(items, true);
          }
        };

    public int getLevel() {
      return 30;
    }

    public Inventory getInventory() {
      return inventory;
    }

    public void save() {}

    public void sendPacket(BasePacket packet) {}
  }

  void reward(int id, int quantity) {
    var resource =
        gson.fromJson(
            "{\"RewardID\":" + id + ",\"ItemID_1\":" + ITEM + ",\"Count_1\":" + quantity + "}",
            RewardExcel.class);
    resource.onLoad();
    GameData.getRewardExcelMap().add(resource);
  }

  @BeforeEach
  void prepare() {
    previousPanel = GameData.getActivityPanelExcelMap().get(50022);
    GameData.getActivityPanelExcelMap()
        .add(
            gson.fromJson(
                "{\"PanelID\":50022,\"UnlockConditions\":\"[PlayerLevel:21]\"}",
                ActivityPanelExcel.class));
    var now = Instant.parse("2026-10-01T00:00:00Z");
    window =
        new GameplayDefinitions.Window(
            50022, 0, now.minusSeconds(1).getEpochSecond(), now.plusSeconds(60).getEpochSecond());
    GameplayDefinitions.windows.add(window);
    GameplayDefinitions.musicLevels.put(
        LEVEL,
        new GameplayDefinitions.MusicLevel(
            LEVEL, GROUP, 1, List.of(100, 200, 300), List.of(REWARD, REWARD + 1, REWARD + 2)));
    GameplayDefinitions.musicGroups.put(
        GROUP, new GameplayDefinitions.MusicGroup(GROUP, 1, 0, List.of()));
    GameData.getItemExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":"
                    + ITEM
                    + ",\"ItemMainType\":\"Material\",\"ItemSubType\":\"Material\",\"PileLimit\":9999}",
                ItemExcel.class));
    for (int i = 0; i < 3; i++) reward(REWARD + i, i + 1);
    player = new Performer();
    player.getLoginActivityManager().setClock(Clock.fixed(now, ZoneOffset.UTC));
    manager = new MusicManager(player);
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.windows.remove(window);
    GameplayDefinitions.musicLevels.remove(LEVEL);
    GameplayDefinitions.musicGroups.remove(GROUP);
    GameData.getItemExcelMap().keySet().remove(ITEM);
    for (int i = 0; i < 3; i++) GameData.getRewardExcelMap().keySet().remove(REWARD + i);
    if (previousPanel == null) GameData.getActivityPanelExcelMap().keySet().remove(50022);
    else GameData.getActivityPanelExcelMap().add(previousPanel);
  }

  @Test
  void missingRewardCannotBeginAPlayableRunThenLoseItsSettlementContext() {
    GameData.getRewardExcelMap().keySet().remove(REWARD);
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST, manager.start(LEVEL));
    assertEquals(0, player.getGameplay().getRhythm().getActiveLevel());
    assertTrue(player.getGameplay().getRhythm().getTransaction().isEmpty());
    assertEquals(0, player.calls);
  }

  @Test
  void rewardsUseTheQuantityCapturedBeforeThePlayerStarted() {
    assertEquals(Retcode.SUCC, manager.start(LEVEL));
    reward(REWARD, 999);
    assertEquals(Retcode.SUCC, manager.finish(1, false));
    assertEquals(1, player.granted);
    assertTrue(player.getRewardLedger().granted("music:" + LEVEL + ":1"));
  }

  @Test
  void failedSecondGrantRetainsTheRunWithoutPublishingACompletedRatingOrRegrantingFirstReward() {
    assertEquals(Retcode.SUCC, manager.start(LEVEL));
    String transaction = player.getGameplay().getRhythm().getTransaction();
    player.failCall = 2;
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.finish(2, false));
    assertEquals(LEVEL, player.getGameplay().getRhythm().getActiveLevel());
    assertEquals(transaction, player.getGameplay().getRhythm().getTransaction());
    assertEquals(0, player.getGameplay().getRhythm().getStars().getOrDefault(LEVEL, 0));
    assertTrue(player.getRewardLedger().granted("music:" + LEVEL + ":1"));
    assertFalse(player.getRewardLedger().granted("music:" + LEVEL + ":2"));
    assertEquals(Retcode.SYSTEM_BUSY, manager.finish(2, false));
    assertEquals(1, player.granted);
    assertEquals(2, player.calls);
  }

  class Session extends GameSession {
    DynamicMessage response;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }

    int code() {
      return (Integer)
          response.getField(response.getDescriptorForType().findFieldByName("retcode"));
    }
  }

  @Test
  void nativeWideRatingCannotAliasThreeStarsAndSpendFrozenRewards() throws Exception {
    assertEquals(Retcode.SUCC, manager.start(LEVEL));
    var session = new Session();
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("MusicRhythmFinishLevelCsReq"),
            new byte[] {8, (byte) 131, (byte) 128, (byte) 128, (byte) 128, 16}));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), session.code());
    assertEquals(LEVEL, player.getGameplay().getRhythm().getActiveLevel());
    assertEquals(0, player.calls);
  }

  @Test
  void nativeDuplicateLevelCannotBeginARunThroughLastValueWinsParsing() throws Exception {
    var session = new Session();
    var bytes = new java.io.ByteArrayOutputStream();
    var output = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    output.writeUInt32(6, LEVEL);
    output.writeUInt32(6, LEVEL);
    output.flush();
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("MusicRhythmStartLevelCsReq"),
            bytes.toByteArray()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), session.code());
    assertEquals(0, player.getGameplay().getRhythm().getActiveLevel());
  }

  @Test
  void nativeNonBooleanComboCannotPublishAFullCombo() throws Exception {
    assertEquals(Retcode.SUCC, manager.start(LEVEL));
    var session = new Session();
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("MusicRhythmFinishLevelCsReq"),
            new byte[] {8, 1, 56, 2}));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), session.code());
    assertFalse(player.getGameplay().getRhythm().getFullCombos().contains(LEVEL));
    assertEquals(0, player.calls);
  }

  @Test
  void interruptedResultCannotChangeItsRatingOrComboToSkipAnUncertainReward() {
    assertEquals(Retcode.SUCC, manager.start(LEVEL));
    player.failCall = 2;
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.finish(2, false));
    assertTrue(player.getGameplay().getRhythm().isAwaitingRewards());
    assertEquals(2, player.getGameplay().getRhythm().getPendingRating());
    assertEquals(Retcode.REQ_PARA_INVALID, manager.finish(1, false));
    assertEquals(Retcode.REQ_PARA_INVALID, manager.finish(3, false));
    assertEquals(Retcode.REQ_PARA_INVALID, manager.finish(2, true));
    assertEquals(Retcode.MUSIC_LOCKED, manager.start(LEVEL));
    assertEquals(1, player.granted);
    assertEquals(2, player.calls);
    assertTrue(player.getGameplay().getRhythm().getStars().isEmpty());
  }

  @Test
  void actualMorphiaReloadPreservesFrozenQuotesWithoutRequiringOldRewardRows() throws Exception {
    assertEquals(Retcode.SUCC, manager.start(LEVEL));
    String transaction = player.getGameplay().getRhythm().getTransaction();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "music-reward-frozen");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456798);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, player.getGameplay());
      store.save(document);
      gameplay.set(player, store.find(Player.class).first().getGameplay());
      for (int i = 0; i < 3; i++) GameData.getRewardExcelMap().keySet().remove(REWARD + i);
      assertEquals(transaction, player.getGameplay().getRhythm().getTransaction());
      assertEquals(Retcode.SUCC, manager.finish(2, false));
      assertEquals(3, player.granted);
      assertEquals(2, player.getGameplay().getRhythm().getStars().get(LEVEL));
      assertEquals(0, player.getGameplay().getRhythm().getActiveLevel());
      assertEquals(
          Retcode.SUCC,
          player.getGameplay().getRewards().get("music:" + LEVEL + ":1").isGranted()
              ? Retcode.SUCC
              : Retcode.SYSTEM_BUSY);
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void legacyMissingAndCorruptQuotesRemainPendingWithoutManufacturingRewards() {
    player.getGameplay().getRhythm().start(LEVEL);
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.finish(1, false));
    assertEquals(LEVEL, player.getGameplay().getRhythm().getActiveLevel());
    player.getGameplay().getRhythm().finish(0, false);
    assertEquals(Retcode.SUCC, manager.start(LEVEL));
    player.getGameplay().getRhythm().getStarRewards().get(1).put(ITEM, -1);
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.finish(1, false));
    assertEquals(LEVEL, player.getGameplay().getRhythm().getActiveLevel());
    assertFalse(player.getGameplay().getRhythm().isAwaitingRewards());
    assertEquals(0, player.calls);
    assertTrue(player.getGameplay().getRewards().isEmpty());
  }
}
