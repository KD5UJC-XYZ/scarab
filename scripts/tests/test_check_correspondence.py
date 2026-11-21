# SPDX-License-Identifier: Apache-2.0
"""check_correspondence: every definition of a mirrored area is mapped, glue or baselined, and every entry is
current, complete and true at its pin where a clone can show it."""
from contextlib import redirect_stdout
from pathlib import Path
import io
import json
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_correspondence as guard  # noqa: E402
import scala_definitions  # noqa: E402
import shrinking_baseline  # noqa: E402

REPOSITORY = 'IntersectMBO/ouroboros-network'
SOURCE = 'net/src/main/scala/xyz/kd5ujc/scarab/net/KeepAlive.scala'
CODE = '''package xyz.kd5ujc.scarab.net

object KeepAlive:
  /** The states. */
  enum State:
    case Client, Server, Done

  def transition(state: State): State = state match
    case State.Client => State.Server
    case _            => State.Client

  def helper(n: Int): Int = n
'''
UPSTREAM = '''module Ouroboros.Network.Protocol.KeepAlive.Type where

data KeepAlive where
    StClient :: KeepAlive
    StServer :: KeepAlive
    StDone   :: KeepAlive
'''
UPSTREAM_FILE = 'protocols/lib/Ouroboros/Network/Protocol/KeepAlive/Type.hs'
SUITE_FILE = 'net/src/test/scala/KeepAliveSuite.scala'
CORPUS_FILE = 'fixtures/reference/keep-alive/cases.jsonl'


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(['git', '-C', str(cwd), *args], check=True, capture_output=True, text=True,
                          env={'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@t', 'GIT_COMMITTER_NAME': 't',
                               'GIT_COMMITTER_EMAIL': 't@t', 'HOME': str(cwd), 'PATH': '/usr/bin:/bin'}).stdout


class Fixture:
    """A repository with one area (`net`), one source, the pins, a test suite, a reference corpus and a register of
    comparison contracts that holds none yet."""

    def __init__(self, tmp: Path, revision: str = 'a' * 40):
        self.root = tmp / 'repo'
        self.revision = revision
        (self.root / 'net/src/main/scala/xyz/kd5ujc/scarab/net').mkdir(parents=True)
        (self.root / 'net/src/test/scala').mkdir(parents=True)
        (self.root / 'docs/data/correspondence').mkdir(parents=True)
        (self.root / SUITE_FILE).write_text('class KeepAliveSuite extends munit.FunSuite\n')
        (self.root / CORPUS_FILE).parent.mkdir(parents=True)
        (self.root / CORPUS_FILE).write_text('{}\n')
        (self.root / CORPUS_FILE).with_name('README.md').write_text('Recorded from the reference node.\n')
        self.write_pins([revision])
        self.register([])
        self.source(CODE)
        self.area({}, [])

    def write_pins(self, revisions):
        pins = [{'name': 'ouroboros-network', 'repository': REPOSITORY, 'revision': r, 'role': 'x'} for r in revisions]
        (self.root / guard.PINS).write_text(json.dumps({'schemaVersion': 1, 'pins': pins}))

    def register(self, rows: list[dict]) -> None:
        (self.root / guard.REGISTER).write_text(json.dumps({'schemaVersion': 1, 'entries': rows}))

    @staticmethod
    def contract(evidence: list[str], **fields) -> dict:
        """A register entry: a covered native-parity comparison, unless `fields` says otherwise."""
        row = {'id': 'keep-alive', 'classification': 'native-parity', 'status': 'covered', 'scope': 's',
               'assertion': 'a', 'rationale': 'r', 'owner': 'o', 'tracking': 't', 'resolution': 'r',
               'evidence': evidence, 'candidateRequired': False}
        row.update(fields)
        return row

    def source(self, text: str) -> None:
        (self.root / SOURCE).write_text(text)

    def area(self, entries: dict, glue: list) -> None:
        body = {'area': 'net', 'roots': ['net/src/main/scala'], 'entries': entries, 'glue': glue}
        (self.root / guard.MAP / 'net.json').write_text(json.dumps(body))

    def digest(self, key: str) -> str:
        found = scala_definitions.read((self.root / SOURCE).read_text()).definitions
        return {d.key: d.digest for d in found}[key]

    def upstream(self, symbol: str = 'KeepAlive', lines=(3, 6)) -> list[dict]:
        return [{'repository': REPOSITORY, 'revision': self.revision, 'file': UPSTREAM_FILE, 'symbol': symbol,
                 'lines': list(lines)}]

    def entry(self, key: str, **fields) -> dict:
        entry = {'upstream': self.upstream(), 'class': 'Mirror', 'structure': 'same', 'digest': self.digest(key)}
        entry.update(fields)
        return entry

    def run(self, *args: str, sources: Path | None = None) -> tuple[int, str]:
        out = io.StringIO()
        argv = list(args) + (['--sources', str(sources)] if sources is not None else [])
        # an empty environment: a run in CI must not read as a pull request's check of this fixture
        with redirect_stdout(out):
            status = guard.main(argv, root=self.root, environment={})
        return status, out.getvalue()


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fixture = Fixture(Path(self.tmp.name))
        self.fixture.run('--write-baseline')

    def tearDown(self):
        self.tmp.cleanup()

    def baselined(self) -> set[str]:
        return set(json.loads((self.fixture.root / guard.BASELINE).read_text())['areas']['net'])

    def test_the_first_baseline_holds_every_definition_and_then_passes(self):
        self.assertEqual(self.baselined(), {'object net.KeepAlive', 'enum net.KeepAlive.State',
                                            'def net.KeepAlive.transition', 'def net.KeepAlive.helper'})
        status, out = self.fixture.run()
        self.assertEqual(status, 0, out)
        self.assertIn('net: 4 definitions: 0 mapped, 0 glue, 4 baselined', out)
        self.assertIn('upstream symbols not checked', out)

    def test_a_new_definition_is_refused_at_once(self):
        self.fixture.source(CODE + '\n  def added(n: Int): Int = n\n')
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn('def net.KeepAlive.added', out)
        status, out = self.fixture.run('--write-baseline')
        self.assertEqual(status, 1, 'writing the baseline never admits a new key')

    def test_replacing_a_baselined_definition_in_the_same_file_is_refused(self):
        # a count per file would let this pass: one definition removed, one added
        replaced = CODE.replace('def helper(n: Int): Int = n', 'def renamed(n: Int): Int = n')
        before = {SOURCE: [1] * 4}
        after = {SOURCE: [1] * len(scala_definitions.read(replaced).definitions)}
        self.assertEqual(shrinking_baseline.compare({k: len(v) for k, v in after.items()},
                                                    {k: len(v) for k, v in before.items()}), ([], []))
        self.fixture.source(replaced)
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn('def net.KeepAlive.renamed', out)

    def test_mapping_a_definition_shrinks_the_baseline(self):
        self.fixture.area({'enum net.KeepAlive.State': self.fixture.entry('enum net.KeepAlive.State')}, [])
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn('fewer unmapped definitions', out)
        self.assertEqual(self.fixture.run('--write-baseline')[0], 0)
        self.assertNotIn('enum net.KeepAlive.State', self.baselined())
        status, out = self.fixture.run()
        self.assertEqual(status, 0, out)
        self.assertIn('1 mapped', out)

    def test_glue_needs_a_reason_and_a_definition(self):
        self.fixture.area({}, [{'reason': 'local helper', 'definitions': ['def net.KeepAlive.helper']}])
        self.fixture.run('--write-baseline')
        self.assertEqual(self.fixture.run()[0], 0)
        self.fixture.area({}, [{'reason': ' ', 'definitions': ['def net.KeepAlive.helper']}])
        self.assertIn('nonempty "reason"', self.fixture.run()[1])
        self.fixture.area({}, [{'reason': 'gone', 'definitions': ['def net.KeepAlive.gone']}])
        self.assertIn('is not a definition of the area', self.fixture.run()[1])

    def test_an_entry_and_glue_for_one_definition_is_refused(self):
        key = 'def net.KeepAlive.helper'
        self.fixture.area({key: self.fixture.entry(key)}, [{'reason': 'x', 'definitions': [key]}])
        self.fixture.run('--write-baseline')
        self.assertIn('both an entry and glue', self.fixture.run()[1])

    def test_a_changed_definition_needs_its_entry_updated_and_a_comment_edit_does_not(self):
        key = 'def net.KeepAlive.transition'
        self.fixture.area({key: self.fixture.entry(key)}, [])
        self.fixture.run('--write-baseline')
        self.assertEqual(self.fixture.run()[0], 0)
        self.fixture.source(CODE.replace('/** The states. */', '/** The three states. */'))
        self.assertEqual(self.fixture.run()[0], 0)
        self.fixture.source(CODE.replace('case _            => State.Client', 'case _            => State.Done'))
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn(f'{key}: its code changed and its entry did not', out)
        self.assertIn(self.fixture.digest(key), out)

    def test_an_unregistered_revision_is_refused(self):
        key = 'enum net.KeepAlive.State'
        entry = self.fixture.entry(key)
        entry['upstream'][0]['revision'] = 'b' * 40
        self.fixture.area({key: entry}, [])
        self.fixture.run('--write-baseline')
        self.assertIn('is not a revision pinned', self.fixture.run()[1])

    def test_classes_records_structure_and_evidence(self):
        key = 'enum net.KeepAlive.State'
        cases = [
            ({'class': 'Same'}, '"class" is one of'),
            ({'structure': 'similar'}, '"structure" is "same" or "differs"'),
            ({'class': 'Deviation'}, 'a Deviation links its record'),
            ({'class': 'Improvement', 'record': 'docs/missing.md'}, 'neither an https link nor a file'),
            ({'class': 'Gap', 'record': 'https://github.com/KD5UJC-XYZ/scarab/pull/1'}, "a Gap's record is its issue"),
            ({'structure': 'differs'}, 'names the suites that compare'),
            ({'structure': 'differs', 'evidence': ['MissingSuite']}, 'MissingSuite is not defined'),
            ({'bogus': 1}, 'unknown fields bogus'),
        ]
        self.fixture.area({key: self.fixture.entry(key)}, [])
        self.assertEqual(self.fixture.run('--write-baseline')[0], 0)
        for fields, message in cases:
            with self.subTest(fields=fields):
                self.fixture.area({key: self.fixture.entry(key, **fields)}, [])
                status, out = self.fixture.run()
                self.assertEqual(status, 1, out)
                self.assertIn(message, out)
        for fields in ({'structure': 'same', 'evidence': ['KeepAliveSuite']},
                       {'class': 'Gap', 'record': 'https://github.com/KD5UJC-XYZ/scarab/issues/932'},
                       {'class': 'Gap', 'structure': 'differs', 'record': 'https://github.com/KD5UJC-XYZ/scarab/issues/9'},
                       {'class': 'Deviation', 'record': 'docs/data/reference-pins.json'}):
            with self.subTest(fields=fields):
                self.fixture.area({key: self.fixture.entry(key, **fields)}, [])
                status, out = self.fixture.run()
                self.assertEqual(status, 0, out)

    def test_a_differs_entrys_evidence_suite_needs_a_comparison_contract_against_the_reference(self):
        key = 'enum net.KeepAlive.State'
        self.fixture.area({key: self.fixture.entry(key)}, [])
        self.assertEqual(self.fixture.run('--write-baseline')[0], 0)
        self.fixture.area({key: self.fixture.entry(key, structure='differs', evidence=['KeepAliveSuite'])}, [])
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn(f'{key}: evidence suite KeepAliveSuite has no comparison contract in {guard.REGISTER}', out)
        self.assertIn(SUITE_FILE, out, 'the refusal names the file to register')
        contract = self.fixture.contract
        passing = [
            ('a corpus', [contract([SUITE_FILE, CORPUS_FILE])]),
            ('an oracle', [contract([SUITE_FILE, 'scripts/oracle/keep-alive/Main.hs'])]),
            ('a running node', [contract([SUITE_FILE, 'scripts/devnet/scenarios/keepalive.py'])]),
            ('an acceptance receipt', [contract([SUITE_FILE, 'docs/data/external-acceptance-294.json'])]),
            ('a bounded contract', [contract([SUITE_FILE, CORPUS_FILE], status='bounded')]),
            ('a reference non-result', [contract([SUITE_FILE, CORPUS_FILE], classification='reference-non-result')]),
            ('a known divergence', [contract([SUITE_FILE, CORPUS_FILE], classification='known-divergence')]),
            ('one of two entries', [contract([SUITE_FILE], id='plain', classification='regression'),
                                    contract([SUITE_FILE, CORPUS_FILE])]),
        ]
        for name, rows in passing:
            with self.subTest(contract=name):
                self.fixture.register(rows)
                status, out = self.fixture.run()
                self.assertEqual(status, 0, out)
        failing = [
            ('no reference source', [contract([SUITE_FILE])]),
            ('a file outside any corpus', [contract([SUITE_FILE, 'docs/notes.md'])]),
            ('an undocumented corpus', [contract([SUITE_FILE, 'fixtures/reference/keep-alive-undocumented/a.json'])]),
            ('another suite\'s contract', [contract(['net/src/test/scala/OtherSuite.scala', CORPUS_FILE])]),
            ('an open contract', [contract([SUITE_FILE, CORPUS_FILE], status='open')]),
            ('a pending contract', [contract([SUITE_FILE, CORPUS_FILE], status='pending-integration')]),
            ('a regression contract', [contract([SUITE_FILE, CORPUS_FILE], classification='regression')]),
            ('an input-boundary contract', [contract([SUITE_FILE, CORPUS_FILE], classification='input-boundary')]),
        ]
        undocumented = self.fixture.root / 'fixtures/reference/keep-alive-undocumented/a.json'
        undocumented.parent.mkdir(parents=True)
        undocumented.write_text('{}\n')
        for name, rows in failing:
            with self.subTest(contract=name):
                self.fixture.register(rows)
                status, out = self.fixture.run()
                self.assertEqual(status, 1, out)
                self.assertIn('KeepAliveSuite has no comparison contract', out)

    def test_the_contract_rule_binds_every_class_of_differs_entry_and_no_same_one(self):
        key = 'enum net.KeepAlive.State'
        issue = 'https://github.com/KD5UJC-XYZ/scarab/issues/9'
        self.fixture.area({key: self.fixture.entry(key, structure='same', evidence=['KeepAliveSuite'])}, [])
        self.assertEqual(self.fixture.run('--write-baseline')[0], 0)
        for fields in ({'class': 'Gap', 'record': issue}, {'class': 'Deviation', 'record': issue},
                       {'class': 'Improvement', 'record': issue}):
            with self.subTest(fields=fields):
                entry = self.fixture.entry(key, structure='differs', evidence=['KeepAliveSuite'], **fields)
                self.fixture.area({key: entry}, [])
                status, out = self.fixture.run()
                self.assertEqual(status, 1, out)
                self.assertIn('KeepAliveSuite has no comparison contract', out)
        self.fixture.area({key: self.fixture.entry(key, structure='differs', **{'class': 'Gap', 'record': issue})}, [])
        self.assertEqual(self.fixture.run()[0], 0, 'a Gap that names no evidence needs no contract')

    def test_a_differing_deviation_with_no_comparing_suite_links_the_issue_of_its_gap(self):
        key = 'enum net.KeepAlive.State'
        decision = 'docs/data/reference-pins.json'
        issue = 'https://github.com/KD5UJC-XYZ/scarab/issues/1263#issuecomment-1'
        self.fixture.area({key: self.fixture.entry(key)}, [])
        self.assertEqual(self.fixture.run('--write-baseline')[0], 0)
        deviation = {'class': 'Deviation', 'structure': 'differs', 'record': decision}
        passing = [
            ('the gap linked', {**deviation, 'gap': issue}),
            ('the gap linked beside a registered suite', {**deviation, 'gap': issue, 'evidence': ['KeepAliveSuite']}),
        ]
        self.fixture.register([self.fixture.contract([SUITE_FILE, CORPUS_FILE])])
        for name, fields in passing:
            with self.subTest(case=name):
                self.fixture.area({key: self.fixture.entry(key, **fields)}, [])
                status, out = self.fixture.run()
                self.assertEqual(status, 0, out)
        self.fixture.register([])
        failing = [
            ('no suite and no gap', deviation, 'or, for a Deviation, the issue of the missing comparison ("gap")'),
            ('an unregistered suite beside the gap', {**deviation, 'gap': issue, 'evidence': ['KeepAliveSuite']},
             'KeepAliveSuite has no comparison contract'),
            ('a gap that is no issue', {**deviation, 'gap': 'docs/decisions.md'},
             '"gap" is the issue of the missing comparison (https://'),
            ('a gap on a Mirror', {'structure': 'differs', 'gap': issue},
             '"gap" is the issue of the missing comparison of a Deviation whose structure differs'),
            ('a gap on a Gap', {'class': 'Gap', 'structure': 'differs', 'record': issue, 'gap': issue},
             '"gap" is the issue of the missing comparison of a Deviation whose structure differs'),
            ('a gap on a Deviation of the same structure', {**deviation, 'structure': 'same', 'gap': issue},
             '"gap" is the issue of the missing comparison of a Deviation whose structure differs'),
        ]
        for name, fields, message in failing:
            with self.subTest(case=name):
                self.fixture.area({key: self.fixture.entry(key, **fields)}, [])
                status, out = self.fixture.run()
                self.assertEqual(status, 1, out)
                self.assertIn(message, out)

    def test_a_suite_defined_twice_needs_one_of_its_files_registered(self):
        key = 'enum net.KeepAlive.State'
        other = 'net/src/test/scala/more/KeepAliveSuite.scala'
        (self.fixture.root / other).parent.mkdir(parents=True)
        (self.fixture.root / other).write_text('class KeepAliveSuite extends munit.FunSuite\n')
        self.fixture.area({key: self.fixture.entry(key)}, [])
        self.fixture.run('--write-baseline')
        self.fixture.area({key: self.fixture.entry(key, structure='differs', evidence=['KeepAliveSuite'])}, [])
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn(f'names {SUITE_FILE} or {other}', out)
        self.fixture.register([self.fixture.contract([other, CORPUS_FILE])])
        self.assertEqual(self.fixture.run()[0], 0)

    def test_a_missing_register_fails_the_check(self):
        (self.fixture.root / guard.REGISTER).unlink()
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn(f'{guard.REGISTER} is missing', out)

    def test_upstream_symbols_are_checked_in_a_clone_at_the_revision(self):
        sources = Path(self.tmp.name) / 'sources'
        clone = sources / 'ouroboros-network'
        (clone / Path(UPSTREAM_FILE).parent).mkdir(parents=True)
        git(sources, 'init', '-q', str(clone))
        (clone / UPSTREAM_FILE).write_text(UPSTREAM)
        git(clone, 'add', '.')
        git(clone, 'commit', '-q', '-m', 'upstream')
        revision = git(clone, 'rev-parse', 'HEAD').strip()
        # the working tree is not what is read: the revision is
        (clone / UPSTREAM_FILE).write_text('nothing\n')
        self.fixture.revision = revision
        self.fixture.write_pins([revision, 'c' * 40])
        key = 'enum net.KeepAlive.State'
        cases = [
            (self.fixture.upstream(), None),
            (self.fixture.upstream(symbol='StReady'), 'StReady does not occur in'),
            (self.fixture.upstream(lines=(3, 60)), 'fewer than 60'),
            ([dict(self.fixture.upstream()[0], file='Missing.hs')], 'Missing.hs does not exist at'),
        ]
        for upstream, message in cases:
            with self.subTest(upstream=upstream):
                self.fixture.area({key: self.fixture.entry(key, upstream=upstream)}, [])
                self.fixture.run('--write-baseline')
                status, out = self.fixture.run(sources=sources)
                if message is None:
                    self.assertEqual(status, 0, out)
                    self.assertNotIn('upstream symbols not checked', out)
                else:
                    self.assertEqual(status, 1, out)
                    self.assertIn(message, out)
        self.fixture.area({key: self.fixture.entry(key, upstream=[dict(self.fixture.upstream()[0],
                                                                         revision='c' * 40)])}, [])
        status, out = self.fixture.run(sources=sources)
        self.assertEqual(status, 1, 'clones that cannot check an entry fail the check')
        self.assertIn(f'cannot check {REPOSITORY} cccccccc', out)
        status, out = self.fixture.run(sources=Path(self.tmp.name) / 'nowhere')
        self.assertEqual(status, 1)
        self.assertIn('no clone of it', out)

    def test_a_baseline_hint_is_checked_like_an_entry(self):
        path = self.fixture.root / guard.BASELINE
        body = json.loads(path.read_text())
        hint = self.fixture.upstream()
        hint[0]['revision'] = 'd' * 40
        body['areas']['net']['enum net.KeepAlive.State'] = hint
        path.write_text(json.dumps(body))
        self.assertIn('(baseline hint): IntersectMBO/ouroboros-network dddd', self.fixture.run()[1])

    def test_a_line_no_definition_holds_is_refused(self):
        self.fixture.source(CODE + 'strange at the top level\n')
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn(f'{SOURCE}:13: no definition holds this line', out)

    def test_a_key_added_to_the_baseline_since_the_merge_base_is_refused(self):
        root = self.fixture.root
        git(root, 'init', '-q')
        git(root, 'add', '.')
        git(root, 'commit', '-q', '-m', 'base')
        git(root, 'update-ref', 'refs/remotes/origin/main', 'HEAD')
        self.fixture.source(CODE + '\n  def sneaked(n: Int): Int = n\n')
        path = root / guard.BASELINE
        body = json.loads(path.read_text())
        body['areas']['net']['def net.KeepAlive.sneaked'] = None
        path.write_text(json.dumps(body))
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn('keys added to', out)
        self.assertIn('def net.KeepAlive.sneaked', out)

    def commit_base(self) -> None:
        root = self.fixture.root
        git(root, 'init', '-q')
        git(root, 'add', '.')
        git(root, 'commit', '-q', '-m', 'base')

    def sneak(self) -> None:
        self.fixture.source(CODE + '\n  def sneaked(n: Int): Int = n\n')
        path = self.fixture.root / guard.BASELINE
        body = json.loads(path.read_text())
        body['areas']['net']['def net.KeepAlive.sneaked'] = None
        path.write_text(json.dumps(body))

    def test_writing_the_baseline_checks_its_growth_first(self):
        self.commit_base()
        git(self.fixture.root, 'update-ref', 'refs/remotes/origin/main', 'HEAD')
        self.sneak()
        status, out = self.fixture.run('--write-baseline')
        self.assertEqual(status, 1, out)
        self.assertIn('def net.KeepAlive.sneaked', out)

    def test_a_pull_request_in_ci_compares_with_its_target_and_needs_it(self):
        ci = {'GITHUB_ACTIONS': 'true', 'GITHUB_EVENT_NAME': 'pull_request'}
        self.commit_base()
        outcome = guard.check(self.fixture.root, None, False, ci)
        self.assertTrue(any('target is not in this checkout' in p for p in outcome.problems), outcome.problems)
        self.sneak()
        git(self.fixture.root, 'add', '.')
        git(self.fixture.root, 'commit', '-q', '-m', 'change')
        outcome = guard.check(self.fixture.root, None, False, ci)
        self.assertTrue(any('def net.KeepAlive.sneaked' in p for p in outcome.problems), outcome.problems)
        self.assertEqual(guard.check(self.fixture.root, None, False, {}).problems, [],
                         'outside CI, with no origin/main, the growth check does not run')

    def test_the_first_baseline_is_written_only_when_the_rest_passes_and_never_again(self):
        path = self.fixture.root / guard.BASELINE
        path.unlink()
        key = 'enum net.KeepAlive.State'
        self.fixture.area({key: self.fixture.entry(key, **{'class': 'Same'})}, [])
        status, out = self.fixture.run('--write-baseline')
        self.assertEqual(status, 1, out)
        self.assertFalse(path.exists(), 'a first baseline is not written while the check fails')
        self.fixture.area({}, [])
        self.assertEqual(self.fixture.run('--write-baseline')[0], 0)
        self.commit_base()
        git(self.fixture.root, 'update-ref', 'refs/remotes/origin/main', 'HEAD')
        path.unlink()
        status, out = self.fixture.run('--write-baseline')
        self.assertEqual(status, 1, out)
        self.assertIn('its base has one', out)
        self.assertFalse(path.exists())

    def test_show_names_the_file_lines_digest_and_listing(self):
        status, out = self.fixture.run('--show', 'def net.KeepAlive.transition')
        self.assertEqual(status, 0)
        self.assertIn(f'{SOURCE}:8-10', out)
        self.assertIn('baseline', out)
        self.assertEqual(self.fixture.run('--show', 'def net.Nothing')[0], 1)


if __name__ == '__main__':
    unittest.main()
