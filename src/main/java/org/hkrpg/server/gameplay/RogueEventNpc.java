package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.util.*;

/** A leased authored actor. Event IDs are independent of scene entity IDs. */
public final class RogueEventNpc extends EntityNpc {
  private final GameplayState.RogueRun run;
  private final GameplayState.RogueDialogue binding;
  private final RogueDialogueDefinitions.Npc definition;
  private final RogueDialogueDefinitions.Dialogue dialogue;
  private final GroupInfo group;
  private final NpcInfo placement;
  private boolean persisted;

  private RogueEventNpc(
      Scene scene,
      GroupInfo group,
      NpcInfo info,
      RogueDialogueDefinitions.Npc definition,
      RogueDialogueDefinitions.Dialogue dialogue,
      GameplayState.RogueDialogue binding) {
    super(scene, group, info);
    this.run = scene.getPlayer().getGameplay().getRogue();
    this.group = group;
    this.placement = info;
    this.definition = definition;
    this.dialogue = dialogue;
    this.binding = binding;
  }

  public static RogueEventNpc create(Scene scene, GroupInfo group, NpcInfo info) {
    var player = scene.getPlayer();
    var run = player.getGameplay().getRogue();
    if (info.getNPCID() != 3013 || !matchesRoom(scene, run, group, info)) return null;
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var definition = RogueDialogueDefinitions.npcs.get(room.GroupWithContent().get(group.getId()));
    if (definition == null || !"Event".equals(definition.type())) return null;
    // Initial progress must be explicitly representable by an authored progress-zero row.
    var candidates = definition.dialogues().stream().filter(d -> d.progress() == 0).toList();
    if (candidates.size() != 1) return null;
    var dialogue = candidates.getFirst();
    if (dialogue.dialoguePath().isEmpty()
        || dialogue.options().isEmpty()
        || !RogueUnlocks.open(player, dialogue.unlock())) return null;
    var binding =
        player
            .getGameplay()
            .registerRogueDialogue(
                group.getId(), info.getID(), info.getNPCID(), definition.id(), dialogue.progress());
    return binding == null
        ? null
        : new RogueEventNpc(scene, group, info, definition, dialogue, binding);
  }

  private static boolean matchesRoom(
      Scene scene, GameplayState.RogueRun run, GroupInfo group, NpcInfo info) {
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    return run.isInside()
        && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        && Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())
        && room != null
        && Set.of(3, 8).contains(room.RogueRoomType())
        && room.GroupWithContent() != null
        && room.GroupWithContent().containsKey(group.getId())
        && entry != null
        && entry.getPlaneID() == scene.getPlaneId()
        && entry.getFloorID() == scene.getFloorId()
        && scene.getPlaneType() == emu.lunarcore.game.enums.PlaneType.Rogue
        && scene.getFloorInfo() != null
        && scene.getResourceGroups().get(group.getId()) == group
        && group.getNPCList() != null
        && group.getNPCList().contains(info)
        && info.getID() > 0
        && !info.isIsDelete()
        && !info.isIsClientOnly()
        && info.isLoadOnInitial();
  }

  public boolean valid() {
    var player = getScene().getPlayer();
    var current = player.getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(current.getRoomId());
    return current == run
        && player.getScene() == getScene()
        && getEntityId() > 0
        && getScene().getEntityById(getEntityId()) == this
        && getNpcId() == binding.getNpc()
        && getGroupId() == binding.getGroup()
        && getInstId() == binding.getInstance()
        && current.getSiteId() == binding.getSite()
        && current.getRoomId() == binding.getRoom()
        && current.getDialogues() != null
        && current.getDialogues().get(binding.key()) == binding
        && matchesRoom(getScene(), current, group, placement)
        && player.getEntryId() == room.MapEntrance()
        && getScene().getEntryId() == room.MapEntrance()
        && Objects.equals(room.GroupWithContent().get(group.getId()), definition.id())
        && definition.equals(RogueDialogueDefinitions.npcs.get(definition.id()))
        && RogueUnlocks.open(player, dialogue.unlock());
  }

  public GameplayState.RogueDialogue binding() {
    return binding;
  }

  public List<RogueDialogueDefinitions.Option> options() {
    return dialogue.options();
  }

  public boolean needsPersistence() {
    return !persisted;
  }

  public void markPersisted() {
    persisted = true;
  }
}
