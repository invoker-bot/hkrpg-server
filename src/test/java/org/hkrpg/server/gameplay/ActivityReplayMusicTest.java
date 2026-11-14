package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;

/** Original snowfield performances through the real manager and native request boundary. */
class ActivityReplayMusicTest {
  MusicWorldEntryRewardTest fixture;
  @BeforeEach void prepare() throws Exception {
    fixture=new MusicWorldEntryRewardTest();fixture.setup();fixture.qualify();
  }
  @AfterEach void restore(){if(fixture!=null)fixture.cleanup();}
  void window(String id){
    GameplayDefinitions.replaceActivityWindows(List.of(new GameplayDefinitions.Window(
        50022,5002202,1,4102444800L,id,"2.6-0175c1cdbc5c")));
  }
  void play(){
    assertEquals(Retcode.SUCC,fixture.manager.start(601));
    assertEquals(Retcode.SUCC,fixture.manager.finish(3,true));
  }
  @Test void legacyAndTwoReplaysKeepIndependentStarsAndActualBadgeRewards(){
    play();assertEquals(3,fixture.player.badges);
    String legacy=fixture.gson.toJson(fixture.player.getGameplay().getRhythm());
    String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
    window(first);assertTrue(fixture.player.getGameplay().getRhythm().getStars().isEmpty());
    play();assertEquals(6,fixture.player.badges);
    window(second);assertTrue(fixture.player.getGameplay().getRhythm().getStars().isEmpty());
    play();assertEquals(9,fixture.player.badges);
    var serialized=fixture.gson.toJsonTree(fixture.player.getGameplay()).getAsJsonObject();
    assertEquals(legacy,fixture.gson.toJson(serialized.get("rhythm")));
    fixture.player.restored=fixture.gson.fromJson(serialized,GameplayState.class);
    assertEquals(3,fixture.player.getGameplay().getRhythm().getStars().get(601));
    assertEquals(Retcode.MUSIC_LOCKED,fixture.manager.finish(3,true));
    window(first);play();assertEquals(9,fixture.player.badges);
    assertEquals(9,fixture.player.getGameplay().getRewards().size());
  }
  @Test void aSaveCallbackCannotMoveAnOldPerformanceIntoTheNextReplay(){
    String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();window(first);
    assertEquals(Retcode.SUCC,fixture.manager.start(601));
    fixture.player.afterSave=()->{fixture.player.afterSave=null;window(second);};
    assertEquals(Retcode.SUCC,fixture.manager.finish(3,false));
    assertTrue(fixture.player.getGameplay().getRhythm().getStars().isEmpty());
    assertTrue(fixture.player.getGameplay().getRewards().keySet().stream().allMatch(k->k.startsWith("activity-replay:"+first+":")));
    window(first);assertEquals(3,fixture.player.getGameplay().getRhythm().getStars().get(601));
  }
  class Session extends GameSession {
    final ProtocolBridge protocol=ProtocolBridge.forVersion("4.6.0");
    DynamicMessage response;
    Session(){super(null);setState(SessionState.ACTIVE);}
    @Override public emu.lunarcore.game.player.Player getPlayer(){return fixture.player;}
    @Override public ProtocolBridge getProtocol(){return protocol;}
    @Override public void send(BasePacket packet){}
    @Override public void sendClientPacket(String name,byte[] body){
      try{response=protocol.clientSchema().parse(name,body);}catch(Exception failure){throw new AssertionError(failure);}
    }
    int request(String name,Object... fields)throws Exception{
      var type=protocol.clientSchema().message(name);var b=DynamicMessage.newBuilder(type);
      for(int i=0;i<fields.length;i+=2)b.setField(type.findFieldByName((String)fields[i]),fields[i+1]);
      assertTrue(VersionedPacketHandler.handle(this,protocol.clientCommand(name),b.build().toByteArray()));
      var retcode=response.getDescriptorForType().findFieldByName("retcode");return retcode==null?0:(Integer)response.getField(retcode);
    }
  }
  @Test void staleNativeFinishIsRejectedUntilTheNewCycleIsViewedAndStarted()throws Exception{
    var session=new Session();window(UUID.randomUUID().toString());
    assertEquals(0,session.request("MusicRhythmDataCsReq"));
    assertEquals(0,session.request("MusicRhythmStartLevelCsReq","level_id",601));
    window(UUID.randomUUID().toString());
    assertNotEquals(0,session.request("MusicRhythmFinishLevelCsReq","role_star",3));
    assertEquals(0,fixture.player.badges);
    assertEquals(0,session.request("MusicRhythmDataCsReq"));
    assertNotEquals(0,session.request("MusicRhythmFinishLevelCsReq","role_star",3));
    assertEquals(0,session.request("MusicRhythmStartLevelCsReq","level_id",601));
    assertEquals(0,session.request("MusicRhythmFinishLevelCsReq","role_star",3));
    assertEquals(3,fixture.player.badges);
  }
}
