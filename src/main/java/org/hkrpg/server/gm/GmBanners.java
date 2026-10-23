package org.hkrpg.server.gm;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import emu.lunarcore.game.gacha.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;

/** Immutable archive templates; only a copied row and its new window enter the live table. */
final class GmBanners {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private static final List<Template> ARCHIVE = readArchive();
  private final Path directory;
  private final GachaService service;
  private final Clock clock;

  private record Template(String key, String version, String phase, String name, int id,
      GachaType type, List<BannerItem> up5, List<BannerItem> up4, boolean replayable,
      String historicalStart, String historicalEnd, String sourceUrl) {}

  GmBanners(Path directory, GachaService service, Clock clock) {
    this.directory = directory;
    this.service = service;
    this.clock = clock;
  }

  List<BannerRow> history() {
    synchronized (service) {
      long now = clock.instant().getEpochSecond();
      return ARCHIVE.stream().map(t -> {
        var live = service.getGachaBanners().get(t.id());
        String status = "未配置";
        if (live != null) {
          boolean matches = live.getGachaType() == t.type()
              && sameItems(live.getRateUpItems5(), t.up5())
              && sameItems(live.getRateUpItems4(), t.up4());
          status = !matches ? "当前为其他配置" : live.isActive(now) ? "正在开放"
              : now < live.getBeginTime() ? "尚未开放" : "已到期";
        }
        return new BannerRow(t.key(), t.version(), t.phase(), t.name(), t.id(), t.type().name(),
            t.up5(), t.up4(), t.replayable(), status,
            live == null ? 0 : live.getBeginTime(), live == null ? 0 : live.getEndTime(),
            t.historicalStart(), t.historicalEnd(), t.sourceUrl());
      }).toList();
    }
  }

  Result rerun(Operation operation) {
    GmValidation.validate(operation);
    var template = ARCHIVE.stream().filter(t -> t.key().equals(operation.bannerKey())).findFirst()
        .orElseThrow(() -> new GmError(400, "历史卡池不存在，请刷新列表。"));
    if (!template.replayable()) throw new GmError(400, "该卡池尚缺已核对的四星 UP 模板，暂不能复刻。");
    int days = operation.durationDays() == null ? 30 : operation.durationDays();
    long begin = clock.instant().getEpochSecond(), end = begin + days * 86400L;
    if (begin < 0 || end > Integer.MAX_VALUE)
      throw new GmError(400, "复刻时间超出当前协议支持范围。");
    synchronized (service) {
      Path file = directory.resolve("Banners.json"), temp = null;
      try {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 2_000_000)
          throw new GmError(503, "当前卡池配置不可读取，请检查数据目录。");
        String previous = Files.readString(file, StandardCharsets.UTF_8);
        JsonArray rows;
        try {
          rows = JsonParser.parseString(previous).getAsJsonArray();
          parse(rows);
        } catch (RuntimeException e) {
          throw new GmError(503, "当前卡池配置无效，未执行复刻，请先修正配置。");
        }
        var replacement = new JsonObject();
        replacement.addProperty("id", template.id());
        replacement.addProperty("gachaType", template.type().name());
        replacement.addProperty("beginTime", begin);
        replacement.addProperty("endTime", end);
        replacement.add("rateUpItems5", JSON.toJsonTree(ids(template.up5())));
        replacement.add("rateUpItems4", JSON.toJsonTree(ids(template.up4())));
        replacement.addProperty("eventChance", 50);
        boolean found = false;
        for (int i = 0; i < rows.size(); i++) {
          if (rows.get(i).getAsJsonObject().get("id").getAsInt() == template.id()) {
            // Preserve operator extension fields while restoring the archive's UP pool.
            var merged = rows.get(i).getAsJsonObject();
            replacement.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
            found = true;
            break;
          }
        }
        if (!found) rows.add(replacement);
        var candidate = parse(rows);
        Path backups = directory.resolve("gm-banner-backups");
        Files.createDirectories(backups);
        Files.writeString(backups.resolve("Banners-" + operation.requestId() + ".json"), previous,
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        temp = Files.createTempFile(directory, ".gm-banners-", ".tmp");
        Files.writeString(temp, JSON.toJson(rows) + "\n", StandardCharsets.UTF_8);
        // The watcher and all pulls share the same monitor; no half-installed live table is visible.
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        temp = null;
        service.replaceBanners(candidate);
        return new Result("completed", List.of(
            "已复刻 " + template.name() + " · " + template.version() + " · #" + template.id(),
            "开放 " + days + " 天，截止 " + java.time.Instant.ofEpochSecond(end),
            "全服配置已加载，重新打开游戏内跃迁页面可查询。"));
      } catch (IOException e) {
        throw new GmError(503, "卡池配置保存失败，未执行复刻，请检查数据目录。 ");
      } finally {
        if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
      }
    }
  }

  private static List<GachaBanner> parse(JsonArray rows) {
    List<GachaBanner> result = JSON.fromJson(rows, new TypeToken<List<GachaBanner>>() {}.getType());
    GachaService.validateBanners(result);
    return result;
  }

  private static int[] ids(List<BannerItem> items) {
    return items.stream().mapToInt(BannerItem::id).toArray();
  }

  private static boolean sameItems(int[] actual, List<BannerItem> expected) {
    if (actual == null) return false;
    int[] actualIds = actual.clone(), expectedIds = ids(expected);
    Arrays.sort(actualIds);
    Arrays.sort(expectedIds);
    return Arrays.equals(actualIds, expectedIds);
  }

  private static List<Template> readArchive() {
    try (var input = GmBanners.class.getResourceAsStream("/gm/banner-history.json")) {
      if (input == null) throw new IOException("Missing banner archive");
      var root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
      var names = root.getAsJsonObject("itemNames");
      var result = new ArrayList<Template>();
      var keys = new HashSet<String>();
      for (var element : root.getAsJsonArray("banners")) {
        var row = element.getAsJsonObject();
        String version = row.get("version").getAsString();
        int id = row.get("id").getAsInt();
        String key = version + "-" + id;
        var up5 = items(row.getAsJsonArray("rateUpItems5"), names);
        var up4 = items(row.getAsJsonArray("rateUpItems4"), names);
        var type = GachaType.valueOf(row.get("gachaType").getAsString());
        String sourceUrl = row.get("sourceUrl").getAsString();
        if (!keys.add(key) || up5.size() != 1 || up4.size() != 3
            || !sourceUrl.matches("https://www\\.hoyolab\\.com/article/[0-9]+")
            || !Set.of(GachaType.AvatarUp, GachaType.WeaponUp).contains(type))
          throw new IOException("Invalid archive row: " + key);
        result.add(new Template(key, version, row.get("phase").getAsString(),
            row.get("name").getAsString(), id, type, up5, up4, row.get("replayable").getAsBoolean(),
            row.get("historicalStart").getAsString(), row.get("historicalEnd").getAsString(), sourceUrl));
      }
      result.sort(Comparator.comparingInt((Template t) -> {
        var parts = t.version().split("\\.");
        return Integer.parseInt(parts[0]) * 100 + Integer.parseInt(parts[1]);
      }).reversed().thenComparingInt(Template::id));
      return List.copyOf(result);
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Invalid bundled GM banner archive", e);
    }
  }

  private static List<BannerItem> items(JsonArray values, JsonObject names) {
    var result = new ArrayList<BannerItem>();
    for (var value : values) {
      int id = value.getAsInt();
      result.add(new BannerItem(id, names.get(Integer.toString(id)).getAsString()));
    }
    return List.copyOf(result);
  }
}
