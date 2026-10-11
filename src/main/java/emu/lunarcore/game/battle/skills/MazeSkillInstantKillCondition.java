package emu.lunarcore.game.battle.skills;

import com.google.gson.JsonElement;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.GameEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hkrpg.server.gameplay.MazeInstantKillTargets;

/** Exact ByAllowInstantKill/ParamEntity; each actual hit owns its branch and singleton target. */
public final class MazeSkillInstantKillCondition extends MazeSkillAction {
  private final boolean inverse;
  private final List<MazeSkillAction> success;
  private final List<MazeSkillAction> failure;

  public MazeSkillInstantKillCondition(
      boolean inverse, List<MazeSkillAction> success, List<MazeSkillAction> failure) {
    this.inverse = inverse;
    this.success = List.copyOf(success);
    this.failure = List.copyOf(failure);
  }

  public static Optional<Boolean> parseInverse(JsonElement expression) {
    if (expression == null || !expression.isJsonObject()) return Optional.empty();
    var object = expression.getAsJsonObject();
    if (!Set.of("$type", "TargetType", "Inverse").containsAll(object.keySet()))
      return Optional.empty();
    var type = object.get("$type");
    var target = object.get("TargetType");
    var inverse = object.get("Inverse");
    if (type == null
        || !type.isJsonPrimitive()
        || !type.getAsJsonPrimitive().isString()
        || !"RPG.GameCore.ByAllowInstantKill".equals(type.getAsString())
        || target == null
        || !target.isJsonObject()
        || (inverse != null
            && (!inverse.isJsonPrimitive() || !inverse.getAsJsonPrimitive().isBoolean())))
      return Optional.empty();
    var selector = target.getAsJsonObject();
    var selectorType = selector.get("$type");
    var alias = selector.get("Alias");
    if (!selector.keySet().equals(Set.of("$type", "Alias"))
        || selectorType == null
        || !selectorType.isJsonPrimitive()
        || !selectorType.getAsJsonPrimitive().isString()
        || !"RPG.GameCore.TargetAlias".equals(selectorType.getAsString())
        || alias == null
        || !alias.isJsonPrimitive()
        || !alias.getAsJsonPrimitive().isString()
        || !"ParamEntity".equals(alias.getAsString())) return Optional.empty();
    return Optional.of(inverse != null && inverse.getAsBoolean());
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    forSelected(caster, targets, (action, target) -> action.onAttack(caster, List.of(target)));
  }

  @Override
  public void prepareAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    forSelected(caster, targets, (action, target) -> action.prepareAttack(caster, List.of(target)));
  }

  @Override
  public void onBattle(GameAvatar caster, List<? extends GameEntity> targets, Battle battle) {
    forSelected(caster, targets, (action, target) -> action.onBattle(caster, List.of(target), battle));
  }

  private void forSelected(GameAvatar caster, List<? extends GameEntity> targets,
      java.util.function.BiConsumer<MazeSkillAction, EntityMonster> callback) {
    if (caster == null || caster.getOwner() == null || targets == null) return;
    var player = caster.getOwner();
    synchronized (player) {
      var scene = player.getScene();
      var lineup = player.getCurrentLineup();
      if (scene == null
          || scene.getPlayer() != player
          || scene.getAvatarByEntityId(caster.getEntityId()) != caster
          || lineup == null
          || !lineup.getAvatars().contains(caster.getAvatarId())
          || player.getAvatarById(caster.getAvatarId()) != caster
          || !caster.isAlive(lineup)) return;
      for (var target : targets) {
        if (!(target instanceof EntityMonster monster)) continue;
        var result = MazeInstantKillTargets.evaluate(scene, monster);
        if (result.isEmpty()) continue;
        var actions = result.get() != inverse ? success : failure;
        for (var action : actions) callback.accept(action, monster);
      }
    }
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> targets) {
    // Non-battle techniques dispatch the authored attack callback through the hit path.
    onAttack(caster, targets);
  }
}
