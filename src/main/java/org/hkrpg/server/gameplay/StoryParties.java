package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.server.packet.send.PacketSyncLineupNotify;
import java.util.*;
import org.bson.types.ObjectId;

/** Earned formal characters and once-only story team changes. Trials keep their own lifecycle. */
public final class StoryParties {
  private static final Gson GSON = new Gson();
  private static final JsonElement WAKE_TASK = JsonParser.parseString("""
      {"OnInitSequece":[],"OnStartSequece":[{"Order":1,"TaskList":[
      {"$type":"RPG.GameCore.FinishPerformanceMission","Key":"Mission_100020103"}]}],"Type":"Mission"}
      """);
  private final Player player;

  public StoryParties(Player player) { this.player = player; }

  /** Missing original server grant: explicitly bounded compatibility rule, not a decoded action. */
  static String wakingSource() {
    var main = GameData.getMissionInfos().get(1000101);
    var sub = GameplayDefinitions.subs.get(100020103);
    if (main == null || !Arrays.equals(main.getFinishSubMissionList(), new int[] {100020103})
        || sub == null || sub.getMainMissionID() != 1000101
        || sub.getLevelPlaneID() != 20002 || sub.getLevelFloorID() != 20002001
        || !"AnySequence".equals(sub.getTakeType())
        || !Arrays.equals(sub.getTakeParamIntList(), new Integer[] {100020102})
        || !"Talk".equals(sub.getFinishType()) || !"Equal".equals(sub.getParamType())
        || !"Mission_100020103".equals(sub.getParamStr1()) || sub.getProgress() != 1
        || sub.getFinishActionList().length != 0 || sub.getTask() == null
        || sub.getTask().getSourceDefinition() == null
        || !WAKE_TASK.equals(JsonParser.parseString(sub.getTask().getSourceDefinition()))) return null;
    return "wake-companion-v1:" + GSON.toJson(sub);
  }

  public void reconcile() {
    synchronized (player) {
      if (player.getGameplay().getStoryParty() == null) return;
      String wake = wakingSource();
      if (wake != null && player.hasCreatedCharacter()
          && player.getAvatars().getAvatars().containsKey(8001)
          && MissionStateRecords.subCompleted(player, 100020103)
          && grantAvatar("wake:100020103", wake, 1001)) {
        joinOnce("wake:100020103", wake, 1001);
      }
      var main = GameData.getMainMissionExcelMap().get(1000201);
      var info = GameData.getMissionInfos().get(1000201);
      var plan = player.getGameplay().getMissionRewardPlans().get("mission:1000201");
      if (main != null && main.getRewardID() == 11000201 && info != null
          && Arrays.equals(info.getFinishSubMissionList(), new int[] {100020109})
          && MissionStateRecords.mainCompleted(player, 1000201)
          && MissionStateRecords.subCompleted(player, 100020109)
          && plan != null && plan.isReady() && Integer.valueOf(1).equals(plan.getItems().get(1002))
          && player.getRewardLedger().granted("mission:1000201")
          && player.getAvatars().getAvatars().containsKey(1002)) {
        joinOnce("mission:1000201", "reward-companion-v1:" + GSON.toJson(plan), 1002);
      }
    }
  }

  boolean grantAvatar(String key, String source, int id) {
    var state = player.getGameplay().getStoryParty();
    if (state == null || source == null || GameData.getAvatarExcelMap().get(id) == null) return false;
    var path = GameData.getMultiplePathAvatarExcelMap().get(id);
    if (path != null && !path.isDefault()) return false;
    var grant = state.getGrants().get(key);
    if (grant == null) {
      grant = new StoryPartyState.Grant(source, id);
      state.getGrants().put(key, grant);
      try { player.save(); }
      catch (RuntimeException error) { state.getGrants().remove(key); throw error; }
    }
    if (!source.equals(grant.getSource()) || grant.getAvatar() != id
        || !ObjectId.isValid(grant.getObjectId())) return false;
    if (grant.isComplete()) return player.getAvatars().getAvatars().containsKey(id);
    if (!player.getAvatars().ensureStoryAvatar(id, new ObjectId(grant.getObjectId()))) return false;
    grant.complete(true);
    try { player.save(); }
    catch (RuntimeException error) { grant.complete(false); throw error; }
    return true;
  }

  /** Do not edit a temporary party, a battle roster, or an unrelated saved normal team. */
  static PlayerLineup normalTeam(Player player) {
    if (player.getLineupManager() == null || player.isInBattle() || player.getInstance() != null
        || player.getGameplay().getCurrentMissionRaid() != 0) return null;
    var team = player.getCurrentLineup();
    int index = player.getLineupManager().getCurrentIndex();
    var teams = player.getLineupManager().getLineups();
    return team != null && !team.isExtraLineup() && index >= 0 && index < teams.length
        && teams[index] == team ? team : null;
  }

  private void joinOnce(String key, String source, int id) {
    var team = normalTeam(player);
    if (team == null) return;
    var existing = player.getGameplay().getStoryParty().getChanges().get(key);
    if (existing != null) {
      if (source.equals(existing.getSource())) apply(existing, team);
      return;
    }
    var ids = new ArrayList<>(team.getAvatars());
    // A full manually composed party stays full; obtaining a character must not evict anyone.
    if (!ids.contains(id) && ids.size() < GameConstants.MAX_AVATARS_IN_TEAM) ids.add(id);
    change(key, source, team, ids, team.getLeader());
  }

  static String actionSource(MissionInfo.SubMissionInfo sub, int index) {
    return "lineup-action-v1:" + index + ":" + GSON.toJson(sub);
  }

  boolean changeAction(MissionInfo.SubMissionInfo sub, int index, int[] parameters) {
    var team = normalTeam(player);
    if (team == null) return false;
    var ids = Arrays.stream(parameters).limit(4).filter(id -> id != 0).boxed().toList();
    return change(DailyMissionRecoverDeliveryRules.actionKey(player, sub, index),
        actionSource(sub, index), team, ids, ids.indexOf(parameters[4]));
  }

  private boolean change(String key, String source, PlayerLineup team, List<Integer> ids, int leader) {
    var state = player.getGameplay().getStoryParty();
    if (state == null || !valid(ids, leader)) return false;
    var change = state.getChanges().get(key);
    if (change == null) {
      if (!valid(team.getAvatars(), team.getLeader())) return false;
      change = new StoryPartyState.Change(source, team.getIndex(), team.getAvatars(), team.getLeader(), ids, leader);
      state.getChanges().put(key, change);
      try { player.save(); }
      catch (RuntimeException error) { state.getChanges().remove(key); throw error; }
    }
    return source.equals(change.getSource()) && change.getAfter().equals(ids)
        && change.getAfterLeader() == leader && apply(change, team);
  }

  private boolean valid(List<Integer> ids, int leader) {
    return ids != null && !ids.isEmpty() && ids.size() <= GameConstants.MAX_AVATARS_IN_TEAM
        && leader >= 0 && leader < ids.size() && new HashSet<>(ids).size() == ids.size()
        && ids.stream().allMatch(id -> id != null && player.getAvatarById(id) != null
            && player.getAvatarById(id).getAvatarId() == id
            && player.getAvatarById(id).getAvatarType()
                == emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType.AVATAR_FORMAL_TYPE);
  }

  private boolean apply(StoryPartyState.Change change, PlayerLineup team) {
    if (change.isComplete()) return true; // Never undo later player edits or story team changes.
    if (change.getIndex() != team.getIndex() || !valid(change.getAfter(), change.getAfterLeader())
        || !valid(change.getBefore(), change.getBeforeLeader())) return false;
    boolean atTarget = team.getAvatars().equals(change.getAfter()) && team.getLeader() == change.getAfterLeader();
    if (!atTarget && !(team.getAvatars().equals(change.getBefore()) && team.getLeader() == change.getBeforeLeader())) return false;
    if (!atTarget) {
      set(team, change.getAfter(), change.getAfterLeader());
      try { team.save(); }
      catch (RuntimeException error) {
        set(team, change.getBefore(), change.getBeforeLeader());
        throw error;
      }
    }
    change.complete(true);
    try { player.save(); }
    catch (RuntimeException error) { change.complete(false); throw error; }
    if (player.getScene() != null) player.getScene().syncLineup();
    player.sendPacket(new PacketSyncLineupNotify(team));
    return true;
  }

  private static void set(PlayerLineup team, List<Integer> ids, int leader) {
    team.getAvatars().clear(); team.getAvatars().addAll(ids); team.setLeader(leader);
  }
}
