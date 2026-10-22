package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.GameConstants;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Literal 4.6 field numbers: request construction deliberately does not use our descriptors. */
class SceneCastAdmission46Test {
  @TempDir Path root;
  final MazeAttackResults46Test base = new MazeAttackResults46Test();

  @BeforeEach void setup() throws Exception { base.root = root; base.setup(); }
  @AfterEach void cleanup() throws Exception { base.cleanup(); }

  interface Writer { void write(CodedOutputStream out) throws Exception; }
  static byte[] wire(Writer writer) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    writer.write(out);
    out.flush();
    return bytes.toByteArray();
  }

  DynamicMessage dispatch(byte[] bytes) throws Exception {
    var session = base.new Session();
    assertTrue(VersionedPacketHandler.handle(session, 1460, bytes));
    assertEquals(1, session.responses.size());
    return session.responses.getFirst();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void observedHitShapeAndBothListEncodingsKeepUseIdSeparateFromEntities(int form) throws Exception {
    var monster = base.monster("[]");
    int actor = base.actor.getEntityId();
    // Observed 2026-10-23 06:17:48 +08:00: use=441, attacker=39, field6=[1], tag=[1].
    // Reconstructed wire with scene IDs remapped. Other placements/encodings are variants,
    // not additional client captures. Motion includes valid negative zig-zag coordinates.
    var response = dispatch(wire(out -> {
      out.writeUInt32(2, monster.getEntityId());
      out.writeUInt32(13, form == 0 ? 441 : actor);
      out.writeByteArray(5, wire(m -> m.writeByteArray(2, wire(v -> v.writeSInt32(1, -12000)))));
      out.writeByteArray(11, new byte[] {1});
      int field = form < 2 ? 6 : form < 4 ? 9 : 7;
      if (field == 7) out.writeByteArray(7, wire(g -> {
        if (form == 4) g.writeUInt32(7, actor);
        else g.writeByteArray(7, wire(p -> p.writeUInt32NoTag(actor)));
      }));
      else if (form % 2 == 0) out.writeUInt32(field, actor);
      else out.writeByteArray(field, wire(p -> p.writeUInt32NoTag(actor)));
    }));
    assertEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertEquals(form == 0 ? 441 : actor, MazeAttackResults46Test.number(response, "cast_entity_id"));
    var battle = base.branch.base.base.player.getBattle();
    assertNotNull(battle);
    assertTrue(battle.getBuffs().containsKey(GameConstants.BATTLE_AMBUSH_BUFF_ID));
    assertEquals(java.util.List.of(monster), battle.getNpcMonsters());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
  void malformedWireCannotSilentlyBecomeAValidAttack(int variant) throws Exception {
    var monster = base.monster("[]");
    var player = base.branch.base.base.player;
    int mp = player.getCurrentLineup().getMp();
    var response = dispatch(wire(out -> {
      out.writeUInt64(2, (long) monster.getEntityId() + (variant == 0 ? 1L << 32 : 0));
      out.writeUInt64(13, 441L + (variant == 1 ? 1L << 32 : 0));
      out.writeUInt32(11, 1);
      if (variant == 2) out.writeUInt32(2, monster.getEntityId());
      if (variant == 3) out.writeByteArray(2, new byte[] {});
      if (variant == 4) out.writeByteArray(7, wire(g -> g.writeUInt32(8, 1)));
      if (variant == 5) out.writeByteArray(7, wire(g -> g.writeFixed32(7, 1)));
      if (variant == 6) out.writeByteArray(5, wire(m -> m.writeUInt32(3, 1)));
      if (variant == 7) out.writeByteArray(5, wire(m -> m.writeByteArray(2,
          wire(v -> v.writeUInt64(1, (1L << 32) + 2)))));
      if (variant == 8) for (int i = 0; i < 129; i++) out.writeUInt32(6, base.actor.getEntityId());
      if (variant == 10) out.writeUInt32(20, 1);
      if (variant == 11) out.writeUInt32(11, 99);
      if (variant == 12) out.writeByteArray(7, new byte[] {(byte) 0x80});
      out.writeUInt64(6, (long) base.actor.getEntityId() + (variant == 9 ? 1L << 32 : 0));
    }));
    assertNotEquals(0, MazeAttackResults46Test.number(response, "retcode"));
    assertNull(player.getBattle());
    assertTrue(monster.isAlive());
    assertEquals(mp, player.getCurrentLineup().getMp());
    assertEquals(0, base.branch.base.base.drops);
  }

  @Test void alreadyActiveBattleIsBusyAndDoesNotBecomeAParameterFailure() throws Exception {
    var monster = base.monster("[]");
    byte[] hit = wire(out -> {
      out.writeUInt32(2, monster.getEntityId());
      out.writeUInt32(13, 441);
      out.writeUInt32(6, base.actor.getEntityId());
      out.writeUInt32(11, 1);
    });
    assertEquals(0, MazeAttackResults46Test.number(dispatch(hit), "retcode"));
    var battle = base.branch.base.base.player.getBattle();
    assertEquals(emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        MazeAttackResults46Test.number(dispatch(hit), "retcode"));
    assertSame(battle, base.branch.base.base.player.getBattle());
  }
}
