"""固定1510原手动能力和完整表，不替未知角色补默认费用或手动能力。

只读固定Git提交与安装原件；输出使用独占创建，禁止覆盖既有证据。
这些数据来自TurnBasedGameData原件，不成为reference代码运行依赖。
"""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b"
ABILITY = "Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_HimekoNova_00_Ability.json"
PINS = {
    ABILITY: "ac339d288f866ec7ecac96b90e6bf0a494e5098ec5b635570ace5ddd7f4da6c3",
    "ExcelOutput/AvatarConfig.json": "9e57b5e3700b525a3f7097895d34177f769012034ac9bd0428318c3ff47f889e",
    "ExcelOutput/MazeSkill.json": "16a060e36ee361f40bcf604abd79d1adfbea7528ca0beb57fe1f93a51083b23e",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def original_sources(reference, resources):
    result = {}
    for relative, expected in PINS.items():
        data = subprocess.run(
            ["git", "-C", str(reference), "show", COMMIT + ":" + relative],
            check=True, capture_output=True,
        ).stdout
        checkout = (reference / relative).read_bytes()
        installed = (resources / relative).read_bytes()
        if digest(data) != expected or checkout.replace(b"\r\n", b"\n") != data or checkout != installed:
            raise ValueError("固定原件不匹配: " + relative)
        result[relative] = {
            "blobSHA256": expected,
            "installedSHA256": digest(installed),
            "fullBlobBytes": len(data),
            "rawBase64": base64.b64encode(data).decode("ascii"),
        }
    return result


def source_fixture(reference, resources):
    files = original_sources(reference, resources)
    config = json.loads(base64.b64decode(files[ABILITY]["rawBase64"]))
    base, variant = config["AbilityList"][1:3]
    modifier = "ADV_StageAbility_Maze_HimekoNova_AttackRadius"
    wait = config["GlobalModifiers"][modifier]["ModifierTaskList"][0]["TaskList"][0]
    if (base["Name"] != "LocalPlayer_HimekoNova_MazeSkill"
            or variant["Name"] != "LocalPlayer_HimekoNova_MazeSkill_Input"
            or variant["ManualTriggerType"] != "Free"
            or base["OnStart"][23]["ModifierName"] != modifier
            or wait["$type"] != "RPG.GameCore.WaitSkillInput"
            or wait["OnInput"][0]["SkillType"] != "MazeSkill"
            or wait["OnInput"][0]["AbilityName"] != variant["Name"]):
        raise ValueError("原输入关联变化")
    projectile = variant["OnStart"][11]
    if projectile["$type"] != "RPG.GameCore.NewAdventureFireProjectile":
        raise ValueError("原投射物声明变化")
    for event in ("OnProjectileHit", "OnProjectileLifetimeFinish"):
        attack = projectile[event][1]
        if (attack["$type"] != "RPG.GameCore.AdventureTriggerAttack"
                or attack["CostMP"] != 2 or attack["CostMpPhaseType"] != "OnFirstHitEnemy"):
            raise ValueError("原命中费用变化")
    avatars = json.loads(base64.b64decode(files["ExcelOutput/AvatarConfig.json"]["rawBase64"]))
    avatar = next(row for row in avatars if row["AvatarID"] == 1510)
    maze = json.loads(base64.b64decode(files["ExcelOutput/MazeSkill.json"]["rawBase64"]))
    rows = [row for row in maze if row["RelatedAvatarSkill"] in avatar["SkillList"]]
    if len(rows) != 2 or next(row for row in rows if row["MazeSkilltype"] == 2)["MPCost"] != 2:
        raise ValueError("原技能表所属变化")
    return {
        "sourceCommit": COMMIT,
        "sourceFiles": files,
        "avatarID": 1510,
        "abilityFile": ABILITY,
        "baseAbilityName": base["Name"],
        "manualAbilityName": variant["Name"],
        "activationModifier": modifier,
        "activationPointer": "/AbilityList/1/OnStart/23",
        "manualPointer": "/AbilityList/2",
        "waitInputPointer": "/GlobalModifiers/" + modifier + "/ModifierTaskList/0/TaskList/0",
        "attackPointers": [
            "/AbilityList/2/OnStart/11/OnProjectileHit/1",
            "/AbilityList/2/OnStart/11/OnProjectileLifetimeFinish/1",
        ],
        "avatar": avatar,
        "mazeRows": rows,
        "boundaries": {
            "wholeOriginalAbilityAndGlobalModifiers": True,
            "noClientExecution": True,
            "noUniformCostForOtherAvatars": True,
            "noField8CostInferred": True,
            "noOncePerUseIdFirstHitRuleInferred": True,
            "noClientGameplayAcceptance": True,
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--references", type=Path, default=ROOT / "references/TurnBasedGameData")
    parser.add_argument("--resources", type=Path, default=ROOT / "resources/4.6")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError("拒绝覆盖已存在输出: " + str(output))
    value = source_fixture(args.references.resolve(), args.resources.resolve())
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"output": str(output), "sha256": digest(output.read_bytes()),
                      "sourceFiles": len(value["sourceFiles"]),
                      "originalBlobBytes": sum(row["fullBlobBytes"] for row in value["sourceFiles"].values())},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
