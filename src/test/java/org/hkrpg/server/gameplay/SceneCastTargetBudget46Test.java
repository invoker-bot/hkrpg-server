package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.scene.entity.EntityProp;
import java.nio.file.Path;
import java.util.ArrayList;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SceneCastTargetBudget46Test {
  @TempDir Path root;
  final BaseZoneRecoveryProp46Test base = new BaseZoneRecoveryProp46Test();
  @BeforeEach void setup() throws Exception { base.root = root; base.setup(); }
  @AfterEach void cleanup() throws Exception { base.cleanup(); }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
  void aPropCannotBorrowAnAvatarIdentityFromACollidingUseId(int phase) throws Exception {
    var request = base.request("direct", base.bottle.getEntityId());
    request.clearField(request.getDescriptorForType().findFieldByName("maze_ability_str"));
    var tags = request.getDescriptorForType().findFieldByName("skill_extra_tags");
    if (phase != 0) request.addRepeatedField(tags, tags.getEnumType().findValueByNumber(phase == 3 ? 1 : phase));
    if (phase == 3) request.addRepeatedField(tags, tags.getEnumType().findValueByNumber(2));
    assertNotEquals(0, base.retcode(base.dispatch(request)));
    assertSame(base.bottle, base.scene.getEntityById(base.bottle.getEntityId()));
    assertEquals(0, base.opening.base.player.getCurrentLineup().getMp());
  }

  @Test void mergingAuxiliaryPropsCannotBypassTheHitLimitOrRecoverPoints() throws Exception {
    var props = new ArrayList<EntityProp>();
    props.add(base.bottle);
    for (int i = 0; i < 64; i++) {
      var prop = new EntityProp(base.scene, base.bottle.getExcel(), null, null);
      base.scene.addEntity(prop);
      assertTrue(base.scene.canDestroyProp(prop));
      props.add(prop);
    }
    var player = base.opening.base.player;
    byte[] request = SceneCastAdmission46Test.wire(out -> {
      out.writeUInt32(13, 701);
      out.writeUInt32(2, player.getCurrentLeaderAvatar().getEntityId());
      out.writeString(15, "LocalPlayer_Kafka_NormalAtk01");
      out.writeUInt32(9, props.getFirst().getEntityId());
      for (var prop : props.subList(1, props.size())) out.writeUInt32(6, prop.getEntityId());
    });
    assertTrue(VersionedPacketHandler.handle(base.session, 1460, request));
    assertEquals(1, base.session.responses.size());
    assertNotEquals(0, MazeAttackResults46Test.number(base.session.responses.getFirst(), "retcode"));
    assertEquals(0, player.getCurrentLineup().getMp());
    for (var prop : props) assertSame(prop, base.scene.getEntityById(prop.getEntityId()));
    assertNull(player.getBattle());
  }
}
