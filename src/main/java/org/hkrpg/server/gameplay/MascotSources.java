package org.hkrpg.server.gameplay;

import java.util.*;

/** Source bindings: TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b. */
final class MascotSources {
  static final int MONSTER = 104102, COIN = 104159;
  static final List<Integer> GOALS = List.of(6029853, 6029854, 6029855);

  // The original checkout preserves numeric literals that the first fixture re-encoded.
  // Accept only these verified alternate file digests; keep FILES as the saved receipt identity.
  private static final Map<String, String> ORIGINAL_FILE_ENCODINGS =
      Map.of(
          "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G251.json",
          "29aa774a0f9e4d84106a796432a05991c44608da93770d79b503b8980f7662bc",
          "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G206.json",
          "e75ee5db52f20f24959acd0acb0c091c1aaea1af32bf8cb61c185234122dca6c");

  static boolean originalFileEncoding(String path, String expected, String observed) {
    return expected.equals(FILES.get(path)) && observed.equals(ORIGINAL_FILE_ENCODINGS.get(path));
  }

  record Control(
      int chest,
      int plane,
      int floor,
      int group,
      int rewardGroup,
      String path,
      String savedValue,
      int mission,
      int capture,
      int battle,
      int talk) {}

  static final Map<String, String> ROWS =
      Map.ofEntries(
          Map.entry(
              "CycleQuest:6029853",
              "be1118bd4f27c98c647741b0fab352fa81376d40ad32f3de565ffd126672b062"),
          Map.entry(
              "CycleQuest:6029854",
              "fd3325519e03bf47582bdf7b2681f16145e6fa5d5202a033f8728e623a7f1c99"),
          Map.entry(
              "CycleQuest:6029855",
              "26f9f4814cd9944764ec6c630b17c730198e9b66c3c1d2fc6119bb90eb1def43"),
          Map.entry(
              "FinishWay:6029853",
              "5f93c57e677f7e8ae363bcd05c3ffc7b772d39bc38674fb5b27b83f2f8e191d6"),
          Map.entry(
              "FinishWay:6029854",
              "9cb4241418c107fc173b0819cca6e21e1c04fe3aa176a857bfa92154627f2f4f"),
          Map.entry(
              "FinishWay:6029855",
              "6ce3fb4d827a55e24210a756333f9e4c1a19e5c865ab661878458050c55111b6"),
          Map.entry(
              "MainMission:1040101",
              "e69d40bf815a8619327e673aae3aaba31ce26f1525fd2bbea24328337509128e"),
          Map.entry(
              "MainMission:4015704",
              "cd24e8c9f2252d309edf910c60c64bca7d978af7228bc64a7752904d7f0005f6"),
          Map.entry(
              "MainMission:4015705",
              "48068a9953d07c86f2108075a753be7ccc9185770c444ec18ae71ad71963ee96"),
          Map.entry(
              "MainMission:4015706",
              "48e2c7debe22d5599f131c098f597364cce9c120518330a57eba687560d10adc"),
          Map.entry(
              "MapEntrance:2041201",
              "64aadd406518c63b5b51e119a0dff29b4447a2052e1eb7ff99fb8460a03d8ce4"),
          Map.entry(
              "MazeChest:20411614",
              "22e41a30092ad1e0e3f1c2f4147732fa22c37034c5101a8b1b183aa4e91284ff"),
          Map.entry(
              "MazeChest:20411625",
              "8a148e930aa23357db73ecb1f30a9a382ea09cef66d23ecf9bda2539a4331357"),
          Map.entry(
              "MazeChest:20412620",
              "ffe2761531b1101e3f7866de6a24cf0bb43925b517f4b149df2cbe208ded4597"),
          Map.entry(
              "MazePlane:20412",
              "92f46c6b8537afd9bec0e83b81e095032636d5c3bb1243aaab544a00d79c1892"),
          Map.entry(
              "MazeProp:100", "e5ccfb571c9573aa85bf276fbf499dea189c923b7ab66a5bcac83eb0f9c09a0a"),
          Map.entry(
              "MazeProp:104102",
              "f249c20667d0530cfd57201fa526fb771942d9a9eeeaa109babed47c46d664bf"),
          Map.entry(
              "MazeProp:104159",
              "89884903961c6873c48832110f7afb11c725806a2c826d2f0294fc701dc835ad"),
          Map.entry(
              "MazeProp:60403", "847dac960e7f588c05e23a0a104b47282d7184985da761ca10dc66d085a95f6d"),
          Map.entry(
              "QuestData:6029853",
              "bde24ff164bab0993139165bd245780b6c471f5de0d1d337bbb1656bc7ac1ba5"),
          Map.entry(
              "QuestData:6029854",
              "9df897c0b78220dbf159dfc3e29d8838fd950a0058f588b6e6796e15f7b58fc5"),
          Map.entry(
              "QuestData:6029855",
              "ed056d31b9d1938a247ba8455a92eebb2025c70940e02c306b6731284e109b06"),
          Map.entry(
              "QuestTimeLimitConfig:6029853",
              "f66619a57c7d8dd8fdba1f0645561080220591e012fb5be99d1b50bce239d0b6"),
          Map.entry(
              "QuestTimeLimitConfig:6029854",
              "8d2dde22307143b0ef7593a26db3f8093bbc22cda581a40b244d2d46e94f07cc"),
          Map.entry(
              "QuestTimeLimitConfig:6029855",
              "da16e01521a523678523d5c266deec911d285e621b2aae9d4f098bbc6e1ab91d"),
          Map.entry(
              "RewardData:14015704",
              "815955d3dfcb3d903e9023fb733cc829aabb4b8984310b898d39e456f75d0ac5"),
          Map.entry(
              "RewardData:14015705",
              "f9cc4500d40020df36fbfaf42377fced95dd6b0be9e88c48c57dbd6894f3642b"),
          Map.entry(
              "RewardData:14015706",
              "a3053272af14dcab87dc7bcd41dea3018dbd74e483bbf51ef45900fd3aefb30a"),
          Map.entry(
              "RewardData:3129013",
              "09a5edef531ca8e54af3cdf20674657b6910592226a4e234eb0371769055c9d8"),
          Map.entry(
              "RewardData:3129014",
              "b8f3d49b324dae88a0c0ec01405a477065d7fb1a25bf813ff8d7de7c52c225a8"),
          Map.entry(
              "RewardData:3129015",
              "31308ad251a623e80c107bde51931e9239106ce263f45f064944fea3f7d548ed"));
  static final Map<String, String> FILES =
      Map.ofEntries(
          Map.entry(
              "Config/Level/Mission/4015704/MissionInfo_4015704.json",
              "8f56181a8b2847ef9f76cd080adcdc860750e9b9c652dc2920598ac0e4c8b057"),
          Map.entry(
              "Config/Level/Mission/4015704/MissionInfo_4015704.partial.json",
              "bb0d7592c6afe726ba4fe16d3adf1cdd74ddf45cb2cb44cc29ed4216e2cc7ba6"),
          Map.entry(
              "Config/Level/Mission/4015704/MissionInfo_4015704.partial.layout.json",
              "16f0012ba4b3a5a6a1b4161316c543ff6bcae409cf093b978f72e0c9febdff79"),
          Map.entry(
              "Config/Level/Mission/4015705/MissionInfo_4015705.json",
              "bb32f63828bca2e2735ea6584b9909ab1fa9d11a81a99a1b85c6d2bb69af4893"),
          Map.entry(
              "Config/Level/Mission/4015705/MissionInfo_4015705.partial.json",
              "4257b08e04e6c0495f14d5cd0fe3aace06a2a3df74a4be8e208a2859a6fabb1b"),
          Map.entry(
              "Config/Level/Mission/4015705/MissionInfo_4015705.partial.layout.json",
              "85fc2307602084b405dace31052d6897c5e9892eb3eb29dd68f5e9bd729a520e"),
          Map.entry(
              "Config/Level/Mission/4015706/MissionInfo_4015706.json",
              "9c8ef7a456cd9f3c5f48dcb31ee7847c2c4f4a04685144a9662f33299f3b0586"),
          Map.entry(
              "Config/Level/Mission/4015706/MissionInfo_4015706.partial.json",
              "b934fdcf6808b829fe0a5d4ee4dc975f31bcaaad7e2e260a3fec6f0216db6d2c"),
          Map.entry(
              "Config/Level/Mission/4015706/MissionInfo_4015706.partial.layout.json",
              "ef9c038d9c27556231e8d65abbcea393a4d596b5ad4526f7d7c7de404a31330e"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G251.json",
              "02e3d2ea1469da8c83197acfe21c580cdd775ad271e8ccae40d250b785800fd9"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G252.json",
              "e52fb5567fb2b1722df85818deef6695d5b721b2821a2d6c258f027478c25553"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G253.json",
              "ac1a4ed221a5064aea9e18e5f1c8fb26bafcd00484a76c399686601ed07e90ef"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G254.json",
              "8a1d9ab6f247695e7ef8daf9b3f9c4edb32fff7817f44516cb13fcb14cc857c9"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G256.json",
              "4f1d6ee78795c5b62564906e795fbef2560299aba55ef333c050db0c6ef035d9"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G257.json",
              "cc2f479b495af1894cfa26f9f1239a258b29c0486c08174ef5b0d23e4c84ce63"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G206.json",
              "45291a5c875e4bec423b0537ee0603a27a375e0a6053609bff584620e781977a"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G207.json",
              "f9db013f33d08dae55496f14f2ae72d92f2432eba36aacd923ed47b8cf99c3e8"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G214.json",
              "ca3abf66ffaa75321754d61b1ae11b80fe1695021c43a3fae1b5b7ffbf869fb3"));
  static final Map<Integer, Control> CONTROLS =
      Map.ofEntries(
          Map.entry(
              20411614,
              new Control(
                  20411614,
                  20411,
                  20411001,
                  251,
                  252,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G251.json",
                  "FSV_QTEGotchard_RewardGet_01",
                  4015704,
                  401570401,
                  0,
                  401570402)),
          Map.entry(
              20411625,
              new Control(
                  20411625,
                  20411,
                  20411001,
                  253,
                  254,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G253.json",
                  "FSV_QTEGotchard_RewardGet_02",
                  4015705,
                  401570501,
                  401570502,
                  401570503)),
          Map.entry(
              20412620,
              new Control(
                  20412620,
                  20412,
                  20412001,
                  206,
                  207,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G206.json",
                  "FSV_QTEGotchard_RewardGet_01",
                  4015706,
                  401570601,
                  0,
                  401570602)));
  static final Map<Integer, WorldChestSources.Location> PUZZLES =
      Map.ofEntries(
          Map.entry(
              20411614,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  252,
                  300001,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G252.json",
                  Set.of(0, 1))),
          Map.entry(
              20411625,
              new WorldChestSources.Location(
                  20411,
                  20411001,
                  254,
                  300001,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G254.json",
                  Set.of(0, 1))),
          Map.entry(
              20412620,
              new WorldChestSources.Location(
                  20412,
                  20412001,
                  207,
                  300001,
                  60403,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G207.json",
                  Set.of(0, 1))));
  static final Map<Integer, WorldChestSources.Location> LOCATIONS =
      GoldenSpearSources.merge(GoldenSpearSources.LOCATIONS, PUZZLES);

  private MascotSources() {}
}
