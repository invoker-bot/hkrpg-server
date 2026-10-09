package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueEffectHide46.Behavior;
import org.hkrpg.server.gameplay.RogueMutexAttach46.MonoEffect;

/**
 * Ordinary MonoEffectBehavior activation; initialization, visibility and Unity access stay bound.
 */
public final class RogueEffectActive46 {
  private RogueEffectActive46() {}

  public static final class GameObject {
    public long cachedPointer;
  }

  public static final class CharaPlugin {
    public long cachedPointer;
  }

  public interface World {
    void initialize(Behavior self);

    GameObject gameObject(MonoEffect instance);

    void setVisible(Behavior self, boolean active);

    Object charaPluginType();

    CharaPlugin findCharaPlugin(GameObject gameObject, Object type);

    void enableCharaEffect(CharaPlugin plugin, boolean active, boolean immediate);
  }

  public static void setActive(Behavior self, boolean active, World world) {
    require(self, "effect active behavior");
    if (!self.effectInited) require(world, "effect initialization binding").initialize(self);
    MonoEffect first = owner(self.ownerMonoBehavior);
    if (first.isActive == active) return;
    first.isActive = active;
    World bound = require(world, "effect game object binding");
    GameObject firstObject = bound.gameObject(first);
    if (firstObject == null || firstObject.cachedPointer == 0) return;
    bound.setVisible(self, active);
    MonoEffect current = owner(self.ownerMonoBehavior);
    GameObject currentObject = require(bound.gameObject(current), "current effect game object");
    Object type = bound.charaPluginType();
    CharaPlugin plugin = bound.findCharaPlugin(currentObject, type);
    if (plugin == null || plugin.cachedPointer == 0) return;
    bound.enableCharaEffect(plugin, active, true);
  }

  private static MonoEffect owner(Object value) {
    if (!(value instanceof MonoEffect result))
      throw new IllegalArgumentException("Missing or unknown MonoEffect owner projection");
    return result;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
