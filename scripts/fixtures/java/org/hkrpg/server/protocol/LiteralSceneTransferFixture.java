import com.google.gson.Gson;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;

/** Seed the preceding talk and original entrance prerequisites only in a stopped disposable DB. */
public class LiteralSceneTransferFixture {
  static void require(boolean value, String why) {
    if (!value) throw new IllegalStateException(why);
  }

  public static void main(String[] args) throws Exception {
    var directory = Path.of(args[0]).toRealPath();
    var root = Path.of("").toRealPath();
    require(directory.startsWith(root.resolve("build/smoke"))
        && directory.getFileName().toString().startsWith("literal-transfer-"), "Not owned fixture");
    require(Files.readString(directory.resolve("fixture-marker")).equals("literal-transfer-v1"), "Missing marker");
    String mode = args[1];
    int uid = Integer.parseInt(args[2]);
    require(Set.of("seed", "inspect").contains(mode) && uid > 0, "Invalid operation");
    var backend = new H2Backend(directory.resolve("data/database.mv").toString());
    var server = new MongoServer(backend);
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      require(players.countDocuments() == 1, "Expected one disposable player");
      var filter = new Document("_id", uid);
      var before = Objects.requireNonNull(players.find(filter).first());
      if (mode.equals("seed")) {
        require(!before.get("gameplay", Document.class).get("mainMissions", Document.class)
            .containsKey("1010205"), "Already seeded");
        var update = new Document("planeId", 10101).append("floorId", 10101004)
            .append("entryId", 1010104).append("worldId", 201)
            .append("pos", new Document("x", -2540).append("y", 0).append("z", 300))
            .append("rot", new Document("x", 0).append("y", 81151).append("z", 0))
            .append("gameplay.mainMissions.1010205", Document.parse(new Gson().toJson(new QuestState(1010205))))
            .append("gameplay.subMissions.101020201", Document.parse(new Gson().toJson(new QuestState(101020201))));
        // The room retains the original snowfield chapter and city-arrival entrance gates.
        for (var key : List.of("mainMissions.1010101", "subMissions.101010114")) {
          var state = new QuestState(Integer.parseInt(key.substring(key.indexOf('.') + 1)));
          state.advance(1, 1, 100);
          update.append("gameplay." + key, Document.parse(new Gson().toJson(state)));
        }
        require(players.updateOne(filter, new Document("$set", update)).getModifiedCount() == 1, "Seed failed");
        backend.commit(); backend.getMvStore().sync();
      }
      var store = dev.morphia.Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var player = Objects.requireNonNull(store.find(Player.class)
          .filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first());
      var subs = player.getGameplay().getSubMissions();
      if (mode.equals("seed")) {
        require(subs.get(101020201).getPhase() == QuestState.Phase.DOING
            && !subs.containsKey(101020202), "Seed prewrote the talk or transfer");
      } else {
        require(player.getPlaneId() == 10101 && player.getFloorId() == 10101006
            && player.getEntryId() == 1010106, "Hotel location lost");
        for (int id : new int[] {101020201, 101020202})
          require(subs.get(id).getPhase() == QuestState.Phase.FINISHED, "Hotel progress lost");
        require(subs.get(101020203).getPhase() == QuestState.Phase.DOING, "Following interaction skipped");
      }
      System.out.println("TRANSFER_INSPECT=" + new Gson().toJson(Map.of("passed", true, "uid", uid,
          "mode", mode, "actualPlayerRestored", true, "floor", player.getFloorId(),
          "position", player.getPos(), "credits", player.getScoin())));
    } finally { server.shutdownNow(); }
  }
}
