package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import java.util.HashSet;
import java.util.List;

/** Finite entry policies; RecoverMp currently requires a frozen multi-stage trial-only source. */
final class RaidEntryRecovery {
  static boolean none(GameplayDefinitions.Raid row) {
    return row != null
        && List.of().equals(row.RecoverType())
        && "Player".equals(row.TeamType())
        && "SkipUI".equals(row.EnterType())
        && row.MainMissionIDList() != null
        && row.MainMissionIDList().size() == 1
        && row.MainMissionIDBefore() > 0
        && row.MainMissionIDBefore() == row.MainMissionIDAfter();
  }

  static boolean supported(GameplayDefinitions.Raid row) {
    return row != null
        && (List.of("Unknown").equals(row.RecoverType())
            || none(row) && RaidEntranceSources.original(row)
            || List.of("RecoverMp").equals(row.RecoverType())
                && "TrialOnly".equals(row.TeamType())
                && row.MainMissionIDList() != null
                && row.MainMissionIDList().size() > 1);
  }

  /** Project entry policy: fill the isolated trial party once, before it is published or saved. */
  static void initialize(GameplayDefinitions.Raid row, RaidTrialManager.Prepared prepared) {
    if (!supported(row)) throw new IllegalArgumentException("Unsupported raid recovery source");
    if (!List.of("RecoverMp").equals(row.RecoverType())) return;
    if (prepared == null
        || prepared.party() == null
        || prepared.actors() == null
        || prepared.actors().isEmpty()
        || !new HashSet<>(prepared.party().getRoster()).equals(prepared.actors().keySet()))
      throw new IllegalArgumentException("Missing recovery-owned trial actors");
    int maximum = GameConstants.MAX_MP;
    for (var entry : prepared.actors().entrySet()) {
      var actor = entry.getValue();
      if (actor == null || actor.getAvatarId() != entry.getKey())
        throw new IllegalArgumentException("Invalid recovery-owned trial actor");
      maximum = Math.addExact(maximum, GameConstants.BONUS_MAX_MP.get(actor.getActorId()));
    }
    if (maximum < 0) throw new IllegalArgumentException("Invalid recovery point limit");
    prepared.party().mp(maximum);
  }

  private RaidEntryRecovery() {}
}
