package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Additional source gate for client-reported blast victims. The packet has no prop ID, hit origin,
 * shape or collision result; radius and collision detection remain in the client. This validates
 * a real authored explosive and whole reviewed tasks, without altering the existing ammo lease.
 */
final class WolfBroExplosionRules {
  // Complete source records at TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b.
  private static final Map<String,String> ABILITIES = Map.of(
      "NPCMonster_W3_TV_00_Ability.json", "0870292622bb7238f5156f39a860d6ca21ea4d202bb511191dab8c25fd89ece4",
      "NPCMonster_W3_Clock_00_Ability.json", "68a4c98154d54081700fc9c04b86f39172bb9d6e43419acb1037fcbc951f2b80",
      "NPCMonster_W3_Dinosaur_00_Ability.json", "ab21ecdf26796d765c5b1f033eea93eebd41e32bb040c8bddf48f08b76a5a6de",
      "NPCMonster_XP_Minion01_04_Ability.json", "6aa3ad0ea7a46dacfef20bd19c346e4dc53122f0c8706494046137c93c90c055");
  private static final String PROP = "d1893670008efaadb061e9a66dd9dea218f3a87f08d718cb09ac47b5418c924b";

  static Retcode check(Player player, WolfBroAmmoRules rules) {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var realRoot = root.toRealPath();
      for (var pin : ABILITIES.entrySet()) {
        var path = root.resolve("Config/ConfigAdventureAbility/NpcMonster/" + pin.getKey());
        if (!Files.isRegularFile(path) || Files.size(path) > 4*1024*1024
            || !path.toRealPath().startsWith(realRoot)) return Retcode.PLAYER_DATA_ERROR;
        var value = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!pin.getValue().equals(TimelineActivityRules.fingerprint(value))) return Retcode.PLAYER_DATA_ERROR;
      }
      var table = rules.originals.get("ExcelOutput/MazeProp.json").getAsJsonArray();
      var rows = table.asList().stream().map(JsonElement::getAsJsonObject)
          .filter(row -> row.get("ID").getAsInt() == 103055).toList();
      if (rows.size() != 1 || !PROP.equals(TimelineActivityRules.fingerprint(rows.getFirst())))
        return Retcode.PLAYER_DATA_ERROR;
      var expected = ResourceLoader.getGson().fromJson(rows.getFirst(), PropExcel.class);
      expected.onLoad();
      var loaded = GameData.getPropExcelMap().get(103055);
      if (!equal(expected, loaded)) return Retcode.PLAYER_DATA_ERROR;
      var scene = player.getScene();
      for (var group : rules.groups.values()) {
        if (!rules.level.GroupIDList().contains(group.getId()) || !scene.getLoadedGroups().contains(group.getId())) continue;
        for (var source : group.getPropList()) {
          if (source.getPropID() != 103055 || source.isIsDelete() || source.isIsClientOnly()
              || !source.isLoadOnInitial()) continue;
          var matches = scene.getEntities().values().stream().filter(EntityProp.class::isInstance)
              .map(EntityProp.class::cast).filter(prop -> prop.getGroupId()==group.getId()
                  && prop.getInstId()==source.getID()).toList();
          if (matches.size() == 1) {
            var actual=matches.getFirst();
            if (actual.getScene()==scene && actual.getEntityId()>0 && actual.getPropId()==103055
                && actual.getExcel()==loaded && equal(actual.getPropInfo(),source)
                && WolfBroAmmoRules.same(actual.getPos(),source.getPos())) return Retcode.SUCC;
          }
        }
      }
      return Retcode.REQ_PARA_INVALID;
    } catch (Exception invalidSource) { return Retcode.PLAYER_DATA_ERROR; }
  }

  private static boolean equal(Object expected, Object actual) {
    var gson=ResourceLoader.getGson();
    return expected!=null && actual!=null && gson.toJsonTree(expected).equals(gson.toJsonTree(actual));
  }
}
