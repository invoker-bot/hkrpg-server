package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.scene.*;
import emu.lunarcore.game.scene.entity.EntityMonster;

/** Room/content relation follows LunarCore-development ec315bb1 (AGPL), using 4.6 resources. */
public final class UniverseEntityLoader extends SceneEntityLoader {
  @Override
  public emu.lunarcore.game.scene.entity.EntityNpc loadNpc(
      Scene scene, GroupInfo group, NpcInfo info) {
    var original = super.loadNpc(scene, group, info);
    if (original == null) return null;
    var event = RogueEventNpc.create(scene, group, info);
    return event == null ? original : event;
  }

  public static java.util.Set<Integer> roomGroups(emu.lunarcore.game.player.Player p) {
    var room = GameplayDefinitions.rooms.get(p.getGameplay().getRogue().getRoomId());
    return room == null || room.GroupWithContent() == null
        ? java.util.Set.of()
        : room.GroupWithContent().keySet();
  }

  @Override
  public void onSceneLoad(Scene scene) {
    var run = scene.getPlayer().getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    if (!run.isInside()
        || room == null
        || !java.util.Set.of(
                GameplayState.RogueRun.Phase.EXPLORING,
                GameplayState.RogueRun.Phase.FINISHED,
                GameplayState.RogueRun.Phase.FAILED)
            .contains(run.getPhase())) return;
    var entry = GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    if (entry == null
        || entry.getFloorID() != scene.getFloorId()
        || entry.getPlaneID() != scene.getPlaneId()) return;
    for (int id : roomGroups(scene.getPlayer())) scene.loadGroup(id);
  }

  @Override
  public emu.lunarcore.game.scene.entity.EntityProp loadProp(
      Scene scene, GroupInfo group, PropInfo info) {
    if (RoguePropDestruction.alreadyDestroyed(scene, group, info)
        || RoguePropRecovery.alreadyDestroyed(scene, group, info)) return null;
    if (info.getPropID() == 1001) {
      var prop = super.loadProp(scene, group, info);
      if (prop != null && RogueDownload.used(scene.getPlayer(), group.getId(), info.getID()))
        prop.setState(emu.lunarcore.game.enums.PropState.Locked, false);
      return prop;
    }
    if (!java.util.Set.of(1000, 1021, 1022, 1023).contains(info.getPropID()))
      return super.loadProp(scene, group, info);
    if (info.isIsDelete() || info.isIsClientOnly() || !info.isLoadOnInitial()) return null;
    var run = scene.getPlayer().getGameplay().getRogue();
    var rows =
        GameplayDefinitions.maps.getOrDefault(run.getMapId(), java.util.List.of()).stream()
            .filter(s -> s.SiteID() == run.getSiteId())
            .toList();
    if (rows.size() != 1 || rows.getFirst().NextSiteIDList() == null) return null;
    var next = rows.getFirst().NextSiteIDList();
    // Terminal-result/exit presentation needs its own client flow; do not fabricate a destination.
    if (next.isEmpty()) return super.loadProp(scene, group, info);
    var source = GameplayDefinitions.rooms.get(run.getRoomId());
    if (source != null
        && source.RogueRoomType() == 5
        && group.getId() == source.GroupID()
        && info.getPropID() == 1000
        && "Duck".equals(info.getName())
        && RogueMapPortal.SCRIPT.equals(info.getInitLevelGraph())) {
      var excel = GameData.getPropExcelMap().get(1000);
      if (excel == null
          || !excel
              .getPropStateList()
              .containsAll(
                  java.util.Set.of(
                      emu.lunarcore.game.enums.PropState.Open,
                      emu.lunarcore.game.enums.PropState.Closed))) return null;
      return new RogueMapPortal(scene, excel, group, info);
    }
    int index =
        switch (java.util.Objects.toString(info.getName(), "")) {
          case "Door", "Door1", "Teleport1" -> 0;
          case "Door2" -> 1;
          default -> -1;
        };
    if (index < 0 || index >= next.size() || next.size() > 2) return null;
    int site = next.get(index);
    var destination = GameplayDefinitions.rooms.get(run.getRooms().get(site));
    if (destination == null) return null;
    int prop =
        switch (destination.RogueRoomType()) {
          case 3, 8 -> 1022;
          case 5 -> 1023;
          default -> 1021;
        };
    var excel = GameData.getPropExcelMap().get(prop);
    if (excel == null
        || !excel
            .getPropStateList()
            .containsAll(
                java.util.Set.of(
                    emu.lunarcore.game.enums.PropState.Open,
                    emu.lunarcore.game.enums.PropState.Closed))) return null;
    return new RoguePortal(scene, excel, group, info, site, destination.RogueRoomID());
  }

  @Override
  public EntityMonster loadMonster(Scene scene, GroupInfo group, MonsterInfo info) {
    if (RogueBattleKills.alreadyDefeated(scene, group, info)) return null;
    if (info.isIsDelete() || info.isIsClientOnly() || !info.isLoadOnInitial()) return null;
    var run = scene.getPlayer().getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || room == null
        || room.GroupWithContent() == null
        || run.getCleared().contains(run.getSiteId())) return null;
    var row = RogueMonsterSelection.resolve(scene, group, info);
    if (row == null || GameData.getStageExcelMap().get(row.EventID()) == null) return null;
    var npc = GameData.getNpcMonsterExcelMap().get(row.NpcMonsterID());
    if (npc == null) return null;
    int level = RogueBossEncounters.level(run, room, group.getId());
    if (level < 0) return null;
    var monster = new EntityMonster(scene, npc, group, info);
    monster.setEventId(row.EventID());
    monster.setCustomStage(row.EventID());
    monster.setCustomLevel(level);
    return monster;
  }
}
