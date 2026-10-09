package org.hkrpg.server.protocol;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.DynamicMessage;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

public final class ProtocolSchema {
    private final Map<String, Descriptor> messages = new TreeMap<>();

    public static ProtocolSchema load(String version) {
        try (var stream = ProtocolSchema.class.getResourceAsStream("/protocol/" + version + "/descriptor.pb")) {
            if (stream == null) throw new IllegalStateException("Missing protocol descriptor: " + version);
            var set = FileDescriptorSet.parseFrom(stream);
            var files = new HashMap<String, FileDescriptor>();
            var schema = new ProtocolSchema();
            for (var file : set.getFileList()) {
                var dependencies = file.getDependencyList().stream().map(name -> {
                    var dependency = files.get(name);
                    if (dependency == null) throw new IllegalStateException("Unresolved descriptor dependency: " + name);
                    return dependency;
                }).toArray(FileDescriptor[]::new);
                var descriptor = FileDescriptor.buildFrom(file, dependencies);
                files.put(file.getName(), descriptor);
                descriptor.getMessageTypes().forEach(schema::register);
            }
            if (version.equals("4.6")) {
                try (var aliases = ProtocolSchema.class.getResourceAsStream("/protocol/4.6/aliases.json")) {
                    if (aliases == null) throw new IllegalStateException("Missing 4.6 aliases");
                    Map<String, String> names = new Gson().fromJson(new InputStreamReader(aliases, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() {}.getType());
                    names.forEach((alias, original) -> {
                        var type = schema.find(original);
                        if (type != null) schema.messages.put(alias, type);
                    });
                }
            }
            return schema;
        } catch (IOException | DescriptorValidationException ex) {
            throw new IllegalStateException("Invalid protocol descriptor: " + version, ex);
        }
    }

    private void register(Descriptor descriptor) {
        String name = descriptor.getFullName().replaceFirst("^legacy\\.", "");
        if (messages.putIfAbsent(name, descriptor) != null) throw new IllegalStateException("Duplicate message: " + name);
        descriptor.getNestedTypes().forEach(this::register);
    }

    public Descriptor find(String name) { return messages.get(name); }
    public Descriptor message(String name) {
        var descriptor = find(name);
        if (descriptor == null) throw new ProtocolException("Unmapped message: " + name);
        return descriptor;
    }
    public DynamicMessage parse(String name, byte[] bytes) throws IOException {
        return DynamicMessage.parseFrom(message(name), bytes);
    }
    public Collection<Descriptor> messages() { return Collections.unmodifiableCollection(messages.values()); }
}
