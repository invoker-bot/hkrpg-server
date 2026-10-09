package emu.lunarcore.game.quest;

import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import dev.morphia.Morphia;
import emu.lunarcore.game.player.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestPersistenceTest {
    @Test void actualPlayerDocumentRestoresQuestProgressAndClaimReservation() throws Exception {
        var server = new MongoServer(new MemoryBackend());
        var address = server.bind();
        try (var client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
            var store = Morphia.createDatastore(client, "quest-persistence-test");
            store.getMapper().map(Player.class, QuestState.class);
            var player = new Player();
            var uid = Player.class.getDeclaredField("uid");
            uid.setAccessible(true);
            uid.setInt(player, 123456789);
            var state = new QuestState(123);
            state.advance(40, 100, 10);
            player.getQuestStates().put(123, state);
            store.save(player);
            var loaded = store.find(Player.class).first();
            assertNotNull(loaded);
            assertNotNull(loaded.getQuestManager());
            assertEquals(40, loaded.getQuestStates().get(123).getProgress());
            var restored = loaded.getQuestStates().get(123);
            restored.advance(60, 100, 20);
            assertTrue(restored.reserveReward());
            store.save(loaded);
            var again = store.find(Player.class).first();
            assertEquals(QuestState.Phase.CLAIMING, again.getQuestStates().get(123).getPhase());
            assertFalse(again.getQuestStates().get(123).reserveReward());
        } finally { server.shutdownNow(); }
    }
}
