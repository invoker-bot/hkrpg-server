package org.hkrpg.server.gameplay;

import java.util.*;

/** A replay owns selected keys; hidden original entries remain in the original save. */
final class ActivityReplayMap<K,V> extends AbstractMap<K,V> {
  private final Map<K,V> original,cycle;
  private final Set<K> owned;
  ActivityReplayMap(Map<K,V> original,Map<K,V> cycle,Set<K> owned){
    this.original=Objects.requireNonNull(original);this.cycle=Objects.requireNonNull(cycle);this.owned=Set.copyOf(owned);
  }
  private Map<K,V> owner(Object key){return key!=null&&owned.contains(key)?cycle:original;}
  @Override public V get(Object key){return owner(key).get(key);}
  @Override public boolean containsKey(Object key){return owner(key).containsKey(key);}
  @Override public V put(K key,V value){return owner(key).put(key,value);}
  @Override public V remove(Object key){return owner(key).remove(key);}
  @Override public Set<Entry<K,V>> entrySet(){
    var keys=new LinkedHashSet<K>();original.keySet().stream().filter(k->!owned.contains(k)).forEach(keys::add);
    cycle.keySet().stream().filter(owned::contains).forEach(keys::add);
    return new AbstractSet<>(){
      @Override public int size(){return keys.size();}
      @Override public Iterator<Entry<K,V>> iterator(){
        var iterator=keys.iterator();
        return new Iterator<>(){
          K current;
          @Override public boolean hasNext(){return iterator.hasNext();}
          @Override public Entry<K,V> next(){
            current=iterator.next();
            return new SimpleEntry<>(current,get(current)){
              @Override public V setValue(V value){var previous=put(getKey(),value);super.setValue(value);return previous;}
            };
          }
          @Override public void remove(){if(current==null)throw new IllegalStateException();ActivityReplayMap.this.remove(current);iterator.remove();current=null;}
        };
      }
    };
  }
}
