package org.hkrpg.server.gameplay;

import java.util.*;

/**
 * Original data checksums at TurnBasedGameData@724b139d8c9c32d12552eb95745a4fee72bfe48b; no
 * reference runtime code.
 */
final class WorldFirstTalkSources {
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
          Map.entry("PerformanceE", "PerformanceID"),
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
              "CycleQuest:6029859",
              "d4f81d06730c3ee56b50fe31ea2e71153026ef7408d8f49206dfbed898bc2dc6"),
          Map.entry(
              "FinishWay:6029859",
              "d44b6905f71cd04da2002a64802ca896a25f4627e6a74da8f340d01db84a1f87"),
          Map.entry(
              "FuncUnlockData:106001",
              "51b0213ca203fa5297c9f227c892eda1bcb58e7b6723f0aaa5e52ad7d45c54b9"),
          Map.entry(
              "ItemConfig:1", "ae81c59f29957673ebc218333628663ec21c14ef7861361bed8b62d27da1d684"),
          Map.entry(
              "ItemConfig:110202",
              "b6bd2f45329d334adcff0bff2c1c7124c4f35c1aa2fb4dcd57bf002833c1686b"),
          Map.entry(
              "MainMission:1000510",
              "c794662b3d9fa2f2c0e3049da7ad0f5f436663ef50e23aa8d29c983db0b32964"),
          Map.entry(
              "MainMission:1040101",
              "e69d40bf815a8619327e673aae3aaba31ce26f1525fd2bbea24328337509128e"),
          Map.entry(
              "MapEntrance:1040101",
              "321014fe7e8a08a4738424ffef4e725ae10c2ab35e98e1cdb9dc957c26b2ac25"),
          Map.entry(
              "MapEntrance:104010102",
              "7267898642cf3267bae55759617ba29840e0955a6eec6d1072c738995cbc7829"),
          Map.entry(
              "MapEntrance:1040102",
              "a6f7291d0104548b5f79e247ecd97e70a25eba3d44fb54007669fa1004e3d3ed"),
          Map.entry(
              "MapEntrance:1040103",
              "3fd146d88526382f219a50c2a3838d4e7d07f3d3ad752eb203b4ebfc0b9fcf3f"),
          Map.entry(
              "MapEntrance:1040104",
              "e614c9c3d8fbe9aee8f6586fcdc4143cbc79a131e4a4cefee64716c43c481b11"),
          Map.entry(
              "MapEntrance:1040105",
              "003ef854f7d01bb3c8f0124baa51349060366aedceb9e989dc840bb87d68f45d"),
          Map.entry(
              "MapEntrance:1040106",
              "cc89d4f760cec8355def9b9a333e95b7553cb6650da2fb829d3b081fd83b6b8a"),
          Map.entry(
              "MapEntrance:1040107",
              "be3b9869567e6bbae94433def798e5d2183f9d2e6656c81512650d1011292646"),
          Map.entry(
              "MapEntrance:1040108",
              "f70904fba6b645a66b8c5ce4041e4e1738a9606f73a025fafcd797ecf23a9167"),
          Map.entry(
              "MapEntrance:1040151",
              "85d177e125ea4986588b30932051f28593f0aac3166f2beff3a06d985ac10964"),
          Map.entry(
              "MapEntrance:1040152",
              "777b9636a7aa26659b2bba73d1ee297de1a3602d167b772c173801856c6fdd6b"),
          Map.entry(
              "MapEntrance:1040153",
              "fda0d7c14a3a50308eda4656ac811cc92cbd752604702e916680e4b125e278ac"),
          Map.entry(
              "MapEntrance:1040154",
              "2b5595c7b2b951238a73ae0c47dc5d4b217382458ada3d15fb7757f069237134"),
          Map.entry(
              "MapEntrance:1040155",
              "a299aca157a46b4dc3fc0a0a8059e76a27629fd5eaeba2001bc933df3eecb1aa"),
          Map.entry(
              "MapEntrance:1040156",
              "c750388b2e2c3df2117380402354bfc339112a535e0bc31f7e2580bd507a8cd5"),
          Map.entry(
              "MapEntrance:1040161",
              "76bcf8b8927347d095e6eec04784f2bb239daf45528849a307cbcf3f0ea0a2bf"),
          Map.entry(
              "MapEntrance:1040162",
              "967a1ac305589ad3a621e70894f72b9474f9a7643122eeea43c0018c9ac7c50d"),
          Map.entry(
              "MapEntrance:1040163",
              "97408744df9d206483a7d6cedd946ab7db690b2ee7f9452419cc0cc493ce9cdc"),
          Map.entry(
              "MapEntrance:1040164",
              "0de742426ade9dd749ee45d5fdfc50c0698448f1a3fce755095bd8b1337efe2f"),
          Map.entry(
              "MapEntrance:1040165",
              "89df70fd52b2b352264e07c075f4bc483f8cfe4df5c63bf3785c97cfba06f5ad"),
          Map.entry(
              "MapEntrance:1040166",
              "26fbcc7162109cf0f945deccb3de13e6549e40be29c1ea1276068d66b9a3961b"),
          Map.entry(
              "MapEntrance:2041101",
              "85e9ceed8021aea4f9092bddd236d134679dd685ee469c02d2fbbbe4d34a3227"),
          Map.entry(
              "MapEntrance:2041102",
              "a962c6eeb4752cb5fdd18d53bb1e81516a75c1072d41fddf652c76fbdc027aff"),
          Map.entry(
              "MapEntrance:2041201",
              "64aadd406518c63b5b51e119a0dff29b4447a2052e1eb7ff99fb8460a03d8ce4"),
          Map.entry(
              "MapEntrance:2041202",
              "bf1e7e94859634438a20cf9e6a28c01fd91becd33a99201d328b42223e50d71e"),
          Map.entry(
              "MapEntrance:2041203",
              "3a1546f7a1193ad56d3a0f4a176d14a67ca30033a147134d41f12b2b73277d91"),
          Map.entry(
              "MapEntrance:2041301",
              "291d6fb90f60886421d112c40ff9704ccc1e753a3608c59ff64ff95cc9da26d0"),
          Map.entry(
              "MapEntrance:40239001",
              "a2ff73f5be080a09adc88ea94f88436c09c3158440b6e24f669cbd622187b085"),
          Map.entry(
              "MapEntrance:40239002",
              "efa37da27a58df0209c24e33ea6982261cd7d16d53f28577108e3699a110e358"),
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
              "MazeProp:104050",
              "6aa6612d39e47299a065ce48e2faf7611deb05bdebf070690266f53f4e86ae7d"),
          Map.entry(
              "PerformanceE:304002001",
              "7a5ca1a29d0f9d70f0af3a03286857b277ba067866338cef02ba59504d2f9d89"),
          Map.entry(
              "PerformanceE:304002002",
              "73834975505dc56fa608079434f4e51f7a5947f106eeac08705adf47e2714e1e"),
          Map.entry(
              "PerformanceE:304002003",
              "9b7655320b2cc02070e3e2787211214a6913504698d62daab73c4c477644bea3"),
          Map.entry(
              "PerformanceE:304002004",
              "83c4da83863beaad15e7c7aa3625ed2f0c542967ee71c2585ad8fa298a9f61ca"),
          Map.entry(
              "PerformanceE:304002005",
              "e43267fe58a6b1b4fab00a773079d5175ba6d4b37fa5836249757c5c7b795d39"),
          Map.entry(
              "PerformanceE:304002006",
              "a8a3b877aa1321333c73236c568ab387fb81569cf8ade2b98a0048262a2f3440"),
          Map.entry(
              "PerformanceE:304002007",
              "7f26ee20f20974910bf927f2a9c85f5de940bdd538f59b3f0fbb04573e402022"),
          Map.entry(
              "PerformanceE:304002008",
              "28feae2f5d233001181c877ffbaf2c67f3c866f1721e31990c06d286f498286e"),
          Map.entry(
              "PerformanceE:304002009",
              "a0b3cedd58895c7fc4be15877ef60208dadb86777d953065816d71d87fac4348"),
          Map.entry(
              "PerformanceE:304002010",
              "c79d6eb6b9a87f77d370cfaaea58ddd159b101df57e772bab6e7dd3d0beae577"),
          Map.entry(
              "PerformanceE:304002011",
              "b18d2eaea3e96150c4b1eed17d614c94f7957fb83a2d0b2d47d071b15e3d0c0d"),
          Map.entry(
              "PerformanceE:304002012",
              "1212a910efb1caf98ef4cb51b75ff5790c1310fd0cc5290d320dea00da22f5ba"),
          Map.entry(
              "PerformanceE:304002013",
              "72c38dd371200a8a5ba90c8306da2bbea9ec96f29e81892f2cd63e83f7612048"),
          Map.entry(
              "PerformanceE:304002014",
              "eeaba9431717c40c5dfce41abe1539f8280a29b374e7673464014b60c41077b7"),
          Map.entry(
              "PerformanceE:304002015",
              "8e748534de3b9e4e1365e2f39d1f1c55a901a4596ab3e0b04b6db3b0a3dbd86b"),
          Map.entry(
              "PerformanceE:304002016",
              "6692a0e4ea17091c07a7bb4b0efd4171c6e6e23490d1c47ed8ba158560ea0608"),
          Map.entry(
              "PerformanceE:304002017",
              "928e91fc7cf58f5913476db7a2c423abfeaf9a292ccfb1b235926acddfead63a"),
          Map.entry(
              "PerformanceE:304002018",
              "8c201b9d6ceb525243a55bb5c5c4e5e45c4e572fde44cfb1844643492fca3400"),
          Map.entry(
              "PerformanceE:304002019",
              "9591bc5ff3f17fca541ecc291d29718e730a348005adbed9101011ab6f13c9f8"),
          Map.entry(
              "PerformanceE:304002020",
              "b3da694aa75f820bb3b5ea94cda51f32fab9d9052f92977b46db1b058c8f170d"),
          Map.entry(
              "PerformanceE:304002021",
              "411ae7ae1be52fdc5b75f03e672d32b82a97088dc87a89ce660eaeb4dbb6ca67"),
          Map.entry(
              "PerformanceE:304002022",
              "49ae88d2d64e73ffa59a9eb67e56504d4e143e3aa0ec9c65d8c9200bf6536690"),
          Map.entry(
              "PerformanceE:304002023",
              "3cad06d0f769017c79c74728b4586809d7b87241efa3d3991985d21f51d9b3b0"),
          Map.entry(
              "PerformanceE:304002024",
              "20305dae0c2923811b12e06731c9bcdc81e52bb7f59d19a6af5a0b6c2c67e8cd"),
          Map.entry(
              "PerformanceE:304002025",
              "78d771cbb6595381de8bd85d14c74056c73593d74a9c4d92b5a707fe43e31ade"),
          Map.entry(
              "PerformanceE:304002026",
              "598c990fa1c6fad273668f206a9b1f2abc2a740a00df2129e3ea0e679ec5c583"),
          Map.entry(
              "QuestData:6029859",
              "8cba019193b8be5463985a1c7f6d492c70d91985fec5a8287d87267b53083cd6"),
          Map.entry(
              "QuestTimeLimitConfig:6029859",
              "02df8a40d48f498146fbb7bd76af84ed1b295ddbcc80968af969829e36803ba1"),
          Map.entry(
              "RewardData:3129019",
              "6b12ea9bb58e89ce5ac28d2e10d8ac3569ce95627c0b92e52befe0a17397cc75"));
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
              "Config/Level/Mission/1040101/MissionInfo_1040101.json",
              "479bc0e148f700f97f2dddb24e2c4eefce7eb7f5fc80dafc1680fadc32ec91a8"),
          Map.entry(
              "Config/Level/Mission/1040101/MissionInfo_1040101.partial.json",
              "331907bfbbee55c2aab270f310b23561275832f43b31d6378efa76f742991650"),
          Map.entry(
              "Config/Level/Mission/1040101/MissionInfo_1040101.partial.layout.json",
              "1698e2f6a3012df01a05f28ad25ac79f8cd10fa52b270b0141dde560098b19bb"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002001.json",
              "d073ab4180ae99e780c1e56c22a985aefe5fecf5a7d6cf783ed56e4c99f88670"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002002.json",
              "77d0a4a5eda90d86a842380340cbf43e690e57e84e3334e2592fbba59e4d95ae"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002003.json",
              "a4e0bfe191b77af10dda5744121aff0cc079390c9563f6f2b1912555a6d1b855"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002004.json",
              "d7657383e63d6cad9362e9c24ba111c8c21f8140772f1658aa93936c842e6674"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002005.json",
              "c868daa17452241da8be289ddbd647adcf58ed282ec3539325563dc522569f4b"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002006.json",
              "129c10287065fab8ec69984a81570d2afc196a2b7beb0e4b8c9438125fd08926"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002007.json",
              "aadb05ee4a956fafadb963ed4b97feb216aa4822a17b0b191b4cceeb4bfd5f8c"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002008.json",
              "26301492393f4354f94010d85b46c6423aaea820ac200f502627d208df4a24d0"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002009.json",
              "587c831a8aac8b380456eab1e8c7e52f8aa7fb73337784891957e6ef0338de5b"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002010.json",
              "d1ee74f2821e5a3bca1b512cac2c00aa1283d9e5c827de2a146962afae11bab8"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002011.json",
              "ef826012ef54d173c704dd8fc307cf2bdcbcd90f01abe65aba424d21d377d45e"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002012.json",
              "6041c310cb04ef37e6ab8bc083ae27f08d5a1d89b39d9d9612aacd14f119d7dc"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002013.json",
              "388ce70c27908e1c648a12e3c34b30be636a6966d2ab81f9cb7c549e5293588a"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002014.json",
              "397ffbc6de6c8413f766b0231eae572996a3bc56f2bfe2134502d1e5a0453ff5"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002015.json",
              "9c0a007b496e25456abb2dfa12f31d9577e9f54f6cd7a555e8d9c422118dd796"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002016.json",
              "fec59402d58f1196cdf4fad4f77360a85506e1d055454418bd2a14112af336f8"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002017.json",
              "31c147d2bea9217c587999aff6170cb0ecb49fc73b6374360040ce9c567a0577"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002018.json",
              "2ed3300fa538aa32fbe3bf262e61d492774cb30f1e37611ffe3efb0a53ab4725"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002019.json",
              "99385853b900700fd314fcb4c38e7d9d87dda6788f5ef836abf28f6d65a646ca"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002020.json",
              "b9ee353a40c8b1f27b38b54b618200d7ff6ae4b7b29566c160a3010c5fdf604b"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002021.json",
              "8906cfacd60c94947f0addf1d1b7306271cfb2c02d3ce7a13cc5104260008cf8"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002022.json",
              "880feaaab5cc7cabb0c146270be2b3f86f03641d6f5fe8ef47a4c71c8a8cb5a4"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002023.json",
              "984bdd031ce219c08b488f377605b28f25eab6755f591e42fcc1e8b5209c0730"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002024.json",
              "aaaa2d3f742defa440b5cbd75ff39df7c21bef6ffc01f294671c13efe561ed78"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002025.json",
              "6816b319c573ec26bb87eec7ca3861b7dd85af84b8687e42b6d53eda646359bc"),
          Map.entry(
              "Config/Level/Mission/3040020/Act/Act304002026.json",
              "e60c34fadbb7097ae82311aae4a79711734c0c5a5affcf986ddfb921c01424be"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
              "5ed98e22b176d4d140810c1f2dd792ae6510d1e7e7920df649fb2c84140bac3f"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G169.json",
              "d2c5ec9fa8a199ac3f71755a371ebd8b1bf23761e5a67827f302724e44e93647"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G106.json",
              "9ba717c72adc07a4d18dcd8e8eba1e41ebb5fc85d90841b6fc03d9811e3b265c"),
          Map.entry(
              "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G161.json",
              "09767367c30f096941b703252c859cb0730b08610cf2e4a01c78dc4155ef0212"));

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
              304002001,
              new Location(
                  20412,
                  20412001,
                  106,
                  300001,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G106.json",
                  Set.of(0))),
          Map.entry(
              304002002,
              new Location(
                  20412,
                  20412001,
                  106,
                  300002,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G106.json",
                  Set.of(0))),
          Map.entry(
              304002003,
              new Location(
                  20412,
                  20412001,
                  106,
                  300003,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G106.json",
                  Set.of(0))),
          Map.entry(
              304002004,
              new Location(
                  20412,
                  20412001,
                  106,
                  300004,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20412_F20412001/LevelGroup_P20412_F20412001_G106.json",
                  Set.of(0))),
          Map.entry(
              304002005,
              new Location(
                  10401,
                  10401001,
                  97,
                  300001,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002006,
              new Location(
                  10401,
                  10401001,
                  97,
                  300002,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002007,
              new Location(
                  10401,
                  10401001,
                  97,
                  300003,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002008,
              new Location(
                  10401,
                  10401001,
                  97,
                  300004,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002009,
              new Location(
                  10401,
                  10401001,
                  97,
                  300005,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002010,
              new Location(
                  10401,
                  10401001,
                  97,
                  300006,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002011,
              new Location(
                  10401,
                  10401001,
                  97,
                  300007,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002012,
              new Location(
                  10401,
                  10401001,
                  97,
                  300008,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002013,
              new Location(
                  10401,
                  10401001,
                  97,
                  300009,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002014,
              new Location(
                  10401,
                  10401001,
                  97,
                  300010,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002015,
              new Location(
                  10401,
                  10401001,
                  97,
                  300011,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G97.json",
                  Set.of(0))),
          Map.entry(
              304002016,
              new Location(
                  20411,
                  20411001,
                  169,
                  300001,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G169.json",
                  Set.of(0))),
          Map.entry(
              304002017,
              new Location(
                  20411,
                  20411001,
                  169,
                  300002,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G169.json",
                  Set.of(0))),
          Map.entry(
              304002018,
              new Location(
                  20411,
                  20411001,
                  169,
                  300003,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G169.json",
                  Set.of(0))),
          Map.entry(
              304002019,
              new Location(
                  20413,
                  20413001,
                  161,
                  300001,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G161.json",
                  Set.of(0))),
          Map.entry(
              304002020,
              new Location(
                  20413,
                  20413001,
                  161,
                  300002,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G161.json",
                  Set.of(0))),
          Map.entry(
              304002021,
              new Location(
                  20413,
                  20413001,
                  161,
                  300004,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G161.json",
                  Set.of(0))),
          Map.entry(
              304002022,
              new Location(
                  20413,
                  20413001,
                  161,
                  300005,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G161.json",
                  Set.of(0))),
          Map.entry(
              304002023,
              new Location(
                  20413,
                  20413001,
                  161,
                  300003,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20413_F20413001/LevelGroup_P20413_F20413001_G161.json",
                  Set.of(0))),
          Map.entry(
              304002024,
              new Location(
                  20411,
                  20411001,
                  169,
                  300004,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G169.json",
                  Set.of(0))),
          Map.entry(
              304002025,
              new Location(
                  20411,
                  20411001,
                  169,
                  300005,
                  104050,
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P20411_F20411001/LevelGroup_P20411_F20411001_G169.json",
                  Set.of(0))));

  private WorldFirstTalkSources() {}
}
