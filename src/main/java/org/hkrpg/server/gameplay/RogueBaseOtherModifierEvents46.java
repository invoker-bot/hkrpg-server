package org.hkrpg.server.gameplay;

/** Ordinary other-modifier callbacks over the existing shared destruction state. */
public final class RogueBaseOtherModifierEvents46 {
  private RogueBaseOtherModifierEvents46() {}

  /** Known LIMAAJAJPKJ projection; the effect list remains an explicitly bound native object. */
  public static class Destroyed {
    public Object modifier, effects, sourceEntity;

    public Destroyed(Object modifier, Object effects, Object sourceEntity) {
      this.modifier = modifier;
      this.effects = effects;
      this.sourceEntity = sourceEntity;
    }
  }

  /** Known GEHAPKHIMNE projection, distinct from the destruction payload hierarchy. */
  public static class FadeFinished {
    public Object eventManager;
    public Object modifier, effects, sourceEntity;

    public FadeFinished(Object modifier, Object effects, Object sourceEntity) {
      this.modifier = modifier;
      this.effects = effects;
      this.sourceEntity = sourceEntity;
    }
  }

  public interface World {
    void virtualEffectSlot21(
        RogueModifierDestroy46.State self, Object effects, boolean fadeFinished);
  }

  public static void onOtherDestroy(RogueModifierDestroy46.State self, Object event, World world) {
    if (!(event instanceof Destroyed payload)) return;
    if (payload.modifier == self) return;
    activeSelf(self);
    if (self.cleanup.candidate.state != 1) return;
    Object effects = payload.effects;
    bound(world).virtualEffectSlot21(self, effects, false);
  }

  public static void onOtherFadeFinished(
      RogueModifierDestroy46.State self, Object event, World world) {
    if (!(event instanceof FadeFinished payload)) return;
    if (payload.modifier == self) return;
    activeSelf(self);
    if (self.cleanup.candidate.state != 1) return;
    Object effects = payload.effects;
    bound(world).virtualEffectSlot21(self, effects, true);
  }

  private static void activeSelf(RogueModifierDestroy46.State self) {
    if (self == null) throw new IllegalArgumentException("Missing active other-event receiver");
  }

  private static World bound(World world) {
    if (world == null)
      throw new IllegalArgumentException("Missing other-event effect dispatch world");
    return world;
  }
}
