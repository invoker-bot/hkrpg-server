package org.hkrpg.server.gameplay;

import java.util.*;

/**
 * Whole rows/groups from TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b; references are
 * not runtime dependencies.
 */
final class TimelineActivitySources {
  static final Map<String, String> KEYS =
      Map.ofEntries(
          Map.entry("ActivityConfig", "ActivityID"),
          Map.entry("ActivityPanel", "PanelID"),
          Map.entry("ActivityQuestTimeLimitGroup", "QuestTimeLimitGroupID"),
          Map.entry("CycleQuest", "CycleID"),
          Map.entry("FinishWay", "ID"),
          Map.entry("FuncUnlockData", "UnlockID"),
          Map.entry("ItemConfig", "ID"),
          Map.entry("MainMission", "MainMissionID"),
          Map.entry("MapEntrance", "ID"),
          Map.entry("MazePlane", "PlaneID"),
          Map.entry("MazeProp", "ID"),
          Map.entry("QuestData", "QuestID"),
          Map.entry("QuestTimeLimitConfig", "QuestID"),
          Map.entry("RewardData", "RewardID"));
  static final Map<String, String> ROWS =
      Map.ofEntries(
          Map.entry(
              "ActivityConfig:30013",
              "92dfeae5be1c966530c080493af9a144318b92967e79831a1c062276a0e5b430"),
          Map.entry(
              "ActivityPanel:30013",
              "4faeaef990e825d584f07d089a37475079e7335d68c36e8bc32d7759a4ffd5ff"),
          Map.entry(
              "ActivityQuestTimeLimitGroup:22",
              "c5af486880cb1f920e91db04bae8e3b8e21fed604129403e6767246d421b6b3e"),
          Map.entry(
              "CycleQuest:6029846",
              "48fae19612ec3a501e47bcd3a358c78fbabbf5709b270a71630ad6469bd93f1c"),
          Map.entry(
              "CycleQuest:6029847",
              "104b5b832f92c7a8509ee60bf88aab8c05847661b1fc5c86a6fdfad2349cd0ef"),
          Map.entry(
              "CycleQuest:6029848",
              "7beedd33d205ff46a2a5bedf2d2f778f513be4ef6e9f8c7b96e765f2132997fd"),
          Map.entry(
              "CycleQuest:6029849",
              "b65667fb499a9f7d7e8e46412980fd6055a98fa2dd9a1432f9a19448c37364b2"),
          Map.entry(
              "FinishWay:6029846",
              "0769f47adc93df481fe1b2d5ee7bbe06c1d9204ac621834c2d32e7063ef5e2ea"),
          Map.entry(
              "FinishWay:6029847",
              "7d70bf8647047bd1a60236c127658caa24978fcba5e0815d905d48ea2dbe8b91"),
          Map.entry(
              "FinishWay:6029848",
              "e11c93804b9ff0cd589a3d8d6a7ecdd2b266d5a030d1ff1b07704ccb1713256f"),
          Map.entry(
              "FinishWay:6029849",
              "706ec3d210e54bc0973491e680375216076e01c2f22d7640daa291dec4b61e1c"),
          Map.entry(
              "FuncUnlockData:106001",
              "51b0213ca203fa5297c9f227c892eda1bcb58e7b6723f0aaa5e52ad7d45c54b9"),
          Map.entry(
              "FuncUnlockData:106002",
              "9e57a8bc4f5a027c83c246bd612a17dcd44b87de8fe0b829e95790d009486ea2"),
          Map.entry(
              "FuncUnlockData:106003",
              "94b0a02bc370148f063cfff6199bf919d4a5b0203b9d1a4bae00b65407aa9d1c"),
          Map.entry(
              "FuncUnlockData:106004",
              "a1c481ffbb7d52b51d775436f3e2913b6dd01904f1cf4fa03cc12a67eedbdd74"),
          Map.entry(
              "ItemConfig:1", "ae81c59f29957673ebc218333628663ec21c14ef7861361bed8b62d27da1d684"),
          Map.entry(
              "ItemConfig:110202",
              "b6bd2f45329d334adcff0bff2c1c7124c4f35c1aa2fb4dcd57bf002833c1686b"),
          Map.entry(
              "ItemConfig:2", "fdf8fd479ddcae80da77d33dafe0d7f7ef11e87c53fb27eb2f0e81d91fe39b30"),
          Map.entry(
              "ItemConfig:213", "b092cb576db4b71501e83759dd3c6a6abe81c44eb17ab36cf8382061710936a4"),
          Map.entry(
              "MainMission:1000510",
              "c794662b3d9fa2f2c0e3049da7ad0f5f436663ef50e23aa8d29c983db0b32964"),
          Map.entry(
              "MainMission:1040101",
              "e69d40bf815a8619327e673aae3aaba31ce26f1525fd2bbea24328337509128e"),
          Map.entry(
              "MainMission:1040106",
              "ddcff8ded13b3fe67c1157273cd9cee862acf6cf755207781f7b17d0a83cae96"),
          Map.entry(
              "MainMission:1040109",
              "b56dd5d4380c3bfbf89460e8babe4ca449ef004b2809cd678128f13d3af41c58"),
          Map.entry(
              "MainMission:1040111",
              "704adeba10836de665cee6972e01a93f4f7f1160f07586c6bbb1a4a5311f9edc"),
          Map.entry(
              "MapEntrance:1040101",
              "321014fe7e8a08a4738424ffef4e725ae10c2ab35e98e1cdb9dc957c26b2ac25"),
          Map.entry(
              "MapEntrance:1040152",
              "777b9636a7aa26659b2bba73d1ee297de1a3602d167b772c173801856c6fdd6b"),
          Map.entry(
              "MapEntrance:1040154",
              "2b5595c7b2b951238a73ae0c47dc5d4b217382458ada3d15fb7757f069237134"),
          Map.entry(
              "MapEntrance:1040155",
              "a299aca157a46b4dc3fc0a0a8059e76a27629fd5eaeba2001bc933df3eecb1aa"),
          Map.entry(
              "MazePlane:10401",
              "07888aaceff549b17e09598880efb23967c4b982a2d368767c665903d9b8ea1f"),
          Map.entry(
              "MazePlane:20411",
              "906d11e0e6c355a375819d3ab6d5a8a77477108954ae93b96406010adae50792"),
          Map.entry(
              "MazePlane:20412",
              "92f46c6b8537afd9bec0e83b81e095032636d5c3bb1243aaab544a00d79c1892"),
          Map.entry(
              "MazePlane:20413",
              "f0547a74062b6b9fdb843c36bafd40304c4e3e758f00ddc8f5c6bac9ee255ed7"),
          Map.entry(
              "MazeProp:104033",
              "b2c304ba3ff9a9abb33e4aaadb56bc421ca6100915c22b2794b1fa1055e9f800"),
          Map.entry(
              "MazeProp:104034",
              "48093a8c45138c30bb8ddf7a95006958f33678c88d93a67c8c80724f2ad6b770"),
          Map.entry(
              "MazeProp:104035",
              "99e2d06532098e2fbb49766f6eda806bc53b775f68667752d99db9ec97a115fb"),
          Map.entry(
              "MazeProp:104036",
              "255ec21ff22d414c196a48a447c96486a7abe9137c8c29a84e30fd9c30e5e8ef"),
          Map.entry(
              "MazeProp:104042",
              "c5f681ff50ed8cae4ca29f296858736e3a9c9136fbd36de23088b60280d56356"),
          Map.entry(
              "MazeProp:104043",
              "cc0045c5f9dddcbeb827836cc5ec6e627250f8e54ddf3c2bdd985d8e703f20fc"),
          Map.entry(
              "MazeProp:104045",
              "056e55ba43bc1aec9bc1d581dfc6c955a12b2359ed313ad32ac461274ad40cdd"),
          Map.entry(
              "MazeProp:104059",
              "a2e1062c0583fbed5f2b5618421ab6534d07fda89805c351ef0b79b13472185c"),
          Map.entry(
              "MazeProp:104067",
              "5ba95cc632ba0681514158b34410f09dfec16377ef037605c2ab5f6449a48bb5"),
          Map.entry(
              "MazeProp:104071",
              "6a59d2b602fcca80859ef620724d1f8bb82f78a16fe987e8315e14007293b23a"),
          Map.entry(
              "MazeProp:104072",
              "a178a05f0c92e6396059b430c3ac75a51baa8e504e7ae609cc30696d008ca723"),
          Map.entry(
              "MazeProp:104073",
              "4db15e12e6e7cd87868839c783e75c68ddb466ba66d9e81e3c6616916cc08574"),
          Map.entry(
              "QuestData:6029846",
              "66f5bea624f1d2ec6f96a86237bb797cf5811cfd76091b058efb13d2c461a4fd"),
          Map.entry(
              "QuestData:6029847",
              "a05e72019322a2957d400317ae0dccb7fb89d3cb01b9dd53a0ad2c67833b5b23"),
          Map.entry(
              "QuestData:6029848",
              "147628753dbd6911dc1860c1e6128f1e09dd454d53aac11a1e75d89752e5c64c"),
          Map.entry(
              "QuestData:6029849",
              "e260f03779efe45366e9071e9c6e04193dcdcbd20cd2e3ab9ee8267d041a01d7"),
          Map.entry(
              "QuestTimeLimitConfig:6029846",
              "fee944c5caf889a682b7424a06e9a8d59fe419c2e9aa5e58b37bb59fcfbade57"),
          Map.entry(
              "QuestTimeLimitConfig:6029847",
              "1939484d6f24c0fd6dd8ce8fc968de6f62a1ce9dad6ded8c2e24292bd3764854"),
          Map.entry(
              "QuestTimeLimitConfig:6029848",
              "4a7c3113f71cd42dc3975665330691ca10d438d6ce6255b4413d306e629348af"),
          Map.entry(
              "QuestTimeLimitConfig:6029849",
              "a3099f5d8712af8cbc12bbcc61a01b3dcf9c3174e486cffde613ca3144f2e264"),
          Map.entry(
              "RewardData:3129006",
              "cf866ff674f8f9057fecff8cf87689a53bfcc3c2216961f400eb91dfc7c7cc8e"),
          Map.entry(
              "RewardData:3129007",
              "219fe64206448f1f6169495e9259a152032850ecfb52b03a6bcf63f4492b3428"),
          Map.entry(
              "RewardData:3129008",
              "41d8742fd3a0014221ea9585d5d84f3a735e6e3f0ba4d1f7cf45adf4f1f9cbb3"),
          Map.entry(
              "RewardData:3129009",
              "a3ec07c2fc4a0e68b5583a470882612a7c3036e3fd618efa3b0e15eb64407559"));

  record Group(
      int plane, int floor, int id, String path, String source, List<Integer> dimensions) {}

  static final Map<String, List<Group>> GROUPS =
      Map.ofEntries(
          Map.entry(
              "10401:10401001:173",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173.json",
                      "fb11f5e89eac0523cf2d0ea048fde69193b3c52c4054b1e09599296aae7d8795",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1001.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1002.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1003.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1004.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1005.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1006.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1007.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1009.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      173,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G173_D1014.json",
                      "ba28f4e8000ed41a4001782caa3d1a8a0cf73f38ed5cf5952eedb76833e5dd38",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:242",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242.json",
                      "ebb1abaf29150877b4163d24bd189cd67f426f280874d8bf94440f449660b7d2",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1001.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1002.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1003.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1004.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1005.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1006.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1007.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1009.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      242,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G242_D1014.json",
                      "856d065a630530d26e70086ac1776cdbf8ae321fc77287d4a0c6474228e04a98",
                      List.of()))),
          Map.entry(
              "10401:10401001:258",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258.json",
                      "5bf8edac8e756342da9e8000d5eae85135391fe7147e61dff1726a47cfc111f6",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1001.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1002.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1003.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1004.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1005.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1006.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1007.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1009.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      258,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G258_D1014.json",
                      "10bb9480abc35ad7a4f05fdbc1523803213c38adf5e6ea1e7d588628620f75bf",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:259",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259.json",
                      "a858048cfa26f8244f3a8f2b70c77c2d7718c125fb4dc17a1a18663b6b576759",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1001.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1002.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1003.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1004.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1005.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1006.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1007.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1009.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      259,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G259_D1014.json",
                      "aec999e0b2502183e67b9acd02e63e1738b7d157982632ac1b3a3bc47773fdc2",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:260",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260.json",
                      "433bc176c941ca8e1280ff327249d740254bbfe9127df842897d0ce3ea40c125",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1001.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1002.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1003.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1004.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1005.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1006.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1007.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1009.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      260,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G260_D1014.json",
                      "c7c2fa9a5bd78e262f7091b06cc0dc4e83a1aa01e46e682f8115e9addd681610",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:261",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261.json",
                      "57dfec866dbd464aa72a1f97dfa4d356ea6500f546d8a06cccabb6be9a1353cf",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1001.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1002.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1003.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1004.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1005.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1006.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1007.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1009.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      261,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G261_D1014.json",
                      "9681f0e590845297c706c8060cb905cf2d3f750928f12ce8edc043e336033faf",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:262",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262.json",
                      "d8a5cc2a305a0e3ba8f60896f996f8349929c416eb4fc69bc11bf0e7751e5ac7",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1001.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1002.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1003.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1004.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1005.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1006.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1007.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1009.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      262,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G262_D1014.json",
                      "0ada9ead349f712da6b9fa156b40e22f430896445845eb6ed71d1e60c7c11f7a",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:263",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263.json",
                      "e3a1d4b6329df9a1b6600a774b74747debd630bca48a16b1b2e6e62798c16e6e",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1001.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1002.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1003.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1004.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1005.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1006.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1007.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1009.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      263,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G263_D1014.json",
                      "ac51fbd95c66209491679c9b32b10171c43df40217ab94c2ea4636c44b320f13",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:522",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      522,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G522.json",
                      "53d3894cb013f55e8d69d1d14b36029de454c1c9648054f22acc46f07efac691",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)))),
          Map.entry(
              "10401:10401001:88",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88.json",
                      "86a2cb15ddaa695ee6fd079aec6cf171de28088c1320517721f5779c9370b1a9",
                      List.of(0, 1, 2, 3, 6, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1001.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1002.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1003.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1004.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1005.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1006.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1007.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1009.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D1014.json",
                      "8986b6f268595f0e2fa98f5e26b5e92d38132616733f2b7620a24536b111757f",
                      List.of(1014)),
                  new Group(
                      10401,
                      10401001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G88_D7.json",
                      "648c5cb5621ffaa0e260543e013cbc0059d840f6bb1a786263bc176370aa212a",
                      List.of(7)))),
          Map.entry(
              "10401:10401001:89",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89.json",
                      "f21554b9c7b15e792488ff4948b9867ae3cc4da502b1bdba57cf2ba2d46cc622",
                      List.of(0, 1, 2, 3, 6, 7, 8, 9, 10)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1001.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1002.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1003.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1004.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1005.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1006.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1007.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1009.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      89,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G89_D1014.json",
                      "f6b7cecb97b698e5112eacf5625e4e13c2f5f60ab0d852bfd010a78de8cb6073",
                      List.of(1014)))),
          Map.entry(
              "10401:10401001:92",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(0, 10)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1.json",
                      "a4d13be85d4f0c4e2ba0a1eda75729c87bfc9027c4b412b4862fb3289e5591a3",
                      List.of(1)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1001.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of()),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1002.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1002)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1003.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1003)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1004.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1004)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1005.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1005)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1006.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1006)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1007.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1007)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1009.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1009)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D1014.json",
                      "ec8b4f91e9ca2bf671e7eb66909e77ed2358404a010470a16cb859d30c290fc1",
                      List.of(1014)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D2.json",
                      "a4d13be85d4f0c4e2ba0a1eda75729c87bfc9027c4b412b4862fb3289e5591a3",
                      List.of(2)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D3.json",
                      "a4d13be85d4f0c4e2ba0a1eda75729c87bfc9027c4b412b4862fb3289e5591a3",
                      List.of(3)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D6.json",
                      "a4d13be85d4f0c4e2ba0a1eda75729c87bfc9027c4b412b4862fb3289e5591a3",
                      List.of(6)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D7.json",
                      "a4d13be85d4f0c4e2ba0a1eda75729c87bfc9027c4b412b4862fb3289e5591a3",
                      List.of(7)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D8.json",
                      "a4d13be85d4f0c4e2ba0a1eda75729c87bfc9027c4b412b4862fb3289e5591a3",
                      List.of(8)),
                  new Group(
                      10401,
                      10401001,
                      92,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G92_D9.json",
                      "a4d13be85d4f0c4e2ba0a1eda75729c87bfc9027c4b412b4862fb3289e5591a3",
                      List.of(9)))),
          Map.entry(
              "10401:10401001:96",
              List.of(
                  new Group(
                      10401,
                      10401001,
                      96,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G96.json",
                      "27a48e15028232797903ebbc353c9950a54fc8ba13a9e6662c2895a584825611",
                      List.of(0)))),
          Map.entry(
              "20411:20411001:18",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      18,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G18.json",
                      "d4b24e438df87571ce72619c3b6877efbf7296dc1405c3341938ad8478b63f29",
                      List.of(0, 1)),
                  new Group(
                      20411,
                      20411001,
                      18,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G18_D1001.json",
                      "281695b862e66b4db5b937b97818fcbc8a5a82c9c78e462696896d8dbedd8ca5",
                      List.of()),
                  new Group(
                      20411,
                      20411001,
                      18,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G18_D1002.json",
                      "281695b862e66b4db5b937b97818fcbc8a5a82c9c78e462696896d8dbedd8ca5",
                      List.of(1002)))),
          Map.entry(
              "20411:20411001:19",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      19,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G19.json",
                      "868ad334c792906c90f621bed403e1a029303867fb68bb2d98710d362aa42c50",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20411:20411001:192",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      192,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G192.json",
                      "dbf4d3ec64af450cbc0c00bd9996825aad4c7ce82db62ed00ff171139da18fb2",
                      List.of(0)))),
          Map.entry(
              "20411:20411001:209",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      209,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G209.json",
                      "b2f5f7c2d99ee8531c13c4ee09969f5edc17839f2e80ab5ab0ac0dd8cdf202f7",
                      List.of(0, 1)))),
          Map.entry(
              "20411:20411001:210",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      210,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G210.json",
                      "b49f5a1215e241b95eec7b5cd02f8f0d26d9cf9a666b8620a7e8ab3603345587",
                      List.of(0, 1)))),
          Map.entry(
              "20411:20411001:211",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      211,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G211.json",
                      "869654f5646bc3f58ab56f5d0d361af0e321c00d94e6d72750693bdc27de7397",
                      List.of(0, 1)))),
          Map.entry(
              "20411:20411001:213",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      213,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G213.json",
                      "a90b21deabbfdfb32ed046e908d25f79295a3a3ec2df4782412b4cb9cbb4b3e9",
                      List.of(0, 1)))),
          Map.entry(
              "20411:20411001:287",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      287,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G287.json",
                      "be018cbc4efa2d5bea3ccfb4538b86e2e8b83383b37d3d0887bbf57856193d34",
                      List.of(0, 1)))),
          Map.entry(
              "20411:20411001:290",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      290,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G290.json",
                      "28deb3a49412e1a46c207a6402affa26c8ef7f68fbc3ccd4117ef75aa7bc7233",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20411:20411001:6",
              List.of(
                  new Group(
                      20411,
                      20411001,
                      6,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G6.json",
                      "04add780fcdf3af9ab02bd2090b4159cc9eee9e900ec2e5a2ca45c0160eee120",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20412:20412001:49",
              List.of(
                  new Group(
                      20412,
                      20412001,
                      49,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G49.json",
                      "092b3ffd29cdf8a777e87778cf8ab85b000d8f4ff64f878ddc3d4dedeea19528",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20412:20412001:53",
              List.of(
                  new Group(
                      20412,
                      20412001,
                      53,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G53.json",
                      "f0c693d37259a23327cab2f3f0f8626f96f914e9d404ffbc92950c2e9b0ad9bb",
                      List.of(0, 1)),
                  new Group(
                      20412,
                      20412001,
                      53,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G53_D1001.json",
                      "22ecd9663581d7d7c91b8b587f0c81967f73435f47823ff28090a7f8db8fac8b",
                      List.of()),
                  new Group(
                      20412,
                      20412001,
                      53,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G53_D1002.json",
                      "22ecd9663581d7d7c91b8b587f0c81967f73435f47823ff28090a7f8db8fac8b",
                      List.of(1002)))),
          Map.entry(
              "20412:20412001:54",
              List.of(
                  new Group(
                      20412,
                      20412001,
                      54,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G54.json",
                      "21603a27612933aa82e749098056fdfbd148239e159a2525afc654437f85c43b",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20412:20412001:63",
              List.of(
                  new Group(
                      20412,
                      20412001,
                      63,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G63.json",
                      "7d96106364ac2d8064328cebe1f3a1e026c5e2668a02d17d40ae7354f5f6f697",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20412:20412001:87",
              List.of(
                  new Group(
                      20412,
                      20412001,
                      87,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G87.json",
                      "c58bd1c00962066ee4c9062c4af494d1295a92b15bdd88b0cff2607bf24e5fe8",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20413:20413001:143",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      143,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G143.json",
                      "eaa30cff0ab335a84e3ce7a3323fcb47c050010eb36e214ac2a5341a61a2384e",
                      List.of(0, 1)))),
          Map.entry(
              "20413:20413001:151",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      151,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G151.json",
                      "863716282d7d886015c023c9adffcb1723201b1be85a968e00e7e294551927a5",
                      List.of(0)))),
          Map.entry(
              "20413:20413001:154",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      154,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G154.json",
                      "7e7f1e2b1ebad1ff273824e5b6093b950f5d25682a550ef6525e40a7a19ce38f",
                      List.of(0)))),
          Map.entry(
              "20413:20413001:245",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      245,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G245.json",
                      "47f7b62e3c0592dfe590f77771e48f2284dc3c8041ba13f49845a0491dcc064d",
                      List.of(0, 1)))),
          Map.entry(
              "20413:20413001:248",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      248,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G248.json",
                      "4ddb9716dfc2f3436d5b3292a731f0f3f7be54fe5f3ede12457c7152d3a17581",
                      List.of(0)))),
          Map.entry(
              "20413:20413001:49",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      49,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G49.json",
                      "8fe52b07f4951a95723ca80c83f85b0a00ced44eab9d5d267100e1f4ce28c631",
                      List.of(0, 1)))),
          Map.entry(
              "20413:20413001:50",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      50,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G50.json",
                      "d95e12d052dfa96d45f3888098ac2edfa0fa0f70d9781b98c83a9d7c75654673",
                      List.of(0, 1)))),
          Map.entry(
              "20413:20413001:51",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      51,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G51.json",
                      "d3f3c06bab8a8169ad57e8bf2a1275822883de74188a59a25c7803b6ca11967f",
                      List.of(0, 1, 1002)))),
          Map.entry(
              "20413:20413001:62",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      62,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G62.json",
                      "dded3367c0769ff9eb2510a229248d960582796dc53c6793637522456b1a35b3",
                      List.of(0, 1)))),
          Map.entry(
              "20413:20413001:88",
              List.of(
                  new Group(
                      20413,
                      20413001,
                      88,
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G88.json",
                      "f7e1d0402a2da129d502f1c7cec9e0d768910166d453bdb53a07dfc80f70a5da",
                      List.of(0, 1, 1002)))));
  static final Map<String, String> PREREQUISITE_FILES =
      Map.ofEntries(
          Map.entry(
              "Config/Level/Mission/1040101/MissionInfo_1040101.partial.json",
              "331907bfbbee55c2aab270f310b23561275832f43b31d6378efa76f742991650"),
          Map.entry(
              "Config/Level/Mission/1040106/MissionInfo_1040106.partial.json",
              "07a857661134302baba708136c48ca576059336953297b1ce5ca0b9e0aa23df3"),
          Map.entry(
              "Config/Level/Mission/1040111/MissionInfo_1040111.partial.json",
              "f47c439e9b03c8d626e68eecf7f3a0f9b50f5a8b87e2ffa7d8a1b5399b7a6a9f"),
          Map.entry(
              "Config/Level/Mission/1040109/MissionInfo_1040109.partial.json",
              "f7e63fe96cb9b021e292f802f9f12bb4befe35ba577992ddd3eb7f463aa88473"));

  private TimelineActivitySources() {}
}
