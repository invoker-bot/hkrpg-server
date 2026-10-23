package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import emu.lunarcore.game.gacha.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.hkrpg.server.gm.GmModels.Operation;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GmBannerNoopReloadTest {
  @TempDir Path directory;

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void sameDiskStateStillRefreshesAStaleLoadedTableWithoutRewritingOrBackingUp(boolean enable) throws Exception {
    var json = new Gson();
    String content = "[{\"id\":1001,\"gachaType\":\"Normal\",\"beginTime\":0,\"endTime\":0,"
        + "\"rateUpItems5\":[],\"rateUpItems4\":[],\"disabled\":" + !enable + "}]";
    Files.writeString(directory.resolve("Banners.json"),content);
    var service = new GachaService(null) { @Override public void watch() {} };
    String stale = content.replace("\"disabled\":" + !enable, "\"disabled\":" + enable);
    service.replaceBanners(json.fromJson(stale,new TypeToken<List<GachaBanner>>() {}.getType()));
    var operation=json.fromJson("{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\""
        +(enable?"banner-enable":"banner-stop")+"\",\"bannerId\":1001}",Operation.class);
    new GmBanners(directory,service,Clock.systemUTC()).setEnabled(operation);
    assertEquals(!enable,service.getGachaBanners().get(1001).isDisabled());
    assertEquals(content,Files.readString(directory.resolve("Banners.json")));
    assertFalse(Files.exists(directory.resolve("gm-banner-backups")));
  }
}
