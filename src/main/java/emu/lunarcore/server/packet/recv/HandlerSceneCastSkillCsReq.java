package emu.lunarcore.server.packet.recv;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.SceneCastSkillCsReqOuterClass.SceneCastSkillCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketSceneCastSkillMpUpdateScNotify;
import emu.lunarcore.server.packet.send.PacketSceneCastSkillScRsp;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

@Opcodes(CmdId.SceneCastSkillCsReq)
public class HandlerSceneCastSkillCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        var req = SceneCastSkillCsReq.parseFrom(data);
        Player player = session.getPlayer();
        synchronized (player) {
            if (player.getGameplay().getStoryLines().isTransitionPending()) {
                session.send(new PacketSceneCastSkillScRsp(req.getAttackedGroupId(), Retcode.SYSTEM_BUSY));
                return;
            }
            castLocked(session, req, player);
        }
    }

    private void castLocked(GameSession session, SceneCastSkillCsReq req, Player player) {
        var scene = player.getScene();
        if (scene == null || player.isInBattle() || req.getSkillIndex() < 0 || req.getSkillIndex() > 1
                || (!scene.getAvatarEntityIds().contains(req.getCasterId())
                    && !(scene.getEntityById(req.getCasterId()) instanceof emu.lunarcore.game.scene.entity.EntityMonster))) {
            session.send(new PacketSceneCastSkillScRsp(req.getAttackedGroupId(), Retcode.REQ_PARA_INVALID));
            return;
        }
        // Setup variables
        MazeSkill skill = null;

        // Check if player casted a maze skill
        if (player.getScene().getAvatarEntityIds().contains(req.getCasterId())) {
            // Get casting avatar
            GameAvatar caster = player.getScene().getAvatarByEntityId(req.getCasterId());

            // Sanity check, but should never happen
            if (caster == null || !caster.isAlive(player.getCurrentLineup())) {
                session.send(new PacketSceneCastSkillScRsp(req.getAttackedGroupId(), Retcode.REQ_PARA_INVALID));
                return;
            }

            // Check if normal attack or technique was used
            if (req.getSkillIndex() > 0) {
                if (caster.isMazeSkillLocked() || caster.getExcel().getMazeSkill() == null) {
                    session.send(new PacketSceneCastSkillScRsp(req.getAttackedGroupId(), Retcode.REQ_PARA_INVALID));
                    return;
                }
                if (caster.getActorId() != 1308 && player.getCurrentLineup().getMp() < 1) {
                    session.send(new PacketSceneCastSkillScRsp(req.getAttackedGroupId(), Retcode.MAZE_MP_NOT_ENOUGH));
                    return;
                }
                if (caster.getActorId() != 1308) {
                    player.getCurrentLineup().removeMp(1);
                    // Spend one skill point
                }
                session.send(new PacketSceneCastSkillMpUpdateScNotify(req.getAttackedGroupId(), player.getCurrentLineup().getMp()));
                // Cast skill effects
                if (caster.getExcel().getMazeSkill() != null) {
                    skill = caster.getExcel().getMazeSkill();
                    skill.onCast(caster, req.getTargetMotion());
                }
            } else {
                skill = caster.getExcel().getMazeAttack();
            }
        }

        if (req.hasHitTargetEntityIdList()) {
            // Parse targets efficiently (skips integer boxing)
            IntSet hitTargets = new IntLinkedOpenHashSet();
            for (int i = 0; i < req.getHitTargetEntityIdList().length(); i++) {
                hitTargets.add(req.getHitTargetEntityIdList().get(i));
            }

            IntSet assistMonsters = new IntLinkedOpenHashSet();
            for (var assistWave : req.getAssistMonsterWaveList()) {
                for (int id : assistWave.getEntityIdList()) {
                    assistMonsters.add(id);
                }
            }

            // Start battle
            session.getServer().getBattleService().attack(player, req.getCasterId(), req.getAttackedGroupId(), skill, hitTargets, assistMonsters);
        } else {
            // We had no targets for some reason
            session.send(new PacketSceneCastSkillScRsp(req.getAttackedGroupId()));
        }
    }

}
