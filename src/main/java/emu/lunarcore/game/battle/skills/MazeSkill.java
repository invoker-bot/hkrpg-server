package emu.lunarcore.game.battle.skills;

import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
public class MazeSkill {
  private int id;
  private int index;
  private final String abilityName;
  private List<MazeSkillAction> castActions;
  private List<MazeSkillAction> attackActions;
  private Set<String> adventureModifiers;
  private Set<String> scriptedAdventureModifiers;
  private Set<String> explicitDefaultAdventureModifiers;

  @Setter private boolean triggerBattle;

  public MazeSkill(AvatarExcel excel, int index) {
    this(excel, index, "");
  }

  public MazeSkill(AvatarExcel excel, int index, String abilityName) {
    this.id = excel.getAvatarID();
    this.index = index;
    this.abilityName = java.util.Objects.requireNonNull(abilityName);
    this.triggerBattle = true;
    this.castActions = new ArrayList<>();
    this.attackActions = new ArrayList<>();
  }

  public void addAdventureModifier(String modifier) {
    if (modifier == null) return;
    registerAdventureModifier(modifier);
    if (this.explicitDefaultAdventureModifiers == null) {
      this.explicitDefaultAdventureModifiers = new ObjectOpenHashSet<>();
    }
    this.explicitDefaultAdventureModifiers.add(modifier);
  }

  public void addScriptedAdventureModifier(String modifier) {
    if (modifier == null) return;
    registerAdventureModifier(modifier);
    if (this.scriptedAdventureModifiers == null) {
      this.scriptedAdventureModifiers = new ObjectOpenHashSet<>();
    }
    this.scriptedAdventureModifiers.add(modifier);
  }

  private void registerAdventureModifier(String modifier) {
    if (this.adventureModifiers == null) {
      this.adventureModifiers = new ObjectOpenHashSet<>();
    }

    this.adventureModifiers.add(modifier);
  }

  public boolean hasAdventureModifier(String modifier) {
    return this.adventureModifiers != null && this.adventureModifiers.contains(modifier);
  }

  public boolean hasAdventureModifier(GameAvatar caster, String modifier) {
    var metadata = caster == null ? null : caster.getMazeCastMetadata();
    return metadata != null && metadata.skill == this
        ? metadata.modifiers.contains(modifier)
        : hasAdventureModifier(modifier);
  }

  public boolean isTriggerBattle(GameAvatar caster) {
    var metadata = caster == null ? null : caster.getMazeCastMetadata();
    return metadata != null && metadata.skill == this ? metadata.triggerBattle : this.triggerBattle;
  }

  /** Transient per-caster request state; this is not an adventure-modifier lifecycle. */
  public static final class CastMetadata {
    private final MazeSkill skill;
    private final Set<String> modifiers;
    private boolean triggerBattle;

    private CastMetadata(MazeSkill skill) {
      this.skill = skill;
      this.modifiers = new java.util.HashSet<>();
      if (skill.adventureModifiers != null) this.modifiers.addAll(skill.adventureModifiers);
      if (skill.scriptedAdventureModifiers != null) {
        this.modifiers.removeAll(skill.scriptedAdventureModifiers);
      }
      if (skill.explicitDefaultAdventureModifiers != null) {
        this.modifiers.addAll(skill.explicitDefaultAdventureModifiers);
      }
      this.triggerBattle = skill.triggerBattle;
    }
  }

  private CastMetadata metadata(GameAvatar caster, boolean reset) {
    if (caster == null) return null;
    var metadata = caster.getMazeCastMetadata();
    if (reset || metadata == null || metadata.skill != this) {
      metadata = new CastMetadata(this);
      caster.setMazeCastMetadata(metadata);
    }
    return metadata;
  }

  void applyMetadata(GameAvatar caster, String modifier, Boolean battle) {
    var metadata = metadata(caster, false);
    if (metadata == null) return;
    if (modifier != null) metadata.modifiers.add(modifier);
    if (battle != null) metadata.triggerBattle = battle;
  }

  void removeMetadata(GameAvatar caster, String modifier) {
    var metadata = metadata(caster, false);
    if (metadata != null) metadata.modifiers.remove(modifier);
  }

  /** Triggered when player casts a skill */
  public void onCast(GameAvatar caster, MotionInfo castPosition) {
    metadata(caster, true);
    if (this.getCastActions().size() == 0) return;

    for (var action : this.getCastActions()) {
      action.onCast(caster, castPosition);
    }
  }

  /** Triggered when player casts a skill and it hits entities */
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> entities) {
    metadata(caster, false);
    if (this.getAttackActions().size() == 0) return;

    for (var action : this.getAttackActions()) {
      action.onCastHit(caster, entities);
    }
  }

  /** Triggered when player attacks an enemy */
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    metadata(caster, false);
    if (this.getAttackActions().size() == 0) return;

    for (var action : this.getAttackActions()) {
      action.onAttack(caster, targets);
    }
  }
}
