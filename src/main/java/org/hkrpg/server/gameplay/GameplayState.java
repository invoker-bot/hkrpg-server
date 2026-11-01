package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import lombok.Getter;

/** Persistent state; queries cannot manufacture completed objectives or rewards. */
@Entity(useDiscriminator = false)
@Getter
public class GameplayState {
  // Absent in old saves: the inherited currentBgm remains the initial selection until first save.
  @lombok.Setter private JukeboxState jukebox;
  private ShopPurchaseState shopPurchases = new ShopPurchaseState();
  public ShopPurchaseState getShopPurchases() {
    if (shopPurchases == null) shopPurchases = new ShopPurchaseState();
    return shopPurchases;
  }
  private ServerPrefsState serverPrefs = new ServerPrefsState();

  public ServerPrefsState getServerPrefs() { return serverPrefs; }

  private DestructibleRewardState destructibleRewards = new DestructibleRewardState();
  public DestructibleRewardState getDestructibleRewards() { return destructibleRewards; }
  void destructibleRewards(DestructibleRewardState value) { destructibleRewards = Objects.requireNonNull(value); }
  private BookPickupState bookPickups = new BookPickupState();
  public BookPickupState getBookPickups() {
    if (bookPickups == null) bookPickups = new BookPickupState();
    return bookPickups;
  }
  void bookPickups(BookPickupState value) { bookPickups = Objects.requireNonNull(value); }
  private TutorialProgress tutorials = new TutorialProgress();

  public TutorialProgress getTutorials() {
    if (tutorials == null) tutorials = new TutorialProgress();
    return tutorials;
  }

  // Client UseID belongs to a live session; it must never become an H2/Mongo receipt.
  @dev.morphia.annotations.Transient
  private transient MazeCastState mazeCasts = new MazeCastState();

  public MazeCastState getMazeCasts() {
    if (mazeCasts == null) mazeCasts = new MazeCastState();
    return mazeCasts;
  }

  private KraterOfferingState kraterOffering = new KraterOfferingState();
  public KraterOfferingState getKraterOffering() {
    if (kraterOffering == null) kraterOffering = new KraterOfferingState();
    return kraterOffering;
  }
  void kraterOffering(KraterOfferingState value) { kraterOffering = Objects.requireNonNull(value); }
  private KraterOfferingState worldShopOffering = new KraterOfferingState();
  public KraterOfferingState getWorldShopOffering() {
    if (worldShopOffering == null) worldShopOffering = new KraterOfferingState();
    return worldShopOffering;
  }
  void worldShopOffering(KraterOfferingState value) { worldShopOffering = Objects.requireNonNull(value); }
  private WorldChestState worldChests = new WorldChestState();
  public WorldChestState getWorldChests() {
    if (worldChests == null) worldChests = new WorldChestState();
    return worldChests;
  }
  void worldChests(WorldChestState value) { worldChests = Objects.requireNonNull(value); }
  private WorldMechanismState worldMechanisms = new WorldMechanismState();
  private CompatibleMechanismState compatibleMechanisms = new CompatibleMechanismState();
  public CompatibleMechanismState getCompatibleMechanisms() {
    if (compatibleMechanisms == null) compatibleMechanisms = new CompatibleMechanismState();
    return compatibleMechanisms;
  }
  void compatibleMechanisms(CompatibleMechanismState value) { compatibleMechanisms = Objects.requireNonNull(value); }
  public WorldMechanismState getWorldMechanisms() {
    if (worldMechanisms == null) worldMechanisms = new WorldMechanismState();
    return worldMechanisms;
  }
  void worldMechanisms(WorldMechanismState value) { worldMechanisms = Objects.requireNonNull(value); }
  private WorldFirstTalkState worldFirstTalks = new WorldFirstTalkState();
  public WorldFirstTalkState getWorldFirstTalks() {
    if (worldFirstTalks == null) worldFirstTalks = new WorldFirstTalkState();
    return worldFirstTalks;
  }
  void worldFirstTalks(WorldFirstTalkState value) { worldFirstTalks = Objects.requireNonNull(value); }
  private NpcFirstTalkState npcFirstTalks = new NpcFirstTalkState();

  public NpcFirstTalkState getNpcFirstTalks() {
    if (npcFirstTalks == null) npcFirstTalks = new NpcFirstTalkState();
    return npcFirstTalks;
  }

  void npcFirstTalks(NpcFirstTalkState value) { npcFirstTalks = Objects.requireNonNull(value); }
  private DailyRecoverIntroductionState dailyRecoverIntroductions = new DailyRecoverIntroductionState();
  void dailyRecoverIntroductions(DailyRecoverIntroductionState value) { dailyRecoverIntroductions = Objects.requireNonNull(value); }
  private DailyOriginEffectsState dailyOriginEffects = new DailyOriginEffectsState();

  public DailyOriginEffectsState getDailyOriginEffects() {
    if (dailyOriginEffects == null) dailyOriginEffects = new DailyOriginEffectsState();
    return dailyOriginEffects;
  }

  void dailyOriginEffects(DailyOriginEffectsState value) {
    dailyOriginEffects = Objects.requireNonNull(value);
  }
  private AetherDivideState aetherDivide = new AetherDivideState();

  // Explicit null is damaged saved data. Do not repair it while answering a query.
  public AetherDivideState getAetherDivide() { return aetherDivide; }

  void aetherDivide(AetherDivideState value) { aetherDivide = Objects.requireNonNull(value); }
  private MaterialSubmitState materialSubmission = new MaterialSubmitState();
  public MaterialSubmitState getMaterialSubmission() { return materialSubmission; }
  void materialSubmission(MaterialSubmitState state) { materialSubmission = Objects.requireNonNull(state); }

  private ChessRogueState chessRogue = new ChessRogueState();

  public ChessRogueState getChessRogue() {
    if (chessRogue == null) chessRogue = new ChessRogueState();
    return chessRogue;
  }

  public void setChessRogue(ChessRogueState value) { chessRogue = Objects.requireNonNull(value); }
  private CavalryBeastState cavalryBeasts = new CavalryBeastState();
  public CavalryBeastState getCavalryBeasts() {
    if (cavalryBeasts == null) cavalryBeasts = new CavalryBeastState();
    return cavalryBeasts;
  }
  void cavalryBeasts(CavalryBeastState value) { cavalryBeasts = Objects.requireNonNull(value); }
  private ActivityKillState activityKillQuests = new ActivityKillState();
  public ActivityKillState getActivityKillQuests() {
    if (activityKillQuests == null) activityKillQuests = new ActivityKillState();
    return activityKillQuests;
  }
  void activityKillQuests(ActivityKillState value) { activityKillQuests = Objects.requireNonNull(value); }
  private TimelineActivityState timelineActivityQuests=new TimelineActivityState();
  public TimelineActivityState getTimelineActivityQuests() {
    if(timelineActivityQuests==null)timelineActivityQuests=new TimelineActivityState();return timelineActivityQuests;
  }
  void timelineActivityQuests(TimelineActivityState value){timelineActivityQuests=Objects.requireNonNull(value);}
  private PropTimelineState propTimelines = PropTimelineState.empty();
  private ScenePropState scenePropStates = ScenePropState.empty();
  public ScenePropState getScenePropStates() {
    if (scenePropStates == null) scenePropStates = ScenePropState.empty();
    return scenePropStates;
  }
  void scenePropStates(ScenePropState value) { scenePropStates = Objects.requireNonNull(value); }
  public PropTimelineState getPropTimelines() {
    if(propTimelines==null)propTimelines=PropTimelineState.empty();
    return propTimelines;
  }
  void propTimelines(PropTimelineState value){propTimelines=Objects.requireNonNull(value);}
  private RaidDifficultySettings raidDifficultySettings = new RaidDifficultySettings();

  public RaidDifficultySettings getRaidDifficultySettings() {
    if (raidDifficultySettings == null) raidDifficultySettings = new RaidDifficultySettings();
    return raidDifficultySettings;
  }

  private StageEventDifficultySettings stageEventDifficultySettings = new StageEventDifficultySettings();

  public StageEventDifficultySettings getStageEventDifficultySettings() {
    if (stageEventDifficultySettings == null) stageEventDifficultySettings = new StageEventDifficultySettings();
    return stageEventDifficultySettings;
  }

  private MissionTrialState missionTrials = new MissionTrialState();

  public MissionTrialState getMissionTrials() {
    if (missionTrials == null) missionTrials = new MissionTrialState();
    return missionTrials;
  }

  private BoxingClubState boxingClub = new BoxingClubState();

  public BoxingClubState getBoxingClub() {
    if (boxingClub == null) boxingClub = new BoxingClubState();
    return boxingClub;
  }

  void boxingClub(BoxingClubState value) { boxingClub = java.util.Objects.requireNonNull(value); }

  private BoxingClubResonanceState boxingClubResonance = new BoxingClubResonanceState();

  public BoxingClubResonanceState getBoxingClubResonance() {
    if (boxingClubResonance == null) boxingClubResonance = new BoxingClubResonanceState();
    return boxingClubResonance;
  }

  void boxingClubResonance(BoxingClubResonanceState value) {
    boxingClubResonance = java.util.Objects.requireNonNull(value);
  }

  private LocalLegendProgress localLegendProgress = new LocalLegendProgress();

  private BoxingClubActivityQuestState boxingClubActivityQuests = new BoxingClubActivityQuestState();

  public BoxingClubActivityQuestState getBoxingClubActivityQuests() {
    if (boxingClubActivityQuests == null) boxingClubActivityQuests = new BoxingClubActivityQuestState();
    return boxingClubActivityQuests;
  }

  void boxingClubActivityQuests(BoxingClubActivityQuestState value) {
    boxingClubActivityQuests = java.util.Objects.requireNonNull(value);
  }

  public LocalLegendProgress getLocalLegendProgress() {
    if (localLegendProgress == null) localLegendProgress = new LocalLegendProgress();
    return localLegendProgress;
  }

  void localLegendProgress(LocalLegendProgress value) {
    localLegendProgress = java.util.Objects.requireNonNull(value);
  }

  private WolfBroState wolfBro = new WolfBroState();

  private ExpeditionState expeditions = new ExpeditionState();

  public ExpeditionState getExpeditions() {
    if (expeditions == null) expeditions = new ExpeditionState();
    return expeditions;
  }

  private CompositionLimits compositionLimits = new CompositionLimits();
  private ItemSaleState itemSale = new ItemSaleState();

  public ItemSaleState getItemSale() {
    if (itemSale == null) itemSale = new ItemSaleState();
    return itemSale;
  }

  public CompositionLimits getCompositionLimits() {
    if (compositionLimits == null) compositionLimits = new CompositionLimits();
    return compositionLimits;
  }

  private Map<Integer, QuestState> mainMissions = new HashMap<>();
  private Map<Integer, QuestState> subMissions = new HashMap<>();
  private Map<Integer, Long> missionAcceptedDays = new HashMap<>();
  private Map<Integer, QuestState> dailyQuests = new HashMap<>();
  private DailyCycleQuestState dailyCycleQuests = new DailyCycleQuestState();

  public DailyCycleQuestState getDailyCycleQuests() {
    if (dailyCycleQuests == null) dailyCycleQuests = new DailyCycleQuestState();
    return dailyCycleQuests;
  }
  private DailyMissionSelections dailyMissionSelections = new DailyMissionSelections();
  private DailyMissionPeriodState dailyMissionPeriods = new DailyMissionPeriodState();
  private DailyMissionSelectedItemState dailyMissionSelectedItems = new DailyMissionSelectedItemState();
    private DailyRecoverPerformanceState dailyRecoverPerformances = new DailyRecoverPerformanceState();
    public DailyRecoverPerformanceState getDailyRecoverPerformances() {
      if (dailyRecoverPerformances == null) dailyRecoverPerformances = new DailyRecoverPerformanceState();
      return dailyRecoverPerformances;
    }
    private DailyMissionRecoverDeliveryState dailyMissionRecoverDelivery = new DailyMissionRecoverDeliveryState();
  public DailyMissionRecoverDeliveryState getDailyMissionRecoverDelivery() {
    if (dailyMissionRecoverDelivery == null) dailyMissionRecoverDelivery = new DailyMissionRecoverDeliveryState();
    return dailyMissionRecoverDelivery;
  }
  void dailyMissionRecoverDelivery(DailyMissionRecoverDeliveryState value) { dailyMissionRecoverDelivery = Objects.requireNonNull(value); }
  public DailyMissionSelectedItemState getDailyMissionSelectedItems() {
    if (dailyMissionSelectedItems == null) dailyMissionSelectedItems = new DailyMissionSelectedItemState();
    return dailyMissionSelectedItems;
  }
  void dailyMissionSelectedItems(DailyMissionSelectedItemState value) { dailyMissionSelectedItems = Objects.requireNonNull(value); }
  void dailyMissionSelections(DailyMissionSelections value) { dailyMissionSelections = Objects.requireNonNull(value); }
  private Map<String, Receipt> rewards = new HashMap<>();
  private Map<Integer, AvatarRankChange> avatarRankChanges = new HashMap<>();

  public Map<Integer, AvatarRankChange> getAvatarRankChanges() {
    if (avatarRankChanges == null) avatarRankChanges = new HashMap<>();
    return avatarRankChanges;
  }
  private Map<String, MissionRewardPlan> missionRewardPlans = new HashMap<>();
  private Map<Integer, LoginDays> activityDays = new HashMap<>();
  private long dailyDay = Long.MIN_VALUE;
  private int dailyPoints;
  private RogueRun rogue = new RogueRun();
  private int rogueSelectionSequence;
  private int rogueDialogueSequence;
  private Map<String, Integer> storyValues = new HashMap<>();
  private Map<Integer, ActionCursor> storyActions = new HashMap<>();
  private StoryPartyState storyParty = new StoryPartyState();

  public StoryPartyState getStoryParty() { return storyParty; }
  private Map<Integer, ActionCursor> missionPayments = new HashMap<>();
  private Map<Integer, MissionPaymentPlan> missionPaymentPlans = new HashMap<>();
  private Map<Integer, Set<Integer>> missionAcquiredItems = new HashMap<>();
  private Set<Integer> cancelledMissions = new HashSet<>();
  private Set<Integer> cancelledSubs = new HashSet<>();
  private Rhythm rhythm = new Rhythm();
  private ClockParkState clockPark = new ClockParkState();

  void clockPark(ClockParkState value) {
    clockPark = java.util.Objects.requireNonNull(value);
  }
  private MatchThree matchThree = new MatchThree();
  private DiceCombat diceCombat = new DiceCombat();
  private DiceRollState diceRolls = new DiceRollState();
  private Map<Integer, Offering> offerings = new HashMap<>();
  private List<Integer> equippedOutfits = new ArrayList<>();

  public List<Integer> getEquippedOutfits() {
    return equippedOutfits == null ? List.of() : Collections.unmodifiableList(equippedOutfits);
  }

  public void replaceEquippedOutfits(List<Integer> ids) {
    equippedOutfits = new ArrayList<>(ids);
  }

  public Map<Integer, Offering> getOfferings() {
    if (offerings == null) offerings = new HashMap<>();
    return offerings;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Offering {
    private int id, item, level, levelExp, totalExp;
    private List<Integer> costs = new ArrayList<>(), rewardIds = new ArrayList<>();
    private List<OfferingSubmission> submissions = new ArrayList<>();
    private List<OfferingClaim> claims = new ArrayList<>();

    protected Offering() {}

    public Offering(OfferingCatalog catalog) {
      id = catalog.id();
      item = catalog.item();
      costs = new ArrayList<>(catalog.costs());
      rewardIds = new ArrayList<>(catalog.rewards());
    }

    public void apply(OfferingSubmission receipt) {
      if (receipt.isPaid()
          || submissions.isEmpty()
          || submissions.getLast() != receipt
          || totalExp != receipt.getBefore())
        throw new IllegalStateException("Invalid offering debit");
      totalExp = Math.addExact(totalExp, receipt.getAmount());
      level = 0;
      levelExp = totalExp;
      while (level < costs.size() && levelExp >= costs.get(level)) levelExp -= costs.get(level++);
      receipt.paid = true;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class OfferingSubmission {
    private int before, amount;
    private boolean paid;

    protected OfferingSubmission() {}

    public OfferingSubmission(int before, int amount) {
      this.before = before;
      this.amount = amount;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class OfferingClaim {
    private List<Integer> levels = new ArrayList<>();
    private Map<Integer, Integer> items = new HashMap<>();
    private Map<Integer, DiceAvatar> avatars = new HashMap<>();
    private Map<Integer, Long> dice = new HashMap<>();
    private boolean granted;

    protected OfferingClaim() {}

    public OfferingClaim(List<Integer> levels, Map<Integer, Integer> items) {
      this.levels = new ArrayList<>(levels);
      this.items = new HashMap<>(items);
    }

    public OfferingClaim(
        List<Integer> levels,
        Map<Integer, Integer> items,
        Map<Integer, DiceAvatar> avatars,
        Map<Integer, Long> dice) {
      this(levels, items);
      this.avatars = new HashMap<>(avatars);
      this.dice = new HashMap<>(dice);
    }

    public void complete() {
      granted = true;
    }
  }

  public DiceCombat getDiceCombat() {
    if (diceCombat == null) diceCombat = new DiceCombat();
    return diceCombat;
  }

  public DiceRollState getDiceRolls() {
    if (diceRolls == null) diceRolls = new DiceRollState();
    return diceRolls;
  }

  void diceRolls(DiceRollState value) {
    diceRolls = value;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class DiceCombat {
    private Map<Integer, DiceAvatar> avatars = new HashMap<>();
    private Map<Integer, Long> dice = new HashMap<>();
    private Map<Integer, DicePurchase> purchases = new HashMap<>();
    private List<DiceUpgrade> upgrades = new ArrayList<>();
    private Map<Integer, DicePveWin> pveWins = new HashMap<>();

    public Map<Integer, DicePveWin> getPveWins() {
      return pveWins == null ? Map.of() : Collections.unmodifiableMap(pveWins);
    }

    void reserve(DicePveWin win) {
      if (pveWins == null) pveWins = new HashMap<>();
      if (pveWins.putIfAbsent(win.getStage(), win) != null)
        throw new IllegalStateException("PVE first win already reserved");
    }

    public List<DiceUpgrade> getUpgrades() {
      return upgrades == null ? List.of() : Collections.unmodifiableList(upgrades);
    }

    void reserve(DiceUpgrade receipt) {
      if (upgrades == null) upgrades = new ArrayList<>();
      upgrades.add(receipt);
    }
  }

  /** Client-reported victory, with rewards and accepted mission targets frozen before grants. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class DicePveWin {
    private int stage, reward, plane, floor;
    // Native tag 4 is retained without inferring avatar, turn or damage semantics.
    private long nativeScalar;
    private Map<Integer, Integer> items = new HashMap<>();
    private Set<Integer> targets = new HashSet<>();
    private boolean applied, missionEventsDone;

    protected DicePveWin() {}

    public DicePveWin(
        int stage,
        int reward,
        int plane,
        int floor,
        long scalar,
        Map<Integer, Integer> items,
        Set<Integer> targets) {
      this.stage = stage;
      this.reward = reward;
      this.plane = plane;
      this.floor = floor;
      this.nativeScalar = scalar;
      this.items = new HashMap<>(items);
      this.targets = new HashSet<>(targets);
    }

    void applied(boolean value) {
      applied = value;
    }

    void finishMissionEvents() {
      missionEventsDone = true;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class DiceAvatar {
    private int id;
    private int level = 1;
    private long time;
    private List<Integer> dice = new ArrayList<>();

    protected DiceAvatar() {}

    public DiceAvatar(int id, long time, List<Integer> dice) {
      this.id = id;
      this.time = time;
      this.dice = new ArrayList<>(dice);
    }

    void setLevel(int value) {
      if (value < 1 || value > 3) throw new IllegalArgumentException("Invalid dice avatar level");
      level = value;
    }

    void replaceDice(List<Integer> value) {
      dice = new ArrayList<>(value);
    }
  }

  /** Frozen before inventory writes. An unpaid receipt is never retried automatically. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class DiceUpgrade {
    private int avatar, rarity, before, after, item, amount;
    private long acquisitionTime;
    private boolean paid;

    protected DiceUpgrade() {}

    public DiceUpgrade(
        int avatar, int rarity, int before, long acquisitionTime, int item, int amount) {
      this.avatar = avatar;
      this.rarity = rarity;
      this.before = before;
      this.after = before + 1;
      this.acquisitionTime = acquisitionTime;
      this.item = item;
      this.amount = amount;
    }

    void paid(boolean value) {
      paid = value;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class DicePurchase {
    private int shop, good, item;
    private long time;
    private Map<Integer, Integer> costs = new HashMap<>();
    private boolean granted;

    protected DicePurchase() {}

    public DicePurchase(int shop, int good, int item, long time, Map<Integer, Integer> costs) {
      this.shop = shop;
      this.good = good;
      this.item = item;
      this.time = time;
      this.costs = new HashMap<>(costs);
    }

    public void complete() {
      granted = true;
    }
  }

  private Parkour parkour = new Parkour();
  private ParkourVenue parkourVenue = new ParkourVenue();
  private Map<Integer, MissionRaidRun> missionRaids = new HashMap<>();
  private int currentMissionRaid;
  private Map<Integer, Integer> rogueWins = new HashMap<>();
  private Set<Integer> rogueCollectedBuffs = new HashSet<>();
  private RogueHandbookState rogueHandbook = new RogueHandbookState();
  private RogueEnhancementState rogueEnhancements = new RogueEnhancementState();

  /** Undo one unacknowledged merchant operation without replacing the live run or NPC leases. */
  public void restoreMiracleShop(GameplayState prior) {
    if (prior == null) throw new IllegalArgumentException("Missing merchant snapshot");
    rogue.restoreMiracleShop(prior.rogue);
    rogueCollectedBuffs.clear();
    rogueCollectedBuffs.addAll(prior.rogueCollectedBuffs);
    rogueHandbook = prior.rogueHandbook;
    rogueEnhancements = prior.rogueEnhancements;
  }
  private RogueDestructionState rogueDestructions = new RogueDestructionState();
  private RogueBattleKillState rogueBattleKills = new RogueBattleKillState();
  private RogueBattleDeathState rogueBattleDeaths = new RogueBattleDeathState();
  private Map<Integer, PhoneSection> phoneSections = new HashMap<>();
  private DailyMissionMessageState dailyMissionMessages = new DailyMissionMessageState();
  private Map<Integer, FirstTalkReport> firstTalkReports = new HashMap<>();
  private Map<String, RogueResult> rogueResults = new HashMap<>();
  private Set<Integer> rogueTalents = new HashSet<>();
  private Map<String, CycleWeek> cycleWeeks = new HashMap<>();
  private Map<Integer, Integer> rogueCurrencies = new HashMap<>();
  private ItemUseReceipt lastItemUse;
  private Set<Integer> learnedFormulas = new HashSet<>();

  public Set<Integer> getLearnedFormulas() {
    return learnedFormulas == null ? Set.of() : Collections.unmodifiableSet(learnedFormulas);
  }

  public boolean learnFormula(int item) {
    if (item <= 0) return false;
    if (learnedFormulas == null) learnedFormulas = new HashSet<>();
    return learnedFormulas.add(item);
  }

  private StoryLineState storyLines = new StoryLineState();
  private Map<Integer, TrialCompletion> trialCompletions = new HashMap<>();
  private TrialRun trialRun;
  private Map<Integer, Integer> heartDialEmotions = new HashMap<>();
  private Map<Integer, HeartDialReport> heartDialReports = new HashMap<>();
  private int heartDialUnlockNotified;
  private Map<Integer, HeartDialPayment> heartDialPayments = new HashMap<>();
  private Map<Integer, HeartDialLock> heartDialLocks = new HashMap<>();
  private Set<Integer> completedMemoryChallenges = new HashSet<>();

  public Set<Integer> getCompletedMemoryChallenges() {
    return completedMemoryChallenges == null
        ? Set.of()
        : Collections.unmodifiableSet(completedMemoryChallenges);
  }

  public boolean recordMemoryChallenge(int id) {
    if (id <= 0) return false;
    if (completedMemoryChallenges == null) completedMemoryChallenges = new HashSet<>();
    return completedMemoryChallenges.add(id);
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class HeartDialLock {
    private int script, raid, missingCondition, missingItem, lockCondition, lockItem;
    private int unlockCondition, unlockMission;
    private HeartDialPayment missingPayment, lockPayment;
    private boolean normalUnlocked;
    private int notifiedStep = -1;

    protected HeartDialLock() {}

    public HeartDialLock(HeartDialLocks.Rule rule) {
      script = rule.script();
      raid = rule.raid();
      missingCondition = rule.missingCondition();
      missingItem = rule.missingItem();
      lockCondition = rule.lockCondition();
      lockItem = rule.lockItem();
      unlockCondition = rule.unlockCondition();
      unlockMission = rule.unlockMission();
    }

    public void reserve(int step, HeartDialPayment payment) {
      if (step == 0 && missingCondition > 0 && missingPayment == null && lockPayment == null)
        missingPayment = payment;
      else if (step == 2
          && lockPayment == null
          && (missingCondition == 0 || missingPayment != null && missingPayment.isPaid()))
        lockPayment = payment;
      else throw new IllegalStateException("Clockwork phase already reserved or out of order");
    }

    public boolean pending() {
      return missingPayment != null && !missingPayment.isPaid()
          || lockPayment != null && !lockPayment.isPaid();
    }

    public void unlockNormal() {
      normalUnlocked = true;
    }

    public void recordNotifiedStep(int step) {
      if (step == 4) unlockNormal();
      notifiedStep = step;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class HeartDialPayment {
    private int script;
    private int condition;
    private int item;
    private int plane;
    private int floor;
    private int group;
    private int instance;
    private boolean paid;
    private boolean normalUnlocked;
    private int notifiedStep = -1;

    protected HeartDialPayment() {}

    public HeartDialPayment(
        int script, int condition, int item, int plane, int floor, int group, int instance) {
      this.script = script;
      this.condition = condition;
      this.item = item;
      this.plane = plane;
      this.floor = floor;
      this.group = group;
      this.instance = instance;
    }

    public void complete() {
      paid = true;
    }

    public void unlockNormal() {
      normalUnlocked = true;
    }

    public void recordNotifiedStep(int step) {
      notifiedStep = step;
    }
  }

  public void recordHeartDialUnlock(int value) {
    heartDialUnlockNotified = value;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class HeartDialReport {
    private int step = 4;
    private int script;
    private int emotion;
    private int plane;
    private int floor;
    private int group;
    private int instance;
    private Set<Integer> targets = new HashSet<>();
    private boolean applied;

    protected HeartDialReport() {}

    public HeartDialReport(
        int script,
        int emotion,
        int plane,
        int floor,
        int group,
        int instance,
        Set<Integer> targets) {
      this(script, emotion, plane, floor, group, instance, targets, 4);
    }

    public HeartDialReport(
        int script,
        int emotion,
        int plane,
        int floor,
        int group,
        int instance,
        Set<Integer> targets,
        int step) {
      this.step = step;
      this.script = script;
      this.emotion = emotion;
      this.plane = plane;
      this.floor = floor;
      this.group = group;
      this.instance = instance;
      this.targets.addAll(targets);
    }

    public void complete() {
      applied = true;
    }
  }

  public void setTrialRun(TrialRun run) {
    trialRun = run;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class MatchThree {
    private Map<Integer, MatchThreeBird> birds = new HashMap<>();
    private Map<String, MatchThreeLevel> completed = new HashMap<>();
    private Map<Integer, Integer> statistics = new HashMap<>();
    private Map<String, MatchThreeReport> reports = new HashMap<>();
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class MatchThreeBird {
    private int position;
    private int count;
    private int maximum;

    public void position(int value) {
      position = value;
    }

    public void result(int uses, int power) {
      count = uses;
      maximum = power;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class MatchThreeLevel {
    private int id;
    private int mode;

    protected MatchThreeLevel() {}

    public MatchThreeLevel(int id, int mode) {
      this.id = id;
      this.mode = mode;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class MatchThreeReport {
    private String uuid;
    private int id;
    private int mode;
    private int bird;
    private int power;
    private int count;
    private int maximum;
    private Map<Integer, Integer> reported = new HashMap<>();
    private Map<Integer, Integer> totals = new HashMap<>();
    private Map<Integer, Integer> rewards = new HashMap<>();
    private Set<Integer> missionTargets = new HashSet<>();
    private boolean missionEventsDone;
    private boolean applied;

    protected MatchThreeReport() {}

    public MatchThreeReport(
        String uuid,
        int id,
        int mode,
        int bird,
        int power,
        int count,
        int maximum,
        Map<Integer, Integer> reported,
        Map<Integer, Integer> totals,
        Map<Integer, Integer> rewards,
        Set<Integer> missionTargets) {
      this.uuid = uuid;
      this.id = id;
      this.mode = mode;
      this.bird = bird;
      this.power = power;
      this.count = count;
      this.maximum = maximum;
      this.reported.putAll(reported);
      this.totals.putAll(totals);
      this.rewards.putAll(rewards);
      this.missionTargets.addAll(missionTargets);
    }

    public boolean matches(
        String uuid, int id, int mode, int bird, int power, Map<Integer, Integer> reported) {
      return Objects.equals(this.uuid, uuid)
          && this.id == id
          && this.mode == mode
          && this.bird == bird
          && this.power == power
          && Objects.equals(this.reported, reported);
    }

    public void apply() {
      applied = true;
    }

    public void finishMissionEvents() {
      missionEventsDone = true;
    }
  }

  /** Confirmed NPC victory and the quantities captured before entering its trial. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class TrialCompletion {
    private int rewardId;
    private Map<Integer, Integer> items = new HashMap<>();
    private boolean ready;
    private Map<Integer, Integer> received = new HashMap<>();

    protected TrialCompletion() {}

    public TrialCompletion(int rewardId, Map<Integer, Integer> items, boolean ready) {
      this.rewardId = rewardId;
      this.items.putAll(items);
      this.ready = ready;
    }

    public void received(java.util.List<emu.lunarcore.game.inventory.GameItem> actual) {
      for (var item : actual) received.merge(item.getItemId(), item.getCount(), Math::addExact);
    }
  }

  public ItemUseReceipt reserveItemUse(
      int item, int count, int avatar, Map<Integer, Integer> reward) {
    long sequence = lastItemUse == null ? 1 : Math.addExact(lastItemUse.sequence, 1);
    lastItemUse = new ItemUseReceipt(sequence, item, count, avatar, reward);
    return lastItemUse;
  }

  /** Frozen actual costs, rather than every alternative offered by a selection task. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class MissionPaymentPlan {
    private String finishType;
    private Map<Integer, Integer> costs = new HashMap<>();

    protected MissionPaymentPlan() {}

    public MissionPaymentPlan(String finishType, Map<Integer, Integer> costs) {
      this.finishType = finishType;
      this.costs.putAll(costs);
    }
  }

  /** One retained transaction, including an interrupted debit/grant, survives reconnect. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class ItemUseReceipt {
    private long sequence;
    private int itemId;
    private int count;
    private int avatarId;
    private String phase = "RESERVED";
    private Map<Integer, Integer> rewards = new HashMap<>();
    private Map<Integer, Integer> received = new HashMap<>();

    protected ItemUseReceipt() {}

    public ItemUseReceipt(
        long sequence, int item, int count, int avatar, Map<Integer, Integer> reward) {
      this.sequence = sequence;
      this.itemId = item;
      this.count = count;
      this.avatarId = avatar;
      rewards.putAll(reward);
    }

    public boolean pending() {
      return !"COMPLETE".equals(phase) && !"CANCELLED".equals(phase);
    }

    public void debited() {
      phase = "DEBITED";
    }

    public void cancel() {
      phase = "CANCELLED";
    }

    public void settle(List<emu.lunarcore.game.inventory.GameItem> items, boolean complete) {
      for (var item : items) received.merge(item.getItemId(), item.getCount(), Math::addExact);
      phase = complete ? "COMPLETE" : "PARTIAL";
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class CycleWeek {
    private long start;
    private long end;
    private int score;
    private Map<Integer, CycleClaim> claims = new HashMap<>();

    protected CycleWeek() {}

    public CycleWeek(long start, long end) {
      this.start = start;
      this.end = end;
    }

    public boolean credit(int points) {
      long next = (long) score + points;
      if (points < 0 || next > Integer.MAX_VALUE) return false;
      score = (int) next;
      return true;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class CycleClaim {
    private int rewardId;
    private Map<Integer, Integer> items = new HashMap<>();
    private boolean discardable;
    private boolean exceedPending;
    private boolean discarded;

    protected CycleClaim() {}

    public CycleClaim(int rewardId, Map<Integer, Integer> items, boolean discardable) {
      this.rewardId = rewardId;
      this.items.putAll(items);
      this.discardable = discardable;
    }

    public void markExceed() {
      exceedPending = true;
    }

    public void discard() {
      discarded = true;
    }
  }

  /**
   * Terminal records survive the next run; pending first rewards retain their original quantities.
   */
  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueResult {
    private int areaId;
    private RogueRun.Phase outcome;
    private int passedRooms;
    private int reachedRooms;
    private List<Integer> avatars = new ArrayList<>();
    private Map<Integer, Integer> avatarLevels = new HashMap<>();
    private Set<Integer> trialAvatars = new HashSet<>();
    private Map<Integer, Integer> blessings = new HashMap<>();
    private List<Integer> miracles = new ArrayList<>();
    private int firstRewardId;
    private Map<Integer, Integer> firstRewardPlan = new HashMap<>();
    private boolean firstRewardReady;
    private boolean rewardSettled;
    private byte[] firstRewardItems = new byte[0];
    private int roomScore;
    private int talentCoin;
    private boolean earningsReady;
    private boolean earningsSettled;
    private long cycleStart;
    private long cycleEnd;
    private int cyclePoints;
    private boolean cycleReady;
    private boolean cycleSettled;
    // Null old records cannot invent a target that was accepted after their score credit.
    private Map<Integer, String> exploreMissionTargets;

    void observeExploreTargets(Map<Integer, String> targets) {
      if (cycleSettled || exploreMissionTargets != null)
        throw new IllegalStateException("Exploration credit targets already observed");
      exploreMissionTargets = new HashMap<>(targets);
    }

    protected RogueResult() {}

    public RogueResult(
        emu.lunarcore.game.player.Player player, RogueRun run, RogueRun.Phase outcome, int reward) {
      this.areaId = run.areaId;
      this.outcome = outcome;
      passedRooms = run.cleared.size();
      reachedRooms =
          passedRooms
              + (run.rooms.containsKey(run.siteId) && !run.cleared.contains(run.siteId) ? 1 : 0);
      avatars.addAll(run.roster);
      for (int id : avatars) {
        var avatar = player.getAvatarById(id);
        if (avatar != null) avatarLevels.put(id, avatar.getLevel());
        if (avatar != null && avatar.getAvatarType() == emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType.AVATAR_TRIAL_TYPE)
          trialAvatars.add(id);
      }
      blessings.putAll(run.blessings);
      miracles.addAll(new TreeSet<>(run.miracles.keySet()));
      var score = RogueEconomy.capture(run);
      roomScore = score.points();
      talentCoin = score.coins();
      earningsReady = score.ready();
      earningsSettled = earningsReady && talentCoin == 0;
      player
          .getCycleScoreManager()
          .freeze(run, score.points(), score.ready())
          .ifPresent(
              credit -> {
                cycleStart = credit.start();
                cycleEnd = credit.end();
                cyclePoints = credit.points();
                cycleReady = true;
                cycleSettled = cyclePoints == 0;
              });
      firstRewardId = reward;
      firstRewardReady = reward == 0;
      rewardSettled = reward == 0;
      if (reward <= 0) return;
      var definition = emu.lunarcore.data.GameData.getRewardExcelMap().get(reward);
      if (definition == null || definition.getRewards() == null) return;
      firstRewardReady = true;
      for (var item : definition.getRewards()) {
        if (item.getIntKey() <= 0
            || item.getIntValue() <= 0
            || emu.lunarcore.data.GameData.getItemExcelMap().get(item.getIntKey()) == null) {
          firstRewardReady = false;
          continue;
        }
        try {
          firstRewardPlan.merge(item.getIntKey(), item.getIntValue(), Math::addExact);
        } catch (ArithmeticException e) {
          firstRewardReady = false;
        }
      }
    }

    public void settle(java.util.List<emu.lunarcore.game.inventory.GameItem> items) {
      var list = emu.lunarcore.proto.ItemListOuterClass.ItemList.newInstance();
      items.forEach(item -> list.addItemList(item.toProto()));
      firstRewardItems = list.toByteArray();
      rewardSettled = true;
    }

    public void settleEarnings() {
      earningsSettled = true;
    }

    public void settleCycle() {
      cycleSettled = true;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class FirstTalkReport {
    private int plane;
    private int floor;
    private List<Integer> subMissionIds = new ArrayList<>();
    private boolean completed;

    protected FirstTalkReport() {}

    public FirstTalkReport(int plane, int floor, List<Integer> ids) {
      this.plane = plane;
      this.floor = floor;
      this.subMissionIds.addAll(ids);
    }

    public void complete() {
      completed = true;
    }
  }

  /**
   * Completion-time quantities and choices; an incomplete snapshot needs explicit reconciliation.
   */
  @Entity(useDiscriminator = false)
  @Getter
  public static class MissionRewardPlan {
    private List<Integer> rewardIds = new ArrayList<>();
    private Map<String, Integer> choices = new HashMap<>();
    private Map<Integer, Integer> items = new HashMap<>();
    private boolean ready;

    protected MissionRewardPlan() {}

    public MissionRewardPlan(
        List<Integer> ids,
        Map<String, Integer> choices,
        Map<Integer, Integer> items,
        boolean ready) {
      this.rewardIds.addAll(ids);
      this.choices.putAll(choices);
      this.items.putAll(items);
      this.ready = ready;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class PhoneSection {
    private int id;
    private long refreshed;
    private boolean finished;
    private List<Integer> history = new ArrayList<>();
    private List<Integer> offered = new ArrayList<>();

    protected PhoneSection() {}

    public PhoneSection(int id, List<Integer> start) {
      this.id = id;
      offered.addAll(start);
      touch();
    }

    public PhoneSection copy() {
      var copy = new PhoneSection();
      copy.id = id;
      copy.refreshed = refreshed;
      copy.finished = finished;
      copy.history.addAll(history);
      copy.offered.addAll(offered);
      return copy;
    }

    public void step(int id, List<Integer> next) {
      history.add(id);
      offered.clear();
      offered.addAll(next);
      touch();
    }

    public void finish() {
      finished = true;
      touch();
    }

    private void touch() {
      refreshed = java.time.Instant.now().getEpochSecond();
    }
  }

  public boolean recordRogueWin(RogueRun run) {
    if (run != rogue
        || run.phase != RogueRun.Phase.FINISHED
        || run.historyRecorded
        || run.areaId <= 0) return false;
    rogueWins.merge(run.areaId, 1, Math::addExact);
    run.historyRecorded = true;
    return true;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RaidFormalTeam {
    private String type;
    private List<Integer> trials = new ArrayList<>();
    private Map<Integer, Integer> requirements = new HashMap<>();
    private List<Integer> replaceRules = new ArrayList<>();
    private int captain;
    private Map<Integer, Integer> levels = new HashMap<>();
    private Map<Integer, Integer> counts = new HashMap<>();
    private Map<Integer, Integer> paths = new HashMap<>();

    protected RaidFormalTeam() {}

    public RaidFormalTeam(RaidTeams.Plan plan) {
      type = plan.type();
      trials.addAll(plan.trials());
      requirements.putAll(plan.requirements());
      replaceRules.addAll(plan.replaceRules());
      captain = plan.captain();
      levels.putAll(plan.levels());
      counts.putAll(plan.counts());
      paths.putAll(plan.paths());
    }

    public List<Integer> getTrials() {
      return trials == null ? List.of() : List.copyOf(trials);
    }

    public Map<Integer, Integer> getRequirements() {
      return requirements == null ? Map.of() : Map.copyOf(requirements);
    }

    public List<Integer> getReplaceRules() {
      return replaceRules == null ? List.of() : List.copyOf(replaceRules);
    }

    public Map<Integer, Integer> getLevels() {
      return levels == null ? Map.of() : Map.copyOf(levels);
    }

    public Map<Integer, Integer> getCounts() {
      return counts == null ? Map.of() : Map.copyOf(counts);
    }

    public Map<Integer, Integer> getPaths() {
      return paths == null ? Map.of() : Map.copyOf(paths);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class MissionRaidRun {
    private PropTimelineState propTimelines = PropTimelineState.empty();
    private ScenePropState scenePropStates = ScenePropState.empty();
    public ScenePropState getScenePropStates() {
      if (scenePropStates == null) scenePropStates = ScenePropState.empty();
      return scenePropStates;
    }
    void scenePropStates(ScenePropState value) { scenePropStates = Objects.requireNonNull(value); }
    public PropTimelineState getPropTimelines() {
      if(propTimelines==null)propTimelines=PropTimelineState.empty();
      return propTimelines;
    }
    void propTimelines(PropTimelineState value){propTimelines=Objects.requireNonNull(value);}
    private int raidId;
    private int worldLevel;
    private int mainMission;
    private int plane;
    private int floor;
    private int entry;
    private RogueReturn checkpoint;
    private RogueReturn returnTo;
    private int pendingTransfer;
    private Map<Integer, Integer> transferredSubs = new HashMap<>();
    private boolean completed;
    private RogueReturn finishedReturn;
    private RaidFormalTeam formalTeamPlan;
    private RaidTrialParty trialParty;
    private MissionTrialState missionTrials = new MissionTrialState();
    private MissionRaidSequence sequence;
    private MissionRaidPayout payout;
    private RaidDifficultySettings.Frozen difficultySnapshot;

    public void freezeDifficulty(RaidDifficultySettings.Frozen value) {
      if (difficultySnapshot != null) throw new IllegalStateException("Existing raid difficulty");
      difficultySnapshot = value;
    }

    protected MissionRaidRun() {}

    public MissionRaidRun(int raid, int world, int main, int plane, int floor, int entry) {
      raidId = raid;
      worldLevel = world;
      mainMission = main;
      this.plane = plane;
      this.floor = floor;
      this.entry = entry;
    }

    public void returnFrom(emu.lunarcore.game.player.Player player) {
      returnTo = new RogueReturn(player);
    }

    public void freezeFormalTeam(RaidTeams.Plan plan) {
      if (formalTeamPlan != null) throw new IllegalStateException("Existing raid team plan");
      formalTeamPlan = new RaidFormalTeam(plan);
    }

    public void freezeTrialParty(RaidTrialParty party) {
      if (trialParty != null || party == null)
        throw new IllegalStateException("Existing or absent raid trial party");
      trialParty = party;
    }

    public void freezeSequence(GameplayDefinitions.Raid row) {
      if (sequence != null) throw new IllegalStateException("Existing raid mission sequence");
      sequence = new MissionRaidSequence(row);
    }

    public void freezePayout(GameplayDefinitions.Raid row) {
      if (payout != null) throw new IllegalStateException("Existing raid payout");
      payout = new MissionRaidPayout(row);
    }

    public void advanceSequence() {
      if (sequence == null) throw new IllegalStateException("Missing raid mission sequence");
      sequence.advance();
      mainMission = sequence.getMains().get(sequence.getStage());
    }

    public void checkpoint(emu.lunarcore.game.player.Player player) {
      checkpoint = new RogueReturn(player);
      plane = checkpoint.getPlane();
      floor = checkpoint.getFloor();
      entry = checkpoint.getEntry();
    }

    public void reserveTransfer(int sub, int plane, int floor, int entry) {
      pendingTransfer = sub;
      this.plane = plane;
      this.floor = floor;
      this.entry = entry;
    }

    public void cancelTransfer() {
      pendingTransfer = 0;
      if (checkpoint != null) {
        plane = checkpoint.getPlane();
        floor = checkpoint.getFloor();
        entry = checkpoint.getEntry();
      }
    }

    public void completeTransfer(int sub, emu.lunarcore.game.player.Player player) {
      checkpoint(player);
      transferredSubs.put(sub, entry);
      pendingTransfer = 0;
    }

    public void complete() {
      completed = true;
    }

    public void finishReturn(emu.lunarcore.game.player.Player player) {
      if (!completed)
        throw new IllegalStateException("Unfinished raid cannot record a finished return");
      finishedReturn = new RogueReturn(player);
    }
  }

  public void beginMissionRaid(MissionRaidRun run) {
    missionRaids.put(run.getRaidId(), run);
    currentMissionRaid = run.getRaidId();
  }

  public void endMissionRaid() {
    currentMissionRaid = 0;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class ParkourVenue {
    private int raidId;
    private int worldLevel;
    private int plane;
    private int floor;
    private int entry;
    private RogueReturn returnTo;

    public boolean inside() {
      return raidId > 0;
    }

    public void enter(
        int raid,
        int world,
        int plane,
        int floor,
        int entry,
        emu.lunarcore.game.player.Player player) {
      this.raidId = raid;
      this.worldLevel = world;
      this.plane = plane;
      this.floor = floor;
      this.entry = entry;
      this.returnTo = new RogueReturn(player);
    }

    public void close() {
      raidId = worldLevel = plane = floor = entry = 0;
      returnTo = null;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Parkour {
    private int activeLevel;
    private int activeBall;
    private int targetRank;
    private int floor;
    private long windowBegin;
    private long windowEnd;
    private int unlockSub;
    private boolean snapshotReady;
    private Map<Integer, ParkourRecord> records = new HashMap<>();
    private Map<String, ParkourQuestWindow> questWindows = new HashMap<>();

    public void start(
        int level, int ball, int rank, int floor, long begin, long end, int unlockSub) {
      activeLevel = level;
      activeBall = ball;
      targetRank = rank;
      this.floor = floor;
      windowBegin = begin;
      windowEnd = end;
      this.unlockSub = unlockSub;
      snapshotReady = true;
    }

    public void close() {
      activeLevel = 0;
      activeBall = 0;
      targetRank = 0;
      floor = 0;
      windowBegin = 0;
      windowEnd = 0;
      unlockSub = 0;
      snapshotReady = false;
    }
  }

  /** Activity results and receipts are separate for each explicit operator window. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class ParkourQuestWindow {
    private Map<Integer, ParkourRecord> records = new HashMap<>();
    private Map<Integer, QuestState> quests = new HashMap<>();
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class ParkourRecord {
    private int time;
    private int rank;
    private boolean passed;
    private int bestBall;

    public void record(int elapsed, int rank, boolean pass) {
      record(elapsed, rank, pass, 0);
    }

    public void record(int elapsed, int rank, boolean pass, int ball) {
      if (time == 0 || elapsed < time) {
        time = elapsed;
        bestBall = ball;
      }
      if (this.rank == 0 || rank < this.rank) this.rank = rank;
      passed |= pass;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Rhythm {
    private int activeLevel;
    private String transaction = "";
    private Map<Integer, Map<Integer, Integer>> starRewards = new HashMap<>();
    private boolean rewardsFrozen;
    private boolean awaitingRewards;
    private int pendingRating;
    private boolean pendingCombo;
    private Map<Integer, Integer> stars = new HashMap<>();
    private Set<Integer> fullCombos = new HashSet<>();
    private int currentSong;
    private Map<Integer, Composition> compositions = new HashMap<>();

    public void compose(int song, int preset, List<Integer> grid, List<Integer> effects) {
      compositions.put(song, new Composition(preset, grid, effects));
      currentSong = song;
    }

    public void start(int id) {
      activeLevel = id;
      transaction = UUID.randomUUID().toString();
      starRewards.clear();
      rewardsFrozen = false;
      awaitingRewards = false;
      pendingRating = 0;
      pendingCombo = false;
    }

    public void start(int id, List<Map<Integer, Integer>> rewards) {
      start(id);
      for (int i = 0; i < rewards.size(); i++)
        starRewards.put(i + 1, new HashMap<>(rewards.get(i)));
      rewardsFrozen = true;
    }

    public boolean prepareFinish(int rating, boolean combo) {
      if (awaitingRewards) return rating == pendingRating && combo == pendingCombo;
      awaitingRewards = true;
      pendingRating = rating;
      pendingCombo = combo;
      return true;
    }

    public void finish(int star, boolean combo) {
      stars.merge(activeLevel, star, Math::max);
      if (combo) fullCombos.add(activeLevel);
      activeLevel = 0;
      transaction = "";
      starRewards.clear();
      rewardsFrozen = false;
      awaitingRewards = false;
      pendingRating = 0;
      pendingCombo = false;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Composition {
    private int preset;
    private List<Integer> grid = new ArrayList<>();
    private List<Integer> effects = new ArrayList<>();

    protected Composition() {}

    public Composition(int preset, List<Integer> grid, List<Integer> effects) {
      this.preset = preset;
      this.grid.addAll(grid);
      this.effects.addAll(effects);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class ActionCursor {
    private int next;
    private boolean pending;
    private String lineupSource;

    public void reserve() {
      pending = true;
    }

    public void complete() {
      next++;
      pending = false;
      lineupSource = null;
    }

    void reserveLineup(String source) { pending = true; lineupSource = source; }
    void retryLineup(String source) { next--; reserveLineup(source); }
    void cancelLineupReservation() { pending = false; lineupSource = null; }
  }

  public void resetDaily(long day) {
    dailyDay = day;
    dailyPoints = 0;
    dailyQuests.clear();
  }

  public void addPoints(int count) {
    dailyPoints = Math.min(500, Math.addExact(dailyPoints, count));
  }

  public void setRogue(RogueRun run) {
    Objects.requireNonNull(run);
    // Old documents have no sequence field: recover the high-water mark from their active run.
    rogueSelectionSequence = Math.max(rogueSelectionSequence, rogue.getSelectionId());
    if (rogue.dialogues != null) {
      for (var dialogue : rogue.dialogues.values()) {
        if (dialogue != null && dialogue.uniqueId > 0)
          rogueDialogueSequence = Math.max(rogueDialogueSequence, dialogue.uniqueId);
      }
    }
    run.selectionId = Math.max(run.selectionId, rogueSelectionSequence);
    rogue = run;
  }

  /** Player-wide high-water mark prevents a stale dialogue request from matching a later run. */
  public RogueDialogue registerRogueDialogue(int group, int instance, int npc, int dialogue, int progress) {
    if (rogue.dialogues == null) rogue.dialogues = new HashMap<>();
    String key = rogue.siteId + ":" + rogue.roomId + ":" + group + ":" + instance;
    var old = rogue.dialogues.get(key);
    if (old != null) {
      if (old.uniqueId <= 0 || old.site != rogue.siteId || old.room != rogue.roomId
          || old.group != group || old.instance != instance || old.npc != npc
          || old.dialogue != dialogue || old.progress != progress) return null;
      rogueDialogueSequence = Math.max(rogueDialogueSequence, old.uniqueId);
      return old;
    }
    for (var row : rogue.dialogues.values()) {
      if (row == null || row.uniqueId <= 0) return null;
      rogueDialogueSequence = Math.max(rogueDialogueSequence, row.uniqueId);
    }
    if (rogueDialogueSequence == Integer.MAX_VALUE) return null;
    var next = new RogueDialogue(++rogueDialogueSequence, rogue.siteId, rogue.roomId,
        group, instance, npc, dialogue, progress);
    rogue.dialogues.put(key, next);
    return next;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueDialogue {
    private int uniqueId, site, room, group, instance, npc, dialogue, progress;
    private RogueDialogueRewardState rewardState;
    public RogueDialogueRewardState getRewardState() { return rewardState; }
    void rewardState(RogueDialogueRewardState value) {
      if (rewardState != null) throw new IllegalStateException("Event recipe already frozen");
      rewardState = java.util.Objects.requireNonNull(value);
    }
    protected RogueDialogue() {}
    private RogueDialogue(int uniqueId,int site,int room,int group,int instance,int npc,int dialogue,int progress) {
      this.uniqueId=uniqueId; this.site=site; this.room=room; this.group=group; this.instance=instance;
      this.npc=npc; this.dialogue=dialogue; this.progress=progress;
    }
    public String key() { return site+":"+room+":"+group+":"+instance; }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Receipt {
    private int rewardId;
    private boolean granted;

    protected Receipt() {}

    public Receipt(int id) {
      rewardId = id;
    }

    public void complete() {
      granted = true;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class LoginDays {
    private long lastDay = Long.MIN_VALUE;
    private int days;

    public boolean record(long day, int limit) {
      if (day <= lastDay || days >= limit) return false;
      lastDay = day;
      days++;
      return true;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueRun {
    public enum Phase {
      IDLE,
      EXPLORING,
      FINISHED,
      FAILED,
      ABANDONED
    }

    private String runId = "";
    private int areaId;
    private int mapId;
    private int siteId;
    private int roomId;
    private int selectedAeon;
    private RogueResonance.Plan resonance;
    private int resonanceSp = -1;

    public void activateResonance(int buff) {
      if (resonance != null && resonance.valid(selectedAeon) && buff == resonance.getBasic() && resonanceSp == -1)
        resonanceSp = 10000; // Explicit project initial value, retained from the old battle serializer.
    }

    public void saveResonanceSp(int value) {
      if (resonance == null || !resonance.earned(this) || value < 0 || value > 10000)
        throw new IllegalArgumentException("Invalid reserved resonance energy");
      resonanceSp = value;
    }
    private Phase phase = Phase.IDLE;
    private Set<Integer> cleared = new HashSet<>();
    private Map<Integer, Integer> rooms = new HashMap<>();
    private RogueBossEncounters.Plan bossEncounter;
    private boolean bossEncounterPlanned;
    // Distinguishes corrected first-story runs from runs created at the old floor origin.
    private boolean firstStoryEntrancePlaced;
    void markFirstStoryEntrancePlaced() { firstStoryEntrancePlaced = true; }
    void restoreFirstStoryEntrancePlaced(boolean value) { firstStoryEntrancePlaced = value; }
    // 0 = earned and queued, positive = current selection, -1 = selected.
    private Map<Integer, Integer> firstStoryBlessingReceipts = new LinkedHashMap<>();
    // The second tutorial reuses group IDs on another floor; do not mix its receipts with map 1.
    private Map<Integer, Integer> secondStoryBlessingReceipts = new LinkedHashMap<>();
    private Map<Integer, Integer> blessings = new HashMap<>();
    private Map<Integer, RogueMiracle> miracles = new HashMap<>();
    private List<Integer> miracleOffers = new ArrayList<>();
    private Set<String> processedBattles = new HashSet<>();
    private Map<String, RogueCombatGrant> combatFragmentGrants = new HashMap<>();
    private Map<String, RogueCombatGrant> miracleFragmentGrants = new HashMap<>();
    private Map<Integer, RogueEnhancementPlan> enhancementPlans = new HashMap<>();
    private Map<String, RogueShopPlan> blessingShops = new HashMap<>();
    private Map<String, RogueShopPlan> miracleShops = new HashMap<>();
    private Map<String, RogueDialogue> dialogues = new HashMap<>();
    private Set<String> destroyedRecoveryProps = new HashSet<>();
    private Set<String> destroyedDestructibleProps = new HashSet<>();
    private Set<String> recordedBattleKills = new HashSet<>();
    private Set<String> defeatedRogueMonsters = new HashSet<>();
    private Map<String, RogueMonsterSelection.Choice> monsterSelections = new HashMap<>();
    private Set<String> usedDownloadConsoles = new HashSet<>();
    private Map<Integer, RogueTrialState> downloadedTrials = new HashMap<>();
    private int initialAvatarSp = -1;
    private List<Integer> offers = new ArrayList<>();
    private int selectionId;
    private String eventRewardLease = "";
    void eventRewardLease(String key) { eventRewardLease = java.util.Objects.requireNonNull(key); }
    private int coins = 100;
    private Set<Integer> selectionRooms = new HashSet<>();
    private Set<Integer> talents = new HashSet<>();
    private List<Integer> rerollCosts = new ArrayList<>();
    private List<Integer> offerPool = new ArrayList<>();
    private Map<Integer, Integer> offerLevels = new HashMap<>();
    private Map<Integer, Integer> offerWeights = new HashMap<>();
    private int offerChoiceTotal;
    private int offerChoiceIndex;
    private int offerOptionLimit;
    private int offerEnhancementCount;
    private Map<Integer, Integer> offerBaseLevels = new HashMap<>();
    private Set<Integer> offerEnhanceable = new HashSet<>();
    private List<Integer> offerRerollCosts = new ArrayList<>();
    private List<RogueFragmentCost> offerFragmentCosts = new ArrayList<>();
    private Set<Integer> offerExcluded = new HashSet<>();
    private int offerRerolls;
    private int revivalCost = -1;
    private int revivalHp;
    private List<Integer> roster = new ArrayList<>();
    private Map<Integer, RogueVitals> vitals = new HashMap<>();
    private int leader;
    private int mp;
    private boolean inside;
    private boolean dismissed;
    private boolean historyRecorded;
    private RogueReturn returnTo;

    public void setupTeam(emu.lunarcore.game.player.Player player, List<Integer> ids) {
      RogueEconomy.initialCoins().ifPresent(base -> coins = base);
      roster.addAll(ids);
      var normal = player.getCurrentLineup();
      var initialSp = RogueTalentEffects.initialSp(player);
      if (initialSp.isPresent())
        initialAvatarSp = (int) Math.floor(initialSp.getAsDouble() * 10000);
      for (int id : ids) {
        var avatar = player.getAvatarById(id);
        vitals.put(
            id,
            new RogueVitals(
                initialSp.isPresent() ? avatar.getMaxHp() : avatar.getCurrentHp(normal),
                initialSp.isPresent()
                    ? (int) Math.floor(avatar.getMaxSp() * initialSp.getAsDouble())
                    : avatar.getCurrentSp(normal)));
      }
      leader = Math.max(0, ids.indexOf(normal.getAvatars().get(normal.getLeader())));
      mp = initialSp.isPresent() ? normal.getMaxMp() : normal.getMp();
      for (int talent : List.of(11, 21, 32))
        if (RogueTalentEffects.enabled(player, talent)) talents.add(talent);
      rerollCosts.addAll(RogueTalentEffects.rerollCosts(player));
      RogueRevival.plan()
          .ifPresent(
              plan -> {
                revivalCost = plan.cost();
                revivalHp = plan.hp();
              });
      returnTo = new RogueReturn(player);
      inside = true;
    }

    public void setInside(boolean value) {
      inside = value;
    }

    public void dismiss() {
      dismissed = true;
    }

    public void setLeader(int value) {
      leader = value;
    }

    public void setMp(int value) {
      mp = value;
    }

    public void offer(List<Integer> ids) {
      if (!miracleOffers.isEmpty()) throw new IllegalStateException("Pending miracle selection");
      offers.clear();
      offers.addAll(ids);
      selectionId = Math.incrementExact(selectionId);
      offerPool.clear();
      if (offerLevels == null) offerLevels = new HashMap<>();
      else offerLevels.clear();
      if (offerWeights == null) offerWeights = new HashMap<>();
      else offerWeights.clear();
      offerRerollCosts.clear();
      if (offerFragmentCosts != null) offerFragmentCosts.clear();
      offerExcluded.clear();
      offerRerolls = 0;
      offerChoiceTotal = 0;
      offerChoiceIndex = 0;
      offerOptionLimit = 0;
      clearBeaconPlan();
    }

    public void offer(List<Integer> ids, List<Integer> pool, List<Integer> costs) {
      offer(ids);
      offerPool.addAll(pool);
      offerRerollCosts.addAll(costs);
      offerExcluded.addAll(ids);
    }

    public void offer(
        List<Integer> ids, List<Integer> pool, List<Integer> costs, Map<Integer, Integer> levels) {
      offer(ids, pool, costs, levels, 1);
    }

    public void offer(
        List<Integer> ids,
        List<Integer> pool,
        List<Integer> costs,
        Map<Integer, Integer> levels,
        int choices) {
      if (!levels.keySet().equals(Set.copyOf(pool))
          || !pool.containsAll(ids)
          || choices < 1
          || choices > 2
          || choices > ids.size()
          || levels.values().stream()
              .anyMatch(level -> level == null || (level != 1 && level != 2)))
        throw new IllegalArgumentException("Invalid blessing candidate levels");
      offer(ids, pool, costs);
      offerLevels.putAll(levels);
      offerChoiceTotal = choices;
      offerChoiceIndex = 1;
      offerOptionLimit = ids.size();
    }

    public void offer(
        List<Integer> ids,
        List<Integer> pool,
        List<Integer> costs,
        Map<Integer, Integer> levels,
        int choices,
        Map<Integer, Integer> weights) {
      if (!weights.keySet().equals(Set.copyOf(pool))
          || weights.values().stream().anyMatch(w -> w == null || (w != 1 && w != 2)))
        throw new IllegalArgumentException("Invalid blessing candidate weights");
      offer(ids, pool, costs, levels, choices);
      offerWeights.putAll(weights);
    }

    /** Older pending menus stay uniform instead of borrowing newly acquired path effects. */
    public int offerWeight(int id) {
      if (offerWeights == null || offerWeights.isEmpty()) return 1;
      return offerWeights.getOrDefault(id, 0);
    }

    /**
     * Older menus lack level snapshots and remain at level one. Incomplete snapshots fail closed.
     */
    public int offerLevel(int id) {
      if (offerLevels == null || offerLevels.isEmpty()) return 1;
      var level = offerLevels.get(id);
      return level != null && (level == 1 || level == 2) ? level : 0;
    }

    public boolean validOfferLevels() {
      boolean choicesValid =
          (offerChoiceTotal == 0 && offerChoiceIndex == 0)
              || (offerChoiceTotal >= 1
                  && offerChoiceTotal <= 2
                  && offerChoiceIndex >= 1
                  && offerChoiceIndex <= offerChoiceTotal
                  && offerChoiceRemaining() <= offers.size()
                  && (offerChoiceRemaining() <= 1 || selectionId < Integer.MAX_VALUE));
      return choicesValid
          && validBeaconPlan()
          && offerOptionLimit >= 0
          && offerOptionLimit <= 3
          && (offerOptionLimit == 0 || offers.size() <= offerOptionLimit)
          && (offerWeights == null
              || offerWeights.isEmpty()
              || (offerWeights.keySet().equals(Set.copyOf(offerPool))
                  && offerPool.containsAll(offers)
                  && offerWeights.values().stream()
                      .allMatch(weight -> weight != null && (weight == 1 || weight == 2))))
          && (offerLevels == null
              || offerLevels.isEmpty()
              || (offerLevels.keySet().equals(Set.copyOf(offerPool))
                  && offerPool.containsAll(offers)
                  && offerLevels.values().stream()
                      .allMatch(level -> level != null && (level == 1 || level == 2))));
    }

    public int offerChoiceTotal() {
      return offerChoiceTotal == 0 ? 1 : offerChoiceTotal;
    }

    public int offerChoiceIndex() {
      return offerChoiceIndex == 0 ? 1 : offerChoiceIndex;
    }

    public int offerChoiceRemaining() {
      return offerChoiceTotal() - offerChoiceIndex() + 1;
    }

    public int offerOptionLimit() {
      // Old saved menus preserve the previous one/two-choice menu behavior.
      return offerOptionLimit == 0 ? (offerChoiceTotal() == 2 ? 2 : 3) : offerOptionLimit;
    }

    public void freezeBeaconPlan(int count, Map<Integer, Integer> base, Set<Integer> eligible) {
      clearBeaconPlan();
      if (count == 0) return;
      if (count != 1 || base == null || eligible == null)
        throw new IllegalArgumentException("Invalid candidate enhancement plan");
      offerEnhancementCount = count;
      offerBaseLevels.putAll(base);
      offerEnhanceable.addAll(eligible);
      if (!validBeaconPlan())
        throw new IllegalArgumentException("Mismatched candidate enhancement plan");
    }

    private void clearBeaconPlan() {
      offerEnhancementCount = 0;
      if (offerBaseLevels == null) offerBaseLevels = new HashMap<>();
      else offerBaseLevels.clear();
      if (offerEnhanceable == null) offerEnhanceable = new HashSet<>();
      else offerEnhanceable.clear();
    }

    private boolean validBeaconPlan() {
      if (offerEnhancementCount == 0)
        return (offerBaseLevels == null || offerBaseLevels.isEmpty())
            && (offerEnhanceable == null || offerEnhanceable.isEmpty());
      if (offerEnhancementCount != 1
          || offerBaseLevels == null
          || offerEnhanceable == null
          || offerLevels == null
          || !offerBaseLevels.keySet().equals(Set.copyOf(offerPool))
          || !offerLevels.keySet().equals(offerBaseLevels.keySet())
          || !offerBaseLevels.keySet().containsAll(offerEnhanceable)) return false;
      int changed = 0;
      for (var entry : offerBaseLevels.entrySet()) {
        var base = entry.getValue();
        var level = offerLevels.get(entry.getKey());
        if (base == null
            || (base != 1 && base != 2)
            || level == null
            || offerEnhanceable.contains(entry.getKey()) && base != 1) return false;
        if (!base.equals(level)) {
          if (base != 1
              || level != 2
              || !offerEnhanceable.contains(entry.getKey())
              || !offers.contains(entry.getKey())) return false;
          changed++;
        }
      }
      return changed <= offerEnhancementCount;
    }

    public int offerBaseLevel(int id) {
      return offerEnhancementCount == 1 ? offerBaseLevels.getOrDefault(id, 0) : offerLevel(id);
    }

    public void replaceBeaconLevels(Map<Integer, Integer> levels) {
      if (offerEnhancementCount != 1 || levels == null)
        throw new IllegalArgumentException("Missing candidate enhancement plan");
      var previous = offerLevels;
      offerLevels = new HashMap<>(levels);
      if (!validBeaconPlan()) {
        offerLevels = previous;
        throw new IllegalArgumentException("Invalid refreshed candidate levels");
      }
    }

    public void reroll(List<Integer> ids) {
      if (ids.size() < offerChoiceRemaining() || ids.size() > offerOptionLimit())
        throw new IllegalArgumentException("Too few blessing candidates");
      offers.clear();
      offers.addAll(ids);
      offerExcluded.addAll(ids);
      offerRerolls = Math.incrementExact(offerRerolls);
    }

    public void offerMiracles(List<Integer> ids) {
      if (!offers.isEmpty()) throw new IllegalStateException("Pending blessing selection");
      miracleOffers.clear();
      miracleOffers.addAll(ids);
      selectionId = Math.incrementExact(selectionId);
    }

    public boolean pending() {
      return !offers.isEmpty() || !miracleOffers.isEmpty();
    }

    public void select(int id) {
      int level = offerLevel(id);
      if (!validOfferLevels() || level == 0)
        throw new IllegalStateException("Invalid blessing candidate level");
      boolean more = offerChoiceRemaining() > 1;
      int nextBatch = more ? Math.incrementExact(selectionId) : selectionId;
      blessings.put(id, level);
      if (more) {
        offers.remove(Integer.valueOf(id));
        offerPool.remove(Integer.valueOf(id));
        if (offerLevels != null) offerLevels.remove(id);
        if (offerWeights != null) offerWeights.remove(id);
        if (offerBaseLevels != null) offerBaseLevels.remove(id);
        if (offerEnhanceable != null) offerEnhanceable.remove(id);
        offerChoiceIndex++;
        selectionId = nextBatch;
        return;
      }
      offers.clear();
      offerPool.clear();
      if (offerLevels != null) offerLevels.clear();
      if (offerWeights != null) offerWeights.clear();
      offerRerollCosts.clear();
      offerExcluded.clear();
      offerChoiceTotal = 0;
      offerChoiceIndex = 0;
      if (offerFragmentCosts != null) offerFragmentCosts.clear();
      offerOptionLimit = 0;
      clearBeaconPlan();
    }

    public boolean spend(int cost) {
      if (cost < 0 || coins < cost) return false;
      coins -= cost;
      return true;
    }

    public void freezeRerollPlans(List<RogueFragmentCost> plans) {
      if (plans == null || plans.size() != offerRerollCosts.size())
        throw new IllegalArgumentException("Invalid reroll cost plan");
      for (int i = 0; i < plans.size(); i++)
        if (plans.get(i) == null
            || !plans.get(i).valid()
            || plans.get(i).getCost() != offerRerollCosts.get(i))
          throw new IllegalArgumentException("Mismatched reroll cost plan");
      offerFragmentCosts = new ArrayList<>(plans);
    }

    public RogueFragmentCost rerollPlan(int index) {
      if (index < 0 || index >= offerRerollCosts.size()) return null;
      int cost = offerRerollCosts.get(index);
      if (cost < 0) return null;
      // Existing menus predate the clock: honor their saved advertised price.
      if (offerFragmentCosts == null || offerFragmentCosts.isEmpty())
        return new RogueFragmentCost(cost);
      if (offerFragmentCosts.size() != offerRerollCosts.size()) return null;
      var plan = offerFragmentCosts.get(index);
      return plan != null && plan.valid() && plan.getCost() == cost ? plan : null;
    }

    public void enhance(int id) {
      blessings.computeIfPresent(id, (k, v) -> v + 1);
    }

    public void addCoins(int count) {
      coins = Math.addExact(coins, count);
    }

    /** Revert only the fields changed by one event reward before an acknowledged save. */
    public void restoreEventReward(RogueRun prior) {
      if (prior == null || !java.util.Objects.equals(runId, prior.runId)
          || siteId != prior.siteId || roomId != prior.roomId)
        throw new IllegalArgumentException("Foreign event reward snapshot");
      coins = prior.coins;
      cleared = prior.cleared;
      blessings = prior.blessings;
      offers = prior.offers;
      selectionId = prior.selectionId;
      eventRewardLease = prior.eventRewardLease;
      offerPool = prior.offerPool;
      offerLevels = prior.offerLevels;
      offerWeights = prior.offerWeights;
      offerRerollCosts = prior.offerRerollCosts;
      offerExcluded = prior.offerExcluded;
      offerChoiceTotal = prior.offerChoiceTotal;
      offerChoiceIndex = prior.offerChoiceIndex;
      offerOptionLimit = prior.offerOptionLimit;
      offerFragmentCosts = prior.offerFragmentCosts;
      offerEnhancementCount = prior.offerEnhancementCount;
      offerBaseLevels = prior.offerBaseLevels;
      offerEnhanceable = prior.offerEnhanceable;
      offerRerolls = prior.offerRerolls;
      resonanceSp = prior.resonanceSp;
    }

    protected RogueRun() {}

    public void restoreMiracleShop(RogueRun prior) {
      // Includes the exact ordinary/resonance selection fields and currency changed by the shop.
      // The existing immutable resonance plan, live run and authored dialogue objects are retained.
      restoreEventReward(prior);
      firstStoryBlessingReceipts = prior.firstStoryBlessingReceipts;
      secondStoryBlessingReceipts = prior.secondStoryBlessingReceipts;
      miracles = prior.miracles;
      miracleFragmentGrants = prior.miracleFragmentGrants;
      miracleShops = prior.miracleShops;
    }

    public RogueRun(int area, int map, Map<Integer, Integer> assignments) {
      runId = UUID.randomUUID().toString();
      areaId = area;
      mapId = map;
      rooms.putAll(assignments);
      phase = Phase.EXPLORING;
    }

    public RogueRun(int area, int map, Map<Integer, Integer> assignments, RogueBossEncounters.Plan boss) {
      this(area, map, assignments);
      bossEncounter = boss;
      bossEncounterPlanned = boss != null;
    }

    public RogueRun(int area, int map, Map<Integer, Integer> assignments, RogueBossEncounters.Plan boss, int aeon) {
      this(area, map, assignments, boss);
      if (aeon < 0 || aeon > 9) throw new IllegalArgumentException("Invalid selected path");
      selectedAeon = aeon;
      resonance = RogueResonance.freeze(aeon);
    }

    public void enter(int site, int room) {
      siteId = site;
      roomId = room;
    }

    public boolean clear() {
      return cleared.add(siteId);
    }

    public void finish(Phase value) {
      phase = value;
      if (value == Phase.FINISHED || value == Phase.FAILED || value == Phase.ABANDONED) {
        offers.clear();
        miracleOffers.clear();
        offerPool.clear();
        if (offerLevels != null) offerLevels.clear();
        if (offerWeights != null) offerWeights.clear();
        offerRerollCosts.clear();
        if (offerFragmentCosts != null) offerFragmentCosts.clear();
        offerExcluded.clear();
        offerChoiceTotal = 0;
        offerChoiceIndex = 0;
        offerOptionLimit = 0;
        clearBeaconPlan();
      }
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueCombatGrant {
    private int fragments;
    private boolean settled;

    protected RogueCombatGrant() {}

    public RogueCombatGrant(int fragments) {
      if (fragments <= 0) throw new IllegalArgumentException("Nonpositive combat fragments");
      this.fragments = fragments;
    }

    public boolean credit(RogueRun run) {
      if (settled || fragments <= 0) return false;
      try {
        run.addCoins(fragments);
      } catch (ArithmeticException e) {
        return false;
      }
      settled = true;
      return true;
    }
  }

  /** One room merchant's frozen stock; scene entity IDs are deliberately excluded. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueShopPlan {
    private Map<Integer, RogueShopOffer> offers = new HashMap<>();
    private RogueRestMerchantLease restMerchant;

    protected RogueShopPlan() {}

    public RogueShopPlan(Map<Integer, RogueShopOffer> offers) {
      this.offers.putAll(offers);
    }

    public RogueShopPlan(Map<Integer, RogueShopOffer> offers, RogueRestMerchantLease lease) {
      this(offers);
      restMerchant = java.util.Objects.requireNonNull(lease);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueShopOffer {
    private int level;
    private int cost = -1;
    private boolean purchased;
    private RogueFragmentCost fragmentCost;

    protected RogueShopOffer() {}

    public RogueShopOffer(int level, int cost) {
      if (level != 1 || cost < 0) throw new IllegalArgumentException("Invalid shop offer");
      this.level = level;
      this.cost = cost;
    }

    public RogueShopOffer(int level, RogueFragmentCost plan) {
      this(level, plan.getCost());
      if (!plan.valid()) throw new IllegalArgumentException("Invalid shop cost plan");
      fragmentCost = plan;
    }

    public RogueFragmentCost debitPlan() {
      if (cost < 0) return null;
      if (fragmentCost == null) return new RogueFragmentCost(cost);
      return fragmentCost.valid() && fragmentCost.getCost() == cost ? fragmentCost : null;
    }

    public void purchase() {
      purchased = true;
    }
  }

  /** One classic level-1 to level-2 quote, persisted with the wallet and blessing. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueEnhancementPlan {
    private int cost = -1;
    private boolean completed;
    private RogueFragmentCost fragmentCost;

    protected RogueEnhancementPlan() {}

    public RogueEnhancementPlan(int cost) {
      if (cost < 0) throw new IllegalArgumentException("Negative enhancement price");
      this.cost = cost;
    }

    public RogueEnhancementPlan(RogueFragmentCost plan) {
      this(plan.getCost());
      if (!plan.valid()) throw new IllegalArgumentException("Invalid enhancement cost plan");
      fragmentCost = plan;
    }

    public RogueFragmentCost debitPlan() {
      if (cost < 0) return null;
      if (fragmentCost == null) return new RogueFragmentCost(cost);
      return fragmentCost.valid() && fragmentCost.getCost() == cost ? fragmentCost : null;
    }

    public void complete() {
      completed = true;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueMiracle {
    private int useCount;
    private int totalCount;
    private List<Double> effectParameters = new ArrayList<>();
    private String acquisitionId = "";
    private long extraFragmentsSpent;
    private List<Double> clockBreakParameters = new ArrayList<>();
    private Set<String> destroyedProps = new HashSet<>();
    private List<Double> codeRepairParameters = new ArrayList<>();
    private RogueLotteryState lottery;

    protected RogueMiracle() {}

    public RogueMiracle(int total) {
      if (total < 0) throw new IllegalArgumentException("Negative miracle lifetime");
      totalCount = total;
    }

    public RogueMiracle(int total, List<Double> parameters) {
      this(total);
      if (parameters == null
          || parameters.isEmpty()
          || parameters.size() > 16
          || parameters.stream().anyMatch(v -> v == null || !Double.isFinite(v)))
        throw new IllegalArgumentException("Invalid miracle acquisition parameters");
      effectParameters = new ArrayList<>(parameters);
      acquisitionId = UUID.randomUUID().toString();
    }

    public RogueMiracle(int total, List<Double> parameters, List<Double> repairedParameters) {
      this(total, parameters);
      if (repairedParameters == null
          || repairedParameters.isEmpty()
          || repairedParameters.size() > 16
          || repairedParameters.stream().anyMatch(v -> v == null || !Double.isFinite(v)))
        throw new IllegalArgumentException("Invalid code repair parameters");
      codeRepairParameters = new ArrayList<>(repairedParameters);
    }

    public List<Double> getCodeRepairParameters() {
      return codeRepairParameters == null
          ? List.of()
          : Collections.unmodifiableList(codeRepairParameters);
    }

    public String getAcquisitionId() {
      return Objects.toString(acquisitionId, "");
    }

    void freezeLottery() {
      if (lottery != null || !RogueLotteryState.uuid(getAcquisitionId()))
        throw new IllegalStateException("Invalid or already frozen lottery acquisition");
      lottery = new RogueLotteryState(getAcquisitionId());
    }

    public void recordExtraFragments(int extra) {
      if (extra < 0) throw new IllegalArgumentException("Negative extra cost");
      extraFragmentsSpent = Math.addExact(extraFragmentsSpent, extra);
    }

    public List<Double> getClockBreakParameters() {
      return clockBreakParameters == null ? null : Collections.unmodifiableList(clockBreakParameters);
    }

    public void freezeClockBreakParameters(List<Double> parameters) {
      if (clockBreakParameters == null || !clockBreakParameters.isEmpty()
          || !List.of(499.0, 999.0).equals(parameters))
        throw new IllegalArgumentException("Invalid or already frozen clock break parameters");
      clockBreakParameters = new ArrayList<>(parameters);
    }

    public List<Double> getEffectParameters() {
      return effectParameters == null ? List.of() : Collections.unmodifiableList(effectParameters);
    }

    public Set<String> getDestroyedProps() {
      return destroyedProps == null ? null : Collections.unmodifiableSet(destroyedProps);
    }

    public int destructionCount() {
      if (destroyedProps == null
          || destroyedProps.size() > 16777215
          || destroyedProps.stream().anyMatch(key -> !validDestructionKey(key))) return -1;
      return destroyedProps.size();
    }

    public boolean rememberDestruction(String key) {
      return destructionCount() >= 0
          && destroyedProps.size() < 16777215
          && validDestructionKey(key)
          && destroyedProps.add(key);
    }

    private static boolean validDestructionKey(String key) {
      if (key == null) return false;
      var parts = key.split(":", -1);
      if (parts.length != 6) return false;
      try {
        for (String part : parts) {
          int value = Integer.parseInt(part);
          if (value <= 0 || !Integer.toString(value).equals(part)) return false;
        }
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }

    public boolean broken() {
      return totalCount != 0 && useCount >= totalCount;
    }

    public boolean consume() {
      if (broken()) return false;
      if (totalCount != 0) useCount++;
      return true;
    }

    public void repair() {
      useCount = 0;
      if (lottery != null) lottery.repaired();
    }
  }

  /** Frozen debit and extra cost bound to the acquisition that created the quote. */
  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueFragmentCost {
    private int cost = -1;
    private int extra;
    private String clockAcquisition = "";

    protected RogueFragmentCost() {}

    public RogueFragmentCost(int cost) {
      this(cost, 0, "");
    }

    public RogueFragmentCost(int cost, int extra, String clockAcquisition) {
      this.cost = cost;
      this.extra = extra;
      this.clockAcquisition = clockAcquisition;
      if (!valid()) throw new IllegalArgumentException("Invalid fragment cost");
    }

    public boolean valid() {
      return cost >= 0
          && extra >= 0
          && extra <= cost
          && clockAcquisition != null
          && (extra == 0 ? clockAcquisition.isEmpty() : !clockAcquisition.isBlank());
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueVitals {
    private int hp;
    private int sp;

    protected RogueVitals() {}

    public RogueVitals(int hp, int sp) {
      this.hp = hp;
      this.sp = sp;
    }

    public void setHp(int value) {
      hp = value;
    }

    public void setSp(int value) {
      sp = value;
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class RogueReturn {
    private int plane;
    private int floor;
    private int entry;
    private emu.lunarcore.util.Position pos;
    private emu.lunarcore.util.Position rot;

    protected RogueReturn() {}

    public RogueReturn(emu.lunarcore.game.player.Player player) {
      plane = player.getPlaneId();
      floor = player.getFloorId();
      entry = player.getEntryId();
      pos = player.getPos().clone();
      rot = player.getRot().clone();
    }
  }
}
