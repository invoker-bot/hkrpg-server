package emu.lunarcore.game.quest;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.proto.GetQuestDataScRspOuterClass.GetQuestDataScRsp;
import emu.lunarcore.proto.QuestStatusOuterClass.QuestStatus;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestProgressTest {
    private static final Gson GSON = new Gson();
    private static QuestExcel definition(int id, String finishType, int goal) {
        var quest = GSON.fromJson("{\"QuestID\":" + id + ",\"QuestType\":1,\"RewardID\":1999000002,\"FinishWayID\":" + id + ",\"UnlockType\":\"AutoUnlock\"}", QuestExcel.class);
        var way = GSON.fromJson("{\"ID\":" + id + ",\"Progress\":" + goal + ",\"FinishType\":\"" + finishType + "\"}", FinishWayExcel.class);
        var reward = GSON.fromJson("{\"RewardID\":1999000002,\"Hcoin\":60}", RewardExcel.class);
        reward.onLoad();
        GameData.getQuestExcelMap().add(quest);
        GameData.getFinishWayExcelMap().add(way);
        GameData.getRewardExcelMap().add(reward);
        return quest;
    }

    @Test void earnedProgressPersistsAndConcurrentClaimsGrantOnce() throws Exception {
        int id = 1999000010;
        definition(id, "ConsumeStamina", 100);
        var player = new QuestHandlerTest.TestPlayer();
        player.getQuestManager().refreshUnlocks();
        player.getQuestManager().onStaminaConsumed(40);
        assertEquals(Retcode.QUEST_NOT_FINISH, player.getQuestManager().claim(id).retcode());
        var persisted = GSON.toJson(player.getQuestStates().get(id));
        player.getQuestStates().put(id, GSON.fromJson(persisted, QuestState.class));
        player.getQuestManager().onStaminaConsumed(60);
        assertEquals(QuestState.Phase.FINISHED, player.getQuestStates().get(id).getPhase());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Retcode>>();
            for (int i = 0; i < 20; i++) tasks.add(() -> player.getQuestManager().claim(id).retcode());
            long success = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get() == Retcode.SUCC) success++;
            assertEquals(1, success);
        }
        assertEquals(1, player.grants);
        var reloaded = GSON.fromJson(GSON.toJson(player.getQuestStates().get(id)), QuestState.class);
        player.getQuestStates().put(id, reloaded);
        assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getQuestManager().claim(id).retcode());
    }

    @Test void clientCannotFinishServerMeasuredObjective() {
        int id = 1999000011;
        definition(id, "ConsumeStamina", 100);
        var player = new QuestHandlerTest.TestPlayer();
        player.getQuestManager().refreshUnlocks();
        assertEquals(Retcode.QUEST_STATUS_ERROR, player.getQuestManager().finishByClient(id));
        assertEquals(0, player.getQuestStates().get(id).getProgress());
    }

    @Test void configuredClientCompletionDoesNotGrantRewardUntilClaim() {
        int id = 1999000012;
        definition(id, "FinishQuestByClient", 1);
        var player = new QuestHandlerTest.TestPlayer();
        player.getQuestManager().refreshUnlocks();
        assertEquals(Retcode.SUCC, player.getQuestManager().finishByClient(id));
        assertEquals(0, player.grants);
        assertEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
        assertEquals(1, player.grants);
    }

    @Test void interruptedReservationIsNeverAutomaticallyGrantedAgain() {
        int id = 1999000013;
        definition(id, "AutoFinish", 1);
        var player = new QuestHandlerTest.TestPlayer();
        player.getQuestManager().refreshUnlocks();
        assertTrue(player.getQuestStates().get(id).reserveReward());
        player.getQuestStates().put(id, GSON.fromJson(GSON.toJson(player.getQuestStates().get(id)), QuestState.class));
        assertEquals(Retcode.SYSTEM_BUSY, player.getQuestManager().claim(id).retcode());
        assertEquals(0, player.grants);
    }

    @Test void achievementsAreReturnedWithActualProgress() throws Exception {
        int id = 1999000014;
        definition(id, "ConsumeStamina", 10);
        var player = new QuestHandlerTest.TestPlayer();
        player.getQuestManager().refreshUnlocks();
        var packet = new PacketGetQuestDataScRsp(player);
        var data = GetQuestDataScRsp.parseFrom(packet.getData().toByteArray());
        var quest = java.util.stream.StreamSupport.stream(data.getQuestList().spliterator(), false)
            .filter(row -> row.getId() == id).findFirst().orElseThrow();
        assertEquals(QuestStatus.QUEST_DOING, quest.getStatus());
        assertEquals(0, quest.getFinishTime());
        assertEquals(0, quest.getProgress());
        assertEquals(0, player.getQuestManager().achievementCount());
    }

    @Test void negativeStaminaSpendCannotIncreaseStamina() {
        var player = new QuestHandlerTest.TestPlayer();
        int before = player.getStamina();
        player.spendStamina(-10);
        assertEquals(before, player.getStamina());
    }

    @Test void achievementCountUsesMetadataRatherThanQuestType() {
        int id = 1999000015;
        definition(id, "AutoFinish", 1);
        // The 4.6 snapshot links achievements to ordinary QuestType=1 quests.
        var quest = GSON.fromJson("{\"QuestID\":" + id + ",\"QuestType\":1,\"RewardID\":1999000002,\"FinishWayID\":" + id + ",\"UnlockType\":\"AutoUnlock\"}", QuestExcel.class);
        GameData.getQuestExcelMap().add(quest);
        var achievement = GSON.fromJson("{\"AchievementID\":1999000900,\"QuestID\":" + id + "}", AchievementExcel.class);
        GameData.getAchievementExcelMap().add(achievement);
        var player = new QuestHandlerTest.TestPlayer();
        player.getQuestManager().refreshUnlocks();
        assertEquals(0, player.getQuestManager().achievementCount());
        assertEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
        assertEquals(1, player.getQuestManager().achievementCount());
        assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getQuestManager().claim(id).retcode());
        assertEquals(1, player.getQuestManager().achievementCount());
    }
}
