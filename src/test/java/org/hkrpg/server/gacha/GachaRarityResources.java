package org.hkrpg.server.gacha;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import java.io.*;
import java.util.*;

/** Minimal real 4.6 item projection; restore shared maps after each test. */
public final class GachaRarityResources implements AutoCloseable {
  private final Map<Integer, ItemExcel> items = new HashMap<>();
  private final Map<Integer, AvatarExcel> avatars = new HashMap<>();
  private final Gson json = new Gson();

  public GachaRarityResources() throws Exception {
    try (var input = getClass().getResourceAsStream("/gacha-rarity-items-4.6.json")) {
      var rows = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(input), java.nio.charset.StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonArray("items");
      for (var row : rows) {
        var item = json.fromJson(row, ItemExcel.class);
        int id = item.getId();
        items.put(id, GameData.getItemExcelMap().get(id));
        if (item.getItemMainType() == emu.lunarcore.game.enums.ItemMainType.AvatarCard) {
          avatars.put(id, GameData.getAvatarExcelMap().get(id));
          GameData.getAvatarExcelMap().add(json.fromJson("{\"AvatarID\":"+id+",\"MaxRank\":6}", AvatarExcel.class));
          int shard = id + 10000;
          items.put(shard, GameData.getItemExcelMap().get(shard));
          GameData.getItemExcelMap().add(json.fromJson("{\"ID\":" + shard
              + ",\"ItemMainType\":\"Material\",\"ItemSubType\":\"Eidolon\",\"PileLimit\":999}", ItemExcel.class));
        } else {
          item.setEquipmentExcel(json.fromJson("{\"EquipmentID\":"+id+",\"MaxRank\":5}", EquipmentExcel.class));
        }
        GameData.getItemExcelMap().add(item);
      }
      for (int id : List.of(251, 252)) {
        items.put(id, GameData.getItemExcelMap().get(id));
        GameData.getItemExcelMap().add(json.fromJson("{\"ID\":" + id
            + ",\"ItemMainType\":\"Material\",\"ItemSubType\":\"Material\",\"PileLimit\":999999}", ItemExcel.class));
      }
    }
  }

  public void close() {
    items.forEach((id, old) -> { if (old == null) GameData.getItemExcelMap().keySet().remove(id); else GameData.getItemExcelMap().add(old); });
    avatars.forEach((id, old) -> { if (old == null) GameData.getAvatarExcelMap().keySet().remove(id); else GameData.getAvatarExcelMap().add(old); });
  }
}
