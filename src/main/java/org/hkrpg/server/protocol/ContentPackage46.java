package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.game.GameSession;

/** Initial catalog only. Story conditions and current content selection remain unimplemented. */
public final class ContentPackage46 {
    public static void send(GameSession session, String messageName) {
        var schema = session.getProtocol().clientSchema();
        var dataType = schema.message("ContentPackageData");
        var infoType = schema.message("ContentPackageInfo");
        var status = infoType.findFieldByName("status").getEnumType().findValueByName("ContentPackageStatus_Init");
        var data = DynamicMessage.newBuilder(dataType);
        GameData.getContentPackageExcelMap().values().stream().sorted(java.util.Comparator.comparingInt(value -> value.getId()))
            .forEach(excel -> data.addRepeatedField(dataType.findFieldByName("content_package_list"),
                DynamicMessage.newBuilder(infoType).setField(infoType.findFieldByName("content_id"), excel.getId())
                    .setField(infoType.findFieldByName("status"), status).build()));
        var message = schema.message(messageName);
        var response = DynamicMessage.newBuilder(message).setField(message.findFieldByName("data"), data.build()).build();
        session.sendClientPacket(messageName, response.toByteArray());
    }
    private ContentPackage46() {}
}
