"""只读核对后续主线影片和列车到雅利洛的来源链，不代表客户端已经播放。"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MOVIES = (
    (100020117, 1000203, 100020117, 16, 'CS_Chap00_Act270.usm'),
    (100050201, 1000502, 100050201, 310, 'CS_Chap00_Act310.usm'),
    (101000102, 1010001, 101000100, 1, 'CS_Chap01_Act010.usm'),
    (101000201, 1010002, 101000106, 2, 'CS_Chap01_Act020.usm'),
    (101060103, 1010601, 101060103, 100, 'CS_Chap01_Act3010.usm'),
)


def tasks(node, kind):
    if isinstance(node, dict):
        if node.get('$type') == 'RPG.GameCore.' + kind:
            yield node
        for value in node.values():
            yield from tasks(value, kind)
    elif isinstance(node, list):
        for value in node:
            yield from tasks(value, kind)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/followup-story-audit.json')
    args = parser.parse_args()
    source, output = args.resources.resolve(strict=True), args.output.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise ValueError('报告只能写入工程 build 目录')
    hashes = {}

    def read(name):
        raw = (source / name).read_bytes()
        hashes[name] = hashlib.sha256(raw).hexdigest()
        return json.loads(raw)

    performances = read('ExcelOutput/PerformanceVideo.json')
    videos = read('ExcelOutput/VideoConfig.json')
    encryption = read('ExcelOutput/VideoEncryptionConfig.json')
    results = []
    for mission, main, performance, video, filename in MOVIES:
        graph = read(f'Config/Level/Mission/{main}/MissionInfo_{main}.partial.json')
        children = [s for s in graph['SubMissionList'] if s.get('ID') == mission]
        if len(children) != 1 or not children[0].get('MissionJsonPath'):
            raise ValueError(f'任务定义不匹配: {mission}')
        mission_path = children[0]['MissionJsonPath']
        triggers = [t for t in tasks(read(mission_path), 'TriggerPerformance')
                    if t.get('PerformanceID') == performance and t.get('PerformanceType') == 'PlayVideo']
        paths = [r['PerformancePath'] for r in performances if r.get('PerformanceID') == performance]
        if len(triggers) != 1 or len(paths) != 1:
            raise ValueError(f'演出入口不匹配: {mission}')
        plays = [t for t in tasks(read(paths[0]), 'PlayVideo') if t.get('VideoID') == video]
        configs = [r for r in videos if r.get('VideoID') == video]
        crypto = [r for r in encryption if r.get('VideoID') == video]
        if (len(plays) != 1 or len(configs) != 1 or configs[0].get('VideoPath') != filename
                or len(crypto) != 1 or crypto[0].get('Encryption') is not True):
            raise ValueError(f'影片配置不匹配: {video}')
        results.append(dict(mission=mission, performance=performance, video=video,
                            movie=filename, missionPath=mission_path, performancePath=paths[0]))

    fixture = json.loads((ROOT / 'src/test/resources/fixtures/story-transfers-4.6.json').read_bytes())
    relevant = [key for key in fixture['sourceSHA256']
                if '1010002/' in key or 'P20101_F20101001' in key]
    relevant += ['ExcelOutput/MapEntrance.json', 'ExcelOutput/MazePlane.json', 'ExcelOutput/MainMission.json']
    for name in relevant:
        read(name)
        if hashes[name] != fixture['sourceSHA256'][name]:
            raise ValueError('切图来源变化，需重新审计: ' + name)
    expected = dict(sub=101000205, main=1010002, entry=2010101, group=2, anchor=1)
    if fixture['cases'].count(expected) != 1:
        raise ValueError('雅利洛切图夹具不匹配')
    report = dict(passed=True, movieChains=results, nextWorldTransfer=expected,
                  sourceSHA256=hashes, clientGameplayVerified=False)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(passed=True, movieChains=len(results), nextWorldTransfer=True,
                          clientGameplayVerified=False, output=str(output))))


if __name__ == '__main__':
    main()
