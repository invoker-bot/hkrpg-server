"""准备只读来源的隔离资源目录，返回需要建立的 Windows 目录联接。"""
import json
import os
from pathlib import Path


def mirror_resources(source, fixture, replacements):
    source=Path(source).resolve();fixture=Path(fixture).resolve();junctions=[]
    if source==fixture or fixture.is_relative_to(source):raise ValueError("来源不能充当测试目录")
    def mirror(origin, relative=''):
        target=fixture/relative
        if relative=='resource-manifest.json':return
        if relative=='Config/Level/Mission':target.mkdir(parents=True)
        elif relative in replacements:
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(json.dumps(replacements[relative]),'utf-8')
        elif not relative or any(n.startswith(relative+'/') for n in [*replacements,'Config/Level/Mission']):
            target.mkdir(parents=True,exist_ok=True)
            for child in origin.iterdir():mirror(child,(relative+'/' if relative else '')+child.name)
        elif origin.is_file():os.link(origin,target)
        elif os.name=='nt':
            if not target.resolve().is_relative_to(fixture):raise ValueError("联接目标越过隔离目录")
            junctions.append({'target':str(target),'source':str(origin)})
        else:target.symlink_to(origin,target_is_directory=True)
    mirror(source)
    return junctions
