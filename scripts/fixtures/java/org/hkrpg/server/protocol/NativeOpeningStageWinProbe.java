package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Fresh opening party, tutorial progress and first victory over KCP; no seeded player state. */
public final class NativeOpeningStageWinProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<DynamicMessage> lineupUpdates = new ArrayList<>();
  private final List<Integer> unloadedGroups = new ArrayList<>();
  private Ukcp channel;
  private int requests;
  private int enterSceneRequests;
  private int expectedFloor = 20001001;
  private int credits;
  private String loginNickname;
  private int rewardHints;
  private boolean ambush;
  private int ambushUseId = 395;
  private int inclinationAcknowledgements;
  private boolean verifyAvatarProgression;
  private boolean verifySpaceAnchors;
  private boolean verifyStoryParty;
  private int avatarProgressNotifications;

  private static Object field(DynamicMessage message, String name) {
    return message.getField(
        Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name), name));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private DynamicMessage call(String name, Map<String, Object> fields, boolean success)
      throws Exception {
    var request = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) ->
            request.setField(request.getDescriptorForType().findFieldByName(key), value));
    return callRaw(name, request.build().toByteArray(), success);
  }

  private DynamicMessage callRaw(String name, byte[] bytes, boolean success) throws Exception {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], bytes));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    requests++;
    if (name.equals("EnterSceneCsReq")) enterSceneRequests++;
    var response = name.replace("CsReq", "ScRsp");
    boolean formalReplacement = name.equals("ReplaceLineupCsReq");
    int responseCommand = formalReplacement ? 719 : protocol.clientCommand(response);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("KCP failure", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("SyncLineupNotify"))
        lineupUpdates.add(protocol.clientSchema().parse("SyncLineupNotify", frame.body()));
      if (frame.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
        var update = protocol.clientSchema().parse("SceneGroupRefreshScNotify", frame.body());
        require((Integer) field(update, "floor_id") == expectedFloor, "Group update floor");
        for (var raw : (List<?>) field(update, "group_refresh_list")) {
          var group = (DynamicMessage) raw;
          if (((Descriptors.EnumValueDescriptor) field(group, "refresh_type")).getNumber() == 2)
            unloadedGroups.add((Integer) field(group, "group_id"));
        }
      }
      if (frame.command() == protocol.clientCommand("ScenePlaneEventScNotify")) {
        protocol.clientSchema().parse("ScenePlaneEventScNotify", frame.body());
        rewardHints++;
      }
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var sync = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        if (sync.hasField(sync.getDescriptorForType().findFieldByName("basic_info")))
          credits = (Integer) field((DynamicMessage) field(sync, "basic_info"), "scoin");
        if (sync.hasField(sync.getDescriptorForType().findFieldByName("avatar_sync"))) {
          for (var value : (List<?>) field((DynamicMessage) field(sync, "avatar_sync"), "avatar_list")) {
            var avatar = (DynamicMessage) value;
            if ((Integer) field(avatar, "base_avatar_id") == 8001
                && ((Integer) field(avatar, "exp") > 0 || (Integer) field(avatar, "level") > 1))
              avatarProgressNotifications++;
          }
        }
      }
      if (frame.command() != responseCommand) continue;
      if (formalReplacement) {
        // Independently verified native CN replacement response: command 719, index 7, retcode 9.
        var fields = UnknownFieldSet.parseFrom(frame.body()).asMap();
        require(Set.of(7, 9).containsAll(fields.keySet()), "Unexpected native replacement fields");
        for (var value : fields.values())
          require(value.getVarintList().size() == 1 && value.getFixed32List().isEmpty()
              && value.getFixed64List().isEmpty() && value.getLengthDelimitedList().isEmpty()
              && value.getGroupList().isEmpty(), "Invalid replacement response wire type");
        require(!fields.containsKey(7) || fields.get(7).getVarintList().getFirst() == 0,
            "Replacement acknowledged a different normal team");
        long code = fields.containsKey(9) ? fields.get(9).getVarintList().getFirst() : 0;
        require((code == 0) == success, "Native replacement rejected: " + code);
        return null;
      }
      var result = protocol.clientSchema().parse(response, frame.body());
      require(result.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      require(
          ((Integer) field(result, "retcode") == 0) == success,
          "Unexpected status: " + response + " code=" + field(result, "retcode"));
      return result;
    }
    throw new IllegalStateException("Response timeout: " + response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), true);
  }

  private void progress(boolean finished) throws Exception {
    var data = call("GetMissionDataCsReq");
    var missions = new HashMap<Integer, DynamicMessage>();
    for (var row : (List<?>) field(data, "mission_list")) {
      var mission = (DynamicMessage) row;
      missions.put((Integer) field(mission, "id"), mission);
    }
    var battle = missions.get(100010103);
    require(
        battle != null && (Integer) field(battle, "progress") == (finished ? 1 : 0),
        "Opening battle progress");
    require(
        ((Descriptors.EnumValueDescriptor) field(battle, "status")).getNumber()
            == (finished ? 2 : 1),
        "Opening battle status");
    require(missions.containsKey(100010105) == finished, "Next opening objective");
  }

  private void openingParty(boolean silverWolf) throws Exception {
    var team = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var avatars = (List<?>) field(team, "avatar_list");
    require(avatars.size() == (silverWolf ? 2 : 1), "Opening story party size");
    var kafka = (DynamicMessage) avatars.getFirst();
    require((Integer) field(kafka, "id") == 1001005, "Opening Kafka special-avatar ID");
    require(
        ((Descriptors.EnumValueDescriptor) field(kafka, "avatar_type")).getNumber() == 1,
        "Opening actor must remain a trial");
    if (silverWolf)
      require(
          (Integer) field((DynamicMessage) avatars.get(1), "id") == 1001006,
          "Silver Wolf story trial must survive reconnect/restart");
    var owned =
        (List<?>)
            field(call("GetAvatarDataCsReq", Map.of("is_get_all", true), true), "avatar_list");
    require(
        owned.size() == 1
            && (Integer) field((DynamicMessage) owned.getFirst(), "base_avatar_id") == 8001,
        "Opening trials must not be granted as formal avatars");
  }

  private void tutorialProgress(boolean finished) throws Exception {
    var tutorials = (List<?>) field(call("GetTutorialCsReq"), "tutorial_list");
    var guides = (List<?>) field(call("GetTutorialGuideCsReq"), "tutorial_guide_list");
    if (!finished) {
      require(
          tutorials.isEmpty() && guides.isEmpty(), "Fresh tutorials were fabricated as completed");
    } else {
      require(
          tutorials.size() == 1 && guides.isEmpty(),
          "Tutorial progress must survive reconnect/restart");
      var item = (DynamicMessage) tutorials.getFirst();
      require(
          (Integer) field(item, "id") == 1001
              && ((Descriptors.EnumValueDescriptor) field(item, "status")).getNumber() == 2,
          "Only the acknowledged opening tutorial should be finished");
    }
  }

  private void openingScene(boolean expectDoorEffects, boolean silverWolf) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require(
        (Integer) field(scene, "entry_id") == 2000101
            && (Integer) field(scene, "floor_id") == 20001001,
        "Actual initial scene");
    boolean kafkaPresent = false;
    boolean doorEffects = false;
    boolean openingProps = false;
    for (var raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      int id = (Integer) field(group, "group_id");
      require(id != 93 && id != 6, "Opening scene contains locked world group " + id);
      doorEffects |= id == 52;
      openingProps |= id == 88;
      for (var value : (List<?>) field(group, "entity_list")) {
        var entity = (DynamicMessage) value;
        var actorField = entity.getDescriptorForType().findFieldByName("actor");
        if (!entity.hasField(actorField)) continue;
        var actor = (DynamicMessage) entity.getField(actorField);
        require(
            (Integer) field(actor, "base_avatar_id") == 7005
                || silverWolf && (Integer) field(actor, "base_avatar_id") == 7006,
            "Opening scene must use the Kafka actor");
        kafkaPresent |= (Integer) field(actor, "base_avatar_id") == 7005;
      }
    }
    require(kafkaPresent, "Opening scene Kafka actor is absent");
    require(
        doorEffects == expectDoorEffects && openingProps,
        "Opening scene lost its current story props");
  }

  private Map<Integer, DynamicMessage> doors() throws Exception {
    var result = new HashMap<Integer, DynamicMessage>();
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) value;
        if ((Integer) field(entity, "group_id") == 5)
          result.put((Integer) field(entity, "inst_id"), entity);
      }
    return result;
  }

  private void doorState(Map<Integer, DynamicMessage> props, int instance, int state) {
    var entity =
        Objects.requireNonNull(props.get(instance), "Missing opening door/console " + instance);
    require(
        (Integer) field((DynamicMessage) field(entity, "prop"), "prop_state") == state,
        "Door/console " + instance + " expected state " + state);
  }

  private void useDoor(int instance, boolean success) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var entity = doors().get(instance);
    call(
        "InteractPropCsReq",
        Map.of(
            "prop_entity_id",
            field(entity, "entity_id"),
            "interact_id2",
            1010,
            "interact_id",
            field(scene, "interact_id")),
        success);
  }

  private void storyDoors(String phase) throws Exception {
    var props = doors();
    if (phase.equals("first") || phase.equals("reconnect")) {
      doorState(props, 300001, 0);
      doorState(props, 300002, 0);
      doorState(props, 300003, 0);
      doorState(props, 300004, 2);
      useDoor(300003, false);
      useDoor(300004, false);
      if (phase.equals("reconnect")) {
        useDoor(300002, true);
        props = doors();
        doorState(props, 300001, 1);
        doorState(props, 300002, 1);
        doorState(props, 300003, 0);
        doorState(props, 300004, 0);
      }
    } else if (phase.equals("restart")) {
      doorState(props, 300001, 1);
      doorState(props, 300002, 1);
      doorState(props, 300003, 0);
      doorState(props, 300004, 0);
      useDoor(300004, true);
      props = doors();
      doorState(props, 300003, 1);
      doorState(props, 300004, 1);
    } else {
      doorState(props, 300001, 1);
      doorState(props, 300002, 1);
      doorState(props, 300003, 1);
      doorState(props, 300004, 1);
      useDoor(300004, false);
    }
    var missionData = call("GetMissionDataCsReq");
    var expected =
        phase.equals("first")
            ? Map.of(100010105, 1)
            : phase.equals("reconnect")
                ? Map.of(100010105, 2, 100010107, 1)
                : Map.of(100010105, 2, 100010107, 2);
    var statuses = new HashMap<Integer, Integer>();
    for (var raw : (List<?>) field(missionData, "mission_list")) {
      var m = (DynamicMessage) raw;
      statuses.put(
          (Integer) field(m, "id"),
          ((Descriptors.EnumValueDescriptor) field(m, "status")).getNumber());
    }
    expected.forEach(
        (id, status) -> require(status.equals(statuses.get(id)), "Door objective " + id));
  }

  private void capturedDestructible(boolean alreadyCollected) throws Exception {
    capturedDestructible(36, Map.of(300003, 100026, 300006, 100027), 2, alreadyCollected);
  }

  private void capturedDestructible(int groupId, Map<Integer, Integer> expected,
      int expectedMetal, boolean alreadyCollected) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    int caster = 0;
    var boxes = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      for (var rawEntity : (List<?>) field(group, "entity_list")) {
        var entity = (DynamicMessage) rawEntity;
        if (entity.hasField(entity.getDescriptorForType().findFieldByName("actor")))
          caster = (Integer) field(entity, "entity_id");
        if ((Integer) field(group, "group_id") == groupId
            && expected.containsKey((Integer) field(entity, "inst_id")))
          boxes.put((Integer) field(entity, "inst_id"), entity);
      }
    }
    require(
        caster > 0 && boxes.size() == (alreadyCollected ? 0 : expected.size()),
        "Paid boxes respawned or original source is absent");
    if (!alreadyCollected) {
      var tags =
          protocol
              .clientSchema()
              .message("SceneCastSkillCsReq")
              .findFieldByName("skill_extra_tags")
              .getEnumType();
      int useId = 138;
      for (int instance : new TreeSet<>(expected.keySet())) {
        var entity = boxes.get(instance);
        require(
            expected.get(instance).equals(field((DynamicMessage) field(entity, "prop"), "prop_id")),
            "Ordinary box source");
        int target = (Integer) field(entity, "entity_id");
        var request =
            Map.<String, Object>of(
                "cast_entity_id",
                useId++,
                "attacked_by_entity_id",
                caster,
                "assist_monster_entity_id_list",
                List.of(target),
                "skill_extra_tags",
                List.of(tags.findValueByNumber(1), tags.findValueByNumber(2)));
        int before = credits, hints = rewardHints;
        var response = call("SceneCastSkillCsReq", request, true);
        require(
            !response.hasField(response.getDescriptorForType().findFieldByName("battle_info")),
            "Box entered battle");
        require(
            credits == before + 100 && rewardHints == hints + 1,
            "Box credit delivery or reward hint missing");
        call("SceneCastSkillCsReq", request, false);
        require(credits == before + 100, "Repeated hit awarded credits twice");
      }
    }
    var bag = new HashMap<Integer, Integer>();
    for (var raw : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var item = (DynamicMessage) raw;
      bag.merge((Integer) field(item, "tid"), (Integer) field(item, "num"), Math::addExact);
    }
    require(Objects.equals(bag.get(180005), expectedMetal), "Box metal missing, duplicated, or not restored");
    var after = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    for (var raw : (List<?>) field(after, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      if ((Integer) field(group, "group_id") != groupId) continue;
      for (var value : (List<?>) field(group, "entity_list"))
        require(
            !expected.containsKey((Integer) field((DynamicMessage) value, "inst_id")),
            "Paid box still exists");
    }
  }

  private void recoveryProps() throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var bottles = new ArrayList<Integer>();
    // This probe sends Kafka's ability name; the active leader remains Kafka after Silver Wolf
    // joins.
    int caster = (Integer) field(scene, "leader_entity_id");
    for (var raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      for (var value : (List<?>) field(group, "entity_list")) {
        var entity = (DynamicMessage) value;
        if ((Integer) field(group, "group_id") == 91
            && entity.hasField(entity.getDescriptorForType().findFieldByName("prop"))
            && (Integer) field((DynamicMessage) field(entity, "prop"), "prop_id") == 100025)
          bottles.add((Integer) field(entity, "entity_id"));
      }
    }
    require(caster > 0 && bottles.size() == 4, "Original Base Zone recovery props");
    var team = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    int before = (Integer) field(team, "mp");
    int maximum = (Integer) field(team, "max_mp");
    int notifications = lineupUpdates.size();
    var locations =
        List.of(
            "hit_target_entity_id_list", "assist_monster_entity_id_list", "assistInfo", "direct");
    for (int i = 0; i < bottles.size(); i++) {
      int target = bottles.get(i);
      var fields = new LinkedHashMap<String, Object>();
      fields.put("cast_entity_id", 199990001 + i);
      fields.put("attacked_by_entity_id", caster);
      fields.put("maze_ability_str", "LocalPlayer_Kafka_NormalAtk01");
      var location = locations.get(i);
      if (location.equals("direct")) {
        fields.put("cast_entity_id", caster);
        fields.put("attacked_by_entity_id", target);
      } else if (location.equals("assistInfo")) {
        var infoType =
            protocol
                .clientSchema()
                .message("SceneCastSkillCsReq")
                .findFieldByName("assist_monster_entity_info")
                .getMessageType();
        var info = DynamicMessage.newBuilder(infoType);
        info.addRepeatedField(infoType.findFieldByName("entity_id_list"), target);
        fields.put("assist_monster_entity_info", List.of(info.build()));
      } else fields.put(location, List.of(target));
      var response = call("SceneCastSkillCsReq", fields, true);
      require(
          !response.hasField(response.getDescriptorForType().findFieldByName("battle_info")),
          "A recovery prop must not enter battle");
      call("SceneCastSkillCsReq", fields, false);
    }
    var after = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    require(
        (Integer) field(after, "mp") == Math.min(maximum, before + 8), "Recovery points or cap");
    require(
        lineupUpdates.size() - notifications == 4,
        "Each recovery must synchronize the mission party");
    for (int i = 0; i < 4; i++)
      require(
          (Integer)
                  field(
                      (DynamicMessage) field(lineupUpdates.get(notifications + i), "lineup"), "mp")
              == Math.min(maximum, before + 2 * (i + 1)),
          "Recovered MP notification");
    var updated = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    for (var raw : (List<?>) field(updated, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list"))
        require(
            !bottles.contains((Integer) field((DynamicMessage) value, "entity_id")),
            "Destroyed bottle remains in current scene");
  }

  private void openingChest(boolean alreadyOpened) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    DynamicMessage chest = null;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) value;
        if ((Integer) field(entity, "group_id") == 36
            && (Integer) field(entity, "inst_id") == 300002) chest = entity;
      }
    require(chest != null, "Original opening chest is absent");
    var prop = (DynamicMessage) field(chest, "prop");
    require(
        (Integer) field(prop, "prop_id") == 60001
            && (Integer) field(prop, "prop_state") == (alreadyOpened ? 13 : 12),
        "Opening chest lost its persistent state");
    var request =
        Map.<String, Object>of(
            "prop_entity_id",
            field(chest, "entity_id"),
            "interact_id2",
            112131,
            "interact_id",
            field(scene, "interact_id"));
    if (!alreadyOpened) {
      var reply = call("InteractPropCsReq", request, true);
      require((Integer) field(reply, "prop_state") == 13, "Opening chest did not open");
    }
    call("InteractPropCsReq", request, false);
  }

  private void deskReadings(boolean alreadyCollected) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var readings = new HashMap<Integer, DynamicMessage>();
    int avatar = 0;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) value;
        if (entity.hasField(entity.getDescriptorForType().findFieldByName("actor")))
          avatar = (Integer) field(entity, "entity_id");
        if ((Integer) field(entity, "group_id") == 44)
          readings.put((Integer) field(entity, "inst_id"), entity);
      }
    require(avatar > 0 && readings.size() == 5, "Original reading group is absent or over-spawned");
    require(
        !readings.containsKey(300003) && !readings.containsKey(300007),
        "Reading spawn gates were bypassed");
    for (int instance : new int[] {300002, 300001}) {
      var entity = readings.get(instance);
      require(entity != null, "Missing original reception-desk reading");
      require(
          (Integer) field((DynamicMessage) field(entity, "prop"), "prop_state")
              == (alreadyCollected ? 19 : 18),
          "Reading state lost across reconnect/restart");
      var motionType =
          protocol
              .clientSchema()
              .message("SceneEntityMoveCsReq")
              .findFieldByName("entity_motion_list")
              .getMessageType();
      var movement = DynamicMessage.newBuilder(motionType);
      movement.setField(motionType.findFieldByName("entity_id"), avatar);
      movement.setField(motionType.findFieldByName("motion"), field(entity, "motion"));
      call("SceneEntityMoveCsReq", Map.of("entity_motion_list", List.of(movement.build())), true);
      var request =
          Map.<String, Object>of(
              "prop_entity_id",
              field(entity, "entity_id"),
              "interact_id2",
              118191,
              "interact_id",
              field(scene, "interact_id"));
      if (!alreadyCollected) {
        var response = call("InteractPropCsReq", request, true);
        require((Integer) field(response, "prop_state") == 19, "Reading did not become collected");
      }
      call("InteractPropCsReq", request, false);
    }
    var bag = new HashMap<Integer, Integer>();
    for (var raw : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var item = (DynamicMessage) raw;
      bag.merge((Integer) field(item, "tid"), (Integer) field(item, "num"), Math::addExact);
    }
    require(
        Objects.equals(bag.get(190170), 1) && Objects.equals(bag.get(190282), 1),
        "Desk books missing, duplicated, or not restored in the real inventory");
  }

  private List<DynamicMessage> groupEntities(int id) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var result = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      if ((Integer) field(group, "group_id") == id)
        for (var entity : (List<?>) field(group, "entity_list"))
          result.add((DynamicMessage) entity);
    }
    return result;
  }

  private void objective(int id, int expectedStatus) throws Exception {
    var missions = (List<?>) field(call("GetMissionDataCsReq"), "mission_list");
    var target =
        missions.stream()
            .map(x -> (DynamicMessage) x)
            .filter(x -> (Integer) field(x, "id") == id)
            .findFirst()
            .orElseThrow();
    require(
        ((Descriptors.EnumValueDescriptor) field(target, "status")).getNumber() == expectedStatus,
        "Unexpected objective status: " + id);
  }

  private void corridorBattle(int instance, boolean win) throws Exception {
    var target =
        groupEntities(21).stream()
            .filter(x -> (Integer) field(x, "inst_id") == instance)
            .findFirst()
            .orElseThrow();
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    int caster = (Integer) field(scene, "leader_entity_id");
    var motionType =
        protocol
            .clientSchema()
            .message("SceneEntityMoveCsReq")
            .findFieldByName("entity_motion_list")
            .getMessageType();
    var movement = DynamicMessage.newBuilder(motionType);
    movement.setField(motionType.findFieldByName("entity_id"), caster);
    movement.setField(motionType.findFieldByName("motion"), field(target, "motion"));
    call("SceneEntityMoveCsReq", Map.of("entity_motion_list", List.of(movement.build())), true);
    DynamicMessage entered;
    if (ambush && instance == 200001) {
      var tag =
          protocol
              .clientSchema()
              .message("SceneCastSkillCsReq")
              .findFieldByName("skill_extra_tags")
              .getEnumType()
              .findValueByNumber(1);
      var attack = new HashMap<String, Object>();
      attack.put("cast_entity_id", ambushUseId++);
      attack.put("attacked_by_entity_id", field(target, "entity_id"));
      attack.put("target_motion", field(target, "motion"));
      attack.put("skill_extra_tags", List.of(tag));
      var empty = call("SceneCastSkillCsReq", attack, true);
      require(
          Objects.equals(field(empty, "cast_entity_id"), attack.get("cast_entity_id")),
          "Monster callback lost its Ability UseID");
      require(
          (Integer) field((DynamicMessage) field(empty, "battle_info"), "battle_id") == 0,
          "An empty monster hit must not invent a battle");
      attack.put("cast_entity_id", ambushUseId++);
      // The real monster-hit callback uses field 6 for its avatar target, despite the
      // inherited assist-monster field name. Keep the ordinary hit list absent.
      attack.put("assist_monster_entity_id_list", List.of(caster));
      // Literal wire regression: old protobuf handling ignored this nested field and
      // admitted an ambush. Rejection must leave the same connection and scene usable.
      var malformed = new java.io.ByteArrayOutputStream();
      var wire = CodedOutputStream.newInstance(malformed);
      wire.writeUInt32(2, (Integer) field(target, "entity_id"));
      wire.writeUInt32(13, (Integer) attack.get("cast_entity_id"));
      wire.writeUInt32(6, caster);
      wire.writeUInt32(11, 1);
      wire.writeByteArray(7, new byte[] {64, 1}); // Unknown group field 8.
      wire.flush();
      var denied = callRaw("SceneCastSkillCsReq", malformed.toByteArray(), false);
      require((Integer) field(denied, "retcode") == 5, "Malformed group must be rejected");
      require((Integer) field((DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info"), "battle_id") == 0,
          "Rejected nested fields must not create a battle");
      entered = call("SceneCastSkillCsReq", attack, true);
      require(
          Objects.equals(field(entered, "cast_entity_id"), attack.get("cast_entity_id")),
          "Ambush response lost its Ability UseID");
      var buffs = (List<?>) field((DynamicMessage) field(entered, "battle_info"), "buff_list");
      require(
          buffs.stream()
              .map(DynamicMessage.class::cast)
              .anyMatch(
                  x ->
                      (Integer) field(x, "id")
                          == emu.lunarcore.GameConstants.BATTLE_AMBUSH_BUFF_ID),
          "Monster entry lost the ambush effect");
    } else
      entered =
          call(
              "SceneCastSkillCsReq",
              Map.of(
                  "cast_entity_id",
                  caster,
                  "attacked_by_entity_id",
                  field(target, "entity_id"),
                  "maze_ability_str",
                  "LocalPlayer_Kafka_NormalAtk01"),
              true);
    var battle = (DynamicMessage) field(entered, "battle_info");
    require(
        (Integer) field(battle, "battle_id") > 0, "Original tutorial monster must enter battle");
    var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
    call(
        "PVEBattleResultCsReq",
        Map.of(
            "battle_id",
            field(battle, "battle_id"),
            "stage_id",
            field(battle, "stage_id"),
            "end_status",
            end.getEnumType().findValueByName(win ? "BATTLE_END_WIN" : "BATTLE_END_LOSE")),
        true);
  }

  private void corridorBarriers(boolean restored, boolean scriptedUnlock, boolean silverWolfRemoved) throws Exception {
    if (!restored) {
      require(!groupEntities(33).isEmpty(), "Hall barrier must precede its actual defeat");
      call(
          "FinishTalkMissionCsReq",
          Map.of("sub_mission_id", 100010108, "talk_str", "Mission_100010108"),
          true);
      unloadedGroups.clear();
      corridorBattle(200003, true);
      objective(100010122, 2);
      require(
          groupEntities(33).isEmpty() && Collections.frequency(unloadedGroups, 33) == 1,
          "Hall victory must send UNLOAD, not only delete individual props");
      objective(100010109, 1);
      openingParty(false);
      call(
          "FinishTalkMissionCsReq",
          Map.of("sub_mission_id", 100010109, "talk_str", "Mission_100010109"),
          true);
      openingParty(true);
      useDoor(300006, true);
      call(
          "FinishTalkMissionCsReq",
          Map.of("sub_mission_id", 100010112, "talk_str", "Mission_100010112"),
          true);
      objective(100010130, 1);
      require(!groupEntities(17).isEmpty(), "Corridor barrier opened before its objective");
      unloadedGroups.clear();
      corridorBattle(200001, false);
      objective(100010130, 1);
      require(
          !groupEntities(17).isEmpty() && !unloadedGroups.contains(17),
          "Loss must retain the corridor barrier");
      corridorBattle(200001, true);
      objective(100010130, 2);
      require(
          Collections.frequency(unloadedGroups, 17) == 1,
          "Corridor victory must send one actual group UNLOAD");
    }
    objective(100010122, 2);
    objective(100010130, 2);
    objective(100010113, restored && scriptedUnlock ? 2 : 1);
    require(
        groupEntities(17).isEmpty() && groupEntities(33).isEmpty(),
        "Completed story blockers respawned");
    openingParty(!silverWolfRemoved);
  }

  private void scriptedDoorUnlock(boolean restored, boolean continued) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    if (!restored) {
      var entered = call("SceneEnterStageCsReq", Map.of("event_id", 20001006), true);
      var battle = (DynamicMessage) field(entered, "battle_info");
      var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
      call("PVEBattleResultCsReq", Map.of("battle_id", field(battle, "battle_id"),
          "stage_id", field(battle, "stage_id"),
          "end_status", end.getEnumType().findValueByName("BATTLE_END_WIN")), true);
      objective(100010113, 2);
      objective(100010124, 1);
      var console = doors().get(300029);
      require((Integer) field((DynamicMessage) field(console, "prop"), "prop_state") == 2,
          "The scripted control must still be locked before config 1200");
      var unlocked = call("InteractPropCsReq", Map.of("prop_entity_id", field(console, "entity_id"),
          "interact_id2", 1200, "interact_id", field(scene, "interact_id")), true);
      require((Integer) field(unlocked, "prop_state") == 0, "Scripted unlock must return Closed");
    }
    objective(100010124, 2);
    objective(100010116, continued ? 2 : 1);
    var props = doors();
    require((Integer) field((DynamicMessage) field(props.get(300029), "prop"), "prop_state") == 0,
        "Scripted unlock was lost on reconnect/restart");
    require((Integer) field((DynamicMessage) field(props.get(300007), "prop"), "prop_state") == 0,
        "Unlock must not prematurely open the associated door");
  }

  private void stellaronConsole(boolean restored, boolean characterChoice) throws Exception {
    if (!restored) {
      var locked = doors().get(300018);
      require((Integer) field((DynamicMessage) field(locked, "prop"), "prop_state") == 2,
          "Stellaron console must stay locked before its objective");
      // Authored client dialogue receipts, not a claim that the game rendered these performances.
      for (int id : new int[] {100010116, 100010117, 100010118, 100010127,
          100010119, 100010125, 100010129, 100010128})
        call("FinishTalkMissionCsReq", Map.of("sub_mission_id", id, "talk_str", "Mission_" + id), true);
      objective(100010126, 1);
      var ready = doors().get(300018);
      require((Integer) field((DynamicMessage) field(ready, "prop"), "prop_state") == 0,
          "Accepted stellaron objective must enable its console");
      var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
      var response = call("InteractPropCsReq", Map.of("prop_entity_id", field(ready, "entity_id"),
          "interact_id2", 1010, "interact_id", field(scene, "interact_id")), true);
      require((Integer) field(response, "prop_state") == 1, "Stellaron interaction must return Open");
    }
    objective(100010126, 2);
    objective(100010133, restored && characterChoice ? 2 : 1);
    openingParty(false);
    var props = doors();
    require((Integer) field((DynamicMessage) field(props.get(300018), "prop"), "prop_state") == 1,
        "Stellaron console state lost on reconnect/restart");
    require((Integer) field((DynamicMessage) field(props.get(300007), "prop"), "prop_state") == 0,
        "Stellaron interaction must not open an unrelated door");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    call("InteractPropCsReq", Map.of("prop_entity_id", field(props.get(300018), "entity_id"),
        "interact_id2", 1010, "interact_id", field(scene, "interact_id")), false);
  }

  private void characterChoice(String phase) throws Exception {
    boolean naming = phase.equals("doors-restart");
    boolean confirming = phase.equals("barriers-reconnect");
    byte[] capturedNickname = HexFormat.of().parseHex("6209e5858be58ab3e5beb7");
    if (naming) {
      call("FinishTalkMissionCsReq", Map.of("talk_str", "Mission_100010133"), true);
      objective(100010134, 1);
      var named = callRaw("SetPlayerInfoCsReq", capturedNickname, true);
      require(Boolean.FALSE.equals(field(named, "is_modify")), "Naming must not confirm a character");
    } else require("克劳德".equals(loginNickname), "Nickname was not saved across sessions/restart");
    if (naming || confirming) {
      objective(100010134, 1);
      require(Boolean.FALSE.equals(field(call("GetBasicInfoCsReq"), "is_gender_set")),
          "Nickname confirmation must not set gender or finish character selection");
    }
    callRaw("SetPlayerInfoCsReq", capturedNickname, true);
    if (naming) return;
    var gender = protocol.clientSchema().message("SetPlayerInfoCsReq")
        .findFieldByName("gender").getEnumType();
    var selected = call("SetPlayerInfoCsReq", Map.of("nickname", "克劳德", "is_modify", true,
        "gender", gender.findValueByNumber(2)), true);
    require(Boolean.TRUE.equals(field(selected, "is_modify")), "Explicit character confirmation lost");
    require(((Descriptors.EnumValueDescriptor) field(selected, "cur_avatar_path")).getNumber() == 8002,
        "Female path was not selected/restored");
    var basic = call("GetBasicInfoCsReq");
    require(Boolean.TRUE.equals(field(basic, "is_gender_set")), "Confirmed character was not saved");
    require((Integer) field(basic, "gender") == 2,
        "Gender was not selected/restored");
    objective(100010134, 2);
    objective(100010136, 1);
    call("SetPlayerInfoCsReq", Map.of("nickname", "conflicting", "is_modify", true,
        "gender", gender.findValueByNumber(1)), false);
    call("SetPlayerInfoCsReq", Map.of("nickname", "conflicting"), false);
    openingParty(false);
  }

  private void openingTransfer(boolean restored, boolean interrupted) throws Exception {
    if (!restored) {
      call("FinishTalkMissionCsReq", Map.of("talk_str", "Mission_100010136"), true);
      objective(100010136, 2);
      objective(100010120, 2);
      objective(100010121, 1);
      if (interrupted) {
        var source = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
        require((Integer) field(source, "floor_id") == 20001001,
            "Interrupted transfer must still be pending in the source room");
        require(enterSceneRequests == 0, "Interrupted run unexpectedly sent EnterScene");
        return;
      }
      call("EnterSceneCsReq", Map.of("entry_id", 200020102), false);
      expectedFloor = 20002001;
      call("EnterSceneCsReq", Map.of("entry_id", 2000201), true);
    }
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require((Integer) field(scene, "plane_id") == 20002
        && (Integer) field(scene, "floor_id") == 20002001
        && (Integer) field(scene, "entry_id") == 2000201, "Opening transfer location lost");
    objective(100010136, 2);
    objective(100010120, 2);
    objective(100010121, 2);
    objective(100020101, 1);
    var team = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var avatars = (List<?>) field(team, "avatar_list");
    require(avatars.size() == 1 && (Integer) field((DynamicMessage) avatars.getFirst(), "id") == 8001,
        "Transfer must keep the owned Trailblazer base ID");
    boolean selectedActor = false;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var entityRaw : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) entityRaw;
        if (!entity.hasField(entity.getDescriptorForType().findFieldByName("actor"))) continue;
        var actor = (DynamicMessage) field(entity, "actor");
        require((Integer) field(actor, "base_avatar_id") == 8001,
            "Transfer scene must retain the formal actor's base ID");
        selectedActor = true;
      }
    require(selectedActor, "Transferred scene must contain its selected actor");
    var owned = (List<?>) field(call("GetAvatarDataCsReq", Map.of("is_get_all", true), true), "avatar_list");
    require(owned.size() == 1
        && (Integer) field((DynamicMessage) owned.getFirst(), "base_avatar_id") == 8001
        && (Integer) field((DynamicMessage) owned.getFirst(), "cur_multi_path_avatar_type") == 8002,
        "Transfer must preserve the female path separately from the base avatar ID");
    require(Boolean.TRUE.equals(field(call("GetBasicInfoCsReq"), "is_gender_set")),
        "Transfer lost character creation");
    require("克劳德".equals(loginNickname), "Transfer lost selected nickname");
  }

  private void awakeningDoor(boolean restored) throws Exception {
    if (!restored) {
      var locked = groupEntities(3).stream().filter(e -> (Integer) field(e, "inst_id") == 300002)
          .findFirst().orElseThrow();
      require((Integer) field((DynamicMessage) field(locked, "prop"), "prop_state") == 2,
          "Awakening console must stay locked before the dialogues finish");
      // Real protocol receipts for the authored dialogues, not rendered-client acceptance.
      for (int id : new int[] {100020101, 100020102, 100020103})
        call("FinishTalkMissionCsReq", Map.of("sub_mission_id", id, "talk_str", "Mission_" + id), true);
      formalParty(List.of(8001, 1001), 0, Set.of(8001, 1001));
      objective(100020104, 1);
    }
    var props = new HashMap<Integer, DynamicMessage>();
    for (var entity : groupEntities(3)) props.put((Integer) field(entity, "inst_id"), entity);
    doorState(props, 300002, restored ? 1 : 0);
    doorState(props, 300001, restored ? 1 : 0);
    doorState(props, 300006, 2);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var request = Map.<String, Object>of("prop_entity_id", field(props.get(300002), "entity_id"),
        "interact_id2", 1010, "interact_id", field(scene, "interact_id"));
    if (!restored) {
      var opened = call("InteractPropCsReq", request, true);
      require((Integer) field(opened, "prop_state") == 1, "Awakening console did not open");
    }
    call("InteractPropCsReq", request, false);
    for (var entity : groupEntities(3)) props.put((Integer) field(entity, "inst_id"), entity);
    doorState(props, 300002, 1);
    doorState(props, 300001, 1);
    doorState(props, 300006, 2);
    objective(100020104, 2);
    objective(100020105, 1);
    require((Integer) field(scene, "plane_id") == 20002 && (Integer) field(scene, "floor_id") == 20002001,
        "Opening the door must not teleport or skip the next battle");
  }

  private DynamicMessage battleStatistics(DynamicMessage battle) {
    var stats = DynamicMessage.newBuilder(protocol.clientSchema().message("BattleStatistics"));
    stats.setField(stats.getDescriptorForType().findFieldByName("round_cnt"), 2);
    var rows = stats.getDescriptorForType().findFieldByName("battle_avatar_list");
    for (var value : (List<?>) field(battle, "battle_avatar_list")) {
      var avatar = (DynamicMessage) value;
      var row = DynamicMessage.newBuilder(rows.getMessageType());
      row.setField(row.getDescriptorForType().findFieldByName("id"), field(avatar, "id"));
      row.setField(row.getDescriptorForType().findFieldByName("avatar_type"), field(avatar, "avatar_type"));
      var statusField = row.getDescriptorForType().findFieldByName("avatar_status");
      var status = DynamicMessage.newBuilder(statusField.getMessageType());
      for (var entry : Map.of("max_hp", 1000.0, "left_hp", 750.0, "max_sp", 120.0, "left_sp", 30.0).entrySet())
        status.setField(status.getDescriptorForType().findFieldByName(entry.getKey()), entry.getValue());
      row.setField(statusField, status.build());
      stats.addRepeatedField(rows, row.build());
    }
    return stats.build();
  }

  private void awakeningBattle(int group, int instance, boolean win) throws Exception {
    var priorProgress = verifyAvatarProgression ? heroProgress() : null;
    var target = groupEntities(group).stream().filter(x -> (Integer) field(x, "inst_id") == instance).findFirst().orElseThrow();
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var sections = Map.<String,Object>of("section_id", 1, "interact_id", field(scene,"interact_id"));
    call("EnterSectionCsReq", sections, true);
    call("EnterSectionCsReq", sections, true);
    int leader = (Integer) field(scene,"leader_entity_id");
    var motionType = protocol.clientSchema().message("SceneEntityMoveCsReq").findFieldByName("entity_motion_list").getMessageType();
    var movement = DynamicMessage.newBuilder(motionType)
        .setField(motionType.findFieldByName("entity_id"),leader)
        .setField(motionType.findFieldByName("motion"),field(target,"motion")).build();
    call("SceneEntityMoveCsReq", Map.of("entity_motion_list",List.of(movement)),true);
    var tag = protocol.clientSchema().message("SceneCastSkillCsReq").findFieldByName("skill_extra_tags").getEnumType().findValueByNumber(1);
    var entered = call("SceneCastSkillCsReq", Map.of("cast_entity_id",ambushUseId++,
        "attacked_by_entity_id",field(target,"entity_id"),"assist_monster_entity_id_list",List.of(leader),
        "skill_extra_tags",List.of(tag),"target_motion",field(target,"motion")),true);
    var battle = (DynamicMessage)field(entered,"battle_info");
    var avatars = (List<?>)field(battle,"battle_avatar_list");
    require(avatars.stream().map(DynamicMessage.class::cast).anyMatch(a -> (Integer)field(a,"id") == 8002),
        "The selected female path must actually be in this battle");
    var stats = battleStatistics(battle);
    var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
    var report = new HashMap<String,Object>();
    report.put("battle_id",field(battle,"battle_id"));report.put("stage_id",field(battle,"stage_id"));
    report.put("end_status",end.getEnumType().findValueByName(win?"BATTLE_END_WIN":"BATTLE_END_LOSE"));
    report.put("stt",stats);
    var wrong = stats.toBuilder();
    var rows = stats.getDescriptorForType().findFieldByName("battle_avatar_list");
    var first = ((DynamicMessage)wrong.getRepeatedField(rows,0)).toBuilder();
    first.setField(first.getDescriptorForType().findFieldByName("id"),8001);
    wrong.setRepeatedField(rows,0,first.build());report.put("stt",wrong.build());
    call("PVEBattleResultCsReq",report,false);
    require(field((DynamicMessage)field(call("GetCurBattleInfoCsReq"),"battle_info"),"battle_id").equals(field(battle,"battle_id")),
        "Invalid report must not consume the valid current battle");
    report.put("stt",stats);
    call("PVEBattleResultCsReq",report,true);
    int earned = credits;
    call("PVEBattleResultCsReq",report,false);
    require(credits == earned,"Duplicate settlement granted credits twice");
    require((Integer)field((DynamicMessage)field(call("GetCurBattleInfoCsReq"),"battle_info"),"battle_id") == 0,
        "Settlement did not release the battle busy flag");
    if (verifyAvatarProgression) {
      var after = heroProgress();
      require((Integer) field(after, "level") == 1, "Opening monster XP should remain below the level-two threshold");
      require((Integer) field(after, "exp") == (Integer) field(priorProgress, "exp") + (win ? 36 : 0),
          "Win must grant the original 36 character XP once; loss must grant none");
    }
  }

  private DynamicMessage heroProgress() throws Exception {
    return ((List<?>) field(call("GetAvatarDataCsReq", Map.of("is_get_all", true), true), "avatar_list"))
        .stream().map(DynamicMessage.class::cast)
        .filter(a -> (Integer) field(a, "base_avatar_id") == 8001).findFirst().orElseThrow();
  }

  private int materialCount(int id) throws Exception {
    int count = 0;
    for (var value : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var item = (DynamicMessage) value;
      if ((Integer) field(item, "tid") == id) count += (Integer) field(item, "num");
    }
    return count;
  }

  private Map<String, Object> avatarProgression(boolean restored) throws Exception {
    var before = heroProgress();
    if (!restored) {
      require((Integer) field(before, "level") == 1 && (Integer) field(before, "exp") == 72,
          "Both ordinary awakening victories must persist their own XP");
      int books = materialCount(212), coins = credits, notices = avatarProgressNotifications;
      require(books >= 3, "The one opening chest must provide three adventure logs");
      var pile = DynamicMessage.newBuilder(protocol.clientSchema().message("PileItem"));
      pile.setField(pile.getDescriptorForType().findFieldByName("item_id"), 212);
      pile.setField(pile.getDescriptorForType().findFieldByName("item_num"), 3);
      var item = DynamicMessage.newBuilder(protocol.clientSchema().message("ItemCost"));
      item.setField(item.getDescriptorForType().findFieldByName("pile_item"), pile.build());
      var cost = DynamicMessage.newBuilder(protocol.clientSchema().message("ItemCostData"));
      cost.addRepeatedField(cost.getDescriptorForType().findFieldByName("item_list"), item.build());
      call("AvatarExpUpCsReq", Map.of("base_avatar_id", 8001, "item_cost", cost.build()), true);
      require(credits == coins - 1500 && materialCount(212) == books - 3,
          "Native upgrade must charge exactly three books and 1500 credits");
      require(avatarProgressNotifications > notices, "Native upgrade did not publish the new character XP");
      // Unaffordable material quantity must be acknowledged without a second debit.
      pile.setField(pile.getDescriptorForType().findFieldByName("item_num"), books + 1);
      item.setField(item.getDescriptorForType().findFieldByName("pile_item"), pile.build());
      cost.setRepeatedField(cost.getDescriptorForType().findFieldByName("item_list"), 0, item.build());
      call("AvatarExpUpCsReq", Map.of("base_avatar_id", 8001, "item_cost", cost.build()), false);
      require(credits == coins - 1500 && materialCount(212) == books - 3, "Rejected upgrade changed the inventory");
    }
    var after = heroProgress();
    require((Integer) field(after, "level") == 10 && (Integer) field(after, "exp") == 1512,
        "The character's 72 battle XP plus 15000 book XP must survive reconnect/restart");
    call("PlayerHeartBeatCsReq", Map.of("client_time_ms", 123456789L), true);
    return Map.of("level", field(after, "level"), "exp", field(after, "exp"),
        "books", materialCount(212), "notifications", avatarProgressNotifications);
  }

  private void awakeningDialogueChoices(boolean completed) throws Exception {
    int state = completed ? 2 : 1;
    objective(100020107, state);
    int before = credits;
    // Original Act100020107 options; repeated receipt must only echo the actual sentence.
    for (int sentence : new int[] {100020242, 100020242, 100020244, 100020246}) {
      var reply = call("SelectInclinationTextCsReq", Map.of("talk_sentence_id", sentence), true);
      require((Integer) field(reply, "talk_sentence_id") == sentence, "Dialogue sentence echo lost");
      inclinationAcknowledgements++;
      objective(100020107, state);
      require(credits == before, "Dialogue selection unexpectedly granted rewards");
    }
    var heartbeat = call("PlayerHeartBeatCsReq", Map.of("client_time_ms", 123456789L), true);
    require((Long) field(heartbeat, "client_time_ms") == 123456789L,
        "Dialogue acknowledgement did not preserve the connection");
  }

  private void awakeningBattleFlow(boolean restored) throws Exception {
    if (!restored) {
      awakeningBattle(16,200001,false);
      objective(100020105,1);
      awakeningBattle(16,200001,true);
      objective(100020105,2);objective(100020106,1);
      for (int id : new int[]{100020106,100020107}) {
        if (id == 100020107) awakeningDialogueChoices(false);
        call("FinishTalkMissionCsReq",Map.of("sub_mission_id",id,"talk_str","Mission_"+id),true);
      }
      objective(100020131,1);
      awakeningBattle(5,200002,true);
    }
    if (restored) awakeningDialogueChoices(true);
    for (int id : new int[]{100020104,100020105,100020106,100020107,100020131}) objective(id,2);
    objective(100020109, verifyStoryParty && restored ? 2 : 1);
    var team = (DynamicMessage)field(call("GetCurLineupDataCsReq"),"lineup");
    var hero = ((List<?>)field(team,"avatar_list")).stream().map(DynamicMessage.class::cast)
        .filter(a -> (Integer)field(a,"id") == 8001).findFirst().orElseThrow();
    require((Integer)field(hero,"hp") == (restored && verifySpaceAnchors ? 10000 : 7500),
        "Hero HP lost during settlement/reconnect or anchor healing");
    require((Integer)field((DynamicMessage)field(hero,"sp_bar"),"cur_sp") == 2500,"Hero energy lost during settlement/reconnect");
    require((Integer)field((DynamicMessage)field(call("GetCurBattleInfoCsReq"),"battle_info"),"battle_id") == 0,
        "Saved victory resurrected a busy battle");
  }

  private Map<String, Object> spaceAnchor(boolean restored) throws Exception {
    var unlocked = (List<?>) field(call("GetUnlockTeleportCsReq", Map.of("entry_id_list", List.of(2000201)), true),
        "unlocked_teleport_list");
    require(unlocked.contains(2000204) == restored, "Reported anchor must be discovered, then survive restart");
    if (!restored) {
      call("EnterSceneCsReq", Map.of("entry_id", 2000201, "teleport_id", 2000204), false);
      var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
      var entity = groupEntities(1).stream().filter(e -> (Integer) field(e, "inst_id") == 300007).findFirst().orElseThrow();
      var type = protocol.clientSchema().message("SceneEntityMoveCsReq").findFieldByName("entity_motion_list").getMessageType();
      var motion = DynamicMessage.newBuilder(type).setField(type.findFieldByName("entity_id"), field(scene, "leader_entity_id"))
          .setField(type.findFieldByName("motion"), field(entity, "motion")).build();
      call("SceneEntityMoveCsReq", Map.of("entity_motion_list", List.of(motion)), true);
      call("SceneEntityMoveCsReq", Map.of("entity_motion_list", List.of(motion)), true);
    }
    unlocked = (List<?>) field(call("GetUnlockTeleportCsReq", Map.of("entry_id_list", List.of(2000201)), true),
        "unlocked_teleport_list");
    require(unlocked.contains(2000204) && !unlocked.contains(2000202), "Discovery unlocked the wrong waypoint");
    var identifier = DynamicMessage.newBuilder(protocol.clientSchema().message("SceneIdentifier"));
    identifier.setField(identifier.getDescriptorForType().findFieldByName("floor_id"), 20002001);
    var maps = (List<?>) field(call("GetSceneMapInfoCsReq", Map.of("scene_identifiers", List.of(identifier.build())), true), "scene_map_info");
    var map = maps.stream().map(DynamicMessage.class::cast).filter(m -> (Integer) field(m, "entry_id") == 2000201).findFirst().orElseThrow();
    require(((List<?>) field(map, "unlock_teleport_list")).contains(2000204), "Map query lost activated waypoint");
    var row = ((List<?>) field(map, "maze_prop_list")).stream().map(DynamicMessage.class::cast)
        .filter(p -> (Integer) field(p, "group_id") == 1 && (Integer) field(p, "config_id") == 300007).findFirst().orElseThrow();
    require((Integer) field(row, "state") == 8, "Map prop and teleport list disagree");
    call("EnterSceneCsReq", Map.of("entry_id", 2000201, "teleport_id", 2000204), true);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    DynamicMessage leader = null;
    for (var value : (List<?>) field(scene, "entity_group_list"))
      for (var e : (List<?>) field((DynamicMessage) value, "entity_list"))
        if (field((DynamicMessage) e, "entity_id").equals(field(scene, "leader_entity_id"))) leader = (DynamicMessage) e;
    require(leader != null, "Teleport must retain the selected protagonist");
    var position = (DynamicMessage) field((DynamicMessage) field(leader, "motion"), "pos");
    require(Math.abs((Integer) field(position, "x") + 69860) <= 1
        && Math.abs((Integer) field(position, "y") + 3195) <= 1
        && Math.abs((Integer) field(position, "z") + 62700) <= 1, "Teleport used a different authored arrival anchor");
    var entity = groupEntities(1).stream().filter(e -> (Integer) field(e, "inst_id") == 300007).findFirst().orElseThrow();
    require((Integer) field((DynamicMessage) field(entity, "prop"), "prop_state") == 8, "Scene reload lost activated waypoint");
    return Map.of("mapping", 2000204, "activationPersisted", true, "mapConsistent", true,
        "teleportVerified", true, "restored", restored);
  }

  private void formalParty(List<Integer> expected, int leader, Set<Integer> owned) throws Exception {
    var current = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var roster = ((List<?>) field(current, "avatar_list")).stream().map(DynamicMessage.class::cast)
        .map(a -> (Integer) field(a, "id")).toList();
    require(roster.equals(expected) && (Integer) field(current, "leader_slot") == leader,
        "Formal story roster or leader differs: " + roster);
    var all = ((List<?>) field(call("GetAllLineupDataCsReq"), "lineup_list")).stream()
        .map(DynamicMessage.class::cast).filter(t -> (Integer) field(t, "index") == 0).findFirst().orElseThrow();
    require(all.equals(current), "Current and all-lineup queries disagree");
    var avatars = ((List<?>) field(call("GetAvatarDataCsReq", Map.of("is_get_all", true), true), "avatar_list"))
        .stream().map(DynamicMessage.class::cast).map(a -> (Integer) field(a, "base_avatar_id")).toList();
    require(new HashSet<>(avatars).equals(owned) && avatars.size() == owned.size(), "Formal grants missing or duplicated");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var actors = new HashSet<Integer>();
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var entityRaw : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) entityRaw;
        if (entity.hasField(entity.getDescriptorForType().findFieldByName("actor")))
          actors.add((Integer) field((DynamicMessage) field(entity, "actor"), "base_avatar_id"));
      }
    require(actors.equals(new HashSet<>(expected)), "Scene actors disagree with current party: " + actors);
  }

  private Map<String, Object> storyParty(boolean restored) throws Exception {
    if (!restored) {
      formalParty(List.of(8001, 1001), 0, Set.of(8001, 1001));
      call("FinishTalkMissionCsReq", Map.of("sub_mission_id", 100020109, "talk_str", "Mission_100020109"), true);
      formalParty(List.of(8001, 1001, 1002), 0, Set.of(8001, 1001, 1002));
      objective(100020133, 1);
      call("FinishTalkMissionCsReq", Map.of("sub_mission_id", 100020133, "talk_str", "Mission_100020133"), true);
      formalParty(List.of(8001, 1001, 1002), 1, Set.of(8001, 1001, 1002));
      int books = materialCount(211);
      call("FinishTalkMissionCsReq", Map.of("sub_mission_id", 100020133, "talk_str", "Mission_100020133"), false);
      call("FinishTalkMissionCsReq", Map.of("sub_mission_id", 100020109, "talk_str", "Mission_100020109"), false);
      require(materialCount(211) == books && books == 2, "Repeated completion duplicated mission rewards");
      var field = protocol.clientSchema().message("ReplaceLineupCsReq").findFieldByName("lineup_slot_list");
      var rows = new ArrayList<DynamicMessage>();
      int slot = 0;
      for (int id : List.of(1002, 8001)) {
        var row = DynamicMessage.newBuilder(field.getMessageType());
        row.setField(row.getDescriptorForType().findFieldByName("id"), id);
        row.setField(row.getDescriptorForType().findFieldByName("slot"), slot++);
        var type = row.getDescriptorForType().findFieldByName("avatar_type");
        row.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
        rows.add(row.build());
      }
      call("ReplaceLineupCsReq", Map.of("index", 0, "plane_id", 20002, "lineup_slot_list", rows, "leader_slot", 0), true);
    }
    objective(100020109, 2); objective(100020133, 2);
    formalParty(List.of(1002, 8001), 0, Set.of(8001, 1001, 1002));
    require(materialCount(211) == 2, "Restored story reward changed");
    return Map.of("owned", List.of(1001, 1002, 8001), "roster", List.of(1002, 8001), "leader", 0,
        "marchJoined", true, "danHengJoined", true, "authoredLineupCompleted", true, "restored", restored);
  }

  private Map<String, Object> keyConsole(boolean restored) throws Exception {
    var props = new HashMap<Integer, DynamicMessage>();
    for (var entity : groupEntities(3)) props.put((Integer) field(entity, "inst_id"), entity);
    if (!restored) {
      doorState(props, 300006, 2);
      doorState(props, 300004, 0);
      var lockedScene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
      call("InteractPropCsReq", Map.of("prop_entity_id", field(props.get(300006), "entity_id"),
          "interact_id2", 1010, "interact_id", field(lockedScene, "interact_id")), false);
      objective(100020110, 1);
      call("FinishTalkMissionCsReq", Map.of("sub_mission_id", 100020110, "talk_str", "Mission_100020110"), true);
      objective(100020132, 1);
      awakeningBattle(5, 200004, true);
      objective(100020132, 2);
      objective(100020111, 1);
      call("FinishTalkMissionCsReq", Map.of("sub_mission_id", 100020111, "talk_str", "Mission_100020111"), true);
      objective(100020112, 1);
      for (var entity : groupEntities(3)) props.put((Integer) field(entity, "inst_id"), entity);
      doorState(props, 300006, 0);
      doorState(props, 300004, 0);
      var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
      var opened = call("InteractPropCsReq", Map.of("prop_entity_id", field(props.get(300006), "entity_id"),
          "interact_id2", 1010, "interact_id", field(scene, "interact_id")), true);
      require((Integer) field(opened, "prop_state") == 1, "Accepted key console remained locked");
    }
    for (var entity : groupEntities(3)) props.put((Integer) field(entity, "inst_id"), entity);
    doorState(props, 300006, 1);
    doorState(props, 300004, 1);
    doorState(props, 300010, 2);
    objective(100020112, 2);
    objective(100020113, 1);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    call("InteractPropCsReq", Map.of("prop_entity_id", field(props.get(300006), "entity_id"),
        "interact_id2", 1010, "interact_id", field(scene, "interact_id")), false);
    return Map.of("doorOpen", true, "nextPerformancePending", true, "restored", restored,
        "console", 300006, "door", 300004);
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    String phase = control.get("phase").getAsString();
    verifyAvatarProgression = control.has("avatarProgression") && control.get("avatarProgression").getAsBoolean();
    verifySpaceAnchors = control.has("spaceAnchors") && control.get("spaceAnchors").getAsBoolean();
    verifyStoryParty = control.has("storyParty") && control.get("storyParty").getAsBoolean();
    boolean verifyKeyConsole = control.has("keyConsole") && control.get("keyConsole").getAsBoolean();
    if (phase.startsWith("transfer-") || phase.startsWith("awakening-") || phase.startsWith("key-console-")) expectedFloor = 20002001;
    int uid =
        (Integer)
            field(
                call(
                    "PlayerGetTokenCsReq",
                    Map.of(
                        "account_uid",
                        control.get("accountUid").getAsString(),
                        "token",
                        control.get("comboToken").getAsString(),
                        "platform",
                        1),
                    true),
                "uid");
    var loginBasic = (DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info");
    credits = (Integer) field(loginBasic, "scoin");
    loginNickname = (String) field(loginBasic, "nickname");
    call("PlayerLoginFinishCsReq");
    if (phase.equals("key-console-reconnect")) {
      require(verifyKeyConsole, "Unexpected key console phase");
      var key = keyConsole(true);
      return Map.of("passed", true, "uid", uid, "phase", phase, "requests", requests,
          "keyConsole", key, "clientGameplayVerified", false);
    }
    boolean verifyTransfer = control.has("openingTransfer") && control.get("openingTransfer").getAsBoolean();
    boolean interruptedTransfer = control.has("interruptedTransfer") && control.get("interruptedTransfer").getAsBoolean();
    boolean verifyAwakening = control.has("awakeningDoor") && control.get("awakeningDoor").getAsBoolean();
    boolean verifyAwakeningBattle = control.has("awakeningBattle") && control.get("awakeningBattle").getAsBoolean();
    if (phase.startsWith("awakening-")) {
      require(verifyAwakening, "Unexpected awakening phase");
      if (!verifyAwakeningBattle || phase.equals("awakening-reconnect")) awakeningDoor(true);
      if (verifyAwakeningBattle) {
        awakeningBattleFlow(phase.equals("awakening-restart"));
        capturedDestructible(12, Map.of(300004, 100026, 300012, 100027), 4,
            phase.equals("awakening-restart"));
      }
      var avatarProgress = verifyAvatarProgression ? avatarProgression(phase.equals("awakening-restart")) : Map.of();
      var anchor = verifySpaceAnchors ? spaceAnchor(phase.equals("awakening-restart")) : Map.of();
      var party = verifyStoryParty ? storyParty(phase.equals("awakening-restart")) : Map.of();
      var key = verifyKeyConsole ? keyConsole(phase.equals("awakening-restart")) : Map.of();
      return Map.ofEntries(Map.entry("passed", true), Map.entry("uid", uid),
          Map.entry("phase", phase), Map.entry("requests", requests),
          Map.entry("awakeningDoorVerified", true), Map.entry("nextBattlePending", !verifyAwakeningBattle),
          Map.entry("awakeningBattleVerified", verifyAwakeningBattle), Map.entry("credits", credits),
          Map.entry("clientGameplayVerified", false), Map.entry("inclinationAcknowledgements", inclinationAcknowledgements),
          Map.entry("stationDestructiblesVerified", verifyAwakeningBattle),
          Map.entry("avatarProgression", avatarProgress), Map.entry("spaceAnchor", anchor), Map.entry("storyParty", party),
          Map.entry("keyConsole", key));
    }
    if (phase.startsWith("transfer-")) {
      require(verifyTransfer, "Unexpected transfer phase");
      openingTransfer(true, false);
      require(enterSceneRequests == 0, "Recovery must use only the persisted task, not a new EnterScene request");
      boolean opened = verifyAwakening && phase.equals("transfer-restart");
      if (opened) awakeningDoor(false);
      return Map.of("passed", true, "uid", uid, "phase", phase, "requests", requests,
          "openingTransferVerified", true, "enterSceneRequests", enterSceneRequests,
          "awakeningDoorVerified", opened,
          "interruptedTransferRecovery", interruptedTransfer, "clientGameplayVerified", false);
    }
    boolean restoredBarriers = phase.startsWith("barriers-");
    boolean verifyStellaron = control.has("stellaronConsole") && control.get("stellaronConsole").getAsBoolean();
    boolean verifyCharacter = control.has("characterChoice") && control.get("characterChoice").getAsBoolean();
    ambush = control.has("ambush") && control.get("ambush").getAsBoolean();
    openingParty(restoredBarriers && !verifyStellaron);
    boolean verifyDoors = control.has("doors") && control.get("doors").getAsBoolean();
    openingScene(
        !verifyDoors || phase.equals("first") || phase.equals("reconnect"), restoredBarriers && !verifyStellaron);
    boolean first = phase.equals("first");
    if (first) {
      tutorialProgress(false);
      var missions = (List<?>) field(call("GetMissionDataCsReq"), "mission_list");
      var intro =
          missions.stream()
              .map(row -> (DynamicMessage) row)
              .filter(row -> (Integer) field(row, "id") == 100010102)
              .findFirst()
              .orElseThrow();
      require(
          (Integer) field(intro, "progress") == 0
              && ((Descriptors.EnumValueDescriptor) field(intro, "status")).getNumber() == 1,
          "Opening intro must wait for the client performance");
      for (int id : new int[] {100010102, 100010100, 100010104})
        call(
            "FinishTalkMissionCsReq",
            Map.of("sub_mission_id", id, "talk_str", "Mission_" + id),
            true);
      progress(false);
      var entered = call("SceneEnterStageCsReq", Map.of("event_id", 20001001), true);
      var battle = (DynamicMessage) field(entered, "battle_info");
      require((Integer) field(battle, "stage_id") == 200010010, "Original world-zero stage");
      var combatants = (List<?>) field(battle, "battle_avatar_list");
      require(combatants.size() == 1, "Opening battle must use Kafka only");
      var kafka = (DynamicMessage) combatants.getFirst();
      require(
          (Integer) field(kafka, "id") == 1001005 && (Integer) field(kafka, "level") == 10,
          "Opening battle must use the original level-10 Kafka trial");
      require(
          !((List<?>) field(battle, "monster_wave_list")).isEmpty(), "Original monsters are empty");
      var end =
          protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
      var report =
          Map.<String, Object>of(
              "battle_id",
              field(battle, "battle_id"),
              "stage_id",
              field(battle, "stage_id"),
              "end_status",
              end.getEnumType().findValueByName("BATTLE_END_WIN"));
      call("PVEBattleResultCsReq", report, true);
      progress(true);
      call("PVEBattleResultCsReq", report, false);
      call("UnlockTutorialCsReq", Map.of("HPIMDINCCNN", 1001), true);
      call("FinishTutorialCsReq", Map.of("tutorial_id", 1001), true);
    }
    progress(true);
    tutorialProgress(true);
    capturedDestructible(!first);
    recoveryProps();
    openingChest(!first);
    if (verifyDoors) storyDoors(phase);
    boolean verifyReadings = control.has("readings") && control.get("readings").getAsBoolean();
    if (verifyReadings) deskReadings(!first);
    boolean verifyBarriers =
        control.has("barriers")
            && control.get("barriers").getAsBoolean()
            && (phase.equals("doors-restart") || restoredBarriers);
    boolean verifyScriptedUnlock = verifyBarriers && control.has("scriptedUnlock")
        && control.get("scriptedUnlock").getAsBoolean();
    if (verifyBarriers) corridorBarriers(restoredBarriers, verifyScriptedUnlock, restoredBarriers && verifyStellaron);
    if (verifyScriptedUnlock) scriptedDoorUnlock(restoredBarriers, restoredBarriers && verifyStellaron);
    if (verifyScriptedUnlock && verifyStellaron) {
      stellaronConsole(restoredBarriers, verifyCharacter);
      if (verifyCharacter) characterChoice(phase);
    }
    if (verifyTransfer && phase.equals("barriers-restart")) openingTransfer(false, interruptedTransfer);
    var result =
        new LinkedHashMap<String, Object>(
            Map.of(
                "passed",
                true,
                "uid",
                uid,
                "phase",
                control.get("phase").getAsString(),
                "requests",
                requests,
                "openingBattleFinished",
                true,
                "nextObjectiveAccepted",
                true,
                "openingKafkaVerified",
                true,
                "tutorialProgressVerified",
                true,
                "openingSceneGatesVerified",
                true,
                "clientGameplayVerified",
                false));
    result.put("recoveryPropsVerified", true);
    result.put("capturedPropVerified", true);
    result.put("ordinaryBoxRewardsVerified", true);
    result.put("openingChestVerified", true);
    result.put("storyDoorsVerified", verifyDoors);
    result.put("deskReadingsVerified", verifyReadings);
    result.put("storyBarriersVerified", verifyBarriers);
    result.put("monsterAmbushVerified", ambush && verifyBarriers && !restoredBarriers);
    result.put("sceneActionAdmissionVerified", ambush && verifyBarriers && !restoredBarriers);
    result.put("scriptedDoorUnlockVerified", verifyScriptedUnlock);
    result.put("stellaronConsoleVerified", verifyScriptedUnlock && verifyStellaron);
    result.put("characterChoiceVerified", verifyScriptedUnlock && verifyStellaron && verifyCharacter);
    result.put("openingTransferVerified", verifyTransfer && phase.equals("barriers-restart") && !interruptedTransfer);
    result.put("openingTransferPending", verifyTransfer && phase.equals("barriers-restart") && interruptedTransfer);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeOpeningStageWinProbe();
    var client = new KcpClient();
    int exit = 1;
    try {
      var control =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(40000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp connection) {
              probe.connected.complete(connection);
            }

            public void handleReceive(ByteBuf bytes, Ukcp connection) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated opening frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp connection) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp connection) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("Opening probe disconnected"));
            }
          });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(15, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(control)));
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      client.stop();
    }
    System.exit(exit);
  }
}
