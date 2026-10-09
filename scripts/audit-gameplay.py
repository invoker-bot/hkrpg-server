"""统计固定资源的剧情规则缺口。文件存在、条件部分支持均不等于玩法已完成。"""
import argparse
import collections
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# These names describe implemented subsets; parameter combinations can still remain pending.
FINISH = {"Auto", "SubMissionFinishCnt", "SubMissionMultiFinish", "Talk", "StageWin", "BattleLose", "BattleQuit", "EnterFloor", "EnterPlane", "EnterMapByEntrance", "PropState", "KillMonster",
          "PlayerLevel", "WorldLevel", "FloorSavedValue", "GroupPropertyValue", "GroupPropertyValueIgnoreVerse", "FinishMission", "FinishQuest", "ItemNum", "MusicRhythmFinishLevel", "KillMonsterInBattle", "ChallengeFinishCnt", "ChallengeFinish",
          "ConsumeMissionItem", "UseSelectedItem", "NotInPlane", "NotInFloor", "GroupState", "GetItem", "GetItemWithList", "StoryLineAddTrialAvatar",
          "LeavePlane", "LeaveFloor", "WaitDays", "KillMonsterList", "KillMonsterGroupList",
          "MessageSectionFinish", "MessagePerformSectionFinish", "MusicRhythmFinishAllLevel",
          "MultiMessagePerformSectionFinish", "MusicRhythmSaveSongConfig", "InteractPropList", "PropDestruct", "FinishFirstTalkPerformance",
          "UseItem", "BuyShopGoods", "TeamLeaderChange", "ParkourFinishLevel", "RoguePassAreaProgress", "CocoonFinish", "AnyCocoonFinish", "AnyFarmElementFinish", "CreateCharacter", "MatchThreeFinishLevel", "HeartDialDialoguePerformanceFinish", "HeartDialScriptListStep", "EnterRaidScene", "RaidFinishCnt", "RaidFinishedAndTransfer", "DiceCombatFinishPVEStage", "BattleWinWithCustomValue", "GetTrialAvatar", "GetTrialAvatarList", "RogueExploreScore"}
ACTIONS = {"MoveToAnchor", "EnterEntryIfNotThere", "addMissionItem", "addRecoverMissionItem", "delMissionItem",
           "SetFloorSavedValue", "SetGroupProperty", "SetGroupState", "ChangeLineup", "ChangeStoryLine", "SetCustomValue", "AddCustomValue", "delSubMission", "delMission", "DisableMission", "Recover", "ActivateAnchor", "ChangeTrainWorldId"}


def audit(resources):
    missions = resources / "Config/Level/Mission"
    finishes, actions, tasks = collections.Counter(), collections.Counter(), collections.Counter()
    missing = []
    sub_count = 0
    for path in sorted(missions.rglob("MissionInfo_*.partial.json")):
        for sub in json.loads(path.read_text(encoding="utf-8")).get("SubMissionList", []):
            sub_count += 1
            finishes[sub.get("FinishType") or "<unspecified>"] += 1
            for action in sub.get("FinishActionList", []):
                actions[action.get("FinishActionType") or "<empty>"] += 1
            script = sub.get("MissionJsonPath")
            if script and not (resources / script).is_file():
                missing.append({"subMissionId": sub["ID"], "path": script})
    def visit(value):
        if isinstance(value, dict):
            if "$type" in value:
                tasks[value["$type"]] += 1
            for child in value.values(): visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    scripts = list(missions.rglob("Mission_*.json"))
    for path in scripts: visit(json.loads(path.read_text(encoding="utf-8")))
    return {"clientAccepted": False, "completeGameplay": False, "subMissions": sub_count,
            "missionScripts": len(scripts), "missionResourceJsonFiles": len(list(missions.rglob("*.json"))),
            "missingMissionScripts": missing,
            "finishTypes": dict(sorted(finishes.items())), "finishActionTypes": dict(sorted(actions.items())),
            "partiallySupportedFinishTypes": sorted(FINISH & finishes.keys()),
            "partiallySupportedActionTypes": sorted(ACTIONS & actions.keys()),
            "additionalSupportedFinishTypes": sorted(FINISH - finishes.keys()),
            "additionalSupportedActionTypes": sorted(ACTIONS - actions.keys()),
            "unimplementedFinishTypes": {k: v for k, v in sorted(finishes.items()) if k not in FINISH},
            "unimplementedActionTypes": {k: v for k, v in sorted(actions.items()) if k not in ACTIONS and k != "<empty>"},
            "clientScriptTaskTypes": dict(sorted(tasks.items()))}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", type=Path, default=ROOT / "resources/4.6")
    parser.add_argument("--output", type=Path, default=ROOT / "build/gameplay-audit.json")
    args = parser.parse_args()
    report = audit(args.resources.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: len(v) if isinstance(v, (dict, list)) else v for k, v in report.items()}, ensure_ascii=False, indent=2))
