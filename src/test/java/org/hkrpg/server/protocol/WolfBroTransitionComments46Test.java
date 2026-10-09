package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.GroupInfo;
import org.junit.jupiter.api.Test;

/** Descriptive source comments must survive parsing without changing accepted state edges. */
class WolfBroTransitionComments46Test {
  @Test
  void originalCommentsSurviveTheBundledResourceParsing() throws Exception {
    var fixture = new WolfBro46Test();
    fixture.prepare();
    try {
      var rows = fixture.current.getFloorInfo().getGroups().get(156).getGroupStateList();
      var json = ResourceLoader.getGson().toJsonTree(rows.get(1)).getAsJsonObject();
      assertTrue(json.has("Comment"));
      assertEquals("变小", json.get("Comment").getAsString());
      assertEquals(
          "变大",
          ResourceLoader.getGson()
              .toJsonTree(rows.get(2))
              .getAsJsonObject()
              .get("Comment")
              .getAsString());
    } finally {
      fixture.restore();
    }
  }

  @Test
  void commentsAndStateNamesRemainIndependentOriginalJsonValues() {
    var source =
        JsonParser.parseString(
            "{\"GroupStateList\":[{\"SrcState\":1,\"DstState\":2,\"StateName\":{\"label\":\"small\"},\"Comment\":[\"说明\",4]}]}");
    var parsed = ResourceLoader.getGson().fromJson(source, GroupInfo.class);
    var roundTrip = ResourceLoader.getGson().toJsonTree(parsed.getGroupStateList().getFirst());
    assertEquals(source.getAsJsonObject().getAsJsonArray("GroupStateList").get(0), roundTrip);
  }

  @Test
  void existingControlSignaturesRemainByteCompatible() throws Exception {
    var fixture = new WolfBro46Test();
    fixture.prepare();
    try {
      fixture.start();
      assertEquals(
          "156:WolfBroGroup:[StateTransition[SrcState=0, DstState=0, StateName=null],"
              + " StateTransition[SrcState=1, DstState=2, StateName=null],"
              + " StateTransition[SrcState=2, DstState=1, StateName=null]]",
          fixture.session.actor.getGameplay().getWolfBro().getRuns().get(3).getControlSignature());
    } finally {
      fixture.restore();
    }
  }

  @Test
  void descriptiveCommentChangesDoNotCreateNewGameplayTransitions() throws Exception {
    var fixture = new WolfBro46Test();
    fixture.prepare();
    try {
      fixture.start();
      var group = fixture.current.getFloorInfo().getGroups().get(156);
      var source = ResourceLoader.getGson().toJsonTree(group).getAsJsonObject();
      source
          .getAsJsonArray("GroupStateList")
          .get(1)
          .getAsJsonObject()
          .addProperty("Comment", "仅修改说明，不添加第三状态");
      var parsed = ResourceLoader.getGson().fromJson(source, GroupInfo.class);
      parsed.setId(156);
      fixture.current.getFloorInfo().getGroups().put(156, parsed);
      assertEquals(0, fixture.code(fixture.request("GetWolfBroGameDataCsReq", 3)));
      var control = new WolfBroControl46Test();
      control.fixture = fixture;
      assertEquals(0, fixture.code(control.change(2)));
      assertNotEquals(0, fixture.code(control.change(3)));
    } finally {
      fixture.restore();
    }
  }
}
