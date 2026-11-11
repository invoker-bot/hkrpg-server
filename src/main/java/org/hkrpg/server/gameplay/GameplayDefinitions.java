package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Resource fields are read by semantic name, independent of generated client classes. */
public final class GameplayDefinitions {
  public record DailyLevel(
      int Level, int WorldLevel, int DailyActivePoint, int DailyActiveReward) {}

  public record DailyPool(int QuestID, int Type) {}

  public record LoginActivity(int ID, List<Integer> RewardList, int ActivityModuleID) {}

  public record Window(int panelId, int moduleId, long beginTime, long endTime,
      String replayId, String replayKey) {
    public Window(int panelId,int moduleId,long beginTime,long endTime){this(panelId,moduleId,beginTime,endTime,null,null);}
  }

  public record World(int ID) {}

  public record Area(
      int RogueAreaID,
      int AreaProgress,
      int Difficulty,
      int FirstReward,
      int UnlockID,
      Map<Integer, Integer> ScoreMap,
      Map<Integer, Integer> DisplayMonsterMap) {
    public Area(int id, int progress, int difficulty, int reward, int unlock) {
      this(id, progress, difficulty, reward, unlock, Map.of(), Map.of());
    }

    public Area(int id, int progress, int difficulty, int reward, int unlock,
        Map<Integer, Integer> scores) {
      this(id, progress, difficulty, reward, unlock, scores, Map.of());
    }

    public int mapId() {
      return AreaProgress * 100 + Difficulty;
    }
  }

  public record Site(
      int RogueMapID,
      int SiteID,
      boolean IsStart,
      List<Integer> NextSiteIDList,
      int RogueRoomType) {}

  public record Room(
      int RogueRoomID,
      int RogueRoomType,
      int MapEntrance,
      int GroupID,
      Map<Integer, Integer> GroupWithContent) {
    public Room(int id, int type, int entry, int group) {
      this(id, type, entry, group, Map.of(group, 0));
    }
  }

  public record RogueMonster(int RogueMonsterID, int NpcMonsterID, int EventID) {}

  public record RogueMonsterGroup(
      int RogueMonsterGroupID, Map<Integer, Integer> RogueMonsterListAndWeight) {}

  public record RogueSeason(
      int RogueSeason,
      int RogueVersion,
      List<Integer> RogueAreaIDList,
      String BeginTime,
      String EndTime) {}

  public record RogueUnlock(int RogueUnlockID, int UnlockFinishWay) {}

  public record RogueTalent(
      int TalentID,
      List<Integer> NextTalentIDList,
      List<TalentCost> Cost,
      List<Integer> UnlockIDList,
      List<EffectValue> EffectDescParamList) {
    public RogueTalent(int id, List<Integer> next, List<TalentCost> cost, List<Integer> unlock) {
      this(id, next, cost, unlock, null);
    }
  }

  public record RogueBuffGroup(int GMLOGNJAIGI, List<Integer> HECJCAMDGNO) {}

  public record TalentCost(int ItemID, int ItemNum) {}

  public record RogueConstant(String ConstRogueName, String ConstValue) {}

  public record CycleReward(int ScoreRank, int Score, int Reward) {}

  public record CommonConstant(String ConstValueName, JsonObject Value) {}

  public record Miracle(int MiracleID, int MiracleDisplayID, int MiracleEffectDisplayID,
      int UnlockHandbookMiracleID) {
    public Miracle(int id, int display, int effect) { this(id, display, effect, 0); }
  }

  public record MiracleEffect(int MiracleEffectID, List<EffectValue> ParamList) {}

  public record EffectValue(double Value) {}

  public record MiracleGroup(int RogueMiracleGroupID, Map<Integer, Integer> MiracleWeight) {}

  public record RogueRule(
      int ID,
      String FinishType,
      String ParamType,
      int ParamInt1,
      int ParamInt2,
      int ParamInt3,
      String ParamStr1,
      List<Integer> ParamIntList,
      List<JsonElement> ParamItemList,
      int Progress) {}

  public record Blessing(
      int MazeBuffID,
      int MazeBuffLevel,
      int RogueBuffType,
      String RogueBuffCategory,
      int RogueVersion,
      List<Integer> UnlockIDList,
      boolean IsShow,
      int RogueBuffTag,
      int AeonID,
      String BattleEventBuffType) {
    public Blessing(int id, int level, int type, String category, int version, List<Integer> unlocks, boolean show) {
      this(id, level, type, category, version, unlocks, show, 0, 0, null);
    }
    public Blessing(int id, int level, int type, String category, int version, List<Integer> unlocks) {
      this(id, level, type, category, version, unlocks, false);
    }
  }

  public record MusicLevel(
      int ID,
      int Group,
      int Difficulty,
      List<Integer> StarScoreList,
      List<Integer> StarRewardIDList) {}

  public record MusicGroup(
      int ID, int Phase, int UnlockSubMissionID, List<Integer> RewardTrackIDList) {}

  public record MusicPhase(int Phase, int SongID, List<Integer> TrackIDList, int FinishMissionID) {}

  public record MusicSong(
      int ID,
      int UnlockType,
      int UnlockTypeParam,
      List<Integer> GridNumList,
      List<Integer> TrackIDList,
      List<Integer> PresetIDList,
      int SoundEffectUnlockSubMission,
      List<Integer> SoundEffectIDList) {}

  public record MusicPreset(int ID, List<Integer> PresetGridConfig) {}

  public record MusicTrack(int ID, int UnlockSubMissionID, List<Integer> EmptyGridList) {}

  public record MusicConstant(String ConstValueName, ConstantValue Value) {}

  public record ConstantValue(int IntValue) {}

  public record ParkourLevel(
      int ID,
      String UnlockType,
      int UnlockParam,
      int RailBallLimit,
      int TargetRank,
      int LapCount,
      String GameAssetPath) {}

  public record ParkourBall(int ID, int UpgradeSubMission, int UpgradeBallID) {}

  public record ParkourRankReference(
      int RailBallID, List<ParkourRankReferenceTime> LevelBestRecordList) {}

  public record ParkourRankReferenceTime(int MLDLOJJBIJD, int GAFENHHEBPG) {}

  public record Raid(
      int RaidID,
      int WorldLevel,
      String Type,
      String TeamType,
      String EnterType,
      List<Integer> MainMissionIDList,
      List<Integer> TrialAvatarList,
      List<Integer> RaidTargetID,
      List<Integer> RewardList,
      boolean SkipRewardOnFinish,
      int MainMissionIDBefore,
      int MainMissionIDAfter,
      int FinishEntranceID,
      List<Integer> TeamLimitIDList,
      List<Integer> LimitIDList,
      List<JsonElement> BuffParamList,
      List<Integer> UnlockWorldLevel,
      List<String> RecoverType,
      boolean LockCaptain,
      int LockCaptainAvatarID,
      boolean SkipJoinLineup) {
    public Raid(
        int raid,
        int world,
        String type,
        String team,
        String enter,
        List<Integer> missions,
        List<Integer> trials,
        List<Integer> targets,
        List<Integer> rewards,
        boolean skipReward) {
      this(
          raid,
          world,
          type,
          team,
          enter,
          missions,
          trials,
          targets,
          rewards,
          skipReward,
          0,
          0,
          0,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of("Unknown"),
          false,
          0,
          false);
    }

    public long key() {
      return (long) RaidID * 100 + WorldLevel;
    }
  }

  public static final Map<Integer, ParkourLevel> parkourLevels = new HashMap<>();
  public static final Map<Integer, ParkourBall> parkourBalls = new HashMap<>();
  public static final Map<String, Integer> parkourConstants = new HashMap<>();
  public static final List<ParkourRankReference> parkourRankings = new ArrayList<>();
  public static final Map<Long, Raid> raids = new HashMap<>();

  public record MessageSection(
      int ID,
      boolean IsPerformMessage,
      int MainMissionLink,
      List<Integer> StartMessageItemIDList) {}

  public record MessageGroup(
      int ID, int MessageContactsID, int ActivityModuleID, List<Integer> MessageSectionIDList) {}

  public record MessageItem(
      int ID, int SectionID, String Sender, String ItemType, List<Integer> NextItemIDList) {}

  public static final Map<Integer, MessageSection> messageSections = new HashMap<>();
  public static final Map<Integer, MessageGroup> messageGroups = new HashMap<>();
  public static final Map<Integer, MessageItem> messageItems = new HashMap<>();

  public static final Map<Integer, MusicSong> musicSongs = new HashMap<>();
  public static final Map<Integer, MusicPreset> musicPresets = new HashMap<>();
  public static final Map<Integer, MusicTrack> musicTracks = new HashMap<>();
  public static final Map<String, Integer> musicConstants = new HashMap<>();

  public static final Map<Integer, MusicLevel> musicLevels = new HashMap<>();
  public static final Map<Integer, MusicGroup> musicGroups = new HashMap<>();
  public static final Map<Integer, MusicPhase> musicPhases = new HashMap<>();
  public static final Map<Integer, RogueMonster> rogueMonsters = new HashMap<>();
  public static final Map<Integer, RogueMonsterGroup> rogueMonsterGroups = new HashMap<>();
  public static boolean rogueMonsterGroupsPresent;
  public static final List<Blessing> blessings = new ArrayList<>();
  public static final List<RogueSeason> rogueSeasons = new ArrayList<>();
  public static final Map<Integer, RogueUnlock> rogueUnlocks = new HashMap<>();
  public static final Map<Integer, RogueRule> rogueRules = new HashMap<>();
  public static final Map<Integer, RogueTalent> rogueTalents = new HashMap<>();
  public static final Map<Integer, RogueBuffGroup> rogueBuffGroups = new HashMap<>();
  public static final Map<String, String> rogueConstants = new HashMap<>();
  public static final Map<Integer, CycleReward> cycleRewards = new HashMap<>();
  public static final Map<String, JsonObject> cycleConstants = new HashMap<>();
  public static final Map<Integer, Miracle> miracles = new HashMap<>();
  public static final Map<Integer, MiracleEffect> miracleEffects = new HashMap<>();
  public static final Map<Integer, MiracleGroup> miracleGroups = new HashMap<>();

  public static final List<DailyLevel> dailyLevels = new ArrayList<>();
  public static final List<DailyPool> dailyPool = new ArrayList<>();
  public static final Map<Integer, LoginActivity> activities = new HashMap<>();
  public static volatile List<Window> windows = new java.util.concurrent.CopyOnWriteArrayList<>();

  public static void replaceActivityWindows(List<Window> value) {
    windows=new java.util.concurrent.CopyOnWriteArrayList<>(List.copyOf(value));
  }
  public static final Map<Integer, Area> areas = new HashMap<>();
  public static final Map<Integer, List<Site>> maps = new HashMap<>();
  public static final Map<Integer, Room> rooms = new HashMap<>();
  public static final Map<Integer, List<Integer>> roomPool = new HashMap<>();
  public static final Map<Integer, Map<Integer, List<Integer>>> roomPoolByMap = new HashMap<>();
  public static final Map<Integer, Integer> bossContentByArea = new HashMap<>();
  public static final Map<Integer, MissionInfo.SubMissionInfo> subs = new HashMap<>();
  public static final Map<Integer, World> worlds = new HashMap<>();

  public static void load(Path root) {
    StoryChallengeCatalog.load(root);
    LocalLegendCatalog.load(root);
    LocalLegendAccess.load(root);
    DiceRollQuests.load(root);
    WolfBroCatalog.load(root);
    DailyMissionCatalog.load(root);
    RogueHandbookCatalog.load(root);
    RogueBlessingQuests.load(root);
    RogueAeonCatalog.load(root);
    ExpeditionCatalog.load(root);
    dailyLevels.clear();
    dailyPool.clear();
    activities.clear();
    windows.clear();
    areas.clear();
    maps.clear();
    rooms.clear();
    rogueMonsters.clear();
    rogueMonsterGroups.clear();
    rogueMonsterGroupsPresent = java.nio.file.Files.isRegularFile(root.resolve("ExcelOutput/RogueMonsterGroup.json"));
    blessings.clear();
    rogueSeasons.clear();
    rogueUnlocks.clear();
    rogueRules.clear();
    rogueTalents.clear();
    rogueBuffGroups.clear();
    rogueConstants.clear();
    cycleRewards.clear();
    cycleConstants.clear();
    miracles.clear();
    miracleEffects.clear();
    miracleGroups.clear();
    musicLevels.clear();
    musicGroups.clear();
    musicPhases.clear();
    musicSongs.clear();
    musicPresets.clear();
    musicTracks.clear();
    musicConstants.clear();
    parkourLevels.clear();
    parkourBalls.clear();
    parkourConstants.clear();
    parkourRankings.clear();
    raids.clear();
    messageSections.clear();
    messageGroups.clear();
    messageItems.clear();
    roomPool.clear();
    subs.clear();
    worlds.clear();
    rows(root, "WorldDataConfig", World.class, v -> worlds.put(v.ID(), v));
    rows(root, "DailyActiveConfig", DailyLevel.class, dailyLevels::add);
    rows(root, "DailyActiveQuestPool", DailyPool.class, dailyPool::add);
    DailyCycleCatalog.load(root);
    rows(root, "ActivityLoginConfig", LoginActivity.class, v -> activities.put(v.ID(), v));
    rows(root, "RogueAreaConfig", Area.class, v -> areas.put(v.RogueAreaID(), v));
    rows(
        root,
        "RogueMap",
        Site.class,
        v -> maps.computeIfAbsent(v.RogueMapID(), k -> new ArrayList<>()).add(v));
    rows(root, "RogueRoom", Room.class, v -> rooms.put(v.RogueRoomID(), v));
    rows(root, "RogueMonster", RogueMonster.class, v -> rogueMonsters.put(v.RogueMonsterID(), v));
    rows(root, "RogueMonsterGroup", RogueMonsterGroup.class,
        v -> rogueMonsterGroups.put(v.RogueMonsterGroupID(), v));
    rows(root, "RogueBuff", Blessing.class, blessings::add);
    rows(root, "RogueManager", RogueSeason.class, rogueSeasons::add);
    rows(root, "RogueUnlockConfig", RogueUnlock.class, v -> rogueUnlocks.put(v.RogueUnlockID(), v));
    rows(root, "FinishWayRogue", RogueRule.class, v -> rogueRules.put(v.ID(), v));
    rows(root, "RogueTalent", RogueTalent.class, v -> rogueTalents.put(v.TalentID(), v));
    rows(
        root, "RogueBuffGroup", RogueBuffGroup.class, v -> rogueBuffGroups.put(v.GMLOGNJAIGI(), v));
    rows(root, "CycleScoreReward", CycleReward.class, v -> cycleRewards.put(v.ScoreRank(), v));
    rows(
        root,
        "ConstValueCommon",
        CommonConstant.class,
        v -> {
          if (v.ConstValueName() != null && v.ConstValueName().startsWith("CycleScore_"))
            cycleConstants.put(v.ConstValueName(), v.Value());
        });
    rows(
        root,
        "ConstValueRogue",
        RogueConstant.class,
        v -> rogueConstants.put(v.ConstRogueName(), v.ConstValue()));
    rows(root, "RogueMiracle", Miracle.class, v -> miracles.put(v.MiracleID(), v));
    rows(
        root,
        "RogueMiracleEffect",
        MiracleEffect.class,
        v -> miracleEffects.put(v.MiracleEffectID(), v));
    rows(
        root,
        "RogueMiracleGroup",
        MiracleGroup.class,
        v -> miracleGroups.put(v.RogueMiracleGroupID(), v));
    rows(root, "MusicRhythmLevel", MusicLevel.class, v -> musicLevels.put(v.ID(), v));
    rows(root, "MusicRhythmGroup", MusicGroup.class, v -> musicGroups.put(v.ID(), v));
    rows(root, "MusicRhythmPhase", MusicPhase.class, v -> musicPhases.put(v.Phase(), v));
    rows(root, "MusicRhythmSong", MusicSong.class, v -> musicSongs.put(v.ID(), v));
    rows(root, "MusicRhythmPresetSong", MusicPreset.class, v -> musicPresets.put(v.ID(), v));
    rows(root, "MusicRhythmTrack", MusicTrack.class, v -> musicTracks.put(v.ID(), v));
    MatchThreeCatalog.load(root);
    rows(root, "ParkourLevelConfig", ParkourLevel.class, v -> parkourLevels.put(v.ID(), v));
    rows(root, "ParkourRailBallConfig", ParkourBall.class, v -> parkourBalls.put(v.ID(), v));
    rows(root, "ParkourRankingList", ParkourRankReference.class, parkourRankings::add);
    rows(root, "RaidConfig", Raid.class, v -> raids.put(v.key(), v));
    rows(
        root,
        "ParkourConstValueCommon",
        MusicConstant.class,
        v -> {
          if (v.Value() != null) parkourConstants.put(v.ConstValueName(), v.Value().IntValue());
        });
    rows(
        root,
        "MusicRhythmConstValueCommon",
        MusicConstant.class,
        v -> {
          if (v.Value() != null) musicConstants.put(v.ConstValueName(), v.Value().IntValue());
        });
    rows(root, "MessageSectionConfig", MessageSection.class, v -> messageSections.put(v.ID(), v));
    rows(root, "MessageGroupConfig", MessageGroup.class, v -> messageGroups.put(v.ID(), v));
    rows(root, "MessageItemConfig", MessageItem.class, v -> messageItems.put(v.ID(), v));
    // Operator configuration is optional: never advertise every historical activity as active.
    Path settings = Path.of(LunarCore.getConfig().getDataDir(), "Gameplay46.json");
    try { ActivitySchedules.load(settings); }
    catch (java.io.IOException e) { throw new IllegalStateException("Invalid Gameplay46.json",e); }
    ActivityMissionSchedule.load(root);
    Path pool = Path.of(LunarCore.getConfig().getDataDir(), "RogueMapGen.json");
    if (Files.isRegularFile(pool))
      try (var reader = Files.newBufferedReader(pool)) {
        for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
          var ids = new ArrayList<Integer>();
          for (var id : e.getValue().getAsJsonArray())
            if (rooms.containsKey(id.getAsInt())) ids.add(id.getAsInt());
          roomPool.put(Integer.parseInt(e.getKey()), ids);
        }
      } catch (Exception e) {
        throw new IllegalStateException("Invalid RogueMapGen.json", e);
      }
    RogueMapPools.load(Path.of(LunarCore.getConfig().getDataDir(), "RogueMapGenByMap.json"));
    RogueBossEncounters.load(Path.of(LunarCore.getConfig().getDataDir(), "RogueBossGen.json"));
    loadMissions(root.resolve("Config/Level/Mission"));
    FirstTalkDefinitions.load(root);
    StoryLineDefinitions.load(root);
    HeartDialDefinitions.load(root);
    RogueDialogueDefinitions.load(root);
    RogueDialogueRewards.load(root, Path.of(LunarCore.getConfig().getDataDir(), "RogueDialogueRewards.json"));
    RogueRestMerchantRecipes.load(root, Path.of(LunarCore.getConfig().getDataDir(), "RogueRestMerchants.json"));
    ChessRogueRoutes.load(root, Path.of(LunarCore.getConfig().getDataDir(), "ChessRogueGuideRoutes.json"));
  }

  public static void loadMissions(Path root) {
    if (!Files.isDirectory(root)) return;
    GameData.getMissionInfos().clear();
    subs.clear();
    try (var paths = Files.walk(root)) {
      for (var path :
          paths
              .filter(p -> p.getFileName().toString().matches("MissionInfo_[0-9]+\\.json"))
              .sorted()
              .toList()) {
        var full = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        Path partial =
            path.resolveSibling(path.getFileName().toString().replace(".json", ".partial.json"));
        if (Files.isRegularFile(partial))
          for (var field :
              JsonParser.parseString(Files.readString(partial)).getAsJsonObject().entrySet()) {
            if (full.has(field.getKey()) && !full.get(field.getKey()).equals(field.getValue()))
              throw new IllegalStateException(
                  "Conflicting mission field: " + path + " " + field.getKey());
            full.add(field.getKey(), field.getValue());
          }
        var mission = ResourceLoader.getGson().fromJson(full, MissionInfo.class);
        GameData.getMissionInfos().put(mission.getMainMissionID(), mission);
        for (var sub : mission.getSubMissionList()) {
          if (sub.getMainMissionID() != mission.getMainMissionID()
              || subs.putIfAbsent(sub.getId(), sub) != null)
            throw new IllegalStateException("Conflicting sub mission: " + sub.getId());
          if (!sub.getMissionJsonPath().isBlank()) {
            Path resourceRoot =
                root.toAbsolutePath().normalize().getParent().getParent().getParent();
            Path script = resourceRoot.resolve(sub.getMissionJsonPath()).normalize();
            if (!script.startsWith(root.toAbsolutePath().normalize()))
              throw new IllegalStateException(
                  "Mission script escapes resource directory: " + sub.getId());
            if (Files.isRegularFile(script)) {
              var definition = JsonParser.parseString(Files.readString(script));
              sub.setTask(
                  ResourceLoader.getGson()
                      .fromJson(definition, emu.lunarcore.data.config.MissionTaskInfo.class));
              sub.getTask().retainSourceDefinition(definition);
              sub.setStoryTrialTask(StoryTrialReplacement.from(sub, definition));
            }
          }
        }
        UnavailableMissionScripts.markSource(
            root.toAbsolutePath().normalize().getParent().getParent().getParent(), full, mission);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load mission definitions", e);
    }
    LunarCore.getLogger()
        .info(
            "Loaded {} mission definitions / {} sub missions / {} literal story trial tasks",
            GameData.getMissionInfos().size(),
            subs.size(),
            subs.values().stream().filter(sub -> sub.getStoryTrialTask() != null).count());
  }

  static <T> void rows(Path root, String name, Class<T> type, Consumer<T> consume) {
    Path path = root.resolve("ExcelOutput/" + name + ".json");
    if (!Files.isRegularFile(path)) return;
    try (var reader = Files.newBufferedReader(path)) {
      var json = JsonParser.parseReader(reader);
      var rows =
          json.isJsonArray() ? json.getAsJsonArray() : json.getAsJsonObject().asMap().values();
      for (var row : rows) consume.accept(ResourceLoader.getGson().fromJson(row, type));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid gameplay table " + name, e);
    }
  }

  private GameplayDefinitions() {}
}
