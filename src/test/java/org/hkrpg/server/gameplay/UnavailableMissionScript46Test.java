package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UnavailableMissionScript46Test {
  private static final int MAIN=4040118, SUB=404011802;
  @TempDir Path root;
  private final Map<Integer,emu.lunarcore.data.config.MissionInfo> oldMains=new HashMap<>(GameData.getMissionInfos());
  private final Map<Integer,emu.lunarcore.data.config.MissionInfo.SubMissionInfo> oldSubs=new HashMap<>(GameplayDefinitions.subs);
  private final ClientNpcOwnership46Test.Actor actor=new ClientNpcOwnership46Test.Actor();

  @BeforeEach void prepare() throws Exception {
    var fixture=JsonParser.parseReader(new java.io.InputStreamReader(Objects.requireNonNull(
        getClass().getResourceAsStream("/fixtures/unavailable-mission-script-4.6.json")),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    for(var entry:fixture.entrySet())write(entry.getKey(),entry.getValue());
    write("ExcelOutput/PerformanceE.json",new JsonArray());
    load();actor.getGameplay().getMainMissions().put(MAIN,new QuestState(MAIN));
  }
  @AfterEach void restore() {
    GameData.getMissionInfos().clear();GameData.getMissionInfos().putAll(oldMains);
    GameplayDefinitions.subs.clear();GameplayDefinitions.subs.putAll(oldSubs);
  }
  @Test void missingUnreferencedAutoBranchCannotStartTheClientScript() throws Exception {
    assertFalse(eligible(SUB));assertTrue(eligible(404011801));
    assertArrayEquals(new int[]{404011803},GameData.getMissionInfos().get(MAIN).getFinishSubMissionList());
    assertFalse(actor.getGameplay().getSubMissions().containsKey(SUB));
  }
  @Test void oldDoingSaveIsRetractedWithoutCompletingItOrItsParent() {
    var saved=new QuestState(SUB);actor.getGameplay().getSubMissions().put(SUB,saved);
    assertTrue(MissionSnapshotRecords.retiredSub(actor,SUB));
    assertNull(MissionSnapshotRecords.sceneSub(actor,SUB));
    assertEquals(QuestState.Phase.DOING,saved.getPhase());assertEquals(0,saved.getProgress());
    assertEquals(QuestState.Phase.DOING,actor.getGameplay().getMainMissions().get(MAIN).getPhase());
  }
  @Test void legacySessionKeepsTheExistingRule() throws Exception {
    actor.wire=ProtocolBridge.forVersion("4.2.0");assertTrue(eligible(SUB));
  }
  @Test void restoredPerformanceOrChangedScriptKeepTheirAuthoredPath() throws Exception {
    write("ExcelOutput/PerformanceE.json",JsonParser.parseString("[{\"PerformanceID\":404011800}]"));load();assertTrue(eligible(SUB));
    write("ExcelOutput/PerformanceE.json",new JsonArray());
    write("Config/Level/Mission/4040118/Mission_404011802.json",JsonParser.parseString("{\"Type\":\"Mission\",\"OnStartSequece\":[]}"));
    load();assertTrue(eligible(SUB));
  }
  @Test void operatorChangedMissionDependencyIsNotSuppressed() throws Exception {
    var file=root.resolve("Config/Level/Mission/4040118/MissionInfo_4040118.json");
    var raw=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    raw.add("FinishSubMissionList",JsonParser.parseString("[404011802]"));write(root.relativize(file).toString(),raw);
    load();assertTrue(eligible(SUB));
  }
  private void load(){GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission"));}
  private boolean eligible(int id) throws Exception {
    var method=MissionManager.class.getDeclaredMethod("subEligible",emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class);
    method.setAccessible(true);return (boolean)method.invoke(actor.getMissionManager(),GameplayDefinitions.subs.get(id));
  }
  private void write(String name,JsonElement value) throws Exception {
    var file=root.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,value.toString());
  }
}
