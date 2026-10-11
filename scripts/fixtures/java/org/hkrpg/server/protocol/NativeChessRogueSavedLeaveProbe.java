package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Owned saved-record KCP lane. Existing merchant sequence is run by its unchanged separate probe. */
public final class NativeChessRogueSavedLeaveProbe {
  private static final String FORMAL_AVATAR_ENUM = "AvatarType_AvatarFormalType";
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<Map<String,Object>> events = new ArrayList<>(), notifications = new ArrayList<>();
  private final List<DynamicMessage> lineupNotifications = new ArrayList<>();
  private final List<DynamicMessage> miracleActionNotifications = new ArrayList<>();
  private final List<Map<String,Object>> miracleActionEvents = new ArrayList<>();
  private final List<String> checks = new ArrayList<>();
  private final List<Map<String,Integer>> encounters = new ArrayList<>();
  private final Map<String,Map<String,Integer>> schemas = new LinkedHashMap<>();
  private Ukcp channel;
  private JsonArray sourceBoards;
  private int requests, castUseId = 190000;

  private static void require(boolean ok,String why) { if (!ok) throw new IllegalStateException(why); }
  private void check(boolean ok,String why) { require(ok,why); checks.add(why); }
  private static Descriptors.FieldDescriptor descriptor(DynamicMessage value,int id) {
    return Objects.requireNonNull(value.getDescriptorForType().findFieldByNumber(id), value.getDescriptorForType().getName()+"."+id);
  }
  private static Object field(DynamicMessage value,int id) { return value.getField(descriptor(value,id)); }
  private static Object field(DynamicMessage value,String name) {
    return value.getField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(name),name));
  }
  private static int number(DynamicMessage value,String name) { return ((Number)field(value,name)).intValue(); }
  private static int number(DynamicMessage value,int id) {
    var raw=field(value,id);
    return raw instanceof Descriptors.EnumValueDescriptor ordinal ? ordinal.getNumber() : ((Number)raw).intValue();
  }
  private static DynamicMessage nested(DynamicMessage value,int id) { return (DynamicMessage)field(value,id); }
  private static DynamicMessage nested(DynamicMessage value,String key) { return (DynamicMessage)field(value,key); }
  private static boolean has(DynamicMessage value,String key) {
    return value.hasField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(key),key));
  }
  private static String sha(DynamicMessage value) throws Exception {
    return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()));
  }
  private static Object view(Object value) {
    if (value instanceof DynamicMessage message) {
      var out=new LinkedHashMap<String,Object>();
      message.getAllFields().forEach((key,raw)->out.put(key.getNumber()+":"+key.getName(),
          key.getName().toLowerCase(Locale.ROOT).contains("token") ? "[redacted]" : view(raw)));
      return out;
    }
    if (value instanceof List<?> rows) return rows.stream().map(NativeChessRogueSavedLeaveProbe::view).toList();
    if (value instanceof Descriptors.EnumValueDescriptor ordinal) return Map.of("name",ordinal.getName(),"number",ordinal.getNumber());
    if (value instanceof ByteString bytes) return Base64.getEncoder().encodeToString(bytes.toByteArray());
    return value;
  }
  private void schema(String name,int... ids) {
    var message=Objects.requireNonNull(protocol.clientSchema().message(name),name);
    var fields=new LinkedHashMap<String,Integer>();
    for (int id:ids) fields.put(Objects.requireNonNull(message.findFieldByNumber(id),name+"."+id).getName(),id);
    schemas.put(name,fields);
  }
  private void namedSchema(String name,String... names) {
    var message=Objects.requireNonNull(protocol.clientSchema().message(name),name);
    var fields=new LinkedHashMap<String,Integer>();
    for (String key:names) fields.put(key,Objects.requireNonNull(message.findFieldByName(key),name+"."+key).getNumber());
    schemas.put(name,fields);
  }
  private void verifySchemas() {
    schema("ChessRogueStartCsReq",13,9,7); schema("ChessRogueStartScRsp",2,7,13);
    schema("ChessRogueEnterCsReq",8); schema("ChessRogueEnterScRsp",3,6,8,10);
    schema("ChessRogueQueryCsReq"); schema("ChessRogueQueryScRsp",8,10,12);
    schema("ChessRogueLeaveCsReq"); schema("ChessRogueLeaveScRsp",4,6,11,12,14);
    schema("HIMJKAMHLMJ",9,10,11,12); schema("MOOKMKEKMKC",3,9,10); schema("DDAFEPBGOHK",8,9);
    check(protocol.clientCommand("ChessRogueLeaveCsReq")==5462 && protocol.clientCommand("ChessRogueLeaveScRsp")==5419,
        "fixed-native-LeavingAndSave5462-response5419");
    check(protocol.clientSchema().message("ChessRogueLeaveCsReq").getFields().isEmpty(),"original5462-request-body-is-empty");
    check(protocol.clientSchema().message("ChessRogueQueryScRsp").findFieldByNumber(10).getMessageType().getName().equals("HIMJKAMHLMJ")
        && protocol.clientSchema().message("ChessRogueLeaveScRsp").findFieldByNumber(12).getMessageType().getName().equals("HIMJKAMHLMJ"),
        "Query10-and-Leave12-share-complete-HIM-profile-type");
    schema("ChessRogueSelectCellCsReq",2); schema("ChessRogueSelectCellScRsp",15);
    schema("ChessRogueEnterCellCsReq",13,15); schema("ChessRogueEnterCellScRsp",2,8,12);
    schema("ChessRogueEnterNextLayerCsReq"); schema("ChessRogueEnterNextLayerScRsp",4,8,11);
    schema("DJEKEGMEHLK",1,2,3,10,14,15); schema("AEMAPGKPPJA",14);
    schema("LKPCDHCKHKP",1,2,4,9,11,12); schema("DPELLHBJGML",5,8,9,10,15);
    schema("IKCONCOKNAN",3,4,7,9,15); schema("CGMCMAKMJDK",1,6,7,10,13,14);
    schema("GKDJBPJOGPO",2); schema("MANINNKMFHG",2,14); schema("AGEOGDOGDMM",13);
    schema("NHLBNBBDHMI",3); schema("GMIJHCONFIH",1); schema("KOFMGCNIMIN",1,2,4);
    schema("SyncRogueCommonActionResultScNotify",1,15); schema("MAOKMPOELCO",3);
    schema("PKHKEEKIODC",894); schema("IOMEMIEAPJO",13);
    schema("ChessRogueUpdateMoneyInfoScNotify",14); schema("ChessRogueCellUpdateNotify",1,7);
    schema("ChessRogueUpdateAllowedSelectCellScNotify",4,9);
    namedSchema("PlayerGetTokenCsReq","account_uid","token","platform");
    namedSchema("GetCurLineupDataScRsp","lineup");
    namedSchema("SyncLineupNotify","lineup");
    namedSchema("LineupInfo","avatar_list","leader_slot","mp");
    namedSchema("LineupAvatar","id","slot","avatar_type","hp","sp_bar");
    namedSchema("SpBarInfo","cur_sp","max_sp");
    namedSchema("GetCurSceneInfoScRsp","scene");
    namedSchema("SceneInfo","plane_id","floor_id","entry_id","leader_entity_id","entity_group_list");
    namedSchema("SceneEntityGroupInfo","group_id","entity_list");
    namedSchema("SceneEntityInfo","entity_id","group_id","inst_id","npc","npc_monster","motion");
    namedSchema("SceneNpcInfo","npc_id"); namedSchema("SceneNpcMonsterInfo","monster_id","event_id");
    namedSchema("SceneCastSkillCsReq","cast_entity_id","attacked_by_entity_id","skill_index","hit_target_entity_id_list");
    namedSchema("SceneCastSkillScRsp","retcode","battle_info");
    namedSchema("SceneBattleInfo","battle_id","stage_id","monster_wave_list");
    namedSchema("SceneMonsterWave","battle_stage_id","monster_list","monster_param");
    namedSchema("SceneMonsterWaveParam","level");
    namedSchema("SceneMonster","monster_id");
    namedSchema("PVEBattleResultCsReq","battle_id","stage_id","end_status","stt");
    namedSchema("BattleStatistics","battle_avatar_list");
    namedSchema("AvatarBattleInfo","id","avatar_type","avatar_status");
    namedSchema("AvatarProperty","max_hp","left_hp","max_sp","left_sp");
    var lineupAvatarType=protocol.clientSchema().message("LineupAvatar").findFieldByName("avatar_type").getEnumType();
    var reportAvatarType=protocol.clientSchema().message("AvatarBattleInfo").findFieldByName("avatar_type").getEnumType();
    var endType=protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status").getEnumType();
    var actualEnums=new LinkedHashMap<String,Object>();
    for (var original:List.of(lineupAvatarType,reportAvatarType,endType)) {
      var values=new LinkedHashMap<String,Integer>();
      original.getValues().forEach(value->values.put(value.getName(),value.getNumber()));
      actualEnums.put(original.getFullName(),values);
    }
    System.out.println("CHESS_ACTUAL_ENUM_SEMANTICS="+new Gson().toJson(actualEnums)); System.out.flush();
    var lineupFormal=lineupAvatarType.findValueByName(FORMAL_AVATAR_ENUM);
    var reportFormal=reportAvatarType.findValueByName(FORMAL_AVATAR_ENUM);
    check(lineupFormal!=null && reportFormal!=null && lineupFormal.getNumber()==3 && reportFormal.getNumber()==3
        && lineupAvatarType.equals(reportAvatarType),"actual-4.6-owned-lineup-and-battle-report-formal-enum3");
    var reportWin=endType.findValueByName("BATTLE_END_WIN");
    check(reportWin!=null && reportWin.getNumber()==1,"actual-4.6-native-battle-report-WIN-enum1");
    namedSchema("PVEBattleResultScRsp","retcode");
    namedSchema("GetRogueInfoScRsp","rogue_game_info");
    namedSchema("GetRogueShopMiracleInfoScRsp","retcode","DNPCCFFFBHN");
    namedSchema("BuyRogueShopMiracleScRsp","retcode","DNPCCFFFBHN");
    namedSchema("AGKONMPOJKP","MAJGMMCOCIG");
    namedSchema("ELGOBKCNOHH","LJCEOGPOGBE","GCIHGKCJOGB","BELPGDAGFIH","cost_data","KINNMAGDDJJ");
    namedSchema("ItemCostData","item_list"); namedSchema("ItemCost","pile_item");
    namedSchema("PileItem","item_id","item_num");
    check(protocol.clientCommand("ChessRogueStartCsReq")==5426
        && protocol.clientCommand("ChessRogueEnterCsReq")==5437
        && protocol.clientCommand("ChessRogueQueryCsReq")==5547
        && protocol.clientCommand("ChessRogueSelectCellCsReq")==5534
        && protocol.clientCommand("ChessRogueEnterCellCsReq")==5468
        && protocol.clientCommand("ChessRogueEnterNextLayerCsReq")==5447,"six-fixed-native-chess-command-identities");
    check(protocol.clientCommand("GetRogueShopMiracleInfoCsReq")==5675
        && protocol.clientCommand("BuyRogueShopMiracleCsReq")==5656,"native-shop-query-and-buy-command-identities");
    check(protocol.clientCommand("SyncLineupNotify")==742,"existing-native-lineup-notify-command742");
    var acquired=protocol.clientSchema().message("PKHKEEKIODC").findFieldByNumber(894);
    check(protocol.clientCommand("SyncRogueCommonActionResultScNotify")==5647
        && protocol.clientSchema().message("SyncRogueCommonActionResultScNotify").findFieldByNumber(15).isRepeated()
        && acquired.getContainingOneof()!=null && acquired.getMessageType().getName().equals("IOMEMIEAPJO")
        && protocol.clientSchema().message("IOMEMIEAPJO").findFieldByNumber(13).getMessageType().getName().equals("KOFMGCNIMIN"),
        "actual-native-Chess-acquired-miracle-notify5647-action894-and-miracle13");
    check(protocol.clientSchema().message("ChessRogueStartCsReq").findFieldByNumber(7).isRepeated()
        && protocol.clientSchema().message("ChessRogueEnterCellCsReq").findFieldByNumber(15).getType()==Descriptors.FieldDescriptor.Type.UINT32,
        "original-roster-repeated7-and-no-client-monster-pool-field");
  }
  private DynamicMessage message(String name,Map<Integer,Object> fields) {
    var builder=DynamicMessage.newBuilder(Objects.requireNonNull(protocol.clientSchema().message(name),name));
    fields.forEach((id,raw)->builder.setField(Objects.requireNonNull(builder.getDescriptorForType().findFieldByNumber(id),name+"."+id),raw));
    return builder.build();
  }
  private Map<Integer,Object> named(String name,Map<String,Object> fields) {
    var descriptor=Objects.requireNonNull(protocol.clientSchema().message(name),name);
    var out=new LinkedHashMap<Integer,Object>();
    fields.forEach((key,value)->out.put(Objects.requireNonNull(descriptor.findFieldByName(key),name+"."+key).getNumber(),value));
    return out;
  }
  private void observe(Frame frame) throws Exception {
    if (frame.command()==protocol.clientCommand("SyncRogueCommonActionResultScNotify")) {
      var event=new LinkedHashMap<String,Object>();
      event.put("command",frame.command()); event.put("name","SyncRogueCommonActionResultScNotify");
      event.put("observedBeforeReplyRequestOrdinal",requests);
      event.put("observedBodyBase64",Base64.getEncoder().encodeToString(frame.body()));
      miracleActionEvents.add(event);
      System.out.println("CHESS_MIRACLE_ACTION_FRAME="+new Gson().toJson(event)); System.out.flush();
      var value=protocol.clientSchema().parse("SyncRogueCommonActionResultScNotify",frame.body());
      event.put("observed",view(value)); event.put("unknownFields",value.getUnknownFields().asMap().keySet());
      System.out.println("CHESS_MIRACLE_ACTION_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
      require(value.getUnknownFields().asMap().isEmpty(),"Unknown actual native acquired-miracle notification fields");
      miracleActionNotifications.add(value);
      return;
    }
    if (frame.command()==protocol.clientCommand("SyncLineupNotify")) {
      var event=new LinkedHashMap<String,Object>();
      event.put("command",frame.command()); event.put("name","SyncLineupNotify");
      event.put("observedBodyBase64",Base64.getEncoder().encodeToString(frame.body()));
      System.out.println("CHESS_LINEUP_FRAME="+new Gson().toJson(event)); System.out.flush();
      var value=protocol.clientSchema().parse("SyncLineupNotify",frame.body());
      event.put("observed",view(value)); event.put("unknownFields",value.getUnknownFields().asMap().keySet());
      System.out.println("CHESS_LINEUP_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
      require(value.getUnknownFields().asMap().isEmpty(),"Unknown actual native lineup notification fields");
      lineupNotifications.add(value);
      return;
    }
    for (String name:List.of("ChessRogueUpdateMoneyInfoScNotify","ChessRogueCellUpdateNotify","ChessRogueUpdateAllowedSelectCellScNotify")) {
      if (frame.command()==protocol.clientCommand(name)) {
        var value=protocol.clientSchema().parse(name,frame.body());
        var event=new LinkedHashMap<String,Object>();
        event.put("command",frame.command()); event.put("name",name); event.put("observed",view(value));
        notifications.add(event); System.out.println("CHESS_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
      }
    }
  }
  private Frame raw(int command,byte[] body,int expected) throws Exception {
    var bytes=Unpooled.wrappedBuffer(PacketCodec.encode(command,new byte[0],body));
    try { channel.write(bytes); } finally { bytes.release(); }
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime()<until) {
      if (failure.get()!=null) throw new IllegalStateException("Native Chess connection failed",failure.get());
      var frame=inbox.poll(1,TimeUnit.SECONDS); if (frame==null) continue;
      if (frame.command()==expected) return frame;
      observe(frame);
    }
    throw new TimeoutException("Actual native Chess reply "+expected);
  }
  private DynamicMessage reply(String label,String name,int command,byte[] bytes,String response,int expected) throws Exception {
    requests++;
    var frame=raw(command,bytes,protocol.clientCommand(response));
    // Persist the actual frame before parsing or asserting. Authentication bodies stay redacted.
    var event=new LinkedHashMap<String,Object>();
    event.put("label",label); event.put("requestCommand",command); event.put("request",name);
    event.put("response",response); event.put("responseCommand",frame.command()); event.put("expectedRetcode",expected);
    if (!name.startsWith("PlayerGetToken")) event.put("observedBodyBase64",Base64.getEncoder().encodeToString(frame.body()));
    events.add(event); System.out.println("CHESS_REPLY_FRAME="+new Gson().toJson(event)); System.out.flush();
    var value=protocol.clientSchema().parse(response,frame.body());
    event.put("observedReply",view(value)); event.put("actualRetcode",number(value,"retcode"));
    event.put("unknownFields",value.getUnknownFields().asMap().keySet());
    System.out.println("CHESS_REPLY="+new Gson().toJson(event)); System.out.flush();
    require(value.getUnknownFields().asMap().isEmpty(),"Unknown actual "+response+" fields");
    require(expected<0 ? number(value,"retcode")!=0 : number(value,"retcode")==expected,
        label+" actual retcode="+number(value,"retcode")+" expected="+expected);
    return value;
  }
  private DynamicMessage call(String label,String name,Map<Integer,Object> fields,int status) throws Exception {
    return reply(label,name,protocol.clientCommand(name),message(name,fields).toByteArray(),name.replace("CsReq","ScRsp"),status);
  }
  private DynamicMessage call(String name) throws Exception { return call(name,name,Map.of(),0); }
  private DynamicMessage callNamed(String label,String name,Map<String,Object> fields,int status) throws Exception {
    return call(label,name,named(name,fields),status);
  }
  private DynamicMessage query() throws Exception { return nested(call("ChessRogueQueryCsReq"),8); }
  private DynamicMessage scene() throws Exception { return nested(call("GetCurSceneInfoCsReq"),"scene"); }
  private DynamicMessage classic() throws Exception { return nested(call("GetRogueInfoCsReq"),"rogue_game_info"); }
  private List<DynamicMessage> entities(DynamicMessage scene,String type) {
    var out=new ArrayList<DynamicMessage>();
    for (var raw:(List<?>)field(scene,"entity_group_list"))
      for (var value:(List<?>)field((DynamicMessage)raw,"entity_list")) {
        var entity=(DynamicMessage)value; if (has(entity,type)) out.add(entity);
      }
    return out;
  }
  private int money(DynamicMessage info) { return number(nested(info,10),13); }
  private Set<Integer> miracles(DynamicMessage info) {
    var out=new TreeSet<Integer>();
    for (var raw:(List<?>)field(nested(nested(info,3),3),1)) {
      var row=(DynamicMessage)raw;
      require(out.add(number(row,2)) && number(row,1)==0 && number(row,4)==1,"Duplicate or unauthored miracle counters");
    }
    return out;
  }
  private void board(DynamicMessage info,int layer,int cell,int coins,boolean cleared) {
    var area=nested(info,2); var board=nested(area,2); var layout=nested(board,9);
    var original=sourceBoards.get(layer).getAsJsonObject(); var sourceCells=original.getAsJsonObject("cells");
    check(number(area,9)==101 && number(area,11)==layer && number(area,12)==1011+layer
        && number(board,5)==(layer==0?10111:10121) && number(board,8)==cell
        && number(nested(info,1),14)==6 && money(info)==coins,"actual-chess-board-layer-cell-and-wallet-"+layer+"-"+cell+"-"+coins);
    var cells=new TreeMap<Integer,Integer>();
    for (var raw:(List<?>)field(layout,7)) {
      var row=(DynamicMessage)raw; require(cells.put(number(row,1),number(row,6))==null,"Duplicate native board cell");
      var source=sourceCells.getAsJsonObject(Integer.toString(number(row,1)));
      require(source!=null && number(row,13)==source.get("x").getAsInt() && number(row,7)==source.get("y").getAsInt()
          && number(row,14)==source.get("block").getAsInt() && number(row,10)==source.get("room").getAsInt(),
          "Original complete board cell coordinates/block or declared room mapping changed");
      int id=number(row,1), expected=id==cell ? cleared?3:2 : layer==0 && id>=201 && id<cell ? 3:0;
      require(number(row,6)==expected,"Unauthored completion/selection appeared in another board cell");
    }
    check(cells.keySet().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()).equals(sourceCells.keySet())
        && number(layout,15)==original.get("width").getAsInt() && number(layout,9)==original.get("height").getAsInt()
        && number(layout,4)==original.get("start").getAsInt() && number(layout,3)==original.get("end").getAsInt(),
        "whole-original-eight-cell-board-and-layout-preserved-"+layer);
    check(cells.get(cell)==(cleared?3:2),"current-native-cell-status-"+cell+"-"+cleared);
    var allowed=(List<?>)field(board,15);
    check(allowed.equals(layer==0 && cleared && cell<204 ? List.of(cell+1):List.of()),"actual-next-cell-permission-"+cell+"-"+cleared);
    var history=new ArrayList<Integer>();
    for (var raw:(List<?>)field(board,10)) history.add(number((DynamicMessage)raw,2));
    check(history.equals(layer==0?java.util.stream.IntStream.rangeClosed(201,cell).boxed().toList():List.of(201)),"actual-entered-original-path-history-"+layer+"-"+cell);
  }
  private void sourceScene(DynamicMessage scene,Set<Integer> groups) {
    var actual=new TreeSet<Integer>();
    for (var raw:(List<?>)field(scene,"entity_group_list")) actual.add(number((DynamicMessage)raw,"group_id"));
    check(actual.remove(0),"native-scene-has-the-real-player-actor-group");
    check(number(scene,"plane_id")==80201 && number(scene,"floor_id")==80201001
        && number(scene,"entry_id")==8020101 && actual.equals(groups),"actual-original-scene-and-complete-route-group-set-"+groups);
  }
  private int merchant() throws Exception {
    var scene=scene(); sourceScene(scene,Set.of(34,35));
    check(entities(scene,"npc_monster").isEmpty(),"original-Respite-has-no-encounter");
    var npcs=entities(scene,"npc").stream().filter(row->number(row,"group_id")==35
        && number(row,"inst_id")==400001 && number(nested(row,"npc"),"npc_id")==1013).toList();
    check(npcs.size()==1 && number(npcs.getFirst(),"entity_id")>0,"real-G35-NPC1013-inst400001-shop-actor");
    return number(npcs.getFirst(),"entity_id");
  }
  private void bars() throws Exception {
    validateBars(nested(call("GetCurLineupDataCsReq"),"lineup"));
  }
  private void validateBars(DynamicMessage lineup) {
    var avatars=(List<?>)field(lineup,"avatar_list");
    check(avatars.size()==1 && number(lineup,"mp")==5,"current-real-chess-roster-and-MP-remain-independent");
    var avatar=(DynamicMessage)avatars.getFirst(); var sp=nested(avatar,"sp_bar");
    check(number(avatar,"id")==8001 && number(avatar,"hp")==5000 && number(sp,"cur_sp")==2500
        && number(sp,"max_sp")==10000,"controlled-actual-native-settlement-report-updated-only-Chess-HP5000-SP2500");
  }
  private DynamicMessage shop(String label,int entity,int id,int status) throws Exception {
    var buffer=new ByteArrayOutputStream(); var writer=CodedOutputStream.newInstance(buffer);
    writer.writeUInt32(12,entity); if (id>0) writer.writeUInt32(6,id); writer.flush();
    return reply(label,id>0?"BuyRogueShopMiracleCsReq":"GetRogueShopMiracleInfoCsReq",id>0?5656:5675,
        buffer.toByteArray(),id>0?"BuyRogueShopMiracleScRsp":"GetRogueShopMiracleInfoScRsp",status);
  }
  private void quote(DynamicMessage response,boolean bought) {
    var offers=(List<?>)field(nested(response,"DNPCCFFFBHN"),"MAJGMMCOCIG");
    check(offers.size()==1,"only-original-compatible-miracle4-stock");
    var offer=(DynamicMessage)offers.getFirst(); var cost=nested(offer,"cost_data");
    var items=(List<?>)field(cost,"item_list"); require(items.size()==1,"Unexpected shop currency list");
    var pile=nested((DynamicMessage)items.getFirst(),"pile_item");
    check(number(offer,"LJCEOGPOGBE")==4 && number(pile,"item_id")==31 && number(pile,"item_num")==200
        && cost.equals(nested(offer,"KINNMAGDDJJ")),"declared-server-policy-price200-in-both-native-cost-fields");
    check(field(offer,"BELPGDAGFIH").equals(bought) && field(offer,"GCIHGKCJOGB").equals(bought),"independent-owned-and-unavailable-stock-bits-"+bought);
  }
  private void acquiredMiracle(DynamicMessage notification,int purchaseRequest) {
    var actions=(List<?>)field(notification,15);
    check(number(notification,1)==201 && actions.size()==1,"actual-Chess-provider-mode201-single-acquisition-action");
    var detail=nested((DynamicMessage)actions.getFirst(),3);
    var expected=descriptor(detail,894);
    check(detail.hasField(expected) && detail.getOneofFieldDescriptor(expected.getContainingOneof()).equals(expected),
        "actual-oneof894-is-AddMiracleItem");
    var miracle=nested(nested(detail,894),13);
    check(number(miracle,2)==4 && number(miracle,1)==0 && number(miracle,4)==1,
        "actual-acquired-original-miracle4-use0-total1");
    check(miracleActionEvents.getLast().get("observedBeforeReplyRequestOrdinal").equals(purchaseRequest),
        "native-acquisition-notification-was-received-before-this-successful-purchase-response");
  }
  private void profile(DynamicMessage response,int id) {
    check(response.hasField(descriptor(response,id)),"successful-response-has-required-HIM-"+id);
    var value=nested(response,id);
    check(value.getDescriptorForType().getName().equals("HIMJKAMHLMJ")
        && field(value,11).equals(List.of(101)) && ((List<?>)field(value,10)).isEmpty(),
        "actual-source101-unlocked-and-no-invented-first-reward");
    check(value.hasField(descriptor(value,9)) && value.hasField(descriptor(value,12)),"required-MOOK9-and-DDAF12-containers-nonnull");
    check(nested(value,9).getAllFields().isEmpty() && nested(value,12).getAllFields().isEmpty(),
        "Nous-dice-talents-and-currency281013-not-invented-by-Chess201");
    check(value.getAllFields().keySet().stream().map(Descriptors.FieldDescriptor::getNumber)
        .collect(java.util.stream.Collectors.toSet()).equals(Set.of(9,11,12)),"only-qualified-shared-profile-projection");
  }
  private DynamicMessage completeQuery(String label,String expectedHash) throws Exception {
    var response=call(label,"ChessRogueQueryCsReq",Map.of(),0); profile(response,10);
    var game=nested(response,8); board(game,1,201,200,false);
    check(number(nested(game,2),1)==2,"existing-record-status-literal2-from-native-getter");
    check(miracles(game).equals(Set.of(4)),"same-paid-miracle4-use0-total1-in-query");
    check(sha(game).equals(expectedHash),"complete-original-board-and-held-game-snapshot-retained-"+label);
    return game;
  }
  private void ordinaryScene(DynamicMessage value) {
    check(number(value,"plane_id")==20001 && number(value,"floor_id")==20001001
        && number(value,"entry_id")==2000101,"actual-return-original-ordinary-scene");
  }
  private void ordinaryBars(DynamicMessage lineup,JsonObject original) {
    var avatars=(List<?>)field(lineup,"avatar_list");
    check(avatars.size()==1,"ordinary-lineup-has-only-owned-original-avatar");
    var actor=(DynamicMessage)avatars.getFirst();
    check(number(actor,"id")==8001 && number(actor,"hp")==original.get("currentHp").getAsInt()
        && number(nested(actor,"sp_bar"),"cur_sp")==original.get("currentSp").getAsInt(),
        "return-stage-reports-ordinary-bars-independent-of-Chess5000-2500");
  }
  private Map<String,Object> execute(JsonObject input,BufferedReader control) throws Exception {
    sourceBoards=input.getAsJsonArray("boards"); String mode=input.get("mode").getAsString();
    require(Set.of("saved-leave","paused-resume","resaved-leave").contains(mode),"Unknown owned saved-record phase");
    String expected=input.get("gameInfoHash").getAsString();
    int uid=number(callNamed("token","PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),
        "token",input.get("comboToken").getAsString(),"platform",1),0),"uid");
    call("PlayerLoginCsReq"); call("PlayerLoginFinishCsReq"); check(uid==10001,"same-owned-account-and-uid");
    String classicHash=sha(classic()); check(classicHash.equals(input.get("classicHash").getAsString()),"classic-universe-unborrowed-before-saved-record-chain");
    var info=completeQuery("query-existing-record-before-"+mode,expected);
    JsonObject ordinary=input.getAsJsonObject("ordinaryAvatar"); boolean paused=!mode.equals("paused-resume");
    if (mode.equals("paused-resume")) {
      ordinaryScene(scene()); ordinaryBars(nested(call("GetCurLineupDataCsReq"),"lineup"),ordinary);
      call("saved-record-blocks-another-first-Start","ChessRogueStartCsReq",Map.of(13,101,9,6,7,List.of(8001)),-1);
      var resumed=call("actual5437-existing-record-after-normal-H2-restart","ChessRogueEnterCsReq",Map.of(8,101),0);
      sourceScene(nested(nested(resumed,6),14),Set.of(34,35));
      check(number(resumed,10)==101 && sha(nested(resumed,3)).equals(expected),"actual-resume-does-not-create-reprice-or-reset-record");
      int entity=merchant(); quote(shop("paid-stock-restored-after-record-resume",entity,0,0),true);
      shop("resumed-record-cannot-buy-same-miracle-again",entity,4,-1); bars();
    } else {
      sourceScene(scene(),Set.of(34,35)); bars();
      reply("nonempty-original-empty5462-body-rejected","ChessRogueLeaveCsReq",5462,new byte[]{8,0},"ChessRogueLeaveScRsp",-1);
      completeQuery("bad-leave-does-not-change-source-record",expected);
      var returned=call("actual5462-save-and-return-ordinary-stage","ChessRogueLeaveCsReq",Map.of(),0);
      check(returned.hasField(descriptor(returned,14)),"actual-leave-has-nonnull-return-stage14");
      ordinaryScene(nested(nested(returned,14),14)); ordinaryBars(nested(nested(returned,14),2),ordinary); profile(returned,12);
      check(returned.hasField(descriptor(returned,6)) && number(nested(returned,6),14)==6,"required-leave-AEM6-retains-selected-aeon6");
      check(!returned.hasField(descriptor(returned,11)),"no-fake-Quit-or-finished-PPAG-payload");
      ordinaryScene(scene()); ordinaryBars(nested(call("GetCurLineupDataCsReq"),"lineup"),ordinary);
      var duplicate=call("duplicate5462-cannot-return-save-again","ChessRogueLeaveCsReq",Map.of(),-1);
      check(!duplicate.hasField(descriptor(duplicate,14)) && !duplicate.hasField(descriptor(duplicate,12)),"rejected-duplicate-no-success-stage-or-profile");
    }
    info=completeQuery("query-existing-record-after-"+mode,expected);
    check(sha(classic()).equals(classicHash),"classic-universe-wire-state-unchanged-by-saved-record-chain");
    check(notifications.isEmpty() && miracleActionNotifications.isEmpty(),"save-resume-neither-grants-acquisition-nor-resettles-battles");
    var out=new LinkedHashMap<String,Object>();
    out.put("passed",true); out.put("uid",uid); out.put("mode",mode); out.put("requests",requests); out.put("checks",checks);
    out.put("events",events); out.put("notifications",notifications); out.put("acquiredMiracleNotifications",miracleActionEvents);
    out.put("gameInfo",view(info)); out.put("gameInfoHash",sha(info)); out.put("classicHash",classicHash);
    out.put("coins",money(info)); out.put("miracles",miracles(info)); out.put("expectedInside",!paused);
    out.put("actualNetworkSaveAndReturn",paused); out.put("clientGameplayVerified",false); out.put("officialReturnMappingRecovered",false);
    return out;
  }
  public static void main(String[] args) {
    var probe=new NativeChessRogueSavedLeaveProbe();
    if (args.length==1 && args[0].equals("--verify-descriptors")) {
      probe.verifySchemas(); System.out.println(new Gson().toJson(Map.of("passed",true,"schemas",probe.schemas,"checks",probe.checks))); return;
    }
    var client=new KcpClient(); int status=1;
    try {
      var control=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
      var input=JsonParser.parseString(control.readLine()).getAsJsonObject();
      var config=new ChannelConfig(); config.nodelay(true,40,2,true); config.setMtu(1400);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE)); config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config,new KcpListener() {
        public void onConnected(Ukcp value) { probe.connected.complete(value); }
        public void handleReceive(ByteBuf bytes,Ukcp value) {
          try { while (bytes.isReadable()) {
            var frame=PacketCodec.decodeNext(bytes); require(frame!=null,"Truncated Chess frame");
            probe.inbox.add(new Frame(frame.cmdId(),frame.body()));
          } } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error,Ukcp value) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp value) { probe.failure.compareAndSet(null,new IllegalStateException("Owned Chess probe disconnected")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[0])),config);
      probe.channel=probe.connected.get(10,TimeUnit.SECONDS); System.out.println(new Gson().toJson(probe.execute(input,control))); status=0;
    } catch (Exception error) {
      System.err.println("FAILED_CHESS_OBSERVATIONS="+new Gson().toJson(probe.events)); error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(status);
  }
}
