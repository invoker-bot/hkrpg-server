package emu.lunarcore.game.quest;

import dev.morphia.annotations.Entity;
import emu.lunarcore.proto.QuestOuterClass.Quest;
import emu.lunarcore.proto.QuestStatusOuterClass.QuestStatus;
import lombok.Getter;

@Entity(useDiscriminator = false)
@Getter
public class QuestState {
    public enum Phase { DOING, FINISHED, CLAIMING, CLOSED }
    private int questId;
    private int progress;
    private long finishTime;
    private Phase phase = Phase.DOING;

    protected QuestState() {}
    public QuestState(int id) {
        if (id <= 0) throw new IllegalArgumentException("Quest id must be positive");
        this.questId = id;
    }
    public boolean advance(int amount, int goal, long now) {
        if (amount <= 0 || goal <= 0 || phase != Phase.DOING) return false;
        progress = (int) Math.min((long) goal, (long) progress + amount);
        if (progress == goal) { phase = Phase.FINISHED; finishTime = now; }
        return true;
    }
    public boolean reserveReward() {
        if (phase != Phase.FINISHED) return false;
        phase = Phase.CLAIMING;
        return true;
    }
    public void closeReward() {
        if (phase != Phase.CLAIMING) throw new IllegalStateException("Reward was not reserved");
        phase = Phase.CLOSED;
    }
    public Quest toProto() {
        return Quest.newInstance().setId(questId).setProgress(progress).setFinishTime(finishTime)
            .setStatus(switch (phase) {
                case DOING -> QuestStatus.QUEST_DOING;
                case FINISHED, CLAIMING -> QuestStatus.QUEST_FINISH;
                case CLOSED -> QuestStatus.QUEST_CLOSE;
            });
    }
}
