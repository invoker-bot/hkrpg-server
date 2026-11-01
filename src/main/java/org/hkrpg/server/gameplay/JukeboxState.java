package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.GameData;
import java.util.*;
import lombok.Getter;

/** One saved background mix, stored atomically with the owning Player. */
@Entity(useDiscriminator = false)
@Getter
public final class JukeboxState {
  // Pinned native PlayingMusicItemsInfo constants, not inferred from the UI slider.
  public static final int MAX_LAYERS = 5, MAX_VOLUME = 100;
  private int musicId;
  private boolean musicPaused;
  private boolean whiteNoisePaused;
  private List<Layer> layers = new ArrayList<>();

  public JukeboxState() {}

  public JukeboxState(int id, boolean paused, boolean noisePaused, List<Layer> layers) {
    musicId = id;
    musicPaused = paused;
    whiteNoisePaused = noisePaused;
    this.layers = new ArrayList<>(layers);
  }

  public List<Layer> getLayers() { return Collections.unmodifiableList(layers); }

  public boolean valid() {
    if (musicId < 0 || layers == null || layers.size() > MAX_LAYERS) return false;
    if (musicId != 0 && !isMusic(musicId, false)) return false;
    var seen = new HashSet<Integer>();
    for (var layer : layers) {
      if (layer == null || !seen.add(layer.id) || !isMusic(layer.id, true)
          || layer.volume < 0 || layer.volume > MAX_VOLUME) return false;
    }
    return true;
  }

  private static boolean isMusic(int id, boolean noise) {
    var music = GameData.getBackGroundMusicExcelMap().get(id);
    if (music == null) return false;
    var group = GameData.getBackGroundMusicGroupExcelMap().get(music.getGroupId());
    return group != null && noise == "WhiteNoise".equals(group.getType());
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Layer {
    private int id;
    private int volume;
    public Layer() {}
    public Layer(int id, int volume) { this.id = id; this.volume = volume; }
  }
}
