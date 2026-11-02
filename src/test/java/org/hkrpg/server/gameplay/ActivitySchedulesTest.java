package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActivitySchedulesTest {
  @TempDir Path directory;
  List<GameplayDefinitions.Window> previous;
  @BeforeEach void remember(){previous=List.copyOf(GameplayDefinitions.windows);}
  @AfterEach void restore(){GameplayDefinitions.replaceActivityWindows(previous);}
  JsonObject root(String rows){return JsonParser.parseString("{\"activities\":"+rows+"}").getAsJsonObject();}

  @ParameterizedTest @ValueSource(strings={
      "null", "{}", "[{}]", "[{\"panelId\":0,\"moduleId\":1,\"beginTime\":0,\"endTime\":1}]",
      "[{\"panelId\":1,\"moduleId\":1,\"beginTime\":0,\"endTime\":0}]",
      "[{\"panelId\":1,\"moduleId\":1,\"beginTime\":-1,\"endTime\":1}]",
      "[{\"panelId\":1,\"moduleId\":1,\"beginTime\":0.5,\"endTime\":1}]",
      "[{\"panelId\":1,\"moduleId\":2147483648,\"beginTime\":0,\"endTime\":1}]",
      "[{\"panelId\":1,\"moduleId\":1,\"beginTime\":0,\"endTime\":9223372036854775808}]",
      "[{\"panelId\":1,\"moduleId\":1,\"beginTime\":0,\"endTime\":1,\"disabled\":\"true\"}]",
      "[{\"panelId\":1,\"moduleId\":1,\"beginTime\":0,\"endTime\":1},{\"panelId\":1,\"moduleId\":1,\"beginTime\":1,\"endTime\":2,\"disabled\":true}]"
  }) void malformedSchedulesAreRejectedAsAWhole(String rows){
    assertThrows(RuntimeException.class,()->ActivitySchedules.parse(root(rows)));
  }

  @Test void startupHonorsDisabledRowsAndDoesNotMutateOldPublishedSnapshot() throws Exception {
    var old=new GameplayDefinitions.Window(9,9,0,Long.MAX_VALUE);
    GameplayDefinitions.replaceActivityWindows(List.of(old));var published=GameplayDefinitions.windows;
    var path=directory.resolve("Gameplay46.json");
    Files.writeString(path,"""
        {"activities":[{"panelId":1,"moduleId":1,"beginTime":0,"endTime":9999999999,"disabled":true},
                       {"panelId":1,"moduleId":2,"beginTime":0,"endTime":9999999999}]}
        """);
    ActivitySchedules.load(path);
    assertEquals(List.of(new GameplayDefinitions.Window(1,2,0,9999999999L)),GameplayDefinitions.windows);
    assertEquals(List.of(old),published);
    Files.writeString(path,"broken");assertThrows(java.io.IOException.class,()->ActivitySchedules.load(path));
    assertEquals(2,GameplayDefinitions.windows.getFirst().moduleId());
  }

  @Test void existingDirectoryCannotBeInterpretedAsAnEmptyConfig() {
    GameplayDefinitions.replaceActivityWindows(List.of(new GameplayDefinitions.Window(1,2,0,10)));
    var before=GameplayDefinitions.windows;
    assertThrows(java.io.IOException.class,()->ActivitySchedules.load(directory));
    assertSame(before,GameplayDefinitions.windows);
  }
}
