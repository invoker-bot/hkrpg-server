package org.hkrpg.server;

import com.google.gson.GsonBuilder;
import emu.lunarcore.*;
import emu.lunarcore.data.resource.ResourceType;
import org.reflections.Reflections;
import java.nio.file.Path;
import java.util.*;

public final class HkrpgServer {
    public static void main(String[] args) {
        try {
            var options = BootstrapOptions.parse(args);
            if (options.help()) {
                System.out.println("HKRPG Server (Java 21), protocol baseline " + GameConstants.VERSION + "\n" +
                    "Usage: java -jar hkrpg-server.jar [--mode=both|dispatch|game|database] " +
                    "[--no-console] [--no-handbook] [--check]\n" +
                    "--check validates configuration and game resources without starting services.");
                return;
            }
            var config = LunarCore.getConfig();
            var protocol = org.hkrpg.server.protocol.ActiveProtocol.get();
            boolean needsGame = options.mode() == BootstrapOptions.Mode.BOTH || options.mode() == BootstrapOptions.Mode.GAME;
            ResourcePreflight.Report resources = needsGame
                ? ResourcePreflight.inspect(Path.of(config.resourceDir), protocol.version(), requiredTables())
                : new ResourcePreflight.Report(List.of(), List.of());
            var errors = new ArrayList<>(resources.errors());
            if (config.httpServer.bindPort < 1 || config.httpServer.bindPort > 65535) errors.add("Invalid HTTP bindPort");
            if (config.gameServer.bindPort < 1 || config.gameServer.bindPort > 65535) errors.add("Invalid game bindPort");
            if (config.gameServer.kcpTimeout <= 0) errors.add("kcpTimeout must be positive");
            if (options.check()) {
                var report = new LinkedHashMap<String, Object>();
                report.put("server", "hkrpg-server");
                report.put("clientVersion", protocol.version());
                report.put("designTarget", org.hkrpg.server.protocol.ProtocolBridge.DESIGN_TARGET);
                report.put("experimentalProtocol", protocol.experimental());
                report.put("clientAccepted", false);
                report.put("mode", options.mode());
                report.put("ready", errors.isEmpty());
                report.put("errors", errors);
                report.put("warnings", resources.warnings());
                System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
                if (!errors.isEmpty()) System.exit(1);
                return;
            }
            resources.warnings().forEach(w -> LunarCore.getLogger().warn(w));
            if (protocol.experimental()) LunarCore.getLogger().warn("4.6.0 实验协议：仅部分字段映射经过服务端测试，资源及游戏客户端验收尚未完成");
            if (!errors.isEmpty()) throw new IllegalStateException(String.join("\n", errors));
            DefaultData.install(Path.of(config.dataDir));
            LunarCore.main(options.coreArguments());
        } catch (Exception | ExceptionInInitializerError e) {
            Throwable cause = e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
            System.err.println("HKRPG startup failed: " + cause.getMessage());
            System.exit(1);
        }
    }

    private static List<String> requiredTables() {
        var names = new TreeSet<String>();
        new Reflections("emu.lunarcore.data.excel").getTypesAnnotatedWith(ResourceType.class)
            .forEach(type -> {
                var definition = type.getAnnotation(ResourceType.class);
                var optional = Set.of(definition.optionalNames());
                Arrays.stream(definition.name()).filter(name -> !optional.contains(name)).forEach(names::add);
            });
        if (names.isEmpty()) throw new IllegalStateException("No resource definitions found in this distribution");
        return List.copyOf(names);
    }
    private HkrpgServer() {}
}
