package org.hkrpg.server.gameplay;

import java.util.*;

/** Freeze a request's cycle across saves and callbacks, even when GM replaces the live window. */
public final class ActivityReplayScope {
  @FunctionalInterface public interface Action<T,E extends Exception>{T run() throws E;}
  private record Scope(GameplayState root,int panel,GameplayDefinitions.Window window){}
  private static final ThreadLocal<Deque<Scope>> SCOPES=ThreadLocal.withInitial(ArrayDeque::new);
  public static GameplayDefinitions.Window current(GameplayState root,int panel){
    for(var scope:SCOPES.get())if(scope.root()==root&&scope.panel()==panel)return scope.window();
    var windows=GameplayDefinitions.windows.stream().filter(w->w.panelId()==panel).toList();
    if(windows.isEmpty())return null;
    var first=windows.getFirst();
    if(windows.stream().anyMatch(w->!Objects.equals(w.replayId(),first.replayId())||!Objects.equals(w.replayKey(),first.replayKey())))
      throw new IllegalStateException("Mixed replay identity in one activity panel");
    return first;
  }
  public static <T,E extends Exception>T in(GameplayState root,int panel,Action<T,E> action)throws E {
    for(var scope:SCOPES.get())if(scope.root()==root&&scope.panel()==panel)return action.run();
    var scope=new Scope(root,panel,current(root,panel));var stack=SCOPES.get();stack.push(scope);
    try{return action.run();}finally{stack.pop();if(stack.isEmpty())SCOPES.remove();}
  }
  public static String rewardKey(GameplayState root,int panel,String original){
    var window=current(root,panel);
    return window==null||window.replayId()==null?original:"activity-replay:"+window.replayId()+":"+original;
  }
  public static String identity(GameplayState root,int panel){
    var window=current(root,panel);return window==null?null:window.replayId();
  }
  private ActivityReplayScope(){}
}
