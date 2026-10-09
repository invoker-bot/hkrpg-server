package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class Music46CompositionTest {
  static final int SONG = 199935, LEVEL = 1999351, GROUP = 1999352, TRACK = 1999353, SUB = 1999354;
  static final String COMPOSE = "Activity_MusicRhythm_MusicComposeUnlockMission";
  Gameplay46HandlerTest.Session session;
  emu.lunarcore.data.excel.ActivityPanelExcel priorPanel;

  @BeforeEach
  void setup() {
    session = new Gameplay46HandlerTest.Session();
    priorPanel = emu.lunarcore.data.GameData.getActivityPanelExcelMap().get(50022);
    emu.lunarcore.data.GameData.getActivityPanelExcelMap()
        .add(
            new com.google.gson.Gson()
                .fromJson(
                    "{\"PanelID\":50022}", emu.lunarcore.data.excel.ActivityPanelExcel.class));
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50022, 0, 0, 4102444800L));
    GameplayDefinitions.musicLevels.put(
        LEVEL,
        new GameplayDefinitions.MusicLevel(LEVEL, GROUP, 1, List.of(1, 2, 3), List.of(0, 0, 0)));
    GameplayDefinitions.musicGroups.put(
        GROUP, new GameplayDefinitions.MusicGroup(GROUP, 1, 0, List.of(TRACK)));
    GameplayDefinitions.musicSongs.put(
        SONG,
        new GameplayDefinitions.MusicSong(
            SONG,
            3,
            SUB,
            List.of(2, 3, 4),
            List.of(TRACK),
            List.of(1999355),
            SUB,
            List.of(1999356)));
    GameplayDefinitions.musicPresets.put(
        1999355, new GameplayDefinitions.MusicPreset(1999355, List.of(7)));
    GameplayDefinitions.musicTracks.put(
        TRACK, new GameplayDefinitions.MusicTrack(TRACK, SUB, List.of()));
    GameplayDefinitions.musicConstants.put(COMPOSE, 1999357);
    // The synthetic panel is open; the song still requires its own real mission gates.
    var complete = new QuestState(SUB);
    complete.advance(1, 1, 1);
    session.player.getGameplay().getSubMissions().put(SUB, complete);
    var main = new QuestState(1999357);
    main.advance(1, 1, 1);
    session.player.getGameplay().getMainMissions().put(1999357, main);
  }

  @AfterEach
  void clear() {
    if (priorPanel != null) emu.lunarcore.data.GameData.getActivityPanelExcelMap().add(priorPanel);
    else emu.lunarcore.data.GameData.getActivityPanelExcelMap().keySet().remove(50022);
    GameplayDefinitions.windows.removeIf(w -> w.panelId() == 50022 && w.beginTime() == 0);
    GameplayDefinitions.musicLevels.remove(LEVEL);
    GameplayDefinitions.musicGroups.remove(GROUP);
    GameplayDefinitions.musicSongs.remove(SONG);
    GameplayDefinitions.musicTracks.remove(TRACK);
    GameplayDefinitions.musicPresets.remove(1999355);
    GameplayDefinitions.musicConstants.remove(COMPOSE);
  }

  DynamicMessage save(int song, int preset, List<Integer> grid, List<Integer> sfx)
      throws Exception {
    var req = Gameplay46.builder(session, "MusicRhythmSaveSongConfigDataCsReq");
    var config = Gameplay46.builder(session, "MusicRhythmGroup");
    Gameplay46.set(config, "music_group_phase", song);
    Gameplay46.set(config, "music_group_id", preset);
    grid.forEach(v -> Gameplay46.add(config, "EOHMNCBOALC", v));
    sfx.forEach(v -> Gameplay46.add(config, "BBINFDHMGII", v));
    Gameplay46.set(req, "BENAFNHLCEO", config.build());
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("MusicRhythmSaveSongConfigDataCsReq"),
            req.build().toByteArray()));
    assertEquals("MusicRhythmSaveSongConfigDataScRsp", session.responseName);
    return session.response;
  }

  @Test
  void anOpenMusicLevelCannotStartDuringASavedMissionRaid() {
    var manager = new MusicManager(session.player);
    session
        .player
        .getGameplay()
        .beginMissionRaid(
            new GameplayState.MissionRaidRun(49979, 0, 199979, 49979, 49979001, 499791));
    assertEquals(emu.lunarcore.server.packet.Retcode.MUSIC_LOCKED, manager.start(LEVEL));
    assertEquals(0, session.player.getGameplay().getRhythm().getActiveLevel());
    session.player.getGameplay().endMissionRaid();
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, manager.start(LEVEL));
    assertEquals(LEVEL, session.player.getGameplay().getRhythm().getActiveLevel());
  }

  @Test
  void aValidatedSaveAdvancesOnlyTheCurrentlyAcceptedSongObjective() throws Exception {
    int main = 199934, first = 1999341, next = 1999342;
    var gson = new com.google.gson.Gson();
    for (int id : List.of(first, next)) {
      var row =
          gson.fromJson(
              "{\"ID\":"
                  + id
                  + ",\"MainMissionID\":"
                  + main
                  + ",\"TakeType\":\""
                  + (id == first ? "Auto" : "AnySequence")
                  + "\",\"TakeParamIntList\":["
                  + first
                  + "],\"FinishType\":\"MusicRhythmSaveSongConfig\",\"ParamType\":\"Equal\",\"ParamInt1\":"
                  + SONG
                  + "}",
              emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(id, row);
    }
    try {
      session.player.getGameplay().getMainMissions().put(main, new QuestState(main));
      session.player.getMissionManager().refresh();
      assertNotEquals(0, Gameplay46.number(save(SONG, 0, List.of(8), List.of()), "retcode"));
      assertEquals(0, session.player.getMissionManager().sub(first).getProgress());
      assertEquals(0, Gameplay46.number(save(SONG, 0, List.of(5), List.of()), "retcode"));
      assertEquals(
          QuestState.Phase.FINISHED, session.player.getMissionManager().sub(first).getPhase());
      assertEquals(0, session.player.getMissionManager().sub(next).getProgress());
      session.player.getMissionManager().refresh();
      assertEquals(0, session.player.getMissionManager().sub(next).getProgress());
      assertEquals(0, Gameplay46.number(save(SONG, 0, List.of(3), List.of()), "retcode"));
      assertEquals(
          QuestState.Phase.FINISHED, session.player.getMissionManager().sub(next).getPhase());
    } finally {
      GameplayDefinitions.subs.remove(first);
      GameplayDefinitions.subs.remove(next);
    }
  }

  @Test
  void queryRestoresEarnedStarsUsingTheFieldReadByTheClient() throws Exception {
    session.player.getGameplay().getRhythm().start(LEVEL);
    session.player.getGameplay().getRhythm().finish(2, false);
    session.request("MusicRhythmDataCsReq");
    var row = InitialData46Test.first(session.response, "level_data_list");
    assertEquals(2, Gameplay46.number(row, "unlock_level"));
    assertTrue(((List<?>) Gameplay46.value(session.response, "music_group")).isEmpty());
  }

  @Test
  void customCompositionRoundTripsWithoutTreatingSongsAsLevelGroups() throws Exception {
    assertEquals(0, Gameplay46.number(save(SONG, 0, List.of(5), List.of(1999356)), "retcode"));
    assertEquals(SONG, Gameplay46.number(session.response, "cur_song_id"));
    session.request("MusicRhythmDataCsReq");
    var song = InitialData46Test.first(session.response, "music_group");
    assertEquals(SONG, Gameplay46.number(song, "music_group_phase"));
    assertEquals(0, Gameplay46.number(song, "music_group_id"));
    assertEquals(List.of(5), Gameplay46.value(song, "EOHMNCBOALC"));
    assertEquals(List.of(1999356), Gameplay46.value(song, "BBINFDHMGII"));
    assertEquals(SONG, Gameplay46.number(session.response, "cur_song_id"));
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void invalidAndLockedCompositionsLeaveThePreviousSongIntact() throws Exception {
    assertEquals(0, Gameplay46.number(save(SONG, 0, List.of(5), List.of()), "retcode"));
    assertNotEquals(0, Gameplay46.number(save(SONG, 0, List.of(8), List.of()), "retcode"));
    assertNotEquals(0, Gameplay46.number(save(SONG, 0, List.of(1, 1), List.of()), "retcode"));
    assertNotEquals(0, Gameplay46.number(save(SONG, 0, List.of(1), List.of(42)), "retcode"));
    assertNotEquals(0, Gameplay46.number(save(SONG, 99, List.of(), List.of()), "retcode"));
    assertNotEquals(0, Gameplay46.number(save(SONG, 1999355, List.of(1), List.of()), "retcode"));
    session.player.getGameplay().getCancelledSubs().add(SUB);
    assertNotEquals(0, Gameplay46.number(save(SONG, 0, List.of(1), List.of()), "retcode"));
    session.player.getGameplay().getCancelledSubs().remove(SUB);
    session.request("MusicRhythmDataCsReq");
    assertEquals(
        List.of(5),
        Gameplay46.value(InitialData46Test.first(session.response, "music_group"), "EOHMNCBOALC"));
  }

  @Test
  void resourcePresetAndComposerGateAreCheckedBeforeChangingTheCurrentSong() throws Exception {
    assertEquals(0, Gameplay46.number(save(SONG, 1999355, List.of(), List.of()), "retcode"));
    session.player.getGameplay().getCancelledMissions().add(1999357);
    assertNotEquals(0, Gameplay46.number(save(SONG, 0, List.of(5), List.of()), "retcode"));
    session.request("MusicRhythmDataCsReq");
    var song = InitialData46Test.first(session.response, "music_group");
    assertEquals(1999355, Gameplay46.number(song, "music_group_id"));
    assertEquals(List.of(), Gameplay46.value(song, "EOHMNCBOALC"));
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void highestDifficultyUsesTheGlobalResourceGateAndTheNativeUnlockFlag() throws Exception {
    final String mission = "Activity_MusicRhythm_Max_Difficulty_Levels_Unlock_Mission";
    final String star = "Activity_MusicRhythm_Max_Difficulty_Levels_Unlock_Star";
    final String maximum = "Activity_MusicRhythm_Level_Max_Difficulty";
    GameplayDefinitions.musicConstants.put(mission, 1999357);
    GameplayDefinitions.musicConstants.put(star, 3);
    GameplayDefinitions.musicConstants.put(maximum, 3);
    GameplayDefinitions.musicLevels.put(
        LEVEL + 1,
        new GameplayDefinitions.MusicLevel(
            LEVEL + 1, GROUP, 2, List.of(1, 2, 3), List.of(0, 0, 0)));
    GameplayDefinitions.musicLevels.put(
        LEVEL + 2,
        new GameplayDefinitions.MusicLevel(
            LEVEL + 2, GROUP, 3, List.of(1, 2, 3), List.of(0, 0, 0)));
    GameplayDefinitions.musicPhases.put(
        1, new GameplayDefinitions.MusicPhase(1, SONG, List.of(TRACK), 0));
    try {
      var rhythm = session.player.getGameplay().getRhythm();
      rhythm.getStars().put(LEVEL + 1, 1);
      var manager = new MusicManager(session.player);
      assertFalse(manager.unlocked(LEVEL + 2));
      rhythm.getStars().put(LEVEL + 1, 3);
      assertFalse(manager.unlocked(LEVEL + 2));
      rhythm.getStars().put(LEVEL, 3);
      assertTrue(manager.unlocked(LEVEL + 2));
      session.request("MusicRhythmDataCsReq");
      assertEquals(true, Gameplay46.value(session.response, "show_hint"));
      session.player.getGameplay().getCancelledMissions().add(1999357);
      assertFalse(manager.unlocked(LEVEL + 2));
      session.request("MusicRhythmDataCsReq");
      assertEquals(false, Gameplay46.value(session.response, "show_hint"));
    } finally {
      GameplayDefinitions.musicConstants.remove(mission);
      GameplayDefinitions.musicConstants.remove(star);
      GameplayDefinitions.musicConstants.remove(maximum);
      GameplayDefinitions.musicLevels.remove(LEVEL + 1);
      GameplayDefinitions.musicLevels.remove(LEVEL + 2);
      GameplayDefinitions.musicPhases.remove(1);
    }
  }

  @Test
  void hiddenSongUsesTheMinimumRatingOfAllResourceLevelsIncludingUnplayedOnes() {
    GameplayDefinitions.musicSongs.put(
        SONG + 1,
        new GameplayDefinitions.MusicSong(
            SONG + 1, 2, 1, List.of(2), List.of(TRACK), List.of(), 0, List.of()));
    GameplayDefinitions.musicPhases.put(
        1, new GameplayDefinitions.MusicPhase(1, SONG, List.of(TRACK), 0));
    GameplayDefinitions.musicLevels.put(
        LEVEL + 1,
        new GameplayDefinitions.MusicLevel(
            LEVEL + 1, GROUP, 3, List.of(1, 2, 3), List.of(0, 0, 0)));
    try {
      var compose = new MusicComposition(session.player);
      var ratings = session.player.getGameplay().getRhythm().getStars();
      ratings.put(LEVEL, 3);
      assertFalse(compose.songUnlocked(SONG + 1));
      ratings.put(LEVEL + 1, 1);
      assertTrue(compose.songUnlocked(SONG + 1));
      ratings.put(12345, 3);
      ratings.remove(LEVEL + 1);
      assertFalse(compose.songUnlocked(SONG + 1));
      ratings.put(LEVEL + 1, 1);
      GameplayDefinitions.musicPhases.remove(1);
      assertFalse(compose.songUnlocked(SONG + 1));
    } finally {
      GameplayDefinitions.musicSongs.remove(SONG + 1);
      GameplayDefinitions.musicLevels.remove(LEVEL + 1);
      GameplayDefinitions.musicPhases.remove(1);
    }
  }

  @Test
  void theAllLevelMissionIncludesEveryDifficultyUpToItsResourceCeiling() {
    final int goal = 1999358, parent = 1999359;
    var c =
        new com.google.gson.Gson()
            .fromJson(
                "{\"ID\":"
                    + goal
                    + ",\"MainMissionID\":"
                    + parent
                    + ",\"TakeType\":\"Auto\",\"FinishType\":\"MusicRhythmFinishAllLevel\",\"ParamType\":\"GreaterEqual\",\"ParamInt1\":2,\"ParamInt2\":3}",
                emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(goal, c);
    GameplayDefinitions.musicPhases.put(
        1, new GameplayDefinitions.MusicPhase(1, SONG, List.of(TRACK), 0));
    GameplayDefinitions.musicLevels.put(
        LEVEL + 1,
        new GameplayDefinitions.MusicLevel(
            LEVEL + 1, GROUP, 2, List.of(1, 2, 3), List.of(0, 0, 0)));
    GameplayDefinitions.musicLevels.put(
        LEVEL + 2,
        new GameplayDefinitions.MusicLevel(
            LEVEL + 2, GROUP, 3, List.of(1, 2, 3), List.of(0, 0, 0)));
    try {
      session.player.getGameplay().getMainMissions().put(parent, new QuestState(parent));
      var ratings = session.player.getGameplay().getRhythm().getStars();
      ratings.put(LEVEL + 1, 3);
      session.player.getMissionManager().refresh();
      assertEquals(0, session.player.getMissionManager().sub(goal).getProgress());
      ratings.put(LEVEL, 3);
      session.player.getMissionManager().refresh();
      assertEquals(
          QuestState.Phase.FINISHED, session.player.getMissionManager().sub(goal).getPhase());
      assertEquals(0, ratings.getOrDefault(LEVEL + 2, 0));
    } finally {
      GameplayDefinitions.subs.remove(goal);
      GameplayDefinitions.musicPhases.remove(1);
      GameplayDefinitions.musicLevels.remove(LEVEL + 1);
      GameplayDefinitions.musicLevels.remove(LEVEL + 2);
    }
  }

  @Test
  void nativeSongListMeansUnlockedSoundEffectsAndDoesNotBypassTheirStoryGate() throws Exception {
    var song = GameplayDefinitions.musicSongs.get(SONG);
    GameplayDefinitions.musicSongs.put(
        SONG,
        new GameplayDefinitions.MusicSong(
            SONG,
            song.UnlockType(),
            song.UnlockTypeParam(),
            song.GridNumList(),
            song.TrackIDList(),
            song.PresetIDList(),
            SUB + 1,
            song.SoundEffectIDList()));
    session.request("MusicRhythmDataCsReq");
    assertEquals(List.of(), Gameplay46.value(session.response, "unlock_song_list"));
    var done = new QuestState(SUB + 1);
    done.advance(1, 1, 1);
    session.player.getGameplay().getSubMissions().put(SUB + 1, done);
    session.request("MusicRhythmDataCsReq");
    assertEquals(List.of(SONG), Gameplay46.value(session.response, "unlock_song_list"));
    session.player.getGameplay().getCancelledSubs().add(SUB + 1);
    session.request("MusicRhythmDataCsReq");
    assertEquals(List.of(), Gameplay46.value(session.response, "unlock_song_list"));
  }

  @Test
  void finishingTheSoundEffectObjectiveSendsTheNativeSongIdsOnce() {
    int parent = 1999359, goal = 1999358;
    var song = GameplayDefinitions.musicSongs.get(SONG);
    GameplayDefinitions.musicSongs.put(
        SONG,
        new GameplayDefinitions.MusicSong(
            SONG,
            song.UnlockType(),
            song.UnlockTypeParam(),
            song.GridNumList(),
            song.TrackIDList(),
            song.PresetIDList(),
            goal,
            song.SoundEffectIDList()));
    var c =
        new com.google.gson.Gson()
            .fromJson(
                "{\"ID\":"
                    + goal
                    + ",\"MainMissionID\":"
                    + parent
                    + ",\"TakeType\":\"Auto\",\"FinishType\":\"MusicRhythmFinishAllLevel\",\"ParamType\":\"GreaterEqual\",\"ParamInt1\":1,\"ParamInt2\":1}",
                emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(goal, c);
    GameplayDefinitions.musicPhases.put(
        1, new GameplayDefinitions.MusicPhase(1, SONG, List.of(TRACK), 0));
    var p =
        new emu.lunarcore.game.player.Player() {
          public boolean isOnline() {
            return true;
          }

          public emu.lunarcore.server.game.GameSession getSession() {
            return session;
          }

          public void save() {}
        };
    try {
      p.getGameplay().getMainMissions().put(parent, new QuestState(parent));
      p.getGameplay().getRhythm().getStars().put(LEVEL, 1);
      p.getMissionManager().refresh();
      assertEquals("MusicRhythmUnlockSongSfxScNotify", session.responseName);
      assertEquals(List.of(SONG), Gameplay46.value(session.response, "PHCHFKMHDMA"));
      session.responseName = "no new notification";
      p.getMissionManager().refresh();
      assertEquals("no new notification", session.responseName);
      Music46.syncMaximum(p);
      assertEquals("MusicRhythmMaxDifficultyLevelsUnlockNotify", session.responseName);
    } finally {
      GameplayDefinitions.subs.remove(goal);
      GameplayDefinitions.musicPhases.remove(1);
    }
  }
}
