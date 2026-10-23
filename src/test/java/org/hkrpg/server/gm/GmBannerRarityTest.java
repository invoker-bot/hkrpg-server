package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.gacha.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.hkrpg.server.gacha.GachaRarityResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GmBannerRarityTest {
  @TempDir Path directory;

  @Test void currentStandardDisplayUsesRealRarityWithoutRewritingTheOperatorFile() throws Exception {
    try (var resources = new GachaRarityResources(); var in = getClass().getResourceAsStream("/defaults/Banners.json")) {
      byte[] original = Objects.requireNonNull(in).readAllBytes();
      var path = directory.resolve("Banners.json"); Files.write(path, original);
      var rows = new Gson().fromJson(new String(original, java.nio.charset.StandardCharsets.UTF_8), GachaBanner[].class);
      var service = new GachaService(null) { @Override public void watch() {} };
      service.replaceBanners(Arrays.asList(rows));
      var normal = new GmBanners(directory, service, Clock.systemUTC()).current(id -> "资源 #" + id).getFirst();
      assertEquals(1001, normal.id());
      assertTrue(normal.up5().stream().allMatch(i -> GameData.getItemExcelMap().get(i.id()).getRarityNum() == 5));
      assertTrue(normal.up4().stream().allMatch(i -> GameData.getItemExcelMap().get(i.id()).getRarityNum() == 4));
      assertTrue(normal.up4().stream().anyMatch(i -> i.id() == 1001));
      assertTrue(normal.up4().stream().anyMatch(i -> i.id() == 1002));
      assertArrayEquals(original, Files.readAllBytes(path));
      assertFalse(Files.exists(directory.resolve("gm-banner-backups")));
    }
  }
}
