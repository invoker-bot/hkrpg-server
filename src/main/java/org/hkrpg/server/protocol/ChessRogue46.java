package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Fixed local 4.6 Start/Select/Enter/Next/Resume writers; no generated protocol edits. */
public final class ChessRogue46 {
  private record Request(Map<Integer,Integer> scalars,List<Integer> avatars) {
    int number(int field) { return scalars.getOrDefault(field,0); }
  }
  public static boolean handle(GameSession session,int command,byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    boolean shop = command == 5675 || command == 5656;
    if (shop && (session.getState() != SessionState.ACTIVE
        || !session.getPlayer().getGameplay().getChessRogue().isInside())) return false;
    if (!shop && !Set.of(5426,5437,5534,5468,5447,5547,5462).contains(command)) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      if (shop) { merchant(session,command,bytes); return true; }
      var manager = session.getPlayer().getUniverseManager().chess();
      var request = switch (command) {
        case 5426 -> read(bytes,Set.of(13,9),7); case 5437 -> read(bytes,Set.of(8),0);
        case 5534 -> read(bytes,Set.of(2),0); case 5468 -> read(bytes,Set.of(13,15),0);
        default -> bytes.length == 0 ? new Request(Map.of(),List.of()) : null;
      };
      var status = request == null ? Retcode.REQ_PARA_INVALID : switch (command) {
        case 5426 -> manager.start(request.number(13),request.number(9),request.avatars());
        case 5437 -> manager.resume(request.number(8)); case 5534 -> manager.select(request.number(2));
        case 5468 -> manager.enter(request.number(13),request.number(15));
        case 5447 -> manager.nextLayer(); case 5462 -> manager.leaveAndSave(); default -> manager.queryStatus();
      };
      String name = switch (command) {
        case 5426 -> "ChessRogueStartScRsp"; case 5437 -> "ChessRogueEnterScRsp";
        case 5534 -> "ChessRogueSelectCellScRsp"; case 5468 -> "ChessRogueEnterCellScRsp";
        case 5447 -> "ChessRogueEnterNextLayerScRsp"; case 5462 -> "ChessRogueLeaveScRsp"; default -> "ChessRogueQueryScRsp";
      };
      var response = builder(session,name); set(response,"retcode",status.getVal());
      if (status == Retcode.SUCC) {
        if (command == 5462) {
          put(response,14,stage(session)); put(response,12,profile(session));
          var aeon = builder(session,"AEMAPGKPPJA");
          put(aeon,14,session.getPlayer().getGameplay().getChessRogue().getAeon()); put(response,6,aeon.build());
        } else if (command == 5534) sync(session);
        else {
          put(response,switch (command) { case 5426 -> 13; case 5437 -> 3; case 5468 -> 12; case 5447 -> 11; default -> 8; },snapshot(session));
          if (command == 5547) put(response,10,profile(session));
          if (command != 5547) {
            put(response,switch (command) { case 5426 -> 7; case 5437 -> 6; default -> 8; },stage(session));
            if (command == 5437) put(response,10,session.getPlayer().getGameplay().getChessRogue().getArea());
          }
        }
      }
      session.sendClientPacket(name,response.build().toByteArray());
      return true;
    }
  }

  private static Request read(byte[] bytes,Set<Integer> fields,int repeated) {
    if (bytes.length > 4096) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      var scalars = new LinkedHashMap<Integer,Integer>(); var avatars = new ArrayList<Integer>();
      int tag;
      while ((tag = input.readTag()) != 0) {
        int field = tag >>> 3;
        if (field == repeated && repeated != 0) {
          if ((tag & 7) == 0) avatars.add(unsigned(input));
          else if ((tag & 7) == 2) {
            int size = input.readRawVarint32();
            if (size < 0 || size > input.getBytesUntilLimit() && input.getBytesUntilLimit() >= 0) return null;
            int prior = input.pushLimit(size);
            while (input.getBytesUntilLimit() > 0) { avatars.add(unsigned(input)); if (avatars.size() > 4) return null; }
            input.popLimit(prior);
          } else return null;
          if (avatars.size() > 4) return null;
        } else if (fields.contains(field) && (tag & 7) == 0 && !scalars.containsKey(field)) scalars.put(field,unsigned(input));
        else return null;
      }
      return input.isAtEnd() ? new Request(Map.copyOf(scalars),List.copyOf(avatars)) : null;
    } catch (IOException | IllegalArgumentException malformed) { return null; }
  }
  private static int unsigned(CodedInputStream input) throws IOException {
    long value = input.readUInt64();
    if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Unsupported Chess unsigned value");
    return (int)value;
  }
  private static void put(DynamicMessage.Builder builder,int number,Object value) {
    var field = builder.getDescriptorForType().findFieldByNumber(number);
    if (field == null) throw new ProtocolException("Missing verified Chess field " + number);
    if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM && value instanceof Integer ordinal) {
      value = field.getEnumType().findValueByNumber(ordinal);
      if (value == null) throw new ProtocolException("Unverified Chess enum ordinal");
    }
    builder.setField(field,value);
  }
  private static void repeat(DynamicMessage.Builder builder,int number,Object value) {
    var field = builder.getDescriptorForType().findFieldByNumber(number);
    if (field == null || !field.isRepeated()) throw new ProtocolException("Missing verified Chess repeated field");
    builder.addRepeatedField(field,value);
  }

  private static DynamicMessage stage(GameSession session) throws IOException {
    var player = session.getPlayer(); var stage = builder(session,"MANINNKMFHG");
    put(stage,14,session.getProtocol().clientSchema().parse("SceneInfo",session.getProtocol().translate(
        "SceneInfo",player.getScene().toProto().toByteArray(),true)));
    put(stage,2,session.getProtocol().clientSchema().parse("LineupInfo",session.getProtocol().translate(
        "LineupInfo",player.getCurrentLineup().toProto().toByteArray(),true)));
    return stage.build();
  }
  private static DynamicMessage money(GameSession session) {
    var money = builder(session,"AGEOGDOGDMM");
    put(money,13,session.getPlayer().getGameplay().getChessRogue().getCoins()); return money.build();
  }
  private static DynamicMessage profile(GameSession session) {
    var source = ChessRogueProfile.current(session.getPlayer());
    var profile = builder(session,"HIMJKAMHLMJ");
    source.unlockedAreaIds().forEach(id -> repeat(profile,11,id));
    source.firstRewardTakenAreaIds().forEach(id -> repeat(profile,10,id));
    // These shared consumers require nonnull containers even in Chess201. Their collections
    // remain empty: this guide has no Nous acquisitions or currency281013 ledger to publish.
    put(profile,9,builder(session,"MOOKMKEKMKC").build());
    put(profile,12,builder(session,"DDAFEPBGOHK").build());
    return profile.build();
  }
  private static DynamicMessage cell(GameSession session,ChessRogueRoutes.Cell source) {
    var run = session.getPlayer().getGameplay().getChessRogue(); var cell = builder(session,"CGMCMAKMJDK");
    put(cell,1,source.id()); put(cell,13,source.x()); put(cell,7,source.y());
    put(cell,14,source.blockId()); put(cell,6,run.status(source.id()));
    var room = ChessRogueRoutes.room(source); if (room != null) put(cell,10,room.id());
    return cell.build();
  }
  public static DynamicMessage snapshot(GameSession session) {
    var run = session.getPlayer().getGameplay().getChessRogue(); var source = ChessRogueRoutes.board(run.getLayerIndex());
    var game = builder(session,"DJEKEGMEHLK"); put(game,14,201);
    var aeon = builder(session,"AEMAPGKPPJA"); put(aeon,14,run.getAeon()); put(game,1,aeon.build());
    var layout = builder(session,"IKCONCOKNAN"); put(layout,15,source.width()); put(layout,9,source.height());
    put(layout,4,source.start()); put(layout,3,source.end());
    source.cells().values().stream().sorted(Comparator.comparingInt(ChessRogueRoutes.Cell::id))
        .forEach(row -> repeat(layout,7,cell(session,row)));
    var board = builder(session,"DPELLHBJGML"); put(board,5,source.id()); put(board,8,run.getCurrentCell()); put(board,9,layout.build());
    run.allowedCells().forEach(id -> repeat(board,15,id));
    for (int id : source.path()) {
      if (source.path().indexOf(id) > source.path().indexOf(run.getEnteredCell())) break;
      var history = builder(session,"GKDJBPJOGPO"); put(history,2,id); repeat(board,10,history.build());
    }
    var area = builder(session,"LKPCDHCKHKP"); put(area,9,run.getArea()); put(area,12,source.layer());
    put(area,11,run.getLayerIndex()); put(area,1,2); put(area,4,run.getActionPoints()); put(area,2,board.build());
    put(game,2,area.build()); put(game,10,money(session));
    var miracles = builder(session,"GMIJHCONFIH");
    run.getMiracles().values().stream().sorted(Comparator.comparingInt(ChessRogueState.Miracle::getId)).forEach(item -> {
      var row = builder(session,"KOFMGCNIMIN"); put(row,2,item.getId()); put(row,1,item.getUseCount());
      put(row,4,item.getTotalCount()); repeat(miracles,1,row.build());
    });
    var owned = builder(session,"NHLBNBBDHMI"); put(owned,3,miracles.build()); put(game,3,owned.build());
    return game.build();
  }

  /** Fixed native 201 provider and case894 AddMiracleItem; called only after the purchase save. */
  public static void syncMiracleAcquisition(Player player,int id) {
    if (!player.isOnline() || player.getSession() == null || !player.getSession().getProtocol().experimental()) return;
    var run = player.getGameplay().getChessRogue(); var item = run.getMiracles().get(id);
    if (player.getSession().getPlayer() != player || !run.isInside() || !ChessRogueRoutes.valid(run)
        || id != 4 || item == null || item.getId() != id || item.getUseCount() != 0 || item.getTotalCount() != 1) return;
    var session = player.getSession();
    var row = builder(session,"KOFMGCNIMIN"); put(row,2,id); put(row,1,item.getUseCount()); put(row,4,item.getTotalCount());
    var acquired = builder(session,"IOMEMIEAPJO"); put(acquired,13,row.build());
    var detail = builder(session,"PKHKEEKIODC"); put(detail,894,acquired.build());
    var action = builder(session,"MAOKMPOELCO"); put(action,3,detail.build());
    var notify = builder(session,"SyncRogueCommonActionResultScNotify"); put(notify,1,201); repeat(notify,15,action.build());
    session.sendClientPacket("SyncRogueCommonActionResultScNotify",notify.build().toByteArray());
  }

  public static void sync(Player player) {
    if (!player.isOnline() || player.getSession() == null || !player.getSession().getProtocol().experimental()) return;
    sync(player.getSession());
  }
  private static void sync(GameSession session) {
    var run = session.getPlayer().getGameplay().getChessRogue(); var board = ChessRogueRoutes.board(run.getLayerIndex());
    var update = builder(session,"ChessRogueCellUpdateNotify"); put(update,1,board.id());
    board.cells().values().stream().sorted(Comparator.comparingInt(ChessRogueRoutes.Cell::id))
        .forEach(row -> repeat(update,7,cell(session,row)));
    session.sendClientPacket("ChessRogueCellUpdateNotify",update.build().toByteArray());
    var allowed = builder(session,"ChessRogueUpdateAllowedSelectCellScNotify"); put(allowed,4,board.id());
    run.allowedCells().forEach(id -> repeat(allowed,9,id));
    session.sendClientPacket("ChessRogueUpdateAllowedSelectCellScNotify",allowed.build().toByteArray());
    var money = builder(session,"ChessRogueUpdateMoneyInfoScNotify"); put(money,14,money(session));
    session.sendClientPacket("ChessRogueUpdateMoneyInfoScNotify",money.build().toByteArray());
  }

  private static void merchant(GameSession session,int command,byte[] bytes) {
    boolean query = command == 5675;
    var request = read(bytes,query ? Set.of(12,13) : Set.of(12,6),0);
    var player = session.getPlayer();
    var status = request == null || request.number(12) <= 0 || query && request.number(13) != 0 || !query && request.number(6) <= 0
        ? Retcode.REQ_PARA_INVALID : query ? ChessRogueShop.status(player,request.number(12))
            : ChessRogueShop.buy(player,request.number(12),request.number(6));
    var name = query ? "GetRogueShopMiracleInfoScRsp" : "BuyRogueShopMiracleScRsp";
    var response = builder(session,name); set(response,"retcode",status.getVal());
    if (status == Retcode.SUCC) {
      var shop = builder(session,"AGKONMPOJKP");
      for (var quote : ChessRogueShop.quotes(player,request.number(12))) {
        var row = builder(session,"ELGOBKCNOHH"); set(row,"LJCEOGPOGBE",quote.id());
        set(row,"GCIHGKCJOGB",!quote.available()); set(row,"BELPGDAGFIH",quote.owned());
        var cost = RogueBuffShop46.cost(session,quote.cost()); set(row,"cost_data",cost); set(row,"KINNMAGDDJJ",cost);
        add(shop,"MAJGMMCOCIG",row.build());
      }
      set(response,"DNPCCFFFBHN",shop.build());
    }
    session.sendClientPacket(name,response.build().toByteArray());
  }
  private ChessRogue46() {}
}
