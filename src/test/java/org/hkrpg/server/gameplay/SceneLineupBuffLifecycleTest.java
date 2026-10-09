package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Scene refresh must distinguish retained actors from newly installed actor instances. */
class SceneLineupBuffLifecycleTest {
  static final int BUFF = 121101;
  static final int SECOND = StoryLineLifecycleTest.ACTOR + 2;
  @TempDir Path root;
  final MazeCasterBuffRemovalTest fixture = new MazeCasterBuffRemovalTest();
  AvatarExcel oldSecond;
  boolean secondInstalled;

  @BeforeEach
  void setup() {
    fixture.root = root;
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      fixture.cleanup();
    } finally {
      if (secondInstalled) {
        GameData.getAvatarExcelMap().keySet().remove(SECOND);
        if (oldSecond != null) GameData.getAvatarExcelMap().add(oldSecond);
      }
    }
  }

  GameAvatar second(MazeCasterBuffRemovalTest.Capture owner) {
    oldSecond = GameData.getAvatarExcelMap().get(SECOND);
    secondInstalled = true;
    var excel = fixture.gson.fromJson("{\"AvatarID\":" + SECOND + "}", AvatarExcel.class);
    GameData.getAvatarExcelMap().add(excel);
    var actor = new GameAvatar(excel);
    actor.setOwner(owner);
    owner.getAvatars().getAvatars().put(SECOND, actor);
    return actor;
  }

  @ParameterizedTest
  @CsvSource({"false,1.1", "false,-1", "true,1.1", "true,-1"})
  void repeatedRefreshKeepsTheSameFormalOrTrialActorAndItsExactExpiry(
      boolean trial, double lifetime) throws Exception {
    var owner = fixture.owner(trial);
    var scene = owner.getScene();
    var actor = owner.getCurrentLeaderAvatar();
    actor.addBuff(BUFF, lifetime);
    long expiry = actor.getBuffs().get(BUFF);
    int entity = actor.getEntityId();
    scene.syncLineup();
    scene.syncLineup();
    assertEquals(expiry, actor.getBuffs().get(BUFF), "Refresh erased an active actor's buff");
    assertEquals(entity, actor.getEntityId());
    assertSame(actor, scene.getAvatarByEntityId(entity));
    assertEquals(1, actor.getBuffs().size());
    if (trial)
      assertTrue(
          owner.getAvatars().getAvatarById(StoryLineLifecycleTest.ACTOR).getBuffs().isEmpty());
  }

  @Test
  void joiningAnActorResetsOnlyTheNewSceneInstance() throws Exception {
    var owner = fixture.owner(false);
    var retained = owner.getCurrentLeaderAvatar();
    retained.addBuff(BUFF, -1);
    int entity = retained.getEntityId();
    var added = second(owner);
    added.addBuff(BUFF + 1, -1);
    assertTrue(owner.getLineupManager().joinLineup(0, 1, SECOND));
    assertEquals(Long.MAX_VALUE, retained.getBuffs().get(BUFF));
    assertEquals(entity, retained.getEntityId());
    assertTrue(added.getBuffs().isEmpty(), "Detached effects must not enter a newly joined actor");
    assertSame(added, owner.getScene().getAvatarByEntityId(added.getEntityId()));
  }

  @Test
  void departingActorDropsItsSceneEffectsWhileTheRemainingActorKeepsThem() throws Exception {
    var owner = fixture.owner(false);
    var removed = owner.getCurrentLeaderAvatar();
    var retained = second(owner);
    assertTrue(owner.getLineupManager().joinLineup(0, 1, SECOND));
    removed.addBuff(BUFF, -1);
    retained.addBuff(BUFF + 1, -1);
    int stale = removed.getEntityId();
    assertTrue(owner.getLineupManager().quitLineup(0, removed.getAvatarId()));
    assertTrue(removed.getBuffs().isEmpty(), "A departing scene actor retained detached effects");
    assertEquals(0, removed.getEntityId());
    assertNull(owner.getScene().getAvatarByEntityId(stale));
    assertEquals(Long.MAX_VALUE, retained.getBuffs().get(BUFF + 1));
  }

  @Test
  void rejoiningTheSameStoredAvatarStartsWithANewEntityAndNoDetachedEffects() throws Exception {
    var owner = fixture.owner(false);
    var actor = owner.getCurrentLeaderAvatar();
    second(owner);
    assertTrue(owner.getLineupManager().joinLineup(0, 1, SECOND));
    int stale = actor.getEntityId();
    assertTrue(owner.getLineupManager().quitLineup(0, actor.getAvatarId()));
    actor.addBuff(BUFF, -1);
    assertTrue(owner.getLineupManager().joinLineup(0, 1, actor.getAvatarId()));
    assertTrue(actor.getBuffs().isEmpty());
    assertNotEquals(stale, actor.getEntityId());
    assertNull(owner.getScene().getAvatarByEntityId(stale));
    assertSame(actor, owner.getScene().getAvatarByEntityId(actor.getEntityId()));
  }

  @Test
  void replacingAnActorObjectWithTheSameIdRemovesTheOldEntityAndInstallsTheNewOne()
      throws Exception {
    var owner = fixture.owner(false);
    var old = owner.getCurrentLeaderAvatar();
    int stale = old.getEntityId();
    old.addBuff(BUFF, -1);
    var replacement = new GameAvatar(old.getExcel());
    replacement.setOwner(owner);
    replacement.addBuff(BUFF + 1, -1);
    owner.getAvatars().getAvatars().put(old.getAvatarId(), replacement);
    owner.sent.clear();
    owner.getScene().syncLineup();
    assertTrue(replacement.getEntityId() > 0, "Same ID hid a replaced scene actor instance");
    assertNotEquals(stale, replacement.getEntityId());
    assertEquals(0, old.getEntityId());
    assertTrue(old.getBuffs().isEmpty());
    assertTrue(replacement.getBuffs().isEmpty());
    assertNull(owner.getScene().getAvatarByEntityId(stale));
    assertSame(replacement, owner.getScene().getAvatarByEntityId(replacement.getEntityId()));
    assertEquals(1, owner.sent.size());
    var bridge = ProtocolBridge.forVersion("4.6.0");
    BasePacket packet = owner.sent.getFirst();
    assertEquals(CmdId.SceneGroupRefreshScNotify, packet.getCmdId());
    var wire = bridge.outbound(packet.getCmdId(), packet.getData().toByteArray());
    var message = bridge.clientSchema().parse("SceneGroupRefreshScNotify", wire.body());
    var group = (DynamicMessage) ((List<?>) field(message, "group_refresh_list")).getFirst();
    var rows = (List<?>) field(group, "refresh_entity");
    assertEquals(2, rows.size());
    var added = (DynamicMessage) field((DynamicMessage) rows.get(0), "add_entity");
    assertEquals(replacement.getEntityId(), field(added, "entity_id"));
    assertEquals(stale, field((DynamicMessage) rows.get(1), "delete_entity"));
  }

  Object field(DynamicMessage message, String name) {
    var field = message.getDescriptorForType().findFieldByName(name);
    assertNotNull(field, name);
    return message.getField(field);
  }

  @Test
  void rosterReorderingKeepsBothActorsEffectsAndEntityIdentities() throws Exception {
    var owner = fixture.owner(false);
    var first = owner.getCurrentLeaderAvatar();
    var other = second(owner);
    assertTrue(owner.getLineupManager().joinLineup(0, 1, SECOND));
    first.addBuff(BUFF, -1);
    other.addBuff(BUFF + 1, -1);
    int firstEntity = first.getEntityId(), secondEntity = other.getEntityId();
    owner.getCurrentLineup().replace(List.of(SECOND, first.getAvatarId()));
    assertEquals(Long.MAX_VALUE, first.getBuffs().get(BUFF));
    assertEquals(Long.MAX_VALUE, other.getBuffs().get(BUFF + 1));
    assertEquals(firstEntity, first.getEntityId());
    assertEquals(secondEntity, other.getEntityId());
    assertSame(other, owner.getCurrentLeaderAvatar());
  }
}
