"""隔离双角色剧情队伍，验证原生换队长、拒绝错误请求及正常重连/H2重启。"""
import importlib.util
import argparse
from pathlib import Path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='双角色剧情队长与施法者的隔离网络验证')
    parser.add_argument('--caster-checks', action='store_true', help='隔离声明非队长秘技锁定，验证拒绝、普通攻击及重连/H2恢复')
    parser.add_argument('--ability-name-checks', action='store_true', help='验证原始普通攻击名称、未知名称拒绝及恢复，包含施法者检查')
    args = parser.parse_args()
    path = Path(__file__).with_name("smoke-story-trial.py")
    spec = importlib.util.spec_from_file_location("story_trial_smoke", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.main(leader_checks=True, caster_checks=args.caster_checks or args.ability_name_checks,
                ability_name_checks=args.ability_name_checks)
