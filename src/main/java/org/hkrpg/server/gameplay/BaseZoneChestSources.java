package org.hkrpg.server.gameplay;

import java.util.Map;
import java.util.Set;

/**
 * Original Base Zone exploration chests;
 * TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b.
 */
final class BaseZoneChestSources {
  static final String GROUP =
      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G6.json";
  static final Map<String, String> ROWS =
      Map.ofEntries(
          Map.entry(
              "MazeChest:20001601",
              "f833c4b25b994b66b30b5741048ba55b8e96d24a559baa3ab85a431f5eae3902"),
          Map.entry(
              "MazeChest:20001602",
              "ab3c7c3f2a32ab68cfe66cb35825ac716ef568de8418736f349236e140d77d1f"),
          Map.entry(
              "MazeChest:20001603",
              "c42942992ac44b829d1953779f2e44abb5e2079af066565f9f8cab027b2ab258"),
          Map.entry(
              "MazeChest:20001604",
              "d7ecf6ab6d6bba99d23c4649ede813836a236e76a9ef2f47e87bae71ae2d60fe"),
          Map.entry(
              "MazeChest:20001605",
              "666084ffbcd3ac0f0070ed0e2cbb89dd00d094ddf7cfa1591edc391cc1b016a2"),
          Map.entry(
              "MazeChest:20001606",
              "3b4ede79cb0497da071b8e28290c1c96e9abbe6a0233587d2f56617671a2b92f"),
          Map.entry(
              "MazeProp:60001", "84aa72357747b692f3704e6ca344b30dc2e12e7c44a17861f073775017c5cf77"),
          Map.entry(
              "MazePlane:20001",
              "0b488825bfb362ec705c026ee3f0d0254a1a072c321c9c71e2815fbb664394b5"),
          Map.entry(
              "MapEntrance:2000101",
              "5b7f406b6eb7e8764baaf7df2063d484a30864f1ac7c240c91e4d4e9cb095e91"),
          Map.entry(
              "MapEntrance:200010102",
              "9115a08b674ef6cad7df6f78f00daa2fccdd1e43d13f7a6bc9389b570f2e42b4"),
          Map.entry(
              "MapEntrance:200010103",
              "8759b29d16d693768450db6da0387533dc561f9ec7d05a0921b63afd8d054266"),
          Map.entry(
              "MapEntrance:200010104",
              "893b7f37d357845c785c57e0f94934100c9e7097ad64bc662dae7482784c0cea"),
          Map.entry(
              "ItemConfig:120000",
              "f497bc7c0bb14b0e771f14b908ae4282d5db0263c209bdbcf49ad9f50105c52a"),
          Map.entry(
              "MainMission:1000401",
              "406cd305b2d59bcb2fde80352436e572ed9c4b6430e3efd6c4464099a447b1eb"));
  static final Map<String, String> FILES =
      Map.of(GROUP, "e5ffffdfa4afaeaa2945c30dcbc4a839df1f8e317d0410b2447ed92d18a38269");
  static final Map<Integer, WorldChestSources.Location> LOCATIONS =
      Map.ofEntries(
          Map.entry(
              20001601,
              new WorldChestSources.Location(20001, 20001001, 6, 300001, 60001, GROUP, Set.of(0))),
          Map.entry(
              20001602,
              new WorldChestSources.Location(20001, 20001001, 6, 300002, 60001, GROUP, Set.of(0))),
          Map.entry(
              20001603,
              new WorldChestSources.Location(20001, 20001001, 6, 300003, 60001, GROUP, Set.of(0))),
          Map.entry(
              20001604,
              new WorldChestSources.Location(20001, 20001001, 6, 300004, 60001, GROUP, Set.of(0))),
          Map.entry(
              20001605,
              new WorldChestSources.Location(20001, 20001001, 6, 300006, 60001, GROUP, Set.of(0))),
          Map.entry(
              20001606,
              new WorldChestSources.Location(20001, 20001001, 6, 300005, 60001, GROUP, Set.of(0))));

  private BaseZoneChestSources() {}
}
