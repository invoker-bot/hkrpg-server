package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.h2.H2Backend;
import dev.morphia.Morphia;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.h2.mvstore.MVStore;

/** Independently reads a stopped, owned fixture database with MVStore write access disabled. */
public final class DailyMonsterFixtureInspector {
  private static void require(boolean value, String why) {
    if (!value) throw new IllegalStateException(why);
  }

  private static String sha(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private static Map<String,Object> state(QuestState state, int id, QuestState.Phase phase, long day) {
    require(state != null && state.getQuestId() == id && state.getPhase() == phase,
        "Stored phase differs: " + id + " expected=" + phase + " actual=" + (state == null ? null : state.getPhase()));
    boolean done = phase != QuestState.Phase.DOING;
    require(state.getProgress() == (done ? 1 : 0), "Stored progress differs: " + id);
    require(done ? state.getFinishTime() > 0 && DailyManager.day(Instant.ofEpochSecond(state.getFinishTime())) == day
        : state.getFinishTime() == 0, "Stored finishTime differs from the run day: " + id);
    return Map.of("id", id, "phase", phase.name(), "progress", state.getProgress(),
        "finishTime", state.getFinishTime(), "runDay", day);
  }

  private static Map<String,Object> run(QuestState main, Map<Integer,QuestState> subs,
      int id, long day, boolean completed) {
    var proof = new TreeMap<String,Object>();
    proof.put("main", state(main, id, completed ? QuestState.Phase.CLOSED : QuestState.Phase.DOING, day));
    var rows = new ArrayList<Map<String,Object>>();
    for (int index = 1; index <= 4; index++) {
      int sid = id * 100 + index;
      if (!completed && index == 4) {
        require(!subs.containsKey(sid), "Unpaid tail was completed or accepted: " + sid);
        continue;
      }
      var phase = !completed && index == 3 ? QuestState.Phase.DOING : QuestState.Phase.FINISHED;
      rows.add(state(subs.get(sid), sid, phase, day));
    }
    proof.put("subs", rows);
    return proof;
  }

  private static void reward(GameplayState state, String key, boolean earned) {
    var receipt = state.getRewards().get(key);
    var plan = state.getMissionRewardPlans().get(key);
    if (!earned) {
      require(receipt == null && plan == null, "Unpaid run acquired a reward: " + key);
      return;
    }
    require(receipt != null && receipt.isGranted() && plan != null && plan.isReady()
        && plan.getRewardIds().equals(List.of(231)) && plan.getChoices().isEmpty()
        && plan.getItems().equals(Map.of(2, 5000)), "Stored source reward differs: " + key);
  }

  public static void main(String[] args) throws Exception {
    var root = Path.of("").toRealPath();
    var dir = Path.of(args[0]).toRealPath();
    var input = Path.of(args[1]).toRealPath();
    require(dir.startsWith(root.resolve("build/smoke"))
        && dir.getFileName().toString().startsWith("daily-monster-") && input.startsWith(dir), "Unowned fixture");
    var control = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
    int uid = control.get("uid").getAsInt(), id = control.get("mainMission").getAsInt();
    long firstDay = control.get("firstDay").getAsLong(), day = control.get("runDay").getAsLong();
    boolean completed = control.get("completed").getAsBoolean();
    int coreCredit = control.get("existingCoreBattleCredit").getAsInt();
    String scenario = control.get("scenario").getAsString();
    require(uid > 0 && coreCredit == 36 && Set.of(3000211,3000212,3000213).contains(id)
        && Set.of("settled","expired-unpaid").contains(scenario)
        && (day == firstDay || day == firstDay + 1) && (!completed || day == firstDay + 1), "Invalid inspection");
    var marker = JsonParser.parseString(Files.readString(dir.resolve("resources-fixture/resource-manifest.json"))).getAsJsonObject();
    require(marker.get("testFixture").getAsBoolean() && marker.get("dailyMonsterFixture").getAsBoolean()
        && marker.get("originalMission").getAsInt() == id, "Wrong fixture marker");
    var configPath = dir.resolve("config.json");
    var file = dir.resolve("data/database.mv");
    require(Files.isRegularFile(file), "Missing stopped database");
    String configSha = sha(configPath), beforeSha = sha(file);
    var proof = new TreeMap<String,Object>();
    var mv = new MVStore.Builder().fileName(file.toString()).readOnly().open();
    var server = new MongoServer(new H2Backend(mv));
    var address = server.bind();
    try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var players = client.getDatabase("hkrpg").getCollection("players");
      var filter = Filters.eq("_id", uid);
      require(players.countDocuments() == 1 && players.countDocuments(filter) == 1, "Not one fixture player");
      var before = Objects.requireNonNull(players.find(filter).first());
      var store = Morphia.createDatastore(client, "hkrpg");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      var first = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      var second = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", uid)).first();
      require(first != null && second != null && first != second, "Independent Morphia restores failed");
      var gson = new Gson();
      require(gson.toJson(first.getGameplay()).equals(gson.toJson(second.getGameplay()))
          && first.getScoin() == second.getScoin(), "Independent reads differ");
      var g = first.getGameplay();
      proof.put("currentRun", run(g.getMainMissions().get(id), g.getSubMissions(), id, day, completed));
      boolean later = day > firstDay, expired = scenario.equals("expired-unpaid");
      var periods = g.getDailyMissionPeriods();
      require(periods.getRunDays().equals(later ? Map.of(id, day) : Map.of()), "Stored runDays differ");
      require(periods.getArchives().size() == (later && !expired ? 1 : 0)
          && periods.getExpired().size() == (later && expired ? 1 : 0), "Stored old-run identities differ");
      var goals = Map.of(id*100+1,1,id*100+2,1,id*100+3,1,id*100+4,1);
      if (later && expired) {
        var old = periods.getExpired().get(id + ":" + firstDay);
        require(old != null && old.getMainId() == id && old.getDay() == firstDay && old.getGoals().equals(goals)
            && DailyManager.day(Instant.ofEpochSecond(old.getExpiredAt())) == day, "Stored expiry differs");
        proof.put("expiredRun", run(old.getMain(), old.getSubs(), id, firstDay, false));
        proof.put("expiredAt", old.getExpiredAt());
      } else if (later) {
        var old = periods.getArchives().get(id + ":" + firstDay);
        require(old != null && old.getMainId() == id && old.getDay() == firstDay && old.getGoals().equals(goals)
            && old.getRewardKeys().equals(List.of("mission:" + id)), "Stored archive differs");
        proof.put("archivedRun", run(old.getMain(), old.getSubs(), id, firstDay, true));
      }
      var selections = g.getDailyMissionSelections();
      var days = later ? Set.of(Long.toString(firstDay), Long.toString(day)) : Set.of(Long.toString(day));
      require(selections.getLatestDay() == day && selections.getDays().keySet().equals(days), "Stored selections differ");
      for (var choices : selections.getDays().values()) {
        require(choices.size() == 1, "Stored quota differs");
        var choice = choices.getFirst();
        require(choice.getId() == id && choice.getType() == 1 && choice.getGroup() == 30002, "Stored source selection differs");
      }
      reward(g, "mission:" + id, later && !expired);
      reward(g, "daily-mission:" + (firstDay+1) + ":mission:" + id, completed);
      int wallet = (later && !expired ? 5000 : 0) + (completed ? 5000 : 0) + (later ? 2 : 1) * coreCredit;
      require(first.getScoin() == wallet && g.getDailyPoints() == (completed ? 200 : 0)
          && g.getDailyDay() == day, "Stored credit/points/day differ: credit=" + first.getScoin()
              + " points=" + g.getDailyPoints() + " day=" + g.getDailyDay() + " expected=" + wallet + "/"
              + (completed ? 200 : 0) + "/" + day);
      proof.put("dailyQuest", state(g.getDailyQuests().get(2100003), 2100003,
          completed ? QuestState.Phase.CLOSED : QuestState.Phase.DOING, day));
      require(before.equals(players.find(filter).first()) && players.countDocuments() == 1, "Inspector mutated player documents");
      proof.put("credit", wallet);
      proof.put("existingCoreBattleCreditPerAcceptedWin", coreCredit);
      proof.put("activePoints", g.getDailyPoints());
      proof.put("selections", days);
      proof.put("runDays", periods.getRunDays());
      proof.put("independentMorphiaReadCount", 2);
      proof.put("changedPlayerDocumentCount", 0);
    } finally {
      server.shutdownNow();
      if (!mv.isClosed()) mv.close();
    }
    require(beforeSha.equals(sha(file)) && configSha.equals(sha(configPath)), "Read-only database/config bytes changed");
    proof.put("databaseSHA256", beforeSha);
    proof.put("databaseFileBytesUnchanged", true);
    proof.put("mvStoreReadOnly", true);
    proof.put("uid", uid);
    proof.put("mainMission", id);
    proof.put("scenario", scenario);
    proof.put("runDay", day);
    proof.put("completed", completed);
    proof.put("passed", true);
    System.out.println(new Gson().toJson(proof));
  }
}
