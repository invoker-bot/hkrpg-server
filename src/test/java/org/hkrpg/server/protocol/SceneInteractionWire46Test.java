package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SceneInteractionWire46Test {
  final SceneInteraction46Test base = new SceneInteraction46Test();
  @BeforeEach void setup() { base.setup(); }
  @AfterEach void cleanup() { base.cleanup(); }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void identityAliasesAndDuplicateFieldsCannotOpenADoorOrAdvanceItsMission(int variant) throws Exception {
    var prop = base.prop();
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt64(4, (long) prop.getEntityId() + (variant == 0 ? 1L << 32 : 0));
    out.writeUInt64(5, (long) SceneInteraction46Test.INTERACT + (variant == 1 ? 1L << 32 : 0));
    out.writeUInt64(8, 1L << 40);
    if (variant == 2) out.writeUInt32(5, SceneInteraction46Test.INTERACT);
    if (variant == 3) out.writeUInt64(8, 1L << 40);
    out.flush();
    assertTrue(VersionedPacketHandler.handle(base.session, 1405, bytes.toByteArray()));
    assertNotEquals(0, Gameplay46.number(base.session.response, "retcode"));
    assertEquals(PropState.Closed, prop.getState());
    assertEquals(QuestState.Phase.DOING,
        base.session.getPlayer().getMissionManager().sub(SceneInteraction46Test.SUB).getPhase());
  }

  @Test void opaqueUInt64ContextMustNotBeTruncatedOrTreatedAsAConfigId() throws Exception {
    assertEquals(0, base.request(base.prop().getEntityId(), SceneInteraction46Test.INTERACT, -1L));
    assertEquals(PropState.Open, base.prop().getState());
  }
}
