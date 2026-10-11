"""Fixture path selection guards only; complete content validation remains the real source preflight."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

OWN=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('effects_path_guards',OWN/'smoke-daily-origin-effects.py')
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)


class EffectsSourcePathGuardTest(unittest.TestCase):
    def test_only_an_absent_primary_uses_the_bundled_complete_fixture(self):
        with tempfile.TemporaryDirectory()as directory:
            previous=(smoke.ROOT,smoke.OWN)
            try:
                smoke.ROOT=Path(directory);smoke.OWN=Path(directory)/'scripts/fixtures';smoke.OWN.mkdir(parents=True)
                fallback=smoke.OWN/'example.json';fallback.write_text('bundled')
                self.assertEqual(fallback,smoke.source_path('example.json'))
                primary=smoke.ROOT/'src/test/resources/fixtures/example.json';primary.parent.mkdir(parents=True);primary.write_text('invalid primary')
                self.assertEqual(primary,smoke.source_path('example.json'))
            finally:smoke.ROOT,smoke.OWN=previous

    def test_missing_source_or_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory()as directory:
            previous=(smoke.ROOT,smoke.OWN)
            try:
                smoke.ROOT=Path(directory);smoke.OWN=Path(directory)/'scripts/fixtures'
                with self.assertRaises(FileNotFoundError):smoke.source_path('missing.json')
                with self.assertRaises(AssertionError):smoke.source_path('../foreign.json')
            finally:smoke.ROOT,smoke.OWN=previous


if __name__=='__main__':unittest.main()
