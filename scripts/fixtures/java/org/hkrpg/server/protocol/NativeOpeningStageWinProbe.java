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
  private int credits;
  private int rewardHints;
  private boolean ambush;
  private int ambushUseId = 395;

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
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], request.build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    requests++;
    var response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("KCP failure", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("SyncLineupNotify"))
        lineupUpdates.add(protocol.clientSchema().parse("SyncLineupNotify", frame.body()));
      if (frame.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
        var update = protocol.clientSchema().parse("SceneGroupRefreshScNotify", frame.body());
        require((Integer) field(update, "floor_id") == 20001001, "Group update floor");
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
      }
      if (frame.command() != protocol.clientCommand(response)) continue;
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
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    int caster = 0;
    var boxes = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      for (var rawEntity : (List<?>) field(group, "entity_list")) {
        var entity = (DynamicMessage) rawEntity;
        if (entity.hasField(entity.getDescriptorForType().findFieldByName("actor")))
          caster = (Integer) field(entity, "entity_id");
        if ((Integer) field(group, "group_id") == 36
            && Set.of(300003, 300006).contains((Integer) field(entity, "inst_id")))
          boxes.put((Integer) field(entity, "inst_id"), entity);
      }
    }
    require(
        caster > 0 && boxes.size() == (alreadyCollected ? 0 : 2),
        "Paid boxes respawned or original source is absent");
    if (!alreadyCollected) {
      var tags =
          protocol
              .clientSchema()
              .message("SceneCastSkillCsReq")
              .findFieldByName("skill_extra_tags")
              .getEnumType();
      int useId = 138;
      for (int instance : List.of(300003, 300006)) {
        var entity = boxes.get(instance);
        require(
            (Integer) field((DynamicMessage) field(entity, "prop"), "prop_id")
                == (instance == 300003 ? 100026 : 100027),
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
    require(Objects.equals(bag.get(180005), 2), "Box metal missing, duplicated, or not restored");
    var after = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    for (var raw : (List<?>) field(after, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      if ((Integer) field(group, "group_id") != 36) continue;
      for (var value : (List<?>) field(group, "entity_list"))
        require(
            !Set.of(300003, 300006).contains((Integer) field((DynamicMessage) value, "inst_id")),
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
      attack.put("hit_target_entity_id_list", List.of(caster));
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

  private void corridorBarriers(boolean restored) throws Exception {
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
    objective(100010113, 1);
    require(
        groupEntities(17).isEmpty() && groupEntities(33).isEmpty(),
        "Completed story blockers respawned");
    openingParty(true);
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
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
    credits =
        (Integer) field((DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info"), "scoin");
    call("PlayerLoginFinishCsReq");
    String phase = control.get("phase").getAsString();
    boolean restoredBarriers = phase.startsWith("barriers-");
    ambush = control.has("ambush") && control.get("ambush").getAsBoolean();
    openingParty(restoredBarriers);
    boolean verifyDoors = control.has("doors") && control.get("doors").getAsBoolean();
    openingScene(
        !verifyDoors || phase.equals("first") || phase.equals("reconnect"), restoredBarriers);
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
    if (verifyBarriers) corridorBarriers(restoredBarriers);
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
