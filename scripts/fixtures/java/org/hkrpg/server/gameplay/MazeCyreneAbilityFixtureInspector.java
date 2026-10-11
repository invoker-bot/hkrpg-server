package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import dev.morphia.mapping.MapperOptions;
import emu.lunarcore.database.codecs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.h2.mvstore.MVStore;

/** Real Morphia reads from normally stopped owned read-only H2; no cast/MP seeding. */
public final class MazeCyreneAbilityFixtureInspector {
  private static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }
  private static String sha(Path file) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    require(directory.startsWith(root.resolve("build/smoke")) && directory.getFileName().toString().startsWith("maze-cyrene-ability-"), "Unowned manual smoke directory");
    String mode = args[1];
    require(Set.of("sequence", "new-session", "restored").contains(mode), "Unknown H2 inspection phase");
    int uid = Integer.parseInt(args[2]), mp = Integer.parseInt(args[3]);
    require(uid == 10001 && mp == (mode.equals("sequence") ? 1 : 0), "Only the declared isolated Player and actual saved MP may be inspected");
    var marker = JsonParser.parseString(Files.readString(directory.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("mazeCyreneAbilityFixture").getAsBoolean()
        && !marker.get("useIDOrPaymentPrewritten").getAsBoolean(), "Missing owned source-only manual fixture marker");
    var config = directory.resolve("config.json");
    var file = directory.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing normally stopped owned H2 data");
    String configBefore = sha(config), databaseBefore = sha(file);
    var mv = new MVStore.Builder().fileName(file.toString()).readOnly().open();
    var server = new MongoServer(new H2Backend(mv));
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var collection = client.getDatabase("hkrpg").getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(collection.countDocuments() == 1 && collection.countDocuments(filter) == 1, "Not exactly one isolated manual Player");
      var before = Objects.requireNonNull(collection.find(filter).first());
      require(before.get("lineupManager", Document.class).getInteger("mp") == mp, "Saved MP differs from actual KCP balance");
      require(!before.get("gameplay", Document.class).containsKey("mazeCasts"), "Native UseIDs or prepaid origins were incorrectly persisted");
      var options = MapperOptions.builder().storeEmpties(true).storeNulls(false)
          .codecProvider(CodecRegistries.fromCodecs(new IntSetCodec(), new IntListCodec(), new Int2IntMapCodec())).build();
      var store = Morphia.createDatastore(client, "hkrpg", options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class, GameAvatar.class);
      var players = new ArrayList<Player>();
      for (int i = 0; i < 3; i++) {
        var player = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
        require(player != null && player.getUid() == uid && player.getEntryId() == 100000301, "Actual Morphia Player restoration failed");
        var field = player.getGameplay().getClass().getDeclaredField("mazeCasts");
        require(Modifier.isTransient(field.getModifiers()) && field.isAnnotationPresent(dev.morphia.annotations.Transient.class), "UseID field lacks Java and Morphia transient boundaries");
        field.setAccessible(true);
        var casts = field.get(player.getGameplay());
        if (casts != null) {
          var ledger = casts.getClass().getDeclaredField("casts"); ledger.setAccessible(true);
          require(((Map<?, ?>) ledger.get(casts)).isEmpty(), "Restored transient origin/UseID ledger is nonempty");
        }
        players.add(player);
      }
      require(players.getFirst() != players.getLast(), "Morphia did not perform three independent Player reads");
      var avatars = client.getDatabase("hkrpg").getCollection("avatars");
      var avatarFilter = Filters.and(Filters.eq("ownerUid", uid), Filters.eq("avatarId", 1415));
      require(avatars.countDocuments(avatarFilter) == 1, "Missing actual saved formal Cyrene avatar");
      var avatarBefore = Objects.requireNonNull(avatars.find(avatarFilter).first());
      require(!avatarBefore.containsKey("buffs") && !avatarBefore.containsKey("mazeCastMetadata"), "Scene buff or modifier metadata persisted incorrectly");
      var restored = new ArrayList<GameAvatar>();
      for (int i = 0; i < 3; i++) {
        var avatar = store.find(GameAvatar.class).filter(dev.morphia.query.filters.Filters.eq("ownerUid", uid),
            dev.morphia.query.filters.Filters.eq("avatarId", 1415)).first();
        require(avatar != null && avatar.getAvatarId() == 1415 && avatar.getBuffs().isEmpty()
            && avatar.getMazeCastMetadata() == null, "Restored Cyrene has a persisted temporary buff or modifier");
        var transientBuffs = GameAvatar.class.getDeclaredField("buffs");
        require(Modifier.isTransient(transientBuffs.getModifiers()), "Scene buff field lost its Java transient boundary");
        restored.add(avatar);
      }
      require(restored.getFirst() != restored.getLast() && avatarBefore.equals(avatars.find(avatarFilter).first()),
          "Read-only independent Avatar restoration mutated its document");
      require(before.equals(collection.find(filter).first()), "Read-only H2 inspection mutated the Player document");
      Files.writeString(directory.resolve("saved-player-" + mode + ".json"), before.toJson(), StandardOpenOption.CREATE_NEW);
    } finally { server.shutdownNow(); }
    require(databaseBefore.equals(sha(file)) && configBefore.equals(sha(config)), "Read-only H2 inspection changed database/config bytes");
    var report = new LinkedHashMap<String, Object>();
    report.putAll(Map.of("passed", true, "uid", uid, "mode", mode, "mp", mp,
        "actualIndependentMorphiaPlayerReads", 3, "changedPlayers", 0, "databaseSHA256", databaseBefore,
        "nativeOriginOrUseIDLedgerPersisted", false, "restoredUseIDLedgerEmpty", true, "receiptPrewritten", false));
    report.put("actualIndependentMorphiaAvatarReads", 3); report.put("restoredTemporaryBuffsEmpty", true);
    System.out.println(new Gson().toJson(report));
  }
}
