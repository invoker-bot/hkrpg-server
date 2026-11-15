package org.hkrpg.server.gameplay;

import com.google.gson.JsonParser;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo.SubMissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.List;
import java.util.Map;

/** Identical performance reports with two already accepted, source-verified objectives. */
final class SharedMissionTalk {
  private record Source(int primary, String primaryHash, int secondary, String secondaryHash,
      String scriptHash, int plane, int floor) {}

  // TurnBasedGameData 724b139d8c9c32d12552eb95745a4fee72bfe48b. Hashes bind the
  // normalized server-relevant rows and the complete original primary mission script.
  private static final Map<String, Source> SOURCES = Map.of(
      "Mission_200010823", new Source(200010823,
          "74034e954da9e9cd9fd5b5d8504cb6db7126848ed825ee52142a15e12fed109b", 200010828,
          "0d9097b718f9d19f72a5e67ef101b0fae61fa3bc4e9879dc4dd9d882a331df71",
          "5c078c03667c047bfa7f2170e4ec3da3c6cf427cde03d52022516e8a6c8a0359", 10001, 10001001),
      "Mission_200011012", new Source(200011012,
          "6210467f6d96045482506170c637937fe6c432bee677b90fe83f6d269936f686", 200011029,
          "5419048fa3bd43e8c9a35a0dd254e31987b49f7c0754b2d08e8040c2085b4051",
          "519dc50b50c44f72f0e244315e1ba2b2cdc5c5787a9c994f90b75581ae2d52ba", 10001, 10001001),
      "Mission_200011013", new Source(200011013,
          "cf5814f4dd4d5806dd7ca97b09a12056883b449383dcd90db4a9d70b097df33c", 200011030,
          "f93882980489353fa0a9b7eb1f7422e596100b624c75e7a9bb11cabbbf4a1039",
          "7d103e5a2a1c7f3fc152c65abb6a3d6c3c4a380aa434d9dad799fa5c9a052f55", 10001, 10001001),
      "Mission_401010506", new Source(401010506,
          "ad3619fd0686a0b9a88d8f585c8175da53bf3c3490baa449c5a89a7f3746f236", 401013401,
          "67db0944104269e6a8aaa54108fcc455a7b36d2f8e2eaf934d746247f3a05458",
          "d376adc3729b944555ce68b374d7ea1e5883d21187e87e00590e026082e57dd4", 10000, 10000000),
      "Mission_407131202", new Source(407131202,
          "520d79385b3d15a7b2bc69b67a546c9a1d991119ec932e082cd7cf5cb0cd4429", 407131208,
          "f3e65bf1d64524df35d30faaeacab461e2111349f67b4ef837919fd1453ca324",
          "185c5cc0655d13d0003b03b3bca4eaaa0d27b36d7b92a06346cf8b7062752036", 40131, 40131002),
      "Mission_407131203", new Source(407131203,
          "c6fba3a89b6d063eb4da8029728563c7dbf17a1f68a1892454607ceb46328d40", 407131209,
          "bd9977e753c0b474a66b484b1ff370ea5b139d9a8e4766cd105d014bd3f12c67",
          "e433bc37c77ed23df161752a4a6e983055791cf42920978c466998e675a206c4", 40131, 40131002));

  static boolean matches(Player player, String key, List<SubMissionInfo> matches) {
    var source = SOURCES.get(key);
    if (source == null || matches.size() != 2 || player.getPlaneId() != source.plane()
        || player.getFloorId() != source.floor()) return false;
    var primary = matches.stream().filter(c -> c.getId() == source.primary()).findFirst().orElse(null);
    var secondary = matches.stream().filter(c -> c.getId() == source.secondary()).findFirst().orElse(null);
    if (primary == null || secondary == null || primary.getTask() == null
        || secondary.getTask() != null || primary.isUnavailableNativeScript()
        || secondary.isUnavailableNativeScript()) return false;
    for (var sub : matches) {
      if (sub.getLevelPlaneID() != source.plane() || sub.getLevelFloorID() != source.floor()
          || sub.getProgress() != 1 || sub.getSubRewardID() != 0
          || sub.getCustomValueReward() == null || sub.getCustomValueReward().length != 0
          || sub.getCustomValueList() == null || sub.getCustomValueList().length != 0
          || sub.getFinishActionList() == null || sub.getFinishActionList().length != 0)
        return false;
    }
    try {
      return source.primaryHash().equals(rowHash(primary))
          && source.secondaryHash().equals(rowHash(secondary))
          && source.scriptHash().equals(TimelineActivityRules.fingerprint(
              JsonParser.parseString(primary.getTask().getSourceDefinition())));
    } catch (RuntimeException invalidSource) {
      return false;
    }
  }

  private static String rowHash(SubMissionInfo sub) {
    var row = ResourceLoader.getGson().toJsonTree(sub).getAsJsonObject();
    row.remove("task");
    return TimelineActivityRules.fingerprint(row);
  }

  private SharedMissionTalk() {}
}
