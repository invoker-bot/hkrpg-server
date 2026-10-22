package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import emu.lunarcore.game.battle.skills.MazeSkill.CallbackPhase;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.EntityProp;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import java.util.List;

/** The sole native cast adapter: wire aliases end here, before any gameplay effects. */
record SceneCastRequest46(int useId, int casterEntityId, int skillIndex, String ability,
    CallbackPhase phase, boolean monsterCast, IntLinkedOpenHashSet hits,
    IntLinkedOpenHashSet assists, String rejection) {
  static SceneCastRequest46 decode(DynamicMessage request, Scene scene) {
    int use = number(request, "cast_entity_id"); // Ability UseID, not an entity.
    int caster = number(request, "attacked_by_entity_id");
    int index = number(request, "skill_index");
    String ability = (String) value(request, "maze_ability_str");
    var phase = phase(request);
    var hits = ids(request, "hit_target_entity_id_list");
    var assists = ids(request, "assist_monster_entity_id_list");
    String error = null;
    for (var raw : (List<?>) value(request, "assist_monster_entity_info")) {
      var group = (DynamicMessage) raw;
      if (!group.getUnknownFields().asMap().isEmpty() || !only(group, "entity_id_list"))
        error = "group-fields";
      assists.addAll(ids(group, "entity_id_list"));
    }
    if (!request.getUnknownFields().asMap().isEmpty() || !only(request, "cast_entity_id",
        "attacked_by_entity_id", "skill_index", "maze_ability_str", "target_motion",
        "skill_extra_tags", "hit_target_entity_id_list", "assist_monster_entity_id_list",
        "assist_monster_entity_info")) error = "fields";
    if (index < 0 || index > 1) error = "skill-index";
    if (phase == null) error = "callback-phase";
    if (hits.size() > 64 || assists.size() > 64) error = "target-count";
    boolean monster = false;
    if (scene == null) error = "missing-scene";
    else {
      // Preserve only the established direct-avatar compatibility form. Never reverse an
      // empty monster callback just because its UseID happens to equal an avatar entity.
      if (phase == CallbackPhase.DIRECT && scene.getAvatarEntityIds().contains(use)
          && !ability.isEmpty() && assists.isEmpty()
          && hits.intStream().noneMatch(scene.getAvatarEntityIds()::contains)
          && (scene.getEntities().get(caster) instanceof EntityMonster
              || scene.getEntities().get(caster) instanceof EntityProp)) {
        hits.add(caster);
        caster = use;
      }
      monster = scene.getEntities().get(caster) instanceof EntityMonster;
      boolean avatar = scene.getAvatarEntityIds().contains(caster);
      if (!avatar && !monster) error = "caster-entity";
      if (monster && index != 0) error = "monster-skill-index";
      for (var it = assists.iterator(); it.hasNext();) {
        int id = it.nextInt();
        var target = scene.getEntities().get(id);
        if (monster && scene.getAvatarEntityIds().contains(id)) {
          hits.add(id);
          it.remove();
        } else if (!(target instanceof EntityMonster)
            && !(target instanceof EntityProp prop && scene.canDestroyProp(prop))) error = "assist-entity";
      }
      if (hits.isEmpty()) hits.addAll(assists);
      for (var it = assists.iterator(); it.hasNext();) {
        int id = it.nextInt();
        if (scene.getEntities().get(id) instanceof EntityProp) {
          hits.add(id);
          it.remove();
        }
      }
      // All placements are now normalized; validation must finish before MP or prop effects.
      if (hits.size() > 64 || assists.size() > 64) error = "normalized-target-count";
      for (int id : hits) {
        var target = scene.getEntities().get(id);
        if (scene.getAvatarEntityIds().contains(id)) {
          if (avatar) error = "avatar-self-hit";
        } else if (!(target instanceof EntityMonster)
            && !(target instanceof EntityProp prop && scene.canDestroyProp(prop)))
          error = "hit-entity";
      }
    }
    return new SceneCastRequest46(use, caster, index, ability, phase, monster, hits, assists, error);
  }

  @SuppressWarnings("unchecked")
  private static IntLinkedOpenHashSet ids(DynamicMessage request, String name) {
    return new IntLinkedOpenHashSet((List<Integer>) value(request, name));
  }

  private static CallbackPhase phase(DynamicMessage request) {
    var tags = ((List<?>) value(request, "skill_extra_tags")).stream()
        .map(v -> ((EnumValueDescriptor) v).getNumber()).toList();
    if (tags.isEmpty() || tags.equals(List.of(0))) return CallbackPhase.DIRECT;
    if (tags.equals(List.of(1))) return CallbackPhase.PROJECTILE_HIT;
    if (tags.equals(List.of(2))) return CallbackPhase.PROJECTILE_LIFETIME_FINISH;
    // Only the emitted order is verified for the no-LastFinishProjectile constructor.
    if (tags.equals(List.of(1, 2))) return CallbackPhase.COMBINED;
    return null;
  }
}
