package org.hkrpg.server.protocol;

import emu.lunarcore.game.player.Player;

/** Native scene values, using named fields from the pinned 4.6 descriptor. */
public final class Story46 {
  public static void syncReward(
      Player p, boolean main, int id, java.util.List<emu.lunarcore.game.inventory.GameItem> items) {
    if (!p.isOnline() || p.getSession() == null || !p.getSession().getProtocol().experimental())
      return;
    var s = p.getSession();
    var name = main ? "MissionRewardScNotify" : "SubMissionRewardScNotify";
    var b = Gameplay46.builder(s, name);
    Gameplay46.set(b, main ? "main_mission_id" : "sub_mission_id", id);
    Gameplay46.set(b, "reward", Gameplay46.items(s, items));
    s.sendClientPacket(name, b.build().toByteArray());
  }

  public static void syncState(Player p, int floor, int group, int state) {
    if (!p.isOnline() || p.getSession() == null || !p.getSession().getProtocol().experimental())
      return;
    var session = p.getSession();
    var notify = Gameplay46.builder(session, "SceneGroupRefreshScNotify");
    Gameplay46.set(notify, "floor_id", floor);
    var field = notify.getDescriptorForType().findFieldByName("group_refresh_list");
    var row = com.google.protobuf.DynamicMessage.newBuilder(field.getMessageType());
    Gameplay46.set(row, "group_id", group);
    Gameplay46.set(row, "state", state);
    Gameplay46.set(
        row,
        "refresh_type",
        row.getDescriptorForType()
            .findFieldByName("refresh_type")
            .getEnumType()
            .findValueByName("SCENE_GROUP_REFRESH_TYPE_LOADED"));
    Gameplay46.add(notify, "group_refresh_list", row.build());
    session.sendClientPacket("SceneGroupRefreshScNotify", notify.build().toByteArray());
  }

  public static void syncGroup(
      Player p, int floor, int group, String name, int oldValue, int value) {
    if (!p.isOnline() || !p.getSession().getProtocol().experimental()) return;
    var session = p.getSession();
    var b = Gameplay46.builder(session, "SceneGroupRefreshScNotify");
    Gameplay46.set(b, "floor_id", floor);
    var groups = b.getDescriptorForType().findFieldByName("group_refresh_list");
    var row = com.google.protobuf.DynamicMessage.newBuilder(groups.getMessageType());
    Gameplay46.set(row, "group_id", group);
    Gameplay46.set(
        row,
        "refresh_type",
        row.getDescriptorForType()
            .findFieldByName("refresh_type")
            .getEnumType()
            .findValueByNumber(1));
    var properties = row.getDescriptorForType().findFieldByName("CDMKBCHLPAB");
    var property = com.google.protobuf.DynamicMessage.newBuilder(properties.getMessageType());
    Gameplay46.set(property, "property_name", name);
    Gameplay46.set(property, "old_value", oldValue);
    Gameplay46.set(property, "new_value", value);
    Gameplay46.add(row, "CDMKBCHLPAB", property.build());
    Gameplay46.add(b, "group_refresh_list", row.build());
    session.sendClientPacket("SceneGroupRefreshScNotify", b.build().toByteArray());
  }

  public static void syncFloor(Player p, int plane, int floor, String name, int value) {
    if (!p.isOnline() || !p.getSession().getProtocol().experimental()) return;
    var s = p.getSession();
    var b = Gameplay46.builder(s, "UpdateFloorSavedValueNotify");
    Gameplay46.set(b, "plane_id", plane);
    Gameplay46.set(b, "floor_id", floor);
    var f = b.getDescriptorForType().findFieldByName("saved_value");
    var entry = com.google.protobuf.DynamicMessage.newBuilder(f.getMessageType());
    Gameplay46.set(entry, "key", name);
    Gameplay46.set(entry, "value", value);
    Gameplay46.add(b, "saved_value", entry.build());
    s.sendClientPacket("UpdateFloorSavedValueNotify", b.build().toByteArray());
  }

  private Story46() {}
}
