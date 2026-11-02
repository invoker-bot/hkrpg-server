package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Reuses the seven original rows and actual native submission/debit/claim entrypoints. */
class ActivityReplayMaterial46Test {
  @TempDir Path root;
  MaterialSubmitActivity46Test fixture;
  @BeforeEach void prepare() throws Exception {
    fixture=new MaterialSubmitActivity46Test();fixture.root=root;fixture.prepare();
  }
  @AfterEach void restore() throws Exception {if(fixture!=null)fixture.restore();}
  void run(String id){
    var old=List.copyOf(GameplayDefinitions.windows);
    GameplayDefinitions.replaceActivityWindows(old.stream().map(w->new GameplayDefinitions.Window(w.panelId(),w.moduleId(),w.beginTime(),w.endTime(),id,"3.1-material-submit")).toList());
  }
  @Test void freshRerunCanCompleteAllSevenSubmissionsAndClaimWithoutErasingLegacyReceipts() throws Exception {
    assertEquals(0,fixture.code(fixture.query()));
    for(int id=301;id<=307;id++){
      assertEquals(0,fixture.code(fixture.submit(id)));assertEquals(0,fixture.code(fixture.take(id)));
    }
    var original=MaterialSubmitActivity46Test.GSON.toJson(fixture.player.getGameplay().getMaterialSubmission());
    var legacy=new HashMap<>(fixture.player.getGameplay().getRewards());
    String replay=UUID.randomUUID().toString();run(replay);
    var empty=fixture.query();assertEquals(0,fixture.code(empty));
    assertEquals(0,empty.getRepeatedFieldCount(empty.getDescriptorForType().findFieldByNumber(8)));
    for(int id=301;id<=307;id++){
      assertEquals(0,fixture.code(fixture.submit(id)));assertEquals(0,fixture.code(fixture.take(id)));
      assertNotEquals(0,fixture.code(fixture.take(id)));
    }
    assertEquals(14,fixture.player.grants);
    for(var entry:legacy.entrySet())assertSame(entry.getValue(),fixture.player.getGameplay().getRewards().get(entry.getKey()));
    var saved=MaterialSubmitActivity46Test.GSON.toJsonTree(fixture.player.getGameplay()).getAsJsonObject();
    assertEquals(original,MaterialSubmitActivity46Test.GSON.toJson(saved.get("materialSubmission")));
    assertEquals(7,fixture.player.getGameplay().getMaterialSubmission().receipts().size());
  }

  @Test void aStaleNativeWindowCannotSubmitIntoTheNextRerunBeforeQueryingItsData() throws Exception {
    run(UUID.randomUUID().toString());assertEquals(0,fixture.code(fixture.query()));
    assertEquals(0,fixture.code(fixture.submit(301)));
    run(UUID.randomUUID().toString());var stock=fixture.stocks();
    assertNotEquals(0,fixture.code(fixture.submit(301)));assertEquals(stock,fixture.stocks());
    assertEquals(0,fixture.code(fixture.query()));assertEquals(0,fixture.code(fixture.submit(301)));
  }
  @Test void preservedBelobogActivityCanCompleteAllSevenOriginalOrdersInItsOwnCycle() throws Exception {
    String cycle=UUID.randomUUID().toString();
    GameplayDefinitions.replaceActivityWindows(java.util.stream.IntStream.rangeClosed(201,207).mapToObj(id->
        new GameplayDefinitions.Window(30011,MaterialSubmitActivity46Test.ROWS.get(id).get("ActivityModuleID").getAsInt(),1,Long.MAX_VALUE,cycle,"2.6-belobog-orders")).toList());
    for(int id=201;id<=207;id++)for(var raw:MaterialSubmitActivity46Test.ROWS.get(id).getAsJsonArray("MaterialList")){
      int item=raw.getAsJsonObject().get("ItemID").getAsInt();
      if(fixture.player.stock.getMaterialByItemId(item)==null)fixture.player.stock.addItem(new MaterialSubmitActivity46Test.Material(fixture.player,item,100),false);
    }
    assertEquals(0,fixture.code(fixture.query()));
    for(int id=201;id<=207;id++){
      assertEquals(0,fixture.code(fixture.submit(id)));assertEquals(0,fixture.code(fixture.take(id)));
    }
    assertEquals(7,fixture.player.grants);
  }
}
