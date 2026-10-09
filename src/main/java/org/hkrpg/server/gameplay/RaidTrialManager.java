package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import emu.lunarcore.GameConstants;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import java.util.*;

/** Publishes an exact resource-declared trial party only inside its owning raid. */
public final class RaidTrialManager {
  public record Prepared(RaidTrialParty party, Map<Integer, TrialAvatar> actors) {}

  private static final Gson GSON = new Gson();
  private final Player player;
  private GameplayState.MissionRaidRun cachedRun;
  private RaidTrialParty cachedParty;
  private RaidTrialLineup cachedLineup;
  private Map<Integer, TrialAvatar> actors = Map.of();
  private Map<Integer, GameAvatar> formals = Map.of();
  private String frozen;

  public RaidTrialManager(Player player) {
    this.player = player;
  }

  private static boolean supports(RaidTeams.Plan plan) {
    return plan != null
        && ("TrialOrPlayer".equals(plan.type())
            || "TrialOnly".equals(plan.type())
            || "Player".equals(plan.type()) && RaidTeams.frozenParty(plan));
  }

  private GameplayState.MissionRaidRun current() {
    return new MissionRaid(player).current();
  }

  public boolean inside() {
    var run = current();
    return run != null && run.getTrialParty() != null;
  }

  public List<RaidTeams.Selection> defaults(RaidTeams.Plan plan) {
    if (plan == null || !"TrialOnly".equals(plan.type()))
      throw new IllegalArgumentException("No automatic raid trial party");
    var result = new ArrayList<RaidTeams.Selection>();
    for (int id : plan.trials()) {
      var row = player.getTrialManager().specialRow(id);
      if (row == null) throw new IllegalArgumentException("Missing declared raid trial");
      if (player.getTrialManager().matchesGender(row.getAvatarID()))
        result.add(new RaidTeams.Selection(id, true));
    }
    if (result.isEmpty() || result.size() > 4)
      throw new IllegalArgumentException("Invalid automatic raid trial party");
    return List.copyOf(result);
  }

  private static int captainSlot(int captain, List<GameAvatar> members) {
    int selected = -1;
    for (int i = 0; i < members.size(); i++) {
      int actor = members.get(i).getActorId();
      var path = GameData.getMultiplePathAvatarExcelMap().get(actor);
      if (actor == captain || path != null && path.getBaseAvatarID() == captain) {
        if (selected != -1) throw new IllegalArgumentException("Ambiguous raid captain");
        selected = i;
      }
    }
    if (selected == -1) throw new IllegalArgumentException("Missing raid captain");
    return selected;
  }

  private static int roleIdentity(GameAvatar member) {
    int role = RaidTeams.roleIdentity(member);
    if (role <= 0) throw new IllegalArgumentException("Missing raid base role identity");
    return role;
  }

  public boolean leaderAllowed(int slot) {
    var run = current();
    return run != null
        && run.getFormalTeamPlan() != null
        && (run.getFormalTeamPlan().getCaptain() == 0 || slot == run.getTrialParty().getLeader());
  }

  public Prepared prepare(RaidTeams.Plan plan, List<RaidTeams.Selection> selected) {
    if (!supports(plan)
        || selected.isEmpty()
        || selected.size() > 4
        || "TrialOnly".equals(plan.type()) && !selected.equals(defaults(plan)))
      throw new IllegalArgumentException("Unsupported raid trial choice");
    var prepared = new LinkedHashMap<Integer, TrialAvatar>();
    var selectedActors = new ArrayList<GameAvatar>();
    var ids = new HashSet<Integer>();
    var actorIds = new HashSet<Integer>();
    for (var choice : selected) {
      if (choice == null || choice.id() <= 0 || !ids.add(choice.id()))
        throw new IllegalArgumentException("Invalid raid trial choice");
      if (!choice.trial()) {
        var formal = formal(choice.id());
        if (!actorIds.add(roleIdentity(formal)))
          throw new IllegalArgumentException("Duplicate raid actor");
        selectedActors.add(formal);
        continue;
      }
      if (!plan.trials().contains(choice.id()) || player.getAvatars().hasAvatar(choice.id()))
        throw new IllegalArgumentException("Invalid raid trial choice");
      var actor = player.getTrialManager().buildTrialAvatar(choice.id());
      if (actor == null
          || actor.getAvatarId() != choice.id()
          || !validProgression(actor, player.getWorldLevel())
          || !validEquipment(actor)
          || !actorIds.add(roleIdentity(actor)))
        throw new IllegalArgumentException("Unavailable raid trial actor");
      prepared.put(choice.id(), actor);
      selectedActors.add(actor);
    }
    if (prepared.isEmpty() && !RaidTeams.frozenParty(plan)
        || !RaidTeams.membersAllowed(plan, selectedActors))
      throw new IllegalArgumentException("Missing raid trial role");
    int mp = Math.clamp(player.getCurrentLineup().getMp(), 0, maximum(selectedActors));
    var party =
        new RaidTrialParty(player.getWorldLevel(), mp, selectedActors, player.getCurrentLineup());
    if (plan.captain() > 0) party.leader(captainSlot(plan.captain(), selectedActors));
    return new Prepared(party, Map.copyOf(prepared));
  }

  private static boolean validEquipment(TrialAvatar actor) {
    var row = actor.getSpecialAvatarExcel();
    if (row.getEquipmentID() == 0)
      return row.getEquipmentLevel() == 0
          && row.getEquipmentPromotion() == 0
          && row.getEquipmentRank() == 0;
    var gear = GameData.getItemExcelMap().get(row.getEquipmentID());
    var promotion =
        GameData.getEquipmentPromotionExcelMap()
            .get(row.getEquipmentID(), row.getEquipmentPromotion());
    return gear != null
        && gear.isEquipment()
        && row.getEquipmentPromotion() >= 0
        && row.getEquipmentPromotion() <= gear.getEquipmentExcel().getMaxPromotion()
        && row.getEquipmentRank() > 0
        && row.getEquipmentRank() <= gear.getEquipmentExcel().getMaxRank()
        && promotion != null
        && promotion.getEquipmentID() == row.getEquipmentID()
        && promotion.getPromotion() == row.getEquipmentPromotion()
        && row.getEquipmentLevel() > 0
        && row.getEquipmentLevel() <= promotion.getMaxLevel()
        && actor.getEquips().values().stream()
            .anyMatch(
                item ->
                    item.getItemId() == row.getEquipmentID()
                        && item.getLevel() == row.getEquipmentLevel()
                        && item.getPromotion() == row.getEquipmentPromotion()
                        && item.getRank() == row.getEquipmentRank());
  }

  private static boolean validProgression(TrialAvatar actor, int world) {
    var row = actor.getSpecialAvatarExcel();
    var excel = actor.getExcel();
    var promotion =
        excel instanceof emu.lunarcore.data.excel.TrialActorExcel
            ? GameData.getTrialActorPromotionExcelMap().get(row.getAvatarID(), row.getPromotion())
            : GameData.getAvatarPromotionExcelMap().get(row.getAvatarID(), row.getPromotion());
    return world >= 0
        && row.getWorldLevel() == (row.isIsUseWorldLevel() ? world : 0)
        && row.getPromotion() >= 0
        && row.getPromotion() <= excel.getMaxPromotion()
        && row.getRank() >= 0
        && row.getRank() <= excel.getMaxRank()
        && promotion != null
        && promotion.getAvatarID() == row.getAvatarID()
        && promotion.getPromotion() == row.getPromotion()
        && row.getLevel() > 0
        && row.getLevel() <= promotion.getMaxLevel();
  }

  private static int maximum(Collection<? extends GameAvatar> actors) {
    int maximum = GameConstants.MAX_MP;
    for (var actor : actors)
      maximum = Math.addExact(maximum, GameConstants.BONUS_MAX_MP.get(actor.getActorId()));
    return maximum;
  }

  public Prepared rebuild(RaidTrialParty party, RaidTeams.Plan plan) {
    if (party == null || !supports(plan) || party.getPlayerWorld() != player.getWorldLevel())
      throw new IllegalArgumentException("Invalid frozen raid trial context");
    var roster = party.getRoster();
    if ("TrialOnly".equals(plan.type())
        && (party.getPlayerGender() != player.getGender().getVal()
            || !roster.equals(defaults(plan).stream().map(RaidTeams.Selection::id).toList())
            || !new HashSet<>(roster).equals(party.getConfigs().keySet())))
      throw new IllegalArgumentException("Invalid frozen automatic raid trial party");
    var ids = new HashSet<>(roster);
    if (roster.isEmpty()
        || roster.size() > 4
        || ids.size() != roster.size()
        || party.getConfigs().isEmpty() && !RaidTeams.frozenParty(plan)
        || !ids.containsAll(party.getConfigs().keySet())
        || !plan.trials().containsAll(party.getConfigs().keySet())
        || party.getLeader() < 0
        || party.getLeader() >= roster.size()
        || !party.getConfigs().keySet().equals(party.getPlans().keySet())
        || !party.getConfigs().keySet().equals(party.getSkills().keySet())
        || !ids.equals(party.getVitals().keySet()))
      throw new IllegalArgumentException("Invalid frozen raid trial party");
    var prepared = new HashMap<Integer, TrialAvatar>();
    var actorIds = new HashSet<Integer>();
    for (int id : roster) {
      var vital = party.getVitals().get(id);
      if (vital == null
          || vital.getHp() < 0
          || vital.getHp() > 10000
          || vital.getSp() < 0
          || vital.getSp() > 10000)
        throw new IllegalArgumentException("Invalid raid member vitals");
      if (!party.getConfigs().containsKey(id)) {
        if (!actorIds.add(roleIdentity(formal(id))))
          throw new IllegalArgumentException("Duplicate raid actor");
        continue;
      }
      var row = GSON.fromJson(party.getConfigs().get(id), SpecialAvatarExcel.class);
      var excel = row == null ? null : GameData.getActorForTrial(row.getAvatarID());
      var existing = player.getAvatars().getTempAvatars().get(id);
      if (id <= 0
          || row == null
          || row.getId() != id
          || excel == null
          || !player.getTrialManager().matchesGender(row.getAvatarID())
          || vital == null
          || player.getAvatars().getAvatars().containsKey(id)
          || existing != null && existing != actors.get(id)
          || !TrialSkillTrees.supports(excel, row.getEnhancedID())
          || excel instanceof emu.lunarcore.data.excel.TrialActorExcel plot
              && !plot.supportsTrial(row)
          || vital.getHp() < 0
          || vital.getHp() > 10000
          || vital.getSp() < 0
          || vital.getSp() > 10000)
        throw new IllegalArgumentException("Invalid frozen raid trial actor");
      var skillPlan = GSON.fromJson(party.getPlans().get(id), TrialSkillPresets.Plan.class);
      if (skillPlan == null
          || skillPlan.custom() != "TYPE_CUSTOM".equals(row.getSkillTreeTemplate()))
        throw new IllegalArgumentException("Invalid frozen raid trial skills");
      var actor = TrialAvatar.restore(player, row, excel, skillPlan, party.getSkills().get(id));
      if (!validProgression(actor, party.getPlayerWorld())
          || !validEquipment(actor)
          || !actorIds.add(roleIdentity(actor)))
        throw new IllegalArgumentException("Invalid frozen raid trial equipment");
      prepared.put(id, actor);
    }
    if (plan.captain() > 0
        && party.getLeader() != captainSlot(plan.captain(), members(party, prepared)))
      throw new IllegalArgumentException("Invalid frozen raid captain");
    if (!RaidTeams.membersAllowed(plan, members(party, prepared))
        || party.getMp() < 0
        || party.getMp() > maximum(members(party, prepared)))
      throw new IllegalArgumentException("Invalid frozen raid trial limits");
    return new Prepared(party, Map.copyOf(prepared));
  }

  private GameAvatar formal(int id) {
    var actor = player.getAvatars().getAvatars().get(id);
    if (actor == null
        || actor.getAvatarId() != id
        || actor.getActorId() <= 0
        || actor.getAvatarType() != AvatarType.AVATAR_FORMAL_TYPE
        || player.getAvatarById(id) != actor)
      throw new IllegalArgumentException("Unowned raid formal actor");
    return actor;
  }

  private List<GameAvatar> members(RaidTrialParty party, Map<Integer, TrialAvatar> trials) {
    var selected = new ArrayList<GameAvatar>();
    for (int id : party.getRoster())
      selected.add(trials.containsKey(id) ? trials.get(id) : formal(id));
    return selected;
  }

  public void install(GameplayState.MissionRaidRun run, Prepared prepared) {
    if (current() != run || run.getTrialParty() != prepared.party())
      throw new IllegalStateException("Unowned raid trial party");
    for (var actor : prepared.actors().values()) {
      var old = player.getAvatars().getTempAvatars().get(actor.getAvatarId());
      if (old != null && old != actors.get(actor.getAvatarId()))
        throw new IllegalStateException("Occupied raid trial identity");
    }
    detach();
    cachedRun = run;
    cachedParty = prepared.party();
    actors = prepared.actors();
    var owned = new HashMap<Integer, GameAvatar>();
    for (int id : cachedParty.getRoster()) if (!actors.containsKey(id)) owned.put(id, formal(id));
    formals = Map.copyOf(owned);
    frozen = cachedParty.frozen();
    cachedLineup = new RaidTrialLineup(player, run, cachedParty);
    for (var actor : actors.values()) player.getAvatars().addTempAvatar(actor);
  }

  public void detach() {
    for (var entry : actors.entrySet())
      if (player.getAvatars().getTempAvatars().get(entry.getKey()) == entry.getValue())
        player.getAvatars().removeTempAvatar(entry.getKey());
    actors = Map.of();
    formals = Map.of();
    cachedRun = null;
    cachedParty = null;
    cachedLineup = null;
    frozen = null;
  }

  public RaidTrialLineup lineup() {
    var run = current();
    if (run == null || run.getTrialParty() == null) return null;
    if (run != cachedRun || run.getTrialParty() != cachedParty) {
      var row = MissionRaid.resource(run.getRaidId(), run.getWorldLevel());
      install(run, rebuild(run.getTrialParty(), RaidTeams.plan(row)));
    }
    return cachedLineup;
  }

  public boolean allowed(RaidTeams.Plan plan) {
    try {
      var team = lineup();
      if (team == null
          || !cachedParty.frozen().equals(frozen)
          || cachedParty.getPlayerWorld() != player.getWorldLevel()
          || !plan.trials().containsAll(cachedParty.getConfigs().keySet())
          || cachedParty.getLeader() < 0
          || cachedParty.getLeader() >= cachedParty.getRoster().size()
          || cachedParty.getMp() < 0
          || cachedParty.getMp() > maximum(members(cachedParty, actors))
          || !cachedParty.getVitals().keySet().equals(new HashSet<>(cachedParty.getRoster())))
        return false;
      if (plan.captain() > 0
          && cachedParty.getLeader() != captainSlot(plan.captain(), members(cachedParty, actors)))
        return false;
      if ("TrialOnly".equals(plan.type())
          && (cachedParty.getPlayerGender() != player.getGender().getVal()
              || !cachedParty
                  .getRoster()
                  .equals(defaults(plan).stream().map(RaidTeams.Selection::id).toList())))
        return false;
      for (int id : cachedParty.getRoster()) {
        var vital = cachedParty.getVitals().get(id);
        if ((actors.containsKey(id)
                ? player.getAvatars().getTempAvatars().get(id) != actors.get(id)
                : formal(id) != formals.get(id))
            || actors.containsKey(id)
                && !player.getTrialManager().matchesGender(actors.get(id).getActorId())
            || vital == null
            || vital.getHp() < 0
            || vital.getHp() > 10000
            || vital.getSp() < 0
            || vital.getSp() > 10000) return false;
      }
      var members = members(cachedParty, actors);
      var identities = new HashSet<Integer>();
      return members.stream().allMatch(member -> identities.add(roleIdentity(member)))
          && RaidTeams.membersAllowed(plan, members);
    } catch (RuntimeException invalid) {
      return false;
    }
  }
}
