package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import emu.lunarcore.data.config.LoadConditionInfo;
import emu.lunarcore.data.config.NpcInfo;
import java.io.IOException;

/** Read native dialogue gates without changing legacy serialized resource fingerprints. */
public final class NpcDialogueSourceAdapter implements TypeAdapterFactory {
  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    if (type.getRawType() != NpcInfo.DialogInfo.class) return null;
    var delegate = gson.getDelegateAdapter(this, type);
    return new TypeAdapter<T>() {
      @Override public T read(JsonReader reader) throws IOException {
        var raw = JsonParser.parseReader(reader);
        T value = delegate.fromJsonTree(raw);
        if (value instanceof NpcInfo.DialogInfo dialogue && raw.isJsonObject())
          dialogue.setEnableCondition(gson.fromJson(raw.getAsJsonObject().get("EnableCondition"), LoadConditionInfo.class));
        return value;
      }

      @Override public void write(JsonWriter writer, T value) throws IOException {
        delegate.write(writer, value);
      }
    }.nullSafe();
  }
}
