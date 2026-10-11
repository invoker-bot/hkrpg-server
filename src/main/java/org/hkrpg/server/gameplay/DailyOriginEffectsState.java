package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** Original initial-report effects share their player's saved daily acceptance. */
@Entity(useDiscriminator = false)
public final class DailyOriginEffectsState {
  private int version = 1;
  private Map<String, Receipt> records = new HashMap<>();

  public DailyOriginEffectsState() {}
  DailyOriginEffectsState(DailyOriginEffectsState previous) { records.putAll(previous.records); }
  public int getVersion() { return version; }
  public Map<String, Receipt> getRecords() { return records; }
  boolean valid() {
    if (version != 1 || records == null || records.size() > 4096) return false;
    for (var entry : records.entrySet()) {
      var row = entry.getValue();
      if (row == null || !row.valid() || !entry.getKey().equals("3000601:" + row.day)) return false;
    }
    return true;
  }

  @Entity(useDiscriminator = false)
  public static final class Actor {
    private int group, instance, npc, entity;
    private String source;
    protected Actor() {}
    Actor(int instance, int npc, int entity, String source) {
      group=84;this.instance=instance;this.npc=npc;this.entity=entity;this.source=source;
    }
    public int getGroup() { return group; }
    public int getInstance() { return instance; }
    public int getNpc() { return npc; }
    public int getEntity() { return entity; }
    public String getSource() { return source; }
    boolean valid() {
      return group==84 && entity>0 && source!=null && source.matches("[0-9a-f]{64}")
          && (instance==400001 && npc==10209 || instance==400002 && npc==10215);
    }
  }

  @Entity(useDiscriminator = false)
  public static final class Receipt {
    private int uid, main, sub, plane, floor, entry, dimension, story;
    private long day, at;
    private String mainAcceptance, initialAcceptance, source, report, event;
    private Map<Integer, Actor> actors = new HashMap<>();
    protected Receipt() {}
    Receipt(int uid, int entry, long day, long at, String mainAcceptance,
        String initialAcceptance, String source, Map<Integer, Actor> actors) {
      this.uid=uid;main=3000601;sub=300060001;plane=20002;floor=20002001;this.entry=entry;
      this.day=day;this.at=at;this.mainAcceptance=mainAcceptance;this.initialAcceptance=initialAcceptance;
      this.source=source;report="Mission_300060001";event=UUID.randomUUID().toString();this.actors.putAll(actors);
    }
    public int getUid() { return uid; }
    public int getMain() { return main; }
    public int getSub() { return sub; }
    public int getPlane() { return plane; }
    public int getFloor() { return floor; }
    public int getEntry() { return entry; }
    public int getDimension() { return dimension; }
    public int getStory() { return story; }
    public long getDay() { return day; }
    public long getAt() { return at; }
    public String getMainAcceptance() { return mainAcceptance; }
    public String getInitialAcceptance() { return initialAcceptance; }
    public String getSource() { return source; }
    public String getReport() { return report; }
    public String getEvent() { return event; }
    public Map<Integer, Actor> getActors() { return actors; }
    boolean valid() {
      if (uid<=0 || main!=3000601 || sub!=300060001 || plane!=20002 || floor!=20002001 || entry<=0
          || dimension!=0 || story!=0 || day<=0 || at<=0 || DailyManager.day(java.time.Instant.ofEpochSecond(at))!=day
          || !DailyOriginEffects.SOURCE.equals(source) || !"Mission_300060001".equals(report)
          || !uuid(mainAcceptance) || !uuid(initialAcceptance) || !uuid(event)
          || actors==null || !actors.keySet().equals(Set.of(400001,400002))) return false;
      for(var pair:actors.entrySet())if(pair.getValue()==null || !pair.getValue().valid() || pair.getKey()!=pair.getValue().instance)return false;
      return actors.get(400001).entity!=actors.get(400002).entity;
    }
  }

  static boolean uuid(String value) {
    if (value==null) return false;
    try { return UUID.fromString(value).toString().equals(value); }
    catch(IllegalArgumentException invalid) { return false; }
  }
}
