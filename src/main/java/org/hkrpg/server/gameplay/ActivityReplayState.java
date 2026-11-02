package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** A rerun adds a new namespace; original progress and reward receipts are never erased. */
@Entity(useDiscriminator = false)
public final class ActivityReplayState {
  private int version = 1;
  private Map<String, LoginRun> loginRuns = new HashMap<>();
  private Map<String, MaterialRun> materialRuns = new HashMap<>();
  private Map<Integer,MaterialSubmitState> legacyMaterials = new HashMap<>();
  private Map<String,MusicRun> musicRuns = new HashMap<>();

  @Entity(useDiscriminator=false)
  public static final class MusicRun {
    private String replayId,replayKey;
    private GameplayState.Rhythm state=new GameplayState.Rhythm();
    protected MusicRun(){}
    MusicRun(GameplayDefinitions.Window w){replayId=w.replayId();replayKey=w.replayKey();}
    boolean matches(GameplayDefinitions.Window w){return Objects.equals(replayId,w.replayId())&&Objects.equals(replayKey,w.replayKey())&&state!=null;}
  }
  GameplayState.Rhythm music(GameplayDefinitions.Window window){return musicRun(window).state;}
  void music(GameplayDefinitions.Window window,GameplayState.Rhythm state){musicRun(window).state=Objects.requireNonNull(state);}
  private MusicRun musicRun(GameplayDefinitions.Window window){
    if(version!=1||musicRuns==null||window.replayId()==null)throw new IllegalStateException("Damaged music replay state");
    if(!musicRuns.containsKey(window.replayId()))musicRuns.put(window.replayId(),new MusicRun(window));
    var run=musicRuns.get(window.replayId());
    if(run==null||!run.matches(window))throw new IllegalStateException("Music replay identity changed");
    return run;
  }

  MaterialSubmitState legacyMaterial(int panel){
    if(version!=1||legacyMaterials==null)return null;
    if(!legacyMaterials.containsKey(panel))legacyMaterials.put(panel,new MaterialSubmitState());
    return legacyMaterials.get(panel);
  }
  void legacyMaterial(int panel,MaterialSubmitState state){
    if(version!=1||legacyMaterials==null)throw new IllegalStateException("Damaged material state");
    legacyMaterials.put(panel,Objects.requireNonNull(state));
  }

  @Entity(useDiscriminator=false)
  public static final class MaterialRun {
    private String replayId,replayKey;
    private MaterialSubmitState state=new MaterialSubmitState();
    protected MaterialRun(){}
    MaterialRun(GameplayDefinitions.Window window){replayId=window.replayId();replayKey=window.replayKey();}
    boolean matches(GameplayDefinitions.Window window){return Objects.equals(replayId,window.replayId())&&Objects.equals(replayKey,window.replayKey());}
  }

  MaterialSubmitState material(GameplayDefinitions.Window window){
    var run=materialRun(window);return run==null?null:run.state;
  }
  void material(GameplayDefinitions.Window window,MaterialSubmitState state){
    var run=materialRun(window);
    if(run==null)throw new IllegalStateException("Damaged material replay state");
    run.state=Objects.requireNonNull(state);
  }
  private MaterialRun materialRun(GameplayDefinitions.Window window){
    if(version!=1||materialRuns==null)return null;
    if(!materialRuns.containsKey(window.replayId()))materialRuns.put(window.replayId(),new MaterialRun(window));
    var run=materialRuns.get(window.replayId());return run!=null&&run.matches(window)?run:null;
  }

  @Entity(useDiscriminator = false)
  public static final class LoginRun {
    private String replayId;
    private String replayKey;
    private Map<Integer, GameplayState.LoginDays> days = new HashMap<>();
    protected LoginRun() {}
    LoginRun(GameplayDefinitions.Window window) { replayId=window.replayId();replayKey=window.replayKey(); }
    Map<Integer,GameplayState.LoginDays> days() { return days; }
    boolean matches(GameplayDefinitions.Window window) {
      return Objects.equals(replayId,window.replayId())&&Objects.equals(replayKey,window.replayKey())&&days!=null;
    }
  }

  Map<Integer,GameplayState.LoginDays> login(GameplayDefinitions.Window window,boolean create) {
    if(version!=1||loginRuns==null||window.replayId()==null)throw new IllegalStateException("Damaged activity replay state");
    var run=loginRuns.get(window.replayId());
    if(run==null&&create){run=new LoginRun(window);loginRuns.put(window.replayId(),run);}
    if(run!=null&&!run.matches(window))throw new IllegalStateException("Replay identity changed");
    return run==null?Map.of():run.days();
  }
}
