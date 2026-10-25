package org.hkrpg.server.protocol;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Semantic field conversion. Obfuscated fields and unmatched values are never copied by number. */
public final class ProtocolBridge {
  public static final String DESIGN_TARGET = "4.6.0";

  public record Packet(int cmdId, byte[] body) {}

  private static final Map<String, String> TYPE_ALIASES =
      Map.of(
          "DispatchRegionData",
          "Dispatch",
          "RegionEntry",
          "RegionInfo",
          "Gateserver",
          "GateServer",
          "AvatarPathInfo",
          "AvatarPathData",
          "PlayerOutfitInfo",
          "CJLCPMDGIBO",
          "SceneIdentifierInfo",
          "SceneIdentifier",
          "ChallengeInfo",
          "CurChallenge");
  // Semantic evidence: Honey fe00bdb, AvatarData/InventoryData/PlayerData and
  // SceneInstance.Serialization.
  private static final Map<String, String> FIELD_ALIASES =
      Map.ofEntries(
          Map.entry("PlayerGetTokenCsReq.platform_type", "platform"),
          Map.entry("PlayerGetTokenScRsp.msg", "stop_desc"),
          // Honey fe00bdb CmdAvatarType: native request 3 contains ItemCostData (list 2).
          Map.entry("AvatarExpUpCsReq.item_cost_list", "item_cost"),
          // Honey fe00bdb RankUpAvatar consumes CostData (CN4.6 field 14).
          Map.entry("RankUpAvatarCsReq.item_cost_list", "cost_data"),
          // Honey fe00bdb CmdShopType: CN4.6 field 15 is the interacted scene prop.
          Map.entry("BuyGoodsCsReq.server_entity_id", "interacted_prop_entity_id"),
          // Honey fe00bdb CmdItemType consumes CostData for all three equipment operations.
          Map.entry("ExpUpEquipmentCsReq.item_cost_list", "cost_data"),
          Map.entry("ExpUpRelicCsReq.item_cost_list", "cost_data"),
          Map.entry("RankUpEquipmentCsReq.item_cost_list", "cost_data"),
          Map.entry("FinishQuestScRsp.retcode", "PLDKENJLKME"),
          Map.entry("Avatar.changed_avatar_type", "cur_multi_path_avatar_type"),
          Map.entry("Avatar.first_met_timestamp", "first_met_time_stamp"),
          Map.entry("Avatar.taken_rewards", "has_taken_promotion_reward_list"),
          Map.entry("AvatarPathInfo.avatar_skin", "dressed_skin_id"),
          Map.entry("AvatarPathInfo.enhance_id", "unk_enhanced_id"),
          Map.entry("AvatarPathInfo.skilltree_list", "avatar_path_skill_tree"),
          Map.entry("AvatarPathSkillTree.anchor_point_id", "point_id"),
          Map.entry("EquipRelic.slot", "type"),
          // Honey fe00bdb CmdArchiveType / PacketGetArchiveDataScRsp:
          // monster list 13; relic set 3 and slot/type 15. Preserve the core catalog values.
          Map.entry("ArchiveData.monster_list", "kill_monster_list"),
          Map.entry("RelicArchive.relic_id", "set_id"),
          Map.entry("RelicArchive.slot", "type"),
          Map.entry("SceneEnterStageCsReq.stage_id", "event_id"),
          // Honey fe00bdb consumes CN4.6 wave field4 as the challenge count; the 4.2 handler
          // consumes wave_count, while the unused 4.2 wave field must retain its legacy encoding.
          Map.entry("StartCocoonStageCsReq.wave_count", "wave"),
          Map.entry("QuickStartCocoonStageCsReq.wave_count", "wave"),
          Map.entry("QuickStartCocoonStageScRsp.wave_count", "wave"),
          // Honey fe00bdb memory challenge request/response and settlement builders.
          Map.entry("StartChallengeCsReq.first_half_lineup", "first_lineup"),
          Map.entry("StartChallengeCsReq.second_half_lineup", "second_lineup"),
          Map.entry("StartChallengeScRsp.challenge_info", "cur_challenge"),
          Map.entry("GetCurChallengeScRsp.challenge_info", "cur_challenge"),
          Map.entry("ChallengeSettleNotify.challenge_info", "cur_challenge"),
          Map.entry("ChallengeSettleNotify.stars", "star"),
          Map.entry("ChallengeSettleNotify.reward_list", "reward"),
          // Honey ChallengeStoryInstance/BossInstance serialize ScoreStage1 as ScoreId.
          Map.entry("ChallengeInfo.score", "score_id"),
          Map.entry("ChallengeInfo.ext_info", "stage_info"),
          Map.entry("Challenge.stars", "star"),
          Map.entry("Challenge.score", "score_id"),
          Map.entry("BattleAvatar.avatar_enhance_id", "enhanced_id"),
          Map.entry("BattleBuff.owner_id", "owner_index"),
          Map.entry("SceneMonsterWave.stage_id", "battle_stage_id"),
          Map.entry("SceneMonsterWave.wave_id", "battle_wave_id"),
          Map.entry("SceneMonsterWave.wave_param", "monster_param"),
          Map.entry("SceneBattleInfo.event_battle_info_list", "battle_event"),
          // Native Battle46 passes the 4.6 cast entity echo to these legacy packet builders.
          Map.entry("SceneCastSkillScRsp.attacked_group_id", "cast_entity_id"),
          Map.entry("SceneCastSkillMpUpdateScNotify.attacked_group_id", "cast_entity_id"),
          // Honey fe00bdb scene buff notifications and the matched CN4.6 oneof fields.
          Map.entry(
              "SyncEntityBuffChangeListScNotify.entity_buff_info_list", "entity_buff_change_list"),
          Map.entry("EntityBuffChangeInfo.add_buff_info", "buff_change_info"),
          Map.entry("GetAvatarDataScRsp.avatar_path_info_list", "avatar_path_data_info_list"),
          Map.entry("GetAvatarDataScRsp.owned_skin_list", "skin_list"),
          // Honey fe00bdb CmdAvatarType: unlock field 11 and dress request field 3.
          Map.entry("UnlockAvatarSkinScNotify.avatar_skin_id", "skin_id"),
          Map.entry("DressAvatarSkinCsReq.avatar_skin_id", "skin_id"),
          Map.entry("GetAvatarDataScRsp.player_outfit_data", "DHKFCDAGHDM"),
          Map.entry("AvatarSync.avatar_path_info_list", "avatar_path_data_info_list"),
          Map.entry("PlayerSyncScNotify.player_outfit_data", "DHKFCDAGHDM"),
          // Honey fe00bdb PlayerBoardSync and named CN4.6 CmdPlayerSyncType/Common fields.
          // The obfuscated frame-unlock list is deliberately still unsupported.
          Map.entry("PlayerSyncScNotify.board_data_sync", "update_player_board_module_list"),
          Map.entry("BoardDataSync.head_icon_frame_info", "head_frame_info"),
          Map.entry("HeadIconFrameInfo.head_icon_frame_id", "head_frame_item_id"),
          Map.entry("PlayerOutfitInfo.outfit_id_list", "EAKOLIJOEPA"),
          Map.entry("Equipment.equip_avatar_id", "dress_avatar_id"),
          Map.entry("Relic.equip_avatar_id", "dress_avatar_id"),
          Map.entry("LineupInfo.is_virtual", "_is_virtual"),
          // CN4.6 named list field 3 and LineupAvatarData id/hp/type 7/14/3;
          // Honey fe00bdb PacketGetLineupAvatarDataScRsp populates AvatarDataList.
          Map.entry("GetLineupAvatarDataScRsp.lineup_avatar_data_list", "avatar_data_list"),
          // Named CN4.6 initial cosmetic snapshots. Ownership lists retain their actual values.
          Map.entry("GetPetDataScRsp.pet_id_list", "unlocked_pet_id"),
          Map.entry("GetPamSkinDataScRsp.current_pam_skin_id", "cur_skin"),
          Map.entry("GetPamSkinDataScRsp.unlocked_pam_skins", "unlock_skin_list"),
          // Named CN4.6 GetFriendLoginInfoScRsp wire 3 retains the friend UID snapshot.
          Map.entry("GetFriendLoginInfoScRsp.friend_uid_list", "_friend_uid_list"),
          // CN4.6 TalkModule consumes list field 2 and FirstNpcTalkInfo id 1 / is_meet 3.
          // The core has no completed-meeting flag; preserve its default false, never invent one.
          Map.entry("GetFirstTalkNpcScRsp.npc_talk_info_list", "npc_meet_status_list"),
          // Honey fe00bdb CmdTalkEventType and handler: sentence request 4, echoed reply 11.
          Map.entry("SelectInclinationTextCsReq.inclination_text_id", "talk_sentence_id"),
          Map.entry("SelectInclinationTextScRsp.inclination_text_id", "talk_sentence_id"),
          // Named CN4.6 field 14; Honey fe00bdb ChallengePeak snapshot uses CurrentPeakGroupId.
          Map.entry("GetChallengePeakDataScRsp.cur_challenge_peak_group_id", "current_peak_group_id"),
          // Honey fe00bdb JukeBox packet and pinned native MusicAlbumModule callback.
          Map.entry("GetJukeboxDataScRsp.music_list", "IOKAJIBHLMP"),
          Map.entry("UnlockedMusic.unkbool", "BIMDKNLMICG"),
          Map.entry("SceneInfo.floor_saved_value_map", "floor_saved_data"),
          Map.entry("SceneEntityGroupInfo.group_property_map", "BANBECDCDHG"),
          // 4.6 CmdSceneType: group list 7, GroupRefreshInfo refresh_type 3, delete_entity 14.
          Map.entry("SceneGroupRefreshScNotify.group_refresh_info", "group_refresh_list"),
          Map.entry("SceneGroupRefreshInfo.group_refresh_type", "refresh_type"),
          Map.entry("SceneEntityRefreshInfo.del_entity", "delete_entity"),
          Map.entry("PropExtraInfo.prop_timeline_info", "timeline_info"),
          Map.entry("PropTimelineInfo.KBFLIKKFOEG", "timeline_bool_value"),
          Map.entry("PropTimelineInfo.COOGIPJIPIA", "timeline_byte_value"),
          Map.entry("AvatarPathChangedNotify.changed_avatar_type", "cur_multi_path_avatar_type"));
  private static final Set<String> FIELDLESS_CORE_HANDLERS =
      Set.of("PlayerLoginCsReq", "PlayerLoginFinishCsReq", "GetAvatarDataCsReq", "GetMailCsReq");
  private final boolean legacy;
  private final ProtocolSchema coreSchema;
  private final ProtocolSchema clientSchema;
  private final Map<String, Integer> coreCommands;
  private final Map<String, Integer> clientCommands;
  private final Map<Integer, String> coreNames;
  private final Map<Integer, String> clientNames;

  private ProtocolBridge(boolean legacy) {
    this.legacy = legacy;
    this.coreSchema = Schemas.CORE;
    this.clientSchema = legacy ? coreSchema : Schemas.CLIENT;
    this.coreCommands = Schemas.CORE_COMMANDS;
    this.clientCommands = legacy ? coreCommands : Schemas.CLIENT_COMMANDS;
    this.coreNames = reverse(coreCommands);
    this.clientNames = reverse(clientCommands);
  }

  private static final class Schemas {
    private static final ProtocolSchema CORE = ProtocolSchema.load("4.2");
    private static final ProtocolSchema CLIENT = ProtocolSchema.load("4.6");
    private static final Map<String, Integer> CORE_COMMANDS = coreCommands();
    private static final Map<String, Integer> CLIENT_COMMANDS = readClientCommands();
  }

  public static ProtocolBridge forVersion(String version) {
    if (version == null || version.isBlank())
      throw new IllegalArgumentException("Wire protocol version must be non-empty");
    return switch (version) {
      case "4.2.0" -> new ProtocolBridge(true);
      case DESIGN_TARGET -> new ProtocolBridge(false);
      default ->
          throw new IllegalArgumentException(
              "Unsupported wire protocol: " + version + "; 4.6.51 beta is a separate protocol");
    };
  }

  public ProtocolSchema coreSchema() {
    return coreSchema;
  }

  public ProtocolSchema clientSchema() {
    return clientSchema;
  }

  public String version() {
    return legacy ? "4.2.0" : DESIGN_TARGET;
  }

  public boolean experimental() {
    return !legacy;
  }

  public byte[] outboundFrames(byte[] bytes) {
    return outboundFrames(bytes, null);
  }

  public byte[] outboundFrames(byte[] bytes, emu.lunarcore.game.player.Player player) {
    if (legacy) return bytes;
    var input = Unpooled.wrappedBuffer(bytes);
    var output = new ByteArrayOutputStream();
    try {
      while (input.isReadable()) {
        var frame = PacketCodec.decodeNext(input);
        if (frame == null) throw new ProtocolException("Truncated outgoing frame");
        var converted = outbound(frame.cmdId(), frame.body());
        var body =
            Scene46.enrich(clientSchema, coreNames.get(frame.cmdId()), converted.body(), player);
        body = StoryParty46.enrich(clientSchema, coreNames.get(frame.cmdId()), body, player);
        body = FormulaLearning46.enrich(clientSchema, coreNames.get(frame.cmdId()), body, player);
        output.writeBytes(PacketCodec.encode(converted.cmdId(), frame.header(), body));
      }
      return output.toByteArray();
    } finally {
      input.release();
    }
  }

  public int clientCommand(String name) {
    var id = clientCommands.get(name);
    if (id == null || id <= 0) throw new ProtocolException("No client command: " + name);
    return id;
  }

  public Packet inbound(int id, byte[] bytes) {
    if (legacy) return new Packet(id, bytes);
    String name = clientNames.get(id);
    if (name == null || !coreCommands.containsKey(name)
        || clientSchema.find(clientTypeName(name)) == null)
      throw new UnsupportedInboundCommandException(id);
    if (FIELDLESS_CORE_HANDLERS.contains(name)) {
      parseClient(name, bytes);
      return new Packet(coreCommands.get(name), new byte[0]);
    }
    if (coreSchema.find(name) == null) {
      // These audited handlers consume no request fields and return full snapshots.
      var request = clientSchema.message(name);
      if (request.getFields().isEmpty()) {
        parseClient(name, bytes);
        return new Packet(coreCommands.get(name), new byte[0]);
      }
      throw new UnsupportedInboundCommandException(id);
    }
    return new Packet(coreCommands.get(name), translate(name, bytes, false));
  }

  public Packet outbound(int id, byte[] bytes) {
    if (legacy) return new Packet(id, bytes);
    String name = coreNames.get(id);
    if (name == null) throw new ProtocolException("Unknown core command: " + id);
    int targetId = clientCommand(name);
    // Some core handlers acknowledge by sending a cached empty response without a generated type.
    if (bytes.length == 0) {
      clientSchema.message(name);
      return new Packet(targetId, bytes);
    }
    return new Packet(targetId, translate(name, bytes, true));
  }

  public byte[] translate(String coreName, byte[] bytes, boolean outbound) {
    String clientName = clientTypeName(coreName);
    var source = outbound ? coreSchema.message(coreName) : clientSchema.message(clientName);
    var target = outbound ? clientSchema.message(clientName) : coreSchema.message(coreName);
    try {
      var message = DynamicMessage.parseFrom(source, bytes);
      if (!message.getUnknownFields().asMap().isEmpty())
        throw new ProtocolException("Unknown wire fields in " + source.getFullName());
      return convert(message, target, outbound, 0).toByteArray();
    } catch (IOException ex) {
      throw new ProtocolException("Malformed protobuf: " + coreName);
    }
  }

  private void parseClient(String name, byte[] bytes) {
    try {
      var request = clientSchema.parse(name, bytes);
      if ("GetMailCsReq".equals(name)
          && (!Parkour46.wireValid(request.getDescriptorForType(), bytes, 0)
              || !request.getAllFields().isEmpty()))
        throw new ProtocolException("Unsupported mail snapshot fields");
      if (!request.getUnknownFields().asMap().isEmpty())
        throw new ProtocolException("Unknown wire fields in " + name);
    } catch (IOException ex) {
      throw new ProtocolException("Malformed protobuf: " + name);
    }
  }

  private DynamicMessage convert(
      DynamicMessage input, Descriptor target, boolean outbound, int depth) {
    if (depth > 64) throw new ProtocolException("Protocol nesting exceeds limit");
    // The inherited Quickbuf packet writes enum 3 although its frozen proto2 descriptor only
    // names 0..2. Honey fe00bdb PacketSceneGroupRefreshScNotify uses Afibfmafncc for deletion.
    // Admit only this exact server-authored value; all other unknown fields still fail closed.
    var refreshType = input.getDescriptorForType().findFieldByName("group_refresh_type");
    boolean entityRefresh =
        outbound
            && input.getDescriptorForType().getName().equals("SceneGroupRefreshInfo")
            && target.getName().equals("GroupRefreshInfo")
            && refreshType != null
            && !input.hasField(refreshType)
            && input
                .getUnknownFields()
                .asMap()
                .equals(
                    Map.of(
                        refreshType.getNumber(),
                        com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                            .addVarint(3)
                            .build()));
    if (!entityRefresh && !input.getUnknownFields().asMap().isEmpty())
      throw new ProtocolException(
          "Unknown nested wire fields: " + input.getDescriptorForType().getFullName());
    if (outbound && input.getDescriptorForType().getName().equals("SceneInfo"))
      input = Scene46Migration.normalize(input);
    if (!outbound) {
      // A missing core field must not silently become an ID of zero or an empty action.
      for (var field : target.getFields()) {
        if (mappedField(field, input.getDescriptorForType(), true) == null
            && !field.getFullName().endsWith("PVEBattleResultCsReq.is_ai_consider_ultra_skill")) {
          throw new ProtocolException(
              "Core request field has no 4.6 mapping: " + field.getFullName());
        }
      }
    }
    var result = DynamicMessage.newBuilder(target);
    if (entityRefresh) {
      var field = target.findFieldByName("refresh_type");
      result.setField(
          field, field.getEnumType().findValueByName("SCENE_GROUP_REFRESH_TYPE_AFIBFMAFNCC"));
    }
    for (var entry : input.getAllFields().entrySet()) {
      var sourceField = entry.getKey();
      // The core explicitly writes zero even when no pet is selected. CN4.6 represents that
      // absence by omitting its current-pet union, not by reusing the old scalar field number.
      // A nonzero selection still requires a verified union mapping and remains rejected.
      if (outbound && sourceField.getFullName().equals("legacy.GetPetDataScRsp.cur_pet_id")
          && entry.getValue().equals(0)) continue;
      if (outbound && sourceField.getFullName().equals("legacy.GetJukeboxDataScRsp.playing_id")) {
        // CN4.6 replaces the flat selection with response 12 -> player 1 -> music id 1.
        // Native PlayingMusicItemsInfo.SyncServerData requires the player container.
        var playerField = target.findFieldByName("GFFOBALDBPM");
        var selectedField = playerField.getMessageType().findFieldByName("JKNLCEEDAHJ");
        var selected = DynamicMessage.newBuilder(selectedField.getMessageType())
            .setField(selectedField.getMessageType().findFieldByName("id"), entry.getValue());
        result.setField(playerField, DynamicMessage.newBuilder(playerField.getMessageType())
            .setField(selectedField, selected.build()).build());
        continue;
      }
      var field = mappedField(sourceField, target, outbound);
      if (field == null) {
        // Extra client telemetry is ignored only when it has no corresponding core field.
        if (!outbound) continue;
        throw new ProtocolException("Unmapped outgoing field: " + sourceField.getFullName());
      }
      if (!compatible(sourceField, field))
        throw new ProtocolException("Incompatible field: " + sourceField.getFullName());
      if (sourceField.isRepeated()) {
        for (Object value : (List<?>) entry.getValue())
          result.addRepeatedField(field, convertValue(value, field, outbound, depth));
      } else {
        result.setField(field, convertValue(entry.getValue(), field, outbound, depth));
      }
    }
    if (outbound && input.getDescriptorForType().getName().equals("EntityBuffChangeInfo")) {
      var add = target.findFieldByName("buff_change_info");
      if (add != null && result.hasField(add)) {
        var reason = target.findFieldByName("reason");
        result.setField(
            reason,
            reason.getEnumType().findValueByName("SCENE_ENTITY_BUFF_CHANGE_TYPE_ADD_MAZEBUFF"));
      }
    }
    return result.build();
  }

  static FieldDescriptor mappedField(FieldDescriptor source, Descriptor target, boolean outbound) {
    String owner = source.getContainingType().getName();
    String name = source.getName();
    boolean explicit = false;
    if (outbound) {
      explicit = FIELD_ALIASES.containsKey(owner + "." + name);
      name = FIELD_ALIASES.getOrDefault(owner + "." + name, name);
    } else {
      for (var alias : FIELD_ALIASES.entrySet()) {
        if (alias.getKey().startsWith(target.getName() + ".") && alias.getValue().equals(name)) {
          name = alias.getKey().substring(target.getName().length() + 1);
          explicit = true;
          break;
        }
      }
    }
    // A coincidentally unchanged obfuscated name is not evidence of semantic equivalence.
    if (!explicit && name.matches("[A-Z]{6,}")) return null;
    return target.findFieldByName(name);
  }

  static boolean compatible(FieldDescriptor source, FieldDescriptor target) {
    return source.isRepeated() == target.isRepeated()
        && source.getJavaType() == target.getJavaType()
        && (source.getType() == target.getType()
            || signedInteger(source) && signedInteger(target)
            || source.getJavaType() == FieldDescriptor.JavaType.MESSAGE
            || source.getJavaType() == FieldDescriptor.JavaType.ENUM);
  }

  private static boolean signedInteger(FieldDescriptor field) {
    return switch (field.getType()) {
      case INT32, SINT32, SFIXED32, INT64, SINT64, SFIXED64 -> true;
      default -> false;
    };
  }

  private Object convertValue(Object value, FieldDescriptor field, boolean outbound, int depth) {
    if (value instanceof DynamicMessage nested)
      return convert(nested, field.getMessageType(), outbound, depth + 1);
    if (value instanceof EnumValueDescriptor source) {
      String semanticName = enumName(source);
      for (var candidate : field.getEnumType().getValues()) {
        String targetName = enumName(candidate);
        if (semanticName.equals(targetName)) return candidate;
      }
      throw new ProtocolException("Unmapped enum value: " + source.getFullName());
    }
    return value;
  }

  static String enumName(EnumValueDescriptor value) {
    String name = value.getName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    String prefix =
        value.getType().getName().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
  }

  static String clientTypeName(String name) {
    return TYPE_ALIASES.getOrDefault(name, name);
  }

  private static Map<String, Integer> coreCommands() {
    var result = new TreeMap<String, Integer>();
    for (var field : CmdId.class.getFields()) {
      try {
        if (field.getType() == int.class && field.getInt(null) > 0)
          result.put(field.getName(), field.getInt(null));
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException(ex);
      }
    }
    return Map.copyOf(result);
  }

  private static Map<String, Integer> readClientCommands() {
    try (var stream = ProtocolBridge.class.getResourceAsStream("/protocol/4.6/commands.json")) {
      if (stream == null) throw new IllegalStateException("Missing 4.6 commands");
      return Map.copyOf(
          new Gson()
              .fromJson(
                  new InputStreamReader(stream, StandardCharsets.UTF_8),
                  new TypeToken<Map<String, Integer>>() {}.getType()));
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static Map<Integer, String> reverse(Map<String, Integer> commands) {
    var result = new HashMap<Integer, String>();
    commands.forEach(
        (name, id) -> {
          if (result.putIfAbsent(id, name) != null)
            throw new IllegalStateException("Duplicate command id: " + id);
        });
    return Map.copyOf(result);
  }
}
