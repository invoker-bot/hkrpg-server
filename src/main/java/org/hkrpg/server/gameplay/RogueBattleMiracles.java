package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.MazeBuff;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Pinned 4.6 entry effects, executed by their resource abilities. */
final class RogueBattleMiracles {
  // The native dynamic-value map carries float32. Keep every integer balance exact.
  private static final int MAX_EXACT_FRAGMENT_BALANCE = (1 << 24) - 1;
  private static final Map<Integer, Integer> BINDINGS =
      Map.ofEntries(
          Map.entry(8, 633102),
          Map.entry(10, 633111),
          Map.entry(14, 633104),
          Map.entry(19, 633105),
          Map.entry(45, 631013),
          Map.entry(46, 631014),
          Map.entry(47, 631015),
          Map.entry(48, 631016),
          Map.entry(49, 631017),
          Map.entry(50, 631018),
          Map.entry(51, 631019),
          Map.entry(52, 631020),
          Map.entry(53, 631021),
          Map.entry(54, 631022),
          Map.entry(55, 631023),
          Map.entry(56, 631024),
          Map.entry(58, 633112),
          Map.entry(59, 633107),
          Map.entry(62, 633108),
          Map.entry(66, 633109),
          Map.entry(68, 633110),
          Map.entry(71, 633111));
  private static final Map<Integer, List<Double>> RESOURCE_PARAMETERS =
      Map.ofEntries(
          Map.entry(8, List.of(.3)),
          Map.entry(10, List.of(2.0)),
          Map.entry(14, List.of(100.0, .2)),
          Map.entry(19, List.of(3.0)),
          Map.entry(45, List.of()),
          Map.entry(46, List.of(40.0)),
          Map.entry(47, List.of(.3)),
          Map.entry(48, List.of(.3)),
          Map.entry(49, List.of(.5, .35)),
          Map.entry(50, List.of(.5, .25)),
          Map.entry(51, List.of(.35)),
          Map.entry(52, List.of(.25)),
          Map.entry(53, List.of(.35)),
          Map.entry(54, List.of(.25)),
          Map.entry(55, List.of(1.0)),
          Map.entry(56, List.of(1.0)),
          Map.entry(59, List.of(.5, .2)),
          Map.entry(58, List.of(.3)),
          Map.entry(62, List.of(1.0)),
          Map.entry(66, List.of(5.0, 5.0)),
          Map.entry(68, List.of(1.0, 10.0)),
          Map.entry(71, List.of(2.0)));

  static boolean hasBinding(int id) {
    return BINDINGS.containsKey(id);
  }

  static boolean definitionMatches(int id) {
    int buff = BINDINGS.getOrDefault(id, 0);
    var row = GameData.getMazeBuffExcelMap().get(buff, 1);
    return buff > 0
        && row != null
        && row.getID() == buff
        && row.getLv() == 1
        && row.getLvMax() == 1
        && "StageAbilityBeforeCharacterBorn".equals(row.getInBattleBindingType())
        && ("StageAbility_" + buff).equals(row.getInBattleBindingKey())
        && row.getParamList() != null
        && row.getParamList().stream().allMatch(Objects::nonNull)
        && row.getParamList().stream()
            .map(p -> p.Value())
            .toList()
            .equals(RESOURCE_PARAMETERS.get(id));
  }

  /** Called only after the universe manager validates actual room NPC/stage identities. */
  static void bind(Player player, Battle battle) {
    var run = player.getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    var scene = player.getScene();
    if (entry == null
        || scene == null
        || scene.getPlaneType() != PlaneType.Rogue
        || player.getEntryId() != room.MapEntrance()
        || scene.getPlaneId() != entry.getPlaneID()
        || scene.getFloorId() != entry.getFloorID()
        || !Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())
        || run.getRoster().isEmpty()) return;
    BINDINGS.forEach(
        (id, buff) -> {
          if (RogueMiracles.active(run, id)
              && definitionMatches(id)
              && !battle.hasBuff(buff)
              && (id != 14
                  || run.getCoins() >= 0 && run.getCoins() <= MAX_EXACT_FRAGMENT_BALANCE)) {
            var binding = new MazeBuff(buff, 1, -1, -1);
            if (id == 14) {
              var parameters = run.getMiracles().get(id).getEffectParameters();
              // The ability computes complete hundreds; it must receive this battle's balance.
              binding.addDynamicValue("ItemNumber", run.getCoins());
              binding.addDynamicValue("#ADF_1", parameters.getFirst());
              binding.addDynamicValue("#ADF_2", parameters.get(1));
            } else if (id == 58) {
              var item = run.getMiracles().get(id);
              binding.addDynamicValue(RoguePropMiracle.COUNT_KEY, item.destructionCount());
              binding.addDynamicValue("#ADF_1", item.getEffectParameters().getFirst());
            } else if (id == 68) {
              var parameters = run.getMiracles().get(id).getEffectParameters();
              // The resource's second default is ten turns; the acquired curio specifies three.
              binding.addDynamicValue("#ADF_1", parameters.getFirst());
              binding.addDynamicValue("#ADF_2", parameters.get(2));
            }
            battle.addBuff(binding);
          }
        });
  }

  private RogueBattleMiracles() {}
}
