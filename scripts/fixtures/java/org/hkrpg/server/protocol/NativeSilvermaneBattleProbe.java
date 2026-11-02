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
    return Map.of("passed",true,"uid",uid,"mode",mode,"requests",requests,
        "bothEventRepliesVerified",true,"firstVictoryDidNotSkipSecond",true,"secondVictoryAcceptedNext",true,
        "invalidAndRepeatedResultsRejected",true,"clientGameplayVerified",false);
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
