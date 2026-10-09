package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.protocol.Message46;

/** Resource message graphs. A client reports choices, never a trusted completion flag. */
public final class MessageManager {
  private final Player player;

  public MessageManager(Player player) {
    this.player = player;
  }

  public static boolean matches(MissionInfo.SubMissionInfo c) {
    var section = GameplayDefinitions.messageSections.get(c.getParamInt1());
    return section != null
        && "Equal".equals(c.getParamType())
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1().isBlank()
        && c.getParamIntList().length == 0
        && c.getParamItemList().isEmpty()
        && c.getProgress() <= 1
        && (section.IsPerformMessage() ? "MessagePerformSectionFinish" : "MessageSectionFinish")
            .equals(c.getFinishType());
  }

  public static boolean matchesMultiple(MissionInfo.SubMissionInfo c) {
    if (c == null || !"MultiMessagePerformSectionFinish".equals(c.getFinishType())
        || c.getParamIntList() == null || c.getParamStr1() == null
        || c.getParamItemList() == null) return false;
    var ids = Arrays.asList(c.getParamIntList());
    return "MultiMessagePerformSectionFinish".equals(c.getFinishType())
        && "ListContain".equals(c.getParamType())
        && c.getParamInt1() == 0
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1().isBlank()
        && c.getParamItemList().isEmpty()
        && !ids.isEmpty()
        && ids.size() <= 32
        && new HashSet<>(ids).size() == ids.size()
        && Math.max(c.getProgress(), 1) <= ids.size()
        && ids.stream()
            .allMatch(
                id ->
                    id != null
                        && id > 0
                        && GameplayDefinitions.messageSections.containsKey(id)
                        && GameplayDefinitions.messageSections.get(id).IsPerformMessage());
  }

  public int completedCount(MissionInfo.SubMissionInfo c) {
    return matchesMultiple(c)
        ? (int) Arrays.stream(c.getParamIntList()).filter(this::finished).count()
        : 0;
  }

  public GameplayDefinitions.MessageGroup group(int section) {
    var rows =
        GameplayDefinitions.messageGroups.values().stream()
            .filter(
                g ->
                    g.MessageContactsID() > 0
                        && g.MessageSectionIDList() != null
                        && g.MessageSectionIDList().contains(section))
            .toList();
    return rows.size() == 1 ? rows.getFirst() : null;
  }

  private boolean activeMain(int id) {
    var state = player.getGameplay().getMainMissions().get(id);
    return state != null
        && state.getPhase() == QuestState.Phase.DOING
        && !player.getGameplay().getCancelledMissions().contains(id);
  }

  private boolean eligible(GameplayDefinitions.MessageSection s) {
    var group = group(s.ID());
    if (group == null
        || group.ActivityModuleID() != 0
            && GameplayDefinitions.windows.stream()
                .noneMatch(
                    w ->
                        w.moduleId() == group.ActivityModuleID()
                            && w.beginTime() <= java.time.Instant.now().getEpochSecond()
                            && java.time.Instant.now().getEpochSecond() < w.endTime()))
      return false;
    if (s.MainMissionLink() > 0 && activeMain(s.MainMissionLink())) return true;
    return GameplayDefinitions.subs.values().stream()
        .anyMatch(
            c ->
                ((c.getParamInt1() == s.ID() && matches(c))
                        || (matchesMultiple(c)
                            && Arrays.asList(c.getParamIntList()).contains(s.ID())))
                    && activeMain(c.getMainMissionID())
                    && !player.getGameplay().getCancelledSubs().contains(c.getId())
                    && player.getMissionManager().sub(c.getId()) != null
                    && player.getMissionManager().sub(c.getId()).getPhase()
                        == QuestState.Phase.DOING);
  }

  public boolean activateEligible() {
    boolean changed = false;
    for (var section :
        GameplayDefinitions.messageSections.values().stream()
            .sorted(Comparator.comparingInt(GameplayDefinitions.MessageSection::ID))
            .toList()) {
      if (player.getGameplay().getPhoneSections().containsKey(section.ID())
          || !eligible(section)
          || !validGraph(section)) continue;
      var group = group(section.ID());
      if (group.MessageSectionIDList().stream()
          .map(player.getGameplay().getPhoneSections()::get)
          .anyMatch(s -> s != null && !s.isFinished())) continue;
      var state = new GameplayState.PhoneSection(section.ID(), section.StartMessageItemIDList());
      if (!autoAdvance(state)) continue;
      player.getGameplay().getPhoneSections().put(section.ID(), state);
      changed = true;
      Message46.sync(player, group, state);
    }
    return changed;
  }

  public boolean finished(int section) {
    var state = player.getGameplay().getPhoneSections().get(section);
    return state != null && state.isFinished();
  }

  private boolean validGraph(GameplayDefinitions.MessageSection section) {
    if (section.StartMessageItemIDList() == null
        || section.StartMessageItemIDList().isEmpty()
        || section.StartMessageItemIDList().size() > 32
        || new HashSet<>(section.StartMessageItemIDList()).size()
            != section.StartMessageItemIDList().size()) return false;
    var visiting = new HashSet<Integer>();
    var checked = new HashSet<Integer>();
    for (int id : section.StartMessageItemIDList())
      if (!visit(section.ID(), id, visiting, checked)) return false;
    return true;
  }

  private boolean visit(int section, int id, Set<Integer> visiting, Set<Integer> checked) {
    if (checked.contains(id)) return true;
    if (visiting.size() + checked.size() >= 1024 || !visiting.add(id)) return false;
    var row = GameplayDefinitions.messageItems.get(id);
    if (row == null
        || row.SectionID() != section
        || !Set.of("NPC", "Player", "PlayerAuto", "System")
            .contains(Objects.toString(row.Sender(), ""))
        || !Set.of("Text", "Sticker", "Image", "Video")
            .contains(Objects.toString(row.ItemType(), ""))
        || row.NextItemIDList() == null
        || row.NextItemIDList().size() > 32
        || new HashSet<>(row.NextItemIDList()).size() != row.NextItemIDList().size()) return false;
    for (int next : row.NextItemIDList())
      if (!visit(section, next, visiting, checked)) return false;
    visiting.remove(id);
    checked.add(id);
    return true;
  }

  private static boolean requiresCompletionRecord(GameplayDefinitions.MessageItem row) {
    return "Player".equals(row.Sender()) || "Video".equals(row.ItemType());
  }

  private boolean autoAdvance(GameplayState.PhoneSection s) {
    for (int i = 0; i < 1024 && s.getOffered().size() == 1; i++) {
      var row = GameplayDefinitions.messageItems.get(s.getOffered().getFirst());
      if (row == null || row.SectionID() != s.getId() || s.getHistory().contains(row.ID()))
        return false;
      if (requiresCompletionRecord(row)) return true;
      s.step(row.ID(), row.NextItemIDList());
    }
    return s.getHistory().size() <= 1024;
  }

  public Retcode choose(int item, int text) {
    synchronized (player) {
      var row = GameplayDefinitions.messageItems.get(item);
      if (row == null) return Retcode.MESSAGE_CONFIG_NOT_EXIST;
      var config = GameplayDefinitions.messageSections.get(row.SectionID());
      var state = player.getGameplay().getPhoneSections().get(row.SectionID());
      if (config == null || state == null) return Retcode.MESSAGE_SECTION_NOT_TAKE;
      if (config.IsPerformMessage()
          || state.isFinished()
          || !eligible(config)
          || !validGraph(config)
          || text != 0
          || !state.getOffered().contains(item)) return Retcode.MESSAGE_ITEM_CAN_NOT_FINISH;
      var copy = state.copy();
      copy.step(item, row.NextItemIDList());
      if (!autoAdvance(copy)) return Retcode.MESSAGE_ITEM_CAN_NOT_FINISH;
      player.getGameplay().getPhoneSections().put(copy.getId(), copy);
      player.save();
      Message46.sync(player, group(copy.getId()), copy);
      return Retcode.SUCC;
    }
  }

  public Retcode finishSection(int id) {
    synchronized (player) {
      var config = GameplayDefinitions.messageSections.get(id);
      var state = player.getGameplay().getPhoneSections().get(id);
      if (config == null) return Retcode.MESSAGE_CONFIG_NOT_EXIST;
      if (state == null) return Retcode.MESSAGE_SECTION_NOT_TAKE;
      if (state.isFinished()
          || config.IsPerformMessage()
          || !eligible(config)
          || !validGraph(config)
          || state.getHistory().isEmpty()
          || !state.getOffered().isEmpty()) return Retcode.MESSAGE_SECTION_CAN_NOT_FINISH;
      complete(state);
      return Retcode.SUCC;
    }
  }

  public Retcode finishPerformance(int id, List<Integer> choices) {
    return finishPerformances(Map.of(id, choices));
  }

  public Retcode finishPerformances(Map<Integer, List<Integer>> submissions) {
    synchronized (player) {
      if (submissions.isEmpty() || submissions.size() > 32) return Retcode.REQ_PARA_INVALID;
      var prepared = new ArrayList<GameplayState.PhoneSection>();
      for (var entry : submissions.entrySet()) {
        var result = previewPerformance(entry.getKey(), entry.getValue());
        if (result.code() != Retcode.SUCC) return result.code();
        prepared.add(result.state());
      }
      for (var state : prepared) {
        state.finish();
        player.getGameplay().getPhoneSections().put(state.getId(), state);
      }
      player.save();
      prepared.forEach(state -> Message46.sync(player, group(state.getId()), state));
      player.getMissionManager().refresh();
      return Retcode.SUCC;
    }
  }

  private record Preview(Retcode code, GameplayState.PhoneSection state) {}

  private Preview previewPerformance(int id, List<Integer> choices) {
    var config = GameplayDefinitions.messageSections.get(id);
    var state = player.getGameplay().getPhoneSections().get(id);
    if (config == null) return new Preview(Retcode.MESSAGE_CONFIG_NOT_EXIST, null);
    if (state == null) return new Preview(Retcode.MESSAGE_SECTION_NOT_TAKE, null);
    if (state.isFinished()
        || !config.IsPerformMessage()
        || !eligible(config)
        || !validGraph(config)
        || choices.size() > 1024
        || new HashSet<>(choices).size() != choices.size())
      return new Preview(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, null);
    // Reconstruct from the resource start. NPC text streams automatically; video needs a record.
    var copy = new GameplayState.PhoneSection(id, config.StartMessageItemIDList());
    int at = 0;
    while (!copy.getOffered().isEmpty()) {
      int next = at < choices.size() ? choices.get(at) : 0;
      if (copy.getOffered().contains(next)) {
        at++;
      } else if (copy.getOffered().size() == 1
          && !requiresCompletionRecord(
              GameplayDefinitions.messageItems.get(copy.getOffered().getFirst())))
        next = copy.getOffered().getFirst();
      else return new Preview(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, null);
      var row = GameplayDefinitions.messageItems.get(next);
      if (copy.getHistory().contains(next) || copy.getHistory().size() >= 1024)
        return new Preview(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, null);
      copy.step(next, row.NextItemIDList());
    }
    if (at != choices.size() || copy.getHistory().isEmpty())
      return new Preview(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, null);
    return new Preview(Retcode.SUCC, copy);
  }

  private void complete(GameplayState.PhoneSection state) {
    state.finish();
    player.save();
    Message46.sync(player, group(state.getId()), state);
    player.getMissionManager().refresh();
  }
}
