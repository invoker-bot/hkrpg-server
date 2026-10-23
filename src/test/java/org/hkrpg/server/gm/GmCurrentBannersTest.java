package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.gacha.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GmCurrentBannersTest {
  @TempDir Path directory;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
  final GachaService service = new GachaService(null) { @Override public void watch() {} };
  GmBanners store;
  final Gson json = new Gson();

  @BeforeEach void start() throws Exception {
    store = new GmBanners(directory, service, clock);
    save("[{\"id\":1001,\"gachaType\":\"Normal\",\"beginTime\":1,\"endTime\":2,"
        + "\"rateUpItems5\":[],\"rateUpItems4\":[],\"operatorNote\":\"保留\"},"
        + "{\"id\":4001,\"gachaType\":\"Newbie\",\"beginTime\":1,\"endTime\":2,"
        + "\"rateUpItems5\":[],\"rateUpItems4\":[]},"
        + "{\"id\":99999,\"gachaType\":\"AvatarUp\",\"beginTime\":1,\"endTime\":2000000000,"
        + "\"rateUpItems5\":[999998],\"rateUpItems4\":[]}]");
  }

  void save(String text) throws Exception {
    Files.writeString(directory.resolve("Banners.json"), text);
    service.replaceBanners(json.fromJson(text, new com.google.gson.reflect.TypeToken<List<GachaBanner>>() {}.getType()));
  }

  Operation op(String action, int id) {
    return json.fromJson("{\"requestId\":\"" + UUID.randomUUID() + "\",\"action\":\"" + action
        + "\",\"bannerId\":" + id + "}", Operation.class);
  }

  List<CurrentBannerRow> current() { return store.current(id -> "资源 #" + id); }

  @Test void currentListIncludesActualPermanentNewbieAndNonArchiveCustomPools() throws Exception {
    var before = Files.readString(directory.resolve("Banners.json"));
    var rows = current();
    assertEquals(List.of(1001,4001,99999), rows.stream().map(CurrentBannerRow::id).toList());
    assertTrue(rows.get(0).permanent()); assertTrue(rows.get(1).permanent());
    assertEquals("常驻跃迁", rows.get(0).name()); assertEquals("新手跃迁", rows.get(1).name());
    assertTrue(rows.stream().allMatch(b -> b.state().equals("active") && b.canStop() && !b.canEnable()));
    assertEquals("自定义卡池 #99999", rows.get(2).name());
    assertEquals(List.of(), rows.get(2).up5());
    assertFalse(rows.get(2).pullable());
    assertTrue(rows.get(2).poolIssues().stream().anyMatch(message -> message.contains("999998")));
    assertEquals("", rows.get(2).historyKey());
    assertEquals(before, Files.readString(directory.resolve("Banners.json")));
    assertFalse(Files.exists(directory.resolve("gm-banner-backups")));
  }

  @Test void stopPersistsWithoutChangingScheduleContentsOrOtherPoolsAndReloadRemainsStopped() throws Exception {
    String before = Files.readString(directory.resolve("Banners.json"));
    var stop = op("banner-stop",1001);
    assertEquals("completed", store.setEnabled(stop).state());
    var stopped = service.getGachaBanners().get(1001);
    assertTrue(stopped.isDisabled()); assertFalse(stopped.isActive(clock.instant().getEpochSecond()));
    assertEquals(1, stopped.getBeginTime()); assertEquals(2, stopped.getEndTime());
    assertTrue(service.getGachaBanners().get(4001).isActive(clock.instant().getEpochSecond()));
    assertEquals(before,Files.readString(directory.resolve("gm-banner-backups/Banners-"+stop.requestId()+".json")));
    var saved = JsonParser.parseString(Files.readString(directory.resolve("Banners.json"))).getAsJsonArray();
    assertEquals("保留", saved.get(0).getAsJsonObject().get("operatorNote").getAsString());
    var reloaded = new GachaService(null) { @Override public void watch() {} };
    reloaded.replaceBanners(json.fromJson(saved,new com.google.gson.reflect.TypeToken<List<GachaBanner>>() {}.getType()));
    assertFalse(reloaded.getGachaBanners().get(1001).isActive(clock.instant().getEpochSecond()));
    assertEquals("已停止",current().getFirst().status());
    assertTrue(current().getFirst().canEnable());
  }

  @Test void enablingRestoresPermanentAndLimitedPoolsWithoutExtendingTheirSchedule() {
    for (int id : List.of(1001,4001,99999)) {
      var before=service.getGachaBanners().get(id);
      int begin=before.getBeginTime(), end=before.getEndTime();
      store.setEnabled(op("banner-stop",id));store.setEnabled(op("banner-enable",id));
      var after=service.getGachaBanners().get(id);
      assertFalse(after.isDisabled());assertTrue(after.isActive(clock.instant().getEpochSecond()));
      assertEquals(begin,after.getBeginTime());assertEquals(end,after.getEndTime());
    }
  }

  @Test void repeatedStopAndEnableDoNotWriteAnotherBackupOrRewriteTheFile() throws Exception {
    store.setEnabled(op("banner-stop",1001));
    String stopped=Files.readString(directory.resolve("Banners.json"));
    store.setEnabled(op("banner-stop",1001));
    assertEquals(stopped,Files.readString(directory.resolve("Banners.json")));
    try(var files=Files.list(directory.resolve("gm-banner-backups"))){assertEquals(1,files.count());}
    store.setEnabled(op("banner-enable",1001));
    String enabled=Files.readString(directory.resolve("Banners.json"));
    store.setEnabled(op("banner-enable",1001));
    assertEquals(enabled,Files.readString(directory.resolve("Banners.json")));
    try(var files=Files.list(directory.resolve("gm-banner-backups"))){assertEquals(2,files.count());}
  }

  @Test void missingTargetOrExpiredReenableCannotWriteFiles() throws Exception {
    String before=Files.readString(directory.resolve("Banners.json"));
    assertEquals(409,assertThrows(GmError.class,()->store.setEnabled(op("banner-stop",1234))).status);
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));
    store.setEnabled(op("banner-stop",99999));
    String stopped=Files.readString(directory.resolve("Banners.json"));
    var later=new GmBanners(directory,service,Clock.fixed(Instant.ofEpochSecond(2000000001),ZoneOffset.UTC));
    assertFalse(later.current(id->"资源").stream().filter(b->b.id()==99999).findFirst().orElseThrow().canEnable());
    assertEquals(409,assertThrows(GmError.class,()->later.setEnabled(op("banner-enable",99999))).status);
    assertEquals(stopped,Files.readString(directory.resolve("Banners.json")));
  }

  @Test void rerunReenablesAStoppedHistoricalPool() {
    var rerun=json.fromJson("{\"requestId\":\""+UUID.randomUUID()
        +"\",\"action\":\"banner-rerun\",\"bannerKey\":\"1.0-2001\",\"durationDays\":7}",Operation.class);
    store.rerun(rerun);store.setEnabled(op("banner-stop",2001));
    assertEquals("已停止",store.history().stream().filter(b->b.id()==2001).findFirst().orElseThrow().status());
    store.rerun(json.fromJson(json.toJson(rerun).replace(rerun.requestId(),UUID.randomUUID().toString()),Operation.class));
    assertFalse(service.getGachaBanners().get(2001).isDisabled());
    assertEquals("正在开放",store.history().stream().filter(b->b.id()==2001).findFirst().orElseThrow().status());
  }

  @Test void replayingAnOldStopAfterEnableAndJournalRestartDoesNotStopAgain() throws Exception {
    var backend=new GmJournalTest.Backend() {
      @Override public Result execute(Operation operation) { count.incrementAndGet();return store.setEnabled(operation); }
    };
    var stop=op("banner-stop",1001);
    new GmJournal(directory,clock).execute(stop,backend);
    new GmJournal(directory,clock).execute(op("banner-enable",1001),backend);
    String before=Files.readString(directory.resolve("Banners.json"));
    new GmJournal(directory,clock).execute(stop,backend);
    assertEquals(2,backend.count.get());assertFalse(service.getGachaBanners().get(1001).isDisabled());
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));
  }

  @Test void failedBackupCannotStopAnOpenPool() throws Exception {
    Files.writeString(directory.resolve("gm-banner-backups"),"operator file");
    String before=Files.readString(directory.resolve("Banners.json"));
    assertEquals(503,assertThrows(GmError.class,()->store.setEnabled(op("banner-stop",1001))).status);
    assertFalse(service.getGachaBanners().get(1001).isDisabled());
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));
  }
}
