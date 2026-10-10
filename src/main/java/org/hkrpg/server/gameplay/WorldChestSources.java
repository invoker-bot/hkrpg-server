package org.hkrpg.server.gameplay;

import java.util.*;

/** Source data only: TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b. */
final class WorldChestSources {
  static final Map<String, String> KEYS =
      Map.ofEntries(
          Map.entry("ActivityConfig", "ActivityID"),
          Map.entry("ActivityPanel", "PanelID"),
          Map.entry("ActivityQuestTimeLimitGroup", "QuestTimeLimitGroupID"),
          Map.entry("CycleQuest", "CycleID"),
          Map.entry("FinishWay", "ID"),
          Map.entry("InteractConfig", "InteractID"),
          Map.entry("ItemConfig", "ID"),
          Map.entry("MainMission", "MainMissionID"),
          Map.entry("MapEntrance", "ID"),
          Map.entry("MazeChest", "ID"),
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
              "ActivityQuestTimeLimitGroup:21",
              "ba0db69ce82909ab62ebcd047beab196e72585a99cc08a7a75ee1bdad4099543"),
          Map.entry(
              "ActivityQuestTimeLimitGroup:22",
              "c5af486880cb1f920e91db04bae8e3b8e21fed604129403e6767246d421b6b3e"),
          Map.entry(
              "ActivityQuestTimeLimitGroup:23",
              "c571125260c7ad588570e5be88efb1286484009c8414ceda3b4710b503cab843"),
          Map.entry(
              "ActivityQuestTimeLimitGroup:24",
              "4cc13ece4229dbdce7fd68752303fe10c7829405a5cf65e7320160e41af0ea13"),
          Map.entry(
              "CycleQuest:6029850",
              "d6d34490f351b96ded9841aa7e521f66a8525ce89e55c6e52d581f4deebb3532"),
          Map.entry(
              "CycleQuest:6029851",
              "50a64d2674e4338ebc2cd2a3050ba962308338a7101fd5b4f8e9abd62314a204"),
          Map.entry(
              "CycleQuest:6029852",
              "48b57ac5c84f56b0fb334a87d64022092e52de839fecfbd8b8912e504f264a9c"),
          Map.entry(
              "FinishWay:6029850",
              "95d0e1de3204f7ea2a099539dc8b15661a416f1763fa653e8e70968be405226e"),
          Map.entry(
              "FinishWay:6029851",
              "c7c39a401253f39bd0324282d6574b23fb8363e5834d77c83fb3b231f4ff886d"),
          Map.entry(
              "FinishWay:6029852",
              "9ba1501e93f1017f205a9ecbcbf5a9810112938bb1535358ab0a4dcd8424ae4f"),
          Map.entry(
              "InteractConfig:112131",
              "87512457d6bc792aa1f1b2affe5169a554d674202abd00d5fa3666d0d66aad8a"),
          Map.entry(
              "ItemConfig:1", "ae81c59f29957673ebc218333628663ec21c14ef7861361bed8b62d27da1d684"),
          Map.entry(
              "ItemConfig:110202",
              "b6bd2f45329d334adcff0bff2c1c7124c4f35c1aa2fb4dcd57bf002833c1686b"),
          Map.entry(
              "ItemConfig:120004",
              "4503729667bd2df6566b6f18bb1a708666c9b9cd5999f4693f60a26897358200"),
          Map.entry(
              "ItemConfig:2", "fdf8fd479ddcae80da77d33dafe0d7f7ef11e87c53fb27eb2f0e81d91fe39b30"),
          Map.entry(
              "ItemConfig:212", "f8c5d4a328a84b10929575b5a38da4f63164373472de06178352db1df879f0ef"),
          Map.entry(
              "ItemConfig:213", "b092cb576db4b71501e83759dd3c6a6abe81c44eb17ab36cf8382061710936a4"),
          Map.entry(
              "ItemConfig:22", "d992476f4393a4d07f6244a1cf43ab04771c39a3cd5eb75e53dcdbc7dc32f0f1"),
          Map.entry(
              "ItemConfig:232", "10b05eac43753de67c3f1041b76f639593d40f8c46f31043ee49f2cf3164e8da"),
          Map.entry(
              "MainMission:1000510",
              "c794662b3d9fa2f2c0e3049da7ad0f5f436663ef50e23aa8d29c983db0b32964"),
          Map.entry(
              "MapEntrance:1040155",
              "a299aca157a46b4dc3fc0a0a8059e76a27629fd5eaeba2001bc933df3eecb1aa"),
          Map.entry(
              "MapEntrance:2041101",
              "85e9ceed8021aea4f9092bddd236d134679dd685ee469c02d2fbbbe4d34a3227"),
          Map.entry(
              "MapEntrance:2041102",
              "a962c6eeb4752cb5fdd18d53bb1e81516a75c1072d41fddf652c76fbdc027aff"),
          Map.entry(
              "MazeChest:20411601",
              "2fb4183363ae772228442cb6a46a2a22b01a002f3237a1e71e2198681b1a31bd"),
          Map.entry(
              "MazeChest:20411602",
              "97e9fa66ee52e91cdd0b26b86d523882d4cc1b931c474baca4258ac2eb9f9990"),
          Map.entry(
              "MazeChest:20411603",
              "77a573ab5e74779a949dc3b4c2067d6195ad1fd3a0527848cdc0eba445f0cfbe"),
          Map.entry(
              "MazeChest:20411604",
              "ef4c4c31e7594accabebd2f0739afee58a99e74547c9db3ce76c3751f6c5c18e"),
          Map.entry(
              "MazeChest:20411605",
              "330cf04458f856b0fa94d800229595f377880ed731821ad3cc682c258bcf68d4"),
          Map.entry(
              "MazeChest:20411606",
              "511e87f5d1bd0a30563def04069f64175382e85db5a63a5a6f658c94dfb3a997"),
          Map.entry(
              "MazeChest:20411607",
              "7d32c67ea742a501c4055710fde7fb65621ae915d1cfd332d5f49ca6806df369"),
          Map.entry(
              "MazeChest:20411608",
              "e61a4f77cbf6122f18cdf3b179156d596183a9d1ee361f54b3215eb500d14475"),
          Map.entry(
              "MazeChest:20411609",
              "2cb36068351a3b5592061fb5d8af21343093881e0f6a3fa13ee07add3119d32c"),
          Map.entry(
              "MazeChest:20411610",
              "d140257a194ff88f452aad80d7ef77d35fa29f57d6cca9fa9972107cb8a21a09"),
          Map.entry(
              "MazeChest:20411611",
              "301d83a01065b9a0c2632f03e6ca18a9856e3a6580de5fbb376c9412ed622385"),
          Map.entry(
              "MazeChest:20411612",
              "c5abd919e2b423d6c3144d22553c23df0d156a85d7d14d457b875133fbbb2dc5"),
          Map.entry(
              "MazeChest:20411613",
              "067a733f709cfe5a5355da2392efa7f44122b2afc30520feb00e77adb3bd7f50"),
          Map.entry(
              "MazeChest:20411615",
              "320b809e84cd296263a1ec6afc9206466ae52e872aed4288b37cfd43af95600f"),
          Map.entry(
              "MazeChest:20411616",
              "ed3d64af91ebeb41fb8f44f82008b045b05ae26b2adb0c237caaa2c3679c33c4"),
          Map.entry(
              "MazeChest:20411617",
              "4c4dcef6593f749ec93d4e752fa8a2dea2922bc252d3a972cc67dbe05b00f102"),
          Map.entry(
              "MazeChest:20411618",
              "7c06e1998dbbc2878d7e5cc10cf864d2e245da0df90d1b60a68646f41c08c39f"),
          Map.entry(
              "MazeChest:20411619",
              "a3e596ff72c015517590b55d958dce6a825104874eea6a2c3aabf177ce79769c"),
          Map.entry(
              "MazeChest:20411620",
              "e3444a3f5a742c0a1598b2dd7652ed6771a21b2d90504008b466ba558b74d636"),
          Map.entry(
              "MazeChest:20411621",
              "3a38f3ad76240f4793c3bacc4a7820b877f8e9d56f81ac9f7aef95c8f39bfb0e"),
          Map.entry(
              "MazeChest:20411622",
              "53ab31ca3c64b525134d0375a47e1cd6716331b7488b421072134daf35ab0b2d"),
          Map.entry(
              "MazeChest:20411623",
              "91940b7f0878bcf673e14a1600f006b9578d33963153d3e1c4448055e5ba748b"),
          Map.entry(
              "MazeChest:20411624",
              "825dca6b1647234155ceef4f8bce42a22e22de022c1dbe2c434939a9559ed93b"),
          Map.entry(
              "MazeChest:20411626",
              "5c5cf1b726ac80dd20653091a07fcfd05b4e9d09a743142eb1b66165b1b15ab7"),
          Map.entry(
              "MazePlane:20411",
              "906d11e0e6c355a375819d3ab6d5a8a77477108954ae93b96406010adae50792"),
          Map.entry(
              "MazeProp:60401", "e93119c851822d25348e57e2d48733e40faa1830ea7d532d8c430a7732a0d732"),
          Map.entry(
              "MazeProp:60402", "99ca4364b367d8d6801f7554b905b811777ac56835b084a3e84b0d783ece56cf"),
          Map.entry(
              "QuestData:6029850",
              "cf8a957c957b91aad5ce1434d4372da7be01771886370e143a4ae12dacff0822"),
          Map.entry(
              "QuestData:6029851",
              "efcbfe3e7110699e11054b22aa4ca59e1fd4c781363450a03faea87210e3b8ff"),
          Map.entry(
              "QuestData:6029852",
              "6e83d9c91a2fa1672a716c4e272f23f27ea0a88a8ce544fba87b5bc27677f62b"),
          Map.entry(
              "QuestTimeLimitConfig:6029850",
              "c80338a6639e1c06c8c5e6ce73e0efc6d63367329586d58f892a9415aa29292d"),
          Map.entry(
              "QuestTimeLimitConfig:6029851",
              "1883e178bf6bf338a037dbdd6013725440834f11bd4fa00299c75d57e4c165f0"),
          Map.entry(
              "QuestTimeLimitConfig:6029852",
              "820409e508433c8ae6a5d6b94458676db569dffd422bfba971d83868964c749b"),
          Map.entry(
              "RewardData:3129010",
              "f5217b83a321073a8ae519e7d2e3974eac966f11cff26e8607c53b3f425fc226"),
          Map.entry(
              "RewardData:3129011",
              "f44d6dd52ed13abf0f38531e2b7ccdb331f3206645786aaa080fc33696205092"),
          Map.entry(
              "RewardData:3129012",
              "6382f03734d03d92e1c9cf1404c701b0bea0006534a3eb38674746f63cc6f531"));
  static final Map<String, String> FILES =
      Map.ofEntries(
          Map.entry(
              "Config/Level/Mission/1000510/MissionInfo_1000510.json",
              "970078ba06fb486129220851ac3921b3a6a3d3d28c268eca899b4cad1a940e76"),
          Map.entry(
              "Config/Level/Mission/1000510/MissionInfo_1000510.partial.json",
              "f0fea3275cc5593b3d4d913c0f0cce8974be96f90988cbee2f292ffadac05be3"),
          Map.entry(
              "Config/Level/Mission/1000510/MissionInfo_1000510.partial.layout.json",
              "2a2485a4610d152fd041161d0fca0e1e9523eb0e7ee7c166930700640a82b1cc"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G34.json",
              "65a462e244405c6b7872c966e0ac8e5d6cb58c5229551108b64a167c9fac8316"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
              "215886673eb37be2541c52bdb64eb99d04bbc71f59bf041c28f266d632cb2d4c"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
              "7071c05bfee1998ee84ef0f16c17f780300edad16dd65be3d156d6b9a4acfd65"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G42.json",
              "4b734fc328fe9ea57fd681182b948e348b0719240ce4f2393989cbc7effba61c"));

  record Location(
      int plane,
      int floor,
      int group,
      int instance,
      int prop,
      String path,
      Set<Integer> dimensions) {}

  static final Map<Integer, Location> LOCATIONS =
      Map.ofEntries(
          Map.entry(
              20411601,
              new Location(
                  20411,
                  20411001,
                  34,
                  300001,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G34.json",
                  Set.of(0, 1))),
          Map.entry(
              20411602,
              new Location(
                  20411,
                  20411001,
                  34,
                  300002,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G34.json",
                  Set.of(0, 1))),
          Map.entry(
              20411603,
              new Location(
                  20411,
                  20411001,
                  34,
                  300003,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G34.json",
                  Set.of(0, 1))),
          Map.entry(
              20411604,
              new Location(
                  20411,
                  20411001,
                  34,
                  300004,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G34.json",
                  Set.of(0, 1))),
          Map.entry(
              20411605,
              new Location(
                  20411,
                  20411001,
                  34,
                  300005,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G34.json",
                  Set.of(0, 1))),
          Map.entry(
              20411606,
              new Location(
                  20411,
                  20411001,
                  34,
                  300006,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G34.json",
                  Set.of(0, 1))),
          Map.entry(
              20411607,
              new Location(
                  20411,
                  20411001,
                  40,
                  300001,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
                  Set.of(0, 1))),
          Map.entry(
              20411608,
              new Location(
                  20411,
                  20411001,
                  40,
                  300002,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
                  Set.of(0, 1))),
          Map.entry(
              20411609,
              new Location(
                  20411,
                  20411001,
                  40,
                  300003,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
                  Set.of(0, 1))),
          Map.entry(
              20411610,
              new Location(
                  20411,
                  20411001,
                  40,
                  300004,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
                  Set.of(0, 1))),
          Map.entry(
              20411611,
              new Location(
                  20411,
                  20411001,
                  40,
                  300005,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
                  Set.of(0, 1))),
          Map.entry(
              20411612,
              new Location(
                  20411,
                  20411001,
                  40,
                  300006,
                  60402,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
                  Set.of(0, 1))),
          Map.entry(
              20411613,
              new Location(
                  20411,
                  20411001,
                  40,
                  300007,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G40.json",
                  Set.of(0, 1))),
          Map.entry(
              20411615,
              new Location(
                  20411,
                  20411001,
                  41,
                  300002,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
                  Set.of(0, 1))),
          Map.entry(
              20411616,
              new Location(
                  20411,
                  20411001,
                  41,
                  300003,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
                  Set.of(0, 1))),
          Map.entry(
              20411617,
              new Location(
                  20411,
                  20411001,
                  41,
                  300004,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
                  Set.of(0, 1))),
          Map.entry(
              20411618,
              new Location(
                  20411,
                  20411001,
                  41,
                  300005,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
                  Set.of(0, 1))),
          Map.entry(
              20411619,
              new Location(
                  20411,
                  20411001,
                  41,
                  300006,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
                  Set.of(0, 1))),
          Map.entry(
              20411620,
              new Location(
                  20411,
                  20411001,
                  42,
                  300001,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G42.json",
                  Set.of(0, 1))),
          Map.entry(
              20411621,
              new Location(
                  20411,
                  20411001,
                  42,
                  300003,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G42.json",
                  Set.of(0, 1))),
          Map.entry(
              20411622,
              new Location(
                  20411,
                  20411001,
                  42,
                  300004,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G42.json",
                  Set.of(0, 1))),
          Map.entry(
              20411623,
              new Location(
                  20411,
                  20411001,
                  42,
                  300005,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G42.json",
                  Set.of(0, 1))),
          Map.entry(
              20411624,
              new Location(
                  20411,
                  20411001,
                  41,
                  300007,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
                  Set.of(0, 1))),
          Map.entry(
              20411626,
              new Location(
                  20411,
                  20411001,
                  41,
                  300008,
                  60401,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G41.json",
                  Set.of(0, 1))));

  private WorldChestSources() {}
}
