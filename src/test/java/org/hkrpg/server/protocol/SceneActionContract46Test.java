package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SceneActionContract46Test {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");

  void field(String message, String name, int number, Type type, boolean repeated) {
    var field = protocol.clientSchema().message(message).findFieldByName(name);
    assertNotNull(field);
    assertEquals(number, field.getNumber());
    assertEquals(type, field.getType());
    assertEquals(repeated, field.isRepeated());
  }

  @Test void frozenFieldRolesCoverAttacksPaymentsInteractionsAndStoryReceipts() {
    assertEquals(1460, protocol.clientCommand("SceneCastSkillCsReq"));
    assertEquals(1408, protocol.clientCommand("SceneCastSkillCostMpCsReq"));
    assertEquals(1405, protocol.clientCommand("InteractPropCsReq"));
    for (String message : java.util.List.of("SceneCastSkillCsReq", "SceneCastSkillCostMpCsReq")) {
      field(message, "cast_entity_id", 13, Type.UINT32, false);
      field(message, "attacked_by_entity_id", 2, Type.UINT32, false);
    }
    field("SceneCastSkillCsReq", "skill_index", 4, Type.UINT32, false);
    field("SceneCastSkillCostMpCsReq", "skill_index", 7, Type.UINT32, false);
    field("SceneCastSkillCsReq", "assist_monster_entity_id_list", 6, Type.UINT32, true);
    field("SceneCastSkillCsReq", "assist_monster_entity_info", 7, Type.MESSAGE, true);
    field("AssistMonsterEntityInfo", "entity_id_list", 7, Type.UINT32, true);
    field("SceneCastSkillCsReq", "hit_target_entity_id_list", 9, Type.UINT32, true);
    field("SceneCastSkillCsReq", "skill_extra_tags", 11, Type.ENUM, true);
    field("SceneCastSkillCsReq", "maze_ability_str", 15, Type.STRING, false);
    field("InteractPropCsReq", "prop_entity_id", 4, Type.UINT32, false);
    field("InteractPropCsReq", "interact_id2", 5, Type.UINT32, false);
    field("InteractPropCsReq", "interact_id", 8, Type.UINT64, false);
    field("FinishTalkMissionCsReq", "talk_str", 2, Type.STRING, false);
    field("FinishTalkMissionCsReq", "sub_mission_id", 6, Type.UINT32, false);
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
  void paymentUsesTheSameUnsignedIdentityAndDuplicateChecks(int variant) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt64(13, 441L + (variant == 0 ? 1L << 32 : 0));
    out.writeUInt64(2, 1L + (variant == 1 ? 1L << 32 : 0));
    out.writeUInt32(7, 1);
    if (variant == 2) out.writeUInt32(13, 441);
    if (variant == 3) out.writeUInt32(2, 1);
    out.flush();
    assertNotNull(SceneActionWire46.invalid(protocol.clientSchema().message("SceneCastSkillCostMpCsReq"),
        bytes.toByteArray(), 0, ""));
  }

}
