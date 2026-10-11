package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.GameResource;
import emu.lunarcore.game.scene.Scene;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded server routing policy, constrained by complete original boards, floor and source rows. */
public final class ChessRogueRoutes {
  public static final String SOURCE_COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b";
  public static final String RULE_ID = "hkrpg-chess-guide-original-merchant-v1";
  public static final String FLOOR = "Config/LevelOutput/RuntimeFloor/P80201_F80201001.json";
  public static final String ACT = "Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act0110003.json";
  private static final Gson JSON = new Gson();
  private static final Gson RESOURCES = ResourceLoader.getGson();
  private static final JsonObject ORIGINAL = originalPolicy();
  private static final Set<String> VARIABLE_FIELDS = Set.of("enabled", "initialCoins", "coinsPerMonsterVictory",
      "initialActionPoints", "miracleBasePrice", "miracleStockLimit", "monsterLevel");
  private static final Map<String, Integer> BLOCKS = Map.ofEntries(Map.entry("Empty",1),Map.entry("Respite",2),
      Map.entry("MonsterNormal",3),Map.entry("MonsterSwarm",4),Map.entry("MonsterElite",5),
      Map.entry("Adventure",6),Map.entry("Reward",7),Map.entry("Event",8),Map.entry("SwarmEvent",9),
      Map.entry("Trap",10),Map.entry("MonsterBoss",11),Map.entry("MonsterSwarmBoss",12),
      Map.entry("Trade",13),Map.entry("BlackMarket",14),Map.entry("MonsterNousBoss",15),
      Map.entry("NousEvent",16),Map.entry("NousSpecialEvent",17));
  public record Rule(int initialCoins, int victoryCoins, int actionPoints, int miraclePrice, int stockLimit, int monsterLevel) {}
  public record Cell(int id, int x, int y, String block) { public int blockId() { return BLOCKS.get(block); } }
  public record Board(int layer, int id, int width, int height, int start, int end, Map<Integer, Cell> cells, List<Integer> path) {}
  public record Room(String block, int id, int entry, int dimension, int anchorGroup, int anchor,
      List<Integer> groups, int monsterPool, int monster, int merchantGroup, int npc, int instance) {}
  private record ExpectedRow(String table, int id, JsonElement shape) {}
  private record Loaded(Path root, Path config, Rule rule, Map<Path,String> digests, List<Board> boards,
      Map<String, Room> rooms, FloorInfo normalizedFloor, List<ExpectedRow> rows) {}
  private static volatile Loaded current;

  private static JsonObject originalPolicy() {
    try (var input = ChessRogueRoutes.class.getResourceAsStream("/defaults/ChessRogueGuideRoutes.json")) {
      if (input == null) throw new IllegalStateException("Missing bundled fixed Chess source policy");
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (IOException failed) { throw new ExceptionInInitializerError(failed); }
  }

  public static void load(Path resources, Path config) {
    current = null;
    if (!Files.isRegularFile(config)) return;
    try {
      if (Files.size(config) > 200_000) throw new IllegalArgumentException("Oversized Chess policy");
      var policy = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
      if (!policy.keySet().equals(ORIGINAL.keySet())) throw new IllegalArgumentException("Unknown Chess policy fields");
      for (var field : ORIGINAL.entrySet())
        if (!VARIABLE_FIELDS.contains(field.getKey()) && !field.getValue().equals(policy.get(field.getKey())))
          throw new IllegalArgumentException("Unauthored Chess source or route: " + field.getKey());
      if (!policy.get("enabled").isJsonPrimitive() || !policy.get("enabled").getAsJsonPrimitive().isBoolean())
        throw new IllegalArgumentException("Expected Chess boolean switch");
      var rule = new Rule(integer(policy,"initialCoins"),integer(policy,"coinsPerMonsterVictory"),
          integer(policy,"initialActionPoints"),integer(policy,"miracleBasePrice"),integer(policy,"miracleStockLimit"),
          integer(policy,"monsterLevel"));
      if (!validRule(rule)) throw new IllegalArgumentException("Invalid bounded Chess policy amounts");
      if (!policy.get("enabled").getAsBoolean()) return;
      var root = resources.toAbsolutePath().normalize();
      var digests = new LinkedHashMap<Path,String>();
      digests.put(config.toAbsolutePath().normalize(), digest(config));
      for (var value : ORIGINAL.getAsJsonArray("sourceFiles")) {
        var binding = value.getAsJsonObject();
        var file = inside(root, binding.get("path").getAsString());
        if (!Files.isRegularFile(file) || Files.size(file) > 2_000_000) return;
        var bytes = Files.readString(file,StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        if (!hash(bytes).equals(binding.get("normalizedSHA256").getAsString())) return;
        digests.put(file, digest(file));
      }
      var expectedRows = new ArrayList<ExpectedRow>();
      for (var value : ORIGINAL.getAsJsonArray("sourceRows")) {
        var binding = value.getAsJsonObject();
        var file = inside(root,binding.get("path").getAsString());
        if (!Files.isRegularFile(file) || Files.size(file) > 100_000_000) return;
        var rows = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
        // Complete selected original rows are required; unrelated table entries are not fabricated.
        for (var expected : binding.getAsJsonArray("rows")) {
          if (rows.asList().stream().filter(expected::equals).count() != 1) return;
          var table = file.getFileName().toString().replace(".json", "");
          var resource = excel(table,expected);
          if (resource != null) expectedRows.add(new ExpectedRow(table,resource.getId(),RESOURCES.toJsonTree(resource)));
        }
        digests.put(file,digest(file));
      }
      var floor = RESOURCES.fromJson(Files.readString(root.resolve(FLOOR),StandardCharsets.UTF_8),FloorInfo.class);
      for (int index : floor.getReferencedGroupIndices()) {
        var simple = floor.getSimpleGroupList().get(index);
        var group = RESOURCES.fromJson(Files.readString(inside(root,simple.getGroupPath()),StandardCharsets.UTF_8),GroupInfo.class);
        group.setId(simple.getID()); floor.registerGroup(index,group);
      }
      floor.onLoad();
      var boards = new ArrayList<Board>();
      for (var value : ORIGINAL.getAsJsonArray("layers")) {
        var layer = value.getAsJsonObject();
        int id = integer(layer,"board");
        var table = sourceRow("RogueDLCChessBoard", "ChessBoardID", id);
        var data = JsonParser.parseString(Files.readString(root.resolve(table.get("ChessBoardConfiguration").getAsString()),
            StandardCharsets.UTF_8)).getAsJsonObject();
        var cells = new LinkedHashMap<Integer,Cell>();
        for (var entry : data.getAsJsonObject("RogueChestGridItemMap").entrySet()) {
          var row = entry.getValue().getAsJsonObject();
          var types = row.getAsJsonArray("BlockTypeList");
          if (types.size() != 1 || !BLOCKS.containsKey(types.get(0).getAsString())) return;
          int cell = Integer.parseInt(entry.getKey());
          cells.put(cell,new Cell(cell,integer(row,"PosX"),integer(row,"PosY"),types.get(0).getAsString()));
        }
        boards.add(new Board(integer(layer,"layer"),id,integer(data,"Width"),integer(data,"Height"),
            integer(data,"StartGridItemID"),integer(data,"EndGridItemID"),Map.copyOf(cells),integers(layer.getAsJsonArray("path"))));
      }
      var rooms = new LinkedHashMap<String,Room>();
      for (var value : ORIGINAL.getAsJsonArray("rooms")) {
        var room = value.getAsJsonObject();
        var entry = new Room(room.get("block").getAsString(),integer(room,"room"),integer(room,"entry"),
            integer(room,"dimension"),integer(room,"anchorGroup"),integer(room,"anchor"),integers(room.getAsJsonArray("groups")),
            optional(room,"monsterGroup"),optional(room,"rogueMonster"),optional(room,"merchantGroup"),
            optional(room,"npc"),optional(room,"instance"));
        rooms.put(entry.block(),entry);
      }
      current = new Loaded(root,config.toAbsolutePath().normalize(),rule,Map.copyOf(digests),List.copyOf(boards),
          Map.copyOf(rooms),floor,List.copyOf(expectedRows));
    } catch (Exception malformed) {
      throw new IllegalStateException("Invalid ChessRogueGuideRoutes.json or bounded original source", malformed);
    }
  }

  private static boolean validRule(Rule rule) {
    return rule.initialCoins() >= 0 && rule.initialCoins() <= 1_000_000
        && rule.victoryCoins() >= 0 && rule.victoryCoins() <= 100_000
        && rule.actionPoints() >= 1 && rule.actionPoints() <= 100
        && rule.miraclePrice() >= 1 && rule.miraclePrice() <= 1_000_000
        && rule.stockLimit() >= 1 && rule.stockLimit() <= 3
        && Set.of(53,54).contains(rule.monsterLevel());
  }
  private static int integer(JsonObject data,String key) {
    var value = data.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected integer Chess field " + key);
    return value.getAsBigDecimal().intValueExact();
  }
  private static int optional(JsonObject data,String key) { return data.has(key) ? integer(data,key) : 0; }
  private static List<Integer> integers(JsonArray array) { return array.asList().stream().map(v -> v.getAsBigDecimal().intValueExact()).toList(); }
  private static Path inside(Path root,String relative) {
    var path = root.resolve(relative).normalize();
    if (relative.contains("\\") || !path.startsWith(root)) throw new IllegalArgumentException("Chess source escapes root");
    return path;
  }
  private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private static String digest(Path path) throws Exception {
    var md = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] block = new byte[64*1024]; int size;
      while ((size = input.read(block)) != -1) md.update(block,0,size);
    }
    return HexFormat.of().formatHex(md.digest());
  }
  private static JsonObject sourceRow(String table,String key,int id) {
    for (var value : ORIGINAL.getAsJsonArray("sourceRows")) {
      var source = value.getAsJsonObject();
      if (source.get("path").getAsString().equals("ExcelOutput/"+table+".json"))
        for (var row : source.getAsJsonArray("rows"))
          if (row.getAsJsonObject().has(key) && integer(row.getAsJsonObject(),key) == id) return row.getAsJsonObject();
    }
    throw new IllegalArgumentException("Missing pinned Chess row");
  }
  private static GameResource excel(String table,JsonElement row) {
    Class<? extends GameResource> type = switch (table) {
      case "MapEntrance" -> MapEntranceExcel.class; case "MazePlane" -> MazePlaneExcel.class;
      case "AvatarConfig" -> AvatarExcel.class; case "MultiplePathAvatarConfig" -> MultiplePathAvatarExcel.class;
      case "NPCData" -> NpcExcel.class; case "NPCMonsterData" -> NpcMonsterExcel.class;
      case "MonsterConfig" -> MonsterExcel.class; case "StageConfig" -> StageExcel.class;
      case "MazeProp" -> PropExcel.class; default -> null;
    };
    if (type == null) return null;
    var resource = RESOURCES.fromJson(row,type); resource.onLoad(); return resource;
  }
  private static GameResource actual(ExpectedRow row) {
    return switch (row.table()) {
      case "MapEntrance" -> GameData.getMapEntranceExcelMap().get(row.id());
      case "MazePlane" -> GameData.getMazePlaneExcelMap().get(row.id());
      case "AvatarConfig" -> GameData.getAvatarExcelMap().get(row.id());
      case "MultiplePathAvatarConfig" -> GameData.getMultiplePathAvatarExcelMap().get(row.id());
      case "NPCData" -> GameData.getNpcExcelMap().get(row.id());
      case "NPCMonsterData" -> GameData.getNpcMonsterExcelMap().get(row.id());
      case "MonsterConfig" -> GameData.getMonsterExcelMap().get(row.id());
      case "StageConfig" -> GameData.getStageExcelMap().get(row.id());
      case "MazeProp" -> GameData.getPropExcelMap().get(row.id()); default -> null;
    };
  }

  public static Rule rule() { var loaded = current; return loaded == null ? null : loaded.rule(); }
  public static Board board(int layer) {
    var loaded = current;
    return loaded == null || layer < 0 || layer >= loaded.boards().size() ? null : loaded.boards().get(layer);
  }
  public static Room room(ChessRogueState run) {
    var board = board(run.getLayerIndex());
    var cell = board == null ? null : board.cells().get(run.getEnteredCell());
    return current == null || cell == null ? null : current.rooms().get(cell.block());
  }
  public static Room room(Cell cell) { return current == null || cell == null ? null : current.rooms().get(cell.block()); }

  public static boolean available() {
    var loaded = current;
    if (loaded == null) return false;
    try {
      for (var source : loaded.digests().entrySet())
        if (!Files.isRegularFile(source.getKey()) || !source.getValue().equals(digest(source.getKey()))) return false;
      var floor = GameData.getFloorInfo(80201,80201001);
      if (floor == null || !floor.isLoaded() || !RESOURCES.toJsonTree(floor).equals(RESOURCES.toJsonTree(loaded.normalizedFloor()))
          || !floor.getReferencedGroupIndices().equals(loaded.normalizedFloor().getReferencedGroupIndices())) return false;
      for (int index : floor.getReferencedGroupIndices()) {
        var actual = floor.getGroupInfoByIndex(index);
        if (actual == null || !RESOURCES.toJsonTree(actual).equals(RESOURCES.toJsonTree(loaded.normalizedFloor().getGroupInfoByIndex(index)))) return false;
      }
      for (var row : loaded.rows()) {
        var actual = actual(row);
        if (actual == null || !row.shape().equals(RESOURCES.toJsonTree(actual))) return false;
      }
      // Runtime catalogs also have to retain the original selected rows, not just their IDs.
      for (int id : List.of(111,139011)) {
        var original = JSON.fromJson(sourceRow("RogueMonster","RogueMonsterID",id),GameplayDefinitions.RogueMonster.class);
        if (!original.equals(GameplayDefinitions.rogueMonsters.get(id))) return false;
      }
      for (int id : List.of(11,13901)) {
        var original = JSON.fromJson(sourceRow("RogueMonsterGroup","RogueMonsterGroupID",id),GameplayDefinitions.RogueMonsterGroup.class);
        if (!original.equals(GameplayDefinitions.rogueMonsterGroups.get(id))) return false;
      }
      var miracle = JSON.fromJson(sourceRow("RogueMiracle","MiracleID",4),GameplayDefinitions.Miracle.class);
      var effect = JSON.fromJson(sourceRow("RogueMiracleEffect","MiracleEffectID",4),GameplayDefinitions.MiracleEffect.class);
      return miracle.equals(GameplayDefinitions.miracles.get(4)) && effect.equals(GameplayDefinitions.miracleEffects.get(4));
    } catch (Exception unreadable) { return false; }
  }

  public static boolean sceneSource(Scene scene,ChessRogueState run,boolean requireCurrent) {
    var room = room(run);
    return room != null && available() && scene != null && scene.getPlayer().getGameplay().getChessRogue() == run
        && (!requireCurrent || scene.getPlayer().getScene() == scene && scene.getPlayer().getEntryId() == room.entry())
        && scene.getPlaneId() == 80201 && scene.getFloorId() == 80201001 && scene.getDimensionId() == room.dimension()
        && scene.getFloorInfo() == GameData.getFloorInfo(80201,80201001)
        && (!requireCurrent || scene.getEntryId() == room.entry());
  }

  public static boolean valid(ChessRogueState run) {
    if (run == null || run.getSchema() != 1 || !run.active() || !SOURCE_COMMIT.equals(run.getSourceCommit())
        || !RULE_ID.equals(run.getRouteRule()) || run.getArea() != 101 || run.getAeon() != 6
        || run.getRoster() == null || run.getRoster().isEmpty() || run.getRoster().size() > 4
        || new HashSet<>(run.getRoster()).size() != run.getRoster().size()
        || run.getVitals() == null || !run.getVitals().keySet().equals(new HashSet<>(run.getRoster()))
        || run.getVitals().values().stream().anyMatch(v -> v == null || v.getHp() < 0 || v.getHp() > 10000 || v.getSp() < 0)
        || run.getLeader() < 0 || run.getLeader() >= run.getRoster().size() || run.getMp() < 0
        || run.getStatuses() == null || run.getVictories() == null || run.getMiracles() == null || run.getReturnTo() == null
        || run.getCoins() < 0 || run.getCoins() > 2_000_000 || run.getActionPoints() < 1 || run.getActionPoints() > 100
        || run.getCoinsPerVictory() < 0 || run.getCoinsPerVictory() > 100_000
        || run.getMiraclePrice() < 1 || run.getMiraclePrice() > 1_000_000
        || run.getStockLimit() < 1 || run.getStockLimit() > 3 || !Set.of(53,54).contains(run.getMonsterLevel())) return false;
    var board = board(run.getLayerIndex());
    if (board == null || !run.getStatuses().keySet().equals(board.cells().keySet())
        || !board.path().contains(run.getCurrentCell()) || !board.path().contains(run.getEnteredCell())) return false;
    for (var status : run.getStatuses().entrySet())
      if (status.getValue() == null || status.getValue() < 0 || status.getValue() > 3
          || !board.path().contains(status.getKey()) && status.getValue() != 0) return false;
    return run.getCurrentCell() == run.getEnteredCell() && Set.of(2,3).contains(run.status(run.getCurrentCell()))
        || run.getCurrentCell() != run.getEnteredCell() && run.status(run.getCurrentCell()) == 1
            && run.status(run.getEnteredCell()) == 3
            && board.path().indexOf(run.getCurrentCell()) == board.path().indexOf(run.getEnteredCell()) + 1;
  }
  private ChessRogueRoutes() {}
}
