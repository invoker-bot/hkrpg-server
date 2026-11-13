package emu.lunarcore.data.config;

import lombok.Getter;
import lombok.Setter;

/** Original name: LevelNPCInfo */
@Getter
public class NpcInfo extends ObjectInfo {
  private int NPCID;
  private boolean IsClientOnly;
  private boolean LoadOnInitial = true;
  private DialogInfo Dialog;

  @Getter
  public static class DialogInfo {
    private String LevelGraph;
    // Runtime-only so existing typed resource fingerprints keep their original serialized shape.
    @Setter private transient LoadConditionInfo EnableCondition;
  }
}
