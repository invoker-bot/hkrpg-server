package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.proto.DispatchRegionDataOuterClass.DispatchRegionData;
import emu.lunarcore.server.game.RegionInfo;
import emu.lunarcore.server.http.HttpServer;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CnDispatchRoutesTest {
    final Map<Field, Object> saved = new HashMap<>();
    MongoServer backend;
    DatabaseManager database;
    HttpServer server;
    HttpClient client;

    @BeforeEach void setup() throws Exception {
        for (String name : List.of("config", "accountDatabase")) {
            var field = LunarCore.class.getDeclaredField(name);
            field.setAccessible(true);
            saved.put(field, field.get(null));
        }
        set("config", new Config());
        backend = new MongoServer(new MemoryBackend());
        var address = backend.bind();
        var info = new Config.DatabaseInfo();
        info.useInternal = false;
        info.uri = "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true";
        database = new DatabaseManager(info, LunarCore.ServerType.DISPATCH);
        set("accountDatabase", database);
        saveRegion("Local CN", "http://127.0.0.1:18080");
        server = new HttpServer(LunarCore.ServerType.DISPATCH);
        server.getApp().start("127.0.0.1", 0);
        client = HttpClient.newHttpClient();
    }

    void set(String name, Object value) throws Exception {
        saved.keySet().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow().set(null, value);
    }

    void saveRegion(String name, String address) {
        var raw = Map.of("id", "hkrpg_local", "name", name, "gateAddress", address, "up", true);
        database.save(new Gson().fromJson(new Gson().toJson(raw), RegionInfo.class));
    }

    @AfterEach void cleanup() throws Exception {
        if (client != null) client.close();
        if (server != null) server.getApp().stop();
        if (database != null) database.close();
        if (backend != null) backend.shutdownNow();
        for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    HttpResponse<String> dispatch(String version) throws Exception {
        var url = "http://127.0.0.1:" + server.getApp().port() + "/query_dispatch";
        if (version != null) url += "?version=" + version;
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    DispatchRegionData regionList(String version) throws Exception {
        var response = dispatch(version);
        assertEquals(200, response.statusCode());
        return DispatchRegionData.parseFrom(Base64.getDecoder().decode(response.body()));
    }

    @Test void releaseChannelSelectsItsOwnSdkEnvironmentWithoutCrossingTheCache() throws Exception {
        var cases = new String[][] {
            {"CNPRODWin4.2.0", "0"}, {"OSPRODWin4.2.0", "2"},
            {"CNRELWin4.2.0", "0"}, {"OSRELWin4.2.0", "2"},
            {"CNPRODAndroid4.2.0", "0"}, {"4.2.0", "2"}, {null, "2"},
            {"OSPRODWin4.2.0_CNPROD", "2"}, {"XCNPRODWin4.2.0", "2"},
            {"CNPRODWin4.2.0", "0"}
        };
        for (var item : cases) {
            var regions = regionList(item[0]);
            assertEquals(1, regions.getRegionList().length(), item[0]);
            var region = regions.getRegionList().get(0);
            assertEquals(item[1], region.getEnvType(), item[0]);
            assertEquals("hkrpg_local", region.getName());
            assertEquals("http://127.0.0.1:18080/query_gateway", region.getDispatchUrl());
        }
        // The compatibility API and stored record retain the upstream OS default.
        var legacy = DispatchRegionData.parseFrom(Base64.getDecoder().decode(server.getRegionList()));
        assertEquals("2", legacy.getRegionList().get(0).getEnvType());
        assertEquals("2", database.getObjectByField(RegionInfo.class, "id", "hkrpg_local").toProto().getEnvType());
    }

    @Test void refreshInvalidatesBothChannelVariantsOfTheRegionList() throws Exception {
        assertEquals("0", regionList("CNPRODWin4.2.0").getRegionList().get(0).getEnvType());
        regionList("OSPRODWin4.2.0");
        saveRegion("Updated local", "http://127.0.0.1:19090");
        server.forceRegionListRefresh();
        for (var item : new String[][] {{"CNPRODWin4.2.0", "0"}, {"OSPRODWin4.2.0", "2"}}) {
            var region = regionList(item[0]).getRegionList().get(0);
            assertEquals(item[1], region.getEnvType());
            assertEquals("Updated local", region.getDisplayName());
            assertEquals("http://127.0.0.1:19090/query_gateway", region.getDispatchUrl());
        }
    }

    @Test void channelSelectionCannotBypassTheWireVersionCheck() throws Exception {
        regionList("CNPRODWin4.2.0");
        for (String version : List.of("CNPRODWin4.6.0", "OSPRODWin4.6.0", "CNDEVWin4.2.0")) {
            assertEquals(400, dispatch(version).statusCode(), version);
        }
    }
}
