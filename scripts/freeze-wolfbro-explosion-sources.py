"""固定哈努爆炸的完整原始配置；仅写测试夹具与build证据。"""
from pathlib import Path
import hashlib
import json
import subprocess
import source_json

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
OUTPUT = ROOT / 'build/wolfbro-explosion-whole-sources'
FILES = [
    'Config/Props/Chap03/Prop_Chap03_CartoonExplosives_01_Config.json',
    'Config/ConfigEntity/Props/Chap03/Prop_Chap03_CartoonExplosives_01_Entity.json',
    'Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_FireworksBox_01.json',
]
FILES += ['Config/ConfigAdventureAbility/NpcMonster/NPCMonster_' + name + '_Ability.json'
          for name in ['W3_TV_00', 'W3_Clock_00', 'W3_Dinosaur_00', 'XP_Minion01_04']]
FILES += ['Config/ConfigCharacter/NPCMonster/WolfBoy_' + name + '_Config.json'
          for name in ['W3_TV_00', 'W3_Clock_00', 'W3_Dinosaur_00', 'XP_Minion01_04']]


def read(relative):
    raw = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'),
                          'show', COMMIT + ':' + relative], check=True, capture_output=True).stdout
    target = OUTPUT / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as stream:
        stream.write(raw)
    return dict(path=relative, sourceBlobSHA256=hashlib.sha256(raw).hexdigest(),
                canonicalSHA256=source_json.fingerprint(source_json.loads(raw)),
                data=source_json.loads(raw))


if __name__ == '__main__':
    OUTPUT.mkdir(exist_ok=False)
    files = [read(path) for path in FILES]
    table = read('ExcelOutput/MazeProp.json')
    rows = [row for row in table['data'] if row['ID'] == 103055]
    assert len(rows) == 1
    row = dict(table='MazeProp', keyField='ID', key=103055, sourcePath=table['path'],
               sourceBlobSHA256=table['sourceBlobSHA256'],
               canonicalSHA256=source_json.fingerprint(rows[0]), data=rows[0])
    fixture = dict(sourceCommit=COMMIT, sourceFiles=files, sourceRows=[row],
        serverPolicy=dict(initialFillAndDrops='existing WolfBroAmmo policy',
            reportAuthority='client reports blast victims; server binds original level and actual entities',
            blastRadiusRecovered=False, serverExplosionPhysicsImplemented=False,
            officialNumericalRulesRecovered=False), clientStarted=False, clientAccepted=False)
    with (ROOT / 'src/test/resources/fixtures/wolfbro-explosion-4.6.json').open('x', encoding='utf-8') as stream:
        stream.write(source_json.canonical(fixture) + '\n')
    summary = dict(sourceCommit=COMMIT, completeFiles=len(files)+1, completeRows=1,
        wholeBlobBytes=sum((OUTPUT / record['path']).stat().st_size for record in files+[table]),
        sources=[{key:value for key,value in record.items() if key != 'data'} for record in files+[table]],
        clientStarted=False, clientModified=False, clientAccepted=False, runtimeExecuted=False)
    (OUTPUT / 'verification.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', 'utf-8')
    print(json.dumps({key:value for key,value in summary.items() if key != 'sources'}, ensure_ascii=True))
