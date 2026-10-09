package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UniverseTalentEffectsTest {
  @TempDir Path root;
  static final int AREA = 19996, MAP = 1999601, ROOM = 19996001;

  @BeforeEach
  void resources() throws Exception {
    var excel = Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        excel.resolve("RogueTalent.json"),
        """
        [{"TalentID":11,"EffectDescParamList":[],"Cost":[],"UnlockIDList":[]},
         {"TalentID":21,"EffectDescParamList":[],"Cost":[],"UnlockIDList":[]},
         {"TalentID":32,"EffectDescParamList":[{"Value":1}],"Cost":[],"UnlockIDList":[]}]
        """);
    // Numeric resource facts: OS 4.6 724b139d8c9c32d12552eb95745a4fee72bfe48b.
    Files.writeString(
        excel.resolve("ConstValueRogue.json"),
        """
        [{"ConstRogueName":"Rogue_Character_Initial_SP","ConstValue":"0.5"},
         {"ConstRogueName":"Roll_Buff_Cost","ConstValue":"[31:30],[31:50],[31:100]"},
         {"ConstRogueName":"Roll_Buff_Max_Count","ConstValue":"0"}]
        """);
    Files.writeString(
        excel.resolve("RogueBuffGroup.json"),
        """
        [{"GMLOGNJAIGI":100005,"HECJCAMDGNO":[100001,100002]},
         {"GMLOGNJAIGI":100001,"HECJCAMDGNO":[19997201,19997202]},
         {"GMLOGNJAIGI":100002,"HECJCAMDGNO":[19997203,19997204]}]
        """);
    GameplayDefinitions.load(root);
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, 19996, 1, 0, 0));
    GameplayDefinitions.maps.put(
        MAP,
        List.of(
            new GameplayDefinitions.Site(MAP, 1, true, List.of(2), 1),
            new GameplayDefinitions.Site(MAP, 2, false, List.of(), 1)));
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, 1, 12, 8));
    GameplayDefinitions.roomPool.put(1, List.of(ROOM));
    GameplayDefinitions.roomPool.put(2, List.of(ROOM));
    for (int id = 19997201; id <= 19997205; id++) {
      GameData.getMazeBuffExcelMap()
          .add(new Gson().fromJson("{\"ID\":" + id + ",\"Lv\":1}", MazeBuffExcel.class));
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(
              id, 1, 100, id == 19997205 ? "Legendary" : "Common", 1, List.of()));
    }
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.load(root.resolve("missing"));
  }

  UniverseLifecycleTest.Explorer explorer(int... talents) {
    var p = new UniverseLifecycleTest.Explorer();
    for (int id : talents) p.getGameplay().getRogueTalents().add(id);
    return p;
  }

  @Test
  void actualResourceInitialEnergyAndActivated32ApplyOnlyToTheNewUniverseTeam() {
    for (boolean full : List.of(false, true)) {
      var p = full ? explorer(32) : explorer();
      var normal = p.getCurrentLineup();
      p.avatar.setCurrentHp(normal, 2300);
      p.avatar.setCurrentSp(normal, 1200);
      normal.setMp(1);
      assertEquals(Retcode.SUCC, p.getUniverseManager().start(AREA));
      assertEquals(10000, p.avatar.getCurrentHp(p.getCurrentLineup()));
      assertEquals(full ? 10000 : 5000, p.avatar.getCurrentSp(p.getCurrentLineup()));
      assertEquals(p.getCurrentLineup().getMaxMp(), p.getCurrentLineup().getMp());
      assertEquals(2300, p.avatar.getCurrentHp(normal));
      assertEquals(1200, p.avatar.getCurrentSp(normal));
      assertEquals(1, normal.getMp());
      assertEquals(Retcode.SUCC, p.getUniverseManager().leave(false));
      assertEquals(Retcode.SUCC, p.getUniverseManager().resume(AREA, false));
      assertEquals(full ? 10000 : 5000, p.avatar.getCurrentSp(p.getCurrentLineup()));
    }
  }

  @Test
  void talent21CreatesOneInitialSavedSelectionFromTheRecursiveNormalResourceGroup() {
    var p = explorer(21);
    assertEquals(Retcode.SUCC, p.getUniverseManager().start(AREA));
    var run = p.getGameplay().getRogue();
    assertEquals(3, run.getOffers().size());
    assertFalse(run.getOffers().contains(19997205));
    assertFalse(run.getCleared().contains(1));
    var before = List.copyOf(run.getOffers());
    int batch = run.getSelectionId();
    assertEquals(Retcode.SUCC, p.getUniverseManager().leave(false));
    assertEquals(Retcode.SUCC, p.getUniverseManager().resume(AREA, false));
    assertEquals(before, run.getOffers());
    assertEquals(batch, run.getSelectionId());
    assertEquals(Retcode.SUCC, p.getUniverseManager().selectBlessing(batch, before.getFirst()));
    assertTrue(run.getOffers().isEmpty());
    assertEquals(
        Retcode.REQ_PARA_INVALID, p.getUniverseManager().selectBlessing(batch, before.getFirst()));
    assertTrue(p.getUniverseManager().offerBlessings().isEmpty());
    run.clear();
    assertEquals(3, p.getUniverseManager().offerBlessings().size());
    assertTrue(run.getSelectionId() > batch);
    assertFalse(run.getOffers().contains(before.getFirst()));
  }

  @Test
  void acquiringTalentDuringExplorationDoesNotInventAnInitialSelection() {
    var p = explorer();
    assertEquals(Retcode.SUCC, p.getUniverseManager().start(AREA));
    p.getGameplay().getRogueTalents().add(21);
    assertTrue(p.getUniverseManager().offerBlessings().isEmpty());
    assertEquals(Retcode.SUCC, p.getUniverseManager().leave(false));
    assertEquals(Retcode.SUCC, p.getUniverseManager().resume(AREA, false));
    assertTrue(p.getGameplay().getRogue().getOffers().isEmpty());
  }

  @Test
  void missingTalentResourceCannotGrantFullEnergyFromAnOldOwnedId() {
    GameplayDefinitions.rogueTalents.remove(32);
    var p = explorer(32);
    assertEquals(Retcode.SUCC, p.getUniverseManager().start(AREA));
    assertEquals(5000, p.avatar.getCurrentSp(p.getCurrentLineup()));
  }

  @Test
  void malformedTalentParamsAndMissingOrCyclicInitialPoolsNeverCreateBenefits() {
    GameplayDefinitions.rogueTalents.put(
        32,
        new Gson()
            .fromJson(
                "{\"TalentID\":32,\"EffectDescParamList\":[{\"Value\":2}]}",
                GameplayDefinitions.RogueTalent.class));
    GameplayDefinitions.rogueBuffGroups.put(
        100001, new GameplayDefinitions.RogueBuffGroup(100001, List.of(100005)));
    var p = explorer(21, 32);
    assertEquals(Retcode.SUCC, p.getUniverseManager().start(AREA));
    assertEquals(5000, p.avatar.getCurrentSp(p.getCurrentLineup()));
    assertTrue(p.getGameplay().getRogue().getOffers().isEmpty());
    GameplayDefinitions.rogueBuffGroups.remove(100001);
    p = explorer(21);
    assertEquals(Retcode.SUCC, p.getUniverseManager().start(AREA));
    assertTrue(p.getGameplay().getRogue().getOffers().isEmpty());
  }

  @Test
  void failedSceneEntryDoesNotLeakTalentBenefitsAndRetryGetsANewSelectionIdentity() {
    var p = explorer(21, 32);
    var normal = p.getCurrentLineup();
    p.avatar.setCurrentHp(normal, 2200);
    p.avatar.setCurrentSp(normal, 1300);
    p.rejectScene = true;
    assertEquals(Retcode.ROGUE_AREA_INVALID, p.getUniverseManager().start(AREA));
    assertEquals(GameplayState.RogueRun.Phase.IDLE, p.getGameplay().getRogue().getPhase());
    assertSame(normal, p.getCurrentLineup());
    assertEquals(2200, p.avatar.getCurrentHp(normal));
    assertEquals(1300, p.avatar.getCurrentSp(normal));
    p.rejectScene = false;
    assertEquals(Retcode.SUCC, p.getUniverseManager().start(AREA));
    assertEquals(3, p.getGameplay().getRogue().getOffers().size());
    assertTrue(p.getGameplay().getRogue().getSelectionId() > 1);
  }
}
