package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native QuestModule emits the live status-change event only from PlayerSyncScNotify. */
class RogueBlessingClaimSync46Test {
  @TempDir Path root;
  final RogueBlessingQuestTest f = new RogueBlessingQuestTest();
  final WireSession wire = new WireSession();

  class WireSession extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<PacketCodec.Frame> sent = new ArrayList<>();
    WireSession() { super(null); setState(SessionState.ACTIVE); }
    @Override public Player getPlayer() { return f.actor; }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override protected void writeTransport(byte[] bytes) {
      var input = Unpooled.wrappedBuffer(bytes);
      try { while(input.isReadable()) sent.add(PacketCodec.decodeNext(input)); }
      finally { input.release(); }
    }
    void request(String name, byte[] body) {
      sent.clear();
      var input = Unpooled.wrappedBuffer(PacketCodec.encode(bridge.clientCommand(name), new byte[]{8,77}, body));
      try { onMessage(input); } finally { input.release(); }
      assertEquals(SessionState.ACTIVE,getState());
    }
  }

  @BeforeEach void setup() throws Exception {
    f.temp=root; f.setup();
    f.actor=new RogueBlessingQuestTest.Actor() {
      @Override public void sendPacket(BasePacket packet) { wire.send(packet); }
    };
    assertTrue(f.actor.setNickname("BlessingSync"));
    f.actor.setSession(wire);
    f.actor.getQuestManager().refreshUnlocks();
    wire.sent.clear();
  }
  @AfterEach void cleanup() throws Exception { f.restore(); }

  int quest(int path) {
    return f.types.asList().stream().map(com.google.gson.JsonElement::getAsJsonObject)
        .filter(row->row.get("RogueBuffType").getAsInt()==path).findFirst().orElseThrow()
        .getAsJsonArray("RugueBuffTypeRewardQuestList").get(0).getAsInt();
  }
  Object value(DynamicMessage message,String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }
  int number(DynamicMessage message,String name) { return ((Number)value(message,name)).intValue(); }
  List<DynamicMessage> rows(String name) throws Exception {
    var result=new ArrayList<DynamicMessage>();
    for(var frame:wire.sent)
      if(frame.cmdId()==wire.bridge.clientCommand(name)) {
        var data=wire.bridge.clientSchema().parse(name,frame.body());
        for(var row:(List<?>)value(data,"quest_list")) result.add((DynamicMessage)row);
      }
    return result;
  }
  int status(DynamicMessage row) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor)value(row,"status")).getNumber();
  }
  void liveStatus(int id,int expected) throws Exception {
    var matching=rows("PlayerSyncScNotify").stream()
        .filter(row->number(row,"id")==id).toList();
    assertFalse(matching.isEmpty(),"The open reward page needs a native live quest update");
    assertTrue(matching.stream().allMatch(row->status(row)==expected));
    assertTrue(rows("GetQuestDataScRsp").isEmpty(),"A live update must not clear the native quest cache");
  }
  int claim(int id) throws Exception {
    var type=wire.bridge.clientSchema().message("TakeQuestRewardCsReq");
    wire.request("TakeQuestRewardCsReq",DynamicMessage.newBuilder(type)
        .addRepeatedField(type.findFieldByName("ODJIJFJCHHB"),id).build().toByteArray());
    var replies=wire.sent.stream().filter(frame->frame.cmdId()==wire.bridge.clientCommand("TakeQuestRewardScRsp")).toList();
    assertEquals(1,replies.size());
    assertArrayEquals(new byte[]{8,77},replies.getFirst().header());
    for(var frame:wire.sent) if(frame!=replies.getFirst()) assertEquals(0,frame.header().length);
    return number(wire.bridge.clientSchema().parse("TakeQuestRewardScRsp",replies.getFirst().body()),"retcode");
  }

  @ParameterizedTest @ValueSource(ints={120,121,122,123,124,125,126,127,128})
  void claimingEachOriginalFirstTierPaysOnceAndPublishesClosedToTheOpenPage(int path) throws Exception {
    f.collectPath(path,5); int id=quest(path);
    assertEquals(0,claim(id));
    assertEquals(80,f.actor.getHcoin());
    assertEquals(QuestState.Phase.CLOSED,f.actor.getQuestStates().get(id).getPhase());
    liveStatus(id,3);
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),claim(id));
    assertEquals(80,f.actor.getHcoin());
    liveStatus(id,3);
  }

  @Test void actualFifthAcquisitionPublishesCompletionWithoutReplacingCachedQuests() throws Exception {
    f.collectPath(126,4); wire.sent.clear();
    f.collect(f.visible.get(126).get(4));
    liveStatus(3000109,2);
    assertEquals(0,f.actor.getHcoin());
  }

  @Test void aPendingTierIsNeverAdvertisedAsClaimed() throws Exception {
    f.collectPath(126,4);
    assertEquals(Retcode.QUEST_NOT_FINISH.getVal(),claim(3000109));
    assertEquals(0,f.actor.getHcoin());
    liveStatus(3000109,1);
  }

  @Test void savedClosedQuestStillAppearsClosedInAnExplicitFullQueryAfterExplorationEnds() throws Exception {
    f.collectPath(126,5);
    assertEquals(0,claim(3000109));
    var state=f.actor.getQuestStates().get(3000109);
    var gson=new com.google.gson.Gson();
    f.actor.getQuestStates().put(3000109,gson.fromJson(gson.toJson(state),QuestState.class));
    f.actor.getGameplay().setRogue(new GameplayState().getRogue());
    wire.sent.clear();
    new emu.lunarcore.server.packet.recv.HandlerGetQuestDataCsReq().handle(wire,new byte[0]);
    var row=rows("GetQuestDataScRsp").stream().filter(q->number(q,"id")==3000109).findFirst().orElseThrow();
    assertEquals(3,status(row));
    assertEquals(80,f.actor.getHcoin());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),claim(3000109));
    liveStatus(3000109,3);
  }
}
