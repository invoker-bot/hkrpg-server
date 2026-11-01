package emu.lunarcore.game.shop;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Server-wide availability overrides; resource prices and player purchase receipts stay intact. */
public final class ShopAvailability {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  public record GoodsKey(int shopId, int goodsId) {}
  private record Stored(JsonObject document, Set<GoodsKey> disabled, String original) {}
  private final Path directory;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private volatile Set<GoodsKey> disabled = Set.of();

  /** An isolated, in-memory catalog, also used by source-only fixtures. */
  public ShopAvailability() { this.directory = null; }

  public ShopAvailability(Path directory) {
    this.directory = Objects.requireNonNull(directory);
    try { disabled = load().disabled(); }
    catch (IOException e) { throw new IllegalStateException("Cannot load ShopAvailability.json", e); }
  }

  public Set<GoodsKey> snapshot() { return disabled; }

  public boolean isListed(int shopId, int goodsId) {
    return !disabled.contains(new GoodsKey(shopId, goodsId));
  }

  <T> T read(Supplier<T> action) {
    lock.readLock().lock();
    try { return action.get(); }
    finally { lock.readLock().unlock(); }
  }

  /** Validate the whole batch, save atomically, then publish under the purchase write lock. */
  public int setListed(List<GoodsKey> targets, boolean listed, String requestId) throws IOException {
    if (targets == null || targets.isEmpty() || targets.size() > 500
        || new HashSet<>(targets).size() != targets.size())
      throw new IllegalArgumentException("请选择 1–500 个不重复的商品。");
    if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId))
      throw new IllegalArgumentException("请求编号格式不正确。");
    lock.writeLock().lock();
    Path temp = null;
    try {
      for (var target : targets) {
        var shop = target == null ? null : GameData.getShopExcelMap().get(target.shopId());
        if (shop == null || !shop.getGoods().containsKey(target.goodsId()))
          throw new IllegalArgumentException("商品不在当前商店目录中，请刷新列表；本批次未修改。");
      }
      var stored = directory == null ? new Stored(new JsonObject(), disabled, "") : load();
      var candidate = new HashSet<>(stored.disabled());
      int changed = 0;
      for (var target : targets)
        if (listed ? candidate.remove(target) : candidate.add(target)) changed++;
      if (changed == 0) { disabled = Set.copyOf(candidate); return 0; }
      if (directory != null) {
        var rows = new JsonArray();
        candidate.stream().sorted(Comparator.comparingInt(GoodsKey::shopId)
            .thenComparingInt(GoodsKey::goodsId)).forEach(key -> rows.add(JSON.toJsonTree(key)));
        var document = stored.document().deepCopy();
        document.add("disabledGoods", rows);
        Files.createDirectories(directory);
        var backups = directory.resolve("gm-shop-backups");
        Files.createDirectories(backups);
        if (!Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Invalid shop backup directory");
        Files.writeString(backups.resolve("ShopAvailability-" + requestId + ".json"),
            stored.original(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        temp = Files.createTempFile(directory, ".gm-shops-", ".tmp");
        try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
          var buffer = ByteBuffer.wrap((JSON.toJson(document) + "\n").getBytes(StandardCharsets.UTF_8));
          while (buffer.hasRemaining()) channel.write(buffer);
          channel.force(true);
        }
        Files.move(temp, directory.resolve("ShopAvailability.json"),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        temp = null;
      }
      disabled = Set.copyOf(candidate);
      return changed;
    } finally {
      if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
      lock.writeLock().unlock();
    }
  }

  private Stored load() throws IOException {
    var path = directory.resolve("ShopAvailability.json");
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      var document = new JsonObject();
      document.addProperty("version", 1);
      document.add("disabledGoods", new JsonArray());
      return new Stored(document, Set.of(), JSON.toJson(document) + "\n");
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 2_000_000)
      throw new IOException("Invalid shop availability file");
    String original = Files.readString(path, StandardCharsets.UTF_8);
    try {
      var document = JsonParser.parseString(original).getAsJsonObject();
      if (integer(document.get("version")) != 1) throw new IllegalArgumentException("Unknown version");
      var rows = document.getAsJsonArray("disabledGoods");
      if (rows == null || rows.size() > 50_000) throw new IllegalArgumentException("Invalid goods list");
      var keys = new HashSet<GoodsKey>();
      for (var row : rows) {
        var object = row.getAsJsonObject();
        var key = new GoodsKey(integer(object.get("shopId")), integer(object.get("goodsId")));
        if (!keys.add(key)) throw new IllegalArgumentException("Duplicate shop goods");
      }
      return new Stored(document, Set.copyOf(keys), original);
    } catch (RuntimeException e) { throw new IOException("Invalid ShopAvailability.json; kept previous state", e); }
  }

  private static int integer(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected integer");
    int number = value.getAsBigDecimal().intValueExact();
    if (number <= 0) throw new IllegalArgumentException("Expected positive integer");
    return number;
  }
}
