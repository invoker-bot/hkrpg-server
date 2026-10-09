package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UniverseTalent46Test {
  @TempDir Path root;

  static class Resident extends Player {
    public void save() {}
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Resident resident = new Resident();

    Session() {
      var done = new emu.lunarcore.game.quest.QuestState(4030007);
      done.advance(1, 1, 1);
      resident.getGameplay().getMainMissions().put(4030007, done);
    }

    public Player getPlayer() {
      return resident;
    }
  }

  @BeforeEach
  void resources() throws Exception {
    var excel = Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        excel.resolve("RogueTalent.json"),
        """
[{"TalentID":1,"NextTalentIDList":[2,3],"Cost":[{"ItemID":32,"ItemNum":50}],"UnlockIDList":[]},
 {"TalentID":2,"NextTalentIDList":[4],"Cost":[{"ItemID":32,"ItemNum":100}],"UnlockIDList":[10401]},
 {"TalentID":3,"NextTalentIDList":[4],"Cost":[{"ItemID":32,"ItemNum":100}],"UnlockIDList":[10401]},
 {"TalentID":4,"NextTalentIDList":[],"Cost":[{"ItemID":32,"ItemNum":50}],"UnlockIDList":[10402]}]
""");
    Files.writeString(
        excel.resolve("RogueUnlockConfig.json"),
        """
[{"RogueUnlockID":10401,"UnlockFinishWay":10301},{"RogueUnlockID":10402,"UnlockFinishWay":10302}]
""");
    Files.writeString(
        excel.resolve("FinishWayRogue.json"),
        """
[{"ID":10301,"FinishType":"RogueTalentEnable","ParamType":"ListContain","ParamIntList":[1],"Progress":1},
 {"ID":10302,"FinishType":"RogueTalentEnable","ParamType":"ListContain","ParamIntList":[2,3],"Progress":2}]
""");
    Files.writeString(
        excel.resolve("ConstValueRogue.json"),
        """
        [{"ConstRogueName":"Rogue_Talent_Unlock_ID","ConstValue":"11000"}]
        """);
    GameplayDefinitions.load(root);
    GameplayDefinitions.rogueUnlocks.put(11000, new GameplayDefinitions.RogueUnlock(11000, 11000));
    GameplayDefinitions.rogueRules.put(
        11000,
        new GameplayDefinitions.RogueRule(
            11000, "FinishMission", "ListContain", 0, 0, 0, "", List.of(4030007), List.of(), 1));
  }

  DynamicMessage talent(Session s, int id) {
    var list = (DynamicMessage) InitialData46Test.field(s.response, "JCOBJAMPOAL");
    @SuppressWarnings("unchecked")
    var rows = (List<DynamicMessage>) InitialData46Test.field(list, "BLHJIPBHHFO");
    return rows.stream()
        .filter(r -> InitialData46Test.field(r, "CKHCLKOALKE").equals(id))
        .findFirst()
        .orElseThrow();
  }

  void points(Session s, int amount) throws Exception {
    var field = Player.class.getDeclaredField("talentPoints");
    field.setAccessible(true);
    field.setInt(s.resident, amount);
  }

  int enable(Session s, int id) throws Exception {
    var req = Gameplay46.builder(s, "EnableRogueTalentCsReq");
    Gameplay46.set(req, "CKHCLKOALKE", id);
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand("EnableRogueTalentCsReq"), req.build().toByteArray()));
    assertEquals("EnableRogueTalentScRsp", s.responseName);
    return (Integer) InitialData46Test.field(s.response, "retcode");
  }

  @Test
  void globalMissionUnlockAndCancelledPrerequisitesCannotBeBypassedWithCurrency() throws Exception {
    var s = new Session();
    points(s, 500);
    s.resident.getGameplay().getMainMissions().clear();
    assertNotEquals(0, enable(s, 1));
    assertEquals(500, s.resident.getTalentPoints());
    var done = new emu.lunarcore.game.quest.QuestState(4030007);
    done.advance(1, 1, 1);
    s.resident.getGameplay().getMainMissions().put(4030007, done);
    s.resident.getGameplay().getCancelledMissions().add(4030007);
    assertNotEquals(0, enable(s, 1));
    assertEquals(500, s.resident.getTalentPoints());
    s.resident.getGameplay().getCancelledMissions().clear();
    assertEquals(0, enable(s, 1));
    assertEquals(450, s.resident.getTalentPoints());
  }

  @Test
  void missingOrZeroGlobalUnlockIsNotAnUnconditionalFreePass() throws Exception {
    var s = new Session();
    points(s, 500);
    GameplayDefinitions.rogueConstants.put("Rogue_Talent_Unlock_ID", "0");
    assertNotEquals(0, enable(s, 1));
    assertEquals(500, s.resident.getTalentPoints());
    GameplayDefinitions.rogueConstants.remove("Rogue_Talent_Unlock_ID");
    assertNotEquals(0, enable(s, 1));
    assertEquals(500, s.resident.getTalentPoints());
  }

  @Test
  void queryReflectsResourcePrerequisitesWithoutInventingActivationOrCurrency() throws Exception {
    var s = new Session();
    points(s, 500);
    s.request("GetRogueTalentInfoCsReq");
    assertEquals(
        "RogueTalentStatus_Unlock", InitialData46Test.field(talent(s, 1), "status").toString());
    assertEquals(
        "RogueTalentStatus_Lock", InitialData46Test.field(talent(s, 2), "status").toString());
    var condition = InitialData46Test.first(talent(s, 4), "LDHIJAHNEGK");
    assertEquals(10402, InitialData46Test.field(condition, "MMFGAJNCAOA"));
    assertEquals(0, InitialData46Test.field(condition, "progress"));
    assertEquals(false, InitialData46Test.field(condition, "finish"));
    s.request("GetRogueTalentInfoCsReq");
    assertEquals(500, s.resident.getTalentPoints());
    assertTrue(s.resident.getGameplay().getRewards().isEmpty());
  }

  @Test
  void parallelActivationChargesOnlyOneCopyAndMalformedCostsStayLocked() throws Exception {
    var s = new Session();
    points(s, 100);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var requests =
          pool.invokeAll(
              List.of(
                  () -> org.hkrpg.server.gameplay.RogueTalents.enable(s.resident, 1),
                  () -> org.hkrpg.server.gameplay.RogueTalents.enable(s.resident, 1)));
      assertEquals(
          1,
          requests.stream()
              .filter(
                  f -> {
                    try {
                      return f.get() == Retcode.SUCC;
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  })
              .count());
      assertEquals(50, s.resident.getTalentPoints());
    } finally {
      pool.shutdownNow();
    }
    var bad =
        new com.google.gson.Gson()
            .fromJson(
                "{\"TalentID\":2,\"Cost\":[{\"ItemID\":2,\"ItemNum\":1}],\"UnlockIDList\":[10401]}",
                GameplayDefinitions.RogueTalent.class);
    GameplayDefinitions.rogueTalents.put(2, bad);
    assertNotEquals(0, enable(s, 2));
    assertEquals(50, s.resident.getTalentPoints());
    GameplayDefinitions.rogueTalents.put(
        2,
        new com.google.gson.Gson()
            .fromJson(
                "{\"TalentID\":2,\"Cost\":[{\"ItemID\":32,\"ItemNum\":2147483647},{\"ItemID\":32,\"ItemNum\":1}],\"UnlockIDList\":[10401]}",
                GameplayDefinitions.RogueTalent.class));
    assertNotEquals(0, enable(s, 2));
    assertEquals(50, s.resident.getTalentPoints());
  }

  @Test
  void realPlayerDocumentRestoresBalanceAndActivatedNodesWithoutRepeatingTheDebit()
      throws Exception {
    var s = new Session();
    points(s, 100);
    assertEquals(0, enable(s, 1));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-talent-restore");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456781);
      var points = Player.class.getDeclaredField("talentPoints");
      points.setAccessible(true);
      points.setInt(document, s.resident.getTalentPoints());
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.resident.getGameplay());
      store.save(document);
      var restored = store.find(Player.class).first();
      var resumed =
          new Session() {
            public Player getPlayer() {
              return restored;
            }
          };
      resumed.request("GetRogueTalentInfoCsReq");
      assertEquals(
          "RogueTalentStatus_Enable",
          InitialData46Test.field(talent(resumed, 1), "status").toString());
      assertEquals(
          "RogueTalentStatus_Unlock",
          InitialData46Test.field(talent(resumed, 2), "status").toString());
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN,
          org.hkrpg.server.gameplay.RogueTalents.enable(restored, 1));
      assertEquals(50, restored.getTalentPoints());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void activationDebitsExactlyOnceAndJoinedBranchesRequireEveryPredecessor() throws Exception {
    var s = new Session();
    points(s, 300);
    assertNotEquals(0, enable(s, 2));
    assertEquals(300, s.resident.getTalentPoints());
    assertEquals(0, enable(s, 1));
    assertEquals(250, s.resident.getTalentPoints());
    assertNotEquals(0, enable(s, 1));
    assertEquals(250, s.resident.getTalentPoints());
    assertEquals(0, enable(s, 2));
    assertEquals(150, s.resident.getTalentPoints());
    assertNotEquals(0, enable(s, 4));
    assertEquals(150, s.resident.getTalentPoints());
    var condition = InitialData46Test.first(talent(s, 4), "LDHIJAHNEGK");
    assertEquals(1, InitialData46Test.field(condition, "progress"));
    assertEquals(false, InitialData46Test.field(condition, "finish"));
    assertEquals(0, enable(s, 3));
    assertEquals(50, s.resident.getTalentPoints());
    assertEquals(
        "RogueTalentStatus_Unlock", InitialData46Test.field(talent(s, 4), "status").toString());
    assertEquals(0, enable(s, 4));
    assertEquals(0, s.resident.getTalentPoints());
    assertEquals(
        "RogueTalentStatus_Enable", InitialData46Test.field(talent(s, 4), "status").toString());
  }

  @Test
  void insufficientCurrencyUnknownIdsAndUnknownWireFieldsCannotActivate() throws Exception {
    var s = new Session();
    points(s, 49);
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), enable(s, 1));
    assertNotEquals(0, enable(s, -1));
    assertNotEquals(0, enable(s, 999));
    byte[] malformed = {0x40, 1, 0x48, 1};
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand("EnableRogueTalentCsReq"), malformed));
    assertNotEquals(0, InitialData46Test.field(s.response, "retcode"));
    assertEquals(49, s.resident.getTalentPoints());
  }
}
