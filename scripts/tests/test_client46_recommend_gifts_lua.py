"""校验推荐礼包只在对应类型可售时走本服网络，保留其余原始代码。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('cash_lua',ROOT/'scripts/client_lua_recommend_gifts.py')
shop=importlib.util.module_from_spec(spec); spec.loader.exec_module(shop)
CLIENT=Path.home()/'AppData/Local/hoyo/hkrpg/versions/current'
INDEX=CLIENT/shop.lua.LUA/'LuaV_5eb2ceb79e9ffefc7ccb31d6d76c2f7c.bytes'

@unittest.skipUnless(INDEX.is_file(), '固定客户端原始索引不可用')
class RecommendGiftGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        chunks=shop.lua.read_index(INDEX.read_bytes()); directory=INDEX.parent
        first=chunks[0]; payload=(directory/(first['digest']+'.bytes')).read_bytes()
        entry=first['entries'][0]
        manifest=[json.loads(line) for line in payload[entry['offset']:entry['offset']+entry['length']].splitlines()]
        wanted={row['Md5']:row['Path'] for row in manifest if row['Path'] in shop.TARGETS}
        records={}
        for chunk in chunks:
            payload=(directory/chunk['locale']/(chunk['digest']+'.bytes')).read_bytes()
            for entry in chunk['entries']:
                raw=payload[entry['offset']:entry['offset']+entry['length']]
                md5=hashlib.md5(raw).hexdigest()
                if md5 in wanted: records[wanted[md5]]=raw
        assert set(records)==set(shop.TARGETS)
        cls.rows=[]
        for path,profile in shop.TARGETS.items():
            raw=records[path]
            original=shop.lua.Bytecode(raw); patched_raw=shop.patch_record(raw,profile)
            patched=shop.lua.Bytecode(patched_raw)
            old,=[p for p in original.protos if p['name']=='_on_btn_buy_click']
            new,=[p for p in patched.protos if p['name']=='_on_btn_buy_click']
            cls.rows.append((path,profile,raw,patched_raw,original,patched,old,new))

    def execute(self, new, old, gift_type):
        code=new['code']; extra=len(code)-len(old['code'])
        self.assertEqual(old['code'][:10],code[:10]); self.assertEqual(old['code'][10:],code[10+extra:])
        # The original equality branch for Selling lands at the new guard.
        self.assertEqual(10,7+1+(code[7]>>16))
        registers=[None]*new['header'][0]; registers[0]={'_gift_data':{'GiftType':gift_type}}
        sent=[]; refreshed=[]; manager={'GCBGBBMFIJN':lambda target,values:sent.append(list(values))}
        recharge={'RequestData':lambda target:refreshed.append(True)}
        imports={'CS.System.Collections':{'Generic':{'List':lambda typ:lambda:[]}},
                 'CS.System.String':str,'CS.RPG.Client':{'GlobalVars':{'s_NetworkManager':manager,
                     's_ModuleManager':{'RechargeShopModule':recharge}}}}
        pc,end,steps=10,10+extra,0
        while pc<end:
            steps+=1; self.assertLess(steps,100)
            word=code[pc]; op,a,b=word&255,word>>8&255,word>>16&255
            d=word>>16; d=d-65536 if d>=32768 else d
            if op==15:
                registers[a]=registers[b][new['constants'][code[pc+1]]];pc+=2
            elif op==27: pc=pc+1+d if registers[a]==registers[code[pc+1]] else pc+2
            elif op==23: pc=pc+1+d
            elif op==5: registers[a]=new['constants'][word>>16];pc+=1
            elif op==6: registers[a]=registers[b];pc+=1
            elif op==12:
                encoded=code[pc+1]; count=encoded>>30
                path='.'.join(new['constants'][(encoded>>shift)&1023] for shift in (20,10,0)[:count])
                registers[a]=imports[path];pc+=2
            elif op==20:
                target=registers[b];name=new['constants'][code[pc+1]];registers[a+1]=target
                registers[a]=(lambda obj,item:obj.append(item)) if name=='Add' else target[name];pc+=2
            elif op==21:
                result=registers[a](*registers[a+1:a+b])
                if word>>24==2: registers[a]=result
                pc+=1
            elif op==22: return True,sent,len(refreshed)
            else: self.fail(f'意外指令 {op}')
        self.assertEqual(end,pc);return False,sent,len(refreshed)

    def test_only_matching_gift_type_dispatches_the_expected_product(self):
        for path,profile,_,_,_,_,old,new in self.rows:
            with self.subTest(path=path):
                self.assertEqual((True,[[profile[0]]],1),self.execute(new,old,profile[1]))
                for wrong in (0,1,99): self.assertEqual((False,[],0),self.execute(new,old,wrong))

    def test_other_functions_and_original_button_code_are_preserved(self):
        for path,profile,raw,patched_raw,original,patched,old,new in self.rows:
            with self.subTest(path=path):
                self.assertEqual(original.main,patched.main)
                for a,b in zip(original.protos,patched.protos):
                    if a['name']!='_on_btn_buy_click':
                        self.assertEqual(raw[a['start']:a['end']],patched_raw[b['start']:b['end']])
                with self.assertRaises(ValueError): shop.patch_record(raw+b'changed',profile)

if __name__=='__main__':unittest.main()
