package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import emu.lunarcore.data.config.GroupInfo;
import java.io.IOException;

/** Preserve native ownership for proven shapes without changing the inherited group default. */
public final class AtmosphereGroupSourceAdapter implements TypeAdapterFactory {
  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    if (type.getRawType() != GroupInfo.class) return null;
    var delegate = gson.getDelegateAdapter(this, type);
    return new TypeAdapter<T>() {
      @Override public T read(JsonReader reader) throws IOException {
        var raw = JsonParser.parseReader(reader);
        T value = delegate.fromJsonTree(raw);
        if (value instanceof GroupInfo group && raw.isJsonObject()) {
          var row = raw.getAsJsonObject();
          var tag = row.get("$type");
          var initial = row.get("LoadOnInitial");
          boolean dormant = initial == null || initial.isJsonPrimitive()
              && initial.getAsJsonPrimitive().isBoolean() && !initial.getAsBoolean();
          if (tag != null && tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()
              && "RPG.GameCore.RtLevelGroupInfo".equals(tag.getAsString())) {
            if (group.getCategory() == GroupInfo.GroupCategory.Atmosphere
                && !row.has("LoadCondition") && !row.has("UnloadCondition")
                && !row.has("AtmosphereCondition")
                && dormant && !row.has("LoadSide")) group.setLoadSide(GroupInfo.GroupLoadSide.Client);
            ClientNpcGroups.markSource(group, row);
          }
        }
        return value;
      }
      @Override public void write(JsonWriter writer, T value) throws IOException {
        delegate.write(writer, value);
      }
    }.nullSafe();
  }
}
