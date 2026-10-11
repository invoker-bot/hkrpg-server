package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import dev.morphia.annotations.Entity;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.util.Position;
import java.util.*;
import lombok.Getter;

/** Independent, single-player-document Chess run. The classic RogueRun is never borrowed. */
@Entity(useDiscriminator = false)
@Getter
public final class ChessRogueState {
  private static final Gson COPY = new Gson();
  private int schema = 1;
  private String runId = "";
  private String sourceCommit = "";
  private String routeRule = "";
  private int area, aeon, layerIndex, currentCell, enteredCell;
  private boolean inside, failed;
  private int coins, actionPoints, coinsPerVictory, miraclePrice, stockLimit, monsterLevel;
  private int leader, mp;
  private List<Integer> roster = new ArrayList<>();
  private Map<Integer, Vital> vitals = new LinkedHashMap<>();
  private Map<Integer, Integer> statuses = new LinkedHashMap<>();
  private Map<String, Victory> victories = new LinkedHashMap<>();
  private Map<Integer, Miracle> miracles = new LinkedHashMap<>();
  private Shop shop;
  private ReturnLocation returnTo;

  public ChessRogueState() {}

  static ChessRogueState begin(Player player, ChessRogueRoutes.Rule rule, List<Integer> avatars) {
    var run = new ChessRogueState();
    run.runId = UUID.randomUUID().toString();
    run.sourceCommit = ChessRogueRoutes.SOURCE_COMMIT;
    run.routeRule = ChessRogueRoutes.RULE_ID;
    run.area = 101;
    run.aeon = 6;
    run.inside = true;
    run.coins = rule.initialCoins();
    run.actionPoints = rule.actionPoints();
    run.coinsPerVictory = rule.victoryCoins();
    run.miraclePrice = rule.miraclePrice();
    run.stockLimit = rule.stockLimit();
    run.monsterLevel = rule.monsterLevel();
    var ordinary = player.getCurrentLineup();
    run.roster.addAll(avatars);
    run.leader = Math.max(avatars.indexOf(ordinary.getAvatars().get(ordinary.getLeader())), 0);
    run.mp = ordinary.getMp();
    for (int id : avatars) {
      var avatar = player.getAvatarById(id);
      run.vitals.put(id, new Vital(avatar.getCurrentHp(ordinary), avatar.getCurrentSp(ordinary)));
    }
    run.returnTo = new ReturnLocation(player);
    run.enterLayer(0, ChessRogueRoutes.board(0));
    return run;
  }

  public boolean active() { return runId != null && !runId.isBlank(); }
  public int status(int cell) { return statuses.getOrDefault(cell, 0); }
  public String receiptKey() { return layerIndex + ":" + enteredCell; }
  public List<Integer> allowedCells() {
    var board = ChessRogueRoutes.board(layerIndex);
    if (board == null || failed || currentCell != enteredCell || status(enteredCell) != 3) return List.of();
    int index = board.path().indexOf(enteredCell);
    return index < 0 || index + 1 >= board.path().size() ? List.of() : List.of(board.path().get(index + 1));
  }

  void select(int cell) { currentCell = cell; statuses.put(cell, 1); }
  void enter() { enteredCell = currentCell; statuses.put(currentCell, 2); }
  void enterLayer(int index, ChessRogueRoutes.Board board) {
    layerIndex = index;
    currentCell = enteredCell = board.start();
    statuses = new LinkedHashMap<>();
    board.cells().keySet().forEach(id -> statuses.put(id, 0));
    statuses.put(currentCell, 2);
  }
  void victory(int battle, int stage, int group, int instance) {
    coins = Math.addExact(coins, coinsPerVictory);
    victories.put(receiptKey(), new Victory(runId, battle, layerIndex, enteredCell, stage, group, instance));
    statuses.put(enteredCell, 3);
  }
  void lose() { failed = true; }
  void stock(Shop value) { shop = value; }
  void purchase(int id) {
    coins = Math.subtractExact(coins, shop.price);
    miracles.put(id, new Miracle(id));
    shop.purchased.add(id);
  }
  void hp(int id, int amount) { vitals.get(id).hp = amount; }
  void sp(int id, int amount) { vitals.get(id).sp = amount; }
  void leader(int slot) { leader = slot; }
  void mp(int amount) { mp = amount; }
  void inside(boolean value) { inside = value; }

  public ChessRogueState copy() { return COPY.fromJson(COPY.toJson(this), ChessRogueState.class); }
  /** Keep the live run/lineup identity, including a battle that can retry a failed synchronous save. */
  public void restore(ChessRogueState saved) {
    schema = saved.schema; runId = saved.runId; sourceCommit = saved.sourceCommit; routeRule = saved.routeRule;
    area = saved.area; aeon = saved.aeon; layerIndex = saved.layerIndex;
    currentCell = saved.currentCell; enteredCell = saved.enteredCell; inside = saved.inside; failed = saved.failed;
    coins = saved.coins; actionPoints = saved.actionPoints; coinsPerVictory = saved.coinsPerVictory;
    miraclePrice = saved.miraclePrice; stockLimit = saved.stockLimit; monsterLevel = saved.monsterLevel;
    leader = saved.leader; mp = saved.mp; roster = saved.roster; vitals = saved.vitals;
    statuses = saved.statuses; victories = saved.victories; miracles = saved.miracles;
    shop = saved.shop; returnTo = saved.returnTo;
  }

  @Entity(useDiscriminator = false) @Getter
  public static final class Vital {
    private int hp, sp;
    public Vital() {}
    Vital(int hp, int sp) { this.hp = hp; this.sp = sp; }
  }
  @Entity(useDiscriminator = false) @Getter
  public static final class Victory {
    private String runId, settlementId;
    private int battle, layer, cell, stage, group, instance;
    public Victory() {}
    Victory(String run, int battle, int layer, int cell, int stage, int group, int instance) {
      this.runId = run; settlementId = UUID.randomUUID().toString(); this.battle = battle;
      this.layer = layer; this.cell = cell; this.stage = stage; this.group = group; this.instance = instance;
    }
  }
  @Entity(useDiscriminator = false) @Getter
  public static final class Miracle {
    private String acquisitionId;
    private int id, totalCount, useCount;
    private List<Double> effectParameters = new ArrayList<>();
    public Miracle() {}
    Miracle(int id) {
      this.id = id; acquisitionId = UUID.randomUUID().toString();
      // Only fixed-source miracle4 (one use) is admitted by this bounded route.
      totalCount = 1; effectParameters.add(1.0);
    }
  }
  @Entity(useDiscriminator = false) @Getter
  public static final class Shop {
    private String runId, sourceCommit, rule;
    private int layer, cell, room, group, instance, npc, price;
    private List<Integer> ids = new ArrayList<>();
    private Set<Integer> purchased = new LinkedHashSet<>();
    public Shop() {}
    Shop(ChessRogueState run) {
      runId = run.runId; sourceCommit = run.sourceCommit; rule = run.routeRule;
      layer = run.layerIndex; cell = run.enteredCell; room = 1310601;
      group = 35; instance = 400001; npc = 1013; price = run.miraclePrice; ids.add(4);
    }
  }
  @Entity(useDiscriminator = false) @Getter
  public static final class ReturnLocation {
    private int plane, floor, entry, dimension;
    private Position pos, rot;
    private String sourceSignature = "";
    public ReturnLocation() {}
    ReturnLocation(Player player) {
      plane = player.getPlaneId(); floor = player.getFloorId(); entry = player.getEntryId();
      dimension = player.getScene().getDimensionId();
      pos = new Position(player.getPos()); rot = new Position(player.getRot());
      sourceSignature = ChessRogueRoutes.returnSignature(player.getScene(),entry);
    }
    void bindSignature(String value) { sourceSignature = value; }
  }
}
