package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A rejected player-document write must leave every phone operation retryable. */
class MessageSaveRetry46Test {
  static final int MAIN = 199937;
  static final List<Integer> IDS = List.of(1999371, 1999372, 1999373, 1999374);
  final Map<Integer, GameplayDefinitions.MessageSection> sections = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageGroup> groups = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageItem> items = new HashMap<>();
  final Gson gson = new Gson();
  Session session;

  class Actor extends Player {
    boolean failSave;
    int saves;
    String durablePhones;

    @Override
    public void save() {
      if (failSave) throw new IllegalStateException("Fixture document write rejected");
      durablePhones = phones();
      saves++;
    }

    String phones() {
      return gson.toJson(new TreeMap<>(getGameplay().getPhoneSections()));
    }

    @Override
    public boolean isOnline() {
      return true;
    }

    @Override
    public GameSession getSession() {
      return session;
    }
  }

  class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();
    int notifications;
    boolean requireSaved = true;

    @Override
    public Player getPlayer() {
      return actor;
    }

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      if (name.equals("PlayerSyncScNotify")) {
        if (requireSaved)
          assertEquals(actor.durablePhones, actor.phones(), "Phone notification preceded its save");
        notifications++;
      }
      super.sendClientPacket(name, bytes);
    }
  }

  @BeforeEach
  void setup() {
    sections.putAll(GameplayDefinitions.messageSections);
    groups.putAll(GameplayDefinitions.messageGroups);
    items.putAll(GameplayDefinitions.messageItems);
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageItems.clear();
    for (int id : IDS) {
      GameplayDefinitions.messageSections.put(
          id, new GameplayDefinitions.MessageSection(id, id >= 1999373, MAIN, List.of(id * 10)));
      GameplayDefinitions.messageGroups.put(
          id, new GameplayDefinitions.MessageGroup(id, MAIN, 0, List.of(id)));
      GameplayDefinitions.messageItems.put(
          id * 10,
          new GameplayDefinitions.MessageItem(
              id * 10, id, "NPC", "Text", List.of(id * 10 + 1, id * 10 + 2)));
      for (int choice : List.of(1, 2)) {
        GameplayDefinitions.messageItems.put(
            id * 10 + choice,
            new GameplayDefinitions.MessageItem(
                id * 10 + choice, id, "Player", "Text", List.of(id * 10 + 3)));
      }
      GameplayDefinitions.messageItems.put(
          id * 10 + 3,
          new GameplayDefinitions.MessageItem(id * 10 + 3, id, "NPC", "Text", List.of()));
    }
    session = new Session();
    session.actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    session.actor.save();
  }

  @AfterEach
  void restore() {
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageSections.putAll(sections);
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageGroups.putAll(groups);
    GameplayDefinitions.messageItems.clear();
    GameplayDefinitions.messageItems.putAll(items);
  }

  MessageManager open() {
    var manager = new MessageManager(session.actor);
    session.requireSaved = false;
    assertTrue(manager.activateEligible());
    // The old implementation relies on the enclosing mission refresh to save activation.
    session.actor.save();
    session.requireSaved = true;
    session.notifications = 0;
    return manager;
  }

  void rejectedWriteLeavesTheSameObjects(Runnable action) {
    var before = new HashMap<>(session.actor.getGameplay().getPhoneSections());
    String document = session.actor.phones();
    int saves = session.actor.saves;
    session.actor.failSave = true;
    assertThrows(IllegalStateException.class, action::run);
    assertEquals(document, session.actor.phones());
    assertEquals(document, session.actor.durablePhones);
    assertEquals(saves, session.actor.saves);
    assertEquals(0, session.notifications);
    before.forEach(
        (id, state) -> assertSame(state, session.actor.getGameplay().getPhoneSections().get(id)));
    session.actor.failSave = false;
  }

  @Test
  void failedActivationPublishesNothingAndCanRetryAllSections() {
    var manager = new MessageManager(session.actor);
    rejectedWriteLeavesTheSameObjects(manager::activateEligible);
    assertTrue(manager.activateEligible());
    assertEquals(Set.copyOf(IDS), session.actor.getGameplay().getPhoneSections().keySet());
    assertEquals(4, session.notifications);
    assertFalse(manager.activateEligible());
    assertEquals(4, session.notifications);
  }

  @ParameterizedTest
  @ValueSource(ints = {1999371, 1999372})
  void failedReplyKeepsTheOfferedChoicesAndTheExactRequestCanRetry(int id) {
    var manager = open();
    rejectedWriteLeavesTheSameObjects(() -> manager.choose(id * 10 + 2, 0));
    assertEquals(Retcode.SUCC, manager.choose(id * 10 + 2, 0));
    assertEquals(
        List.of(id * 10, id * 10 + 2, id * 10 + 3),
        session.actor.getGameplay().getPhoneSections().get(id).getHistory());
    assertEquals(1, session.notifications);
    assertEquals(Retcode.MESSAGE_ITEM_CAN_NOT_FINISH, manager.choose(id * 10 + 2, 0));
  }

  @ParameterizedTest
  @ValueSource(ints = {1999371, 1999372})
  void failedSectionCompletionRemainsUnfinishedUntilItsRetryIsSaved(int id) {
    var manager = open();
    assertEquals(Retcode.SUCC, manager.choose(id * 10 + 1, 0));
    session.notifications = 0;
    rejectedWriteLeavesTheSameObjects(() -> manager.finishSection(id));
    assertEquals(Retcode.SUCC, manager.finishSection(id));
    assertTrue(session.actor.getGameplay().getPhoneSections().get(id).isFinished());
    assertEquals(1, session.notifications);
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, manager.finishSection(id));
  }

  @Test
  void failedPerformanceCompletionDoesNotConsumeItsSourceOrderedReport() {
    var manager = open();
    rejectedWriteLeavesTheSameObjects(() -> manager.finishPerformance(1999373, List.of(19993732)));
    assertEquals(Retcode.SUCC, manager.finishPerformance(1999373, List.of(19993732)));
    assertEquals(1, session.notifications);
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,
        manager.finishPerformance(1999373, List.of(19993732)));
  }

  @Test
  void failedBatchRestoresBothSectionsAndSavesTheirSuccessfulRetryTogether() {
    var manager = open();
    var submission = Map.of(1999373, List.of(19993731), 1999374, List.of(19993742));
    rejectedWriteLeavesTheSameObjects(() -> manager.finishPerformances(submission));
    int saves = session.actor.saves;
    assertEquals(Retcode.SUCC, manager.finishPerformances(submission));
    assertEquals(saves + 1, session.actor.saves);
    assertEquals(2, session.notifications);
    assertTrue(session.actor.getGameplay().getPhoneSections().get(1999373).isFinished());
    assertTrue(session.actor.getGameplay().getPhoneSections().get(1999374).isFinished());
  }
}
