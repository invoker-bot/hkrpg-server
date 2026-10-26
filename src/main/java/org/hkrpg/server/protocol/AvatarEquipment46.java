package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import java.util.HashMap;
import java.util.List;

/** CN4.6 keeps the active light-cone UID on Avatar as well as on its selected path. */
final class AvatarEquipment46 {
  static DynamicMessage project(DynamicMessage snapshot) {
    var type = snapshot.getDescriptorForType();
    if (!type.getName().equals("GetAvatarDataScRsp") && !type.getName().equals("AvatarSync"))
      return snapshot;
    if (type.getName().equals("GetAvatarDataScRsp") && Gameplay46.number(snapshot, "retcode") != 0)
      return snapshot;

    var avatars = type.findFieldByName("avatar_list");
    var paths = type.findFieldByName("avatar_path_data_info_list");
    var equipmentByPath = new HashMap<Integer, Integer>();
    for (var value : (List<?>) snapshot.getField(paths)) {
      var path = (DynamicMessage) value;
      equipmentByPath.put(
          Gameplay46.number(path, "avatar_id"), Gameplay46.number(path, "path_equipment_id"));
    }
    DynamicMessage.Builder changed = null;
    var rows = (List<?>) snapshot.getField(avatars);
    for (int i = 0; i < rows.size(); i++) {
      var avatar = (DynamicMessage) rows.get(i);
      int pathId = Gameplay46.number(avatar, "cur_multi_path_avatar_type");
      if (pathId == 0) pathId = Gameplay46.number(avatar, "base_avatar_id");
      // A partial delta must never borrow equipment from another path or a live player lookup.
      // Reading both fields from this one packet also keeps login, swap and unequip consistent.
      var uid = equipmentByPath.get(pathId);
      if (uid == null || uid == Gameplay46.number(avatar, "equipment_unique_id")) continue;
      var field = avatar.getDescriptorForType().findFieldByName("equipment_unique_id");
      if (changed == null) changed = snapshot.toBuilder();
      changed.setRepeatedField(avatars, i, avatar.toBuilder().setField(field, uid).build());
    }
    return changed == null ? snapshot : changed.build();
  }

  private AvatarEquipment46() {}
}
