# SPDX-License-Identifier: Apache-2.0
"""check_storage_format: a change to the persisted-format surface is never silent (FMT-1)."""
from datetime import datetime, timezone
from pathlib import Path
import copy
import json
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_storage_format as fmt

ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 11, 14, 12, 0, tzinfo=timezone.utc)
CODEC = 'node/src/test/scala/xyz/kd5ujc/scarab/storage/CodecSuite.scala'
GOLDEN = 'node/src/test/scala/xyz/kd5ujc/scarab/storage/SnapshotGoldenSuite.scala'


def record(**changes):
    entry = {'question': 'May I change the storage format: the V10 sidecar drops two fields? Stores: V8, unaffected.',
             'asked_at': '2026-11-14T09:30:00Z', 'answer': 'Yes, modify V10 directly.',
             'answered_at': '2026-11-14T09:41:07Z'}
    entry.update(changes)
    return entry


class TreeTest(unittest.TestCase):
    """A copy of every file the surface reads, so that each test changes one thing and sees what the guard says."""

    @classmethod
    def setUpClass(cls):
        cls.pristine = tempfile.TemporaryDirectory()
        for path in fmt.surface_paths(ROOT):
            target = Path(cls.pristine.name) / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, target)
        cls.baseline = fmt.surface(ROOT)

    @classmethod
    def tearDownClass(cls):
        cls.pristine.cleanup()

    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name) / 'tree'
        shutil.copytree(self.pristine.name, self.root)

    def edit(self, path, old, new):
        file = self.root / path
        text = file.read_text(encoding='utf-8')
        self.assertIn(old, text, f'{path} no longer holds the text this test edits')
        file.write_text(text.replace(old, new, 1), encoding='utf-8')

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding='utf-8')

    def check(self, manifest=None, approval=None):
        held = {'surface': self.baseline, 'approval': approval} if manifest is None else manifest
        return fmt.problems(self.root, held, NOW)

    def assertLoud(self, reports, *fragments):
        self.assertTrue(reports, 'the guard was silent')
        text = '\n'.join(reports)
        self.assertTrue(all(r.startswith('STORAGE FORMAT CHANGE') for r in reports), text)
        self.assertIn(fmt.RULE, text)
        for fragment in fragments:
            self.assertIn(fragment, text)


class SurfaceTest(TreeTest):
    def test_the_copied_tree_is_the_tree(self):
        self.assertEqual(fmt.surface(self.root), self.baseline)
        self.assertEqual(self.check(), [])

    def test_comments_and_white_space_do_not_count(self):
        self.edit(fmt.REGISTRY, 'enum FormatVersion', '// a comment with "SCRBSNPB" and { braces\nenum FormatVersion')
        self.edit(CODEC, 'private val GoldenSidecarFingerprint', '/* "00" */   private val GoldenSidecarFingerprint')
        self.assertEqual(self.check(), [])

    def test_a_new_version_a_changed_identifier_or_a_new_layout_is_a_change(self):
        for old, new, key in [
            ('case V1, V2, V3, V4, V5, V6, V7, V8, V9, V10', 'case V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11',
             'registry.versions'),
            ('FormatVersion.V10 -> "SCRBSNPA"', 'FormatVersion.V10 -> "SCRBSNPB"', 'registry.kinds.SnapshotFile'),
            ('case TxIdIndex16', 'case TxIdIndex16\n  case TxIdIndex32', 'registry.utxoKeyLayouts'),
        ]:
            with self.subTest(key=key):
                self.setUp()
                self.edit(fmt.REGISTRY, old, new)
                self.assertLoud(self.check(), 'differs from docs/data/storage-format.json', key)

    def test_a_golden_a_magic_a_fixture_or_a_frozen_schema_that_moves_is_a_change(self):
        self.edit(CODEC, '"7650a759442924ec"', '"0000000000000000"')
        self.assertLoud(self.check(), 'goldens.' + CODEC)
        self.setUp()
        self.write('storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotV11.scala',
                   'object SnapshotV11:\n  val Magic = "SCRBSNPB".getBytes("US-ASCII")\n')
        self.assertLoud(self.check(), 'magics.', 'migrations.conversions')
        self.setUp()
        self.write('fixtures/snapshot-v11/preview-4320.bin', 'bytes')
        self.assertLoud(self.check(), 'fixtures.fixtures/snapshot-v11/preview-4320.bin')
        self.setUp()
        fixture = self.root / 'fixtures/snapshot-v10/preview-4320.bin'
        fixture.write_bytes(fixture.read_bytes() + b'\0')
        self.assertLoud(self.check(), 'changed  fixtures.fixtures/snapshot-v10/preview-4320.bin')
        self.setUp()
        self.edit('storage/src/main/scala/xyz/kd5ujc/scarab/storage/FrozenEra.scala', 'enum ', 'enum  Renamed')
        self.assertLoud(self.check(), 'frozen_schemas.')

    def test_a_weakened_golden_assertion_is_a_change(self):
        self.edit(CODEC, 'assertEquals(hex(SnapshotSidecar.fingerprint), GoldenSidecarFingerprint)', 'assert(true)')
        self.assertLoud(self.check(), 'code_sha256')

    def test_a_new_migration_step_is_a_change(self):
        self.edit(fmt.MIGRATION, 'case FormatVersion.V9 => SnapshotV10.fromV9(content)',
                  'case FormatVersion.V9 => SnapshotV10.fromV9(content).flatMap(SnapshotV11.fromV10)')
        self.assertLoud(self.check(), 'migrations.converted')

    def test_a_missing_anchor_is_a_change_never_a_smaller_surface(self):
        (self.root / GOLDEN).unlink()
        self.assertLoud(self.check(), 'cannot be read', GOLDEN)
        self.setUp()
        self.edit(fmt.REGISTRY, 'enum UtxoKeyLayout', 'enum KeyLayouts')
        self.assertLoud(self.check(), 'cannot be read', 'UtxoKeyLayout')

    def test_the_v8_reader_and_the_path_from_v8_stay(self):
        self.edit(fmt.REGISTRY, 'case SnapshotFile => FormatVersion.V5', 'case SnapshotFile => FormatVersion.V9')
        self.assertLoud(self.check(), 'V8 reader', 'readableFrom V9')
        self.setUp()
        self.edit(fmt.MIGRATION, 'case (FormatVersion.V8, FormatVersion.V9) =>', 'case (FormatVersion.V8, FormatVersion.V8) =>')
        self.assertLoud(self.check(), 'no registered migration path from V8 to V10')


class RecordTest(TreeTest):
    def changed(self):
        self.edit(CODEC, '"7650a759442924ec"', '"0000000000000000"')
        return fmt.surface(self.root)

    def test_a_regenerated_manifest_without_an_approval_is_refused(self):
        found = self.changed()
        self.assertLoud(self.check({'surface': found, 'approval': None}), 'other than the baseline')

    def test_an_approval_of_an_earlier_surface_does_not_admit_a_new_one(self):
        earlier = dict(record(), surface_sha256=fmt.digest(self.baseline))
        found = self.changed()
        self.assertLoud(self.check({'surface': found, 'approval': earlier}), 'records the approval of another change')

    def test_a_well_formed_record_of_this_surface_passes(self):
        found = self.changed()
        self.assertEqual(self.check({'surface': found, 'approval': dict(record(), surface_sha256=fmt.digest(found))}),
                         [])

    def test_a_malformed_record_is_refused(self):
        found = self.changed()
        entry = dict(record(answered_at='2026-11-14T09:00:00Z'), surface_sha256=fmt.digest(found))
        self.assertLoud(self.check({'surface': found, 'approval': entry}), 'answered_at is not after asked_at')


class ApprovalFormTest(unittest.TestCase):
    def test_a_complete_record_has_no_problems(self):
        self.assertEqual(fmt.approval_problems(record(), NOW), [])
        self.assertEqual(fmt.approval_problems(record(asked_at='2026-11-14T09:30:00.123Z'), NOW), [])

    def test_each_field_is_required_and_nonempty(self):
        for field in fmt.FIELDS:
            for value in (None, '', '   '):
                entry = record()
                entry.pop(field) if value is None else entry.update({field: value})
                with self.subTest(field=field, value=value):
                    self.assertEqual(fmt.approval_problems(entry, NOW), [f'{field} is missing or empty'])
        self.assertEqual(len(fmt.approval_problems('yes', NOW)), 1)

    def test_times_are_utc_with_seconds_and_the_answer_follows_the_question(self):
        for asked in ('2026-11-14 09:30:00Z', '2026-11-14T09:30Z', '2026-11-14T09:30:00+00:00',
                      '2026-11-14T10:30:00+01:00', 'this morning'):
            with self.subTest(asked=asked):
                self.assertIn('asked_at is not a UTC ISO 8601 time with seconds and Z, such as 2026-11-14T09:30:00Z',
                              fmt.approval_problems(record(asked_at=asked), NOW))
        self.assertEqual(fmt.approval_problems(record(answered_at='2026-11-14T09:30:00Z'), NOW),
                         ['answered_at is not after asked_at: the question comes first'])
        self.assertEqual(fmt.approval_problems(record(answered_at='2026-11-15T09:41:07Z'), NOW),
                         ['answered_at is in the future'])

    def test_the_question_says_plainly_that_it_is_a_storage_format_change(self):
        self.assertEqual(fmt.approval_problems(record(question='May I drop two sidecar fields?'), NOW),
                         ['the question does not say plainly that it is a storage format change'])


class AllowlistTest(unittest.TestCase):
    def test_a_format_change_may_touch_only_its_own_paths(self):
        for path in ('docs/data/storage-format.json', fmt.REGISTRY, fmt.MIGRATION, CODEC,
                     'fixtures/snapshot-v10/eras/byron-1.bin', 'fixtures/snapshot-v11/manifest.json',
                     'docs/storage-migrations.md', 'changes/994-v10-sidecar.md',
                     'tools/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotV10FixtureMain.scala'):
            with self.subTest(path=path):
                self.assertTrue(fmt.allowed(path))
        for path in ('consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/NodeState.scala', 'changes/README.md',
                     'storage/src/main/scala/xyz/kd5ujc/scarab/storage/scls/SclsBase.scala', 'AGENTS.md',
                     'docs/design-rules.md', 'scripts/pr_policy.py', 'fixtures/eras/genesis/byron.json'):
            with self.subTest(path=path):
                self.assertFalse(fmt.allowed(path))
        self.assertEqual(fmt.outside_format_change([fmt.MANIFEST, 'AGENTS.md', 'README.md']), ['AGENTS.md', 'README.md'])

    def test_every_file_the_surface_reads_is_inside_the_allowlist(self):
        self.assertEqual([p for p in fmt.surface_paths(ROOT) if not fmt.allowed(p)], [])

    def test_the_manifest_and_the_guard_are_the_freeze_paths(self):
        self.assertTrue(fmt.touches_freeze(['a', fmt.MANIFEST]))
        self.assertTrue(fmt.touches_freeze([fmt.GUARD]))
        self.assertFalse(fmt.touches_freeze([fmt.REGISTRY, CODEC]))


class LexerTest(unittest.TestCase):
    def test_comments_go_and_literals_stay(self):
        code, literals = fmt.scan('val a = "x // y" // gone "z"\n/* gone /* nested */ "w" */ val b = \'"\'; s"${c}d"')
        self.assertNotIn('gone', code)
        self.assertEqual(literals, ['x // y', '${c}d'])
        self.assertIn('\'"\'', code)


class CommittedManifestTest(unittest.TestCase):
    def test_the_committed_manifest_admits_the_tree(self):
        manifest = json.loads((ROOT / fmt.MANIFEST).read_text(encoding='utf-8'))
        self.assertEqual(fmt.problems(ROOT, manifest, NOW), [])

    def test_the_baseline_admits_only_its_own_surface(self):
        manifest = json.loads((ROOT / fmt.MANIFEST).read_text(encoding='utf-8'))
        if manifest['approval'] is None:
            self.assertEqual(fmt.digest(manifest['surface']), fmt.BASELINE_SURFACE_SHA256)
        moved = copy.deepcopy(manifest)
        moved['surface']['registry']['versions'].append('V11')
        self.assertNotEqual(fmt.digest(moved['surface']), fmt.BASELINE_SURFACE_SHA256)


if __name__ == '__main__':
    unittest.main()
