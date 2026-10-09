package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Controlled dice/tickets and accepted story targets are fixtures, not a natural client run. */
class ClockParkAllMissionProgress46Test {
  @TempDir Path root;
  final ClockParkMissionProgress46Test first = new ClockParkMissionProgress46Test();
  final ClockParkSecondEnding46Test second = new ClockParkSecondEnding46Test();
  final ClockParkLastEndings46Test last = new ClockParkLastEndings46Test();
  final ClockParkFifthEnding46Test fifth = new ClockParkFifthEnding46Test();
  final Map<Integer, GameItem> owned = new HashMap<>();
  final Map<Integer, Integer> deliveries = new HashMap<>();
  final List<Integer> goals = List.of(802210103, 802210201, 802210205, 802210207, 802210401);
  final List<MissionInfo.SubMissionInfo> targets = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    first.root = root;
    first.prepare();
    second.root = root;
    second.prepare();
    last.root = root;
    last.prepare();
    fifth.root = root;
    fifth.prepare();
    var inventory =
        new Inventory(first.player()) {
          public GameItem getMaterialByItemId(int id) {
            return owned.get(id);
          }

          public GrantResult grantItems(ItemParamMap amounts, boolean hint) {
            var result = new ArrayList<GameItem>();
            for (int id : amounts.keySet()) {
              assertEquals(1, amounts.get(id));
              assertFalse(owned.containsKey(id));
              var item =
                  new GameItem(id, 1) {
                    public void save() {}
                  };
              owned.put(id, item);
              deliveries.merge(id, 1, Integer::sum);
              result.add(item);
            }
            return new GrantResult(result, true);
          }
        };
    for (Player player :
        List.of(
            first.player(),
            second.fixture().opening.player,
            last.fixture().opening.player,
            fifth.fixture().opening.player)) {
      var f = Player.class.getDeclaredField("inventory");
      f.setAccessible(true);
      f.set(player, inventory);
      for (var e : Map.of("planeId", 20321, "floorId", 20321001).entrySet()) {
        f = Player.class.getDeclaredField(e.getKey());
        f.setAccessible(true);
        f.setInt(player, e.getValue());
      }
    }
    var gates = Set.of(802210101, 802210801, 802212004, 802212404, 802210311);
    GameplayDefinitions.subs
        .values()
        .removeIf(
            c ->
                Set.of(8022101, 8022102, 8022104).contains(c.getMainMissionID())
                    && !gates.contains(c.getId()));
    for (int id : goals) {
      var c = first.target(id);
      targets.add(c);
      GameplayDefinitions.subs.put(id, c);
      first.player().getGameplay().getSubMissions().put(id, new QuestState(id));
    }
    arm(first.player());
  }

  @AfterEach
  void restore() throws Exception {
    fifth.restore();
    last.restore();
    second.restore();
    first.restore();
  }

  void arm(Player player) {
    for (int id : List.of(8022101, 8022102, 8022104))
      player.getGameplay().getMainMissions().put(id, new QuestState(id));
  }

  void transfer(Player player, GameplayState data) throws Exception {
    var f = Player.class.getDeclaredField("gameplay");
    f.setAccessible(true);
    f.set(player, data);
  }

  Player play(int script, GameplayState prior) throws Exception {
    Player player =
        script == 1
            ? first.player()
            : script == 2
                ? second.fixture().opening.player
                : script == 5 ? fifth.fixture().opening.player : last.fixture().opening.player;
    transfer(player, prior);
    var policy = ClockParkState.class.getDeclaredField("nextCardPool");
    policy.setAccessible(true);
    policy.set(
        player.getGameplay().getClockPark(),
        script == 1 ? ClockParkCards.POOL_RULE : ClockParkCards.BALANCED_RULE);
    if (script == 1) first.ending.endingStory();
    else if (script == 2) second.path(true);
    else if (script == 5) fifth.path(true);
    else last.path(script, true);
    arm(player); // Each reused activity prerequisite helper closes its parent as a fixture.
    if (script == 1) {
      first.ending.completeStory();
      assertEquals(0, Gameplay46.number(first.ending.completion.response, "retcode"));
    } else
      assertEquals(
          Retcode.SUCC,
          (script == 2 ? second.finish() : script == 5 ? fifth.finish() : last.finish()).code());
    assertNull(player.getGameplay().getClockPark().getActive());
    return player;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void allFiveRealEndingProofsAdvanceEveryThresholdOnceAcrossSavedReloads(boolean reverse)
      throws Exception {
    var order = new ArrayList<>(List.of(1, 2, 3, 4, 5));
    if (reverse) Collections.reverse(order);
    GameplayState data = first.player().getGameplay();
    for (int i = 0; i < order.size(); i++) {
      var player = play(order.get(i), data);
      data = player.getGameplay();
      transfer(first.player(), data);
      assertEquals((i + 1) * 2000, data.getClockPark().progress(ClockParkManager.catalog()));
      for (int k = 0; k < targets.size(); k++) {
        assertEquals(k <= i, first.condition(targets.get(k)));
        var q = data.getSubMissions().get(goals.get(k));
        assertEquals(k <= i ? QuestState.Phase.FINISHED : QuestState.Phase.DOING, q.getPhase());
        assertEquals(k <= i ? 1 : 0, q.getProgress());
        assertEquals(k <= i, q.getFinishTime() > 0);
      }
      assertEquals(i + 4, data.getStoryValues().get("floor:20321001:Crowd"));
      data = first.gson.fromJson(first.gson.toJson(data), GameplayState.class);
      transfer(first.player(), data);
      first.player().getMissionManager().refresh();
      assertEquals((i + 1) * 2000, data.getClockPark().progress(ClockParkManager.catalog()));
    }
    assertEquals(Set.of(140483, 140484, 140485, 140486, 140487, 140488), owned.keySet());
    assertTrue(deliveries.values().stream().allMatch(v -> v == 1));
    var times =
        goals.stream()
            .map(id -> first.player().getGameplay().getSubMissions().get(id).getFinishTime())
            .toList();
    var actions = first.gson.toJson(data.getStoryActions());
    var player = play(order.getLast(), data);
    transfer(first.player(), player.getGameplay());
    assertEquals(10000, player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
    assertEquals(
        times,
        goals.stream()
            .map(id -> player.getGameplay().getSubMissions().get(id).getFinishTime())
            .toList());
    assertEquals(actions, first.gson.toJson(player.getGameplay().getStoryActions()));
    assertTrue(deliveries.values().stream().allMatch(v -> v == 1));
    assertEquals(8, player.getGameplay().getStoryValues().get("floor:20321001:Crowd"));
    var opening = first.ending.fixture().opening;
    opening.handle("ClockParkGetInfoCsReq", new byte[0]);
    assertEquals(0, opening.code());
    assertEquals(10000, Gameplay46.number(opening.session.response, "progress"));
    var finalData = player.getGameplay();
    for (var e : new ArrayList<>(finalData.getRewards().entrySet())) {
      if (!e.getKey().startsWith("clock-park-ending:")) continue;
      finalData.getRewards().remove(e.getKey());
      for (var target : targets) assertFalse(first.condition(target));
      opening.handle("ClockParkGetInfoCsReq", new byte[0]);
      assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), opening.code());
      finalData.getRewards().put(e.getKey(), e.getValue());
      opening.handle("ClockParkGetInfoCsReq", new byte[0]);
      assertEquals(0, opening.code());
    }
    for (int id : new ArrayList<>(owned.keySet())) {
      var item = owned.remove(id);
      for (var target : targets) assertFalse(first.condition(target));
      opening.handle("ClockParkGetInfoCsReq", new byte[0]);
      assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), opening.code());
      owned.put(id, item);
      opening.handle("ClockParkGetInfoCsReq", new byte[0]);
      assertEquals(0, opening.code());
    }
    assertEquals(
        times,
        goals.stream()
            .map(id -> player.getGameplay().getSubMissions().get(id).getFinishTime())
            .toList());
  }
}
