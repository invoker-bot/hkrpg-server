package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/** One original nonperiodic goal owns its acceptance and saved source NPC observation. */
@Entity(useDiscriminator = false)
public final class CavalryBeastState {
  private int version = 1;
  @Getter private QuestState activity;
  @Getter private Receipt receipt;

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Receipt {
    private int quest, plane, floor, dimension, story, world, entry, group, instance, npc, entity;
    private long runtime, at, begin, end;
    private String acceptance, groupSource, npcSource;

    private Receipt() {}

    Receipt(Player player, EntityNpc actor, CavalryBeastSources.Binding binding,
        QuestState state, GameplayDefinitions.Window window, long at) {
      var scene = player.getScene();
      quest = state.getQuestId();
      plane = scene.getPlaneId();
      floor = scene.getFloorId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      world = scene.getWorldId();
      entry = scene.getEntryId();
      group = actor.getGroupId();
      instance = actor.getInstId();
      npc = actor.getNpcId();
      entity = actor.getEntityId();
      runtime = scene.getRuntimeId();
      this.at = at;
      begin = window.beginTime();
      end = window.endTime();
      acceptance = state.getObservationKey();
      groupSource = binding.groupSource();
      npcSource = binding.npcSource();
    }

    private boolean valid(CavalryBeastRules rules) {
      try {
        var binding = rules.binding(plane, floor, dimension, group, instance);
        return quest == CavalryBeastSources.QUEST && binding != null && npc == binding.npc()
            && world == CavalryBeastSources.WORLD && entity > 0 && runtime > 0 && story >= 0
            && rules.entry(plane, floor, entry) && begin > 0 && end > begin
            && at >= begin && at < end && canonical(acceptance)
            && Objects.equals(groupSource, binding.groupSource())
            && Objects.equals(npcSource, binding.npcSource());
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  boolean valid(CavalryBeastRules rules) {
    if (rules == null || version != 1) return false;
    if (activity == null) return receipt == null;
    if (!canonical(activity.getObservationKey())) return false;
    if (receipt == null)
      return MissionStateRecords.doing(activity, CavalryBeastSources.QUEST, 1)
          && activity.getProgress() == 0;
    return MissionStateRecords.completed(activity, CavalryBeastSources.QUEST, 1)
        && receipt.valid(rules) && receipt.at == activity.getFinishTime()
        && Objects.equals(receipt.acceptance, activity.getObservationKey());
  }

  boolean accept() {
    if (activity != null) return false;
    activity = new QuestState(CavalryBeastSources.QUEST);
    activity.captureObservationKey();
    return true;
  }

  CavalryBeastState recorded(Receipt observation) {
    var gson = ResourceLoader.getGson();
    var next = gson.fromJson(gson.toJson(this), CavalryBeastState.class);
    if (next.receipt != null || next.activity == null
        || !Objects.equals(observation.acceptance, next.activity.getObservationKey())
        || !next.activity.advance(1, 1, observation.at))
      throw new IllegalStateException("Cavalry acceptance changed");
    next.receipt = observation;
    return next;
  }

  private static boolean canonical(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
