# SPDX-License-Identifier: Apache-2.0
"""check_storage_format: a change to the persisted-format surface is never silent (FMT-1)."""
from contextlib import redirect_stderr
from datetime import datetime, timezone
from pathlib import Path
import copy
import io
import json
import os
import re
import shutil
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_storage_format as fmt

ROOT = Path(__file__).resolve().parents[2]
NOW = datetime(2026, 11, 14, 12, 0, tzinfo=timezone.utc)
CODEC = 'node/src/test/scala/xyz/kd5ujc/scarab/storage/CodecSuite.scala'
GOLDEN = 'node/src/test/scala/xyz/kd5ujc/scarab/storage/SnapshotGoldenSuite.scala'
IDENTITY = 'ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/NetworkIdentity.scala'
UTXO = 'storage/src/main/scala/xyz/kd5ujc/scarab/storage/UtxoStore.scala'
STORAGE = 'storage/src/main/scala/xyz/kd5ujc/scarab/storage'


def record(**changes):
    entry = {'question': 'May I change the storage format: the V10 sidecar drops two fields? Stores: V8, unaffected.',
             'asked_at': '2026-11-14T09:30:00Z', 'decision': 'Approved: edit the V10 sidecar in place.',
             'decided_at': '2026-11-14T09:41:07Z'}
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
        # The committed manifest's approval admits the committed surface, which the pristine copy is; a test that
        # wants no approval, or another one, passes a whole manifest.
        cls.approval = json.loads((ROOT / fmt.MANIFEST).read_text(encoding='utf-8')).get('approval')

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

    def check(self, manifest=None):
        held = {'surface': self.baseline, 'approval': self.approval} if manifest is None else manifest
        # The real clock, not NOW: the committed approval was decided after NOW, and an approval is refused only when
        # it is in the future of the check.
        return fmt.problems(self.root, held, datetime.now(timezone.utc))

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
        # the golden as the tree holds it, so that a deliberate change of the golden leaves this test as it is
        golden = re.search(r'private val GoldenSidecarFingerprint = "[0-9a-f]+"',
                           (self.root / CODEC).read_text(encoding='utf-8'))
        self.assertIsNotNone(golden, f'{CODEC} no longer holds the golden this test edits')
        self.edit(CODEC, golden.group(0), golden.group(0) + ' /* "00" { */   ')
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

    def test_a_reader_or_writer_of_a_format_source_that_changes_is_a_change(self):
        self.edit(IDENTITY, '|conway-genesis ${', '|conway-genesis  ${')
        self.assertLoud(self.check(), 'format_sources.' + IDENTITY)
        self.setUp()
        self.edit(fmt.MIGRATION, 'SnapshotV10.fromV9(content)', 'SnapshotV10.fromV9(content.clone())')
        self.assertLoud(self.check(), 'format_sources.' + fmt.MIGRATION)

    def test_a_codec_of_the_haskell_state_a_v10_snapshot_holds_that_changes_is_a_change(self):
        conway, ledger_file = f'{fmt.HASKELL_STATE}/ConwayCodecs.scala', f'{fmt.HASKELL_STATE}/LedgerFileCodecs.scala'
        self.edit(conway, 'alternative(out, 2, s.kind.tag)', 'alternative(out, 2, s.kind.tag + 1)')
        self.assertLoud(self.check(), 'format_sources.' + conway)
        self.setUp()
        self.edit(ledger_file, '      out.arrayHead(2)', '      out.arrayHead(3)')
        self.assertLoud(self.check(), 'format_sources.' + ledger_file)
        self.setUp()
        self.edit(ledger_file, '  /** `AnnTip` of the Cardano chain', '  /** The `AnnTip` of the Cardano chain')
        self.assertEqual(self.check(), [])
        # an inline comment is no change either
        self.setUp()
        self.edit(ledger_file, '      out.arrayHead(2)', '      out.arrayHead(/* the era and its tip */ 2)')
        self.assertEqual(self.check(), [])
        self.setUp()
        (self.root / conway).unlink()
        self.assertLoud(self.check(), 'cannot be read', conway)
        # the codecs no V10 snapshot reaches are not on the surface
        self.assertNotIn(f'{fmt.HASKELL_STATE}/UtxoCodecs.scala', fmt.surface_paths(ROOT))

    def test_the_writer_and_the_relay_encoding_the_haskell_state_writes_with_are_pinned(self):
        writer = f'{fmt.MODELS_RUNTIME}/Writer.scala'
        self.edit(writer, 'put(if b then 0xf5 else 0xf4)', 'put(if b then 0xf4 else 0xf5)')
        self.assertLoud(self.check(), 'format_sources.' + writer)
        self.setUp()
        self.edit(fmt.POOL_RELAYS, 'private inline val LengthThreshold = 23', 'private inline val LengthThreshold = 24')
        self.assertLoud(self.check(), 'format_members.' + fmt.POOL_RELAYS + '#object PoolRelays')
        self.setUp()
        self.edit(fmt.POOL_RELAYS, 'def wire: Bytes = Bytes.fromArray(Ipv6.swapWords(octets.toArray))',
                  'def wire: Bytes = octets')
        self.assertLoud(self.check(), 'format_members.' + fmt.POOL_RELAYS + '#Ipv6')
        # how an address is shown is not how it is written
        self.setUp()
        self.edit(fmt.POOL_RELAYS, 'case None => groups.map(_.toHexString).mkString(":")',
                  'case None => groups.map(_.toHexString).mkString("-")')
        self.assertEqual(self.check(), [])

    def test_the_definitions_of_other_packages_on_the_v10_body_path_are_pinned(self):
        # the edits an independent review made to show that the guard did not see them (#1241)
        convert, shared = f'{fmt.LEDGER_MAIN}/rules/Convert.scala', f'{fmt.LEDGER_STATE}/Shared.scala'
        era, layout = f'{fmt.PROTOCOL_MAIN}/Era.scala', f'{fmt.PROTOCOL_MAIN}/StateLayout.scala'
        cbor = f'{fmt.CODEC_MAIN}/Cbor.scala'
        for path, old, new, key in [
            # the relay decoder strips the dot that ends an SRV name
            (fmt.POOL_RELAYS, 'dns(f(1), index, reading).map(PoolRelay.MultiHostName(_))',
             'dns(f(1), index, reading).map(n => PoolRelay.MultiHostName(n.stripSuffix(".")))', '#object PoolRelays'),
            # a pool's future pledge is written from its cost
            (convert, '      p.pledge.value,', '      p.cost.value,', '#Convert.poolParamsBack'),
            # a set of two or more elements is written in reverse
            (shared, 'SortedSet.from(keys)(using orderingOf(Order[K]))',
             'SortedSet.from(keys)(using orderingOf(Order[K]).reverse)', '#Sorted.set'),
            # Conway's era index
            (era, 'case Conway extends Era(6, 7)', 'case Conway extends Era(9, 7)', '#enum Era'),
            # the decoder reads a single-precision float only when simple values are allowed too
            (cbor, 'case 26 if limits.allowFloats => floatBits(4)',
             'case 26 if limits.allowFloats && limits.allowSimpleValues => floatBits(4)', '#Cbor.Decoder'),
            # a stored update is read with the transaction's stricter readers
            (fmt.CONWAY_PARAM_UPDATE, 'read(n, ConwayParam.stored)', 'read(n, ConwayParam.decoders)',
             '#ConwayParamUpdate.decodeStored'),
            # the 11.1 build is said to write the Leios layout
            (layout, 'case ReferenceBuild.Release11_1   => Right(Node11_1)',
             'case ReferenceBuild.Release11_1   => Right(Leios)', '#StateLayout.of'),
        ]:
            with self.subTest(key=key):
                self.setUp()
                self.edit(path, old, new)
                self.assertLoud(self.check(), 'format_members.' + path + key)
        # the rest of those files is not pinned: an era's block tag reading, an address's rendering
        self.setUp()
        self.edit(era, 'if tag == 0 then Some(Byron)', 'if tag == 0 then Option(Byron)')
        self.assertEqual(self.check(), [])

    def test_a_format_definition_in_a_mixed_file_is_pinned_and_the_rest_of_the_file_is_not(self):
        self.edit(UTXO, 'a(0) = 1', 'a(0) = 2')
        self.assertLoud(self.check(), 'format_members.' + UTXO + '#UtxoStore.Coding')
        self.setUp()
        self.edit(UTXO, '  /** Value encoding shared', '  private val unrelated = 1\n\n  /** Value encoding shared')
        self.assertEqual(self.check(), [])
        self.setUp()
        self.edit(UTXO, 'object Coding:', 'object Codings:')
        self.assertLoud(self.check(), 'cannot be read', 'UtxoStore.Coding')

    def test_a_constant_or_codec_of_the_stores_that_changes_is_a_change(self):
        for path, old, new, key in [
            (f'{STORAGE}/RocksUtxoStore.scala', 'private val StampKey = Array[Byte](0)',
             'private val StampKey = Array[Byte](1)', '#RocksUtxoStore.StampKey'),
            (f'{STORAGE}/ImmutableStore.scala', 'private val Suffix = " chunkSlots="', 'private val Suffix = " slots="',
             '#FileImmutableStore.formatMigration'),
            (f'{STORAGE}/VolatileStore.scala', 'private val FlagOrigin = 2', 'private val FlagOrigin = 4',
             '#VolatileStore.FlagOrigin'),
        ]:
            with self.subTest(key=key):
                self.setUp()
                self.edit(path, old, new)
                self.assertLoud(self.check(), 'format_members.' + path + key)
        self.setUp()
        self.edit(f'{STORAGE}/VolatileStore.scala', 'def open[F[_]: Async](dir: Path, blocksPerFile: Int = 512)',
                  'def open[F[_]: Async](dir: Path, blocksPerFile: Int = 1024)')
        self.assertEqual(self.check(), [])

    def test_a_consistent_new_version_passes_the_registry_checks_and_still_fires(self):
        self.edit(fmt.REGISTRY, 'case V1, V2, V3, V4, V5, V6, V7, V8, V9, V10',
                  'case V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11')
        self.edit(fmt.REGISTRY, 'FormatVersion.V10 -> "SCRBSNPA"',
                  'FormatVersion.V10 -> "SCRBSNPA",\n          FormatVersion.V11 -> "SCRBSNPB"')
        self.write(f'{STORAGE}/SnapshotV11.scala', 'object SnapshotV11:\n  val Magic = "SCRBSNPB".getBytes("US-ASCII")\n'
                   '  def fromV10(content: Array[Byte]) = Right(content)\n')
        self.edit(fmt.MIGRATION, '        .flatMap(SnapshotV10.fromV9)\n    case FormatVersion.V7',
                  '        .flatMap(SnapshotV10.fromV9)\n        .flatMap(SnapshotV11.fromV10)\n    case FormatVersion.V7')
        self.edit(fmt.MIGRATION, 'SnapshotV8.fromV7(content).flatMap(SnapshotV9.fromV8).flatMap(SnapshotV10.fromV9)',
                  'SnapshotV8.fromV7(content).flatMap(SnapshotV9.fromV8).flatMap(SnapshotV10.fromV9)'
                  '.flatMap(SnapshotV11.fromV10)')
        self.edit(fmt.MIGRATION, 'case FormatVersion.V8 => SnapshotV9.fromV8(content).flatMap(SnapshotV10.fromV9)',
                  'case FormatVersion.V8 => SnapshotV9.fromV8(content).flatMap(SnapshotV10.fromV9)'
                  '.flatMap(SnapshotV11.fromV10)')
        self.edit(fmt.MIGRATION, 'case FormatVersion.V9 => SnapshotV10.fromV9(content)',
                  'case FormatVersion.V9 => SnapshotV10.fromV9(content).flatMap(SnapshotV11.fromV10)\n'
                  '    case FormatVersion.V10 => SnapshotV11.fromV10(content)')
        self.edit(fmt.MIGRATION, 'case (FormatVersion.V9, FormatVersion.V10) =>',
                  'case (FormatVersion.V10, FormatVersion.V11) => ???\n        case (FormatVersion.V9, FormatVersion.V10) =>')
        found = fmt.surface(self.root)
        self.assertEqual(fmt.migration_problems(found['registry'], found['migrations']), [])
        self.assertEqual(fmt.deployed_problems(found), [])
        self.assertLoud(self.check(), 'registry.versions', 'magics.' + f'{STORAGE}/SnapshotV11.scala')

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

    def test_a_registered_migration_that_disagrees_with_the_registry_is_refused(self):
        self.edit(fmt.MIGRATION, 'case FormatVersion.V9 => SnapshotV10.fromV9(content)', '')
        self.assertLoud(self.check(), 'disagrees with the format registry', 'converted V9 is None')
        self.setUp()
        self.edit(fmt.MIGRATION, 'case FormatVersion.V8 => SnapshotV9.fromV8(content).flatMap(SnapshotV10.fromV9)',
                  'case FormatVersion.V8 => SnapshotV10.fromV9(content)')
        self.assertLoud(self.check(), "converted V8 is ['SnapshotV10.fromV9']")
        self.setUp()
        self.edit(fmt.MIGRATION, 'case FormatVersion.V9 =>', 'case\n      FormatVersion\n        .V9 =>')
        self.assertEqual(fmt.migration_problems(fmt.registry(self.root), fmt.migrations(self.root)), [])

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
        entry = dict(record(decided_at='2026-11-14T09:00:00Z'), surface_sha256=fmt.digest(found))
        self.assertLoud(self.check({'surface': found, 'approval': entry}), 'decided_at is not after asked_at')


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

    def test_times_are_utc_with_seconds_and_the_decision_follows_the_question(self):
        for asked in ('2026-11-14 09:30:00Z', '2026-11-14T09:30Z', '2026-11-14T09:30:00+00:00',
                      '2026-11-14T10:30:00+01:00', 'this morning'):
            with self.subTest(asked=asked):
                self.assertIn('asked_at is not a UTC ISO 8601 time with seconds and Z, such as 2026-11-14T09:30:00Z',
                              fmt.approval_problems(record(asked_at=asked), NOW))
        self.assertEqual(fmt.approval_problems(record(decided_at='2026-11-14T09:30:00Z'), NOW),
                         ['decided_at is not after asked_at: the question comes first'])
        self.assertEqual(fmt.approval_problems(record(decided_at='2026-11-15T09:41:07Z'), NOW),
                         ['decided_at is in the future'])

    def test_a_date_that_is_not_in_the_calendar_is_a_problem_not_an_error(self):
        self.assertIn('asked_at is not a UTC ISO 8601 time with seconds and Z, such as 2026-11-14T09:30:00Z',
                      fmt.approval_problems(record(asked_at='2026-02-30T09:30:00Z'), NOW))

    def test_the_question_says_plainly_that_it_is_a_storage_format_change(self):
        self.assertEqual(fmt.approval_problems(record(question='May I drop two sidecar fields?'), NOW),
                         ['the question does not say plainly that it is a storage format change'])


class DigestTest(unittest.TestCase):
    def test_comments_trailing_space_and_empty_lines_do_not_count(self):
        self.assertEqual(fmt.code_digest('val a = 1\n\n// note\nval b = 2   \n'),
                         fmt.code_digest('/** doc */\nval a = 1 // why\nval b = 2\n'))

    def test_a_comment_is_read_as_absent_at_the_token_boundary_it_stands_on(self):
        for with_comment, without in [
            ('f(a, /*c*/ b)', 'f(a, b)'), ('f(a,/*c*/b)', 'f(a,b)'), ('out.arrayHead(/* x */2)', 'out.arrayHead(2)'),
            ('out.arrayHead(2 /* x */)', 'out.arrayHead(2)'), ('a /* x */ + b', 'a + b'), ('a/*x*/ b', 'a b'),
            ('x(1, /* a */ /* b */ 2)', 'x(1, 2)'), ('  /** a\n    * b\n    */\n  def x = 1', '  def x = 1'),
            ('val a = 1 /* a\n b */\nval b = 2', 'val a = 1\nval b = 2'),
            ('  /** doc */\n  def x = 1', '  def x = 1'), ('  /* a */  def x = 1', '  def x = 1'),
            ('if c then\n  /* x */ f()\ng()', 'if c then\n  f()\ng()'),
            ('val a = 1 // why\nval b = 2', 'val a = 1\nval b = 2'),
        ]:
            with self.subTest(code=with_comment):
                self.assertEqual(fmt.code_digest(with_comment), fmt.code_digest(without))
        # a comment that separates two tokens still does: removing it would fuse them
        for with_comment, fused in [('a/**/b', 'ab'), ('s/**/"x"', 's"x"'), ('+/**/-', '+-'), ('1/**/2', '12'),
                                    # a line break inside a comment still ends the line: `g` is no longer applied
                                    ('g /* c\n */ (1)', 'g (1)'), ('foo /* a\n b */ bar', 'foo bar')]:
            with self.subTest(code=with_comment):
                self.assertNotEqual(fmt.code_digest(with_comment), fmt.code_digest(fused))
        # and indentation still counts
        self.assertNotEqual(fmt.code_digest('if c then\n  f()\n/* x */  g()'),
                            fmt.code_digest('if c then\n  f()\n  g()'))

    def test_literals_and_indentation_count(self):
        self.assertNotEqual(fmt.code_digest('val a = "x y"'), fmt.code_digest('val a = "x  y"'))
        text = 'val a = s"""|magic $m\n       |next"""\n'
        for changed in ('val a = s"""|magic $m  \n       |next"""\n', 'val a = s"""|magic $m\n\n       |next"""\n'):
            with self.subTest(changed=changed):
                self.assertNotEqual(fmt.code_digest(text), fmt.code_digest(changed))
        self.assertNotEqual(fmt.code_digest('if c then\n  f()\ng()'), fmt.code_digest('if c then\n  f()\n  g()'))

    def test_a_member_is_its_header_and_what_is_indented_under_it(self):
        code = 'object A:\n  def f(x: Int) =\n    x + 1\n\n    + 2\n  def g = 3\nobject B:\n  def f(x: Int) = 0\n'
        self.assertEqual(fmt.member(code, r'object A:', r'def f\(', 'f'), '  def f(x: Int) =\n    x + 1\n\n    + 2')
        with self.assertRaisesRegex(fmt.SurfaceError, '2 definitions'):
            fmt.member(code, None, r'def f\(', 'f')

    def test_a_member_whose_signature_or_body_spans_lines_is_whole(self):
        for code, body in [
            ('object S:\n  def stem(\n      a: BlockRef\n  )(using\n      R: Raise[F, E]\n  ): String =\n    "snapshot-"\n'
             '  def other = 1\n', '    "snapshot-"'),
            ('object S:\n  def stem(a: BlockRef): String = {\n  "snapshot-"\n  }\n  def other = 1\n', '  }'),
        ]:
            with self.subTest(code=code):
                source, texts = fmt.masked(code)
                found = fmt.member(source, r'object S:', r'def stem\(', 'stem')
                self.assertEqual(found.splitlines()[-1].replace('\x000\x00', '"snapshot-"'), body)
                self.assertNotIn('other', found)
                changed, more = fmt.masked(code.replace('snapshot-', 'checkpoint-'))
                self.assertNotEqual(fmt.digest_of_masked(found, texts),
                                    fmt.digest_of_masked(fmt.member(changed, r'object S:', r'def stem\(', 'stem'), more))


class AllowlistTest(unittest.TestCase):
    def test_a_format_change_may_touch_only_its_own_paths(self):
        for path in ('docs/data/storage-format.json', fmt.REGISTRY, fmt.MIGRATION, CODEC,
                     'fixtures/snapshot-v10/eras/byron-1.bin', 'fixtures/snapshot-v11/manifest.json',
                     'docs/storage-migrations.md', 'changes/994-v10-sidecar.md',
                     'tools/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotV10FixtureMain.scala',
                     'docs/data/correspondence/storage.json'):
            with self.subTest(path=path):
                self.assertTrue(fmt.allowed(path))
        for path in ('consensus/src/main/scala/xyz/kd5ujc/scarab/consensus/NodeState.scala', 'changes/README.md',
                     'storage/src/main/scala/xyz/kd5ujc/scarab/storage/scls/SclsBase.scala', 'AGENTS.md',
                     'docs/design-rules.md', 'scripts/pr_policy.py', 'fixtures/eras/genesis/byron.json',
                     'docs/data/correspondence-baseline.json', 'docs/data/correspondence/storage/extra.json'):
            with self.subTest(path=path):
                self.assertFalse(fmt.allowed(path))
        self.assertEqual(fmt.outside_format_change([fmt.MANIFEST, 'AGENTS.md', 'README.md']), ['AGENTS.md', 'README.md'])

    def test_a_one_off_exception_holds_for_its_pull_request_only(self):
        for number, paths in fmt.ONE_OFF_PATHS.items():
            with self.subTest(number=number):
                self.assertFalse(any(fmt.allowed(p) for p in paths))
                self.assertEqual(fmt.outside_format_change(list(paths), pull_request=number), [])
                self.assertEqual(fmt.outside_format_change(list(paths), pull_request=number + 1), sorted(paths))
                self.assertEqual(fmt.outside_format_change(['AGENTS.md', *paths], pull_request=number), ['AGENTS.md'])

    def test_the_pull_request_is_read_from_the_events_and_none_on_a_doubt(self):
        number, paths = next(iter(fmt.ONE_OFF_PATHS.items()))
        with tempfile.TemporaryDirectory() as tmp:
            def event(name, content):
                path = Path(tmp) / name
                path.write_text(content, encoding='utf-8')
                return str(path)
            this = event('this.json', json.dumps({'pull_request': {'number': number}}))
            other = event('other.json', json.dumps({'pull_request': {'number': number + 1}}))
            self.assertEqual(fmt.event_pull_request(this), number)
            self.assertIsNone(fmt.event_pull_request(event('broken.json', '{')))
            self.assertIsNone(fmt.event_pull_request(str(Path(tmp) / 'missing.json')))
            self.assertIsNone(fmt.event_pull_request(event('text.json', json.dumps({'pull_request': {'number': str(number)}}))))
            self.assertIsNone(fmt.event_pull_request(event('none.json', json.dumps({'action': 'opened'}))))
            # the environment's event, an --event argument, or both naming one pull request
            self.assertEqual(fmt.policy_pull_request([], {'GITHUB_EVENT_PATH': this}), number)
            self.assertEqual(fmt.policy_pull_request(['--event', this], {}), number)
            self.assertEqual(fmt.policy_pull_request([f'--event={this}'], {'GITHUB_EVENT_PATH': this}), number)
            # argparse's abbreviations of --event, as pr_policy.py accepts them
            for form in (['--e', other], ['--ev', other], ['--even', other], [f'--eve={other}']):
                with self.subTest(form=form[0]):
                    self.assertEqual(fmt.event_paths(form, {}), [other])
                    self.assertIsNone(fmt.policy_pull_request(form, {'GITHUB_EVENT_PATH': this}))
            self.assertEqual(fmt.event_paths(['--files', 'f.txt', '--mode', 'transition'], {}), [])
            # events that disagree, or none, grant nothing
            self.assertIsNone(fmt.policy_pull_request(['--event', other], {'GITHUB_EVENT_PATH': this}))
            self.assertIsNone(fmt.policy_pull_request(['--event', this], {'GITHUB_EVENT_PATH': other}))
            self.assertIsNone(fmt.policy_pull_request([], {}))
            with mock.patch.dict(os.environ, {'GITHUB_EVENT_PATH': this}), mock.patch.object(sys, 'argv', ['pr_policy.py']):
                self.assertEqual(fmt.outside_format_change(list(paths)), [])
            with mock.patch.dict(os.environ, {'GITHUB_EVENT_PATH': this}), \
                    mock.patch.object(sys, 'argv', ['pr_policy.py', '--event', other]):
                self.assertEqual(fmt.outside_format_change(list(paths)), sorted(paths))
            with mock.patch.dict(os.environ, {'GITHUB_EVENT_PATH': other}), mock.patch.object(sys, 'argv', ['pr_policy.py']):
                self.assertEqual(fmt.outside_format_change(list(paths)), sorted(paths))

    def test_every_file_the_surface_reads_is_inside_the_allowlist(self):
        self.assertEqual([p for p in fmt.surface_paths(ROOT) if not fmt.allowed(p)], [])

    def test_the_manifest_the_guard_and_its_tests_are_the_freeze_paths(self):
        self.assertTrue(fmt.touches_freeze(['a', fmt.MANIFEST]))
        self.assertTrue(fmt.touches_freeze([fmt.GUARD]))
        self.assertTrue(fmt.touches_freeze([fmt.GUARD_TESTS]))
        self.assertFalse(fmt.touches_freeze([fmt.REGISTRY, CODEC]))


class LexerTest(unittest.TestCase):
    def test_comments_go_and_literals_stay(self):
        code, literals = fmt.scan('val a = "x // y" // gone "z"\n/* gone /* nested */ "w" */ val b = \'"\'; s"${c}d"')
        self.assertNotIn('gone', code)
        self.assertEqual(literals, ['x // y', '${c}d'])
        self.assertIn('\'"\'', code)

    def test_an_interpolation_with_quotes_does_not_end_its_literal(self):
        code, literals = fmt.scan('val a = s"v${f("x")}" // gone\nval b = 1')
        self.assertEqual(literals, ['v${f("x")}'])
        self.assertNotIn('gone', code)
        self.assertIn('val b = 1', code)


class CommittedManifestTest(unittest.TestCase):
    def test_the_committed_manifest_admits_the_tree(self):
        manifest = json.loads((ROOT / fmt.MANIFEST).read_text(encoding='utf-8'))
        self.assertEqual(fmt.problems(ROOT, manifest, datetime.now(timezone.utc)), [])

    def test_the_guard_is_registered_so_that_preflight_and_ci_run_it(self):
        registry = json.loads((ROOT / 'scripts/guards.json').read_text(encoding='utf-8'))
        self.assertIn([fmt.GUARD], [g['argv'] for g in registry['guards']])

    def test_a_manifest_that_is_not_json_is_a_report_not_a_traceback(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        root = Path(scratch.name)
        (root / fmt.MANIFEST).parent.mkdir(parents=True)
        (root / fmt.MANIFEST).write_text('{ not json', encoding='utf-8')
        self.assertIsNone(fmt.load_manifest(root))
        err = io.StringIO()
        with redirect_stderr(err):
            self.assertEqual(fmt.main(['--root', str(root)]), 1)
        self.assertTrue(err.getvalue().startswith('STORAGE FORMAT CHANGE'))

    def test_the_baseline_admits_only_its_own_surface(self):
        manifest = json.loads((ROOT / fmt.MANIFEST).read_text(encoding='utf-8'))
        if manifest['approval'] is None:
            self.assertEqual(fmt.digest(manifest['surface']), fmt.BASELINE_SURFACE_SHA256)
        moved = copy.deepcopy(manifest)
        moved['surface']['registry']['versions'].append('V11')
        self.assertNotEqual(fmt.digest(moved['surface']), fmt.BASELINE_SURFACE_SHA256)


if __name__ == '__main__':
    unittest.main()
