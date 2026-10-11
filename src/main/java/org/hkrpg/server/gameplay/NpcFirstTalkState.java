package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.util.*;
import lombok.Getter;

/** The one source-bound NPC first meeting is global history, independent of any daily period. */
@Entity(useDiscriminator = false)
public final class NpcFirstTalkState {
  private int version = 1;
  private Map<Integer, Observation> records = new TreeMap<>();

  @Getter
  @Entity(useDiscriminator = false)
  public static final class Observation {
    private int uid, performance, plane, floor, entry, dimension, story, group, instance, npc, entity;
    private long at, runtime, prerequisiteFinish;
    private String event, source, npcSource, groupSource, scriptSource;

    private Observation() {}

    Observation(Player player, EntityNpc actor, NpcFirstTalkRules rules, long at) {
      var scene = actor.getScene();
      uid = player.getUid();
      performance = NpcFirstTalkSources.PERFORMANCE;
      plane = scene.getPlaneId(); floor = scene.getFloorId(); entry = scene.getEntryId();
      dimension = scene.getDimensionId(); story = scene.getStoryLineId();
      group = actor.getGroupId(); instance = actor.getInstId(); npc = actor.getNpcId(); entity = actor.getEntityId();
      runtime = scene.getRuntimeId();
      this.at = at;
      prerequisiteFinish = player.getGameplay().getMainMissions().get(NpcFirstTalkSources.PREREQUISITE).getFinishTime();
      event = UUID.randomUUID().toString();
      source = NpcFirstTalkSources.IDENTITY;
      npcSource = rules.npcSource();
      groupSource = NpcFirstTalkSources.FILES.get(NpcFirstTalkSources.GROUP_PATH);
      scriptSource = NpcFirstTalkSources.FILES.get(NpcFirstTalkSources.INTRO);
    }

    boolean valid(Player player, NpcFirstTalkRules rules) {
      try {
        return uid > 0 && uid == player.getUid() && performance == NpcFirstTalkSources.PERFORMANCE
            && plane == NpcFirstTalkSources.PLANE && floor == NpcFirstTalkSources.FLOOR
            && rules.entry(entry) && dimension == 0 && story == 0
            && group == NpcFirstTalkSources.GROUP && instance == NpcFirstTalkSources.INSTANCE
            && npc == NpcFirstTalkSources.NPC && entity > 0 && runtime > 0 && at > 0
            && prerequisiteFinish > 0 && prerequisiteFinish <= at
            && UUID.fromString(event).toString().equals(event)
            && NpcFirstTalkSources.IDENTITY.equals(source) && rules.npcSource().equals(npcSource)
            && NpcFirstTalkSources.FILES.get(NpcFirstTalkSources.GROUP_PATH).equals(groupSource)
            && NpcFirstTalkSources.FILES.get(NpcFirstTalkSources.INTRO).equals(scriptSource);
      } catch (RuntimeException invalid) { return false; }
    }
  }

  public Map<Integer, Observation> getRecords() {
    return records == null ? null : Collections.unmodifiableMap(records);
  }

  boolean valid(Player player, NpcFirstTalkRules rules) {
    if (rules == null || version != 1 || records == null || records.size() > 1) return false;
    for (var record : records.entrySet())
      if (record.getKey() == null || record.getValue() == null
          || record.getKey() != NpcFirstTalkSources.PERFORMANCE
          || record.getValue().performance != record.getKey() || !record.getValue().valid(player, rules)) return false;
    return true;
  }

  NpcFirstTalkState recorded(Observation observation) {
    var next = ResourceLoader.getGson().fromJson(ResourceLoader.getGson().toJson(this), NpcFirstTalkState.class);
    if (next.records.putIfAbsent(observation.performance, observation) != null)
      throw new IllegalStateException("Already observed original NPC introduction");
    return next;
  }
}
