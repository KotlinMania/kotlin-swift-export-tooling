import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_helper', REPO / 'scripts/prepare_native_sdk.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)

class NativeHelperTests(unittest.TestCase):
    def setUp(self):
        root = REPO / 'build/helper-tests'
        root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=root)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        (self.source / 'header.h').write_text('original\n')
        (self.source / 'compiler.bin').write_bytes(b'compiler input')
        self.patch = self.root / 'header.patch'
        self.patch.write_text('--- a/header.h\n+++ b/header.h\n@@ -1 +1 @@\n-original\n+corrected\n')
        self.lock = {'native_header': {'relative_path': 'header.h', 'baseline_sha256': helper.sha(self.source / 'header.h'), 'candidate_sha256': helper.hashlib.sha256(b'corrected\n').hexdigest()}, 'native_identity': {'compiler.bin': helper.sha(self.source / 'compiler.bin')}, 'sources': [{'kind': 'header', 'patch_sha256': helper.sha(self.patch)}]}
        self.destination = self.root / 'prepared'
    def tearDown(self):
        self.temporary.cleanup()
    def test_exact_copy_only_header_changes(self):
        before = helper.inventory(self.source)
        result = helper.prepare(self.source, self.destination, self.lock, self.patch)
        self.assertEqual(result['changed_files'], ['header.h'])
        self.assertEqual(helper.inventory(self.source), before)
        self.assertEqual((self.destination / 'compiler.bin').read_bytes(), b'compiler input')
    def test_existing_destination_is_rejected(self):
        self.destination.mkdir()
        with self.assertRaises(ValueError): helper.prepare(self.source, self.destination, self.lock, self.patch)
    def test_nested_destination_is_rejected(self):
        with self.assertRaises(ValueError): helper.prepare(self.source, self.source / 'copy', self.lock, self.patch)
    def test_wrong_header_is_rejected(self):
        (self.source / 'header.h').write_text('different\n')
        with self.assertRaises(ValueError): helper.prepare(self.source, self.destination, self.lock, self.patch)
        self.assertFalse(self.destination.exists())
    def test_wrong_compiler_is_rejected(self):
        (self.source / 'compiler.bin').write_bytes(b'wrong')
        with self.assertRaises(ValueError): helper.prepare(self.source, self.destination, self.lock, self.patch)
        self.assertFalse(self.destination.exists())
    def test_wrong_patch_is_rejected(self):
        self.patch.write_text('wrong')
        with self.assertRaises(ValueError): helper.prepare(self.source, self.destination, self.lock, self.patch)
        self.assertFalse(self.destination.exists())

if __name__ == '__main__': unittest.main()
