package emu.lunarcore.game.scene.entity;

import emu.lunarcore.LunarCore;
import emu.lunarcore.data.excel.SummonUnitExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.proto.SceneEntityInfoOuterClass.SceneEntityInfo;
import emu.lunarcore.proto.SceneSummonUnitInfoOuterClass.SceneSummonUnitInfo;
import emu.lunarcore.util.Position;
import lombok.Getter;
import lombok.Setter;

@Getter
public class EntitySummonUnit implements GameEntity, emu.lunarcore.server.game.Tickable {
    @Setter private int entityId;
    private final GameAvatar caster;
    private final SummonUnitExcel excel;

    private final Scene scene;
    private final Position pos;
    private final Position rot;
    private final long createTime;

    private int attachedEntityId;
    private int duration;
    private long expiry;
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Map<String, Boolean> triggerStates = new java.util.HashMap<>();
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.List<org.hkrpg.server.gameplay.SummonTriggerTimeline.Switch> triggerTimeline;
    @Getter(lombok.AccessLevel.NONE)
    private int nextTriggerSwitch;
    @Getter(lombok.AccessLevel.NONE)
    private final java.util.Map<EntityMonster, java.util.Map<Integer, emu.lunarcore.game.scene.SceneBuff>> appliedBuffs = new java.util.IdentityHashMap<>();

    public EntitySummonUnit(Scene scene, GameAvatar caster, SummonUnitExcel excel, Position pos, Position rot) {
        this.scene = scene;
        this.caster = caster;
        this.excel = excel;
        this.pos = pos;
        this.rot = rot;
        this.createTime = System.currentTimeMillis();
        for (var trigger : excel.getInfo().getCustomTriggers()) {
            if (trigger.getTriggerName() != null) this.triggerStates.put(trigger.getTriggerName(), trigger.isDefaultEnable());
        }
        this.triggerTimeline = org.hkrpg.server.gameplay.SummonTriggerTimeline.compile(excel.getInfo().getOnCreate());
        this.advanceTriggerTimeline(this.createTime);

        // Attach summon unit to an entity
        String attachPoint = excel.getInfo().getAttachPoint();
        if (attachPoint != null && attachPoint.equals("Origin")) {
            this.attachedEntityId = caster.getEntityId();
        }
    }

    public void setDuration(int seconds) {
        this.setDuration((double) seconds);
    }

    public void setDuration(double seconds) {
        long milliseconds = org.hkrpg.server.gameplay.MazeBuffLifetime.milliseconds(seconds);
        if (milliseconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Summon lifetime does not fit int32 milliseconds");
        }
        this.duration = (int) milliseconds;
        this.expiry = org.hkrpg.server.gameplay.MazeBuffLifetime.expiry(this.createTime, seconds);
    }

    public boolean isExpired() {
        return this.expiry != Long.MAX_VALUE && System.currentTimeMillis() >= this.expiry;
    }

    private synchronized void advanceTriggerTimeline(long timestamp) {
        if (timestamp < this.createTime) return;
        long elapsed = timestamp - this.createTime;
        while (this.nextTriggerSwitch < this.triggerTimeline.size()) {
            var action = this.triggerTimeline.get(this.nextTriggerSwitch);
            if (action.elapsedMs() > elapsed) break;
            this.setTriggersEnabled(action.names(), action.enabled());
            this.nextTriggerSwitch++;
        }
    }

    public synchronized boolean isTriggerEnabled(String name) {
        return this.isTriggerEnabled(name, System.currentTimeMillis());
    }

    public synchronized boolean isTriggerEnabled(String name, long timestamp) {
        this.advanceTriggerTimeline(timestamp);
        return this.triggerStates.getOrDefault(name, false);
    }

    public synchronized void setTriggersEnabled(java.util.List<String> names, boolean enabled) {
        if (names == null || names.isEmpty()) this.triggerStates.replaceAll((name, old) -> enabled);
        else for (var name : names) if (this.triggerStates.containsKey(name)) this.triggerStates.put(name, enabled);
    }

    @Override
    public synchronized void onTick(long timestamp, long delta) {
        this.advanceTriggerTimeline(timestamp);
    }

    public synchronized void trackAppliedBuff(EntityMonster monster, emu.lunarcore.game.scene.SceneBuff buff) {
        this.appliedBuffs.computeIfAbsent(monster, ignored -> new java.util.HashMap<>()).put(buff.getBuffId(), buff);
    }

    @Override
    public synchronized void onRemove() {
        this.triggerStates.clear();
        this.nextTriggerSwitch = this.triggerTimeline.size();
        if (this.excel.isRemoveMazeBuffOnDestroy()) {
            for (var buffs : this.appliedBuffs.entrySet()) {
                for (var buff : buffs.getValue().values()) buffs.getKey().removeBuff(buff);
            }
        }
        this.appliedBuffs.clear();
        this.excel.getInfo().applyKnownDestroyActions(this.caster);
    }

    @Override
    public SceneEntityInfo toSceneEntityProto() {
        var summon = SceneSummonUnitInfo.newInstance()
                .setLifeTimeMs(this.getDuration())
                .setCreateTimeMs(LunarCore.convertToServerTime(this.getCreateTime()))
                .setCasterEntityId(this.getCaster().getEntityId())
                .setAttachEntityId(this.getAttachedEntityId())
                .setSummonUnitId(this.getExcel().getId());

        for (var trigger : this.getExcel().getInfo().getCustomTriggers()) {
            summon.addTriggerNameList(trigger.getTriggerName());
        }

        var proto = SceneEntityInfo.newInstance()
                .setEntityId(this.getEntityId())
                .setMotion(MotionInfo.newInstance().setPos(getPos().toProto()).setRot(getRot().toProto()))
                .setSummonUnit(summon);

        return proto;
    }
}
