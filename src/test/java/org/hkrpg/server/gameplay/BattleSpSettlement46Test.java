package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import dev.morphia.Morphia;
import dev.morphia.mapping.MapperOptions;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.database.codecs.*;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.*;
import emu.lunarcore.proto.*;
import emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.codecs.configuration.CodecRegistries;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original avatar; isolated battle/branch parties and constructed result reports. */
class BattleSpSettlement46Test {
  static final int AVATAR = 1202, STAGE = 19998701;
  final Actor owner = new Actor();
  final Service service = new Service();
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
  GameAvatar avatar;
  AvatarExcel previousAvatar;

  static class Actor extends Player {
    final Map<Integer, GameAvatar> actors = new HashMap<>();
    final LineupManager manager = new LineupManager(this);
    final PlayerLineup normal = new PlayerLineup(this, 0);
    final PlayerExtraLineup extra = new PlayerExtraLineup(this, 2);
    final List<BasePacket> sent = new ArrayList<>();
    PlayerLineup selected = normal;
    int battleIds;

    @Override
    public LineupManager getLineupManager() {
      return manager;
    }

    @Override
    public PlayerLineup getCurrentLineup() {
      return selected;
    }

    @Override
    public GameAvatar getAvatarById(int id) {
      return actors.get(id);
    }

    @Override
    public int getNextBattleId() {
      return ++battleIds;
    }

    @Override
    public void save() {}

    @Override
    public boolean removeFoodBuffs(int amount) {
      return false;
    }

    @Override
    public void sendPacket(BasePacket packet) {
      sent.add(packet);
    }
  }

  static class Service extends BattleService {
    Service() {
      super(null);
    }

    @Override
    protected void grantDrops(Battle battle) {}
  }

  @BeforeEach
  void setup() throws Exception {
    previousAvatar = GameData.getAvatarExcelMap().get(AVATAR);
    owner.resetPosition();
    try (var in = getClass().getResourceAsStream("/fixtures/avatar-skill-parameters-4.6.json")) {
      assertNotNull(in);
      var records =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
      for (var record : records) {
        var row = record.getAsJsonObject().getAsJsonObject("row");
        if (!row.has("AvatarID") || row.get("AvatarID").getAsInt() != AVATAR) continue;
        var excel = ResourceLoader.getGson().fromJson(row, AvatarExcel.class);
        assertEquals(130, excel.getBaseSpCapacity().orElseThrow());
        avatar =
            new GameAvatar(excel) {
              @Override
              public void save() {}
            };
      }
    }
    assertNotNull(avatar);
    GameData.getAvatarExcelMap().add(avatar.getExcel());
    avatar.setOwner(owner);
    owner.actors.put(AVATAR, avatar);
    owner.normal.getAvatars().add(AVATAR);
    owner.extra.getAvatars().add(AVATAR);
    avatar.setCurrentHp(owner.normal, 8765);
    avatar.setCurrentSp(owner.normal, 1111);
    avatar.setCurrentHp(owner.extra, 7654);
    avatar.setCurrentSp(owner.extra, 2222);
  }

  @AfterEach
  void restoreSourceActor() {
    GameData.getAvatarExcelMap().values().removeIf(row -> row.getId() == AVATAR);
    if (previousAvatar != null) GameData.getAvatarExcelMap().add(previousAvatar);
  }

  Battle battle() {
    BattleStage stage =
        new BattleStage() {
          @Override
          public int getId() {
            return STAGE;
          }

          @Override
          public StageType getStageType() {
            return StageType.Trial;
          }

          @Override
          public List<IntList> getMonsterWaves() {
            return List.of();
          }
        };
    var battle = new Battle(owner, owner.selected, stage);
    owner.setBattle(battle);
    return battle;
  }

  BattleStatistics statistics(double left, double maximum) {
    var status =
        AvatarProperty.newInstance()
            .setMaxHp(1000)
            .setLeftHp(500)
            .setMaxSp(maximum)
            .setLeftSp(left);
    return BattleStatistics.newInstance()
        .addBattleAvatarList(
            AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
                .setId(AVATAR)
                .setAvatarStatus(status));
  }

  void win(double left, double maximum) {
    var battle = battle();
    assertSame(
        battle,
        service.finishBattle(
            owner,
            battle.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            statistics(left, maximum)));
    assertNull(owner.getBattle());
  }

  @ParameterizedTest
  @CsvSource({
    "50,130,3846",
    "65,130,5000",
    "130,130,10000",
    "22.5,90,2500",
    "33.333,100,3333",
    "112.5,150,7500",
    "0.5,1,5000",
    "0.0000001,0.0000002,5000",
    "0,130,0",
    "0,0,0",
    "1,3,3333",
    "2,3,6667",
    "130,260,5000"
  })
  void accepted_result_keeps_fractional_energy_and_uses_the_reported_maximum(
      double left, double maximum, int expected) {
    win(left, maximum);
    assertEquals(expected, avatar.getCurrentSp(owner.normal));
    assertEquals(5000, avatar.getCurrentHp(owner.normal));
    assertEquals(2222, avatar.getCurrentSp(owner.extra));
    assertEquals(7654, avatar.getCurrentHp(owner.extra));
  }

  @Test
  void finite_large_values_are_divided_before_integer_conversion() {
    win(Double.MAX_VALUE / 2, Double.MAX_VALUE);
    assertEquals(5000, avatar.getCurrentSp(owner.normal));
  }

  @ParameterizedTest
  @ValueSource(strings = {"extra", "story", "rogue"})
  void settlement_writes_only_the_active_branch_party(String scope) {
    if (scope.equals("extra")) owner.selected = owner.extra;
    else if (scope.equals("story")) {
      var place = new StoryLineState.Place(1, 1, 1, 0, new Position(), new Position());
      var run =
          new StoryLineState.Run(
              1,
              List.of(AVATAR),
              0,
              Map.of(
                  AVATAR,
                  "{\"SpecialAvatarID\":1202,\"AvatarID\":1202,\"Level\":40,\"Promotion\":2}"),
              place);
      owner.getGameplay().getStoryLines().getRuns().put(1, run);
      owner.getGameplay().getStoryLines().begin(1, place, place);
      owner.getGameplay().getStoryLines().arrived();
      owner.selected = new StoryLineup(owner, run);
    } else {
      var run = owner.getGameplay().getRogue();
      run.getRoster().add(AVATAR);
      run.getVitals().put(AVATAR, new GameplayState.RogueVitals(6543, 3333));
      owner.selected = new RogueLineup(owner, run);
    }
    win(50, 130);
    assertEquals(3846, avatar.getCurrentSp(owner.selected));
    assertEquals(5000, avatar.getCurrentHp(owner.selected));
    assertEquals(1111, avatar.getCurrentSp(owner.normal));
    assertEquals(8765, avatar.getCurrentHp(owner.normal));
    if (!scope.equals("extra")) assertEquals(2222, avatar.getCurrentSp(owner.extra));
  }

  @Test
  void loss_keeps_the_energy_ratio_and_the_existing_minimum_health_rule() {
    var battle = battle();
    var stats = statistics(50, 130);
    stats.getBattleAvatarList().get(0).getMutableAvatarStatus().setLeftHp(0);
    assertSame(
        battle,
        service.finishBattle(owner, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_LOSE, stats));
    assertEquals(3846, avatar.getCurrentSp(owner.normal));
    assertEquals(2000, avatar.getCurrentHp(owner.normal));
  }

  @Test
  void quit_does_not_write_reported_energy_and_cannot_be_replayed() {
    var battle = battle();
    var stats = statistics(50, 130);
    assertSame(
        battle,
        service.finishBattle(owner, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_QUIT, stats));
    assertEquals(1111, avatar.getCurrentSp(owner.normal));
    assertEquals(8765, avatar.getCurrentHp(owner.normal));
    assertNull(
        service.finishBattle(owner, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, stats));
    assertEquals(1111, avatar.getCurrentSp(owner.normal));
  }

  @ParameterizedTest
  @CsvSource({"-1,130", "131,130", "1,0", "1,-1", "NaN,130", "1,Infinity"})
  void invalid_energy_reports_do_not_consume_the_battle_or_change_any_party(
      double left, double maximum) {
    var battle = battle();
    assertNull(
        service.finishBattle(
            owner,
            battle.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            statistics(left, maximum)));
    assertSame(battle, owner.getBattle());
    assertEquals(1111, avatar.getCurrentSp(owner.normal));
    assertEquals(2222, avatar.getCurrentSp(owner.extra));
    assertTrue(owner.sent.isEmpty());
    assertSame(
        battle,
        service.finishBattle(
            owner, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, statistics(65, 130)));
    assertEquals(5000, avatar.getCurrentSp(owner.normal));
  }

  static Object field(DynamicMessage message, String name) {
    return message.getField(
        Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name)));
  }

  @Test
  void four_six_property_translation_and_outbound_bar_preserve_the_ratio() throws Exception {
    var property = bridge.clientSchema().message("AvatarProperty");
    var input =
        DynamicMessage.newBuilder(property)
            .setField(property.findFieldByName("max_hp"), 1000d)
            .setField(property.findFieldByName("left_hp"), 500d)
            .setField(property.findFieldByName("max_sp"), 130d)
            .setField(property.findFieldByName("left_sp"), 50d)
            .build();
    var converted =
        AvatarProperty.parseFrom(bridge.translate("AvatarProperty", input.toByteArray(), false));
    var battle = battle();
    var stats =
        BattleStatistics.newInstance()
            .addBattleAvatarList(
                AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
                    .setId(AVATAR)
                    .setAvatarStatus(converted));
    assertSame(
        battle,
        service.finishBattle(owner, battle.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, stats));
    var packet =
        owner.sent.stream()
            .filter(p -> p.getCmdId() == CmdId.SyncLineupNotify)
            .findFirst()
            .orElseThrow();
    var wire = bridge.outbound(packet.getCmdId(), packet.getData().toByteArray());
    var nativePacket = bridge.clientSchema().parse("SyncLineupNotify", wire.body());
    var lineup = (DynamicMessage) field(nativePacket, "lineup");
    var rows = (List<?>) field(lineup, "avatar_list");
    var row = (DynamicMessage) rows.getFirst();
    assertEquals(AVATAR, field(row, "id"));
    var bar = (DynamicMessage) field(row, "sp_bar");
    assertEquals(3846, field(bar, "cur_sp"));
    assertEquals(10000, field(bar, "max_sp"));
  }

  @Test
  void the_actual_avatar_codec_reloads_both_normal_and_extra_energy_after_settlement()
      throws Exception {
    win(50, 130);
    owner.selected = owner.extra;
    win(22.5, 90);
    var server = new MongoServer(new MemoryBackend());
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var options =
          MapperOptions.builder()
              .storeEmpties(true)
              .storeNulls(false)
              .codecProvider(
                  CodecRegistries.fromCodecs(
                      new IntSetCodec(), new IntListCodec(), new Int2IntMapCodec()))
              .build();
      var store = Morphia.createDatastore(client, "battle-energy-settlement", options);
      store.getMapper().map(GameAvatar.class, AvatarData.class);
      store.save(avatar);
      var restored = store.find(GameAvatar.class).first();
      assertNotNull(restored);
      assertEquals(3846, restored.getCurrentSp(owner.normal));
      assertEquals(2500, restored.getCurrentSp(owner.extra));
      assertEquals(5000, restored.getCurrentHp(owner.normal));
      assertEquals(5000, restored.getCurrentHp(owner.extra));
    } finally {
      server.shutdownNow();
    }
  }
}
