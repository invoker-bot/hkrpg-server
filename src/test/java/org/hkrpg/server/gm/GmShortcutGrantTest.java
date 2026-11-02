package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.player.*;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.server.packet.BasePacket;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.*;

class GmShortcutGrantTest {
  final Map<Integer,ItemExcel> previous=new HashMap<>();
  final LiveGmBackend backend=new LiveGmBackend();
  static class Resident extends Player {
    final Inventory inventory=new Inventory(this);
    final PlayerUnlockData unlocks=new PlayerUnlockData(this){public void save(){}};
    public Inventory getInventory(){return inventory;}
    public PlayerUnlockData getUnlocks(){return unlocks;}
    public String getName(){return "Shortcut regression";}
    public void sendPacket(BasePacket packet){}
    public void save(){}
  }
  ItemShortcut item(int id,String main,String sub,int limit) {
    previous.put(id,GameData.getItemExcelMap().get(id));
    var row=new Gson().fromJson("{\"ID\":"+id+",\"ItemMainType\":\""+main+"\",\"ItemSubType\":\""+sub+"\",\"PileLimit\":"+limit+"}",ItemExcel.class);
    GameData.getItemExcelMap().add(row);
    return new ItemShortcut(new CatalogItem(id,"回归物品","item",4,0,0),"other",List.of("hidden"),List.of("回归来源"),GmItemShortcuts.grantable(row),"",GmItemShortcuts.maxAmount(row));
  }
  Operation op(int id,int amount){return GmValidationTest.parse("\"action\":\"shortcut-grant\",\"uid\":10001,\"itemId\":"+id+",\"amount\":"+amount);}
  @AfterEach void restore(){previous.forEach((id,row)->{if(row==null)GameData.getItemExcelMap().keySet().remove(id);else GameData.getItemExcelMap().add(row);});}

  @Test void collectibleGrantUnlocksExactlyOnceAndRejectsDuplicatesAndBatches() {
    var entry=item(199867001,"Usable","PhoneTheme",1);var p=new Resident();
    assertThrows(GmError.class,()->backend.grantShortcut(p,op(199867001,2),entry));
    assertFalse(p.unlocks.getPhoneThemes().contains(199867001));
    assertEquals("completed",backend.grantShortcut(p,op(199867001,1),entry).state());
    assertTrue(p.unlocks.getPhoneThemes().contains(199867001));
    assertThrows(GmError.class,()->backend.grantShortcut(p,op(199867001,1),entry));
  }

  @Test void hiddenMaterialUsesRealInventoryAndChecksTheWholeGrantBeforeWriting() {
    var entry=item(199867002,"Material","Material",5);var p=new Resident();
    assertEquals("completed",backend.grantShortcut(p,op(199867002,3),entry).state());
    assertEquals(3,p.inventory.getMaterialByItemId(199867002).getCount());
    assertThrows(GmError.class,()->backend.grantShortcut(p,op(199867002,3),entry));
    assertEquals(3,p.inventory.getMaterialByItemId(199867002).getCount());
  }

  @Test void unsupportedAndMismatchedEntriesCannotBePostedAroundTheDisabledButton() {
    var entry=item(199867003,"Usable","Unknown",5);var p=new Resident();
    assertFalse(entry.grantable());
    assertThrows(GmError.class,()->backend.grantShortcut(p,op(199867003,1),entry));
    assertThrows(GmError.class,()->backend.grantShortcut(p,op(199867004,1),entry));
    assertNull(p.inventory.getMaterialByItemId(199867003));
  }

  @Test void aFrameWithoutNativeUnlockSyncIsRejectedBeforeChangingUnlockState() {
    var entry=item(226005,"Usable","HeadIconFrame",1);var p=new Resident();
    assertFalse(entry.grantable(),"4.6 has no mapped unlocked frame list; do not acknowledge a broken grant");
    assertThrows(GmError.class,()->backend.grantShortcut(p,op(226005,1),entry));
    assertTrue(p.unlocks.getHeadIconFrames().isEmpty());
  }
}
