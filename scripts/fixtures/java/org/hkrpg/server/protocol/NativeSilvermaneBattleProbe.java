import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;
import org.hkrpg.server.protocol.ProtocolBridge;

/** Disposable local KCP session; never connects to a non-loopback address. */
public class NativeSilvermaneBattleProbe {
  record Frame(int command, byte[] body) {}
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  final AtomicReference<Throwable> failure = new AtomicReference<>();
  final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  Ukcp channel;
  int requests;
  static Object field(DynamicMessage value, String name) {
    return value.getField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(name), name));
  }
  static int number(DynamicMessage value, String name) { return (Integer) field(value,name); }
  static void require(boolean value, String why) { if (!value) throw new IllegalStateException(why); }
  DynamicMessage call(String name, Map<String,Object> fields, boolean success) throws Exception {
    var builder = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach((k,v) -> builder.setField(builder.getDescriptorForType().findFieldByName(k),v));
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name),new byte[0],builder.build().toByteArray()));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
    long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
    String response = name.replace("CsReq","ScRsp");
    while(System.nanoTime()<deadline) {
      if(failure.get()!=null) throw new IllegalStateException("Connection failed",failure.get());
      var frame = inbox.poll(1,TimeUnit.SECONDS);
      if(frame==null || frame.command()!=protocol.clientCommand(response)) continue;
      var result = protocol.clientSchema().parse(response,frame.body());
      require(result.getUnknownFields().asMap().isEmpty(),"Unknown response fields");
      require((number(result,"retcode")==0)==success,"Unexpected result "+response+" "+number(result,"retcode"));
      return result;
    }
    throw new TimeoutException(response);
  }
  DynamicMessage scene() throws Exception {
    var value=(DynamicMessage)field(call("GetCurSceneInfoCsReq",Map.of(),true),"scene");
    require(number(value,"plane_id")==20101 && number(value,"floor_id")==20101001,"Wrong original scene");
    return value;
  }
  void goal(boolean completed) throws Exception {
    var snapshot=call("GetMissionDataCsReq",Map.of(),true);
    boolean finished=((List<?>)field(snapshot,"OLKALABEDOA")).contains(101010110);
    require(finished==completed,"Wrong victory objective state");
    boolean next=false;
    for(var raw:(List<?>)field(snapshot,"mission_list")) {
      var mission=(DynamicMessage)raw;
      if(number(mission,"id")==101010113)next=true;
    }
    require(next==completed,"Wrong following performance objective");
  }
  void cityGoals() throws Exception {
    var data=call("GetMissionDataCsReq",Map.of(),true);
    var finished=(List<?>)field(data,"OLKALABEDOA");
    require(finished.containsAll(List.of(101010110,101010113,101010114)),"City arrival did not finish");
    boolean next=false;
    for(var raw:(List<?>)field(data,"mission_list"))
      if(number((DynamicMessage)raw,"id")==101020101)next=true;
    require(next&&!finished.contains(101020101),"The city dialogue must remain pending");
  }
  void cityScene() throws Exception {
    var scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq",Map.of(),true),"scene");
    require(number(scene,"plane_id")==10101&&number(scene,"floor_id")==10101001,"City scene missing");
    boolean shop=false,investigation=false;
    for(var raw:(List<?>)field(scene,"entity_group_list")) {
      int group=number((DynamicMessage)raw,"group_id");
      require(group!=120,"Dormant client-owned atmosphere group leaked into server scene");
      shop|=group==2;investigation|=group==13;
    }
    require(shop&&investigation,"Original server shop NPCs and investigation props must remain");
  }
  void awaitCityTransfer() throws Exception {
    awaitTransfer(10101,10101001);
  }
  void awaitTransfer(int plane,int floor) throws Exception {
    long started=System.nanoTime(),deadline=started+TimeUnit.SECONDS.toNanos(23);
    int synchronizedScenes=0;
    while(System.nanoTime()<deadline) {
      if(failure.get()!=null)throw new IllegalStateException("KCP failure",failure.get());
      var frame=inbox.poll(1,TimeUnit.SECONDS);
      if(frame==null)continue;
      if(frame.command()==protocol.clientCommand("SyncServerSceneChangeNotify"))synchronizedScenes++;
      if(frame.command()==protocol.clientCommand("EnterSceneByServerScNotify")) {
        var notice=protocol.clientSchema().parse("EnterSceneByServerScNotify",frame.body());
        require(notice.getUnknownFields().asMap().isEmpty(),"Unknown city transition fields");
        var scene=(DynamicMessage)field(notice,"scene");
        require(number(scene,"plane_id")==plane&&number(scene,"floor_id")==floor,"Wrong transfer destination");
        require(synchronizedScenes==1,"Expected one scene synchronization before transfer");
        require(System.nanoTime()-started>=TimeUnit.SECONDS.toNanos(13),"Client grace period was skipped");
        return;
      }
    }
    throw new TimeoutException("Same-session story transition to "+floor);
  }
  void cityChapterGoals(List<Integer> completed,int pending) throws Exception {
    var data=call("GetMissionDataCsReq",Map.of(),true);
    var finished=(List<?>)field(data,"OLKALABEDOA");
    require(finished.containsAll(completed)&&!finished.contains(pending),"Wrong city chapter completions");
    require(((List<?>)field(data,"mission_list")).stream().anyMatch(raw->number((DynamicMessage)raw,"id")==pending),
        "Following dialogue was skipped or not accepted");
  }
  void belobogRoundTrip() throws Exception {
    call("FinishTalkMissionCsReq",Map.of("sub_mission_id",101020101,"talk_str","Mission_101020101"),true);
    call("FinishTalkMissionCsReq",Map.of("sub_mission_id",101020102,"talk_str","Mission_101020102"),true);
    call("FinishTalkMissionCsReq",Map.of("sub_mission_id",101020104,"talk_str","Mission_101020104"),false);
    awaitTransfer(10101,10101002);
    var fort=(DynamicMessage)field(call("GetCurSceneInfoCsReq",Map.of(),true),"scene");
    require(number(fort,"floor_id")==10101002,"Qlipoth Fort did not load");
    cityChapterGoals(List.of(101020101,101020102,101020103),101020104);
    call("FinishTalkMissionCsReq",Map.of("sub_mission_id",101020104,"talk_str","Mission_101020104"),true);
    awaitTransfer(10101,10101001);cityScene();
    cityChapterGoals(List.of(101020101,101020102,101020103,101020104,101020105),101020106);
    call("EnterSceneCsReq",Map.of("entry_id",101010102,"teleport_id",0),true);
    cityScene();
  }
  Map<String,Object> result(DynamicMessage battle,String end) {
    var type=protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status").getEnumType();
    return Map.of("battle_id",number(battle,"battle_id"),"stage_id",number(battle,"stage_id"),"end_status",type.findValueByName(end));
  }
  Map<String,Object> execute(JsonObject control) throws Exception {
    var token=call("PlayerGetTokenCsReq",Map.of("account_uid",control.get("accountUid").getAsString(),
        "token",control.get("comboToken").getAsString(),"platform",1),true);
    int uid=number(token,"uid");
    call("PlayerLoginCsReq",Map.of(),true);call("PlayerLoginFinishCsReq",Map.of(),true);
    String mode=control.get("mode").getAsString();
    if(mode.equals("bootstrap")) return Map.of("passed",true,"uid",uid,"requests",requests);
    boolean city=control.has("cityTransfer")&&control.get("cityTransfer").getAsBoolean();
    boolean roundTrip=control.has("belobogRoundTrip")&&control.get("belobogRoundTrip").getAsBoolean();
    if(city&&mode.equals("restored")) {
      cityScene();
      if(roundTrip)cityChapterGoals(List.of(101020101,101020102,101020103,101020104,101020105),101020106);
      else cityGoals();
      return Map.of("passed",true,"uid",uid,"requests",requests,"cityArrivalRestored",true);
    }
    scene();
    if(mode.equals("restored")) {
      goal(true);
      return Map.of("passed",true,"uid",uid,"requests",requests,"completedGoalRestored",true);
    }
    require(mode.equals("battle"),"Unknown mode");goal(false);
    for(int event:new int[]{20101011,20101012}) {
      var entered=call("SceneEnterStageCsReq",Map.of("event_id",event),true);
      var battle=(DynamicMessage)field(entered,"battle_info");
      require(number(battle,"stage_id")==event*10,"Wrong world-0 event stage");
      var report=result(battle,"BATTLE_END_WIN");
      var invalid=new HashMap<>(report);invalid.put("stage_id",1);
      var rejected=call("PVEBattleResultCsReq",invalid,false);
      require(number(rejected,"event_id")==0,"Rejected report emitted a story event");
      var reply=call("PVEBattleResultCsReq",report,true);
      require(number(reply,"event_id")==event,"Missing event identity for client story predicate");
      require(number(reply,"stage_id")==number(battle,"stage_id"),"Stage identity changed");
      require(number(reply,"battle_id")==number(battle,"battle_id"),"Battle identity changed");
      var duplicate=call("PVEBattleResultCsReq",report,false);
      require(number(duplicate,"event_id")==0,"Repeated result replayed event");goal(event==20101012);
    }
    if(city) {
      call("FinishTalkMissionCsReq",Map.of("sub_mission_id",101010113,"talk_str","Mission_101010113"),true);
      awaitCityTransfer();cityScene();cityGoals();
      call("EnterSceneCsReq",Map.of("entry_id",1010101,"teleport_id",0),true);
      cityScene();cityGoals();
      if(roundTrip)belobogRoundTrip();
    }
    var summary=new LinkedHashMap<String,Object>(Map.of("passed",true,"uid",uid,"mode",mode,"requests",requests,
        "bothEventRepliesVerified",true,"firstVictoryDidNotSkipSecond",true,"secondVictoryAcceptedNext",true,
        "invalidAndRepeatedResultsRejected",true,"clientGameplayVerified",false));
    summary.put("sameSessionCityTransfer",city);
    summary.put("sameSessionBelobogRoundTrip",roundTrip);
    return summary;
  }
  public static void main(String[] args) {
    var probe=new NativeSilvermaneBattleProbe();var client=new KcpClient();int exit=1;
    try {
      int port=Integer.parseInt(args[0]);require(port!=23311,"Production port forbidden");
      var control=JsonParser.parseString(new String(System.in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
      var config=new ChannelConfig();config.nodelay(true,40,2,true);config.setMtu(1200);
      config.setSndwnd(256);config.setRcvwnd(256);config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE));config.setTimeoutMillis(30000);config.setAckNoDelay(true);
      client.init(config,new KcpListener() {
        public void onConnected(Ukcp c) { probe.connected.complete(c); }
        public void handleReceive(ByteBuf b,Ukcp c) {
          try {while(b.isReadable()) { var f=PacketCodec.decodeNext(b);require(f!=null,"Truncated frame");probe.inbox.add(new Frame(f.cmdId(),f.body())); }}
          catch(Throwable error) {probe.failure.set(error);}
        }
        public void handleException(Throwable error,Ukcp c) {probe.failure.set(error);probe.connected.completeExceptionally(error);}
        public void handleClose(Ukcp c) {probe.failure.compareAndSet(null,new IllegalStateException("Disconnected"));}
      });
      client.connect(new InetSocketAddress("127.0.0.1",port),config);probe.channel=probe.connected.get(15,TimeUnit.SECONDS);
      System.out.println("SILVERMANE_PROBE="+new Gson().toJson(probe.execute(control)));exit=0;
    } catch(Exception error) {error.printStackTrace(System.err);}
    finally {client.stop();}
    System.exit(exit);
  }
}
