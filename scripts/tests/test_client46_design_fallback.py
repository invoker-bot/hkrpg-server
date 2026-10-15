"""验证更新目录/游戏自带目录选择；不运行客户端或安装JSON。"""
import hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('design_fallback',ROOT/'scripts/read-client-design-data.py');design=importlib.util.module_from_spec(spec);spec.loader.exec_module(design)
PERSISTENT=CLIENT/'StarRail_Data/Persistent/DesignData/Windows'
BUNDLED=CLIENT/'StarRail_Data/StreamingAssets/DesignData/Windows'
CHUNK='3fe3df4c53e2f8389ce65eb73ec8c55e.bytes'
UPDATED_INDEX='5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed'
def parse_index(directory, bundled=None):
 return design.parse_index(directory,bundled,index_sha256=UPDATED_INDEX)

@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定的本机客户端，跳过实际目录检查')
class DesignFallbackTest(unittest.TestCase):
 def test_bundled_nonlocale_chunk_is_selected_from_fixed_index(self):
  _,chunks,_=parse_index(PERSISTENT,BUNDLED)
  self.assertEqual(str(BUNDLED/CHUNK),chunks[18]['file'])
  self.assertEqual((76204,2,''),(chunks[18]['size'],chunks[18]['count'],chunks[18]['locale']))
  self.assertEqual('3fe3df4c53e2f8389ce65eb73ec8c55e',hashlib.md5(Path(chunks[18]['file']).read_bytes()).hexdigest())
  self.assertEqual([], [c['index'] for c in chunks if c['locale']=='' and not Path(c['file']).is_file()])
 def test_primary_wins_even_when_content_will_later_fail_validation(self):
  with tempfile.TemporaryDirectory() as temp:
   directory=Path(temp);index,_,_=parse_index(PERSISTENT)
   (directory/index.name).write_bytes(index.read_bytes());(directory/CHUNK).write_bytes(b'operator data')
   _,chunks,_=parse_index(directory,BUNDLED)
   self.assertEqual(str(directory/CHUNK),chunks[18]['file']);self.assertEqual(b'operator data',(directory/CHUNK).read_bytes())
 def test_missing_locations_remain_missing_and_legacy_single_directory_is_preserved(self):
  _,legacy,_=parse_index(PERSISTENT)
  self.assertEqual(str(PERSISTENT/CHUNK),legacy[18]['file']);self.assertFalse(Path(legacy[18]['file']).is_file())
  _,chunks,_=parse_index(PERSISTENT,BUNDLED)
  self.assertEqual([7,9,11,13,15,17,19,21,23,25,27,29,31],[c['index'] for c in chunks if not Path(c['file']).is_file()])
  self.assertEqual(str(PERSISTENT/'f96da886871b002d752da7a11bf96a14.bytes'),chunks[17]['file'])
 def test_actual_bundled_record_read_uses_same_size_digest_and_read_only_output_checks(self):
  # Only the path-to-index-key lookup is controlled: this verifies storage fallback, not this label.
  with tempfile.TemporaryDirectory() as temp:
   directory=Path(temp);args=SimpleNamespace(client_dir=CLIENT,paths=['Config/storage-fallback-test.json'],paths_file=None,extract_dir=directory/'raw',output=directory/'report.json',index_sha256=UPDATED_INDEX)
   with patch.object(design,'path_hash',return_value=int('6d8877f08e2ebf38',16)):
    report=design.extract(args)
   record=report['records'][0];self.assertEqual('read',record['status']);self.assertEqual((18,58289,0),(record['chunk'],record['length'],record['offset']))
   expected=(BUNDLED/CHUNK).read_bytes()[:58289]
   self.assertEqual(hashlib.sha256(expected).hexdigest(),record['sha256']);self.assertEqual(expected,Path(record['extractedFile']).read_bytes());self.assertEqual(str(BUNDLED/CHUNK),record['sourceFile'])
   self.assertEqual([18],report['bundledChunks']);self.assertEqual([],report['missingCoreChunks']);self.assertEqual(13,len(report['missingLocaleChunks']))
   self.assertFalse(report['clientModified']);self.assertFalse(report['clientExecutableStarted']);self.assertFalse(report['jsonInstalled']);self.assertFalse(report['clientAccepted'])
 def test_different_preexisting_export_is_rejected_without_overwrite_or_report(self):
  with tempfile.TemporaryDirectory() as temp:
   directory=Path(temp);destination=directory/'raw/Config/storage-fallback-test.bytes';destination.parent.mkdir(parents=True);destination.write_bytes(b'operator output')
   args=SimpleNamespace(client_dir=CLIENT,paths=['Config/storage-fallback-test.json'],paths_file=None,extract_dir=directory/'raw',output=directory/'report.json',index_sha256=UPDATED_INDEX)
   with patch.object(design,'path_hash',return_value=int('6d8877f08e2ebf38',16)),self.assertRaises(FileExistsError):design.extract(args)
   self.assertEqual(b'operator output',destination.read_bytes());self.assertFalse(args.output.exists())
 def test_bad_bundled_content_is_rejected_before_exports(self):
  index,chunks,entries=parse_index(PERSISTENT,BUNDLED)
  with tempfile.TemporaryDirectory() as temp:
   directory=Path(temp);file=directory/CHUNK;file.write_bytes(bytes(76204));chunks[18]['file']=str(file)
   args=SimpleNamespace(client_dir=CLIENT,paths=['Config/storage-fallback-test.json'],paths_file=None,extract_dir=directory/'raw',output=directory/'report.json',index_sha256=UPDATED_INDEX)
   with patch.object(design,'path_hash',return_value=int('6d8877f08e2ebf38',16)),patch.object(design,'parse_index',return_value=(index,chunks,entries)),self.assertRaisesRegex(ValueError,'MD5'):design.extract(args)
   self.assertFalse(args.extract_dir.exists());self.assertFalse(args.output.exists())
if __name__=='__main__':unittest.main()
