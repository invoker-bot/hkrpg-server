package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.tabs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.PlayerUnlockData;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetAvatarDataScRsp;
import org.hkrpg.server.protocol.ProtocolBridge;
import com.google.protobuf.DynamicMessage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pinned formal characters and material costs; failure cases deliberately damage one boundary. */
class AvatarMilestonesTest {
  final DailyUpgradeTest f = new DailyUpgradeTest();
  JsonObject fixture;
  GameItem eidolon, ticket;

  @BeforeEach void setup() throws Exception {
    f.setup();
    try (var input = getClass().getResourceAsStream("/fixtures/avatar-milestones-4.6.json")) {
      fixture = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    for (var file : fixture.getAsJsonArray("files")) {
      String name = file.getAsJsonObject().get("path").getAsString();
      for (var record : file.getAsJsonObject().getAsJsonArray("rows")) {
        var row = record.getAsJsonObject().getAsJsonObject("row").deepCopy();
        if (name.equals("ExcelOutput/AvatarConfig.json")) {
          row.addProperty("AvatarName", row.getAsJsonObject("AvatarName").get("Hash").getAsLong());
          var excel = f.gson.fromJson(row, AvatarExcel.class);
          f.install(GameData.getAvatarExcelMap(), excel); excel.onLoad();
        } else if (name.endsWith("AvatarRankConfig.json")) {
          f.install(GameData.getAvatarRankExcelMap(), f.gson.fromJson(row, AvatarRankExcel.class));
        } else if (name.contains("ItemConfig")) {
          row.addProperty("ItemName", row.getAsJsonObject("ItemName").get("Hash").getAsLong());
          f.install(GameData.getItemExcelMap(), f.gson.fromJson(row, ItemExcel.class));
        } else if (name.endsWith("MultiplePathAvatarConfig.json")) {
          f.install(GameData.getMultiplePathAvatarExcelMap(), f.gson.fromJson(row, MultiplePathAvatarExcel.class));
        } else if (name.endsWith("RewardData.json")) {
          var excel = f.gson.fromJson(row, RewardExcel.class); excel.onLoad();
          f.install(GameData.getRewardExcelMap(), excel);
        } else if (name.endsWith("AvatarPromotionReward.json")) {
          f.install(GameData.getAvatarPromotionRewardExcelMap(), f.gson.fromJson(row, AvatarPromotionRewardExcel.class));
        }
      }
    }
    f.prepare(2100107);
    f.avatar.setPromotion(1);
    eidolon = f.item(11202, 2);
    ticket = f.item(101, 4);
  }

  @AfterEach void cleanup() { f.cleanup(); }

  @Test void fullTicketStackDoesNotMarkPromotionRewardClaimed() {
    ticket.setCount(ticket.getExcel().getPileLimit());
    assertNull(f.service.takePromotionRewardAvatar(f.player, 1202, 1));
    assertFalse(f.avatar.hasTakenReward(1));
    assertEquals(99999, ticket.getCount());
  }

  @Test void detachedOrTrialLookupCannotClaimFormalPromotionReward() {
    f.player.getAvatars().getAvatars().remove(1202);
    f.detachedActorLookup = true;
    assertNull(f.service.takePromotionRewardAvatar(f.player, 1202, 1));
    assertFalse(f.avatar.hasTakenReward(1));
    assertEquals(4, ticket.getCount());
  }

  @Test void detachedOrTrialLookupCannotConsumeFormalEidolons() {
    f.player.getAvatars().getAvatars().remove(1202);
    f.detachedActorLookup = true;
    assertFalse(f.service.rankUpAvatar(f.player, 1202));
    assertEquals(0, f.avatar.getRank());
    assertEquals(2, eidolon.getCount());
  }

  @Test void foreignMaterialCannotGrantAnEidolonForFree() throws Exception {
    var owner = GameItem.class.getDeclaredField("ownerUid"); owner.setAccessible(true);
    owner.setInt(eidolon, f.player.getUid() + 1);
    assertFalse(f.service.rankUpAvatar(f.player, 1202));
    assertEquals(0, f.avatar.getRank());
    assertEquals(2, eidolon.getCount());
  }

  @Test void requestedInactivePathReceivesItsOwnRankAndCost() {
    var base = new GameAvatar(GameData.getAvatarExcelMap().get(8001)) { public void save() {} };
    base.setOwner(f.player); f.player.getAvatars().getAvatars().put(8001, base);
    for (int id : new int[]{8002,8004}) {
      var path = new AvatarMultiPath(f.player, GameData.getAvatarExcelMap().get(id)) { public void save() {} };
      f.player.getAvatars().getMultiPaths().put(id, path);
    }
    var active = f.player.getAvatars().getMultiPathById(8002);
    var requested = f.player.getAvatars().getMultiPathById(8004);
    base.setMultiPath(active);
    var destruction = f.item(18001, 2); var preservation = f.item(18003, 2);
    assertTrue(f.service.rankUpAvatar(f.player, 8004));
    assertEquals(1, requested.getRank());
    assertEquals(0, active.getRank());
    assertSame(active, base.getMultiPath());
    assertEquals(2, destruction.getCount()); assertEquals(1, preservation.getCount());
  }

  @ParameterizedTest @ValueSource(strings={"missing", "empty", "wrong-rank"})
  void invalidRankResourceNeverConsumesMaterials(String mode) {
    var row = f.gson.toJsonTree(GameData.getAvatarRankExcelMap().get(120201)).getAsJsonObject();
    if (mode.equals("missing")) row.remove("UnlockCost");
    else if (mode.equals("empty")) row.add("UnlockCost", new JsonArray());
    else row.addProperty("Rank", 6);
    GameData.getAvatarRankExcelMap().add(f.gson.fromJson(row, AvatarRankExcel.class));
    assertFalse(f.service.rankUpAvatar(f.player, 1202));
    assertEquals(0, f.avatar.getRank()); assertEquals(2, eidolon.getCount());
  }

  @Test void promotionCanRetryAfterCapacityIsAvailableAndPaysOnlyOnce() {
    ticket.setCount(99999);
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT,AvatarMilestones.claimPromotion(f.player,1202,1).retcode());
    assertTrue(f.player.getGameplay().getRewards().isEmpty());
    ticket.setCount(99998);
    var claim=AvatarMilestones.claimPromotion(f.player,1202,1);
    assertEquals(Retcode.SUCC,claim.retcode()); assertEquals(1,claim.items().size());
    assertEquals(101,claim.items().getFirst().getItemId()); assertEquals(1,claim.items().getFirst().getCount());
    assertEquals(99999,ticket.getCount()); assertTrue(f.avatar.hasTakenReward(1));
    assertEquals(Retcode.PROMOTION_REWARD_ALREADY_TAKEN,AvatarMilestones.claimPromotion(f.player,1202,1).retcode());
    assertEquals(99999,ticket.getCount());
  }

  @ParameterizedTest @ValueSource(ints={0,2,3,5,6,32,Integer.MAX_VALUE})
  void unearnedOrUnconfiguredMilestonesDoNotGrant(int promotion) {
    assertNotEquals(Retcode.SUCC,AvatarMilestones.claimPromotion(f.player,1202,promotion).retcode());
    assertEquals(0,f.avatar.getRewards()); assertEquals(4,ticket.getCount());
  }

  @Test void missingRewardConfigCannotMarkAnEarnedMilestone() {
    GameData.getRewardExcelMap().keySet().remove(301);
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST,AvatarMilestones.claimPromotion(f.player,1202,1).retcode());
    assertFalse(f.avatar.hasTakenReward(1)); assertEquals(4,ticket.getCount());
  }

  @ParameterizedTest @ValueSource(strings={"empty","wrong","excess","zero","unique","duplicate","overflow"})
  void clientCostCannotOverrideTheRankResource(String kind) {
    var costs=switch(kind) {
      case "empty" -> List.<ItemParam>of();
      case "wrong" -> List.of(new ItemParam(101,1));
      case "excess" -> List.of(new ItemParam(11202,2));
      case "zero" -> List.of(new ItemParam(11202,0));
      case "unique" -> List.of(new ItemParam(ItemParam.ItemParamType.UNIQUE,eidolon.getInternalUid(),1));
      case "duplicate" -> List.of(new ItemParam(11202,1),new ItemParam(11202,1));
      default -> List.of(new ItemParam(11202,Integer.MAX_VALUE),new ItemParam(11202,1));
    };
    assertNotEquals(Retcode.SUCC,AvatarMilestones.activateRank(f.player,1202,costs));
    assertEquals(0,f.avatar.getRank()); assertEquals(2,eidolon.getCount());
    assertTrue(f.player.getGameplay().getAvatarRankChanges().isEmpty());
  }

  @Test void concurrentRankRequestsCannotSpendOneMaterialTwice() throws Exception {
    eidolon.setCount(1);
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var results=executor.invokeAll(List.of(
          ()->AvatarMilestones.activateRank(f.player,1202,List.of(new ItemParam(11202,1))),
          ()->AvatarMilestones.activateRank(f.player,1202,List.of(new ItemParam(11202,1)))));
      assertEquals(1,results.stream().filter(result->{try{return result.get()==Retcode.SUCC;}catch(Exception e){throw new AssertionError(e);}}).count());
    }
    assertEquals(1,f.avatar.getRank()); assertEquals(0,eidolon.getCount());
    assertEquals(AvatarRankChange.Phase.COMPLETE,f.player.getGameplay().getAvatarRankChanges().get(1202).getPhase());
  }

  @Test void concurrentPromotionRequestsPayOneTicket() throws Exception {
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var results=executor.invokeAll(List.of(
          ()->AvatarMilestones.claimPromotion(f.player,1202,1).retcode(),
          ()->AvatarMilestones.claimPromotion(f.player,1202,1).retcode()));
      assertEquals(1,results.stream().filter(result->{try{return result.get()==Retcode.SUCC;}catch(Exception e){throw new AssertionError(e);}}).count());
    }
    assertEquals(5,ticket.getCount()); assertTrue(f.avatar.hasTakenReward(1));
  }

  @ParameterizedTest @ValueSource(ints={1,3,5})
  void claimedCurrentPromotionIsPresentInSyncAndLoginSnapshots(int promotion) throws Exception {
    var p = new FailedPlayer();
    var avatar = owned(p);
    avatar.setPromotion(promotion);
    var tickets = stock(p,101,4);
    var expected = new ArrayList<Integer>();
    for (int milestone : List.of(1,3,5)) {
      if (milestone > promotion) break;
      assertEquals(Retcode.SUCC,AvatarMilestones.claimPromotion(p,1202,milestone).retcode());
      expected.add(milestone);
    }
    assertEquals(4 + expected.size(),tickets.getCount());
    var packet = p.packets.getLast();
    assertEquals(CmdId.PlayerSyncScNotify,packet.getCmdId());
    assertPromotionSnapshot(packet,"PlayerSyncScNotify",expected);
    assertPromotionSnapshot(new PacketGetAvatarDataScRsp(p),"GetAvatarDataScRsp",expected);
    assertEquals(Retcode.PROMOTION_REWARD_ALREADY_TAKEN,
        AvatarMilestones.claimPromotion(p,1202,promotion).retcode());
    assertEquals(4 + expected.size(),tickets.getCount());
  }

  @ParameterizedTest @ValueSource(ints={1,3,5})
  void unclaimedCurrentPromotionRemainsAvailableInLoginSnapshot(int promotion) throws Exception {
    var p = new FailedPlayer();
    var avatar = owned(p);
    avatar.setPromotion(promotion);
    stock(p,101,4);
    var expected = new ArrayList<Integer>();
    for (int milestone : List.of(1,3,5)) {
      if (milestone >= promotion) break;
      assertEquals(Retcode.SUCC,AvatarMilestones.claimPromotion(p,1202,milestone).retcode());
      expected.add(milestone);
    }
    assertFalse(avatar.hasTakenReward(promotion));
    assertPromotionSnapshot(new PacketGetAvatarDataScRsp(p),"GetAvatarDataScRsp",expected);
  }

  private void assertPromotionSnapshot(BasePacket packet,String name,List<Integer> expected)
      throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var wire = bridge.outbound(packet.getCmdId(),packet.getData().toByteArray());
    assertEquals(bridge.clientCommand(name),wire.cmdId());
    var snapshot = bridge.clientSchema().parse(name,wire.body());
    assertTrue(snapshot.getUnknownFields().asMap().isEmpty());
    if (name.equals("PlayerSyncScNotify")) {
      snapshot = (DynamicMessage) snapshot.getField(
          snapshot.getDescriptorForType().findFieldByName("avatar_sync"));
    }
    var avatars = snapshot.getDescriptorForType().findFieldByName("avatar_list");
    assertEquals(1,snapshot.getRepeatedFieldCount(avatars));
    var avatar = (DynamicMessage) snapshot.getRepeatedField(avatars,0);
    assertEquals(1202,avatar.getField(avatar.getDescriptorForType().findFieldByName("base_avatar_id")));
    assertEquals(expected,avatar.getField(
        avatar.getDescriptorForType().findFieldByName("has_taken_promotion_reward_list")));
  }

  static class FailedPlayer extends Player {
    int saves, failAt;
    boolean failAvatar, failGrant;
    final List<BasePacket> packets = new ArrayList<>();
    final PlayerUnlockData unlocks = new PlayerUnlockData(this) { @Override public void save() {} };
    final Inventory inventory=new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap amounts,boolean hint) {
        return failGrant ? new GrantResult(List.of(),false) : super.grantItems(amounts,hint);
      }
    };
    @Override public Inventory getInventory() { return inventory; }
    @Override public PlayerUnlockData getUnlocks() { return unlocks; }
    @Override public void save() { if(++saves==failAt) throw new IllegalStateException("injected save failure"); }
    @Override public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  GameAvatar owned(FailedPlayer p) {
    var avatar=new GameAvatar(GameData.getAvatarExcelMap().get(1202)) {
      @Override public void save() { if(p.failAvatar) throw new IllegalStateException("injected avatar save"); }
    };
    avatar.setOwner(p); avatar.setPromotion(1); p.getAvatars().getAvatars().put(1202,avatar);
    return avatar;
  }

  GameItem stock(Player p,int id,int count) {
    var item=new GameItem(GameData.getItemExcelMap().get(id),count) { @Override public void save() {} };
    item.setOwner(p); p.getInventory().getItems().put(item.getInternalUid(),item);
    p.getInventory().getTabByItemType(item.getItemMainType()).onAddItem(item);
    return item;
  }

  @Test void partialDeliveryDoesNotBecomeClaimedOrAutomaticallyGrantAgain() {
    var p=new FailedPlayer(); var avatar=owned(p); var tickets=stock(p,101,4); p.failGrant=true;
    assertEquals(Retcode.PLAYER_DATA_ERROR,AvatarMilestones.claimPromotion(p,1202,1).retcode());
    assertFalse(avatar.hasTakenReward(1)); assertEquals(4,tickets.getCount());
    p.failGrant=false;
    assertEquals(Retcode.SYSTEM_BUSY,AvatarMilestones.claimPromotion(p,1202,1).retcode());
    assertEquals(4,tickets.getCount());
  }

  @Test void deliveredRewardRepairsOnlyTheClaimFlagAfterAvatarSaveFailure() {
    var p=new FailedPlayer(); var avatar=owned(p); var tickets=stock(p,101,4); p.failAvatar=true;
    assertThrows(IllegalStateException.class,()->AvatarMilestones.claimPromotion(p,1202,1));
    assertFalse(avatar.hasTakenReward(1)); assertEquals(5,tickets.getCount());
    p.failAvatar=false;
    assertEquals(Retcode.PROMOTION_REWARD_ALREADY_TAKEN,AvatarMilestones.claimPromotion(p,1202,1).retcode());
    assertTrue(avatar.hasTakenReward(1)); assertEquals(5,tickets.getCount());
  }

  @ParameterizedTest @ValueSource(ints={1,2,3,4})
  void interruptedRankWritesCannotAutomaticallyConsumeAgain(int failure) {
    var p=new FailedPlayer(); var avatar=owned(p); var material=stock(p,11202,2);
    if(failure==4) p.failAvatar=true; else p.failAt=failure;
    assertThrows(IllegalStateException.class,()->AvatarMilestones.activateRank(p,1202,List.of(new ItemParam(11202,1))));
    int count=material.getCount(),rank=avatar.getRank(); p.failAt=0;p.failAvatar=false;
    assertEquals(Retcode.SYSTEM_BUSY,AvatarMilestones.activateRank(p,1202,List.of(new ItemParam(11202,1))));
    assertEquals(count,material.getCount()); assertEquals(rank,avatar.getRank());
  }

  @Test void missingNewStackSpaceDoesNotReservePromotionReward() {
    var p=new FailedPlayer(); var avatar=owned(p);
    p.getInventory().createTab(InventoryTabType.MATERIAL,new MaterialInventoryTab(0));
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT,AvatarMilestones.claimPromotion(p,1202,1).retcode());
    assertFalse(avatar.hasTakenReward(1)); assertTrue(p.getGameplay().getRewards().isEmpty());
  }

  @Test void rankPendingEvidenceSurvivesSerialization() {
    var p=new FailedPlayer(); owned(p); stock(p,11202,2);p.failAvatar=true;
    assertThrows(IllegalStateException.class,()->AvatarMilestones.activateRank(p,1202,List.of(new ItemParam(11202,1))));
    var saved=f.gson.fromJson(f.gson.toJson(p.getGameplay()),GameplayState.class).getAvatarRankChanges().get(1202);
    assertEquals(AvatarRankChange.Phase.DEBITED,saved.getPhase());
    assertEquals(Map.of(11202,1),saved.getCosts()); assertEquals(Map.of(11202,2),saved.getInventoryBefore());
    assertEquals(0,saved.getBeforeRank()); assertEquals(1,saved.getAfterRank());
  }
}
