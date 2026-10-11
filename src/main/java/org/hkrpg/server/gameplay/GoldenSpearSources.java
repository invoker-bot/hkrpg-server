package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.PropState;
import java.util.*;

/** Source data only: TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b. */
final class GoldenSpearSources {
  static final int GOAL = 6029858, SHIELD = 104010, SPEAR = 104023;
  static final int[] CONFIGS = {1010, 101101, 1010102, 1020002};
  static final List<PropState> STATES =
      List.of(
          PropState.Closed,
          PropState.Open,
          PropState.CustomState01,
          PropState.CustomState02,
          PropState.Locked);
  static final Map<String, String> ROWS =
      Map.ofEntries(
          Map.entry(
              "CycleQuest:6029858",
              "432454b7603b32145a0fe81dbbf77d4eff9b125d67adb9f120da18c63b9e3c81"),
          Map.entry(
              "FinishWay:6029858",
              "50116e8cb1093c60801c3a5376df3e41bb7224b7eea4938f625dd93cf2961563"),
          Map.entry(
              "InteractConfig:1010",
              "4a2ed9a349ffe1f07aa3046a1fe33805fe7346685ecbacfebbcc8812d964b8ea"),
          Map.entry(
              "InteractConfig:1010102",
              "334f9ae6061e64199d319eb81ee1f422c4c36e2f79d688d56f525db5fe41d54b"),
          Map.entry(
              "InteractConfig:101101",
              "f9611eb8cba1ae2639d51b2885a5c50b2755072366ccbd2f32d96fc724eaea5f"),
          Map.entry(
              "InteractConfig:1020002",
              "7229de40bd949e23ed945db85412e351ed878c914929f307635f2b94beee2407"),
          Map.entry(
              "ItemConfig:233", "cafc3805916adad606b3a70f5fdc3edf906b9fac151c478dc10b90caf79f3f30"),
          Map.entry(
              "MazeChest:20411666",
              "28763833438bce2087495a904c7f47b366fdf0a8e3b935c54fde3e02d0825853"),
          Map.entry(
              "MazeChest:20411667",
              "9c17dc121f3e771b01e1c81b0685df345a8ceac994d5d52fcbe6772a0c9e9067"),
          Map.entry(
              "MazeProp:104010",
              "bd497b7477b04854103b84d01d3d88e1b11a291f8ac9e3e1253c8ebe20622e99"),
          Map.entry(
              "MazeProp:104023",
              "f72208fe48dcaa5f6b5f19e55c40b7eb3bcdb1e1e73d516919828c74ebf8f115"),
          Map.entry(
              "MazeProp:211", "fb573d889cc244abac375ebf1e88b969e2031bb1d9157130d63e754a7c3b2891"),
          Map.entry(
              "QuestData:6029858",
              "797f21445382c797a89be6a7a6f740a7111636ebfb88499f6488f67cdfc4968b"),
          Map.entry(
              "QuestTimeLimitConfig:6029858",
              "0e829d28641d990f837ba732c480d70d744c4a75ae6d84cb4a5e9872b311d140"),
          Map.entry(
              "RewardData:3129018",
              "7300ba7357093469f46fe310e5300828288c2fa64ff5f6be5455073dfdfb8a8e"));
  static final Map<String, String> FILES =
      Map.ofEntries(
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G273.json",
              "19f58fa3e06ed5d790268ac4a75af1795ee0116181d340414d55b749862f965e"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G274.json",
              "a1b05379447b58367e15d88ebc569757157728c98222f793f60c3f0f9c0c2a8c"));
  static final Map<Integer, WorldChestSources.Location> PUZZLES =
      Map.ofEntries(
          Map.entry(
              20411666,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  273,
                  300003,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G273.json",
                  Set.of(0, 1))),
          Map.entry(
              20411667,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  274,
                  300003,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G274.json",
                  Set.of(0, 1))));
  static final Map<Integer, WorldChestSources.Location> LOCATIONS =
      merge(TimeRelayFlameSources.LOCATIONS, PUZZLES);

  static <K, V> Map<K, V> merge(Map<K, V> old, Map<K, V> extra) {
    var out = new HashMap<K, V>(old);
    extra.forEach(
        (k, v) -> {
          var prev = out.putIfAbsent(k, v);
          if (prev != null && !prev.equals(v))
            throw new IllegalStateException("Source identity collision");
        });
    return Map.copyOf(out);
  }

  private GoldenSpearSources() {}
}
