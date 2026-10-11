package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.nio.file.Path;
import org.hkrpg.server.gameplay.*;

/** Native catalog, saved openings and validated normal dice placement. */
public final class ClockPark46 {
  private static final java.util.Set<String> REQUESTS =
      java.util.Set.of(
          "ClockParkGetInfoCsReq",
          "ClockParkStartScriptCsReq",
          "ClockParkGetOngoingScriptInfoCsReq",
          "ClockParkQuitScriptCsReq",
          "ClockParkHandleWaitOperationCsReq");

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    var name =
        REQUESTS.stream()
            .filter(n -> command == session.getProtocol().clientCommand(n))
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      if (!name.equals("ClockParkGetInfoCsReq")) {
        operation(session, name, bytes);
        return true;
      }
      var response = builder(session, "ClockParkGetInfoScRsp");
      // This native query has no fields. Any payload is unsupported, including unknown wire fields.
      var catalog =
          bytes.length == 0
              ? ClockParkCatalog.load(Path.of(LunarCore.getConfig().getResourceDir()))
              : null;
      var state = session.getPlayer().getGameplay().getClockPark();
      boolean valid = catalog != null
          && state != null
          && state.valid(catalog)
          && ClockParkEndingRewards.valid(session.getPlayer(), state);
      if (catalog == null) set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      else if (!valid)
        set(response, "retcode", Retcode.PLAYER_DATA_ERROR.getVal());
      else {
        for (var script : catalog.scripts().values()) {
          var row = builder(session, "DEGFFGKMFJJ");
          set(row, "script_id", script.id());
          for (int chapter : state.reached(script.id())) add(row, "CFJOGMJIHOE", chapter);
          add(response, "DCGDHFJLHCN", row.build());
        }
        set(response, "progress", state.progress(catalog));
      }
      if (valid && state.getActive() != null)
        set(response, "LNKAKGOCAOF", state.getActive().getScript());
      session.sendClientPacket("ClockParkGetInfoScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static void operation(GameSession session, String name, byte[] bytes) throws IOException {
    var response = builder(session, name.replace("CsReq", "ScRsp"));
    var type = session.getProtocol().clientSchema().message(name);
    var manager = new ClockParkManager(session.getPlayer());
    ClockParkManager.Result result = new ClockParkManager.Result(Retcode.REQ_PARA_INVALID, null);
    if (NativeUnsignedActionWire.valid(type, bytes, 0)) {
      var req = session.getProtocol().clientSchema().parse(name, bytes);
      result =
          switch (name) {
            case "ClockParkStartScriptCsReq" ->
                only(req, "script_id") ? manager.start(number(req, "script_id")) : result;
            case "ClockParkGetOngoingScriptInfoCsReq" ->
                bytes.length == 0 ? manager.ongoing() : result;
            case "ClockParkQuitScriptCsReq" -> manager.quit(number(req, "CNHLAAHDPGA"));
            case "ClockParkHandleWaitOperationCsReq" -> {
              for (String field : java.util.List.of("DIKEDLHGDJJ", "ACIOPOIJNEF"))
                if (only(req, "script_id", "JGPHDBDHKKO", field)
                    && req.hasField(type.findFieldByName(field))) {
                  var report =
                      (com.google.protobuf.DynamicMessage)
                          req.getField(type.findFieldByName(field));
                  if (only(report, "is_win", "IMGLLIBJAPC"))
                    yield manager.finishMiniGame(
                        number(req, "script_id"),
                        number(req, "JGPHDBDHKKO"),
                        field.equals("DIKEDLHGDJJ") ? "MOVIE" : "HANU",
                        (Boolean) value(report, "is_win"),
                        number(report, "IMGLLIBJAPC"));
                  yield result;
                }
              if (only(req, "script_id", "JGPHDBDHKKO", "EJLKEGLKGDN")
                  && req.hasField(type.findFieldByName("EJLKEGLKGDN"))) {
                var gacha =
                    (com.google.protobuf.DynamicMessage)
                        req.getField(type.findFieldByName("EJLKEGLKGDN"));
                if (only(gacha, "IMKGOKGKDPH", "gacha_random"))
                  yield manager.slotOperation(
                      number(req, "script_id"),
                      number(req, "JGPHDBDHKKO"),
                      (Boolean) value(gacha, "IMKGOKGKDPH"),
                      number(gacha, "gacha_random"));
                yield result;
              }
              if (only(req, "script_id", "JGPHDBDHKKO", "MLHNHAFPLKD")
                  && req.hasField(type.findFieldByName("MLHNHAFPLKD")))
                yield manager.finishCheckpoint(
                    number(req, "script_id"), number(req, "JGPHDBDHKKO"));
              if (only(req, "script_id", "JGPHDBDHKKO", "IKNEKEJEGLM")
                  && req.hasField(type.findFieldByName("IKNEKEJEGLM")))
                yield manager.finishStory(number(req, "script_id"), number(req, "JGPHDBDHKKO"));
              if (only(req, "script_id", "JGPHDBDHKKO", "JFAJEBGKPEH")
                  && req.hasField(type.findFieldByName("JFAJEBGKPEH"))) {
                var operation =
                    (com.google.protobuf.DynamicMessage)
                        req.getField(type.findFieldByName("JFAJEBGKPEH"));
                var state = session.getPlayer().getGameplay().getClockPark();
                var run = state == null ? null : state.getActive();
                if (run != null && "BRANCH".equals(run.getPhase())) {
                  var branch = branchPlacement(operation);
                  if (branch != null)
                    yield manager.selectBranch(
                        number(req, "script_id"), number(req, "JGPHDBDHKKO"), branch[0], branch[1]);
                  yield result;
                }
                var faces =
                    run == null
                        ? null
                        : placement(operation, ClockParkCards.pool(run.getPoolRule()));
                if (faces != null)
                  yield manager.placeDice(
                      number(req, "script_id"), number(req, "JGPHDBDHKKO"), faces);
              }
              yield result;
            }
            default -> result;
          };
    }
    set(response, "retcode", result.code().getVal());
    var run = result.run();
    if (result.code() == Retcode.SUCC && run != null) {
      if (name.equals("ClockParkStartScriptCsReq")) set(response, "script_id", run.getScript());
      else if (name.equals("ClockParkHandleWaitOperationCsReq")) {
        set(response, "JGPHDBDHKKO", run.getRound());
        if (result.lotteryId() > 0) set(response, "IGBHCBNKEBO", result.lotteryId());
        if ("FINISHED".equals(run.getPhase()) || result.failed() || "BRANCH".equals(run.getPhase()))
          set(
              response,
              "LNPGHCLDOKG",
              response
                  .getDescriptorForType()
                  .findFieldByName("LNPGHCLDOKG")
                  .getEnumType()
                  .findValueByNumber(
                      result.failed() ? 1 : "FINISHED".equals(run.getPhase()) ? 5 : 2));
      } else if (name.equals("ClockParkGetOngoingScriptInfoCsReq")) {
        set(response, "script_id", run.getScript());
        set(response, "DCAHJELNJNP", run.getChapter());
        set(response, "JIPPMKBDJJC", run.getRoundIndex());
        set(response, "INDJEOAJJEN", run.getCheckpoint());
        for (int chapter : run.chapterPath()) add(response, "FKNBOIIMFLD", chapter);
        set(response, "IMJFLCKFJGF", run.getTransaction());
        var wait = builder(session, "MDDIKNGBPPL");
        set(wait, "JGPHDBDHKKO", run.getRound());
        if (java.util.Set.of("STORY", "FINISHING").contains(run.getPhase()))
          set(wait, "ICPMABKBLCD", builder(session, "CNOAGGFCDAL").build());
        else if ("CHECKPOINT".equals(run.getPhase())) {
          var check = builder(session, "FOFGKMONJHJ");
          set(check, "OEKKAJJEGAK", run.getCheckpointPassed());
          set(wait, "BELJJJLLCBK", check.build());
        } else if (java.util.Set.of("MOVIE", "HANU").contains(run.getPhase())) {
          var proof = run.getMini();
          var card = builder(session, "NIJHCCNMEGA");
          set(card, "card_id", proof.getCard());
          add(card, "KCNEKEGGAGG", proof.getFace());
          set(card, "NPCBECNBIJK", proof.getFace());
          var dice = builder(session, "IIMHLPBEDDH");
          add(dice, "LONFKFCFDND", card.build());
          boolean movie = "MOVIE".equals(run.getPhase());
          var game = builder(session, movie ? "KOCBHDOKFNF" : "FFDLJDAGEML");
          set(game, "CENIOBBIJMF", dice.build());
          set(wait, movie ? "BPMEBJHDOFC" : "DLNGPBMGMKM", game.build());
        } else if ("SLOT".equals(run.getPhase())) {
          var proof = run.getSlot();
          var card = builder(session, "NIJHCCNMEGA");
          set(card, "card_id", proof.getCard());
          add(card, "KCNEKEGGAGG", proof.getFace());
          set(card, "NPCBECNBIJK", proof.getFace());
          var dice = builder(session, "IIMHLPBEDDH");
          add(dice, "LONFKFCFDND", card.build());
          var slot = builder(session, "IEFICANECDH");
          set(slot, "NDNHCFBFPMH", proof.isPlayed() ? 0 : 1);
          set(slot, "gacha_random", proof.getTicket());
          set(slot, "CENIOBBIJMF", dice.build());
          set(wait, "HGHBDACDJKP", slot.build());
        } else {
          var dice = builder(session, "IIMHLPBEDDH");
          for (int face : run.getDice()) add(dice, "KCNEKEGGAGG", face);
          boolean branch = "BRANCH".equals(run.getPhase());
          var cards =
              branch
                  ? java.util.List.of(
                      ClockParkCards.load(Path.of(LunarCore.getConfig().getResourceDir()))
                          .branchCard(run.getChapter()))
                  : ClockParkCards.pool(run.getPoolRule());
          for (int id : cards) {
            var card = builder(session, "NIJHCCNMEGA");
            set(card, "card_id", id);
            add(dice, "LONFKFCFDND", card.build());
          }
          var phase = builder(session, branch ? "NIKCDPGKKLF" : "PEGDBKDLJDJ");
          set(phase, "CENIOBBIJMF", dice.build());
          set(wait, branch ? "ABIPNABIDPE" : "FDNLIPKGBLM", phase.build());
        }
        set(response, "HGDLJDAFHLI", wait.build());
        var attributes = builder(session, "LOBFOKCKPKB");
        set(attributes, "JEKOHIHJFMK", run.getAttributeA());
        set(attributes, "AKKLILNJCPL", run.getAttributeB());
        set(attributes, "PNOIJPDMCPG", run.getAttributeC());
        set(response, "JOFJEIFLGBP", attributes.build());
        set(response, "HABJHGPFGEB", builder(session, "ClockParkBuffList").build());
      }
    }
    session.sendClientPacket(name.replace("CsReq", "ScRsp"), response.build().toByteArray());
    if (result.code() == Retcode.SUCC
        && run != null
        && ("FINISHED".equals(run.getPhase()) || result.failed())) {
      var finished = builder(session, "GIPBHLBFDAH");
      set(finished, "script_id", run.getScript());
      set(finished, "DCAHJELNJNP", run.getChapter());
      set(
          finished,
          "progress",
          result.newEnding()
              ? ClockParkManager.catalog().chapters().get(run.getChapter()).rewardProgress()
              : 0);
      set(finished, "DMNEACGEHEC", result.newEnding());
      set(finished, "HGPKMHFPMBJ", result.failed());
      set(finished, "reward", items(session, result.rewards()));
      var notify = builder(session, "ClockParkFinishScriptScNotify");
      set(notify, "NLEOMLNOKCC", finished.build());
      session.sendClientPacket("ClockParkFinishScriptScNotify", notify.build().toByteArray());
    }
  }

  private static java.util.List<Integer> placement(
      com.google.protobuf.DynamicMessage outer, java.util.List<Integer> pool) {
    if (pool == null) return null;
    var field = outer.getDescriptorForType().findFieldByName("CENIOBBIJMF");
    if (!outer.hasField(field)) return null;
    var dice = (com.google.protobuf.DynamicMessage) outer.getField(field);
    if (!((java.util.List<?>) value(dice, "KCNEKEGGAGG")).isEmpty()) return null;
    var rows = (java.util.List<?>) value(dice, "LONFKFCFDND");
    if (rows.size() != pool.size()) return null;
    var faces = new java.util.HashMap<Integer, Integer>();
    for (var item : rows) {
      var row = (com.google.protobuf.DynamicMessage) item;
      int id = number(row, "card_id");
      var slots = (java.util.List<?>) value(row, "KCNEKEGGAGG");
      if (!pool.contains(id)
          || number(row, "NPCBECNBIJK") != 0
          || slots.size() != 1
          || faces.putIfAbsent(id, (Integer) slots.getFirst()) != null) return null;
    }
    var ordered = pool.stream().map(faces::get).toList();
    return ClockParkCards.facesValid(ordered) ? ordered : null;
  }

  private static int[] branchPlacement(com.google.protobuf.DynamicMessage outer) {
    var field = outer.getDescriptorForType().findFieldByName("CENIOBBIJMF");
    if (!outer.hasField(field)) return null;
    var dice = (com.google.protobuf.DynamicMessage) outer.getField(field);
    if (!((java.util.List<?>) value(dice, "KCNEKEGGAGG")).isEmpty()) return null;
    var rows = (java.util.List<?>) value(dice, "LONFKFCFDND");
    if (rows.size() != 1) return null;
    var row = (com.google.protobuf.DynamicMessage) rows.getFirst();
    var faces = (java.util.List<?>) value(row, "KCNEKEGGAGG");
    if (faces.size() != 1 || number(row, "NPCBECNBIJK") != 0) return null;
    int face = (Integer) faces.getFirst();
    return face >= 1 && face <= 6 ? new int[] {number(row, "card_id"), face} : null;
  }

  private ClockPark46() {}
}
