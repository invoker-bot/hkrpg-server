package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GmActivityCatalogTest {
  @TempDir Path directory;

  void fixture(String rows) throws Exception {
    Files.createDirectories(directory.resolve("ExcelOutput"));
    Files.createDirectories(directory.resolve("TextMap"));
    Files.writeString(directory.resolve("ExcelOutput/ActivityPanel.json"),"""
        [{"PanelID":10,"TitleName":{"Hash":1},"UnlockConditions":"[PlayerLevel:21]"},
         {"PanelID":11,"TabName":{"Hash":2},"UIPrefab":"UI/Quest/Widget/RoleTrialPanel.prefab"},
         {"PanelID":12,"TitleName":{"Hash":3}}]
        """);
    Files.writeString(directory.resolve("TextMap/TextMapCHS.json"),"""
        {"1":"<color=#fff>往期活动</color>","2":"角色试用","3":"常驻活动","999":"无关文字"}
        """);
    Files.writeString(directory.resolve("ExcelOutput/ActivityConfig.json"),rows);
  }

  @Test void explicitRelationsMergeResidentModulesAndNeverInventPanelOrReleaseHistory() throws Exception {
    fixture("""
        [{"ActivityID":9,"ActivityPanelID":10,"ResidentModuleList":[103,101],"ActivityModuleIDList":[102]},
         {"ActivityID":10,"ActivityModuleIDList":[104]},
         {"ActivityID":11,"ActivityPanelID":11,"ActivityModuleIDList":[105]},
         {"ActivityID":12,"ActivityPanelID":12,"ResidentModuleList":[106]},
         {"ActivityID":13,"ActivityPanelID":12}]
        """);
    var rows=GmActivityCatalog.load(directory);
    assertEquals(List.of(9,10,11,12,13),rows.stream().map(GmActivityCatalog.Template::id).toList());
    var event=rows.getFirst();
    assertEquals("往期活动",event.name());assertEquals("event",event.type());
    assertEquals(List.of(101,102,103),event.modules());assertEquals(Set.of(101,103),event.resident());
    assertTrue(event.manageable());assertEquals("[PlayerLevel:21]",event.unlockConditions());
    assertEquals(0,rows.get(1).panelId());assertFalse(rows.get(1).manageable());
    assertEquals("trial",rows.get(2).type());assertFalse(rows.get(2).manageable());
    assertTrue(rows.get(2).note().contains("卡池"));
    assertEquals("resident",rows.get(3).type());assertTrue(rows.get(3).manageable());
    assertFalse(rows.get(4).manageable());
  }

  @Test void sharedPanelModuleMakesBothOwnersReadOnlyButDistinctModulesAreIndependent() throws Exception {
    fixture("""
        [{"ActivityID":1,"ActivityPanelID":10,"ActivityModuleIDList":[101,102]},
         {"ActivityID":2,"ActivityPanelID":10,"ResidentModuleList":[101]},
         {"ActivityID":3,"ActivityPanelID":12,"ActivityModuleIDList":[103]}]
        """);
    var rows=GmActivityCatalog.load(directory);
    assertFalse(rows.getFirst().manageable());assertFalse(rows.get(1).manageable());
    assertTrue(rows.getFirst().note().contains("共享"));assertTrue(rows.get(2).manageable());
  }

  @Test void differentPanelsCannotIndependentlyControlTheSameLoginModule() throws Exception {
    fixture("""
        [{"ActivityID":1,"ActivityPanelID":10,"ActivityModuleIDList":[1008001]},
         {"ActivityID":2,"ActivityPanelID":12,"ActivityModuleIDList":[1008001]}]
        """);
    var rows=GmActivityCatalog.load(directory);
    assertTrue(rows.stream().noneMatch(GmActivityCatalog.Template::manageable));
    assertTrue(rows.stream().allMatch(row->row.note().contains("共享")));
  }

  @Test void duplicateIdentityOrModuleAndMissingResourcesCannotProduceWritableCatalog() throws Exception {
    for(var rows:List.of("[{\"ActivityID\":1},{\"ActivityID\":1}]",
        "[{\"ActivityID\":1,\"ActivityModuleIDList\":[1,1]}]")){
      fixture(rows);assertThrows(Exception.class,()->GmActivityCatalog.load(directory));
    }
    Files.delete(directory.resolve("ExcelOutput/ActivityPanel.json"));
    assertThrows(java.io.IOException.class,()->GmActivityCatalog.load(directory));
  }
}
