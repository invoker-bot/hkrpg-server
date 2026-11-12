package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoryActionSaveFailureTest {
  static final int MAIN = 1988692, SUB = 198869201, ITEM = 198869299;
  static final String COUNT = "mission:" + MAIN + ":count";
  final Gson gson = new Gson();
  ItemExcel oldItem;

  static class Actor extends Player {
    int saves, failAt, attempts, delivered;
    boolean throwDelivery;
    GameplayState durable;
    final Inventory inventory = new Inventory(this) {
      @Override public GrantResult grantItems(ItemParamMap items, boolean hint) {
        attempts++;
        delivered += items.get(ITEM);
        if (throwDelivery) throw new IllegalStateException("Fixture delivery interrupted");
        return new GrantResult(List.of(new GameItem(ITEM, items.get(ITEM))), true);
      }
    };

    @Override public Inventory getInventory() { return inventory; }
    @Override public void save() {
      if (++saves == failAt) throw new IllegalStateException("Fixture action write rejected");
      var gson = new Gson();
      durable = gson.fromJson(gson.toJson(getGameplay()), GameplayState.class);
    }
  }

  @BeforeEach void setup() {
    oldItem = GameData.getItemExcelMap().get(ITEM);
    GameData.getItemExcelMap().add(gson.fromJson(
        "{\"ID\":" + ITEM + ",\"ItemMainType\":\"Material\",\"PileLimit\":99}", ItemExcel.class));
  }

  @AfterEach void restore() {
    GameData.getItemExcelMap().keySet().remove(ITEM);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
  }

  MissionInfo.SubMissionInfo sub(String actions) {
    return gson.fromJson("{\"ID\":" + SUB + ",\"MainMissionID\":" + MAIN
        + ",\"FinishActionList\":[" + actions + "]}", MissionInfo.SubMissionInfo.class);
  }

  String add(int count) {
    return "{\"FinishActionType\":\"AddCustomValue\",\"FinishActionParaString\":[\"count\",\""
        + count + "\"]}";
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void rejectedFirstReservationDoesNotStrandTheRequestBeforeAnyEffect(boolean items) {
    var actor = new Actor();
    var sub = sub(items
        ? "{\"FinishActionType\":\"addMissionItem\",\"FinishActionPara\":[" + ITEM + ",3]}"
        : add(3));
    actor.failAt = 1;
    assertThrows(IllegalStateException.class, () -> new StoryActions(actor).execute(sub));
    assertFalse(actor.getGameplay().getStoryActions().containsKey(SUB));
    assertFalse(actor.getGameplay().getStoryValues().containsKey(COUNT));
    assertEquals(0, actor.attempts);
    actor.failAt = 0;
    assertEquals(Retcode.SUCC, new StoryActions(actor).execute(sub));
    assertEquals(Retcode.SUCC, new StoryActions(actor).execute(sub));
    assertEquals(items ? 1 : 0, actor.attempts);
    assertEquals(3, items ? actor.delivered : actor.getGameplay().getStoryValues().get(COUNT));
    assertEquals(1, actor.getGameplay().getStoryActions().get(SUB).getNext());
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void failedLaterReservationPreservesTheSameSavedPrefixAndNeverReplaysIt(boolean restoredPrefix) {
    var actor = new Actor();
    var sub = sub(add(3) + "," + add(7));
    GameplayState.ActionCursor original;
    if (restoredPrefix) {
      original = new GameplayState.ActionCursor();
      original.reserve();
      original.complete();
      actor.getGameplay().getStoryActions().put(SUB, original);
      actor.getGameplay().getStoryValues().put(COUNT, 3);
      actor.save();
      actor.failAt = actor.saves + 1;
    } else {
      // The cursor is first created by this request, but its first action is already saved.
      actor.failAt = 3;
      original = null;
    }
    assertThrows(IllegalStateException.class, () -> new StoryActions(actor).execute(sub));
    var cursor = actor.getGameplay().getStoryActions().get(SUB);
    if (original != null) assertSame(original, cursor);
    assertEquals(1, cursor.getNext());
    assertFalse(cursor.isPending());
    assertEquals(3, actor.getGameplay().getStoryValues().get(COUNT));
    assertEquals(1, actor.durable.getStoryActions().get(SUB).getNext());
    actor.failAt = 0;
    assertEquals(Retcode.SUCC, new StoryActions(actor).execute(sub));
    assertSame(cursor, actor.getGameplay().getStoryActions().get(SUB));
    assertEquals(10, actor.getGameplay().getStoryValues().get(COUNT));
    assertEquals(2, cursor.getNext());
  }

  @Test void anExceptionAfterActualDeliveryKeepsThePersistedReservation() {
    var actor = new Actor();
    actor.throwDelivery = true;
    var sub = sub("{\"FinishActionType\":\"addMissionItem\",\"FinishActionPara\":[" + ITEM + ",3]}");
    assertThrows(IllegalStateException.class, () -> new StoryActions(actor).execute(sub));
    assertTrue(actor.getGameplay().getStoryActions().get(SUB).isPending());
    assertTrue(actor.durable.getStoryActions().get(SUB).isPending());
    assertEquals(Retcode.SYSTEM_BUSY, new StoryActions(actor).execute(sub));
    assertEquals(1, actor.attempts);
    assertEquals(3, actor.delivered);
  }

  @Test void appliedActionDoesNotReplayAndAnOlderDurablePendingCursorCannotAutoResume() throws Exception {
    var actor = new Actor();
    actor.failAt = 2;
    var sub = sub(add(3));
    assertThrows(IllegalStateException.class, () -> new StoryActions(actor).execute(sub));
    assertEquals(3, actor.getGameplay().getStoryValues().get(COUNT));
    assertEquals(1, actor.getGameplay().getStoryActions().get(SUB).getNext());
    assertEquals(Retcode.SUCC, new StoryActions(actor).execute(sub));
    assertEquals(3, actor.getGameplay().getStoryValues().get(COUNT));
    var restored = new Actor();
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(restored, actor.durable);
    assertEquals(Retcode.SYSTEM_BUSY, new StoryActions(restored).execute(sub));
    assertEquals(0, restored.saves);
    assertFalse(restored.getGameplay().getStoryValues().containsKey(COUNT));
  }
}
