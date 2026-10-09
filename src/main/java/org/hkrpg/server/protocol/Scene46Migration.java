package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import java.util.*;

/** 4.6 carries group state in entity_group_list, including groups without entities. */
final class Scene46Migration {
    static DynamicMessage normalize(DynamicMessage scene) {
        var type = scene.getDescriptorForType();
        var states = type.findFieldByName("group_state_list");
        var groups = type.findFieldByName("entity_group_list");
        var groupType = groups.getMessageType();
        var groupId = groupType.findFieldByName("group_id");
        var stateField = groupType.findFieldByName("state");
        var merged = new LinkedHashMap<Integer, DynamicMessage.Builder>();
        for (Object value : (List<?>) scene.getField(groups)) {
            var group = (DynamicMessage) value;
            int id = (Integer) group.getField(groupId);
            if (merged.putIfAbsent(id, group.toBuilder()) != null) throw new ProtocolException("Duplicate scene group: " + id);
        }
        var seenStates = new HashMap<Integer, Integer>();
        for (Object value : (List<?>) scene.getField(states)) {
            var state = (DynamicMessage) value;
            if (!state.getUnknownFields().asMap().isEmpty()) throw new ProtocolException("Unknown scene group state fields");
            int id = (Integer) state.getField(states.getMessageType().findFieldByName("group_id"));
            int number = (Integer) state.getField(states.getMessageType().findFieldByName("state"));
            var previous = seenStates.putIfAbsent(id, number);
            if (previous != null && previous != number) throw new ProtocolException("Conflicting scene group state: " + id);
            var group = merged.computeIfAbsent(id, key -> DynamicMessage.newBuilder(groupType).setField(groupId, key));
            if (group.hasField(stateField) && !group.getField(stateField).equals(number))
                throw new ProtocolException("Conflicting scene group state: " + id);
            group.setField(stateField, number);
        }
        var result = scene.toBuilder().clearField(states).clearField(groups);
        merged.values().forEach(group -> result.addRepeatedField(groups, group.build()));
        return result.build();
    }
    private Scene46Migration() {}
}
