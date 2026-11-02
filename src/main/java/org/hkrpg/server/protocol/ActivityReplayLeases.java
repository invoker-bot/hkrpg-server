package org.hkrpg.server.protocol;

import java.util.*;

/** Native action packets have no cycle field; require a fresh data query after a cycle switch. */
public final class ActivityReplayLeases {
  private final Map<Integer,String> viewed=new HashMap<>();
  public void viewed(int panel,String cycle){viewed.put(panel,cycle);}
  public boolean accepts(int panel,String cycle){return cycle==null||viewed.containsKey(panel)&&Objects.equals(viewed.get(panel),cycle);}
}
