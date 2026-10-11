package org.hkrpg.server.gameplay;

import java.util.*;

/** Source data only: TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b. */
final class TimeRelayFlameSources {
  static final int GOAL = 6029856, UNLOCK = 111120;
  static final Map<String, String> ROWS =
      merged(
          WorldChestSources.ROWS,
          Map.ofEntries(
              Map.entry(
                  "CycleQuest:6029856",
                  "42bd56573834bfc558f7d69ce3094153ca983f1b4f30def8cb3845d05110b318"),
              Map.entry(
                  "FinishWay:6029856",
                  "b18d357bcfcb12a8bd82d0ba57186d260058a4ee857e8882e14d4bb3f60bacf7"),
              Map.entry(
                  "InteractConfig:111120",
                  "7370e9210d0a44e178335c2356bb6e159919ccda38e1d86640ed02263012c185"),
              Map.entry(
                  "MazeChest:20411654",
                  "1e26372a57180215a37bedf22f550702f45d97fbb16e79033dc72ddf02d7e227"),
              Map.entry(
                  "MazeChest:20411655",
                  "80c065e23e9c8a5232be6596ca02b4d32d132721a31f2ef93dbc2b1903325513"),
              Map.entry(
                  "MazeChest:20411656",
                  "85a0ac70701deefe3950126141d42e40af209fde8399c4cfe339a3ec683ccc08"),
              Map.entry(
                  "MazeChest:20411657",
                  "ce4237edb5ff3bfc555c6393a93e31d973274ad3f67807798404938202c619d7"),
              Map.entry(
                  "MazeProp:104046",
                  "70af4013697f52c4456303d8d63e622766b12488fc27aab46e1fe8b2a573cee4"),
              Map.entry(
                  "QuestData:6029856",
                  "7d4409089c5edb8e681e3ae1274070009f4bd6d1edb2c4fa1f524bbedb800546"),
              Map.entry(
                  "QuestTimeLimitConfig:6029856",
                  "dec368b343f8e4d0eb72fe6c2ab6faadf64b43dcec22361c7de540a0c704ca1e"),
              Map.entry(
                  "RewardData:3129016",
                  "43cb4d930af2eee80490f06178cd4a95cae68c8f5929d8e3240286a6682d91c5")));
  static final Map<String, String> FILES =
      merged(
          WorldChestSources.FILES,
          Map.ofEntries(
              Map.entry(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G231.json",
                  "c3da2071a24861db338121a1ba5a19b79a4030bb4079d6ccc91d94be959eb291"),
              Map.entry(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G232.json",
                  "94d7660e67a49d592ead858c2773abf23a88e84834f89a6b6ecb3c9db8fe058f"),
              Map.entry(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G233.json",
                  "03dd67f87514b0e58eaf10f3f7edffca3f01a7a408e44c5741d5344e68325645"),
              Map.entry(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G234.json",
                  "f8d2afccbc211810ea8cbb3d2896e8105598a4b001498fad827c336ff3ae3dd4")));
  static final Map<Integer, WorldChestSources.Location> PUZZLES =
      Map.ofEntries(
          Map.entry(
              20411654,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  231,
                  300001,
                  104046,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G231.json",
                  Set.of(0, 1))),
          Map.entry(
              20411655,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  232,
                  300001,
                  104046,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G232.json",
                  Set.of(0, 1))),
          Map.entry(
              20411656,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  233,
                  300001,
                  104046,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G233.json",
                  Set.of(0, 1))),
          Map.entry(
              20411657,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  234,
                  300001,
                  104046,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G234.json",
                  Set.of(0, 1))));
  static final Map<Integer, WorldChestSources.Location> LOCATIONS =
      merged(WorldChestSources.LOCATIONS, PUZZLES);

  private static <K, V> Map<K, V> merged(Map<K, V> original, Map<K, V> extra) {
    var result = new HashMap<K, V>(original);
    extra.forEach(
        (k, v) -> {
          if (result.putIfAbsent(k, v) != null)
            throw new IllegalStateException("Source identity collision");
        });
    return Map.copyOf(result);
  }

  private TimeRelayFlameSources() {}
}
