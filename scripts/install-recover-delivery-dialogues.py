"""仅补入3011014固定来源中缺失的NPC入口；不覆盖已有资源或客户端目录。"""
import argparse,base64,hashlib,json,zlib
from decimal import Decimal
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
FIXTURE=ROOT/'src/test/resources/fixtures/daily-recover-delivery-3011014-4.6.json'

def install(resources):
    resources=resources.resolve(strict=True)
    if not (resources/'ExcelOutput/PerformanceE.json').is_file():
        raise ValueError('目标不是服务端资源目录')
    source=json.loads(FIXTURE.read_text('utf-8'))
    if source['sourceCommit']!='724b139d8c9c32d12552eb95745a4fee72bfe48b':
        raise ValueError('固定来源发生变化')
    files={p:source['sourceFiles'][p] for p in source['sourceOnlyPaths']}
    extra=json.loads((ROOT/'src/test/resources/fixtures/daily-recover-performance-entrances-4.6.json').read_text('utf-8'))
    if extra['sourceCommit']!=source['sourceCommit']:raise ValueError('对话项来源提交不匹配')
    files.update(extra['sourceFiles'])
    planned=[]
    for relative,meta in files.items():
        if not relative.startswith('Config/Level/NPCDialogue/'):
            raise ValueError('原始入口路径不在限定目录')
        raw=base64.b64decode(meta['sourceBlobBase64'],validate=True) if 'sourceBlobBase64' in meta else zlib.decompress(base64.b64decode(meta['sourceBlobDeflateBase64'],validate=True))
        if hashlib.sha256(raw).hexdigest()!=meta['sourceBlobSHA256']:
            raise ValueError('完整原始文件SHA不匹配: '+relative)
        target=resources/relative
        ancestor=target
        while not ancestor.exists():ancestor=ancestor.parent
        if not ancestor.resolve().is_relative_to(resources):
            raise ValueError('入口路径逃逸资源目录')
        if target.exists():
            if not target.is_file() or not target.resolve().is_relative_to(resources) or json.loads(target.read_text('utf-8-sig'),parse_float=Decimal)!=json.loads(raw,parse_float=Decimal):
                raise ValueError('已有入口冲突，保留原文件: '+relative)
            planned.append((relative,target,None,meta['sourceBlobSHA256']))
        else:planned.append((relative,target,raw,meta['sourceBlobSHA256']))
    # Validate every input/conflict before creating any file. Exclusive writes preserve concurrent edits.
    for relative,target,raw,digest in planned:
        if raw is not None:
            target.parent.mkdir(parents=True,exist_ok=True)
            if not target.parent.resolve().is_relative_to(resources):raise ValueError('入口父目录发生变化')
            with target.open('xb') as output:output.write(raw)
    return dict(fixedCommit=source['sourceCommit'],resources=str(resources),created=[p for p,t,r,h in planned if r is not None],presentMatching=[p for p,t,r,h in planned if r is None],originalSHA256={p:h for p,t,r,h in planned},clientModified=False,existingFilesOverwritten=False)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources',type=Path,default=ROOT/'resources/4.6')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if not args.output.resolve().is_relative_to((ROOT/'build').resolve()):raise ValueError('报告仅限build目录')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open('x',encoding='utf-8') as output:
        result=install(args.resources);json.dump(result,output,ensure_ascii=False,indent=2)
    print(json.dumps(dict(created=len(result['created']),presentMatching=len(result['presentMatching']),report=str(args.output))))
