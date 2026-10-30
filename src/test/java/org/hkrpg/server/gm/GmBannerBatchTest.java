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

class GmBannerBatchTest {
  @TempDir Path directory;
  final Gson json = new Gson();
  final Clock clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC);
  final GachaService service = new GachaService(null) { @Override public void watch() {} };
  GmBanners store;
  @BeforeEach void setup() throws Exception {
    store = new GmBanners(directory, service, clock);
    save("[{\"id\":1001,\"gachaType\":\"Normal\",\"rateUpItems5\":[],\"rateUpItems4\":[],\"operatorNote\":\"keep\"},"
        + "{\"id\":2001,\"gachaType\":\"AvatarUp\",\"beginTime\":1,\"endTime\":2000000000,\"rateUpItems5\":[1102],\"rateUpItems4\":[1105,1106,1109]}]");
  }
  void save(String content) throws Exception {
    Files.writeString(directory.resolve("Banners.json"), content);
    service.replaceBanners(json.fromJson(content,new com.google.gson.reflect.TypeToken<List<GachaBanner>>(){}.getType()));
  }
  Operation op(String action, String fields) {
    return json.fromJson("{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\""+action+"\""+fields+"}",Operation.class);
  }
  JsonArray rows() throws Exception {return JsonParser.parseString(Files.readString(directory.resolve("Banners.json"))).getAsJsonArray();}

  @Test void batchStopEnablePersistsOneAtomicChangeAndKeepsWindowsAndExtensions() throws Exception {
    store.setEnabled(op("banner-batch-stop",",\"bannerIds\":[1001,2001]"));
    assertTrue(service.getGachaBanners().get(1001).isDisabled());assertTrue(service.getGachaBanners().get(2001).isDisabled());
    try(var backups=Files.list(directory.resolve("gm-banner-backups"))){assertEquals(1,backups.count());}
    store.setEnabled(op("banner-batch-enable",",\"bannerIds\":[2001,1001]"));
    assertFalse(service.getGachaBanners().get(1001).isDisabled());assertFalse(service.getGachaBanners().get(2001).isDisabled());
    assertEquals(1,service.getGachaBanners().get(2001).getBeginTime());
    assertEquals(2000000000,service.getGachaBanners().get(2001).getEndTime());
    assertEquals("keep",rows().get(0).getAsJsonObject().get("operatorNote").getAsString());
  }

  @Test void configuredVersionOrderInterleavesCharacterAndConeAndSurvivesCustomizedUp() throws Exception {
    var rows=rows();
    for (int id:List.of(3005,2005,3002,2002,3001)) {
      var template=store.history().stream().filter(b->b.id()==id).findFirst().orElseThrow();
      var row=new JsonObject();row.addProperty("id",id);row.addProperty("gachaType",template.type());
      row.addProperty("endTime",2000000000);row.add("rateUpItems5",json.toJsonTree(template.up5().stream().map(BannerItem::id).toList()));
      row.add("rateUpItems4",json.toJsonTree(template.up4().stream().map(BannerItem::id).toList()));rows.add(row);
    }
    rows.get(1).getAsJsonObject().add("rateUpItems4",json.toJsonTree(List.of(1001)));
    save(rows.toString());
    var current=store.current(id->"item"+id);
    assertEquals(List.of(1001,2001,3001,2002,3002,2005,3005),current.stream().map(CurrentBannerRow::id).toList());
    assertEquals("1.0",current.stream().filter(b->b.id()==2001).findFirst().orElseThrow().version());
  }

  @Test void completionAddsNewbieAndLaterTemplatesDisabledWithoutChangingExistingRows() throws Exception {
    var before=rows();store.completeCatalog(op("banner-complete",""));
    assertEquals(280,rows().size());
    assertEquals(before.get(0),rows().get(0));assertEquals(before.get(1),rows().get(1));
    assertEquals(GachaType.Newbie,service.getGachaBanners().get(4001).getGachaType());
    assertTrue(service.getGachaBanners().get(4001).isDisabled());
    var latest=store.history().stream().filter(b->b.version().equals("4.6")).findFirst().orElseThrow();
    assertTrue(service.getGachaBanners().get(latest.id()).isDisabled());
    String saved=Files.readString(directory.resolve("Banners.json"));store.completeCatalog(op("banner-complete",""));
    assertEquals(saved,Files.readString(directory.resolve("Banners.json")));
  }

  @Test void firstEnableSchedulesOnlyPendingTemplatesAndRestartAndReplayDoNotExtendThem() throws Exception {
    store.completeCatalog(op("banner-complete",""));
    int latest=store.history().stream().filter(b->b.version().equals("4.6")).findFirst().orElseThrow().id();
    var backend=new GmJournalTest.Backend(){@Override public Result execute(Operation operation){return store.setEnabled(operation);}};
    var operation=op("banner-batch-enable",",\"bannerIds\":[4001,"+latest+"],\"durationDays\":7");
    new GmJournal(directory,clock).execute(operation,backend);
    var saved=service.getGachaBanners().get(latest);
    assertEquals(clock.instant().getEpochSecond(),saved.getBeginTime());
    assertEquals(7*86400,saved.getEndTime()-saved.getBeginTime());assertFalse(saved.isGmTemplatePending());
    assertFalse(service.getGachaBanners().get(4001).isDisabled());
    store.setEnabled(op("banner-batch-stop",",\"bannerIds\":[4001,"+latest+"]"));
    var before=Files.readString(directory.resolve("Banners.json"));
    service.replaceBanners(json.fromJson(before,new com.google.gson.reflect.TypeToken<List<GachaBanner>>(){}.getType()));
    new GmJournal(directory,Clock.offset(clock,Duration.ofDays(2))).execute(operation,backend);
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));
    assertTrue(service.getGachaBanners().get(latest).isDisabled());
  }

  @Test void missingOrExpiredTargetMakesEntireBatchLeaveDiskAndMemoryUntouched() throws Exception {
    var before=Files.readString(directory.resolve("Banners.json"));
    assertEquals(409,assertThrows(GmError.class,()->store.setEnabled(op("banner-batch-stop",",\"bannerIds\":[1001,987654]"))).status);
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));assertFalse(service.getGachaBanners().get(1001).isDisabled());
    store.setEnabled(op("banner-batch-stop",",\"bannerIds\":[1001,2001]"));
    before=Files.readString(directory.resolve("Banners.json"));
    var later=new GmBanners(directory,service,Clock.fixed(Instant.ofEpochSecond(2000000001),ZoneOffset.UTC));
    assertEquals(409,assertThrows(GmError.class,()->later.setEnabled(op("banner-batch-enable",",\"bannerIds\":[1001,2001]"))).status);
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));assertTrue(service.getGachaBanners().get(1001).isDisabled());
  }

  @Test void backupFailureLeavesEverySelectedPoolUnchanged() throws Exception {
    Files.writeString(directory.resolve("gm-banner-backups"),"operator-owned-file");
    var before=Files.readString(directory.resolve("Banners.json"));
    assertEquals(503,assertThrows(GmError.class,()->store.setEnabled(op("banner-batch-stop",",\"bannerIds\":[1001,2001]"))).status);
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));
    assertTrue(service.getGachaBanners().values().stream().noneMatch(GachaBanner::isDisabled));
  }

  @Test void batchesValidateIdentityCardinalityDuplicatesAndDurationBeforeWriting() throws Exception {
    var before=Files.readString(directory.resolve("Banners.json"));
    for(var fields:List.of("",",\"bannerIds\":[]",",\"bannerIds\":[1001,1001]",",\"bannerIds\":[null]",
        ",\"bannerIds\":[0]",",\"bannerIds\":[1001],\"durationDays\":0",",\"bannerIds\":[1001],\"durationDays\":366",
        ",\"bannerIds\":[1001],\"uid\":10001",",\"bannerIds\":[1001],\"bannerId\":1001"))
      assertEquals(400,assertThrows(GmError.class,()->store.setEnabled(op("banner-batch-enable",fields))).status);
    String tooMany=",\"bannerIds\":"+json.toJson(java.util.stream.IntStream.range(1,502).boxed().toList());
    assertEquals(400,assertThrows(GmError.class,()->store.setEnabled(op("banner-batch-enable",tooMany))).status);
    assertEquals(before,Files.readString(directory.resolve("Banners.json")));
    assertFalse(Files.exists(directory.resolve("gm-banner-backups")));
  }

  @Test void rerunSchedulesPendingTemplateAndExistingCustomExtensionSurvivesCompletion() throws Exception {
    store.completeCatalog(op("banner-complete",""));
    var target=store.history().stream().filter(b->b.version().equals("4.6")).findFirst().orElseThrow();
    store.rerun(op("banner-rerun",",\"bannerKey\":\""+target.key()+"\",\"durationDays\":3"));
    assertFalse(service.getGachaBanners().get(target.id()).isGmTemplatePending());
    assertFalse(service.getGachaBanners().get(target.id()).isDisabled());
    assertEquals(3*86400,service.getGachaBanners().get(target.id()).getEndTime()-service.getGachaBanners().get(target.id()).getBeginTime());
    assertEquals("keep",rows().get(0).getAsJsonObject().get("operatorNote").getAsString());
  }
}
