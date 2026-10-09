package org.hkrpg.server.protocol;

import com.google.gson.GsonBuilder;
import com.google.protobuf.Descriptors.Descriptor;
import java.nio.file.*;
import java.util.*;

/** A structural inventory, deliberately independent of runtime/client acceptance. */
public final class ProtocolAudit {
    public static Map<String, Object> report() {
        var bridge = ProtocolBridge.forVersion("4.6.0");
        var rows = new ArrayList<Map<String, Object>>();
        for (var message : bridge.coreSchema().messages()) {
            String name = message.getFullName().replaceFirst("^legacy\\.", "");
            String targetName = ProtocolBridge.clientTypeName(name);
            var target = bridge.clientSchema().find(targetName);
            var gaps = new TreeSet<String>();
            if (target == null) gaps.add("message-missing");
            else inspect(message, target, gaps, new HashSet<>(), name);
            var row = new LinkedHashMap<String, Object>();
            row.put("coreType", name);
            row.put("clientType", target == null ? null : target.getFullName());
            row.put("coreFieldCount", message.getFields().size());
            row.put("status", gaps.isEmpty() ? "structurally-mapped" : "gaps");
            row.put("gaps", gaps);
            rows.add(row);
        }
        long mapped = rows.stream().filter(row -> row.get("status").equals("structurally-mapped")).count();
        var result = new LinkedHashMap<String, Object>();
        result.put("designTarget", "4.6.0");
        result.put("coreBaseline", "4.2.0");
        result.put("coreTypes", rows.size());
        result.put("structurallyMapped", mapped);
        result.put("typesWithGaps", rows.size() - mapped);
        result.put("clientAccepted", false);
        result.put("note", "结构匹配不证明玩法语义相同，也不证明客户端接受；空响应和缺失字段均需单独验收。");
        result.put("messages", rows);
        return result;
    }

    private static void inspect(Descriptor source, Descriptor target, Set<String> gaps, Set<String> visited, String path) {
        String pair = source.getFullName() + ":" + target.getFullName();
        if (!visited.add(pair)) return;
        for (var field : source.getFields()) {
            var mapped = ProtocolBridge.mappedField(field, target, true);
            String location = path + "." + field.getName();
            if (mapped == null) gaps.add(location + ":unmapped");
            else if (!ProtocolBridge.compatible(field, mapped)) gaps.add(location + ":type-change");
            else if (field.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                inspect(field.getMessageType(), mapped.getMessageType(), gaps, visited, location);
            } else if (field.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.ENUM) {
                Set<String> names = new HashSet<>();
                mapped.getEnumType().getValues().forEach(value -> names.add(ProtocolBridge.enumName(value)));
                field.getEnumType().getValues().forEach(value -> {
                    if (!names.contains(ProtocolBridge.enumName(value))) gaps.add(location + ":enum-" + value.getName());
                });
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report());
        if (args.length == 1) {
            var output = Path.of(args[0]);
            if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, json + "\n");
            var summary = new LinkedHashMap<>(report());
            summary.remove("messages");
            System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(summary));
        } else System.out.println(json);
    }
    private ProtocolAudit() {}
}
