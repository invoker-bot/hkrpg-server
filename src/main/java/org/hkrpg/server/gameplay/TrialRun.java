package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.data.excel.AvatarDemoExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.util.Location;
import java.util.*;
import lombok.Getter;

/** Trial intent, frozen actors, vitals and home share the player document. */
@Entity(useDiscriminator = false)
@Getter
public final class TrialRun {
  public enum Phase {
    ENTERING,
    ACTIVE,
    RETURNING,
    WON
  }

  private int stageId, leader, mp;
  private Phase phase;
  private String demoJson;
  private List<Integer> roster = new ArrayList<>();
  private Map<Integer, String> trialConfigs = new HashMap<>();
  private Map<Integer, StoryLineState.Vital> vitals = new HashMap<>();
  private Map<Integer, Map<Integer, Integer>> skills = new HashMap<>();
  private Map<Integer, String> skillPlans = new HashMap<>();
  private Set<Integer> defeated = new HashSet<>();
  private StoryLineState.Place home, location;
  private GameplayState.TrialCompletion rewardPlan;
  private static final Gson GSON = new Gson();

  protected TrialRun() {}

  public TrialRun(
      Player player,
      AvatarDemoExcel demo,
      List<TrialAvatar> actors,
      PlayerLineup lineup,
      GameplayState.TrialCompletion rewardPlan) {
    stageId = demo.getId();
    phase = Phase.ENTERING;
    home = new StoryLineState.Place(player);
    this.rewardPlan = rewardPlan;
    var json = GSON.toJsonTree(demo).getAsJsonObject();
    var monsters =
        demo.getMonsters().values().stream()
            .sorted(
                Comparator.comparingInt(emu.lunarcore.data.common.ExcelMonsterParam::getConfigId))
            .toList();
    json.add(
        "ConfigList1",
        GSON.toJsonTree(
            monsters.stream()
                .mapToInt(emu.lunarcore.data.common.ExcelMonsterParam::getConfigId)
                .toArray()));
    json.add(
        "NpcMonsterIDList1",
        GSON.toJsonTree(
            monsters.stream()
                .mapToInt(emu.lunarcore.data.common.ExcelMonsterParam::getNpcMonsterId)
                .toArray()));
    json.add(
        "EventIDList1",
        GSON.toJsonTree(
            monsters.stream()
                .mapToInt(emu.lunarcore.data.common.ExcelMonsterParam::getEventId)
                .toArray()));
    demoJson = GSON.toJson(json);
    for (var actor : actors) {
      trialConfigs.put(actor.getAvatarId(), GSON.toJson(actor.getSpecialAvatarExcel()));
      skills.put(actor.getAvatarId(), new HashMap<>(actor.getSkillTree()));
      skillPlans.put(actor.getAvatarId(), GSON.toJson(actor.getSkillPlan()));
    }
    record(lineup, actors);
  }

  public AvatarDemoExcel demo() {
    var row = GSON.fromJson(demoJson, AvatarDemoExcel.class);
    if (row == null || row.getId() != stageId)
      throw new IllegalStateException("Invalid frozen trial stage");
    row.onLoad();
    return row;
  }

  public void record(PlayerLineup lineup, Collection<TrialAvatar> actors) {
    roster.clear();
    roster.addAll(lineup.getAvatars());
    leader = lineup.getLeader();
    mp = lineup.getMp();
    for (var actor : actors)
      vitals.put(
          actor.getAvatarId(),
          new StoryLineState.Vital(actor.getCurrentHp(lineup), actor.getCurrentSp(lineup)));
  }

  public void arrived() {
    if (phase == Phase.ENTERING) phase = Phase.ACTIVE;
  }

  public void returning() {
    if (phase != Phase.WON) phase = Phase.RETURNING;
  }

  public void won() {
    phase = Phase.WON;
  }

  public void locate(Player player) {
    location = new StoryLineState.Place(player);
  }

  public static Location place(StoryLineState.Place place) {
    return new Location(
        place.getPlane(), place.getFloor(), place.getEntry(), place.getPos(), place.getRot());
  }
}
