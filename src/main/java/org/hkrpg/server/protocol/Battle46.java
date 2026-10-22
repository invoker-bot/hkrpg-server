package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.battle.MazeAttackResult;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketSceneCastSkillMpUpdateScNotify;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.MazeCastState;
import org.hkrpg.server.gameplay.MazeSkillCosts;

final class Battle46 {
  static void handle(GameSession s, String name, DynamicMessage req) throws IOException {
    var player = s.getPlayer();
    var active = player.getBattle();
    if (name.equals("GetCurBattleInfoCsReq")) {
      var response = builder(s, "GetCurBattleInfoScRsp");
      set(response, "retcode", Retcode.SUCC.getVal());
      // CN4.6 BattleModule treats zero battle/stage IDs as no active battle. AdventureModule
      // still dereferences battle_info on success, so the empty snapshot needs its container.
      // A query without an active battle is not a failed operation and must not show FAIL.
      set(response, "battle_info", builder(s, "SceneBattleInfo").build());
      if (active != null)
        set(
            response,
            "battle_info",
            s.getProtocol()
                .clientSchema()
                .parse(
                    "SceneBattleInfo",
                    s.getProtocol()
                        .translate("SceneBattleInfo", active.toProto().toByteArray(), true)));
      s.sendClientPacket("GetCurBattleInfoScRsp", response.build().toByteArray());
      return;
    }
    if (name.equals("SceneCastSkillCostMpCsReq")) {
      payOnUse(s, req);
      return;
    }
    if (name.equals("QuitBattleCsReq")) {
      var energies = BattleResult46.energies((DynamicMessage) value(req, "stt"));
      var stats =
          BattleStatistics.parseFrom(
              s.getProtocol()
                  .translate(
                      "BattleStatistics",
                      ((DynamicMessage) value(req, "stt")).toByteArray(),
                      false));
      var result =
          active == null || !only(req, "stt") || energies == null
              ? null
              : s.getServer()
                  .getBattleService()
                  .finishBattle(
                      player,
                      active.getId(),
                      active.getStage().getId(),
                      BattleEndStatus.BATTLE_END_QUIT,
                      stats,
                      List.of(),
                      energies);
      send(s, "QuitBattleScRsp", Map.of("retcode", result == null ? 1 : 0));
      return;
    }
    var scene = player.getScene();
    var input = SceneCastRequest46.decode(req, scene);
    int cast = input.useId(), caster = input.casterEntityId(), index = input.skillIndex();
    var phase = input.phase();
    var hits = input.hits();
    var assists = input.assists();
    boolean monsterCast = input.monsterCast();
    Retcode error = input.rejection() == null ? Retcode.SUCC : Retcode.REQ_PARA_INVALID;
    if (active != null) error = Retcode.SYSTEM_BUSY;
    if (error == Retcode.SUCC && scene != null) {
      var combatants = new IntLinkedOpenHashSet(hits);
      combatants.addAll(assists);
      for (int id : combatants)
        if (scene.getEntities().get(id) instanceof EntityMonster monster
            && org.hkrpg.server.gameplay.BoxingClubManager.genericMonsterRequiresMatch(monster))
          error = Retcode.REQ_PARA_INVALID;
    }
    MazeSkill skill = null;
    MazeSkillCosts.ManualInput manual = null;
    MazeSkillCosts.FreeNormalAttack freeNormal = null;
    MazeSkillCosts.FreeDirectInput freeDirect = null;
    MazeCastState.Cast directRelease = null;
    MotionInfo directMotion = null;
    int manualCost = 0;
    boolean manualFirstRelease = false;
    var abilityName = input.ability();
    if (error == Retcode.SUCC
        && !abilityName.isEmpty()
        && !scene.getAvatarEntityIds().contains(caster)) error = Retcode.REQ_PARA_INVALID;
    if (error == Retcode.SUCC && projectileEvent(phase) && !monsterCast
        && !scene.getAvatarEntityIds().contains(caster)) error = Retcode.REQ_PARA_INVALID;
    if (error == Retcode.SUCC && player.getGameplay().getStoryLines().isTransitionPending())
      error = Retcode.SYSTEM_BUSY;
    if (error == Retcode.SUCC && scene.getAvatarEntityIds().contains(caster)) {
      var avatar = scene.getAvatarByEntityId(caster);
      if (avatar == null || !avatar.isAlive(player.getCurrentLineup()))
        error = Retcode.REQ_PARA_INVALID;
      else {
        skill = index == 0 ? avatar.getExcel().getMazeAttack() : avatar.getExcel().getMazeSkill();
        if (index == 1 && avatar.isMazeSkillLocked()) error = Retcode.REQ_PARA_INVALID;
        else if (skill == null) error = Retcode.REQ_PARA_INVALID;
        else if (!abilityName.isEmpty() && !abilityName.equals(skill.getAbilityName())) {
          // ManualTriggerAbility starts a separate original Ability; its name is field15,
          // and its new UseID must never reuse the base 1408 payment.
          var free = index == 0 && phase == MazeSkill.CallbackPhase.PROJECTILE_LIFETIME_FINISH
              ? MazeSkillCosts.cyreneFreeNormalAttack(avatar, abilityName)
              : Optional.<MazeSkillCosts.FreeNormalAttack>empty();
          var selected = index == 1 && projectileEvent(phase)
              ? MazeSkillCosts.manualInput(avatar, abilityName) : Optional.<MazeSkillCosts.ManualInput>empty();
          var direct = index == 1 && phase == MazeSkill.CallbackPhase.COMBINED
              ? MazeSkillCosts.castoriceFreeDirectInput(avatar, abilityName)
              : Optional.<MazeSkillCosts.FreeDirectInput>empty();
          var cleanup = index == 1 && phase == MazeSkill.CallbackPhase.COMBINED
              ? MazeSkillCosts.castoriceFreeOut(avatar, abilityName)
              : Optional.<MazeSkillCosts.FreeOut>empty();
          var abort = index == 0 && phase == MazeSkill.CallbackPhase.COMBINED
              ? org.hkrpg.server.gameplay.MazeFeixiaoSources.abortCleanup(avatar, abilityName)
              : Optional.<org.hkrpg.server.gameplay.MazeFeixiaoSources.Cleanup>empty();
          if ((selected.isEmpty() && free.isEmpty() && direct.isEmpty() && cleanup.isEmpty() && abort.isEmpty()) || player.getSession() != s || avatar.getOwner() != player
              || !MazeCastState.validUseId(cast) || !assists.isEmpty()) error = Retcode.REQ_PARA_INVALID;
          else if (abort.isPresent()) {
            // This original Submit has no Attack or target lists; keep it outside resolveAttack.
            error = feixiaoAbort(s, req, avatar, cast, abort.get());
            send(s, "SceneCastSkillScRsp", Map.of("retcode", error.getVal(), "cast_entity_id", cast));
            return;
          }
          else if (cleanup.isPresent()) {
            // Original Submit has no target lists and no Attack. Do not enter resolveAttack.
            error = castoriceOut(s, req, avatar, cast, cleanup.get());
            send(s, "SceneCastSkillScRsp", Map.of("retcode", error.getVal(), "cast_entity_id", cast));
            return;
          }
          else if (direct.isPresent()) {
            freeDirect = direct.get();
            if (!player.getCurrentLineup().getAvatars().contains(avatar.getAvatarId())
                || player.getAvatarById(avatar.getAvatarId()) != avatar
                || !player.getGameplay().getMazeCasts().hasReleasedBuffOrigin(s, avatar,
                    freeDirect.origin(), 1, freeDirect.originBuff())) error = Retcode.REQ_PARA_INVALID;
            else {
              skill = freeDirect.skill();
              for (int id : hits)
                if (!(scene.getEntities().get(id) instanceof EntityMonster)) error = Retcode.REQ_PARA_INVALID;
            }
          }
          else if (free.isPresent()) {
            freeNormal = free.get();
            if (!player.getCurrentLineup().getAvatars().contains(avatar.getAvatarId())
                || player.getAvatarById(avatar.getAvatarId()) != avatar
                || !player.getGameplay().getMazeCasts().hasReleasedBuffOrigin(s, avatar,
                    freeNormal.origin(), 1, freeNormal.originBuff())) error = Retcode.REQ_PARA_INVALID;
            else {
              skill = freeNormal.skill();
              for (int id : hits)
                if (!(scene.getEntities().get(id) instanceof EntityMonster)) error = Retcode.REQ_PARA_INVALID;
            }
          }
          else {
            manual = selected.get();
            if (!player.getGameplay().getMazeCasts().hasReleasedOrigin(s, avatar, skill, index,
                manual.originCost(), manual.originModifier())) error = Retcode.REQ_PARA_INVALID;
            else {
              skill = manual.skill();
              for (int id : hits) {
                if (!(scene.getEntities().get(id) instanceof EntityMonster target)) {
                  error = Retcode.REQ_PARA_INVALID;
                  break;
                }
                // Native phase2 clamps the valid enemy count to one for this Attack.
                // It is not a first-hit flag for the entire Ability UseID.
                if (target.isAlive()) manualCost = manual.cost();
              }
            }
          }
        }
        if (error == Retcode.SUCC && projectileEvent(phase) && !skill.isProjectileDeclared())
          error = Retcode.REQ_PARA_INVALID;
        MazeCastState.Cast binding = null;
        if (error == Retcode.SUCC) {
          binding = player.getGameplay().getMazeCasts().find(s, cast);
          if (binding != null && !binding.matches(avatar, skill, index)) error = Retcode.REQ_PARA_INVALID;
          else if (freeDirect != null && binding != null && binding.released())
            error = Retcode.REQ_PARA_INVALID;
          else if (manual != null && player.getCurrentLineup().getMp() < manualCost)
            error = Retcode.MAZE_MP_NOT_ENOUGH;
          else if (manual == null && freeDirect == null && index == 1 && (binding == null || !binding.prepaid())
              && avatar.getActorId() != 1308 && player.getCurrentLineup().getMp() < 1)
            error = Retcode.MAZE_MP_NOT_ENOUGH;
        }
        if (error == Retcode.SUCC && (index == 1 || freeNormal != null)) {
          if (binding == null && MazeCastState.validUseId(cast)) {
            binding = player.getGameplay().getMazeCasts().begin(s, cast, avatar, skill, index);
            if (binding == null) error = Retcode.SYSTEM_BUSY;
          }
          if (error == Retcode.SUCC) {
            boolean prepaid = binding != null && binding.prepaid();
            // Unpaid requests keep the existing simplified hit-payment rule. Only the proven
            // native OnUse receipt suppresses a second payment; HIT is not globally deduplicated.
            if (manual == null && freeNormal == null && freeDirect == null && !prepaid && avatar.getActorId() != 1308)
              player.getCurrentLineup().removeMp(1);
            boolean firstRelease = binding == null || !binding.released();
            var motion = MotionInfo.parseFrom(s.getProtocol().translate("MotionInfo",
                ((DynamicMessage) value(req, "target_motion")).toByteArray(), false));
            if (freeDirect != null) {
              // Source removal is after Attack. Commit it only after actual battle admission;
              // a missing Stage leaves both the captured origin and this UseID retryable.
              directRelease = binding;
              directMotion = motion;
            } else if (binding == null) skill.onCast(avatar, motion);
            else binding.release(motion);
            if (manual == null && freeNormal == null && freeDirect == null) {
              if (firstRelease) player.getDailyManager().onMazeSkill();
              if (!prepaid) notifyMp(s, cast);
            } else manualFirstRelease = firstRelease;
          }
        }
      }
    }
    if (error != Retcode.SUCC) {
      SceneActionDiagnostics46.rejected(s, name,
          input.rejection() != null ? input.rejection() : active != null ? "active-battle" : "skill-admission",
          error, cast, caster, hits.size(), assists.size());
      send(s, "SceneCastSkillScRsp", Map.of("retcode", error.getVal(), "cast_entity_id", cast));
      return;
    }
    var acceptedRelease = directRelease;
    var acceptedCaster = freeDirect == null ? null : scene.getAvatarByEntityId(caster);
    java.util.function.Consumer<emu.lunarcore.game.battle.Battle> beforeActivation = freeDirect == null ? null : battle -> {
      if (!battle.excludeCastoriceRadius(acceptedCaster, acceptedRelease))
        throw new IllegalStateException("Original Castorice entry binding is no longer valid");
    };
    MazeAttackResult result = null;
    try (var attempt = freeDirect == null ? null : directRelease.attempt()) {
      result = freeDirect == null
          ? s.getServer().getBattleService().resolveAttack(player, caster, skill, hits, assists, phase)
          : s.getServer().getBattleService().resolveAttack(player, caster, skill, hits, assists, phase, beforeActivation);
      if (freeDirect != null && result.retcode() == Retcode.SUCC) {
        directRelease.release(directMotion);
        attempt.commit();
      }
    } catch (RuntimeException rejected) {
      if (freeDirect == null) throw rejected;
      if (result != null && result.battle() != null && player.getBattle() == result.battle()) player.setBattle(null);
      result = MazeAttackResult.failure(Retcode.FAIL);
    }
    // Quote before actions, commit only an accepted finite manual Attack. No fee is
    // invented for a zero-target event or for a failed battle admission.
    if (manual != null && result.retcode() == Retcode.SUCC) {
      if (manualCost > 0) {
        player.getCurrentLineup().removeMp(manualCost);
        notifyMp(s, cast);
      }
      if (manualFirstRelease) player.getDailyManager().onMazeSkill();
    }
    SceneActionDiagnostics46.rejected(s, name, "battle-admission", result.retcode(),
        cast, caster, hits.size(), assists.size());
    var response = builder(s, "SceneCastSkillScRsp");
    set(response, "retcode", result.retcode().getVal());
    set(response, "cast_entity_id", cast);
    if (result.battle() != null)
      set(response, "battle_info", s.getProtocol().clientSchema().parse("SceneBattleInfo",
          s.getProtocol().translate("SceneBattleInfo", result.battle().toProto().toByteArray(), true)));
    var rowType = response.getDescriptorForType().findFieldByName("monster_battle_info").getMessageType();
    var outcome = rowType.findFieldByName("monster_battle_type");
    for (var monster : result.monsters()) {
      var row = DynamicMessage.newBuilder(rowType);
      set(row, "target_monster_entity_id", monster.entityId());
      row.setField(outcome, outcome.getEnumType().findValueByName("MONSTER_BATTLE_TYPE_" + monster.outcome().name()));
      add(response, "monster_battle_info", row.build());
    }
    s.sendClientPacket("SceneCastSkillScRsp", response.build().toByteArray());
  }

  private static Retcode castoriceOut(GameSession session, DynamicMessage request,
      GameAvatar caster, int use, MazeSkillCosts.FreeOut source) throws IOException {
    var player = session.getPlayer();
    var lineup = player.getCurrentLineup();
    if (!((List<?>) value(request, "hit_target_entity_id_list")).isEmpty()
        || !((List<?>) value(request, "assist_monster_entity_id_list")).isEmpty()
        || !((List<?>) value(request, "assist_monster_entity_info")).isEmpty()
        || !lineup.getAvatars().contains(caster.getAvatarId()) || player.getAvatarById(caster.getAvatarId()) != caster
        || lineup.getLeader() < 0 || lineup.getLeader() >= lineup.size()
        || lineup.getAvatars().get(lineup.getLeader()) != caster.getAvatarId()
        || !player.getGameplay().getMazeCasts().hasReleasedBuffOrigin(session, caster,
            source.origin(), 1, source.originBuff())) return Retcode.REQ_PARA_INVALID;
    var state = player.getGameplay().getMazeCasts();
    var binding = state.find(session, use);
    if (binding != null && (!binding.matches(caster, source.skill(), 1) || binding.released()))
      return Retcode.REQ_PARA_INVALID;
    if (binding == null) binding = state.begin(session, use, caster, source.skill(), 1);
    if (binding == null) return Retcode.SYSTEM_BUSY;
    var motion = MotionInfo.parseFrom(session.getProtocol().translate("MotionInfo",
        ((DynamicMessage) value(request, "target_motion")).toByteArray(), false));
    try (var attempt = binding.attempt()) {
      binding.release(motion);
      attempt.commit();
      return Retcode.SUCC;
    } catch (RuntimeException rejected) {
      return Retcode.FAIL;
    }
  }

  private static Retcode feixiaoAbort(GameSession session, DynamicMessage request, GameAvatar caster,
      int use, org.hkrpg.server.gameplay.MazeFeixiaoSources.Cleanup source) throws IOException {
    var player = session.getPlayer();
    if (!((List<?>) value(request, "hit_target_entity_id_list")).isEmpty()
        || !((List<?>) value(request, "assist_monster_entity_id_list")).isEmpty()
        || !((List<?>) value(request, "assist_monster_entity_info")).isEmpty()
        || !player.getCurrentLineup().getAvatars().contains(caster.getAvatarId())
        || player.getAvatarById(caster.getAvatarId()) != caster
        || !player.getGameplay().getMazeCasts().hasReleasedBuffOrigin(session, caster,
            source.origin(), 1, source.originBuff())) return Retcode.REQ_PARA_INVALID;
    var state = player.getGameplay().getMazeCasts();
    var binding = state.find(session, use);
    if (binding != null && (!binding.matches(caster, source.skill(), 0) || binding.released()))
      return Retcode.REQ_PARA_INVALID;
    if (binding == null) binding = state.begin(session, use, caster, source.skill(), 0);
    if (binding == null) return Retcode.SYSTEM_BUSY;
    var motion = MotionInfo.parseFrom(session.getProtocol().translate("MotionInfo",
        ((DynamicMessage) value(request, "target_motion")).toByteArray(), false));
    try (var attempt = binding.attempt()) {
      binding.release(motion);
      attempt.commit();
      return Retcode.SUCC;
    } catch (RuntimeException rejected) {
      return Retcode.FAIL;
    }
  }

  private static boolean projectileEvent(MazeSkill.CallbackPhase phase) {
    return phase == MazeSkill.CallbackPhase.PROJECTILE_HIT
        || phase == MazeSkill.CallbackPhase.PROJECTILE_LIFETIME_FINISH;
  }

  private static void notifyMp(GameSession session, int use) {
    // Keep existing packet interception and translate its verified attacked_group_id alias
    // to the native cast_entity_id field, carrying the actual Ability UseID.
    session.send(new PacketSceneCastSkillMpUpdateScNotify(use, session.getPlayer().getCurrentLineup().getMp()));
  }

  /** 1408 is sent before starting an OnUse Ability and contains its actual uint UseID. */
  private static void payOnUse(GameSession session, DynamicMessage req) {
    var player = session.getPlayer();
    int use = number(req, "cast_entity_id"), caster = number(req, "attacked_by_entity_id"),
        index = number(req, "skill_index");
    Retcode error = Retcode.SUCC;
    var scene = player.getScene();
    GameAvatar avatar = scene == null ? null : scene.getAvatarByEntityId(caster);
    if (!only(req, "cast_entity_id", "attacked_by_entity_id", "skill_index")
        || !MazeCastState.validUseId(use) || index != 1 || scene == null
        || !scene.getAvatarEntityIds().contains(caster) || avatar == null
        || avatar.getOwner() != player || avatar.getEntityId() != caster
        || !avatar.isAlive(player.getCurrentLineup()) || avatar.isMazeSkillLocked()
        || player.getSession() != session) error = Retcode.REQ_PARA_INVALID;
    var skill = avatar == null ? null : avatar.getExcel().getMazeSkill();
    if (error == Retcode.SUCC && (skill == null || skill.getAbilityName().isEmpty()))
      error = Retcode.REQ_PARA_INVALID;
    MazeCastState.Cast binding = null;
    if (error == Retcode.SUCC) {
      binding = player.getGameplay().getMazeCasts().find(session, use);
      if (binding != null && !binding.matches(avatar, skill, index)) error = Retcode.REQ_PARA_INVALID;
      else if (binding != null && binding.prepaid()) {
        send(session, "SceneCastSkillCostMpScRsp", Map.of("retcode", 0, "cast_entity_id", use));
        return;
      } else if (binding != null && binding.released()) error = Retcode.REQ_PARA_INVALID;
      else if (player.getBattle() != null || player.getGameplay().getStoryLines().isTransitionPending())
        error = Retcode.SYSTEM_BUSY;
    }
    int amount = 0;
    if (error == Retcode.SUCC) {
      var quote = MazeSkillCosts.onUse(avatar, skill);
      if (quote.isEmpty()) error = Retcode.REQ_PARA_INVALID;
      else if (player.getCurrentLineup().getMp() < quote.getAsInt()) error = Retcode.MAZE_MP_NOT_ENOUGH;
      else amount = quote.getAsInt();
    }
    if (error == Retcode.SUCC) {
      if (binding == null) binding = player.getGameplay().getMazeCasts().begin(session, use, avatar, skill, index);
      if (binding == null) error = Retcode.SYSTEM_BUSY;
      else {
        player.getCurrentLineup().removeMp(amount);
        binding.pay(amount);
        notifyMp(session, use);
      }
    }
    SceneActionDiagnostics46.rejected(session, "SceneCastSkillCostMpCsReq", "skill-payment",
        error, use, caster, 0, 0);
    send(session, "SceneCastSkillCostMpScRsp", Map.of("retcode", error.getVal(), "cast_entity_id", use));
  }

  private Battle46() {}
}
