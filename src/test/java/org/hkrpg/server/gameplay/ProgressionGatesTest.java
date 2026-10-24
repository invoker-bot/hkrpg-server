package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.GameDepot;
import emu.lunarcore.data.excel.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pinned source rows, with deliberate corruptions only in rejection cases. */
class ProgressionGatesTest {
  final DailyUpgradeTest f = new DailyUpgradeTest();
  JsonObject fixture;

  @BeforeEach void setup() throws Exception {
    f.setup();
    var cache = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    f.restorations.add(() -> {
      GameDepot.getAvatarSkillTreeExcels().clear();
      GameDepot.getAvatarSkillTreeExcels().putAll(cache);
    });
    try (var in = getClass().getResourceAsStream("/fixtures/progression-gates-4.6.json")) {
      fixture = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    var files = new ArrayList<>(fixture.getAsJsonArray("files").asList());
    files.sort(Comparator.comparingInt(file -> file.getAsJsonObject().get("path").getAsString().equals("ExcelOutput/AvatarConfig.json") ? 0 : 1));
    for (var file : files) {
      String name = file.getAsJsonObject().get("path").getAsString();
      for (var record : file.getAsJsonObject().getAsJsonArray("rows")) {
        var raw = record.getAsJsonObject().getAsJsonObject("row").deepCopy();
        if (name.endsWith("AvatarPromotionConfig.json"))
          f.install(GameData.getAvatarPromotionExcelMap(), f.gson.fromJson(raw, AvatarPromotionExcel.class));
        else if (name.endsWith("EquipmentPromotionConfig.json")) {
          var row = f.gson.fromJson(raw, EquipmentPromotionExcel.class); row.onLoad();
          f.install(GameData.getEquipmentPromotionExcelMap(), row);
        } else if (name.equals("ExcelOutput/AvatarConfig.json")) {
          raw.addProperty("AvatarName", raw.getAsJsonObject("AvatarName").get("Hash").getAsLong());
          var row = f.gson.fromJson(raw, AvatarExcel.class);
          f.install(GameData.getAvatarExcelMap(), row); row.onLoad();
        } else if (name.endsWith("MultiplePathAvatarConfig.json"))
          f.install(GameData.getMultiplePathAvatarExcelMap(), f.gson.fromJson(raw, MultiplePathAvatarExcel.class));
        else if (name.endsWith("AvatarSkillTreeConfig.json")) {
          var row = f.gson.fromJson(raw, AvatarSkillTreeExcel.class);
          f.install(GameData.getAvatarSkillTreeExcelMap(), row); row.onLoad();
        } else if (name.endsWith("ItemConfig.json")) {
          raw.addProperty("ItemName", raw.getAsJsonObject("ItemName").get("Hash").getAsLong());
          f.install(GameData.getItemExcelMap(), f.gson.fromJson(raw, ItemExcel.class));
        }
      }
    }
    f.prepare(2100110);
    f.player.setLevel(15);
    f.avatar.setLevel(20);
    f.target.setLevel(20);
    for (var record : fixture.getAsJsonArray("files").get(5).getAsJsonObject().getAsJsonArray("rows")) {
      int id=record.getAsJsonObject().getAsJsonObject("row").get("ID").getAsInt();
      if (id!=2) f.item(id,100);
    }
  }

  @AfterEach void cleanup() { f.cleanup(); }

  Map<String, Object> state() {
    var stock = new TreeMap<Integer,Integer>();
    for (var item : f.player.getInventory().getItems().values()) stock.put(item.getItemId(), item.getCount());
    return Map.of("coins",f.player.getScoin(),"stock",stock,"promotion",f.avatar.getPromotion(),
        "equipmentPromotion",f.target.getPromotion(),"tree",new HashMap<>(f.avatar.getSkillTree()));
  }

  @ParameterizedTest @ValueSource(strings={"promotion","level","prerequisite","detached","maximum"})
  void tracesRejectUnmetSourceGatesBeforeAnyCharge(String mode) {
    int anchor=2, level=2;
    switch(mode) {
      case "level" -> { anchor=17; level=1; f.avatar.setPromotion(6); f.avatar.setLevel(74); f.avatar.getSkillTree().put(16,1); f.player.addSCoin(200000); }
      case "prerequisite" -> { anchor=10; level=1; f.avatar.setPromotion(2); }
      case "detached" -> { f.avatar.setPromotion(2); f.player.getAvatars().getAvatars().remove(1202); f.detachedActorLookup=true; }
      case "maximum" -> { level=11; f.avatar.setPromotion(6); }
    }
    var before=state();
    assertFalse(f.service.unlockSkillTreeAvatar(f.player,1202,anchor,level));
    assertEquals(before,state());
  }

  @Test void allIntermediateTraceLevelsMustMeetTheirGates() {
    f.avatar.setPromotion(1);
    var before=state();
    assertFalse(f.service.unlockSkillTreeAvatar(f.player,1202,2,3));
    assertEquals(before,state());
  }

  @Test void validTraceUsesAuthoritativeCostAndCannotBeBoughtTwice() {
    f.avatar.setPromotion(1);
    int coins=f.player.getScoin();
    assertTrue(f.service.unlockSkillTreeAvatar(f.player,1202,2,2));
    assertEquals(2,f.avatar.getSkillTree().get(2));
    assertEquals(coins-2000,f.player.getScoin());
    assertEquals(98,f.player.getInventory().getItemByParam(new emu.lunarcore.data.common.ItemParam(113001,1)).getCount());
    var after=state();
    assertFalse(f.service.unlockSkillTreeAvatar(f.player,1202,2,2));
    assertEquals(after,state());
  }

  @Test void originalPrerequisiteEnablesItsDependentTrace() {
    f.avatar.setPromotion(2);
    assertTrue(f.service.unlockSkillTreeAvatar(f.player,1202,6,1));
    assertTrue(f.service.unlockSkillTreeAvatar(f.player,1202,10,1));
    assertEquals(1,f.avatar.getSkillTree().get(10));
  }

  @ParameterizedTest @ValueSource(strings={"avatar","equipment"})
  void missingNextPromotionCannotConsumeMaterials(String kind) {
    if (kind.equals("avatar")) GameData.getAvatarPromotionExcelMap().values().removeIf(r->r.getAvatarID()==1202 && r.getPromotion()==1);
    else GameData.getEquipmentPromotionExcelMap().values().removeIf(r->r.getEquipmentID()==20000 && r.getPromotion()==1);
    var before=state();
    assertFalse(promote(kind));
    assertEquals(before,state());
  }

  @ParameterizedTest @ValueSource(strings={"avatar","equipment"})
  void promotionChecksPlayerAndWorldGatesAndDebitsExactlyOnce(String kind) {
    f.player.setLevel(14);
    var before=state(); assertFalse(promote(kind)); assertEquals(before,state());
    f.player.setLevel(15);
    int coins=f.player.getScoin();
    assertTrue(promote(kind));
    assertEquals(coins-(kind.equals("avatar")?3200:3000),f.player.getScoin());
    var after=state(); assertFalse(promote(kind)); assertEquals(after,state());
    f.avatar.setLevel(30); f.target.setLevel(30);
    after=state(); assertFalse(promote(kind)); assertEquals(after,state());
  }

  @Test void trialAvatarCannotBePromotedWithOwnedMaterials() {
    f.player.getAvatars().getAvatars().remove(1202); f.detachedActorLookup=true;
    var before=state(); assertFalse(promote("avatar")); assertEquals(before,state());
  }

  boolean promote(String kind) {
    return kind.equals("avatar")? f.service.promoteAvatar(f.player,1202)
        :f.service.promoteEquipment(f.player,f.target.getInternalUid());
  }

  @ParameterizedTest @ValueSource(strings={"unknown","wrong-kind","foreign-owner","discarded"})
  void invalidProtectionBatchDoesNotPartiallyLockItsFirstItem(String mode) throws Exception {
    var other=f.item(mode.equals("wrong-kind")?31011:20000,1);
    if (mode.equals("foreign-owner")) {
      var field=emu.lunarcore.game.inventory.GameItem.class.getDeclaredField("ownerUid");
      field.setAccessible(true); field.setInt(other,f.player.getUid()+1);
    }
    if (mode.equals("discarded")) other.setDiscarded(true);
    var result=EquipmentProtection.lock(f.player,List.of(f.target.getInternalUid(),
        mode.equals("unknown")?Integer.MAX_VALUE:other.getInternalUid()),true,emu.lunarcore.game.enums.ItemMainType.Equipment);
    assertNotEquals(emu.lunarcore.server.packet.Retcode.SUCC,result);
    assertFalse(f.target.isLocked()); assertFalse(other.isLocked());
  }

  @Test void protectedDonorCannotBeConsumedUntilUnlocked() {
    var donor=f.item(20000,1);
    var kind=emu.lunarcore.game.enums.ItemMainType.Equipment;
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,EquipmentProtection.lock(f.player,List.of(donor.getInternalUid()),true,kind));
    var costs=List.of(new emu.lunarcore.data.common.ItemParam(emu.lunarcore.data.common.ItemParam.ItemParamType.UNIQUE,donor.getInternalUid(),1));
    assertFalse(f.service.rankUpEquipment(f.player,f.target.getInternalUid(),costs));
    assertEquals(1,donor.getCount());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,EquipmentProtection.lock(f.player,List.of(donor.getInternalUid()),false,kind));
    assertTrue(f.service.rankUpEquipment(f.player,f.target.getInternalUid(),costs));
    assertEquals(2,f.target.getRank());
  }

  @Test void concurrentTracePurchaseChargesOnce() throws Exception {
    f.avatar.setPromotion(1);
    int coins=f.player.getScoin();
    try (var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var results=workers.invokeAll(List.of(
          ()->f.service.unlockSkillTreeAvatar(f.player,1202,2,2),
          ()->f.service.unlockSkillTreeAvatar(f.player,1202,2,2)));
      assertEquals(1,results.stream().filter(r->{try{return Boolean.TRUE.equals(r.get());}catch(Exception e){throw new AssertionError(e);}}).count());
    }
    assertEquals(coins-2000,f.player.getScoin());
  }

  @Test void multiPathTraceUpdatesRequestedOwnedPathWithoutSwitchingActivePath() {
    var base=new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(8001)) {public void save(){}};
    base.setOwner(f.player); base.setLevel(20);
    f.player.getAvatars().getAvatars().put(8001,base);
    for(int id:List.of(8001,8002)) {
      var path=new emu.lunarcore.game.avatar.AvatarMultiPath(f.player,GameData.getAvatarExcelMap().get(id)) {public void save(){}};
      f.player.getAvatars().getMultiPaths().put(id,path);
    }
    var female=f.player.getAvatars().getMultiPathById(8002);
    var other=f.player.getAvatars().getMultiPathById(8001);
    base.setMultiPath(female);
    assertTrue(f.service.promoteAvatar(f.player,8001));
    assertEquals(1,base.getPromotion());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,ProgressionUpgrades.unlockPoint(f.player,8001002,2));
    assertEquals(2,other.getSkillTree().get(2));
    assertEquals(1,female.getSkillTree().get(2));
    assertSame(female,base.getMultiPath());
  }

  @ParameterizedTest @ValueSource(strings={"short-stock","duplicate-cost","overflow-cost","missing-cost","foreign-stock"})
  void invalidCostsCannotChangePromotionOrWallet(String mode) throws Exception {
    var current=GameData.getAvatarPromotionExcelMap().get(1202,0);
    var raw=f.gson.toJsonTree(current).getAsJsonObject();
    var material=f.player.getInventory().getMaterialByItemId(113001);
    switch(mode) {
      case "short-stock" -> material.setCount(3);
      case "duplicate-cost" -> { material.setCount(4); raw.getAsJsonArray("PromotionCostList").add(f.gson.toJsonTree(new emu.lunarcore.data.common.ItemParam(113001,4))); }
      case "overflow-cost" -> { material.setCount(Integer.MAX_VALUE); raw.add("PromotionCostList",JsonParser.parseString("[{\"id\":113001,\"count\":2147483647},{\"id\":113001,\"count\":2147483647}]")); }
      case "missing-cost" -> raw.remove("PromotionCostList");
      case "foreign-stock" -> { var field=emu.lunarcore.game.inventory.GameItem.class.getDeclaredField("ownerUid");field.setAccessible(true);field.setInt(material,f.player.getUid()+1); }
    }
    GameData.getAvatarPromotionExcelMap().add(f.gson.fromJson(raw,AvatarPromotionExcel.class));
    var before=state(); assertFalse(promote("avatar")); assertEquals(before,state());
  }
}
