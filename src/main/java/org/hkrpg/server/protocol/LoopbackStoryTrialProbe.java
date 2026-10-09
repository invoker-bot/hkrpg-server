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
import kcp.highway.*;

/** Actual 2022304 actors and replacement task; preceding mission gates are isolated fixtures. */
public final class LoopbackStoryTrialProbe {
  private static final int STORY = 2022304, OLD = 1031224, FUTURE = 1041209;
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume, initialPartyNotified, replacementNotified, storyChanged, leaderChecks;
  private boolean casterChecks, castAfterResume;
  private boolean abilityNameChecks;
  private String sourceAttackName;
  private int requestedCaster, expectedMp, techniquePointNotifications;
  private int step;

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }

  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(message.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder message, int next) {
    step = next;
    System.err.println("Story request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], message.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c, String name, int next) {
    send(c, name, request(name), next);
  }

  private void accept(Ukcp c, int main, int next) {
    var message = request("AcceptMainMissionCsReq");
    set(message, "main_mission_id", main);
    send(c, "AcceptMainMissionCsReq", message, next);
  }

  private void talk(Ukcp c, int next) {
    var message = request("FinishTalkMissionCsReq");
    set(message, "sub_mission_id", 202230316);
    set(message, "talk_str", "Mission_202230316");
    send(c, "FinishTalkMissionCsReq", message, next);
  }

  private void replace(Ukcp c, int special, int next) {
    var message = request("ReplaceLineupCsReq");
    set(message, "game_story_line_id", STORY);
    set(message, "plane_id", 20242);
    var slots = message.getDescriptorForType().findFieldByName("lineup_slot_list");
    var slot = DynamicMessage.newBuilder(slots.getMessageType());
    set(slot, "id", special);
    var type = slot.getDescriptorForType().findFieldByName("avatar_type");
    slot.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
    message.addRepeatedField(slots, slot.build());
    send(c, "ReplaceLineupCsReq", message, next);
  }

  private void mission(DynamicMessage message, int progress) {
    require(number(message, "retcode") == 0, "Mission query rejected");
    for (int id : List.of(202230316, 202230507, 202230501)) {
      var rows =
          ((List<?>) field(message, "mission_list"))
              .stream()
                  .map(row -> (DynamicMessage) row)
                  .filter(row -> number(row, "id") == id)
                  .toList();
      require(rows.size() == 1, "Missing actual goal " + id);
      require(
          number(rows.getFirst(), "progress") == (id == 202230501 ? 0 : progress),
          "Unexpected progress " + id);
    }
    result.put("replacementProgress", progress);
  }

  private void story(DynamicMessage message, boolean active) {
    require(number(message, "retcode") == 0, "Story query rejected");
    require(number(message, "cur_story_line_id") == (active ? STORY : 0), "Wrong story identity");
    require(
        new HashSet<>((List<?>) field(message, "trial_avatar_id_list"))
            .equals(active ? Set.of(OLD, FUTURE) : Set.of()),
        "Wrong available actors");
    if (active) result.put("nativeAvailableActors", true);
  }

  private void party(DynamicMessage lineup, int special) {
    require(number(lineup, "game_story_line_id") == STORY, "Missing story party identity");
    require(
        ((Descriptors.EnumValueDescriptor) field(lineup, "extra_lineup_type")).getNumber() == 0,
        "Story sent as unsupported extra party");
    require(number(lineup, "leader_slot") == 0, "Wrong leader");
    var rows = (List<?>) field(lineup, "avatar_list");
    require(rows.size() == 1, "Wrong story roster size");
    var actor = (DynamicMessage) rows.getFirst();
    require(number(actor, "id") == special && number(actor, "slot") == 0, "Wrong special avatar");
    require(
        "AvatarType_AvatarTrialType"
            .equals(((Descriptors.EnumValueDescriptor) field(actor, "avatar_type")).getName()),
        "Wrong trial actor type");
    require(
        field(lineup, "story_line_avatar_id_list").equals(List.of(special)),
        "Wrong native story roster");
    require(
        new HashSet<>((List<?>) field(lineup, "available_trial_avatar_i_ds"))
            .equals(Set.of(OLD, FUTURE)),
        "Party omitted available trial IDs");
  }

  private void scene(DynamicMessage message, int actorId) {
    require(number(message, "retcode") == 0, "Scene query rejected");
    var scene = (DynamicMessage) field(message, "scene");
    require(
        number(scene, "game_story_line_id") == STORY
            && number(scene, "plane_id") == 20242
            && number(scene, "floor_id") == 20242001
            && number(scene, "entry_id") == 2024201
            && number(scene, "dimension_id") == 2,
        "Wrong actual story location");
    int leader = number(scene, "leader_entity_id");
    var actors = new ArrayList<DynamicMessage>();
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) value;
        if (entity.hasField(entity.getDescriptorForType().findFieldByName("actor")))
          actors.add(entity);
      }
    require(
        actors.size() == 1 && number(actors.getFirst(), "entity_id") == leader,
        "Old scene actor cache was not replaced");
    require(
        number((DynamicMessage) field(actors.getFirst(), "actor"), "base_avatar_id") == actorId,
        "Scene actor does not use the actual actor config");
    require(
        "AvatarType_AvatarTrialType"
            .equals(
                ((Descriptors.EnumValueDescriptor)
                        field((DynamicMessage) field(actors.getFirst(), "actor"), "avatar_type"))
                    .getName()),
        "Scene actor used a different avatar type");
    result.put("nativeSceneActor", true);
    result.put("nativeDimension", true);
  }

  private void formal(DynamicMessage message) {
    require(number(message, "retcode") == 0, "Formal avatar query rejected");
    var ids =
        ((List<?>) field(message, "avatar_list"))
            .stream()
                .map(row -> (DynamicMessage) row)
                .map(row -> number(row, "base_avatar_id"))
                .toList();
    require(ids.equals(List.of(8001)), "Story actors entered formal storage");
    result.put("formalStorageUnchanged", true);
  }

  private void selectLeader(Ukcp c, long slot, int next, boolean duplicate) throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    out.writeUInt64(8, slot);
    if (duplicate) out.writeUInt32(8, 1);
    out.flush();
    step = next;
    var packet = PacketCodec.encode(708, new byte[0], buffer.toByteArray());
    var body = Unpooled.wrappedBuffer(packet);
    try {
      c.write(body);
    } finally {
      body.release();
    }
  }

  private void twoTrialParty(DynamicMessage lineup) {
    require(number(lineup, "game_story_line_id") == STORY, "Wrong saved two-trial party identity");
    require(
        ((Descriptors.EnumValueDescriptor) field(lineup, "extra_lineup_type")).getNumber() == 0,
        "Two-trial story sent as an unrelated extra party");
    require(number(lineup, "leader_slot") == 1, "Story leader did not persist");
    require(
        field(lineup, "story_line_avatar_id_list").equals(List.of(OLD, FUTURE)),
        "Wrong saved two-trial roster");
    var rows = (List<?>) field(lineup, "avatar_list");
    require(rows.size() == 2, "Missing two-trial party actors");
    for (int index = 0; index < 2; index++) {
      var row = (DynamicMessage) rows.get(index);
      require(
          number(row, "slot") == index && number(row, "id") == (index == 0 ? OLD : FUTURE),
          "Wrong two-trial party slot");
      require(
          "AvatarType_AvatarTrialType"
              .equals(((Descriptors.EnumValueDescriptor) field(row, "avatar_type")).getName()),
          "Wrong two-trial avatar type");
    }
    require(
        new HashSet<>((List<?>) field(lineup, "available_trial_avatar_i_ds"))
            .equals(Set.of(OLD, FUTURE)),
        "Leader edit changed trial availability");
    result.put("nativeLeaderSlot", 1);
    result.put("nativeLeaderRoster", List.of(OLD, FUTURE));
  }

  private void castNonLeader(Ukcp c, int index, int next) {
    castNonLeader(c, index, next, "");
  }

  private void castNonLeader(Ukcp c, int index, int next, String abilityName) {
    var cast = request("SceneCastSkillCsReq");
    set(cast, "cast_entity_id", requestedCaster);
    set(cast, "attacked_by_entity_id", requestedCaster);
    set(cast, "skill_index", index);
    if (!abilityName.isEmpty()) set(cast, "maze_ability_str", abilityName);
    send(c, "SceneCastSkillCsReq", cast, next);
  }

  private void twoTrialScene(DynamicMessage message) {
    require(number(message, "retcode") == 0, "Two-trial scene query rejected");
    var scene = (DynamicMessage) field(message, "scene");
    require(
        number(scene, "game_story_line_id") == STORY
            && number(scene, "dimension_id") == 2
            && number(scene, "plane_id") == 20242
            && number(scene, "floor_id") == 20242001
            && number(scene, "entry_id") == 2024201,
        "Two-trial leader changed story location");
    int leader = number(scene, "leader_entity_id"), count = 0;
    boolean found = false;
    for (var group : (List<?>) field(scene, "entity_group_list")) {
      for (var value : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) value;
        if (!entity.hasField(entity.getDescriptorForType().findFieldByName("actor"))) continue;
        count++;
        if (number((DynamicMessage) field(entity, "actor"), "base_avatar_id") == 1224)
          requestedCaster = number(entity, "entity_id");
        if (number(entity, "entity_id") == leader)
          found = number((DynamicMessage) field(entity, "actor"), "base_avatar_id") == 1209;
      }
    }
    require(count == 2 && found, "Scene leader does not match the selected actual actor");
    if (casterChecks)
      require(requestedCaster > 0 && requestedCaster != leader, "Requested caster is the leader");
    result.put("nativeLeaderSceneActor", 1209);
    result.put("nativeSceneActor", true);
    result.put("nativeDimension", true);
  }

  private void prepareTwoTrialParty(Ukcp c) {
    var replace = request("ReplaceLineupCsReq");
    set(replace, "game_story_line_id", STORY);
    set(replace, "plane_id", 20242);
    var field = replace.getDescriptorForType().findFieldByName("lineup_slot_list");
    for (int index = 0; index < 2; index++) {
      var row = DynamicMessage.newBuilder(field.getMessageType());
      set(row, "id", index == 0 ? OLD : FUTURE);
      set(row, "slot", index);
      var type = row.getDescriptorForType().findFieldByName("avatar_type");
      set(row, "avatar_type", type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
      replace.addRepeatedField(field, row.build());
    }
    send(c, "ReplaceLineupCsReq", replace, 30);
  }

  private void done() {
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated frame");
      if (frame.cmdId() == 719) {
        // CN replacement response fields 7/9 were verified independently of the frozen name.
        var input = CodedInputStream.newInstance(frame.body());
        int code = 0;
        while (!input.isAtEnd()) {
          int tag = input.readTag();
          require(tag == 56 || tag == 72, "Unknown replacement response field");
          int value = input.readUInt32();
          if (tag == 72) code = value;
          else require(value == 0, "Wrong replacement index");
        }
        if (leaderChecks && step == 30) {
          require(code == 0, "Two available story actors cannot form a party");
          selectLeader(c, 1, 31, false);
        } else if (step == 7) {
          require(code != 0, "Future actor selected before task");
          result.put("earlyTrialReplacementRejected", true);
          talk(c, 8);
        } else {
          require(step == 16 && code == 0, "Previously available actor cannot return");
          result.put("oldActorRemainsAvailable", true);
          query(c, "GetCurLineupDataCsReq", 17);
        }
        continue;
      }
      if (leaderChecks && frame.cmdId() == 768) {
        var reply = protocol.clientSchema().parse("ChangeLineupLeaderScRsp", frame.body());
        int code = number(reply, "retcode");
        require(reply.getUnknownFields().asMap().isEmpty(), "Unknown leader response field");
        if (step == 31 || step == 33) {
          require(code == 0 && number(reply, "slot") == 1, "Valid leader change/retry rejected");
          if (step == 31) {
            result.put("nativeLeaderChangeSucceeded", true);
            selectLeader(c, 4, 32, false);
          } else {
            result.put("nativeLeaderRetrySucceeded", true);
            selectLeader(c, 0x100000000L, 34, false);
          }
        } else {
          require(
              code == emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(),
              "Invalid leader request acknowledged success");
          if (step == 32) {
            result.put("nativeInvalidLeaderSlotRejected", true);
            selectLeader(c, 1, 33, false);
          } else if (step == 34) {
            result.put("nativeWideLeaderSlotRejected", true);
            selectLeader(c, 0, 35, true);
          } else {
            require(step == 35, "Unexpected leader response");
            result.put("nativeDuplicateLeaderSlotRejected", true);
            query(c, "GetCurLineupDataCsReq", 36);
          }
        }
        continue;
      }
      var name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "EnterSceneScRsp",
                  "AcceptMainMissionScRsp",
                  "GetMissionDataScRsp",
                  "GetStoryLineInfoScRsp",
                  "FinishTalkMissionScRsp",
                  "GetCurLineupDataScRsp",
                  "GetCurSceneInfoScRsp",
                  "GetAvatarDataScRsp",
                  "SceneCastSkillScRsp",
                  "SceneCastSkillMpUpdateScNotify",
                  "SyncLineupNotify",
                  "ChangeStoryLineFinishScNotify")
              .stream()
              .filter(value -> protocol.clientCommand(value) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      System.err.println("Story response=" + name + ", step=" + step);
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown response " + name);
      switch (name) {
        case "SceneCastSkillMpUpdateScNotify" -> {
          if (casterChecks) techniquePointNotifications++;
        }
        case "SceneCastSkillScRsp" -> {
          require(
              casterChecks && number(message, "cast_entity_id") == requestedCaster,
              "Wrong caster response identity");
          if (step == 40) {
            require(
                number(message, "retcode")
                    == emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(),
                "Locked non-leader technique was accepted");
            result.put("nativeLockedNonLeaderTechniqueRejected", true);
            castNonLeader(c, 0, 42);
          } else if (step == 42) {
            require(
                number(message, "retcode") == 0,
                "A technique lock blocked the non-leader's ordinary attack");
            require(
                !message.hasField(message.getDescriptorForType().findFieldByName("battle_info")),
                "A targetless ordinary cast unexpectedly created a battle");
            result.put("nativeLockedNonLeaderOrdinaryAttackAccepted", true);
            if (abilityNameChecks) castNonLeader(c, 0, 44, sourceAttackName);
            else query(c, "GetCurLineupDataCsReq", 43);
          } else if (abilityNameChecks && step == 44) {
            require(number(message, "retcode") == 0, "Exact source primary attack name rejected");
            require(
                !message.hasField(message.getDescriptorForType().findFieldByName("battle_info")),
                "Named targetless attack unexpectedly created a battle");
            result.put("nativeExactSourceAttackNameAccepted", true);
            result.put("nativeSourceAttackName", sourceAttackName);
            castNonLeader(c, 0, 45, sourceAttackName + "_UnimplementedVariant");
          } else {
            require(
                abilityNameChecks
                    && step == 45
                    && number(message, "retcode")
                        == emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(),
                "Unknown named attack was accepted");
            result.put("nativeUnknownAttackNameRejected", true);
            query(c, "GetCurLineupDataCsReq", 43);
          }
        }
        case "PlayerGetTokenScRsp" -> {
          require(number(message, "retcode") == 0, "Token rejected");
          result.put("uid", field(message, "uid"));
          query(c, "PlayerLoginCsReq", 0);
        }
        case "PlayerLoginScRsp" -> {
          require(number(message, "retcode") == 0, "Login rejected");
          query(c, "PlayerLoginFinishCsReq", 0);
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(message, "retcode") == 0, "Login finish rejected");
          if (resume) query(c, "GetStoryLineInfoCsReq", 20);
          else {
            var enter = request("EnterSceneCsReq");
            set(enter, "entry_id", 2024201);
            send(c, "EnterSceneCsReq", enter, 1);
          }
        }
        case "EnterSceneScRsp" -> {
          require(step == 1 && number(message, "retcode") == 0, "Initial actual entry rejected");
          accept(c, 2022303, 2);
        }
        case "AcceptMainMissionScRsp" -> {
          require(number(message, "retcode") == 0, "Accept rejected");
          if (step == 2) accept(c, 2022304, 3);
          else if (step == 3) accept(c, 2022305, 4);
          else {
            require(step == 4, "Unexpected accept");
            query(c, "GetMissionDataCsReq", 5);
          }
        }
        case "GetMissionDataScRsp" -> {
          mission(message, step == 5 ? 0 : 1);
          if (step == 5) {
            result.put("outsideStoryStayedPending", true);
            query(c, "GetStoryLineInfoCsReq", 6);
          } else if (step == 12) {
            require(
                initialPartyNotified && replacementNotified && storyChanged,
                "Missing actual story notifications");
            result.put("nativeInitialAndReplacementNotifications", true);
            result.put("actualSourceTaskCompletedOnce", true);
            var avatars = request("GetAvatarDataCsReq");
            set(avatars, "is_get_all", true);
            send(c, "GetAvatarDataCsReq", avatars, 13);
          } else if (step == 15) {
            result.put("repeatedTalkDidNotReset", true);
            replace(c, OLD, 16);
          } else {
            require(step == 23, "Unexpected mission query");
            result.put("savedGoalRestored", true);
            var avatars = request("GetAvatarDataCsReq");
            set(avatars, "is_get_all", true);
            send(c, "GetAvatarDataCsReq", avatars, 24);
          }
        }
        case "GetStoryLineInfoScRsp" -> {
          story(message, step != 6);
          if (step == 6) replace(c, FUTURE, 7);
          else if (step == 9) query(c, "GetCurLineupDataCsReq", 10);
          else if (step == 20) {
            result.put("savedStoryRestored", true);
            query(c, "GetCurLineupDataCsReq", 21);
          } else {
            require(step == 18, "Unexpected story query");
            if (leaderChecks) prepareTwoTrialParty(c);
            else done();
          }
        }
        case "FinishTalkMissionScRsp" -> {
          require(number(message, "sub_mission_id") == 202230316, "Wrong talk identity");
          if (step == 8) {
            require(number(message, "retcode") == 0, "Story start action rejected");
            query(c, "GetStoryLineInfoCsReq", 9);
          } else {
            require(step == 14 && number(message, "retcode") != 0, "Repeated talk accepted");
            query(c, "GetMissionDataCsReq", 15);
          }
        }
        case "GetCurLineupDataScRsp" -> {
          require(number(message, "retcode") == 0, "Party query rejected");
          if (casterChecks && step == 43) {
            var lineup = (DynamicMessage) field(message, "lineup");
            twoTrialParty(lineup);
            require(
                number(lineup, "mp") == expectedMp && techniquePointNotifications == 0,
                "Rejected technique or ordinary attack spent technique points");
            result.put("nativeNonLeaderCastsKeptPointsAndLeader", true);
            result.put("nativeCasterSpecial", OLD);
            result.put("nativeCasterActor", 1224);
            result.put("nativeCasterPoints", expectedMp);
            if (castAfterResume) query(c, "GetMissionDataCsReq", 23);
            else done();
            break;
          }
          if (leaderChecks && (step == 36 || step == 21)) {
            twoTrialParty((DynamicMessage) field(message, "lineup"));
            if (casterChecks) expectedMp = number((DynamicMessage) field(message, "lineup"), "mp");
            if (step == 21) result.put("savedPartyRestored", true);
            query(c, "GetCurSceneInfoCsReq", step == 36 ? 37 : 22);
            break;
          }
          party((DynamicMessage) field(message, "lineup"), step == 10 ? FUTURE : OLD);
          if (step == 10) {
            result.put("nativeReplacedParty", true);
            query(c, "GetCurSceneInfoCsReq", 11);
          } else if (step == 17) query(c, "GetStoryLineInfoCsReq", 18);
          else {
            require(step == 21, "Unexpected party query");
            result.put("savedPartyRestored", true);
            query(c, "GetCurSceneInfoCsReq", 22);
          }
        }
        case "GetCurSceneInfoScRsp" -> {
          if (leaderChecks && (step == 37 || step == 22)) {
            twoTrialScene(message);
            if (casterChecks) {
              castAfterResume = step == 22;
              castNonLeader(c, 1, 40);
            } else if (step == 37) done();
            else query(c, "GetMissionDataCsReq", 23);
            break;
          }
          scene(message, step == 11 ? 1209 : 1224);
          query(c, "GetMissionDataCsReq", step == 11 ? 12 : 23);
        }
        case "GetAvatarDataScRsp" -> {
          formal(message);
          if (step == 13) talk(c, 14);
          else {
            require(step == 24, "Unexpected formal query");
            done();
          }
        }
        case "SyncLineupNotify" -> {
          var lineup = (DynamicMessage) field(message, "lineup");
          if (number(lineup, "game_story_line_id") == STORY) {
            var ids = (List<?>) field(lineup, "story_line_avatar_id_list");
            if (ids.equals(List.of(OLD))) initialPartyNotified = true;
            if (ids.equals(List.of(FUTURE))) {
              party(lineup, FUTURE);
              replacementNotified = true;
            }
          }
        }
        case "ChangeStoryLineFinishScNotify" -> {
          if (number(message, "cur_story_line_id") == STORY) storyChanged = true;
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackStoryTrialProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      probe.leaderChecks =
          credentials.has("leaderChecks") && credentials.get("leaderChecks").getAsBoolean();
      probe.casterChecks =
          credentials.has("casterChecks") && credentials.get("casterChecks").getAsBoolean();
      probe.abilityNameChecks =
          credentials.has("abilityNameChecks")
              && credentials.get("abilityNameChecks").getAsBoolean();
      require(
          !probe.abilityNameChecks || probe.casterChecks,
          "Ability name checks require the caster fixture");
      probe.sourceAttackName =
          probe.abilityNameChecks ? credentials.get("sourceAttackName").getAsString() : "";
      require(
          !probe.abilityNameChecks || probe.sourceAttackName.endsWith("NormalAtk01"),
          "Missing source primary attack name");
      require(
          !probe.casterChecks || probe.leaderChecks, "Caster checks require the two-trial party");
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", credentials.get("accountUid").getAsString());
      set(token, "token", credentials.get("comboToken").getAsString());
      set(token, "platform", 1);
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1400);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp c) {
              probe.send(c, "PlayerGetTokenCsReq", token, 0);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                probe.receive(bytes, c);
              } catch (Exception failure) {
                probe.completed.completeExceptionally(failure);
              }
            }

            public void handleException(Throwable failure, Ukcp c) {
              probe.completed.completeExceptionally(failure);
            }

            public void handleClose(Ukcp c) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Story probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(25, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception failure) {
      failure.printStackTrace(System.err);
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
