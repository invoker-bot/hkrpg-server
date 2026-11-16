package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.friends.Friendship;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameServer;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.data.GameData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.TrainCakeCatch;
import org.hkrpg.server.gameplay.TrainCakeRoomRegistry;
import org.hkrpg.server.gameplay.TrainCakeRoomStore;
import org.hkrpg.server.gameplay.TrainCakeSocialSettings;
import org.hkrpg.server.gameplay.TrainCakeRoomTravel;
import org.hkrpg.server.gameplay.TrainCakeVisits;

/** Native room access and room packets. */
public final class TrainCakeSocial46 {
  interface Resolver {
    Player trainCakePlayer(int uid);
    boolean trainCakeFriends(int visitorUid, int ownerUid);
  }

  private record Pending(int ownerUid, long expiresAtNanos) {}
  private record Access(Retcode code, Player owner) {}
  private record MotionAccess(int ownerUid, long epoch, long expiresAtNanos, Retcode code) {}
  private static final Map<GameServer, TrainCakeRoomRegistry> REGISTRIES = new WeakHashMap<>();
  private static final Map<GameSession, Pending> PENDING = new WeakHashMap<>();
  private static final Map<GameSession, MotionAccess> MOTION_ACCESS = new WeakHashMap<>();
  private static long accessEpoch;

  /** A committed setting change invalidates cached movement permissions immediately. */
  public static void invalidateRoomAccess() {
    synchronized (MOTION_ACCESS) { accessEpoch++; MOTION_ACCESS.clear(); }
  }

  private static Retcode motionAccess(GameSession session, int ownerUid) {
    for (int attempt = 0; attempt < 3; attempt++) {
      long epoch, now = System.nanoTime();
      synchronized (MOTION_ACCESS) {
        epoch = accessEpoch;
        var cached = MOTION_ACCESS.get(session);
        if (cached != null && cached.ownerUid() == ownerUid && cached.epoch() == epoch
            && cached.expiresAtNanos() - now > 0) return cached.code();
      }
      // Never hold the cache lock while resolving or locking the owner's player record.
      var code = access(session, ownerUid, false).code();
      synchronized (MOTION_ACCESS) {
        if (epoch != accessEpoch) continue;
        MOTION_ACCESS.put(session, new MotionAccess(ownerUid, epoch,
            System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250), code));
        return code;
      }
    }
    return Retcode.SYSTEM_BUSY;
  }

  static TrainCakeRoomRegistry registry(GameSession session) {
    synchronized (REGISTRIES) {
      return REGISTRIES.computeIfAbsent(session.getServer(), ignored -> new TrainCakeRoomRegistry());
    }
  }

  /** Remove the live session before logout saves its separate return bookmark. */
  public static void disconnect(GameSession session) {
    synchronized (PENDING) { PENDING.remove(session); }
    synchronized (MOTION_ACCESS) { MOTION_ACCESS.remove(session); }
    TrainCakeRoomRegistry existing;
    synchronized (REGISTRIES) { existing = REGISTRIES.get(session.getServer()); }
    if (existing == null) return;
    var left = existing.leave(session);
    if (left != null) broadcastSnapshot(session, left.ownerUid());
  }

  static boolean handle(GameSession session, int command, byte[] bytes) {
    boolean scene = command == session.getProtocol().clientCommand("EnterSceneCsReq");
    boolean box = command == 10157 || command == 10144;
    if (box && session.getState() == SessionState.ACTIVE && session.getPlayer() != null
        && registry(session).current(session) != null) {
      socialBox(session, command, bytes);
      return true;
    }
    if (command != 9736 && command != 9726 && command != 9655 && command != 9761 && !scene) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    if (scene) return sceneEntry(session, bytes);
    if (command == 9736) precheck(session, bytes);
    else if (command == 9726) onlineRoom(session, bytes);
    else if (command == 9655) ownerRoom(session, bytes);
    else movement(session, bytes);
    return true;
  }

  private static void ownerRoom(GameSession session, byte[] bytes) {
    var response = builder(session, "SocialPlayGetOwnerRoomDataScRsp");
    Retcode code = Retcode.REQ_PARA_INVALID;
    try {
      if (bytes == null || bytes.length > 32) throw new IllegalArgumentException();
      var req = session.getProtocol().clientSchema().parse("SocialPlayGetOwnerRoomDataCsReq", bytes);
      var type = (com.google.protobuf.Descriptors.EnumValueDescriptor) value(req, "LHLKJIDFLIN");
      if (req.getUnknownFields().asMap().isEmpty() && only(req, "LHLKJIDFLIN") && type.getNumber() == 1) {
        var access = access(session, session.getPlayer().getUid(), false);
        code = access.code();
        if (code == Retcode.SUCC) {
          var membership = registry(session).members(session.getPlayer().getUid());
          if (membership == null) membership = new TrainCakeRoomRegistry.Membership(session.getPlayer().getUid(), List.of());
          set(response, "DJMHHMMABJL", snapshot(session, access.owner(), membership));
        }
      }
    } catch (Exception invalid) { code = Retcode.PLAYER_DATA_ERROR; }
    set(response, "retcode", code.getVal());
    session.sendClientPacket("SocialPlayGetOwnerRoomDataScRsp", response.build().toByteArray());
  }

  private static void movement(GameSession session, byte[] bytes) {
    var response = builder(session, "JKILAALMFIB");
    var membership = registry(session).current(session);
    Retcode result = Retcode.REQ_PARA_INVALID;
    DynamicMessage motion = null;
    if (membership == null || !inSocialScene(session.getPlayer())) result = Retcode.NOT_IN_SCENE;
    else try {
      var allowed = motionAccess(session, membership.ownerUid());
      if (allowed != Retcode.SUCC) {
        set(response, "retcode", allowed.getVal());
        session.sendClientPacket("JKILAALMFIB", response.build().toByteArray());
        org.hkrpg.server.GameDiagnostics.outcome(session, "JGAMELLGLGE", membership.ownerUid(),
            allowed, "train-room-motion-access", "");
        return;
      }
      if (bytes == null || bytes.length == 0 || bytes.length > 512) throw new IllegalArgumentException();
      var req = session.getProtocol().clientSchema().parse("JGAMELLGLGE", bytes);
      var field = req.getDescriptorForType().findFieldByName("NJDCPMENIFG");
      if (!req.getUnknownFields().asMap().isEmpty() || !only(req, "NJDCPMENIFG") || !req.hasField(field))
        throw new IllegalArgumentException();
      motion = (DynamicMessage) req.getField(field);
      if (!validMotion(motion) || !registry(session).motion(session, motion.toByteArray()))
        throw new IllegalArgumentException();
      result = Retcode.SUCC;
    } catch (Exception invalid) { result = Retcode.REQ_PARA_INVALID; }
    set(response, "retcode", result.getVal());
    session.sendClientPacket("JKILAALMFIB", response.build().toByteArray());
    if (result != Retcode.SUCC) {
      org.hkrpg.server.GameDiagnostics.outcome(session, "JGAMELLGLGE", 0, result, "train-room-motion", "");
      return;
    }
    for (var member : membership.members()) if (member != session) {
      try {
        if (motionAccess(member, membership.ownerUid()) != Retcode.SUCC) continue;
        var history = builder(member, "MHNNEFBOPIE");
        set(history, "op_uid", session.getPlayer().getUid()); add(history, "JENKGODEIOM", motion);
        var notify = builder(member, "SocialPlayRoomPlayerMoveScNotify");
        set(notify, "room_owner_uid", membership.ownerUid()); set(notify, "op_uid", session.getPlayer().getUid());
        set(notify, "JENKGODEIOM", history.build());
        member.sendClientPacket("SocialPlayRoomPlayerMoveScNotify", notify.build().toByteArray());
      } catch (RuntimeException failed) {
        org.hkrpg.server.GameDiagnostics.outcome(member, "SocialPlayRoomPlayerMoveScNotify", membership.ownerUid(),
            Retcode.PLAYER_DATA_ERROR, "train-room-motion-send", "");
      }
    }
  }

  private static boolean validMotion(DynamicMessage motion) {
    if (!motion.getUnknownFields().asMap().isEmpty()
        || !only(motion, "pos", "rot", "ICNANKMDJIC", "GHMKFMBDKNE", "GHEMGLKGKLC", "FPHBEHJJBIA", "MGIOLDGHPMA", "time_stamp")
        || !motion.hasField(motion.getDescriptorForType().findFieldByName("pos"))
        || !Float.isFinite((Float) value(motion, "ICNANKMDJIC"))
        || !Float.isFinite((Float) value(motion, "GHEMGLKGKLC"))
        || (Long) value(motion, "time_stamp") < 0) return false;
    for (var name : List.of("pos", "rot", "GHMKFMBDKNE")) {
      var field = motion.getDescriptorForType().findFieldByName(name);
      if (!motion.hasField(field)) continue;
      var vector = (DynamicMessage) motion.getField(field);
      if (!vector.getUnknownFields().asMap().isEmpty() || !only(vector, "x", "y", "z")) return false;
      for (var axis : List.of("x", "y", "z"))
        if (Math.abs((long) number(vector, axis)) > 100_000_000L) return false;
    }
    return true;
  }

  private static void socialBox(GameSession session, int command, byte[] bytes) {
    boolean open = command == 10157;
    String responseName = open ? "TrainCakeCatchOpenBoxScRsp" : "TrainCakeCatchSearchScRsp";
    var response = builder(session, responseName);
    var membership = registry(session).current(session);
    var player = session.getPlayer();
    int cat = open ? 0 : TrainCakeCatch46.readCat(bytes);
    Retcode result = Retcode.REQ_PARA_INVALID;
    boolean valid = open ? bytes != null && bytes.length == 0 : cat > 0;
    if (valid && membership != null && inSocialScene(player)) {
      var allowed = access(session, membership.ownerUid(), open);
      result = allowed.code();
      if (result == Retcode.SUCC) synchronized (player) {
        try {
          boolean own = membership.ownerUid() == player.getUid();
          long now = TrainCakeCatch.now();
          if (own) {
            var action = open ? TrainCakeCatch.openInOwnRoom(player, now)
                : TrainCakeCatch.searchInOwnRoom(player, cat, now);
            result = action.retcode();
            if (result == Retcode.SUCC && open) {
              set(response, "CMHKFNFKGOI", action.state().getRefreshAt());
              addSpots(session, response, action.state().getSpots());
            }
          } else {
            var store = TrainCakeRoomStore.forPlayer(player);
            if (open) {
              var room = store.open(allowed.owner(), now);
              set(response, "CMHKFNFKGOI", room.refreshAt());
              addSpots(session, response, room.spots());
            } else {
              var room = store.peek(membership.ownerUid(), now);
              if (room == null && TrainCakeCatch.snapshot(allowed.owner()).getRefreshAt() > now)
                room = store.open(allowed.owner(), now);
              result = room == null ? Retcode.INTERACT_TYPE_INVALID : TrainCakeVisits.capture(player, room, cat, now);
            }
          }
          if (result == Retcode.SUCC && !open) {
            int total = TrainCakeVisits.totalCounts(player).get(cat);
            set(response, "PCPDHEIEJFO", cat);
            set(response, "NIELDCLPOLA", TrainCakeCatch46.count(session, cat, total));
            TrainCakeCatch46.addWeekRecord(session, response, TrainCakeCatch.snapshot(player));
            var notify = builder(session, "TrainCakeCatchDataScNotify");
            add(notify, "NIELDCLPOLA", TrainCakeCatch46.count(session, cat, total));
            session.sendClientPacket("TrainCakeCatchDataScNotify", notify.build().toByteArray());
          }
        } catch (com.mongodb.MongoException failed) {
          org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-room-box", cat, 0, failed);
          result = Retcode.SERVER_INTERNAL_ERROR;
        } catch (RuntimeException invalid) { result = Retcode.PLAYER_DATA_ERROR; }
      }
    } else if (valid) result = Retcode.NOT_IN_SCENE;
    set(response, "retcode", result.getVal());
    SceneActionDiagnostics46.rejected(session, open ? "TrainCakeCatchOpenBoxCsReq" : "TrainCakeCatchSearchCsReq",
        "train-room-box-preconditions", result, 0, 0, 0, 0);
    session.sendClientPacket(responseName, response.build().toByteArray());
    if (result == Retcode.SUCC && open) broadcastSnapshot(session, membership.ownerUid());
  }

  private static void addSpots(GameSession session, DynamicMessage.Builder target,
      List<org.hkrpg.server.gameplay.TrainCakeCatchState.Spot> spots) {
    for (var spot : spots) {
      var row = builder(session, "IKAHKMNCFDN");
      set(row, "PCPDHEIEJFO", spot.getCat()); set(row, "slot", TrainCakeCatch.clientSlot(spot.getSlot()));
      add(target, "BJPHDKEBOBK", row.build());
    }
  }

  private static void onlineRoom(GameSession session, byte[] bytes) {
    var response = builder(session, "SocialPlayGetOnlineRoomDataScRsp");
    var membership = registry(session).current(session);
    var code = bytes == null || bytes.length != 0 ? Retcode.REQ_PARA_INVALID
        : membership == null || !inSocialScene(session.getPlayer()) ? Retcode.NOT_IN_SCENE : Retcode.SUCC;
    if (code == Retcode.SUCC) try {
      var allowed = access(session, membership.ownerUid(), false);
      code = allowed.code();
      if (code == Retcode.SUCC) {
        set(response, "DJMHHMMABJL", snapshot(session, allowed.owner(), membership));
        // The native room callback branches on this presence flag before reading the snapshot.
        set(response, "OJPDDAOJJKC", true);
      }
    } catch (RuntimeException invalid) { code = Retcode.PLAYER_DATA_ERROR; }
    set(response, "retcode", code.getVal());
    session.sendClientPacket("SocialPlayGetOnlineRoomDataScRsp", response.build().toByteArray());
  }

  private static boolean sceneEntry(GameSession session, byte[] bytes) {
    DynamicMessage req;
    try { req = session.getProtocol().clientSchema().parse("EnterSceneCsReq", bytes); }
    catch (Exception invalid) { return false; }
    var idField = req.getDescriptorForType().findFieldByName("scene_identifier");
    var identifier = req.hasField(idField) ? (DynamicMessage) req.getField(idField) : null;
    var teleportField = identifier == null ? null : identifier.getDescriptorForType().findFieldByName("teleport_info");
    var teleport = teleportField != null && identifier.hasField(teleportField)
        ? (DynamicMessage) identifier.getField(teleportField) : null;
    int entry = number(req, "entry_id");
    boolean socialRequest = teleport != null && (entry == 100000399 || entry == 100000353);
    var existing = registry(session).current(session);
    if (!socialRequest && existing == null) return false;
    if (!socialRequest) { leave(session, req); return true; }

    int ownerUid = number(teleport, "teleport_id"), reason = number(teleport, "reason");
    var code = Retcode.REQ_PARA_INVALID;
    boolean joined = false;
    int previousOwnerUid = existing == null ? 0 : existing.ownerUid();
    try {
      boolean self = ownerUid == session.getPlayer().getUid();
      boolean valid = req.getUnknownFields().asMap().isEmpty()
          && only(req, "entry_id", "teleport_id", "interact_id", "is_close_map", "scene_identifier")
          && number(req, "teleport_id") == 0
          && identifier.getUnknownFields().asMap().isEmpty()
          && only(identifier, "floor_id", "teleport_info") && number(identifier, "floor_id") == 10000003
          && teleport.getUnknownFields().asMap().isEmpty()
          && only(teleport, "teleport_id", "reason", "LHLKJIDFLIN")
          && ownerUid > 0 && reason >= 2 && reason <= 5
          && entry == (self ? 100000399 : 100000353);
      if (valid && existing != null && existing.ownerUid() == ownerUid) {
        code = inSocialScene(session.getPlayer()) ? access(session, ownerUid, false).code() : Retcode.SYSTEM_BUSY;
      } else if (valid && pending(session, ownerUid)) {
        var allowed = access(session, ownerUid, false);
        code = allowed.code();
        if (code == Retcode.SUCC) {
          code = existing == null ? enter(session, allowed.owner(), entry)
              : switchRoom(session, allowed.owner(), entry, previousOwnerUid);
          joined = code == Retcode.SUCC;
        }
      }
    } catch (RuntimeException invalid) { code = Retcode.PLAYER_DATA_ERROR; }
    var response = builder(session, "EnterSceneScRsp");
    set(response, "retcode", code.getVal());
    if (code == Retcode.SUCC) {
      set(response, "scene_identifier", identifier);
      set(response, "is_close_map", value(req, "is_close_map"));
    }
    org.hkrpg.server.GameDiagnostics.outcome(session, "EnterSceneCsReq", entry, code,
        "train-room-entry", "owner=" + ownerUid);
    session.sendClientPacket("EnterSceneScRsp", response.build().toByteArray());
    if (code == Retcode.SUCC) {
      if (joined && previousOwnerUid != 0 && previousOwnerUid != ownerUid)
        broadcastSnapshot(session, previousOwnerUid);
      if (joined) broadcastJoin(session, ownerUid);
      broadcastSnapshot(session, ownerUid);
    }
    return true;
  }

  private static Retcode switchRoom(GameSession session, Player owner, int entryId, int previousOwner) {
    var player = session.getPlayer(); var rooms = registry(session);
    synchronized (player) {
      synchronized (rooms) {
        if (!inSocialScene(player) || !TrainCakeRoomTravel.hasPending(player) || !rooms.canJoin(session, owner.getUid()))
          return Retcode.SYSTEM_BUSY;
        var entrance = GameData.getMapEntranceExcelMap().get(entryId);
        var floor = GameData.getFloorInfo(10000, 10000003);
        if (entrance == null || entrance.getPlaneID() != 10000 || entrance.getFloorID() != 10000003
            || floor == null || !floor.isGroupInDimension(entrance.getStartGroupID(), 11001))
          return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
        var anchor = floor.getAnchorInfo(entrance.getStartGroupID(), entrance.getStartAnchorID());
        if (anchor == null) return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
        var previous = player.captureSceneLocation();
        var entities = new IdentityHashMap<emu.lunarcore.game.avatar.GameAvatar, Integer>();
        for (var avatar : player.getAvatars().getAvatars().values()) entities.put(avatar, avatar.getEntityId());
        try {
          if (!player.loadFreshTrainRoomScene(10000, 10000003, entryId, anchor.getPos(), anchor.getRot(), 11001))
            throw new IllegalStateException("Room scene could not be loaded");
          if (rooms.join(session, owner.getUid()) == null) throw new IllegalStateException("Room membership changed");
          synchronized (PENDING) { PENDING.remove(session); }
          publishScene(session);
          return Retcode.SUCC;
        } catch (RuntimeException failed) {
          rooms.join(session, previousOwner);
          player.restoreSceneLocation(previous);
          entities.forEach((avatar, id) -> avatar.setEntityId(id));
          try { player.save(); }
          catch (RuntimeException rollback) {
            org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-room-switch-rollback", 0, 0, rollback);
          }
          org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-room-switch", 0, 0, failed);
          return Retcode.SERVER_INTERNAL_ERROR;
        }
      }
    }
  }

  private static Retcode enter(GameSession session, Player owner, int entryId) {
    var player = session.getPlayer();
    synchronized (player) {
      var entrance = GameData.getMapEntranceExcelMap().get(entryId);
      var floor = GameData.getFloorInfo(10000, 10000003);
      if (entrance == null || entrance.getPlaneID() != 10000 || entrance.getFloorID() != 10000003
          || floor == null || !floor.isGroupInDimension(entrance.getStartGroupID(), 11001))
        return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      var anchor = floor.getAnchorInfo(entrance.getStartGroupID(), entrance.getStartAnchorID());
      if (anchor == null) return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
      var prepared = TrainCakeRoomTravel.prepare(player);
      if (prepared != Retcode.SUCC) return prepared;
      try {
        if (!player.loadTrainRoomScene(10000, 10000003, entryId, anchor.getPos(), anchor.getRot(), 11001)) {
          TrainCakeRoomTravel.returnToOrigin(player);
          return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
        }
        var joined = registry(session).join(session, owner.getUid());
        if (joined == null) {
          TrainCakeRoomTravel.returnToOrigin(player);
          return Retcode.SYSTEM_BUSY;
        }
        synchronized (PENDING) { PENDING.remove(session); }
        publishScene(session);
        return Retcode.SUCC;
      } catch (RuntimeException failed) {
        registry(session).leave(session);
        var restored = TrainCakeRoomTravel.returnToOrigin(player);
        org.hkrpg.server.GameDiagnostics.saveFailed(player, "train-room-enter", 0, 0, failed);
        return restored == Retcode.SUCC ? Retcode.SERVER_INTERNAL_ERROR : restored;
      }
    }
  }

  private static void leave(GameSession session, DynamicMessage req) {
    var player = session.getPlayer();
    var membership = registry(session).current(session);
    var code = !req.getUnknownFields().asMap().isEmpty()
        || !only(req, "entry_id", "teleport_id", "interact_id", "is_close_map", "scene_identifier")
        ? Retcode.REQ_PARA_INVALID : TrainCakeRoomTravel.returnToOrigin(player);
    if (code == Retcode.SUCC) {
      registry(session).leave(session);
      publishScene(session);
    }
    var response = builder(session, "EnterSceneScRsp");
    set(response, "retcode", code.getVal());
    if (code == Retcode.SUCC) {
      var id = builder(session, "SceneIdentifier"); set(id, "floor_id", player.getFloorId());
      set(response, "scene_identifier", id.build());
      set(response, "is_close_map", value(req, "is_close_map"));
    }
    session.sendClientPacket("EnterSceneScRsp", response.build().toByteArray());
    if (code == Retcode.SUCC && membership != null) broadcastSnapshot(session, membership.ownerUid());
  }

  private static boolean pending(GameSession session, int ownerUid) {
    synchronized (PENDING) {
      var pending = PENDING.get(session);
      return pending != null && pending.ownerUid() == ownerUid && pending.expiresAtNanos() - System.nanoTime() > 0;
    }
  }

  private static void publishScene(GameSession session) {
    if (!session.getPlayer().isOnline()) return;
    session.send(emu.lunarcore.server.packet.CmdId.SyncServerSceneChangeNotify);
    session.send(new emu.lunarcore.server.packet.send.PacketEnterSceneByServerScNotify(session.getPlayer()));
  }

  static boolean inSocialScene(Player player) {
    var scene = player.getScene();
    return scene != null && scene.getPlaneId() == 10000 && scene.getFloorId() == 10000003
        && scene.getDimensionId() == 11001 && scene.getStoryLineId() == 0;
  }

  static Retcode interact(GameSession session, int config, int entity) {
    var player = session.getPlayer();
    var membership = registry(session).current(session);
    if (membership == null || !inSocialScene(player)) return Retcode.NOT_IN_SCENE;
    var allowed = access(session, membership.ownerUid(), false);
    if (allowed.code() != Retcode.SUCC) return allowed.code();
    var target = player.getScene().getEntityById(entity);
    if (!(target instanceof emu.lunarcore.game.scene.entity.EntityProp prop))
      return Retcode.SCENE_ENTITY_NOT_EXIST;
    if (prop.getPropType() != emu.lunarcore.game.enums.PropType.PROP_ORDINARY)
      return Retcode.INTERACT_TYPE_INVALID;
    var row = GameData.getInteractExcelMap().get(config);
    if (row == null) return Retcode.INTERACT_CONFIG_NOT_EXIST;
    if (row.getItemCostList() == null || !row.getItemCostList().isEmpty()
        || row.getInteractCostType() == null
        || !(row.getInteractCostType().isBlank() || row.getInteractCostType().equals("None")))
      return Retcode.INTERACT_TYPE_INVALID;
    var code = org.hkrpg.server.gameplay.SceneInteractions.validate(player, config, entity);
    if (code != Retcode.SUCC) return code;
    return prop.setState(row.getTargetState()) ? Retcode.SUCC : Retcode.UNSUPPORTED_PROP_STATE;
  }

  private static Player lookupOwner(GameSession session, int uid) {
    return session.getPlayer().getUid() == uid ? session.getPlayer() : lookup(session, uid);
  }

  private static DynamicMessage snapshot(GameSession session, Player owner, TrainCakeRoomRegistry.Membership membership) {
    var room = builder(session, "POBLGKIGINE");
    set(room, "room_owner_uid", owner.getUid());
    set(room, "DLKOEIANIMK", owner.getUid() == session.getPlayer().getUid() ? 4650 : 550);
    var outer = builder(session, "PBMDEBCGNMD");
    var container = builder(session, "MNCBEDBDDHL");
    set(container, "JKNLCEEDAHJ", builder(session, "KHADHDLCNOI").build());
    set(outer, "IKJNIKIGFLF", container.build());
    var box = builder(session, "JPAOIPLDKHB");
    set(box, "NLJBJMDPBGN", TrainCakeDiy46.toProto(session, owner));
    set(box, "HBKEPFKDAMI", 1);
    long now = TrainCakeCatch.now();
    var batch = TrainCakeRoomStore.forPlayer(session.getPlayer()).peek(owner.getUid(), now);
    var legacy = TrainCakeCatch.snapshot(owner);
    var spots = batch != null ? batch.spots() : legacy.getRefreshAt() > now ? legacy.getSpots() : List.<org.hkrpg.server.gameplay.TrainCakeCatchState.Spot>of();
    long refresh = batch != null ? batch.refreshAt() : legacy.getRefreshAt() > now ? legacy.getRefreshAt() : 0;
    set(box, "CMHKFNFKGOI", refresh);
    for (var spot : spots) {
      var row = builder(session, "IKAHKMNCFDN"); set(row, "PCPDHEIEJFO", spot.getCat()); set(row, "slot", TrainCakeCatch.clientSlot(spot.getSlot()));
      add(box, "BJPHDKEBOBK", row.build());
    }
    set(outer, "PKHFMJDNKOE", box.build()); set(room, "LNMGOFCOMED", outer.build());
    for (var member : membership.members()) add(room, "player_info", memberInfo(session, member));
    return room.build();
  }

  private static DynamicMessage memberInfo(GameSession recipient, GameSession member) {
    var player = member.getPlayer();
    var info = builder(recipient, "JBJNJPAOCDA");
    set(info, "uid", player.getUid()); set(info, "OJPDDAOJJKC", true);
    set(info, "AJIPBLCIONL", simpleInfo(recipient, player));
    var motion = builder(recipient, "JOLAHHGIFHA");
    var last = registry(member).motion(member);
    if (last != null) {
      try { motion.mergeFrom(last); }
      catch (com.google.protobuf.InvalidProtocolBufferException invalid) { throw new IllegalStateException(invalid); }
    } else {
      if (player.getPos() != null) set(motion, "pos", vector(recipient, player.getPos()));
      if (player.getRot() != null) set(motion, "rot", vector(recipient, player.getRot()));
    }
    set(info, "NJDCPMENIFG", motion.build());
    return info.build();
  }

  private static DynamicMessage vector(GameSession session, emu.lunarcore.util.Position position) {
    var v = builder(session, "Vector"); set(v, "x", position.getX()); set(v, "y", position.getY()); set(v, "z", position.getZ());
    return v.build();
  }

  private static void broadcastSnapshot(GameSession source, int ownerUid) {
    var membership = registry(source).members(ownerUid);
    if (membership == null) return;
    for (var recipient : membership.members()) try {
      var allowed = access(recipient, ownerUid, false);
      if (allowed.code() != Retcode.SUCC) continue;
      var notify = builder(recipient, "SocialPlayGameplayOperationScNotify");
      set(notify, "room_owner_uid", ownerUid); set(notify, "op_uid", source.getPlayer().getUid());
      set(notify, "LABNNEGJNHO", snapshot(recipient, allowed.owner(), membership));
      recipient.sendClientPacket("SocialPlayGameplayOperationScNotify", notify.build().toByteArray());
    } catch (RuntimeException failed) {
      org.hkrpg.server.GameDiagnostics.outcome(recipient, "SocialPlayGameplayOperationScNotify", ownerUid,
          Retcode.PLAYER_DATA_ERROR, "train-room-snapshot", "");
    }
  }

  private static void broadcastJoin(GameSession source, int ownerUid) {
    var membership = registry(source).members(ownerUid);
    if (membership == null) return;
    for (var recipient : membership.members()) if (recipient != source) try {
      if (access(recipient, ownerUid, false).code() != Retcode.SUCC) continue;
      var notify = builder(recipient, "SocialPlayGameplayOperationScNotify");
      set(notify, "room_owner_uid", ownerUid); set(notify, "op_uid", source.getPlayer().getUid());
      set(notify, "GNLAECODENP", memberInfo(recipient, source));
      recipient.sendClientPacket("SocialPlayGameplayOperationScNotify", notify.build().toByteArray());
    } catch (RuntimeException failed) {
      org.hkrpg.server.GameDiagnostics.outcome(recipient, "SocialPlayGameplayOperationScNotify", ownerUid,
          Retcode.PLAYER_DATA_ERROR, "train-room-join", "");
    }
  }

  static void ownerBoxOpened(GameSession session) {
    broadcastSnapshot(session, session.getPlayer().getUid());
  }

  private static void precheck(GameSession session, byte[] bytes) {
    var response = builder(session, "SocialPlayPreCheckEnterRoomScRsp");
    var result = Retcode.REQ_PARA_INVALID;
    int ownerUid = 0;
    synchronized (PENDING) { PENDING.remove(session); }
    try {
      if (bytes == null || bytes.length > 64) throw new IllegalArgumentException("Room request size");
      var req = session.getProtocol().clientSchema().parse("SocialPlayPreCheckEnterRoomCsReq", bytes);
      int requested = number(req, "teleport_id");
      int entry = number(req, "entry_id");
      ownerUid = requested == 0 ? session.getPlayer().getUid() : requested;
      boolean own = ownerUid == session.getPlayer().getUid();
      if (req.getUnknownFields().asMap().isEmpty()
          && only(req, "teleport_id", "entry_id", "GJNMHILINHC") && ownerUid > 0
          && (entry == 0 || entry == (own ? 100000399 : 100000353))) {
        var access = access(session, ownerUid, false);
        result = access.code();
        if (result == Retcode.SUCC) {
          var state = TrainCakeCatch.snapshot(access.owner());
          long now = TrainCakeCatch.now();
          var room = TrainCakeRoomStore.forPlayer(session.getPlayer()).peek(ownerUid, now);
          set(response, "CMHKFNFKGOI", room != null ? room.refreshAt()
              : state.getRefreshAt() > now ? state.getRefreshAt() : TrainCakeCatch.nextRefresh(now));
          set(response, "MCCEJINNADF", simpleInfo(session, access.owner()));
          synchronized (PENDING) {
            PENDING.put(session, new Pending(ownerUid, System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2)));
          }
        }
      }
    } catch (Exception invalid) {
      result = Retcode.PLAYER_DATA_ERROR;
    }
    set(response, "retcode", result.getVal());
    org.hkrpg.server.GameDiagnostics.outcome(session, "SocialPlayPreCheckEnterRoomCsReq",
        ownerUid, result, "train-room-precheck", "");
    session.sendClientPacket("SocialPlayPreCheckEnterRoomScRsp", response.build().toByteArray());
  }

  private static Access access(GameSession session, int ownerUid, boolean openingBox) {
    var visitor = session.getPlayer();
    if (ownerUid <= 0 || visitor == null) return new Access(Retcode.REQ_PARA_INVALID, null);
    if (!sourceUnlockValid()) return new Access(Retcode.PLAYER_DATA_ERROR, null);
    if (!visitor.getMissionManager().finished(4020800)) return new Access(Retcode.PLOT_NOT_UNLOCK, null);
    var owner = ownerUid == visitor.getUid() ? visitor : lookup(session, ownerUid);
    if (owner == null || owner.getUid() != ownerUid) return new Access(Retcode.FRIEND_PLAYER_NOT_FOUND, null);
    if (!owner.getMissionManager().finished(4020800)) return new Access(Retcode.PLOT_NOT_UNLOCK, null);
    if (owner != visitor) {
      boolean allowEntry, allowStrangers, allowBox;
      synchronized (owner) {
        var settings = TrainCakeSocialSettings.current(owner);
        allowEntry = settings.allowEntry(); allowStrangers = settings.allowStrangers(); allowBox = settings.allowBox();
      }
      if (!allowEntry || openingBox && !allowBox || !allowStrangers && !friends(session, ownerUid))
        return new Access(Retcode.INTERACT_TYPE_INVALID, null);
    }
    return new Access(Retcode.SUCC, owner);
  }

  private static Player lookup(GameSession session, int uid) {
    if (session instanceof Resolver resolver) return resolver.trainCakePlayer(uid);
    return session.getServer() == null ? null : session.getServer().getPlayerByUid(uid, true);
  }

  private static boolean friends(GameSession session, int ownerUid) {
    var visitor = session.getPlayer();
    if (session instanceof Resolver resolver) return resolver.trainCakeFriends(visitor.getUid(), ownerUid);
    var outbound = visitor.getFriendList().getFriends().get(ownerUid);
    if (outbound == null || !outbound.isFriend()) return false;
    var database = LunarCore.getGameDatabase();
    if (database == null) return false;
    var inbound = database.getObjectByUid(Friendship.class,
        Friendship.generateUniqueKey(ownerUid, visitor.getUid()));
    return inbound != null && inbound.isFriend();
  }

  private static boolean sourceUnlockValid() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      if (Files.isDirectory(root.resolve("4.6"))) root = root.resolve("4.6");
      var rows = JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/FuncUnlockData.json")))
          .getAsJsonArray();
      int matches = 0;
      for (var raw : rows) {
        var row = raw.getAsJsonObject();
        if (row.get("UnlockID").getAsInt() != 5200) continue;
        var conditions = row.getAsJsonArray("Conditions");
        if (conditions.size() != 1 || !conditions.get(0).getAsJsonObject().get("Type").getAsString().equals("FinishMainMission")
            || !conditions.get(0).getAsJsonObject().get("Param").getAsString().equals("4020800")) return false;
        matches++;
      }
      return matches == 1;
    } catch (Exception invalid) { return false; }
  }

  private static DynamicMessage simpleInfo(GameSession session, Player player) {
    var info = builder(session, "PlayerSimpleInfo");
    set(info, "uid", player.getUid());
    set(info, "nickname", Objects.toString(player.getName(), ""));
    set(info, "signature", Objects.toString(player.getSignature(), ""));
    set(info, "level", player.getLevel()); set(info, "head_icon", player.getHeadIcon());
    set(info, "last_active_time", Math.max(0, player.getLastActiveTime()));
    var field = info.getDescriptorForType().findFieldByName("online_status");
    info.setField(field, field.getEnumType().findValueByNumber(player.isOnline() ? 1 : 0));
    return info.build();
  }
  private TrainCakeSocial46() {}
}
