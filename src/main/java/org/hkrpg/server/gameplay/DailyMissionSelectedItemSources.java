package org.hkrpg.server.gameplay;
import java.util.*;
/** Full source witness: TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b.
 * No reference code is imported. The two NPC DialogueMain files remain source-only;
 * these pins bind the existing server SelectMissionItem/Talk boundaries, not client Acts. */
final class DailyMissionSelectedItemSources {
  record Definition(int main,int plane,int floor,int group,int index,int npc,String mainPin,String graphPin,String groupPin,Set<String> files,Set<String> rows){
    int select(){return main*100+1;} int talk(){return main*100+2;}
    String groupPath(){return "Config/LevelOutput/SharedRuntimeGroup/Groups_P"+plane+"_F"+floor+"/LevelGroup_P"+plane+"_F"+floor+"_G"+group+".json";}
    String source(){return "4.6:selected-item:"+main+":"+graphPin;}
  }
  static final Map<Integer,Definition> DEFINITIONS=Map.ofEntries(
    Map.entry(3011605,new Definition(3011605,20122,20122001,102,82,11103,"f9b6386fdced1d308c5e59c6994b8aa539697219c0a944df95f0f372cc0ab5f6","a10b5e6b702f3aae78becb1e3cfe9d714ca7e6a145778e61482dc944c7aed0d9","a3c8b4df8fbcac0c646ed3c1045c315e3ebf61cf94d29d6e6f5b013babe40df0",Set.of("Config/Level/Mission/3011605/MissionInfo_3011605.json","Config/Level/Mission/3011605/MissionInfo_3011605.partial.json","Config/Level/Mission/3011605/Mission_301160501.json","Config/Level/Mission/3011605/Mission_301160502.json","Config/LevelOutput/SharedRuntimeGroup/Groups_P20122_F20122001/LevelGroup_P20122_F20122001_G102.json","Config/LevelOutput/RuntimeFloor/P20122_F20122001.json","Config/Level/Mission/3011601/Act/Act301160301.json","Config/Level/Mission/3011601/Act/Act301160302.json","Config/Level/Mission/3011601/Act/Act301160303.json"),Set.of("ExcelOutput/DailyMissionData.json#ID:3011605","ExcelOutput/DailyMissionCount.json#ID:1","ExcelOutput/MainMission.json#MainMissionID:1010403","ExcelOutput/MainMission.json#MainMissionID:1011501","ExcelOutput/MainMission.json#MainMissionID:3011605","ExcelOutput/QuestData.json#QuestID:2100003","ExcelOutput/FinishWay.json#ID:2100003","ExcelOutput/RewardData.json#RewardID:231","ExcelOutput/RewardData.json#RewardID:103004","ExcelOutput/ItemConfig.json#ID:2","ExcelOutput/DailyActiveQuestPool.json#QuestID:2100003","ExcelOutput/MazePlane.json#PlaneID:20122","ExcelOutput/MapEntrance.json#ID:2012201","ExcelOutput/MapEntrance.json#ID:2012202","ExcelOutput/MapEntrance.json#ID:201220102","ExcelOutput/MapEntrance.json#ID:201220103","ExcelOutput/ItemConfig.json#ID:401004","ExcelOutput/ItemConfig.json#ID:401012","ExcelOutput/NPCData.json#ID:11103","ExcelOutput/PerformanceE.json#PerformanceID:301160501","ExcelOutput/PerformanceE.json#PerformanceID:301160502","ExcelOutput/PerformanceE.json#PerformanceID:301160503"))),
    Map.entry(3011608,new Definition(3011608,20132,20132001,74,46,2108,"8829e0f81c06d7449e003143339896872e88bfeec73ce1793f2abce7724efb06","3c6362c3f49b59d0d35fbfa852d42cfaea61ca26e7f56784b598a1d10f1bd670","351566b05d807b86a8cf11d16e533bc6d632299dafc7631668bc3b86733111c2",Set.of("Config/Level/Mission/3011608/MissionInfo_3011608.json","Config/Level/Mission/3011608/MissionInfo_3011608.partial.json","Config/Level/Mission/3011608/Mission_301160801.json","Config/Level/Mission/3011608/Mission_301160802.json","Config/LevelOutput/SharedRuntimeGroup/Groups_P20132_F20132001/LevelGroup_P20132_F20132001_G74.json","Config/LevelOutput/RuntimeFloor/P20132_F20132001.json","Config/Level/Mission/3011601/Act/Act301160601.json","Config/Level/Mission/3011601/Act/Act301160602.json","Config/Level/Mission/3011601/Act/Act301160603.json","Config/Level/Mission/3011601/Act/Act301160604.json","Config/Level/Mission/3011601/Act/Act301160605.json","Config/Level/Mission/3011601/Act/Act301160606.json"),Set.of("ExcelOutput/DailyMissionData.json#ID:3011608","ExcelOutput/DailyMissionCount.json#ID:1","ExcelOutput/MainMission.json#MainMissionID:1010403","ExcelOutput/MainMission.json#MainMissionID:1011501","ExcelOutput/MainMission.json#MainMissionID:3011608","ExcelOutput/QuestData.json#QuestID:2100003","ExcelOutput/FinishWay.json#ID:2100003","ExcelOutput/RewardData.json#RewardID:231","ExcelOutput/RewardData.json#RewardID:103004","ExcelOutput/ItemConfig.json#ID:2","ExcelOutput/DailyActiveQuestPool.json#QuestID:2100003","ExcelOutput/MazePlane.json#PlaneID:20132","ExcelOutput/MapEntrance.json#ID:2013201","ExcelOutput/MapEntrance.json#ID:201320102","ExcelOutput/ItemConfig.json#ID:401007","ExcelOutput/ItemConfig.json#ID:401009","ExcelOutput/ItemConfig.json#ID:401011","ExcelOutput/ItemConfig.json#ID:402001","ExcelOutput/ItemConfig.json#ID:402005","ExcelOutput/NPCData.json#ID:2108","ExcelOutput/PerformanceE.json#PerformanceID:301160801","ExcelOutput/PerformanceE.json#PerformanceID:301160802","ExcelOutput/PerformanceE.json#PerformanceID:301160803","ExcelOutput/PerformanceE.json#PerformanceID:301160804","ExcelOutput/PerformanceE.json#PerformanceID:301160805","ExcelOutput/PerformanceE.json#PerformanceID:301160806"))));
  static final Map<String,String> FILES=Map.ofEntries(
    Map.entry("Config/Level/Mission/3011605/MissionInfo_3011605.json","28e6c29f0412be869aed08f0100eb025afd0e6f620eb93773624ab5cac7b9f36"),
    Map.entry("Config/Level/Mission/3011605/MissionInfo_3011605.partial.json","7bfe33c042b8364e46e304af9d908d91da1d5b34d0b5a79061aa4106bbf647e4"),
    Map.entry("Config/Level/Mission/3011605/Mission_301160501.json","e9ab974a0e6aefb0e5d4c2a32aa86a6b8350f8e93674e1ff3ffbce7fc3843d57"),
    Map.entry("Config/Level/Mission/3011605/Mission_301160502.json","8d22ed385979dda395fdc61186574bedd0971432cfad689b3f6c3209a7516188"),
    Map.entry("Config/LevelOutput/SharedRuntimeGroup/Groups_P20122_F20122001/LevelGroup_P20122_F20122001_G102.json","6456a918cadbc0bd7358e9b1a26ebebd31ce1346bafb86bd2f0ea6afb275cca2"),
    Map.entry("Config/LevelOutput/RuntimeFloor/P20122_F20122001.json","39b9fd72a32343cf0f03bf296ee45512b8b4ca7846fa78c0eb09993b5d2cac4b"),
    Map.entry("Config/Level/Mission/3011608/MissionInfo_3011608.json","f2aaab5e8014e206d7a4846e31f2cada192bf4398d636e29ca6fe6606934c9cc"),
    Map.entry("Config/Level/Mission/3011608/MissionInfo_3011608.partial.json","9c6b2396b7a88091bdde6371ac0f7ea1bb846dfe625674e9213df713ec9ed08f"),
    Map.entry("Config/Level/Mission/3011608/Mission_301160801.json","8c05be02fc5b1c995e31cb7a77037bad892c10afa7207105dd5af7eb2ba7041e"),
    Map.entry("Config/Level/Mission/3011608/Mission_301160802.json","c190a3e3a9048079ec0a6c911118b1c4b210c9cab946f44216be3f3c21d170e0"),
    Map.entry("Config/LevelOutput/SharedRuntimeGroup/Groups_P20132_F20132001/LevelGroup_P20132_F20132001_G74.json","b4f2f4d348e99eaef9ebe04ab724f3a5d9fc17d0ce2a6ba60ab3f3da089eb975"),
    Map.entry("Config/LevelOutput/RuntimeFloor/P20132_F20132001.json","37ead343ba4609dbecccc679d9fd806aa745f2dbab0d5b5c461f263bcc694f26"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160301.json","86ae5cada839eda2e99190bf0d4b46f4cb1b3a99acbacf311b4038ab7b7d990a"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160302.json","d9e81afabee6f9771d8e032a2eeac4a45c6b105b9d9a09d0b42a0f164149de85"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160303.json","c6fcc93bf98d4e3418f0ba42fff28cc8e1ad608925e95ba12a828c8e8c0c3949"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160601.json","c1bfcf85eeda80c064a2e8395bf1a1b20b5823348b3665fb7988e2edfc3238e7"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160602.json","37d551cd2f8a219033643ba41920c251369eb7b6176d772a9933070aabc43878"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160603.json","f31ec1ae94b9b84146f4577a74a81f87f0281e7fd0708857824f033716461015"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160604.json","ba11e3a48697db64c1c4c71668cc7dc3e28911e103192164827b92dcb87f7e32"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160605.json","ce2d648dc554cb073dbdb58d1390fe532841f3e53999fb7c751532295cfde1d9"),
    Map.entry("Config/Level/Mission/3011601/Act/Act301160606.json","c5ce0b7d857546696297d2880592552ca3761f20c6e9d578c46c33d877c4ab61"));
  static final Map<String,String> ROWS=Map.ofEntries(
    Map.entry("ExcelOutput/DailyMissionData.json#ID:3011605","3e6159576d4b18a552df6c1e345eb4544ff2f994586706d88daace1b394fc4a5"),
    Map.entry("ExcelOutput/DailyMissionCount.json#ID:1","4f044ba2e805dd9dd96b137036bdf618ac6631402c4955fbd4d16067c639f427"),
    Map.entry("ExcelOutput/MainMission.json#MainMissionID:1010403","32b0f979dc4b2c979841015ad7ff5aca056e57bc747a5e2be2cdc9a40e898203"),
    Map.entry("ExcelOutput/MainMission.json#MainMissionID:1011501","7fd84a2814f6f140934d6c85642a7d48560fb212f5ab7190976fa5945619b8cc"),
    Map.entry("ExcelOutput/MainMission.json#MainMissionID:3011605","1e26f022c5426fffd8851dc0e2008624c83d17978830239638cae6b20e45ed33"),
    Map.entry("ExcelOutput/QuestData.json#QuestID:2100003","62609ad0f612c88e79e057f9d3931e3f1052210efd701d9cff6ccd9f5af2ea7b"),
    Map.entry("ExcelOutput/FinishWay.json#ID:2100003","efd3951357a7dfd2add6667aefbc5c98f94708bb3135cc0cb8f921cc69110dfe"),
    Map.entry("ExcelOutput/RewardData.json#RewardID:231","aa66dbb81d2799a1957ff91f153124d4f0ba5b19e8ca31f63c260769404b8d6b"),
    Map.entry("ExcelOutput/RewardData.json#RewardID:103004","d4acd6f2a47e664c616d188c48d8c3a71ed296982e1d4f0fcfd2c20f80404b9d"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:2","fdf8fd479ddcae80da77d33dafe0d7f7ef11e87c53fb27eb2f0e81d91fe39b30"),
    Map.entry("ExcelOutput/DailyActiveQuestPool.json#QuestID:2100003","a4a6761923da4f4fa57a1f1b67488363fcf3d5787f174d094da18ae2d26bba37"),
    Map.entry("ExcelOutput/MazePlane.json#PlaneID:20122","4b9101864bb22fbb1868989dbb705bb7cddf9547868f7d2cfc0efe73181cc7c7"),
    Map.entry("ExcelOutput/MapEntrance.json#ID:2012201","d02e2a62c863a072aa39ec7b3dbb753be2c14dab86aa91fc5f95cb4eb403242a"),
    Map.entry("ExcelOutput/MapEntrance.json#ID:2012202","a759293d369688f14f98bac0dd738a1e6f7adcaa1e966c165e81f13f12e711e3"),
    Map.entry("ExcelOutput/MapEntrance.json#ID:201220102","27e008a0c355b681ff592b745233f081771b273ac0aafd8a81652d3cea40c35d"),
    Map.entry("ExcelOutput/MapEntrance.json#ID:201220103","ef85e7de57bf2e8c78728eadb27f0ca9a4826567fb405facd0bdff475a409fa1"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:401004","709c3c9a5a9634e7257d9c718ea0325f7abec45c69e322715e24b15dae163e95"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:401012","7e7218fc1cab5d14cd3907cf97fa4c0f9303d860bb53baa51c5573a53ed73064"),
    Map.entry("ExcelOutput/NPCData.json#ID:11103","0a359d7c2fda70eb5dde5d798be033bcf5044f91b8392bcfb529f5034ba68ed4"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160501","618d430719a7962b7ab8b477ab0ec9e71a92fa58aacf4651119b16fc45d04802"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160502","d8585fe447d156d930baae2184bafc513160162601957da853a71f3bf626d83c"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160503","56bc2f15664ca73ee9ee3b84f5526f7f6912fdb6e54025578000f44bb09a81ca"),
    Map.entry("ExcelOutput/DailyMissionData.json#ID:3011608","b341be013bebe22a9b3e6817343a0b6972b30846cf284103b00bf0f6a5df28a9"),
    Map.entry("ExcelOutput/MainMission.json#MainMissionID:3011608","6bfc90b07c1d68d9db1d8e02f4877322c455abd95efdb4a7945c3bdef342426c"),
    Map.entry("ExcelOutput/MazePlane.json#PlaneID:20132","d71423b2262be68f17caaa3b1dd0780e435b2bf901abb4db13c742bffb2b7954"),
    Map.entry("ExcelOutput/MapEntrance.json#ID:2013201","c40238c642f949539da0dba38b4b267c11138f14766f0f51f3dbf24008a78c7c"),
    Map.entry("ExcelOutput/MapEntrance.json#ID:201320102","8de696fc000c0cd59189502906da49ade06f6c8113a62029e9e9bd49fcb27da9"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:401007","a8009b943357ce5c40d6e7abfb3e2ee013eb32385a0afc4ffc5db417cf23aece"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:401009","7f8276d4df9691a0056508d9e62451791d68da30730113ecc5b49ebe875ae8ba"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:401011","bb0d11bb3325db2bc97346219f4ca0af05e931d86e6ee2b2249159ddb86a7e3e"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:402001","8773c2f88643cdeb48d2c3afafd88f8899da7853b652172af8c240486b73d4f4"),
    Map.entry("ExcelOutput/ItemConfig.json#ID:402005","4dfce97e3a3b88c25cecf6df7a8b5897dc6c4d4c7f336c3bee9e55cb0119c92f"),
    Map.entry("ExcelOutput/NPCData.json#ID:2108","0619926523a4609f23b4c65e4cbf02b95bfabfd6ad133d3755dc378bc225ee92"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160801","06475c5857ba6c919f53f441e149396aa34dc1bce2ea45da9fba2a7b9e299032"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160802","e4e2ea3dbc4a6784c6bee640ad3d6bedeb34a611bf9cc11e4a94da6a8e0dfef8"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160803","f34d3a1ae1b5405c745d7a24290bf309784a0e4271646cb0b768660be0aca58d"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160804","602b3fedee03083eed28cae2e810aff222686d3479ede49f864990f597f3e543"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160805","f5fc4523f72e2c5ed54e7b7f70717fdcb9c424d67ee0b9ec8c3a9e5d760d804d"),
    Map.entry("ExcelOutput/PerformanceE.json#PerformanceID:301160806","68eb69c968958707c221121e07d40b9f5010fc5fd329dc517aecb33890500b0e"));
  private DailyMissionSelectedItemSources() {}
}
