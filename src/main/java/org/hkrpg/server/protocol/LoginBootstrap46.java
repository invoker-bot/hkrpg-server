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
 */
final class LoginBootstrap46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    // Honey fe00bdb Raw command pairs; retcode constants independently checked in the pinned
    // installed client (PLDKENJLKME). Numeric routing also covers missing/conflicting named types.
    int response = switch (command) {
      case 5697 -> 5653; case 31 -> 91; case 7172 -> 7173; case 8034 -> 8025;
      case 8110 -> 8116; case 96 -> 47; case 9804 -> 9776; case 8991 -> 8982;
      case 10010 -> 10016; case 9894 -> 9866; case 9490 -> 9496; case 78 -> 4;
      case 9 -> 27; case 522 -> 549; case 2922 -> 2949; case 3405 -> 3475;
      case 5734 -> 5725; case 5834 -> 5825; case 6134 -> 6125; case 8680 -> 8580;
      case 8948 -> 8914; case 9504 -> 9548;
      default -> 0;
    };
    if (response == 0) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    int retcodeField = switch (command) {
      case 5697 -> 15; case 31, 96 -> 8; case 7172 -> 6; case 8034 -> 11;
      case 8110 -> 7; case 9804 -> 4; case 8991 -> 14; case 10010 -> 12;
      case 9894 -> 13; case 9490, 78 -> 9;
      case 9 -> 5; case 522 -> 1; case 2922 -> 6; case 3405, 9504 -> 8;
      case 5734 -> 4; case 5834 -> 10; case 6134, 8680 -> 9; case 8948 -> 13;
      default -> throw new AssertionError(command);
    };
    var body = new ByteArrayOutputStream();
    var output = CodedOutputStream.newInstance(body);
    output.writeUInt32(retcodeField, validRequest(command, bytes) ? 0 : Retcode.REQ_PARA_INVALID.getVal());
    if (command == 8034) output.writeByteArray(7, trainPartyProfile(session));
    if (command == 8680) output.writeByteArray(11, gridFightProfile(session));
    output.flush();
    // There is no active profile, purchase, level-claim ledger or negotiated security key in
    // these unimplemented modules. Do not fabricate records, secret_rsp, unlocks or entitlements.
    session.sendClientPacket(response, body.toByteArray());
    return true;
  }

  private static byte[] trainPartyProfile(GameSession session) {
    // TrainPartyGameInstance.Sync -> TrainPartyBuildManager.Sync dereferences this profile.
    // The optional gameplay state remains absent; no area, passenger or funds are invented.
    var data = Gameplay46.builder(session, "TrainPartyData");
    Gameplay46.set(data, "train_party_info", Gameplay46.builder(session, "TrainPartyInfo").build());
    return data.build().toByteArray();
  }

  private static byte[] gridFightProfile(GameSession session) {
    // The native initial callback and both talent Sync methods require these containers.
    // Keep all progress scalars at zero and omit current_info and optional handbook records.
    var initial = Gameplay46.builder(session, "GridFightStaticGameInfo");
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
    if (bytes.length > 16_384 || command != 5697 && command != 31 && command != 8110) return false;
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
        } else if (command == 31 && tag == 90 && !singleSeen) {
          singleSeen = true;
          // The security challenge is opaque and has no configured negotiation implementation.
          // Validate its framing without retaining, logging, or echoing it as a key.
          input.readBytes();
        } else if (command == 8110 && tag == 48 && !singleSeen) {
          singleSeen = true;
          input.readUInt32();
        } else return false;
      }
      return input.isAtEnd();
    } catch (IOException invalid) {
      return false;
    }
  }

  private LoginBootstrap46() {}
}
