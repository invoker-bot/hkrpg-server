package org.hkrpg.server.gameplay;

import static dev.morphia.query.filters.Filters.eq;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.mapping.MapperOptions;
import emu.lunarcore.database.codecs.*;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.codecs.configuration.CodecRegistries;

/** Only reads this marked fixture's actual Player/Avatar records after normal process stop. */
public final class LivingCasterFixtureInspector {
  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var directory = Path.of(args[0]).toRealPath();
    if (!directory.startsWith(root.resolve("build/smoke"))
        || directory.equals(root.resolve("build/smoke")))
      throw new IllegalArgumentException("Not an owned smoke directory");
    var marker =
        JsonParser.parseString(
                Files.readString(directory.resolve("resources-fixture/resource-manifest.json")))
            .getAsJsonObject();
    if (!marker.get("testFixture").getAsBoolean()
        || !marker.get("livingCasterFixture").getAsBoolean())
      throw new IllegalArgumentException("Missing fixture marker");
    int uid = Integer.parseInt(args[1]);
    String mode = args[2];
    if (uid <= 0 || !Set.of("dead", "restore", "living").contains(mode))
      throw new IllegalArgumentException("Invalid arguments");
    int expectedHp = mode.equals("dead") ? 0 : 10000, expectedMp = mode.equals("dead") ? 5 : 4;
    var file = directory.resolve("data/database.mv");
    if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing fixture database");
    var server = new MongoServer(new H2Backend(file.toString()));
    var address = server.bind();
    try (var client =
        MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var options =
          MapperOptions.builder()
              .storeEmpties(true)
              .storeNulls(false)
              .codecProvider(
                  CodecRegistries.fromCodecs(
                      new IntSetCodec(), new IntListCodec(), new Int2IntMapCodec()))
              .build();
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg", options);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              Player.class,
              QuestState.class,
              emu.lunarcore.util.Position.class,
              GameAvatar.class,
              AvatarData.class);
      if (store.find(Player.class).count() != 1
          || store.find(GameAvatar.class).filter(eq("ownerUid", uid), eq("avatarId", 8001)).count()
              != 1) throw new IllegalStateException("Not unique actual records");
      var players = store.getDatabase().getCollection("players");
      var avatars = store.getDatabase().getCollection("avatars");
      var playerQuery = new org.bson.Document("_id", uid);
      var avatarQuery = new org.bson.Document("ownerUid", uid).append("avatarId", 8001);
      String initialPlayer = Objects.requireNonNull(players.find(playerQuery).first()).toJson();
      String initialAvatar = Objects.requireNonNull(avatars.find(avatarQuery).first()).toJson();
      for (int read = 0; read < 2; read++) {
        var player =
            Objects.requireNonNull(store.find(Player.class).filter(eq("_id", uid)).first());
        var avatar =
            Objects.requireNonNull(
                store
                    .find(GameAvatar.class)
                    .filter(eq("ownerUid", uid), eq("avatarId", 8001))
                    .first());
        if (player.getLineupManager().getMp() != expectedMp
            || avatar.getCurrentHp(new PlayerLineup()) != expectedHp)
          throw new IllegalStateException("Actual saved life or points differ");
        if (!initialPlayer.equals(
                Objects.requireNonNull(players.find(playerQuery).first()).toJson())
            || !initialAvatar.equals(
                Objects.requireNonNull(avatars.find(avatarQuery).first()).toJson()))
          throw new IllegalStateException("Read changed actual records");
      }
      System.out.println(
          new Gson()
              .toJson(
                  Map.of(
                      "passed",
                      true,
                      "uid",
                      uid,
                      "mode",
                      mode,
                      "hp",
                      expectedHp,
                      "mp",
                      expectedMp,
                      "actualPlayerReads",
                      2,
                      "actualAvatarReads",
                      2,
                      "changedRecords",
                      0)));
    } finally {
      server.shutdownNow();
    }
  }

  private LivingCasterFixtureInspector() {}
}
