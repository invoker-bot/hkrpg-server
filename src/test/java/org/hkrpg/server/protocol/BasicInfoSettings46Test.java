package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import org.junit.jupiter.api.Test;

class BasicInfoSettings46Test {
  private static DynamicMessage child(DynamicMessage parent, int number) {
    var field = parent.getDescriptorForType().findFieldByNumber(number);
    assertNotNull(field);
    assertTrue(parent.hasField(field),
        "Native _CmdGetBasicInfoScRsp dereferences field " + number + " of "
            + parent.getDescriptorForType().getName());
    return (DynamicMessage) parent.getField(field);
  }

  private static DynamicMessage query(ProfileCreation46Test.Session session) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 80, new byte[0]));
    assertEquals("GetBasicInfoScRsp", session.responseName);
    assertEquals(67, session.getProtocol().clientCommand(session.responseName));
    return session.response;
  }

  @Test
  void successfulBasicInfoIncludesTheSettingsContainerRequiredByTheActualCallback()
      throws Exception {
    var response = query(new ProfileCreation46Test.Session());
    assertEquals(0, Gameplay46.number(response, "retcode"));
    var settings = child(response, 11);
    assertEquals("PlayerSettingInfo", settings.getDescriptorForType().getName());
  }

  @Test
  void settingsAlsoIncludeTheUnconditionallyReadNestedField13() throws Exception {
    var response = query(new ProfileCreation46Test.Session());
    // Reading a protobuf default instance alone would hide the same null in the native client.
    var settings = (DynamicMessage) Gameplay46.value(response, "player_setting_info");
    var record = child(settings, 13);
    assertEquals("PNOLLIAKGDB", record.getDescriptorForType().getName());
    assertEquals(0, record.getSerializedSize(), "No invented flags or remembered preferences");
  }

  @Test
  void wireRoundTripRetainsOnlyRequiredContainersAndLeavesPlayerDataUntouched() throws Exception {
    var session = new ProfileCreation46Test.Session();
    var before = new Gson().toJson(session.actor.getGameplay());
    var response = query(session);
    response = session.getProtocol().clientSchema().parse("GetBasicInfoScRsp", response.toByteArray());
    var settings = child(response, 11);
    child(settings, 13);
    assertEquals(1, settings.getAllFields().size());
    assertFalse(settings.hasField(settings.getDescriptorForType().findFieldByNumber(1456)),
        "The callback explicitly permits the optional LJFJOGEEGPJ message to be absent");
    assertFalse(settings.hasField(settings.getDescriptorForType().findFieldByNumber(1800)),
        "The callback explicitly permits the optional JPGACKPLGCD message to be absent");
    assertEquals(0, settings.getRepeatedFieldCount(settings.getDescriptorForType().findFieldByNumber(759)));
    assertEquals(before, new Gson().toJson(session.actor.getGameplay()));
    assertTrue(session.actor.saves.isEmpty());
    assertTrue(session.actor.notifications.isEmpty());
    assertFalse(session.actor.hasCreatedCharacter());
    assertEquals(false, Gameplay46.value(response, "is_gender_set"));
    assertEquals(session.actor.getGender().getVal(), Gameplay46.number(response, "gender"));
    assertEquals(session.actor.getBirthday(), Gameplay46.number(response, "gameplay_birthday"));
    assertEquals(session.actor.getNextStaminaRecover() / 1000,
        Gameplay46.value(response, "next_recover_time"));
  }

  @Test
  void invalidBasicInfoBodyStillReturnsAnErrorWithoutSavingOrCreatingAProfile() throws Exception {
    var session = new ProfileCreation46Test.Session();
    assertTrue(VersionedPacketHandler.handle(session, 80, new byte[] {8, 1}));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(session.actor.saves.isEmpty());
    assertFalse(session.actor.hasCreatedCharacter());
  }

  @Test
  void unauthenticatedSessionDoesNotReceiveBasicInformation() throws Exception {
    var session = new ProfileCreation46Test.Session();
    session.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, 80, new byte[0]));
    assertNull(session.response);
    assertTrue(session.actor.saves.isEmpty());
  }
}
