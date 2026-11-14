package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Inactive bootstrap profiles for modules without a supported progression/claim implementation.
 * These replies finish the initial network requests; they do not implement those game modes.
 * Source-matched movie bootstrap and keys use dedicated handlers.
 */
final class LoginBootstrap46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    // Honey fe00bdb Raw command pairs; retcode constants independently checked in the pinned
    // installed client (PLDKENJLKME). Numeric routing also covers missing/conflicting named types.
    int response = switch (command) {
      case 5697 -> 5653; case 31 -> 91; case 7172 -> 7173; case 8034 -> 8025;
      case 8110 -> 8116; case 96 -> 47; case 9804 -> 9776; case 8991 -> 8982;
      case 10010 -> 10016; case 9894 -> 9866; case 9490 -> 9496;
      case 9 -> 27; case 522 -> 549; case 2922 -> 2949; case 3405 -> 3475;
      case 5734 -> 5725; case 5834 -> 5825; case 8680 -> 8580;
      case 8948 -> 8914; case 9504 -> 9548;
      case 6029 -> 6017; case 9990 -> 9996; case 3758 -> 3756;
      default -> 0;
    };
    if (response == 0) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    if (command == 9) {
      VideoVersionKeys46.send(session, bytes);
      return true;
    }
    if (command == 31) {
      VideoBootstrap46.send(session, bytes);
      return true;
    }
    int retcodeField = switch (command) {
      case 5697 -> 15; case 96 -> 8; case 7172 -> 6; case 8034 -> 11;
      case 8110 -> 7; case 9804 -> 4; case 8991 -> 14; case 10010 -> 12;
      case 9894 -> 13; case 9490, 9990 -> 9;
      case 522 -> 1; case 2922 -> 6; case 3405, 9504 -> 8;
      case 5734 -> 4; case 5834 -> 10; case 8680 -> 9; case 8948 -> 13;
      case 6029 -> 2; case 3758 -> 3;
      default -> throw new AssertionError(command);
    };
    var body = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(body);
    output.writeUInt32(retcodeField, validRequest(command, bytes) ? 0 : Retcode.REQ_PARA_INVALID.getVal());
    if (command == 8034) output.writeByteArray(7, trainPartyProfile(session));
    if (command == 8680) output.writeByteArray(11, gridFightProfile(session));
    if (command == 6029) output.writeByteArray(1, rogueTournamentProfile(session));
    output.flush();
    // There is no active profile, purchase or negotiated security key in
    // these unimplemented modules. Do not fabricate records, secret_rsp, unlocks or entitlements.
    // B51's native GetDataAsync waits for 9996 for 30 seconds (154d0634). Its converter
    // (154d2330) accepts absent car/cycle and creates an empty challenge locally. Keep
    // this unplayed profile absent; sending an invented cycle would start gameplay sync.
    session.sendClientPacket(response, body.toByteArray());
    return true;
  }

  private static byte[] trainPartyProfile(GameSession session) {
    // TrainPartyGameInstance.Sync -> TrainPartyBuildManager.Sync dereferences this profile.
    // Area 12 is the sole original TrainPartyAreaConfig root (no RequireAreaID).
    // An empty area list resets every area to locked and hides the initial room's model block.
    // Publish only this initial room reference; construction, furniture, funds and rewards stay zero.
    var room = Gameplay46.builder(session, "TrainPartyArea");
    Gameplay46.set(room, "area_id", 12);
    var build = Gameplay46.builder(session, "TrainPartyInfo");
    Gameplay46.add(build, "area_list", room.build());
    if (session.getPlayer() != null) {
      var wall = org.hkrpg.server.gameplay.PlayerRoomDisplay.view(session.getPlayer());
      if (wall.available()) {
        // Personal-room slot references, not paid steps, construction progress or rewards.
        var displayArea = Gameplay46.builder(session, "TrainPartyArea");
        Gameplay46.set(displayArea, "area_id", 16);
        for (int id : new int[] {16001, 16002, 16003})
          Gameplay46.add(displayArea, "static_prop_id_list", id);
        Gameplay46.add(build, "area_list", displayArea.build());
        Gameplay46.set(build, "CMCNNJKJNPK", PlayerRoomDisplay46.display(session, wall.state()));
        // The native selector builds its catalog from this list, not directly from the bag.
        // The inherited inventory has no acquisition timestamp, so do not invent one.
        for (int id : wall.ownedItems()) {
          var item = Gameplay46.builder(session, "GAGGCODGDLB");
          Gameplay46.set(item, "prop_id", id);
          Gameplay46.add(build, "MHABCMLHCGD", item.build());
        }
      } else if (wall.damaged()) {
        org.hkrpg.server.GameDiagnostics.outcome(session, "TrainPartyGetDataCsReq", 16,
            Retcode.PLAYER_DATA_ERROR, "player-room-source-or-save", "");
      }
    }
    var data = Gameplay46.builder(session, "TrainPartyData");
    Gameplay46.set(data, "train_party_info", build.build());
    // Native MKDPFLPICJB.MergeFrom maps wire 6 to +0x30, which Sync passes to
    // _SetCurTurnID. The descriptor's historical name is misleading: zero has no
    // TrainPartyProgress row and throws in _CheckIsTurnLocked. Row 1 is the initial turn.
    Gameplay46.set(data, "unlock_area_num", 1);
    return data.build().toByteArray();
  }

  private static byte[] rogueTournamentProfile(GameSession session) {
    // Native e002bda/e002c26 reads the static profile's division child even when
    // there is no active game. Its empty snapshot grants no division progress.
    var data = Gameplay46.builder(session, "KIKEAJJLAMH");
    Gameplay46.set(data, "DLDCFHHFMID", Gameplay46.builder(session, "JGMDJIENFFH").build());
    // Original permanent guide 101: no pass, reward, unlock or active run is granted.
    // Native df70310 treats an empty collection as all guides passed; the handbook
    // then hides RogueBeta before its replacement category has been unlocked.
    var guide = Gameplay46.builder(session, "DLEKOLHCNIE");
    Gameplay46.set(guide, "area_id", 101);
    Gameplay46.add(data, "DANNIDIPFAL", guide.build());
    return data.build().toByteArray();
  }

  private static byte[] gridFightProfile(GameSession session) {
    // The native initial callback and both talent Sync methods require these containers.
    // Keep progress at zero; catalog references must resolve even for an unplayed profile.
    var initial = Gameplay46.builder(session, "GridFightStaticGameInfo");
    // Pinned 4.6 GridFightConstCommon season/sub-season and the level-zero division
    // in GridFightDivisionInfo. These identify configuration, not completed content.
    Gameplay46.set(initial, "season", 1);
    Gameplay46.set(initial, "sub_season", 4);
    Gameplay46.set(initial, "division_id", 1);
    Gameplay46.set(initial, "exp", Gameplay46.builder(session, "GridFightSeasonExpInfo").build());
    Gameplay46.set(initial, "season_talent", Gameplay46.builder(session, "GridFightTalentInfo").build());
    var permanent = Gameplay46.builder(session, "GridFightPermanentGameInfo");
    Gameplay46.set(permanent, "talent", Gameplay46.builder(session, "GridFightTalentInfo").build());
    var system = Gameplay46.builder(session, "GridFightSystemInfo");
    Gameplay46.set(system, "static_info", initial.build());
    Gameplay46.set(system, "permanent_info", permanent.build());
    return system.build().toByteArray();
  }

  private static boolean validRequest(int command, byte[] bytes) {
    if (bytes.length == 0) return true;
    if (bytes.length > 16_384 || command != 5697 && command != 8110 && command != 3758)
      return false;
    var input = CodedInputStream.newInstance(bytes);
    int selectors = 0;
    boolean singleSeen = false;
    try {
      for (int tag; (tag = input.readTag()) != 0; ) {
        if (command == 5697 && tag == 104) {
          input.readUInt32();
          if (++selectors > 128) return false;
        } else if (command == 5697 && tag == 106) {
          int limit = input.pushLimit(input.readRawVarint32());
          while (input.getBytesUntilLimit() > 0) {
            input.readUInt32();
            if (++selectors > 128) return false;
          }
          input.popLimit(limit);
        } else if (command == 8110 && tag == 48 && !singleSeen) {
          singleSeen = true;
          input.readUInt32();
        } else if (command == 3758 && tag == 32 && !singleSeen) {
          // TrainModule sends register type 1 or 2. Zero is the protobuf default.
          singleSeen = true;
          if (Long.compareUnsigned(input.readUInt64(), 2) > 0) return false;
        } else return false;
      }
      return input.isAtEnd();
    } catch (IOException invalid) {
      return false;
    }
  }

  private LoginBootstrap46() {}
}
