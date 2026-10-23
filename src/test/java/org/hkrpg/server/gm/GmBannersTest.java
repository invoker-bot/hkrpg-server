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

class GmBannersTest {
  @TempDir Path directory;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC);
  final GachaService service = new GachaService(null) { @Override public void watch() {} };
  GmBanners store;
  final String original = "[{\"id\":1001,\"gachaType\":\"Normal\",\"beginTime\":0,\"endTime\":0,"
      + "\"rateUpItems5\":[],\"rateUpItems4\":[],\"operatorNote\":\"保留原配置\"}]";

  @BeforeEach void setup() throws Exception {
    Files.writeString(directory.resolve("Banners.json"), original);
    store = new GmBanners(directory, service, clock);
  }

  Operation rerun(String key, Integer days) {
    return new Gson().fromJson("{\"requestId\":\"" + UUID.randomUUID()
        + "\",\"action\":\"banner-rerun\",\"bannerKey\":\"" + key + "\""
        + (days == null ? "" : ",\"durationDays\":" + days) + "}", Operation.class);
  }

  @Test void bundledArchiveWorksWithoutGitAndEveryTemplateIsComplete() throws Exception {
    var rows = store.history();
    assertEquals(278, rows.size());
    assertEquals(31, rows.stream().map(BannerRow::version).distinct().count());
    assertEquals(278, rows.stream().filter(BannerRow::replayable).count());
    assertEquals(rows.size(), rows.stream().map(BannerRow::key).distinct().count());
    assertTrue(rows.stream().filter(BannerRow::replayable).allMatch(b -> b.up4().size() == 3));
    assertTrue(rows.stream().allMatch(b -> b.up5().size() == 1));
    assertEquals("4.6", rows.getFirst().version());
    assertEquals(original, Files.readString(directory.resolve("Banners.json")));
    assertFalse(Files.exists(directory.resolve("gm-banner-backups")));
  }

  @Test void everyHistoricalResourceHasACompleteOnlineVerifiedUpPool() {
    var rows = store.history();
    assertEquals(278, rows.stream().filter(BannerRow::replayable).count());
    assertTrue(rows.stream().allMatch(b -> b.up4().size() == 3));
    assertEquals(List.of(1201,1214,1312), rows.stream().filter(b -> b.id()==2139)
        .findFirst().orElseThrow().up4().stream().map(BannerItem::id).sorted().toList());
    assertEquals(List.of(21000,21011,21044), rows.stream().filter(b -> b.id()==3139)
        .findFirst().orElseThrow().up4().stream().map(BannerItem::id).sorted().toList());
  }

  @Test void currentStatusMatchesTheUpCombinationWithoutReorderingOperatorConfig() throws Exception {
    int now = Math.toIntExact(clock.instant().getEpochSecond());
    var banner = new GachaBanner().createBanner(2001, GachaType.AvatarUp,
        now-1, now+86400,
        new int[]{1102}, new int[]{1109,1105,1106});
    service.replaceBanners(List.of(banner));
    assertEquals("正在开放", store.history().stream().filter(b -> b.id()==2001).findFirst().orElseThrow().status());
    assertArrayEquals(new int[]{1109,1105,1106}, banner.getRateUpItems4());
    service.replaceBanners(List.of(new GachaBanner().createBanner(2001, GachaType.AvatarUp,
        now-1, now+86400,
        new int[]{1102}, new int[]{1109,1105,1001})));
    assertEquals("当前为其他配置", store.history().stream().filter(b -> b.id()==2001).findFirst().orElseThrow().status());
    assertEquals(original, Files.readString(directory.resolve("Banners.json")));
  }

  @Test void threePhaseAndVersionLongBannersKeepTheirRealHistoricalPeriods() {
    var rows = store.history();
    assertEquals("第三期",rows.stream().filter(b -> b.id()==2103).findFirst().orElseThrow().phase());
    for (int id : new int[]{2113,2128,2139})
      assertEquals("全期",rows.stream().filter(b -> b.id()==id).findFirst().orElseThrow().phase());
    assertEquals("下半",rows.stream().filter(b -> b.id()==2141).findFirst().orElseThrow().phase());
  }

  @Test void officialNoticesCorrectSixInheritedUpPoolErrors() {
    var expected=Map.of(
        2002,List.of(1001,1202,1206),3002,List.of(21002,21003,21011),
        3012,List.of(21003,21005,21015),3073,List.of(21013,21046,21047),
        3074,List.of(21013,21046,21047),2099,List.of(1001,1111,1301));
    for(var row:store.history())if(expected.containsKey(row.id()))
      assertEquals(expected.get(row.id()),row.up4().stream().map(BannerItem::id).sorted().toList(),"banner "+row.id());
  }

  @Test void rerunAddsAnExactTemplateAndDurationWithBackupAndImmediateReload() throws Exception {
    var op = rerun("1.0-2001", 7);
    assertEquals("completed", store.rerun(op).state());
    var live = service.getGachaBanners().get(2001);
    assertArrayEquals(new int[]{1102}, live.getRateUpItems5());
    assertArrayEquals(new int[]{1105,1106,1109}, live.getRateUpItems4());
    assertEquals(clock.instant().getEpochSecond(), live.getBeginTime());
    assertEquals(clock.instant().getEpochSecond()+7*86400, live.getEndTime());
    assertEquals("正在开放", store.history().stream().filter(b -> b.id()==2001).findFirst().orElseThrow().status());
    assertEquals(original, Files.readString(directory.resolve("gm-banner-backups/Banners-"+op.requestId()+".json")));
    var saved = JsonParser.parseString(Files.readString(directory.resolve("Banners.json"))).getAsJsonArray();
    assertEquals("保留原配置", saved.get(0).getAsJsonObject().get("operatorNote").getAsString());
    assertEquals(2, saved.size());
  }

  @Test void repeatRerunReplacesOneIdAndTheOriginalArchiveStaysImmutable() throws Exception {
    var before = store.history().stream().filter(b -> b.id()==2001).findFirst().orElseThrow();
    store.rerun(rerun("1.0-2001", 1));
    var later = new GmBanners(directory, service, Clock.offset(clock, Duration.ofDays(2)));
    assertEquals("已到期", later.history().stream().filter(b -> b.id()==2001).findFirst().orElseThrow().status());
    later.rerun(rerun("1.0-2001", 365));
    var after = later.history().stream().filter(b -> b.id()==2001).findFirst().orElseThrow();
    assertEquals(before.up4(), after.up4());
    assertEquals(before.up5(), after.up5());
    assertEquals(clock.instant().getEpochSecond()+367*86400, after.endTime());
    assertEquals(2, JsonParser.parseString(Files.readString(directory.resolve("Banners.json"))).getAsJsonArray().size());
  }

  @Test void missingTemplateCannotChangeFilesOrLiveBanners() throws Exception {
    for (var key : List.of("9.9-2999", "4.6-2999"))
      assertEquals(400, assertThrows(GmError.class, () -> store.rerun(rerun(key, 7))).status);
    assertEquals(original, Files.readString(directory.resolve("Banners.json")));
    assertTrue(service.getGachaBanners().isEmpty());
    assertFalse(Files.exists(directory.resolve("gm-banner-backups")));
  }

  @Test void latestPoolCanBeRerunAndExposesTheOriginalAnnouncementAndWindow() {
    var original = store.history().stream().filter(b -> b.id()==2139).findFirst().orElseThrow();
    assertEquals("2026-09-28 更新后",original.historicalStart());
    assertEquals("2026-11-10 15:00:00",original.historicalEnd());
    assertEquals("https://www.hoyolab.com/article/46851682",original.sourceUrl());
    assertEquals("completed",store.rerun(rerun("4.6-2139",9)).state());
    assertEquals(9*86400,service.getGachaBanners().get(2139).getEndTime()-service.getGachaBanners().get(2139).getBeginTime());
    assertEquals(original.historicalStart(),store.history().stream().filter(b -> b.id()==2139).findFirst().orElseThrow().historicalStart());
  }

  @Test void invalidExistingConfigAndBackupFailureKeepTheLastGoodLiveTable() throws Exception {
    store.rerun(rerun("1.0-2001", 7));
    var live = service.getGachaBanners().get(2001);
    Files.writeString(directory.resolve("Banners.json"), "[invalid");
    assertEquals(503, assertThrows(GmError.class, () -> store.rerun(rerun("1.0-2001", 1))).status);
    assertSame(live, service.getGachaBanners().get(2001));
    assertEquals("[invalid", Files.readString(directory.resolve("Banners.json")));
  }

  @Test void failedBackupCannotPublishANewConfiguration() throws Exception {
    Files.writeString(directory.resolve("gm-banner-backups"), "operator-file");
    assertEquals(503, assertThrows(GmError.class, () -> store.rerun(rerun("1.0-2001", 7))).status);
    assertEquals(original, Files.readString(directory.resolve("Banners.json")));
    assertTrue(service.getGachaBanners().isEmpty());
  }

  @Test void duplicateIdsAndInvalidCandidatesCannotClearTheLiveTable() {
    var banner = new GachaBanner().createBanner(2001,GachaType.AvatarUp,1,2,new int[]{1102},new int[]{1105});
    service.replaceBanners(List.of(banner));
    assertThrows(IllegalArgumentException.class, () -> service.replaceBanners(List.of(banner,banner)));
    assertThrows(IllegalArgumentException.class, () -> service.replaceBanners(null));
    assertSame(banner,service.getGachaBanners().get(2001));
  }

  @Test void journalReplayAfterRestartKeepsTheSameWindowAndBackup() throws Exception {
    var backend = new GmJournalTest.Backend() {
      @Override public Result execute(Operation op) { count.incrementAndGet(); return store.rerun(op); }
    };
    var op = rerun("1.0-2001", 7);
    var first = new GmJournal(directory,clock).execute(op,backend);
    var content = Files.readString(directory.resolve("Banners.json"));
    var second = new GmJournal(directory,Clock.offset(clock,Duration.ofDays(1))).execute(op,backend);
    assertEquals(first,second);
    assertEquals(1,backend.count.get());
    assertEquals(content,Files.readString(directory.resolve("Banners.json")));
    try(var files=Files.list(directory.resolve("gm-banner-backups"))) { assertEquals(1,files.count()); }
  }

  @Test void omittedDurationIsThirtyDaysAndProtocolOverflowIsRejected() throws Exception {
    store.rerun(rerun("1.0-2001",null));
    assertEquals(30*86400,service.getGachaBanners().get(2001).getEndTime()-service.getGachaBanners().get(2001).getBeginTime());
    var before=Files.readString(directory.resolve("Banners.json"));
    var future=new GmBanners(directory,service,Clock.fixed(Instant.ofEpochSecond(Integer.MAX_VALUE-10),ZoneOffset.UTC));
    assertEquals(400,assertThrows(GmError.class,()->future.rerun(rerun("1.0-2001",1))).status);
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));
  }

  @Test void concurrentRerunsKeepBothCompleteRows() throws Exception {
    try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first=executor.submit(()->store.rerun(rerun("1.0-2001",7)));
      var second=executor.submit(()->store.rerun(rerun("1.0-3001",9)));
      assertEquals("completed",first.get().state());assertEquals("completed",second.get().state());
    }
    assertEquals(3,service.getGachaBanners().size());
    assertEquals(3,JsonParser.parseString(Files.readString(directory.resolve("Banners.json"))).getAsJsonArray().size());
  }
}
