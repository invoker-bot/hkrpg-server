package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;

/** Runtime-only ownership proof for an actual source-bound mission raid battle. */
final class MissionRaidBattleScope {
  private static final Gson GSON = new Gson();
  private final GameplayState.MissionRaidRun run;
  private final Scene scene;
  private final GameplayState.RaidFormalTeam team;
  private final RaidTrialParty party;
  private final String source, teamDefinition, partyDefinition;

  private MissionRaidBattleScope(Player player, GameplayState.MissionRaidRun run, String source) {
    this.run = run;
    scene = player.getScene();
    team = run.getFormalTeamPlan();
    party = run.getTrialParty();
    this.source = source;
    teamDefinition = GSON.toJson(team);
    partyDefinition = party == null ? "" : party.frozen();
  }

  static MissionRaidBattleScope capture(Player player) {
    try {
      var manager = new MissionRaid(player);
      var run = manager.current();
      var row = run == null ? null : MissionRaid.resource(run.getRaidId(), run.getWorldLevel());
      if (row == null
          || !manager.activeFor(run.getMainMission())
          || !RaidTeams.allowed(player, row)) return null;
      var original = RaidEntranceSources.fingerprint(row);
      // Unknown SkipUI sources have no whole-row identity here and remain outside this proof.
      if (!original.isJsonObject()) return null;
      return new MissionRaidBattleScope(player, run, original.toString());
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  boolean owns(int main) {
    return run.getMainMission() == main;
  }

  boolean matches(Player player) {
    try {
      var manager = new MissionRaid(player);
      var row = MissionRaid.resource(run.getRaidId(), run.getWorldLevel());
      return manager.current() == run
          && player.getScene() == scene
          && manager.activeFor(run.getMainMission())
          && RaidTeams.allowed(player, row)
          && source.equals(RaidEntranceSources.fingerprint(row).toString())
          && run.getFormalTeamPlan() == team
          && teamDefinition.equals(GSON.toJson(team))
          && run.getTrialParty() == party
          && partyDefinition.equals(party == null ? "" : party.frozen());
    } catch (RuntimeException invalid) {
      return false;
    }
  }
}
