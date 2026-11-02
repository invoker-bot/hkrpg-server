import com.google.gson.JsonObject;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.Unpooled;
import java.util.*;

/** Synthetic original hotel talk followed by the real init transfer on the same KCP connection. */
public class NativeLiteralSceneTransferProbe extends NativeSilvermaneBattleProbe {
  DynamicMessage hotelScene(int floor) throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq", Map.of(), true), "scene");
    require(number(scene, "plane_id") == 10101 && number(scene, "floor_id") == floor,
        "Wrong hotel floor");
    return scene;
  }

  Object position(DynamicMessage scene) {
    int leader = number(scene, "leader_entity_id");
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "entity_id") == leader)
          return field((DynamicMessage) field(entity, "motion"), "pos");
      }
    throw new IllegalStateException("Leader missing");
  }

  void hotelGoals() throws Exception {
    cityChapterGoals(List.of(101020201, 101020202), 101020203);
  }

  Map<String,Object> finish(Map<String,Object> result) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(
        protocol.clientCommand("PlayerLogoutCsReq"), new byte[0], new byte[0]));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
    Thread.sleep(300);
    var summary = new LinkedHashMap<>(result);
    summary.put("requests", requests);
    return summary;
  }

  @Override Map<String,Object> execute(JsonObject control) throws Exception {
    var token = call("PlayerGetTokenCsReq", Map.of("account_uid", control.get("accountUid").getAsString(),
        "token", control.get("comboToken").getAsString(), "platform", 1), true);
    int uid = number(token, "uid");
    call("PlayerLoginCsReq", Map.of(), true);
    call("PlayerLoginFinishCsReq", Map.of(), true);
    String mode = control.get("mode").getAsString();
    if (mode.equals("bootstrap")) return finish(Map.of("passed", true, "uid", uid));
    if (mode.equals("live")) {
      hotelScene(10101004);
      call("FinishTalkMissionCsReq", Map.of("sub_mission_id", 101020201,
          "talk_str", "Mission_101020201"), true);
      awaitTransfer(10101, 10101006);
    } else require(mode.equals("restored"), "Unknown probe mode");
    var before = hotelScene(10101006);
    hotelGoals();
    var position = position(before);
    for (int i = 0; i < 2; i++) {
      call("EnterSceneCsReq", Map.of("entry_id", 1010106, "teleport_id", 0), true);
      require(position.equals(position(hotelScene(10101006))), "Repeated request changed hotel anchor");
      hotelGoals();
    }
    return finish(Map.of("passed", true, "uid", uid,
        "sameSessionInitTransfer", mode.equals("live"), "followingInteractionStillPending", true,
        "repeatedRequestsKeepAnchor", true, "clientGameplayVerified", false));
  }

  public static void main(String[] args) { run(new NativeLiteralSceneTransferProbe(), args); }
}
