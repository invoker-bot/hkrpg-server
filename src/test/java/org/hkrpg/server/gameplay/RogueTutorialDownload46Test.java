package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.server.packet.Retcode;
import com.google.gson.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** Source-backed map 2 download gate; uses the combat fixture's isolated player. */
class RogueTutorialDownload46Test {
  @org.junit.jupiter.api.io.TempDir Path root;
  final RogueSecondStoryCombat46Test f = new RogueSecondStoryCombat46Test();
  JsonObject source;
  @BeforeEach void setup() throws Exception {
    f.setup();
    JsonObject records;
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-tutorial-download-4.6.json")) {
      records = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonObject("records");
    }
    source = records;
    var prior = GameData.getSpecialAvatarExcelMap().get(4000005, 0);
    var priorActor = GameData.getAvatarExcelMap().get(1013);
    var promotions = new ArrayList<>(GameData.getAvatarPromotionExcelMap().values());
    var skills = new ArrayList<>(GameData.getAvatarSkillTreeExcelMap().values());
    var anchors = new HashMap<>(GameDepot.getAvatarSkillTreeExcels());
    f.undo.add(() -> {
      GameData.getSpecialAvatarExcelMap().values().removeIf(r -> r.getId() == 4000005);
      if (prior != null) GameData.getSpecialAvatarExcelMap().add(prior);
      GameData.getAvatarExcelMap().values().removeIf(r -> r.getId() == 1013);
      if (priorActor != null) GameData.getAvatarExcelMap().add(priorActor);
      GameData.getAvatarPromotionExcelMap().values().clear(); promotions.forEach(GameData.getAvatarPromotionExcelMap()::add);
      GameData.getAvatarSkillTreeExcelMap().values().clear(); skills.forEach(GameData.getAvatarSkillTreeExcelMap()::add);
      GameDepot.getAvatarSkillTreeExcels().clear(); GameDepot.getAvatarSkillTreeExcels().putAll(anchors);
    });
    var config = emu.lunarcore.LunarCore.class.getDeclaredField("config"); config.setAccessible(true);
    var oldConfig = config.get(null); var settings = new emu.lunarcore.Config(); settings.resourceDir = root.toString();
    config.set(null, settings);
    f.undo.add(() -> { try { config.set(null, oldConfig); } catch (Exception e) { throw new AssertionError(e); } });
    var preset = root.resolve("Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_0.json");
    Files.createDirectories(preset.getParent()); Files.writeString(preset, records.get("skillPreset").toString());
    for (var row : records.getAsJsonArray("AvatarPromotionConfig.json"))
      GameData.getAvatarPromotionExcelMap().add(f.gson.fromJson(row, AvatarPromotionExcel.class));
    GameData.getSpecialAvatarExcelMap().add(f.gson.fromJson(records.getAsJsonArray("SpecialAvatar.json").get(0), SpecialAvatarExcel.class));
    var rawActor = records.getAsJsonArray("AvatarConfig.json").get(0).getAsJsonObject().deepCopy();
    for (String field : Set.copyOf(rawActor.keySet()))
      if (rawActor.get(field).isJsonObject() && rawActor.getAsJsonObject(field).has("Hash"))
        rawActor.add(field, rawActor.getAsJsonObject(field).get("Hash"));
    var actor = f.gson.fromJson(rawActor, AvatarExcel.class); actor.onLoad();
    GameData.getAvatarExcelMap().add(actor);
    for (var row : records.getAsJsonArray("AvatarSkillTreeConfig.json")) {
      var skill = f.gson.fromJson(row, AvatarSkillTreeExcel.class); skill.onLoad(); GameData.getAvatarSkillTreeExcelMap().add(skill);
    }
    var initial = GameplayState.RogueRun.class.getDeclaredField("initialAvatarSp");
    initial.setAccessible(true); initial.setInt(f.run(), 5000);
  }
  @AfterEach void cleanup() { f.cleanup(); }

  void ready() {
    f.savedVictory(3, 200002); f.savedVictory(4, 200001);
    f.player.getUniverseManager().restoreFirstStoryBlessings();
    f.select(); f.select();
  }

  @Test void secondBlessingUnlocksDownloadButNotItsBarrier() {
    assertEquals(PropState.Locked, f.prop(4, 300002).getState());
    ready();
    assertEquals(PropState.Closed, f.prop(4, 300002).getState());
    assertEquals(PropState.Closed, f.prop(19, 300005).getState());
    f.roundTrip();
    assertEquals(PropState.Closed, f.prop(4, 300002).getState());
    assertEquals(PropState.Closed, f.prop(19, 300005).getState());
  }

  int nativePick(List<Integer> formal, List<Integer> trials) throws Exception {
    var session = new RogueSecondStoryCombat46Test.Session(f.player);
    var request = com.google.protobuf.DynamicMessage.newBuilder(session.protocol.clientSchema().message("PickRogueAvatarCsReq"));
    request.setField(request.getDescriptorForType().findFieldByNumber(6), f.prop(4, 300002).getEntityId());
    formal.forEach(id -> request.addRepeatedField(request.getDescriptorForType().findFieldByNumber(14), id));
    trials.forEach(id -> request.addRepeatedField(request.getDescriptorForType().findFieldByNumber(11), id));
    assertTrue(org.hkrpg.server.protocol.RogueDownload46.handle(session, 1864, request.build().toByteArray()));
    var reply = session.messages.get("PickRogueAvatarScRsp");
    if ((Integer) reply.getField(reply.getDescriptorForType().findFieldByNumber(13)) == 0) {
      assertEquals(trials, reply.getField(reply.getDescriptorForType().findFieldByNumber(6)));
      assertEquals(formal, reply.getField(reply.getDescriptorForType().findFieldByNumber(15)));
    }
    return (Integer) reply.getField(reply.getDescriptorForType().findFieldByNumber(13));
  }

  @Test void nativeTrialDownloadOpensBoundBarrierWithoutChangingDeployedOrOwnedParty() throws Exception {
    ready(); var roster = List.copyOf(f.run().getRoster()); var owned = Set.copyOf(f.player.getAvatars().getAvatars().keySet());
    assertEquals(0, nativePick(List.of(), List.of(4000005)));
    assertEquals(roster, f.run().getRoster());
    assertEquals(owned, f.player.getAvatars().getAvatars().keySet());
    assertEquals(PropState.Open, f.prop(4, 300002).getState());
    assertEquals(PropState.Open, f.prop(19, 300005).getState());
    var actor = f.player.getAvatarById(4000005);
    assertNotNull(actor); assertEquals(1013, actor.getActorId()); assertEquals(6, actor.getLevel());
    assertEquals(emu.lunarcore.proto.AvatarTypeOuterClass.AvatarType.AVATAR_TRIAL_TYPE, actor.getAvatarType());
    assertEquals(Retcode.SUCC, f.player.getUniverseManager().lineup().tryReplace(List.of(4000005), 0));
    f.player.getUniverseManager().lineup().hp(4000005, 2400);
    f.roundTrip();
    assertEquals(2400, f.player.getUniverseManager().lineup().hp(4000005));
    assertEquals(PropState.Open, f.prop(19, 300005).getState());
    assertEquals(6, f.player.getAvatarById(4000005).getLevel());
    assertEquals(0, nativePick(List.of(), List.of(4000005)));
    assertEquals(2400, f.player.getUniverseManager().lineup().hp(4000005), "Retry must not heal or duplicate a trial");
    assertNull(f.player.getAvatars().getAvatarById(4000005));
  }

  @Test void failedSaveDoesNotConsumeDownloadOrOpenBarrier() {
    ready(); f.player.failSave = true;
    assertThrows(IllegalStateException.class, () -> RogueTutorialDownload.pick(f.player, List.of(4000005), f.prop(4,300002).getEntityId()));
    assertFalse(f.run().getVitals().containsKey(4000005));
    assertTrue(f.run().getDownloadedTrials().isEmpty()); assertTrue(f.run().getUsedDownloadConsoles().isEmpty());
    assertEquals(PropState.Closed, f.prop(19,300005).getState());
    assertEquals(PropState.Closed, f.prop(4,300002).getState());
  }

  @Test void unavailableOrDifferentTrialDoesNotAdvance() throws Exception {
    assertNotEquals(0, nativePick(List.of(), List.of(4000005)));
    ready(); assertNotEquals(0, nativePick(List.of(), List.of(4000001)));
    assertNotEquals(0, nativePick(List.of(199961), List.of(4000005)));
    assertTrue(f.run().getDownloadedTrials().isEmpty());
    assertEquals(PropState.Closed, f.prop(19,300005).getState());
  }

  @Test void sourceAndRoomBindingCannotBeBypassedWithASavedBlessing() {
    ready();
    var marker = f.floor.getGroups().get(4).getPropList().stream().filter(p -> p.getID()==300001).findFirst().orElseThrow();
    f.floor.getGroups().get(4).getPropList().remove(marker);
    assertNotEquals(Retcode.SUCC, RogueTutorialDownload.pick(f.player, List.of(4000005), f.prop(4,300002).getEntityId()));
    assertTrue(f.run().getDownloadedTrials().isEmpty());
    f.roundTrip(); assertEquals(PropState.Locked, f.prop(4,300002).getState());
  }

  @Test void trialIsAvailableInFollowingRoomButNeverInTheOrdinaryParty() throws Exception {
    ready(); assertEquals(0, nativePick(List.of(), List.of(4000005)));
    assertEquals(Retcode.SUCC, f.player.getUniverseManager().lineup().tryReplace(List.of(4000005), 0));
    f.run().enter(2,202); f.roundTrip();
    assertEquals(1013, f.player.getAvatarById(4000005).getActorId());
    assertEquals(6, f.player.getAvatarById(4000005).toBattleProto(f.player.getUniverseManager().lineup(),0).getLevel());
    f.run().setInside(false);
    assertNull(f.player.getAvatarById(4000005));
    assertNull(f.player.getAvatars().getAvatarById(4000005));
    assertNotNull(f.player.getUniverseManager().downloadedTrial(4000005), "Pause/resume preflight retains the saved trial");
    f.run().setInside(true); assertNotNull(f.player.getAvatarById(4000005));
  }

  @Test void failedDeploymentSaveKeepsThePreviousRoster() throws Exception {
    ready(); assertEquals(0, nativePick(List.of(),List.of(4000005)));
    var original = List.copyOf(f.run().getRoster()); f.player.failSave=true;
    assertThrows(IllegalStateException.class, () -> f.player.getUniverseManager().lineup().tryReplace(List.of(4000005),0));
    assertEquals(original,f.run().getRoster());
  }

  @Test void actualDownloadCompletesPropObjectiveOnlyAfterPersistingTheTrial() throws Exception {
    var prior = new HashMap<>(GameplayDefinitions.subs);
    f.undo.add(() -> { GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(prior); });
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.put(403000207, f.gson.fromJson(source.get("mission207"), emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class));
    f.player.getGameplay().getMainMissions().put(4030002,new emu.lunarcore.game.quest.QuestState(4030002));
    var target = new emu.lunarcore.game.quest.QuestState(403000207);
    f.player.getGameplay().getSubMissions().put(403000207,target);
    ready(); assertEquals(emu.lunarcore.game.quest.QuestState.Phase.DOING, target.getPhase());
    assertEquals(0,nativePick(List.of(),List.of(4000005)));
    assertEquals(emu.lunarcore.game.quest.QuestState.Phase.FINISHED,target.getPhase());
    long time=target.getFinishTime();f.roundTrip();RogueTutorialDownload.sync(f.player);
    assertEquals(time,f.player.getGameplay().getSubMissions().get(403000207).getFinishTime());
  }

  @Test void nativeAvailablePoolUsesSpecialFieldAndReplacementChecksTheTrialType() throws Exception {
    ready(); assertEquals(0,nativePick(List.of(),List.of(4000005)));
    class Session extends RogueSecondStoryCombat46Test.Session {
      byte[] replacement;
      Session() {super(f.player);}
      @Override public void sendClientPacket(int command,byte[] payload) { assertEquals(719,command);replacement=payload; }
    }
    var session=new Session();
    assertTrue(org.hkrpg.server.protocol.Universe46.handle(session,session.protocol.clientCommand("GetRogueInfoCsReq"),new byte[0]));
    var reply=session.messages.get("GetRogueInfoScRsp");
    var game=(com.google.protobuf.DynamicMessage) reply.getField(reply.getDescriptorForType().findFieldByName("rogue_game_info"));
    var current=(com.google.protobuf.DynamicMessage)game.getField(game.getDescriptorForType().findFieldByName("CFDIKLBINHK"));
    var pool=(com.google.protobuf.DynamicMessage)current.getField(current.getDescriptorForType().findFieldByName("EIOHFJMFOKF"));
    assertEquals(List.of(4000005),pool.getField(pool.getDescriptorForType().findFieldByNumber(4)));
    assertEquals(List.of(199961),pool.getField(pool.getDescriptorForType().findFieldByNumber(1)));
    for(String typeName:List.of("AvatarType_AvatarFormalType","AvatarType_AvatarTrialType")) {
      var request=com.google.protobuf.DynamicMessage.newBuilder(session.protocol.clientSchema().message("ReplaceLineupCsReq"));
      var extra=request.getDescriptorForType().findFieldByName("extra_lineup_type");request.setField(extra,extra.getEnumType().findValueByNumber(2));
      var slots=request.getDescriptorForType().findFieldByName("lineup_slot_list");var slot=com.google.protobuf.DynamicMessage.newBuilder(slots.getMessageType());
      slot.setField(slot.getDescriptorForType().findFieldByName("id"),4000005);
      var type=slot.getDescriptorForType().findFieldByName("avatar_type");slot.setField(type,type.getEnumType().findValueByName(typeName));
      request.addRepeatedField(slots,slot.build());
      assertTrue(org.hkrpg.server.protocol.RogueLineup46.handle(session,704,request.build().toByteArray()));
      var wire=com.google.protobuf.UnknownFieldSet.parseFrom(session.replacement);
      assertEquals(typeName.endsWith("FormalType"),wire.hasField(9));
    }
    assertEquals(List.of(4000005),f.run().getRoster());
  }

  @Test void h2BackendRestartRestoresFrozenTrialVitalsAndDownloadGate() throws Exception {
    ready(); assertEquals(0, nativePick(List.of(),List.of(4000005)));
    assertEquals(Retcode.SUCC, f.player.getUniverseManager().lineup().tryReplace(List.of(4000005),0));
    f.player.getUniverseManager().lineup().hp(4000005,2400);
    String runId=f.run().getRunId(); var skills=new HashMap<>(f.player.getAvatarById(4000005).getSkillTree());
    assertFalse(skills.isEmpty(), "The original None preset still preserves default-unlocked skills");
    var field=emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay"); field.setAccessible(true);
    for (int cycle=0;cycle<2;cycle++) {
      var mongo=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(root.resolve("download.mv").toString()));
      var address=mongo.bind();
      try(var client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort())) {
        var providers=org.bson.codecs.configuration.CodecRegistries.fromCodecs(new emu.lunarcore.database.codecs.IntSetCodec(),
            new emu.lunarcore.database.codecs.IntListCodec(),new emu.lunarcore.database.codecs.Int2IntMapCodec());
        var options=dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false).codecProvider(providers).build();
        var store=dev.morphia.Morphia.createDatastore(client,"download",options);
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(emu.lunarcore.game.player.Player.class,emu.lunarcore.game.quest.QuestState.class,emu.lunarcore.util.Position.class);
        if(cycle==0) {
          var document=new emu.lunarcore.game.player.Player();
          var uid=emu.lunarcore.game.player.Player.class.getDeclaredField("uid"); uid.setAccessible(true);uid.setInt(document,19997832);
          field.set(document,f.player.getGameplay());store.save(document);
        }
        var saved=store.find(emu.lunarcore.game.player.Player.class).first();assertNotNull(saved);
        field.set(f.player,saved.getGameplay());f.reloadScene();
        assertEquals(runId,f.run().getRunId());assertEquals(List.of(4000005),f.run().getRoster());
        assertEquals(2400,f.player.getUniverseManager().lineup().hp(4000005));
        assertEquals(skills,f.player.getAvatarById(4000005).getSkillTree());
        assertEquals(PropState.Open,f.prop(4,300002).getState());assertEquals(PropState.Open,f.prop(19,300005).getState());
      } finally {mongo.shutdownNow();}
    }
  }
}
