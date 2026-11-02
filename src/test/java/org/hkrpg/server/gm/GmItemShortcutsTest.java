package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GmItemShortcutsTest {
  @TempDir Path directory;
  final Map<Integer,ItemExcel> previous=new HashMap<>();
  final List<CatalogItem> catalog=new ArrayList<>();
  void item(int id,String main,String sub,String visibility) {
    previous.put(id,GameData.getItemExcelMap().get(id));
    GameData.getItemExcelMap().add(new Gson().fromJson("{\"ID\":"+id+",\"ItemMainType\":\""+main
        +"\",\"ItemSubType\":\""+sub+"\",\"PileLimit\":99"+visibility+"}",ItemExcel.class));
    catalog.add(new CatalogItem(id,"条目 "+id,"item",4,0,0));
  }
  void source(String file,String json) throws Exception {
    var path=directory.resolve(file);Files.createDirectories(path.getParent());Files.writeString(path,json);
  }
  @AfterEach void restore() {previous.forEach((id,row)->{if(row==null)GameData.getItemExcelMap().keySet().remove(id);else GameData.getItemExcelMap().add(row);});}

  @Test void classifiesVisibilityScheduleActivityAndCollectiblesWithExplicitEvidence() throws Exception {
    item(199868001,"Material","Material","");
    item(199868002,"Material","Material",",\"isVisible\":true");
    item(221002,"Usable","PhoneTheme",",\"isVisible\":true");
    item(254001,"Usable","PhoneCase",",\"isVisible\":true");
    item(200001,"Usable","HeadIcon",",\"isVisible\":true");
    item(199868003,"Usable","Unknown",",\"isVisible\":false");
    source("ExcelOutput/ItemComeFrom.json","[{\"ID\":199868002,\"Desc\":{\"Hash\":18446744073709551610}},{\"ID\":200001,\"Desc\":9}]");
    source("TextMap/TextMapCHS.json","{\"18446744073709551610\":\"活动获取\",\"9\":\"<color=red>无名客限定奖励</color>\"}");
    source("ExcelOutput/PhoneThemeConfig.json","[{\"ID\":221002,\"ShowType\":\"UnlockedOnly\"}]");
    source("ExcelOutput/PhoneCaseConfig.json","[{\"CaseID\":254001,\"ShowType\":\"InSchedule\"}]");
    var rows=GmItemShortcuts.load(directory,catalog);
    var byId=new HashMap<Integer,ItemShortcut>();rows.forEach(row->byId.put(row.item().id(),row));
    assertEquals(6,rows.size());
    assertTrue(byId.get(199868001).tags().contains("hidden"));
    assertEquals(List.of("activity"),byId.get(199868002).tags());
    assertEquals("theme",byId.get(221002).type());assertTrue(byId.get(221002).tags().contains("hidden"));
    assertTrue(byId.get(254001).tags().contains("limited"));assertFalse(byId.get(254001).tags().contains("hidden"));
    assertTrue(byId.get(200001).sources().contains("无名客限定奖励"));
    assertTrue(byId.get(200001).sources().contains("收藏类型：头像"));
    assertTrue(byId.get(200001).grantable());assertEquals(1,byId.get(200001).maxAmount());
    assertFalse(byId.get(199868003).grantable());
    assertEquals(rows,GmItemShortcuts.load(directory,catalog));
    assertFalse(Files.exists(directory.resolve("data")));
  }

  @Test void omittedVisibilityDoesNotMislabelAvatarCardsAndPermanentUnitsAsHiddenOrLimited() throws Exception {
    item(1001,"AvatarCard","AvatarCard","");
    item(1102,"AvatarCard","AvatarCard","");
    item(199868002,"Material","Material",",\"isVisible\":true");
    var rows=GmItemShortcuts.load(directory,catalog);
    assertEquals(1,rows.size());assertEquals(1102,rows.getFirst().item().id());
    assertTrue(rows.getFirst().tags().contains("event-warp"));assertFalse(rows.getFirst().tags().contains("hidden"));
    assertFalse(rows.getFirst().sources().isEmpty());
  }

  @Test void brokenSourceDoesNotSilentlyReturnAnIncompleteCatalog() throws Exception {
    source("ExcelOutput/ItemComeFrom.json","{broken");
    assertThrows(java.io.IOException.class,()->GmItemShortcuts.load(directory,catalog));
  }

  @Test void characterClothingShowsTheOriginalOwningAvatar() throws Exception {
    int id=199868005;var old=GameData.getAvatarSkinExcelMap().get(id);
    try{
      item(id,"Usable","AvatarSkin",",\"isVisible\":true");
      item(1001,"AvatarCard","AvatarCard",",\"isVisible\":true");
      catalog.set(catalog.size()-1,new CatalogItem(1001,"三月七","avatar",4,80,6));
      GameData.getAvatarSkinExcelMap().add(new Gson().fromJson("{\"ID\":"+id+",\"AvatarID\":1001}",emu.lunarcore.data.excel.AvatarSkinExcel.class));
      var rows=GmItemShortcuts.load(directory,catalog);
      var skin=rows.stream().filter(r->r.item().id()==id).findFirst().orElseThrow();
      assertEquals("skin",skin.type());assertTrue(skin.grantable());
      assertTrue(skin.sources().contains("对应角色：三月七 #1001"),skin.sources().toString());
    }finally{
      GameData.getAvatarSkinExcelMap().keySet().remove(id);if(old!=null)GameData.getAvatarSkinExcelMap().add(old);
    }
  }

  @Test void fixedShopGoodsAreSearchableByShopNameWithoutAcquisitionTextOrNewRarityClaims() throws Exception {
    int shopId=108;var old=GameData.getShopExcelMap().get(shopId);
    try{
      item(199868006,"Usable","PlayerOutfit",",\"isVisible\":true");
      item(199868007,"Usable","PersonalCard",",\"isVisible\":true");
      item(199868008,"Material","Material",",\"isVisible\":true");
      var shop=new Gson().fromJson("{\"ShopID\":108,\"ShopName\":-2}",emu.lunarcore.data.excel.ShopExcel.class);
      GameData.getShopExcelMap().add(shop);
      for(int n=0;n<3;n++){
        var goods=new Gson().fromJson("{\"GoodsID\":"+(108001+n)+",\"ItemID\":"+(199868006+n)
            +",\"ItemCount\":1,\"ShopID\":108}",emu.lunarcore.data.excel.ShopGoodsExcel.class);
        goods.onLoad();
      }
      source("TextMap/TextMapCHS.json","{\"18446744073709551614\":\"星尘之礼\"}");
      var rows=GmItemShortcuts.load(directory,catalog);
      assertEquals(2,rows.size());
      for(var row:rows){
        assertEquals(List.of("collectible"),row.tags());
        assertTrue(row.sources().stream().anyMatch(s->s.contains("星尘之礼")&&s.contains("#108")),row.sources().toString());
        assertTrue(GmItemShortcuts.matches(row,"星尘之礼"));
      }
      assertEquals(rows,GmItemShortcuts.load(directory,catalog));
    }finally{
      GameData.getShopExcelMap().keySet().remove(shopId);if(old!=null)GameData.getShopExcelMap().add(old);
    }
  }

  @Test void anUnknownResourceSubtypeStaysQueryOnlyWithoutBreakingTheCatalog() throws Exception {
    item(199868004,"Usable","FutureUnknownType",",\"isVisible\":false");
    var rows=GmItemShortcuts.load(directory,catalog);
    assertEquals(1,rows.size());assertFalse(rows.getFirst().grantable());
  }
}
