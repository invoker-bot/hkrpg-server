package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.hkrpg.server.gameplay.RogueDownload;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real source avatars; only scene placement and initial one-member run are isolated fixtures. */
class UniverseDownloadValidation46Test {
  final UniverseDownload46Test fixture = new UniverseDownload46Test();

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
    fixture.begin();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  static void privateValue(GameAvatar avatar, String name, Object value) throws Exception {
    var field = GameAvatar.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(avatar, value);
  }

  void unchanged(int saves) {
    var player = fixture.s.explorer;
    var run = player.getGameplay().getRogue();
    assertEquals(Set.of(199961), run.getVitals().keySet());
    assertEquals(List.of(199961), run.getRoster());
    assertTrue(run.getUsedDownloadConsoles().isEmpty());
    assertEquals(300, run.getCoins());
    assertEquals(saves, player.saves);
    assertEquals(
        PropState.Closed,
        ((emu.lunarcore.game.scene.entity.EntityProp)
                player.getScene().getEntityById(UniverseDownload46Test.ENTITY))
            .getState());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foreign-owner",
        "missing-owner",
        "foreign-uid",
        "temporary-formal",
        "unregistered-excel",
        "missing-excel",
        "unrelated-excel"
      })
  void invalidFormalIdentityCannotBeDownloaded(String mode) throws Exception {
    var player = fixture.s.explorer;
    int id = UniverseDownload46Test.AVATAR;
    var avatar = player.getAvatarById(id);
    int saves = player.saves;
    switch (mode) {
      case "foreign-owner" -> avatar.setOwner(new Player());
      case "missing-owner" -> privateValue(avatar, "owner", null);
      case "foreign-uid" -> privateValue(avatar, "ownerUid", 1234567);
      case "temporary-formal" -> {
        player.getAvatars().getAvatars().remove(id);
        player.getAvatars().getTempAvatars().put(id, avatar);
      }
      case "unregistered-excel" ->
          privateValue(
              avatar,
              "excel",
              new com.google.gson.Gson().fromJson("{\"AvatarID\":1202}", AvatarExcel.class));
      case "missing-excel" -> privateValue(avatar, "excel", null);
      case "unrelated-excel" ->
          privateValue(avatar, "excel", GameData.getAvatarExcelMap().get(1102));
      default -> throw new AssertionError(mode);
    }
    assertEquals(
        Retcode.ROGUE_PICK_AVATAR_INVALID,
        RogueDownload.pick(player, List.of(id), UniverseDownload46Test.ENTITY));
    unchanged(saves);
  }

  @Test
  void anOwnedActiveMultipathUsesItsBaseAvatarIdAndIndependentVitals() {
    var player = fixture.s.explorer;
    var avatar = fixture.ownedAvatar(player, 8001);
    var path = new AvatarMultiPath(player, GameData.getAvatarExcelMap().get(8003));
    player.getAvatars().getMultiPaths().put(8003, path);
    avatar.setMultiPath(path);
    assertEquals(
        Retcode.ROGUE_PICK_AVATAR_INVALID,
        RogueDownload.pick(player, List.of(8003), UniverseDownload46Test.ENTITY));
    assertEquals(
        Retcode.SUCC, RogueDownload.pick(player, List.of(8001), UniverseDownload46Test.ENTITY));
    assertEquals(5000, player.getGameplay().getRogue().getVitals().get(8001).getSp());
    assertEquals(
        0, avatar.getCurrentSp(new emu.lunarcore.game.player.lineup.PlayerLineup(player, 0)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foreign-path-owner",
        "foreign-path-uid",
        "detached-path",
        "missing-path-row",
        "wrong-path-base"
      })
  void invalidActiveMultipathCannotBorrowAFormalBase(String mode) throws Exception {
    var player = fixture.s.explorer;
    var avatar = fixture.ownedAvatar(player, 8001);
    var path = new AvatarMultiPath(player, GameData.getAvatarExcelMap().get(8003));
    player.getAvatars().getMultiPaths().put(8003, path);
    avatar.setMultiPath(path);
    switch (mode) {
      case "foreign-path-owner" -> path.setOwner(new Player());
      case "foreign-path-uid" -> {
        var field = AvatarMultiPath.class.getDeclaredField("ownerUid");
        field.setAccessible(true);
        field.setInt(path, 9999);
      }
      case "detached-path" -> player.getAvatars().getMultiPaths().remove(8003);
      case "missing-path-row" -> GameData.getMultiplePathAvatarExcelMap().keySet().remove(8003);
      case "wrong-path-base" -> {
        var row =
            new com.google.gson.Gson()
                .fromJson(
                    "{\"AvatarID\":8003,\"BaseAvatarID\":1102}",
                    emu.lunarcore.data.excel.MultiplePathAvatarExcel.class);
        GameData.getMultiplePathAvatarExcelMap().add(row);
      }
      default -> throw new AssertionError(mode);
    }
    int saves = player.saves;
    assertEquals(
        Retcode.ROGUE_PICK_AVATAR_INVALID,
        RogueDownload.pick(player, List.of(8001), UniverseDownload46Test.ENTITY));
    unchanged(saves);
  }

  byte[] wire(String mode) throws Exception {
    var buffer = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    long avatar = UniverseDownload46Test.AVATAR, entity = UniverseDownload46Test.ENTITY;
    if (mode.equals("avatar-uint-alias")) avatar += 1L << 32;
    if (mode.equals("entity-uint-alias")) entity += 1L << 32;
    out.writeUInt64(6, entity);
    if (mode.equals("duplicate-entity")) out.writeUInt64(6, entity);
    if (mode.equals("duplicate-entity-overwritten")) out.writeUInt64(6, entity + 1);
    if (mode.equals("wrong-entity-wire")) out.writeBytes(6, com.google.protobuf.ByteString.EMPTY);
    if (mode.equals("wrong-avatar-wire")) out.writeFixed32(14, (int) avatar);
    else if (mode.equals("packed-avatar") || mode.equals("mixed-empty-packed")) {
      if (mode.equals("mixed-empty-packed"))
        out.writeBytes(14, com.google.protobuf.ByteString.EMPTY);
      var packed = new ByteArrayOutputStream();
      var values = CodedOutputStream.newInstance(packed);
      values.writeUInt64NoTag(avatar);
      values.flush();
      out.writeByteArray(14, packed.toByteArray());
    } else if (mode.equals("packed-avatar-alias")) {
      var packed = new ByteArrayOutputStream();
      var values = CodedOutputStream.newInstance(packed);
      values.writeUInt64NoTag(avatar + (1L << 32));
      values.flush();
      out.writeByteArray(14, packed.toByteArray());
    } else out.writeUInt64(14, avatar);
    if (mode.equals("empty-extra")) out.writeBytes(11, com.google.protobuf.ByteString.EMPTY);
    if (mode.equals("unknown-field")) out.writeUInt32(199, 1);
    if (mode.equals("extra-avatar")) out.writeUInt32(14, 1102);
    if (mode.equals("duplicate-avatar")) out.writeUInt32(14, (int) avatar);
    out.flush();
    return buffer.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate-entity",
        "avatar-uint-alias",
        "entity-uint-alias",
        "packed-avatar-alias",
        "empty-extra",
        "wrong-entity-wire",
        "wrong-avatar-wire",
        "duplicate-entity-overwritten",
        "unknown-field",
        "extra-avatar",
        "duplicate-avatar"
      })
  void invalidNativeWireHasNoAvatarOrConsoleMutation(String mode) throws Exception {
    int saves = fixture.s.explorer.saves;
    assertTrue(VersionedPacketHandler.handle(fixture.s, 1864, wire(mode)));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.s.response, "retcode"));
    assertEquals(List.of(), Gameplay46.value(fixture.s.response, "NOEPBNMJFGL"));
    unchanged(saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unpacked-avatar", "packed-avatar", "mixed-empty-packed"})
  void oneFormalAvatarSupportsBothNativeRepeatedEncodings(String mode) throws Exception {
    assertTrue(VersionedPacketHandler.handle(fixture.s, 1864, wire(mode)));
    assertEquals(0, Gameplay46.number(fixture.s.response, "retcode"));
    assertEquals(
        List.of(UniverseDownload46Test.AVATAR),
        Gameplay46.value(fixture.s.response, "NOEPBNMJFGL"));
    assertEquals(
        5000,
        fixture
            .s
            .explorer
            .getGameplay()
            .getRogue()
            .getVitals()
            .get(UniverseDownload46Test.AVATAR)
            .getSp());
  }
}
