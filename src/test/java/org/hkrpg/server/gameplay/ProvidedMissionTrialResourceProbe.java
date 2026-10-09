package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Read installed resources; acceptance and party publication are explicit isolated fixtures. */
public final class ProvidedMissionTrialResourceProbe {
  static final class Actor extends Player {
    int plane, floor;
    boolean virtual;
    emu.lunarcore.game.player.PlayerGender trialGender =
        emu.lunarcore.game.player.PlayerGender.GENDER_MAN;
    final PlayerLineup party =
        new PlayerLineup(this, 0) {
          @Override
          public boolean isExtraLineup() {
            return virtual;
          }
        };
    final LineupManager manager = new LineupManager(this);

    @Override
    public LineupManager getLineupManager() {
      return manager;
    }

    @Override
    public int getPlaneId() {
      return plane;
    }

    @Override
    public int getFloorId() {
      return floor;
    }

    @Override
    public emu.lunarcore.game.player.PlayerGender getGender() {
      return trialGender;
    }

    @Override
    public int getWorldLevel() {
      return 0;
    }

    @Override
    public PlayerLineup getCurrentLineup() {
      return party;
    }

    @Override
    public void save() {
      throw new IllegalStateException("Read-only actor cannot save");
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2 || args.length == 2 && !args[1].equals("list"))
      throw new IllegalArgumentException("Expected resource directory and optional list mode");
    boolean listMode = args.length == 2;
    var root = Path.of(args[0]).toRealPath();
    if (!root.resolve("resource-manifest.json").toFile().isFile())
      throw new IllegalArgumentException("Missing resource manifest");
    var config = new Config();
    config.resourceDir = root.toString();
    var field = LunarCore.class.getDeclaredField("config");
    field.setAccessible(true);
    field.set(null, config);
    ResourceLoader.loadResources();
    GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission"));
    var gson = new Gson();
    JsonObject fixture;
    try (var reader =
        new InputStreamReader(
            Objects.requireNonNull(
                ProvidedMissionTrialResourceProbe.class.getResourceAsStream(
                    listMode
                        ? "/fixtures/get-trial-avatar-list-missions-4.6.json"
                        : "/fixtures/get-trial-avatar-missions-4.6.json")),
            StandardCharsets.UTF_8)) {
      fixture = JsonParser.parseReader(reader).getAsJsonObject();
    }
    var results = new ArrayList<Map<String, Object>>();
    int completedConditions = 0;
    var originals = new ArrayList<JsonObject>();
    for (var element : fixture.getAsJsonArray("cases")) {
      var source = element.getAsJsonObject();
      if (!listMode) originals.add(source);
      else
        for (var role : source.getAsJsonArray("roles")) {
          var record = source.deepCopy();
          role.getAsJsonObject()
              .entrySet()
              .forEach(e -> record.add(e.getKey(), e.getValue().deepCopy()));
          originals.add(record);
        }
    }
    for (var original : originals) {
      int id = original.getAsJsonObject("sub").get("ID").getAsInt();
      var sub = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
      if (sub.getTask() == null
          || !JsonParser.parseString(sub.getTask().getSourceDefinition())
              .equals(original.get("script")))
        throw new IllegalStateException("Changed original mission script: " + id);
      int trialId = original.getAsJsonObject("special").get("SpecialAvatarID").getAsInt();
      var player = new Actor();
      if (trialId == 1108002)
        player.trialGender = emu.lunarcore.game.player.PlayerGender.GENDER_WOMAN;
      player.plane = sub.getLevelPlaneID();
      player.floor = sub.getLevelFloorID();
      player.virtual =
          sub.getTask().getSourceDefinition().contains("RPG.GameCore.ReplaceVirtualTeam");
      player
          .getGameplay()
          .getMainMissions()
          .put(sub.getMainMissionID(), new QuestState(sub.getMainMissionID()));
      player.getGameplay().getSubMissions().put(id, new QuestState(id));
      var trial =
          Objects.requireNonNull(
              player.getTrialManager().buildTrialAvatar(trialId),
              "Actual source trial must build: " + id);
      if (trial.getAvatarId() != trialId
          || trial.getActorId() != original.getAsJsonObject("special").get("AvatarID").getAsInt()
          || trial.getSpecialAvatarExcel().getLevel()
              != original.getAsJsonObject("special").get("Level").getAsInt()
          || trial.getSkillTree().isEmpty())
        throw new IllegalStateException("Invalid source trial identity or real skills: " + id);
      player.getAvatars().addTempAvatar(trial);
      player.party.getAvatars().add(trial.getAvatarId());
      var before = gson.toJson(player.getGameplay());
      boolean satisfied = TrialAvatarMissionConditions.satisfied(player, sub);
      if (satisfied != (listMode || id != 103420827)
          || !before.equals(gson.toJson(player.getGameplay()))
          || !player.getAvatars().getAvatars().isEmpty())
        throw new IllegalStateException("Unexpected provided trial condition or mutation: " + id);
      if (satisfied) completedConditions++;
      var party = player.party.toProto();
      var battle = trial.toBattleProto(player.party, 0);
      if (party.getAvatarList().length() != 1
          || party.getAvatarList().get(0).getId() != trial.getAvatarId()
          || party.getAvatarList().get(0).getAvatarType() != AvatarType.AVATAR_TRIAL_TYPE
          || battle.getId() != trial.getAvatarId()
          || battle.getLevel() != trial.getLevel()
          || battle.getSkilltreeList().length() != trial.getSkillTree().size()
          || battle.getEquipmentList().length() != trial.getEquips().size())
        throw new IllegalStateException("Actual source trial message differs: " + id);
      var result = new LinkedHashMap<String, Object>();
      result.put("subMissionId", id);
      result.put("trialId", trial.getAvatarId());
      result.put("actorId", trial.getActorId());
      result.put("level", trial.getLevel());
      result.put("promotion", trial.getPromotion());
      result.put("realSkills", new TreeMap<>(trial.getSkillTree()));
      result.put("equipmentCount", trial.getEquips().size());
      result.put("virtualParty", player.virtual);
      result.put("providedGender", player.trialGender.toString());
      result.put("conditionSatisfied", satisfied);
      results.add(result);
    }
    if (results.size() != (listMode ? 5 : 10) || completedConditions != (listMode ? 5 : 9))
      throw new IllegalStateException("Missing original provided trial case");
    System.out.println(
        gson.toJson(
            Map.of(
                "passed",
                true,
                "cases",
                results,
                "completedConditions",
                completedConditions,
                "automaticActorProvisioning",
                false,
                "acceptedMissionFixture",
                true,
                "manuallyPublishedPartyFixture",
                true,
                "operatorDataModified",
                false,
                "clientGameplayVerified",
                false)));
  }

  private ProvidedMissionTrialResourceProbe() {}
}
