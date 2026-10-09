package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.challenge.ChallengeType;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketStartChallengeScRsp;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.ChallengeLeave;
import org.hkrpg.server.gameplay.StoryChallengeCatalog;
import us.hebi.quickbuf.RepeatedInt;

/** Formal-avatar memory and original Normal story subset with strict 4.6 stage-info parsing. */
public final class MemoryChallenge46 {
  record Request(
      int challenge,
      List<Integer> first,
      List<Integer> second,
      int firstBuff,
      int secondBuff,
      boolean story) {
    Request {
      first = List.copyOf(first);
      second = List.copyOf(second);
    }
  }

  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()) return false;
    if (command == session.getProtocol().clientCommand("GetChallengeCsReq")) {
      if (session.getState() == SessionState.ACTIVE) {
        if (bytes.length == 0) query(session);
        else
          Gameplay46.send(
              session, "GetChallengeScRsp", Map.of("retcode", Retcode.REQ_PARA_INVALID.getVal()));
      }
      return true;
    }
    if (command == session.getProtocol().clientCommand("LeaveChallengeCsReq")) {
      if (session.getState() == SessionState.ACTIVE) {
        var code =
            bytes.length == 0
                ? ChallengeLeave.leave(session.getPlayer())
                : Retcode.REQ_PARA_INVALID;
        Gameplay46.send(session, "LeaveChallengeScRsp", Map.of("retcode", code.getVal()));
      }
      return true;
    }
    if (command != session.getProtocol().clientCommand("StartChallengeCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var player = session.getPlayer();
    synchronized (player) {
      var request = read(bytes);
      Retcode code = Retcode.REQ_PARA_INVALID;
      if (request != null) {
        var excel = GameData.getChallengeExcelMap().get(request.challenge());
        if (excel == null
            || excel.getType() != ChallengeType.MEMORY && !StoryChallengeCatalog.supports(excel)
            || excel.getStageNum() < 1
            || excel.getStageNum() > 2) code = Retcode.CHALLENGE_NOT_EXIST;
        else if (request.story() != (excel.getType() == ChallengeType.STORY)
            || request.story()
                && (!StoryChallengeCatalog.accepts(excel, request.firstBuff(), request.secondBuff())
                    || request.first().stream().anyMatch(request.second()::contains)))
          code = Retcode.REQ_PARA_INVALID;
        else if (player.isInBattle()) code = Retcode.IN_BATTLE_NOW;
        else if (player.getInstance() != null) code = Retcode.CHALLENGE_ALREADY;
        else if (player.getGameplay().getStoryLines().getCurrentId() == 0
            && !player.getGameplay().getStoryLines().isTransitionPending()
            && !player.getGameplay().getRogue().isInside()
            && player.getGameplay().getClockPark().getActive() == null
            && player.getGameplay().getRhythm().getActiveLevel() == 0
            && player.getGameplay().getParkour().getActiveLevel() == 0
            && !player.getGameplay().getParkourVenue().inside()
            && player.getGameplay().getCurrentMissionRaid() == 0
            && player.getGameplay().getTrialRun() == null
            && !request.first().isEmpty()
            && (excel.getStageNum() == 1 ? request.second().isEmpty() : !request.second().isEmpty())
            && java.util.stream.Stream.concat(request.first().stream(), request.second().stream())
                .allMatch(id -> player.getAvatarById(id) != null)
            && GameData.getMapEntranceExcelMap().get(excel.getMapEntranceID()) != null) {
          var first = RepeatedInt.newEmptyInstance();
          var second = RepeatedInt.newEmptyInstance();
          request.first().forEach(first::add);
          request.second().forEach(second::add);
          player
              .getChallengeManager()
              .startChallenge(
                  request.challenge(), first, second, request.firstBuff(), request.secondBuff());
          return true;
        }
      }
      player.sendPacket(new PacketStartChallengeScRsp(code));
      return true;
    }
  }

  // Honey fe00bdb: Challenge.star/id/taken_reward and ChallengeGroup.taken_stars_count_reward.
  // Story support requires a matched original Normal-mode catalog; other modes stay excluded.
  private static void query(GameSession session) {
    var player = session.getPlayer();
    synchronized (player) {
      var response = Gameplay46.builder(session, "GetChallengeScRsp");
      var config = emu.lunarcore.LunarCore.getConfig();
      boolean unlockAll = config != null && config.getServerOptions().unlockAllChallenges;
      var groups = new TreeSet<Integer>();
      var manager = player.getChallengeManager();
      for (var excel :
          GameData.getChallengeExcelMap().values().stream()
              .filter(
                  row ->
                      (row.getType() == ChallengeType.MEMORY || StoryChallengeCatalog.supports(row))
                          && row.getStageNum() >= 1
                          && row.getStageNum() <= 2)
              .sorted(Comparator.comparingInt(emu.lunarcore.data.excel.ChallengeExcel::getId))
              .toList()) {
        var history = manager.getHistory().get(excel.getId());
        if (history == null && !unlockAll) continue;
        if (history != null
            && (history.getChallengeId() != excel.getId()
                || history.getStars() < 0
                || history.getStars() > 7
                || history.getScore() < 0
                || history.isStoryScoresRecorded()
                    && (history.getStoryScoreOne() < 0
                        || history.getStoryScoreTwo() < 0
                        || (long) history.getStoryScoreOne() + history.getStoryScoreTwo()
                            != history.getScore())
                || history.getTakenReward() < 0)) {
          Gameplay46.send(
              session, "GetChallengeScRsp", Map.of("retcode", Retcode.PLAYER_DATA_ERROR.getVal()));
          return;
        }
        var row = Gameplay46.builder(session, "Challenge");
        Gameplay46.set(row, "challenge_id", excel.getId());
        if (history != null) {
          Gameplay46.set(row, "star", history.getStars());
          boolean split = excel.getType() == ChallengeType.STORY && history.isStoryScoresRecorded();
          Gameplay46.set(row, "score_id", split ? history.getStoryScoreOne() : history.getScore());
          if (split) Gameplay46.set(row, "score_two", history.getStoryScoreTwo());
          Gameplay46.set(row, "taken_reward", history.getTakenReward());
        }
        Gameplay46.add(response, "challenge_list", row.build());
        if (excel.getGroupID() > 0) groups.add(excel.getGroupID());
      }
      for (int id : groups) {
        var reward = manager.getTakenRewards().get(id);
        var row = Gameplay46.builder(session, "ChallengeGroup");
        Gameplay46.set(row, "group_id", id);
        Gameplay46.set(
            row, "taken_stars_count_reward", reward == null ? 0L : reward.getTakenStars());
        Gameplay46.add(response, "challenge_group_list", row.build());
      }
      session.sendClientPacket("GetChallengeScRsp", response.build().toByteArray());
    }
  }

  // Honey fe00bdb CmdChallengeType: id3, packed5/12, identifiers11/8.
  // Only owned formal type3/id7 with assist_uid3=0 is supported here.
  static Request read(byte[] bytes) {
    if (bytes.length > 4096) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      var packedFirst = new ArrayList<Integer>();
      var packedSecond = new ArrayList<Integer>();
      var modernFirst = new ArrayList<Integer>();
      var modernSecond = new ArrayList<Integer>();
      int challenge = 0, tag, firstBuff = 0, secondBuff = 0;
      boolean seen = false;
      boolean story = false;
      while ((tag = input.readTag()) != 0) {
        int field = tag >>> 3, wire = tag & 7;
        if (field == 3) {
          if (wire != 0 || seen) return null;
          seen = true;
          challenge = positive(input);
        } else if (field == 5 || field == 12) {
          var target = field == 5 ? packedFirst : packedSecond;
          if (wire == 0) target.add(positive(input));
          else if (wire == 2) {
            var packed = CodedInputStream.newInstance(input.readByteArray());
            while (!packed.isAtEnd()) {
              target.add(positive(packed));
              if (target.size() > 4) return null;
            }
          } else return null;
          if (target.size() > 4) return null;
        } else if (field == 8 || field == 11) {
          if (wire != 2) return null;
          var target = field == 11 ? modernFirst : modernSecond;
          target.add(avatar(input.readByteArray()));
          if (target.size() > 4) return null;
        } else if (field == 14) {
          if (wire != 2 || story) return null;
          var info = CodedInputStream.newInstance(input.readByteArray());
          if (info.readTag() != (13 << 3 | 2)) return null;
          var buffs = CodedInputStream.newInstance(info.readByteArray());
          if (!info.isAtEnd()) return null;
          var fields = new HashSet<Integer>();
          int buffTag;
          while ((buffTag = buffs.readTag()) != 0) {
            int bf = buffTag >>> 3;
            if ((buffTag & 7) != 0 || !fields.add(bf)) return null;
            if (bf == 7) firstBuff = positive(buffs);
            else if (bf == 13) secondBuff = positive(buffs);
            else return null;
          }
          if (!buffs.isAtEnd() || firstBuff <= 0 || secondBuff <= 0) return null;
          story = true;
        } else return null;
      }
      if (!seen
          || !input.isAtEnd()
          || (!packedFirst.isEmpty() || !packedSecond.isEmpty())
              && (!modernFirst.isEmpty() || !modernSecond.isEmpty())) return null;
      var first = modernFirst.isEmpty() ? packedFirst : modernFirst;
      var second = modernSecond.isEmpty() ? packedSecond : modernSecond;
      if (first.isEmpty()
          || new HashSet<>(first).size() != first.size()
          || new HashSet<>(second).size() != second.size()) return null;
      return new Request(challenge, first, second, firstBuff, secondBuff, story);
    } catch (IOException | IllegalArgumentException invalid) {
      return null;
    }
  }

  private static int positive(CodedInputStream input) throws IOException {
    long value = input.readUInt64();
    if (value <= 0 || Long.compareUnsigned(value, Integer.MAX_VALUE) > 0)
      throw new IllegalArgumentException("Invalid challenge identity");
    return (int) value;
  }

  private static int avatar(byte[] bytes) throws IOException {
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    int id = 0, type = 0, tag;
    while ((tag = input.readTag()) != 0) {
      int field = tag >>> 3;
      if ((tag & 7) != 0 || !seen.add(field)) throw new IllegalArgumentException();
      if (field == 7) id = positive(input);
      else if (field == 13) type = positive(input);
      else if (field != 3 || input.readUInt64() != 0) throw new IllegalArgumentException();
    }
    if (id <= 0 || type != 3 || !input.isAtEnd()) throw new IllegalArgumentException();
    return id;
  }

  private MemoryChallenge46() {}
}
