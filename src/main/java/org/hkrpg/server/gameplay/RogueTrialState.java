package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.trial.TrialAvatar;
import java.util.*;

/** A downloaded trial belongs only to this exploration, including its entry-time skills. */
@Entity(useDiscriminator = false)
public final class RogueTrialState {
  private String config;
  private String plan;
  private Map<Integer, Integer> skills = new HashMap<>();
  protected RogueTrialState() {}

  RogueTrialState(TrialAvatar actor) {
    var gson = new Gson();
    config = gson.toJson(actor.getSpecialAvatarExcel());
    plan = gson.toJson(actor.getSkillPlan());
    skills.putAll(actor.getSkillTree());
  }

  TrialAvatar restore(Player player) {
    var gson = new Gson();
    var row = gson.fromJson(config, SpecialAvatarExcel.class);
    var skillPlan = gson.fromJson(plan, TrialSkillPresets.Plan.class);
    if (!RogueTutorialDownload.trialSource(row) || skillPlan == null || !skillPlan.custom()
        || skillPlan.noKey() || !skillPlan.nodes().isEmpty() || skills == null)
      throw new IllegalStateException("Invalid saved tutorial download");
    var excel = GameData.getActorForTrial(row.getAvatarID());
    if (excel == null) throw new IllegalStateException("Missing downloaded trial actor resource");
    return TrialAvatar.restore(player, row, excel, skillPlan, skills);
  }
}
