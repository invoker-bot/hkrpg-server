package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.battle.skills.MazeSkill;
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

final class Battle46 {
  static void handle(GameSession s, String name, DynamicMessage req) throws IOException {
    var player = s.getPlayer();
    var active = player.getBattle();
    if (name.equals("GetCurBattleInfoCsReq")) {
      var response = builder(s, "GetCurBattleInfoScRsp");
      set(response, "retcode", active == null ? 1 : 0);
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
    int cast = number(req, "cast_entity_id"),
        attacked = number(req, "attacked_by_entity_id"),
        index = number(req, "skill_index");
    Retcode error = Retcode.SUCC;
    var scene = player.getScene();
    var hits = new IntLinkedOpenHashSet();
    var assists = new IntLinkedOpenHashSet();
    if (scene == null
        || active != null
        || !only(
            req,
            "cast_entity_id",
            "attacked_by_entity_id",
            "skill_index",
            "maze_ability_str",
            "target_motion",
            "hit_target_entity_id_list",
            "assist_monster_entity_id_list",
            "assist_monster_entity_info")) error = Retcode.REQ_PARA_INVALID;
    if (index < 0 || index > 1) error = Retcode.REQ_PARA_INVALID;
    @SuppressWarnings("unchecked")
    var hitIds = (List<Integer>) value(req, "hit_target_entity_id_list");
    hits.addAll(hitIds);
    @SuppressWarnings("unchecked")
    var assistIds = (List<Integer>) value(req, "assist_monster_entity_id_list");
    assists.addAll(assistIds);
    @SuppressWarnings("unchecked")
    var infos = (List<DynamicMessage>) value(req, "assist_monster_entity_info");
    for (var info : infos) {
      @SuppressWarnings("unchecked")
      var ids = (List<Integer>) value(info, "entity_id_list");
      assists.addAll(ids);
    }
    // Honey fe00bdb SceneSkillManager.OnCast: direct attacked monster uses cast avatar/leader.
    int caster = attacked;
    if (scene != null
        && scene.getEntities().get(attacked) instanceof EntityMonster
        && assists.isEmpty()) {
      hits.add(attacked);
      caster = cast;
    }
    if (scene != null
        && !scene.getAvatarEntityIds().contains(caster)
        && !(scene.getEntities().get(caster) instanceof EntityMonster))
      error = Retcode.REQ_PARA_INVALID;
    if (scene != null)
      for (int id : assists)
        if (!(scene.getEntities().get(id) instanceof EntityMonster))
          error = Retcode.REQ_PARA_INVALID;
    if (hits.size() > 64 || assists.size() > 64) error = Retcode.REQ_PARA_INVALID;
    if (scene != null)
      for (int id : hits) {
        var target = scene.getEntities().get(id);
        if ((target == null && !scene.getAvatarEntityIds().contains(id))
            || (target != null
                && !(target instanceof EntityMonster)
                && !(target instanceof emu.lunarcore.game.scene.entity.EntityProp))
            || (scene.getAvatarEntityIds().contains(id)
                && scene.getAvatarEntityIds().contains(caster))
            || (target instanceof emu.lunarcore.game.scene.entity.EntityProp prop
                && !scene.canDestroyProp(prop))) error = Retcode.REQ_PARA_INVALID;
      }
    MazeSkill skill = null;
    var abilityName = (String) value(req, "maze_ability_str");
    if (error == Retcode.SUCC
        && !abilityName.isEmpty()
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
        else if (!abilityName.isEmpty() && !abilityName.equals(skill.getAbilityName()))
          error = Retcode.REQ_PARA_INVALID;
        else if (index == 1 && avatar.getActorId() != 1308 && player.getCurrentLineup().getMp() < 1)
          error = Retcode.MAZE_MP_NOT_ENOUGH;
        if (error == Retcode.SUCC && index == 1) {
          if (avatar.getActorId() != 1308) player.getCurrentLineup().removeMp(1);
          var motion =
              MotionInfo.parseFrom(
                  s.getProtocol()
                      .translate(
                          "MotionInfo",
                          ((DynamicMessage) value(req, "target_motion")).toByteArray(),
                          false));
          skill.onCast(avatar, motion);
          player.getDailyManager().onMazeSkill();
          s.send(new PacketSceneCastSkillMpUpdateScNotify(cast, player.getCurrentLineup().getMp()));
        }
      }
    }
    if (error != Retcode.SUCC) {
      send(s, "SceneCastSkillScRsp", Map.of("retcode", error.getVal(), "cast_entity_id", cast));
      return;
    }
    if (hits.isEmpty()) hits.addAll(assists);
    s.getServer().getBattleService().attack(player, caster, cast, skill, hits, assists);
  }

  private Battle46() {}
}
