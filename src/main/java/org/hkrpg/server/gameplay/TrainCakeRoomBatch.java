package org.hkrpg.server.gameplay;

import java.util.*;

/** A persisted weekly room batch; owner identity never comes from a search packet. */
public record TrainCakeRoomBatch(int ownerUid, String source, long refreshAt,
    List<TrainCakeCatchState.Spot> spots) {
  public TrainCakeRoomBatch { spots = List.copyOf(spots); }

  public boolean valid(String expectedSource, long now) {
    return refreshAt == TrainCakeCatch.nextRefresh(now) && validShape(expectedSource);
  }

  boolean validShape(String expectedSource) {
    if (ownerUid <= 0 || source == null || !source.equals(expectedSource)
        || refreshAt <= 0 || spots.size() != 3) return false;
    var cats = new HashSet<Integer>();
    var slots = new HashSet<Integer>();
    for (var spot : spots)
      if (spot == null || spot.getCat() < 1 || spot.getCat() > 27
          || spot.getSlot() < 0 || spot.getSlot() > 6
          || !cats.add(spot.getCat()) || !slots.add(spot.getSlot())) return false;
    return true;
  }
}
