package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ActivityReplayMusicOfferingTest {
  @TempDir Path root;
  MusicOfferingRewardTest fixture;
  @BeforeEach void prepare()throws Exception{
    fixture=new MusicOfferingRewardTest();fixture.root=root;fixture.setup();fixture.qualify();
    fixture.query.fixture.player.setLevel(21);
    try(var reader=new InputStreamReader(getClass().getResourceAsStream("/gameplay/music-replay-4.6.json"),java.nio.charset.StandardCharsets.UTF_8)){
      var row=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records").getAsJsonArray("ActivityPanel").get(0);
      GameData.getActivityPanelExcelMap().add(fixture.query.gson.fromJson(row,ActivityPanelExcel.class));
    }
  }
  @AfterEach void restore()throws Exception{if(fixture!=null)fixture.cleanup();}
  void window(String id,boolean future){
    GameplayDefinitions.replaceActivityWindows(id==null?List.of():List.of(new GameplayDefinitions.Window(
        50022,5002202,future?java.time.Instant.now().plusSeconds(86400).getEpochSecond():1,4102444800L,id,"2.6-0175c1cdbc5c")));
  }
  void earnFirst()throws Exception{
    fixture.query.info(5);fixture.stock(3);assertEquals(0,fixture.submit());assertEquals(0,fixture.claim(1));
  }
  @Test void originalAndTwoReplaysHaveSeparateOfferingLevelsAndClaimReservations()throws Exception{
    earnFirst();var gson=fixture.query.gson;var player=fixture.query.fixture.player;
    String original=gson.toJsonTree(player.getGameplay()).getAsJsonObject().get("offerings").toString();
    String first=UUID.randomUUID().toString();window(first,false);
    assertFalse(player.getGameplay().getOfferings().containsKey(5));earnFirst();
    window(UUID.randomUUID().toString(),false);assertFalse(player.getGameplay().getOfferings().containsKey(5));earnFirst();
    assertEquals(180,player.delivered.get(1));
    assertEquals(original,gson.toJsonTree(player.getGameplay()).getAsJsonObject().get("offerings").toString());
    window(first,false);fixture.query.info(5);assertNotEquals(0,fixture.claim(1));assertEquals(3,player.grants);
  }
  @Test void futureReplayCannotSpendBadgesOrAdvanceOfferingLevels()throws Exception{
    window(UUID.randomUUID().toString(),true);fixture.query.info(5);fixture.stock(3);
    assertNotEquals(0,fixture.submit());assertEquals(3,fixture.query.fixture.player.stock.get(270003).getCount());
    assertFalse(fixture.query.fixture.player.getGameplay().getOfferings().containsKey(5));
  }
  @Test void stoppingAReplayCannotRedirectAnOldOfferingRequestIntoTheOriginalSave()throws Exception{
    window(UUID.randomUUID().toString(),false);fixture.query.info(5);fixture.stock(3);
    window(null,false);assertNotEquals(0,fixture.submit());
    assertFalse(fixture.query.fixture.player.getGameplay().getOfferings().containsKey(5));
    assertEquals(3,fixture.query.fixture.player.stock.get(270003).getCount());
    fixture.query.info(5);assertEquals(0,fixture.submit());
  }
}
