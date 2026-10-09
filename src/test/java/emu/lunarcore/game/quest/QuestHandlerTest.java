package emu.lunarcore.game.quest;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.data.excel.RewardExcel;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.FinishQuestCsReqOuterClass.FinishQuestCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.recv.HandlerFinishQuestCsReq;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestHandlerTest {
    static class TestPlayer extends Player {
        int grants;
        int saves;
        final Inventory inventory = new Inventory(this) {
            @Override public GrantResult grantItems(ItemParamMap map,boolean hint) { return new GrantResult(addItems(map,hint),true); }
            @Override public List<GameItem> addItems(ItemParamMap map, boolean showHint) {
                grants++;
                return List.of();
            }
        };
        @Override public Inventory getInventory() { return inventory; }
        @Override public void save() { saves++; }
    }
    static class TestSession extends GameSession {
        final TestPlayer player = new TestPlayer();
        final List<BasePacket> sent = new ArrayList<>();
        TestSession() { super(null); }
        @Override public Player getPlayer() { return player; }
        @Override public void send(BasePacket packet) { sent.add(packet); }
        @Override public void send(int cmdId) { sent.add(new BasePacket(cmdId)); }
    }

    @Test void unknownQuestReturnsErrorInsteadOfCrashing() {
        var session = new TestSession();
        assertDoesNotThrow(() -> new HandlerFinishQuestCsReq().handle(session,
            FinishQuestCsReq.newInstance().setQuestId(1999999999).toByteArray()));
        assertEquals(0, session.player.grants);
    }

    @Test void unacceptedCycleQuestCannotMintRewards() throws Exception {
        var gson = new Gson();
        var quest = gson.fromJson("{\"QuestID\":1999000001,\"QuestType\":3,\"RewardID\":1999000002}", QuestExcel.class);
        var reward = gson.fromJson("{\"RewardID\":1999000002,\"Hcoin\":60}", RewardExcel.class);
        reward.onLoad();
        GameData.getQuestExcelMap().add(quest);
        GameData.getRewardExcelMap().add(reward);
        var session = new TestSession();
        var request = FinishQuestCsReq.newInstance().setQuestId(quest.getId()).toByteArray();
        new HandlerFinishQuestCsReq().handle(session, request);
        new HandlerFinishQuestCsReq().handle(session, request);
        assertEquals(0, session.player.grants, "未接取/未完成的任务不能通过请求发奖");
    }
}
