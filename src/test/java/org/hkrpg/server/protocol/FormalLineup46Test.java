package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.LineupAvatarOuterClass.LineupAvatar;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FormalLineup46Test {
  final MissionRaid46Test fixture = new MissionRaid46Test();

  static class Actor extends MissionRaid46Test.Actor {
    int lineupSaves, sceneSyncs;
    boolean failPlayerSave, failLineupSave;

    Actor() {
      for (int id : List.of(MissionRaid46Test.AVATAR, 1209)) {
        getAvatars()
            .getAvatars()
            .put(
                id,
                new GameAvatar() {
                  public int getAvatarId() {
                    return id;
                  }

                  public int getActorId() {
                    return id;
                  }

                  public LineupAvatar toLineupAvatarProto(PlayerLineup lineup, int slot) {
                    return LineupAvatar.newInstance().setId(id).setSlot(slot).setAvatarTypeValue(1);
                  }
                });
        getAvatars().getAvatars().get(id).setOwner(this);
      }
      teams.getLineups()[0] =
          new PlayerLineup(this, 0) {
            public void save() {
              if (failLineupSave) throw new IllegalStateException("simulated lineup save failure");
              lineupSaves++;
            }
          };
      teams.getCurrentLineup().getAvatars().add(MissionRaid46Test.AVATAR);
    }

    public GameAvatar getAvatarById(int id) {
      return getAvatars().getAvatars().get(id);
    }

    @Override
    public void save() {
      if (failPlayerSave) throw new IllegalStateException("simulated player save failure");
    }

    public Scene getScene() {
      return new Scene(this, GameData.getMazePlaneExcelMap().get(plane), floor) {
        public void syncLineup() {
          sceneSyncs++;
        }
      };
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();
    int command;
    byte[] payload;

    public Player getPlayer() {
      return actor;
    }

    public void sendClientPacket(int command, byte[] bytes) {
      this.command = command;
      payload = bytes;
    }
  }

  Session session;

  @BeforeEach
  void prepare() {
    fixture.setup();
    session = new Session();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  DynamicMessage.Builder request(int... ids) {
    var req = Gameplay46.builder(session, "ReplaceLineupCsReq");
    var rows = req.getDescriptorForType().findFieldByName("lineup_slot_list");
    for (int i = 0; i < ids.length; i++) {
      var row = DynamicMessage.newBuilder(rows.getMessageType());
      Gameplay46.set(row, "id", ids[i]);
      Gameplay46.set(row, "slot", i);
      var type = row.getDescriptorForType().findFieldByName("avatar_type");
      row.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
      req.addRepeatedField(rows, row.build());
    }
    return req;
  }

  int code(byte[] bytes) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 704, bytes));
    assertEquals(719, session.command);
    var wire = UnknownFieldSet.parseFrom(session.payload);
    assertTrue(Set.of(7, 9).containsAll(wire.asMap().keySet()));
    return wire.hasField(9) ? wire.getField(9).getVarintList().getFirst().intValue() : 0;
  }

  int code(DynamicMessage.Builder req) throws Exception {
    return code(req.build().toByteArray());
  }

  @Test
  void aNativeFormalRosterActuallyReplacesMembersAndHonorsTheRequestedLeader() throws Exception {
    var req = request(MissionRaid46Test.AVATAR, 1209);
    Gameplay46.set(req, "leader_slot", 1);
    assertEquals(0, code(req));
    assertEquals(
        List.of(MissionRaid46Test.AVATAR, 1209), session.actor.getCurrentLineup().getAvatars());
    assertEquals(1, session.actor.getCurrentLineup().getLeader());
    assertEquals(1, session.actor.lineupSaves);
    assertEquals(1, session.actor.sceneSyncs);
    assertEquals(0, code(req));
    assertEquals(1, session.actor.lineupSaves);
  }

  @Test
  void sparseNativeSlotsAreCompactedWithTheSameLeader() throws Exception {
    var req = request(MissionRaid46Test.AVATAR, 1209);
    var rows = req.getDescriptorForType().findFieldByName("lineup_slot_list");
    var second = ((DynamicMessage) req.getRepeatedField(rows, 1)).toBuilder();
    Gameplay46.set(second, "slot", 3);
    req.setRepeatedField(rows, 1, second.build());
    Gameplay46.set(req, "leader_slot", 3);
    assertEquals(0, code(req));
    assertEquals(List.of(MissionRaid46Test.AVATAR, 1209), session.actor.getCurrentLineup().getAvatars());
    assertEquals(1, session.actor.getCurrentLineup().getLeader());
  }

  @Test
  void failedRosterSaveReturnsBusyAndPreservesTheOldParty() throws Exception {
    session.actor.failLineupSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code(request(1209)));
    assertEquals(List.of(MissionRaid46Test.AVATAR), session.actor.getCurrentLineup().getAvatars());
    assertEquals(0, session.actor.getCurrentLineup().getLeader());
    assertEquals(0, session.actor.sceneSyncs);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "empty",
        "unowned",
        "duplicate",
        "leader",
        "slot",
        "assist",
        "trial",
        "virtual",
        "story",
        "wrong-plane",
        "wrong-index"
      })
  void invalidFormalRequestsKeepTheRealRosterAndReturnAnError(String variant) throws Exception {
    var req = request(1209);
    switch (variant) {
      case "empty" -> req = request();
      case "unowned" -> req = request(1224);
      case "duplicate" -> req = request(1209, 1209);
      case "leader" -> Gameplay46.set(req, "leader_slot", 1);
      case "virtual" -> Gameplay46.set(req, "_is_virtual", true);
      case "story" -> Gameplay46.set(req, "game_story_line_id", 2022304);
      case "wrong-plane" -> Gameplay46.set(req, "plane_id", 1);
      case "wrong-index" -> Gameplay46.set(req, "index", 9);
      default -> {
        var field = req.getDescriptorForType().findFieldByName("lineup_slot_list");
        var row = ((DynamicMessage) req.getRepeatedField(field, 0)).toBuilder();
        if (variant.equals("slot")) Gameplay46.set(row, "slot", 1);
        if (variant.equals("assist")) Gameplay46.set(row, "assist_uid", 10000);
        if (variant.equals("trial")) {
          var type = row.getDescriptorForType().findFieldByName("avatar_type");
          row.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
        }
        req.setRepeatedField(field, 0, row.build());
      }
    }
    assertNotEquals(0, code(req));
    assertEquals(List.of(MissionRaid46Test.AVATAR), session.actor.getCurrentLineup().getAvatars());
    assertEquals(0, session.actor.lineupSaves);
  }

  @Test
  void aWideOrRepeatedIndexCannotTurnIntoTheActiveTeam() throws Exception {
    for (boolean duplicate : List.of(false, true)) {
      var buffer = new java.io.ByteArrayOutputStream();
      buffer.write(request(1209).build().toByteArray());
      var out = CodedOutputStream.newInstance(buffer);
      out.writeUInt64(1, duplicate ? 0 : 0x100000000L);
      if (duplicate) out.writeUInt32(1, 0);
      out.flush();
      assertNotEquals(0, code(buffer.toByteArray()));
      assertEquals(
          List.of(MissionRaid46Test.AVATAR), session.actor.getCurrentLineup().getAvatars());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"battle", "raid", "rogue", "trial", "story-transition"})
  void aBusyGameplayContextCannotEditTheNormalFormalTeam(String mode) throws Exception {
    var p = session.actor;
    switch (mode) {
      case "battle" -> p.battle = true;
      case "raid" ->
          p.getGameplay()
              .beginMissionRaid(
                  new org.hkrpg.server.gameplay.GameplayState.MissionRaidRun(
                      40235, 0, 2022005, 40235, 40235001, 40235001));
      case "rogue" -> p.getGameplay().getRogue().setInside(true);
      case "trial" -> {
        var f = p.getGameplay().getClass().getDeclaredField("trialRun");
        f.setAccessible(true);
        f.set(
            p.getGameplay(),
            new com.google.gson.Gson().fromJson("{}", org.hkrpg.server.gameplay.TrialRun.class));
      }
      case "story-transition" -> p.getGameplay().getStoryLines().begin(0, null, null);
    }
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code(request(1209)));
    assertEquals(List.of(MissionRaid46Test.AVATAR), p.getCurrentLineup().getAvatars());
  }
}
