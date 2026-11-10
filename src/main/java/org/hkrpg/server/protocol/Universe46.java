package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.hkrpg.server.gameplay.GameplayState;
import org.hkrpg.server.gameplay.RogueCurrencies;
import org.hkrpg.server.gameplay.RogueEconomy;

/**
 * Static CN 4.6 evidence is recorded in docs/client-protocol-evidence.md; UI acceptance is pending.
 */
public final class Universe46 {
  // Existing OFEDNNJIJFL descriptor; the name-only importer omitted this obfuscated command.
  // Local SendSelectRequest -> d9a8f70 sends 0x162c, WriteTo uses fields 5 / 1474 / 3.
  public static final int PENDING_ACTION_COMMAND = 5676;

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    boolean query = command == session.getProtocol().clientCommand("GetRogueInfoCsReq");
    boolean enter = command == session.getProtocol().clientCommand("EnterRogueMapRoomCsReq");
    String lifecycle =
        Set.of("StartRogueCsReq", "EnterRogueCsReq", "LeaveRogueCsReq", "QuitRogueCsReq").stream()
            .filter(name -> command == session.getProtocol().clientCommand(name))
            .findFirst()
            .orElse(null);
    if (!query && !enter && lifecycle == null && command != PENDING_ACTION_COMMAND) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    if (lifecycle != null) {
      lifecycle(session, lifecycle, bytes);
      return true;
    }
    if (enter) {
      enterRoom(session, bytes);
      return true;
    }
    if (query) {
      var request = session.getProtocol().clientSchema().parse("GetRogueInfoCsReq", bytes);
      if (!request.getUnknownFields().asMap().isEmpty() || !request.getAllFields().isEmpty())
        throw new ProtocolException("Unknown universe query fields");
      synchronized (session.getPlayer()) {
        session.getPlayer().getUniverseManager().settleResults();
        var response = builder(session, "GetRogueInfoScRsp");
        var season = activeSeason(Instant.now());
        set(response, "rogue_game_info", snapshot(session, season));
        if (season == null) set(response, "retcode", Retcode.ROGUE_AREA_INVALID.getVal());
        RogueDialogue46.sync(session);
        session.sendClientPacket("GetRogueInfoScRsp", response.build().toByteArray());
        syncFinish(session);
      }
      return true;
    }
    // Validate raw native scalars and oneofs before protobuf can truncate or merge them.
    var pendingType = session.getProtocol().clientSchema().message("OFEDNNJIJFL");
    if (!NativeUnsignedActionWire.valid(pendingType, bytes, 0)) {
      var response = builder(session, "HandleRogueCommonPendingActionScRsp");
      set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      session.sendClientPacket("HandleRogueCommonPendingActionScRsp", response.build().toByteArray());
      return true;
    }
    var request = session.getProtocol().clientSchema().parse("OFEDNNJIJFL", bytes);
    int batch = number(request, "MPLGEEGOAMC");
    var result = Retcode.REQ_PARA_INVALID;
    boolean rolled = false;
    synchronized (session.getPlayer()) {
      var field = request.getDescriptorForType().findFieldByName("DLKIKBIDJDB");
      var rollField = request.getDescriptorForType().findFieldByName("KBNLGEMNDEN");
      if (request.hasField(rollField)
          && request.getUnknownFields().asMap().isEmpty()
          && only(request, "MPLGEEGOAMC", "KBNLGEMNDEN")) {
        var roll = (DynamicMessage) request.getField(rollField);
        if (roll.getUnknownFields().asMap().isEmpty() && roll.getAllFields().isEmpty()) {
          result = session.getPlayer().getUniverseManager().rerollBlessings(batch);
          rolled = result == Retcode.SUCC;
        }
      } else if (request.hasField(field)
          && request.getUnknownFields().asMap().isEmpty()
          && only(request, "MPLGEEGOAMC", "DLKIKBIDJDB")) {
        var choice = (DynamicMessage) request.getField(field);
        if (choice.getUnknownFields().asMap().isEmpty() && only(choice, "BAOFIGFFMLN"))
          result =
              session
                  .getPlayer()
                  .getUniverseManager()
                  .selectBlessing(batch, number(choice, "BAOFIGFFMLN"));
      } else if (request.getUnknownFields().asMap().isEmpty()
          && only(request, "MPLGEEGOAMC", "CCCIFGFHGIL")) {
        var miracleField = request.getDescriptorForType().findFieldByName("CCCIFGFHGIL");
        if (request.hasField(miracleField)) {
          var choice = (DynamicMessage) request.getField(miracleField);
          if (choice.getUnknownFields().asMap().isEmpty() && only(choice, "OJPGDDFJCHB"))
            result =
                session
                    .getPlayer()
                    .getUniverseManager()
                    .selectMiracle(batch, number(choice, "OJPGDDFJCHB"));
        }
      }
      var response = builder(session, "HandleRogueCommonPendingActionScRsp");
      set(response, "retcode", result.getVal());
      if (rolled) {
        var callback = builder(session, "FKOGDINOBDF");
        set(callback, "PCHGEGFBKLF", blessingOffer(session));
        set(response, "JFHKDEIDMOG", callback.build());
        batch = session.getPlayer().getGameplay().getRogue().getSelectionId();
      }
      // Local receiver dee05d0: field 10 acknowledges a removed action; field 9 reports failure.
      set(response, result == Retcode.SUCC ? "queue_position" : "BFNEPPCNOOI", batch);
      session.sendClientPacket(
          "HandleRogueCommonPendingActionScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static void lifecycle(GameSession session, String name, byte[] bytes) throws IOException {
    if (name.equals("StartRogueCsReq")
        && !NativeUnsignedActionWire.valid(session.getProtocol().clientSchema().message(name), bytes, 0)) {
      var rejected = builder(session, "StartRogueScRsp");
      set(rejected, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      session.sendClientPacket("StartRogueScRsp", rejected.build().toByteArray());
      return;
    }
    var request = session.getProtocol().clientSchema().parse(name, bytes);
    synchronized (session.getPlayer()) {
      var player = session.getPlayer();
      boolean start = name.equals("StartRogueCsReq");
      boolean leave = name.equals("LeaveRogueCsReq");
      boolean quit = name.equals("QuitRogueCsReq");
      Retcode result;
      if (start) {
        var season = activeSeason(Instant.now());
        int area = number(request, "area_id");
        boolean secondEntry = area == 100
            && org.hkrpg.server.gameplay.RogueSecondStoryEntry.inProgress(player);
        @SuppressWarnings("unchecked")
        var ids = (List<Integer>) value(request, "NOEPBNMJFGL");
        // Verified from command 1805 at the authored office entrance (2026-10-30):
        // packed field 4 = [4000004,4000001,4000002], not unfinished story IDs.
        @SuppressWarnings("unchecked")
        var trials = (List<Integer>) request.getField(request.getDescriptorForType().findFieldByNumber(4));
        int aeon = number(request, "AFEMBLFCCCH");
        boolean valid =
            request.getUnknownFields().asMap().isEmpty()
                && only(
                    request,
                    "area_id",
                    "NOEPBNMJFGL",
                    "AFEMBLFCCCH",
                    "JKPIPNMGGOA",
                    "unfinished_story_line_id_list")
                && ((List<?>) value(request, "JKPIPNMGGOA")).isEmpty()
                && (trials.isEmpty() || secondEntry && ids.isEmpty() && aeon == 0
                    && org.hkrpg.server.gameplay.RogueSecondStoryEntry.acceptsClientTrials(player, trials));
        result =
            !valid
                ? Retcode.REQ_PARA_INVALID
                : season == null
                        || RogueEconomy.initialCoins().isEmpty()
                        || season.RogueAreaIDList() == null
                        || !season.RogueAreaIDList().contains(area)
                    ? Retcode.ROGUE_AREA_INVALID
                    : player.getUniverseManager().start(area, ids, aeon, false);
        if (secondEntry) SceneActionDiagnostics46.rogueEntry(session, area, ids, trials, aeon,
            ((List<?>) value(request, "JKPIPNMGGOA")).size(), request.getUnknownFields().asMap().keySet(), valid, result);
      } else {
        boolean valid =
            request.getUnknownFields().asMap().isEmpty()
                && (leave ? request.getAllFields().isEmpty() : only(request, "area_id"));
        result =
            !valid
                ? Retcode.REQ_PARA_INVALID
                : leave
                    ? player.getUniverseManager().leave(false)
                    : quit
                        ? player.getUniverseManager().quit(number(request, "area_id"))
                        : player.getUniverseManager().resume(number(request, "area_id"), false);
      }
      String responseName = name.replace("CsReq", "ScRsp");
      var response = builder(session, responseName);
      set(response, "retcode", result.getVal());
      if (result == Retcode.SUCC) {
        if (!quit) sceneAndLineup(session, response);
        if (!leave)
          set(response, "rogue_game_info", snapshot(session, activeSeason(Instant.now())));
      }
      session.sendClientPacket(responseName, response.build().toByteArray());
      if (result == Retcode.SUCC && !leave && !quit) RogueRevival46.sync(session);
    }
  }

  private static void sceneAndLineup(GameSession session, DynamicMessage.Builder response)
      throws IOException {
    var player = session.getPlayer();
    RogueDialogue46.sync(session);
    for (String type : List.of("SceneInfo", "LineupInfo")) {
      var bytes =
          type.equals("SceneInfo")
              ? player.getScene().toProto().toByteArray()
              : player.getCurrentLineup().toProto().toByteArray();
      set(
          response,
          type.equals("SceneInfo") ? "scene" : "lineup",
          session
              .getProtocol()
              .clientSchema()
              .parse(type, session.getProtocol().translate(type, bytes, true)));
    }
  }

  private static void enterRoom(GameSession session, byte[] bytes) throws IOException {
    var request = session.getProtocol().clientSchema().parse("EnterRogueMapRoomCsReq", bytes);
    synchronized (session.getPlayer()) {
      var player = session.getPlayer();
      var run = player.getGameplay().getRogue();
      int site = number(request, "MAJKFIMPOOF");
      int room = number(request, "room_id");
      Retcode result;
      if (!request.getUnknownFields().asMap().isEmpty() || !only(request, "MAJKFIMPOOF", "room_id"))
        result = Retcode.REQ_PARA_INVALID;
      else if (!Objects.equals(run.getRooms().get(site), room)) result = Retcode.ROGUE_AREA_INVALID;
      else if (player.getScene() == null || player.getCurrentLineup() == null)
        result = Retcode.ROGUE_STATUS_NOT_MATCH;
      else result = player.getUniverseManager().enter(site);
      var response = builder(session, "EnterRogueMapRoomScRsp");
      set(response, "retcode", result.getVal());
      if (result == Retcode.SUCC) {
        set(response, "ANHDBMOPGDC", run.getSiteId());
        sceneAndLineup(session, response);
      }
      session.sendClientPacket("EnterRogueMapRoomScRsp", response.build().toByteArray());
    }
  }

  static GameplayDefinitions.RogueSeason activeSeason(Instant now) {
    var matches =
        GameplayDefinitions.rogueSeasons.stream()
            .filter(
                season -> {
                  if (season.RogueVersion() != 1) return false;
                  try {
                    return !now.isBefore(seasonTime(season.BeginTime()))
                        && now.isBefore(seasonTime(season.EndTime()).plusSeconds(1));
                  } catch (DateTimeException | NullPointerException e) {
                    return false;
                  }
                })
            .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private static Instant seasonTime(String text) {
    return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .toInstant(ZoneOffset.ofHours(8));
  }

  static DynamicMessage snapshot(GameSession session, GameplayDefinitions.RogueSeason season) {
    var player = session.getPlayer();
    var run = player.getGameplay().getRogue();
    var currentArea = GameplayDefinitions.areas.get(run.getAreaId());
    // 4.6 indexes the world picker from progress 1. A returned terminal tutorial has
    // progress 0, so advertising it as a selectable/default area gives Lua a nil index.
    // Keep the saved run and settlement history; active/paused tutorials still resume.
    boolean outsideTerminalTutorial = currentArea != null && currentArea.AreaProgress() == 0
        && !run.isInside() && switch (run.getPhase()) {
          case FINISHED, FAILED, ABANDONED -> true;
          default -> false;
        };
    var game = builder(session, "AGEHGBKOBEF");
    var basic = builder(session, "EDOIEMCFFCA");
    var seasonInfo = builder(session, "CGNNMPIKBBB");
    if (season != null) {
      set(seasonInfo, "LJNGPCKPJLF", season.RogueSeason());
      set(seasonInfo, "begin_time", seasonTime(season.BeginTime()).getEpochSecond());
      set(seasonInfo, "end_time", seasonTime(season.EndTime()).getEpochSecond());
    }
    set(basic, "HHFAAFOBKME", seasonInfo.build());
    var areas = builder(session, "GOIEJOCFKEE");
    if (season != null && season.RogueAreaIDList() != null)
      for (int id : new TreeSet<>(season.RogueAreaIDList())) {
        var definition = GameplayDefinitions.areas.get(id);
        if (definition == null) continue;
        if (outsideTerminalTutorial && id == run.getAreaId()) continue;
        var area = builder(session, "CAJEBOPPMCJ");
        set(area, "area_id", id);
        int map = definition.mapId();
        if (id == 100 && run.getAreaId() == 100 && run.getMapId() == 2
            && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING) map = 2;
        else if (id == 100 && org.hkrpg.server.gameplay.RogueSecondStoryEntry.awaiting(player)) map = 2;
        if (id == 110 && run.getAreaId() == 110 && run.getMapId() == 3
            && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING) map = 3;
        else if (id == 110 && org.hkrpg.server.gameplay.RogueTeachingFlow.introCompleted(player)
            && run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING) map = 3;
        set(area, "map_id", map);
        boolean claimed = player.getRewardLedger().granted("rogue-first:" + id);
        enumValue(
            area,
            "area_status",
            claimed
                ? "RogueAreaStatus_FirstPass"
                : org.hkrpg.server.gameplay.RogueUnlocks.open(player, definition.UnlockID())
                    ? "RogueAreaStatus_Unlock"
                    : "RogueAreaStatus_Lock");
        set(area, "ALAJJMKOMML", claimed);
        if (run.getAreaId() == id) {
          enumValue(area, "NHDLEFAAPOB", status(run));
          // Local get_CurReachRoomNum counts arrived rooms, including the current uncleared room.
          int reached = run.getCleared().size();
          if (run.getRooms().containsKey(run.getSiteId())
              && !run.getCleared().contains(run.getSiteId())) reached++;
          set(area, "KNDHMNBIIDF", reached);
        }
        add(areas, "OJGOAOPLMLB", area.build());
      }
    set(basic, "ACLKMFCDMED", areas.build());
    var availableAeons = builder(session, "GFGHGCIMKGA");
    for (int id : org.hkrpg.server.gameplay.RogueAeonCatalog.available(player))
      add(availableAeons, "OPAEGCBNACJ", id);
    set(basic, "AEFMMOHKCHJ", availableAeons.build());
    var currencies = builder(session, "MBCBDJNKFEB");
    set(currencies, "GEBGGMAGDJB", player.getTalentPoints());
    set(currencies, "CICAENDEDFE", RogueCurrencies.balance(player, 33));
    set(currencies, "PDGBEJHNHHE", RogueCurrencies.balance(player, 34));
    set(basic, "NIKDPODBIEF", currencies.build());
    set(game, "LFLCDNGALME", basic.build());
    if (outsideTerminalTutorial || run.isDismissed()
        || run.getPhase() == GameplayState.RogueRun.Phase.IDLE
        || run.getPhase() == GameplayState.RogueRun.Phase.ABANDONED) return game.build();
    var current = builder(session, "HNKAOFDGLIE");
    enumValue(current, "status", status(run));
    set(current, "ILOFGCAFHAH", run.getPhase() == GameplayState.RogueRun.Phase.FINISHED);
    set(current, "COOOMDKGODF", mapSnapshot(session));
    var buffs = builder(session, "CFMHEMGEANC");
    new TreeMap<>(run.getBlessings())
        .forEach(
            (id, level) -> {
              var buff = builder(session, "ABOFJEIMLLJ");
              set(buff, "buff_id", id);
              set(buff, "level", level);
              add(buffs, "CHCMEKAFKAF", buff.build());
            });
    set(current, "HABJHGPFGEB", buffs.build());
    var money = builder(session, "AGEOGDOGDMM");
    set(money, "rogue_money", run.getCoins());
    set(current, "DHEKLJDBNNP", money.build());
    var miracles = builder(session, "HIKOBOMBMPB");
    var owned = builder(session, "OEDNNHAOHAB");
    new TreeMap<>(run.getMiracles())
        .forEach(
            (id, item) -> {
              var row = builder(session, "EFDAIBDBFEA");
              set(row, "LJCEOGPOGBE", id);
              set(row, "DGGCFDKJDPO", item.getUseCount());
              set(row, "total_count", item.getTotalCount());
              add(owned, "MAJGMMCOCIG", row.build());
            });
    set(miracles, "DNPCCFFFBHN", owned.build());
    set(current, "DNPCCFFFBHN", miracles.build());
    var avatars = builder(session, "ENOBKLGCHOD");
    // Downloaded base-avatar IDs: LunarCore development toAvatarInfoProto / hkrpg-go
    // GetRogueLineupInfo, matched to local ENOBKLGCHOD codec tag 0x0a at 1e2c6931.
    for (int id : new TreeSet<>(run.getVitals().keySet()))
      add(avatars, org.hkrpg.server.gameplay.RogueTutorialDownload.downloaded(run, id)
          ? "unfinished_story_line_id_list" : "NOEPBNMJFGL", id);
    var revive = RogueRevival46.info(session);
    if (revive != null) set(avatars, "MFEODNJNGDP", revive);
    set(current, "EIOHFJMFOKF", avatars.build());
    var selectedAeon = builder(session, "CIFCAIEDOLK");
    // Local CIFCAIEDOLK WriteTo / RogueData.SyncRogueData and selected-ID getter match field 8.
    if (run.getSelectedAeon() >= 1 && run.getSelectedAeon() <= 9)
      set(selectedAeon, "JPPAGGPJOMO", run.getSelectedAeon());
    set(current, "AEFMMOHKCHJ", selectedAeon.build());
    set(current, "IELPCHGLFCA", builder(session, "CDDIJCBEAHJ").build());
    if (run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING && run.pending())
      set(current, "FMIGOKIDKJL", value(pending(session), "action"));
    set(game, "CFDIKLBINHK", current.build());
    return game.build();
  }

  static DynamicMessage mapSnapshot(GameSession session) {
    var run = session.getPlayer().getGameplay().getRogue();
    var map = builder(session, "DNDMLICBKEF");
    set(map, "area_id", run.getAreaId());
    set(map, "map_id", run.getMapId());
    set(map, "ANHDBMOPGDC", run.getSiteId());
    set(map, "HPKMGKPPELC", run.getRoomId());
    var sites = GameplayDefinitions.maps.getOrDefault(run.getMapId(), List.of());
    var current = sites.stream().filter(site -> site.SiteID() == run.getSiteId()).findFirst();
    var next = current.map(GameplayDefinitions.Site::NextSiteIDList).orElse(List.of());
    new TreeMap<>(run.getRooms())
        .forEach(
            (id, roomId) -> {
              var room = builder(session, "PAIJCBOKIFO");
              set(room, "room_id", roomId);
              set(room, "MAJKFIMPOOF", id);
              String status =
                  run.getCleared().contains(id)
                      ? "RogueRoomStatus_Finish"
                      : id == run.getSiteId()
                          ? "RogueRoomStatus_Play"
                          : run.getCleared().contains(run.getSiteId())
                                  && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
                                  && !run.pending()
                                  && next != null
                                  && next.contains(id)
                              ? "RogueRoomStatus_Unlock"
                              : "RogueRoomStatus_Lock";
              enumValue(room, "CAGDNHCOCKM", status);
              add(map, "LLNICHDDIAN", room.build());
            });
    return map.build();
  }

  private static String status(GameplayState.RogueRun run) {
    if (run.isDismissed()) return "RogueStatus_None";
    return switch (run.getPhase()) {
      case IDLE, ABANDONED -> "RogueStatus_None";
      case EXPLORING -> "RogueStatus_Doing";
      case FINISHED, FAILED -> "RogueStatus_Finish";
    };
  }

  private static void enumValue(DynamicMessage.Builder message, String field, String name) {
    var descriptor = message.getDescriptorForType().findFieldByName(field);
    var value = descriptor.getEnumType().findValueByName(name);
    if (value == null) throw new ProtocolException("Unmapped universe enum " + name);
    message.setField(descriptor, value);
  }

  public static DynamicMessage pending(GameSession session) {
    var run = session.getPlayer().getGameplay().getRogue();
    var notify = builder(session, "SyncRogueCommonPendingActionScNotify");
    set(notify, "DLOJFEEHLIJ", 101);
    if (!run.pending()) return notify.build();
    if (!run.getMiracleOffers().isEmpty()) {
      var info = builder(session, "AMKALFJHFJP");
      for (int id : run.getMiracleOffers()) add(info, "BPDDBKGDMDD", id);
      var detail = builder(session, "DCFPEIMAMCN");
      set(detail, "AMIBPAGBMFP", info.build());
      var action = builder(session, "INHBMHCJJCN");
      set(action, "queue_position", run.getSelectionId());
      set(action, "detail", detail.build());
      set(notify, "action", action.build());
      return notify.build();
    }
    var info = blessingOffer(session);
    var detail = builder(session, "DCFPEIMAMCN");
    set(detail, "PCHGEGFBKLF", info);
    var action = builder(session, "INHBMHCJJCN");
    set(action, "queue_position", run.getSelectionId());
    set(action, "detail", detail.build());
    set(notify, "action", action.build());
    return notify.build();
  }

  private static DynamicMessage blessingOffer(GameSession session) {
    var run = session.getPlayer().getGameplay().getRogue();
    var info = builder(session, "GGGBAIAPDLO");
    if (run.offerChoiceTotal() > 1 && run.validOfferLevels()) {
      // Native DEE93F0/DEE9440 via 15EFF190 and GGGBAIAPDLO WriteTo 1E44C5F0.
      set(info, "NKGGAOJLCMD", run.offerChoiceTotal());
      set(info, "EIALMEADLGF", run.offerChoiceIndex());
    }
    for (int buff : run.validOfferLevels() ? run.getOffers() : List.<Integer>of()) {
      var row = builder(session, "KJAMNOGFAMJ");
      set(row, "buff_id", buff);
      set(row, "HAGDKKBAAKK", run.offerLevel(buff));
      add(info, "LEOFNINDJDB", row.build());
    }
    var cost = session.getPlayer().getUniverseManager().blessingRerollCost();
    set(info, "can_roll", cost.isPresent());
    set(info, "PCGFIOKLIBE", run.getOfferRerolls());
    set(info, "EGFOLKEPNCI", run.getOfferRerollCosts().size());
    if (cost.isPresent()) {
      var pile = builder(session, "PileItem");
      set(pile, "item_id", 31);
      set(pile, "item_num", cost.getAsInt());
      var item = builder(session, "ItemCost");
      set(item, "pile_item", pile.build());
      var costs = builder(session, "ItemCostData");
      add(costs, "item_list", item.build());
      set(info, "DEHJDNIEKHB", costs.build());
    }
    return info.build();
  }

  public static void syncPending(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var session = player.getSession();
    Scene46.syncPortals(player);
    if (player.getGameplay().getRogue().pending())
      session.sendClientPacket(
          "SyncRogueCommonPendingActionScNotify", pending(session).toByteArray());
  }

  public static void syncMap(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var session = player.getSession();
    Scene46.syncPortals(player);
    var map = mapSnapshot(session);
    for (Object row : (List<?>) value(map, "LLNICHDDIAN")) {
      var notify = builder(session, "SyncRogueMapRoomScNotify");
      set(notify, "map_id", player.getGameplay().getRogue().getMapId());
      set(notify, "HNEANMNJHDB", row);
      session.sendClientPacket("SyncRogueMapRoomScNotify", notify.build().toByteArray());
    }
  }

  public static void syncFinish(Player player) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    syncFinish(player.getSession());
  }

  private static void syncFinish(GameSession session) {
    var state = session.getPlayer().getGameplay();
    var run = state.getRogue();
    var result = state.getRogueResults().get(run.getRunId());
    if (result == null || run.isDismissed() || session.hasSentRogueFinish(run.getRunId())) return;
    var data = builder(session, "GKHGFCBMLBL");
    set(data, "area_id", result.getAreaId());
    set(data, "is_win", result.getOutcome() == GameplayState.RogueRun.Phase.FINISHED);
    // Verified local _InitBaseInfo and WriteTo: pass=12, reach=98. Site IDs are not counts.
    set(data, "GMIIMAHLIJF", result.getPassedRooms());
    set(data, "HEOMALBNJLL", result.getReachedRooms());
    set(data, "GHCBFCIGIBF", result.getRoomScore());
    set(data, "score_id", result.getRoomScore());
    set(data, "GEBGGMAGDJB", result.isEarningsSettled() ? result.getTalentCoin() : 0);
    var record = builder(session, "KCOEJJKGNIG");
    new TreeMap<>(result.getBlessings())
        .forEach(
            (id, level) -> {
              var buff = builder(session, "ABOFJEIMLLJ");
              set(buff, "buff_id", id);
              set(buff, "level", level);
              add(record, "buff_list", buff.build());
            });
    for (int id : result.getMiracles()) add(record, "MAJGMMCOCIG", id);
    for (int slot = 0; slot < result.getAvatars().size(); slot++) {
      int id = result.getAvatars().get(slot);
      var avatar = builder(session, "ALBLEFHHJIC");
      set(avatar, "id", id);
      set(avatar, "slot", slot);
      set(avatar, "level", result.getAvatarLevels().getOrDefault(id, 0));
      enumValue(avatar, "avatar_type", result.getTrialAvatars().contains(id)
          ? "AvatarType_AvatarTrialType" : "AvatarType_AvatarFormalType");
      add(record, "avatar_list", avatar.build());
    }
    set(data, "record_info", record.build());
    try {
      set(
          data,
          "LHPNJLBPACA",
          session
              .getProtocol()
              .clientSchema()
              .parse(
                  "ItemList",
                  session.getProtocol().translate("ItemList", result.getFirstRewardItems(), true)));
    } catch (IOException e) {
      throw new ProtocolException("Malformed frozen rogue reward");
    }
    var finish = builder(session, "SyncRogueFinishScNotify");
    set(finish, "NLEOMLNOKCC", data.build());
    session.sendClientPacket("SyncRogueFinishScNotify", finish.build().toByteArray());
    if (result.getOutcome() == GameplayState.RogueRun.Phase.FINISHED) {
      var win = builder(session, "SyncRogueExploreWinScNotify");
      set(win, "ILOFGCAFHAH", true);
      session.sendClientPacket("SyncRogueExploreWinScNotify", win.build().toByteArray());
    }
    var status = builder(session, "SyncRogueStatusScNotify");
    enumValue(status, "status", status(run));
    session.sendClientPacket("SyncRogueStatusScNotify", status.build().toByteArray());
    session.markRogueFinishSent(run.getRunId());
  }

  public static void syncBlessing(Player player, int buffId, int batch) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var level = player.getGameplay().getRogue().getBlessings().get(buffId);
    if (level == null) return;
    var session = player.getSession();
    var buff = builder(session, "KJAMNOGFAMJ");
    set(buff, "buff_id", buffId);
    set(buff, "HAGDKKBAAKK", level);
    var detail = builder(session, "PKHKEEKIODC");
    set(detail, "IFOOEPKGGAG", buff.build());
    var action = builder(session, "MAOKMPOELCO");
    set(action, "detail", detail.build());
    set(action, "queue_position", batch);
    var notify = builder(session, "SyncRogueCommonActionResultScNotify");
    set(notify, "DLOJFEEHLIJ", 101);
    add(notify, "CLGGKLNPLCK", action.build());
    // display_type=0 skips presentation at 15ebcc50 but still applies state at 15d779d0.
    // Source enum meanings are not verified; do not invent a Select/Enhance enum mapping.
    session.sendClientPacket("SyncRogueCommonActionResultScNotify", notify.build().toByteArray());
    RogueHandbook46.sync(player);
  }

  public static void syncMiracle(Player player, int id, boolean acquired, int batch) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var item = player.getGameplay().getRogue().getMiracles().get(id);
    if (item == null) return;
    var session = player.getSession();
    var row = builder(session, "KOFMGCNIMIN");
    set(row, "LJCEOGPOGBE", id);
    set(row, "DGGCFDKJDPO", item.getUseCount());
    set(row, "total_count", item.getTotalCount());
    var update = builder(session, acquired ? "IOMEMIEAPJO" : "LHKNCMFEPFK");
    set(update, "miracle", row.build());
    var detail = builder(session, "PKHKEEKIODC");
    set(detail, acquired ? "GPJJMPCMAAA" : "KDJBDMLDMHG", update.build());
    var action = builder(session, "MAOKMPOELCO");
    set(action, "detail", detail.build());
    set(action, "queue_position", batch);
    var notify = builder(session, "SyncRogueCommonActionResultScNotify");
    set(notify, "DLOJFEEHLIJ", 101);
    add(notify, "CLGGKLNPLCK", action.build());
    session.sendClientPacket("SyncRogueCommonActionResultScNotify", notify.build().toByteArray());
    if (acquired) RogueHandbook46.sync(player);
  }

  /** Verified current-client case 1957 invokes IRogueMiracleInfo.ReplaceMiracleItem. */
  public static void syncMiracleReplacement(Player player, int oldId, int newId) {
    if (!player.isOnline()
        || player.getSession() == null
        || !player.getSession().getProtocol().experimental()
        || !(Set.of(45, 47, 49, 51, 53, 55).contains(oldId) && newId == oldId + 1
            || oldId == 70 && newId == 9)
        || player.getGameplay().getRogue().getMiracles().containsKey(oldId)) return;
    var item = player.getGameplay().getRogue().getMiracles().get(newId);
    if (item == null || item.getTotalCount() != 0 || item.getUseCount() != 0) return;
    var session = player.getSession();
    var row = builder(session, "KOFMGCNIMIN");
    set(row, "LJCEOGPOGBE", newId);
    set(row, "DGGCFDKJDPO", item.getUseCount());
    set(row, "total_count", item.getTotalCount());
    var replacement = builder(session, "JLHJICBCHMO");
    set(replacement, "BJIBCOHEGIJ", oldId);
    set(replacement, "HLCPFLOPGBM", row.build());
    var detail = builder(session, "PKHKEEKIODC");
    set(detail, "OMFLCEBLLMI", replacement.build());
    var action = builder(session, "MAOKMPOELCO");
    set(action, "detail", detail.build());
    var notify = builder(session, "SyncRogueCommonActionResultScNotify");
    set(notify, "DLOJFEEHLIJ", 101);
    add(notify, "CLGGKLNPLCK", action.build());
    session.sendClientPacket("SyncRogueCommonActionResultScNotify", notify.build().toByteArray());
  }

  /** Same native Add/Replace oneofs as existing acquisition and code-repair paths. */
  public static void syncMiracleDice(Player player, org.hkrpg.server.gameplay.RogueMiracleDice.Change change) {
    if (!change.valid(player) || !player.isOnline() || player.getSession() == null
        || !player.getSession().getProtocol().experimental()) return;
    var session = player.getSession();
    var notify = builder(session, "SyncRogueCommonActionResultScNotify");
    set(notify, "DLOJFEEHLIJ", 101);
    var dice = builder(session, "KOFMGCNIMIN");
    set(dice, "LJCEOGPOGBE", 21);
    var acquired = builder(session, "IOMEMIEAPJO");
    set(acquired, "miracle", dice.build());
    var detail = builder(session, "PKHKEEKIODC");
    set(detail, "GPJJMPCMAAA", acquired.build());
    var action = builder(session, "MAOKMPOELCO");
    set(action, "detail", detail.build());
    // When a frozen clock payment broke 70, its replacement must precede the dice roll.
    for (var operation : change.replacements()) {
      if (operation.oldId() == 70 && operation.newId() == 9) {
        addDiceReplacement(session, notify, operation);
      }
    }
    add(notify, "CLGGKLNPLCK", action.build());
    for (var operation : change.replacements()) {
      if (!(operation.oldId() == 70 && operation.newId() == 9))
        addDiceReplacement(session, notify, operation);
    }
    session.sendClientPacket("SyncRogueCommonActionResultScNotify", notify.build().toByteArray());
    RogueHandbook46.sync(player);
  }

  private static void addDiceReplacement(GameSession session, DynamicMessage.Builder notify,
      org.hkrpg.server.gameplay.RogueMiracleDice.Replacement operation) {
    var row = builder(session, "KOFMGCNIMIN");
    set(row, "LJCEOGPOGBE", operation.newId());
    set(row, "DGGCFDKJDPO", operation.item().getUseCount());
    set(row, "total_count", operation.item().getTotalCount());
    var replacement = builder(session, "JLHJICBCHMO");
    set(replacement, "BJIBCOHEGIJ", operation.oldId());
    set(replacement, "HLCPFLOPGBM", row.build());
    var detail = builder(session, "PKHKEEKIODC");
    set(detail, "OMFLCEBLLMI", replacement.build());
    var action = builder(session, "MAOKMPOELCO");
    set(action, "detail", detail.build());
    add(notify, "CLGGKLNPLCK", action.build());
  }

  private Universe46() {}
}
