# SPDX-License-Identifier: Apache-2.0
"""check_correspondence: every definition of a mirrored area is mapped, glue or baselined, and every entry is
current, complete and true at its pin where a clone can show it; each key is stored in its source's map file; and
migrate_correspondence: the earlier layout converts to the same map, and a change made in it is carried over."""
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
import migrate_correspondence as migrate  # noqa: E402
import scala_definitions  # noqa: E402
import shrinking_baseline  # noqa: E402

REPOSITORY = 'IntersectMBO/ouroboros-network'
SOURCE = 'net/src/main/scala/xyz/kd5ujc/scarab/net/KeepAlive.scala'
MAP_FILE = 'docs/data/correspondence/net/net/KeepAlive.json'
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


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(['git', '-C', str(cwd), *args], check=True, capture_output=True, text=True,
                          env={'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@t', 'GIT_COMMITTER_NAME': 't',
                               'GIT_COMMITTER_EMAIL': 't@t', 'HOME': str(cwd), 'PATH': '/usr/bin:/bin'}).stdout


class Fixture:
    """A repository with one area (`net`), one source, its map file, the pins and a test suite."""

    def __init__(self, tmp: Path, revision: str = 'a' * 40):
        self.root = tmp / 'repo'
        self.revision = revision
        (self.root / 'net/src/main/scala/xyz/kd5ujc/scarab/net').mkdir(parents=True)
        (self.root / 'net/src/test/scala').mkdir(parents=True)
        (self.root / 'docs/data/correspondence/net').mkdir(parents=True)
        (self.root / guard.MAP / 'net' / guard.AREA_FILE).write_text(
            guard.render({'area': 'net', 'roots': ['net/src/main/scala']}))
        (self.root / 'net/src/test/scala/KeepAliveSuite.scala').write_text('class KeepAliveSuite extends munit.FunSuite\n')
        self.write_pins([revision])
        self.source(CODE)
        self.area({}, [])

    def write_pins(self, revisions):
        pins = [{'name': 'ouroboros-network', 'repository': REPOSITORY, 'revision': r, 'role': 'x'} for r in revisions]
        (self.root / guard.PINS).write_text(json.dumps({'schemaVersion': 1, 'pins': pins}))

    def source(self, text: str) -> None:
        (self.root / SOURCE).write_text(text)

    def document(self) -> dict:
        path = self.root / MAP_FILE
        return json.loads(path.read_text()) if path.is_file() else {'source': SOURCE}

    def write(self, body: dict) -> None:
        """The map file in its one form, or none when it would list nothing."""
        path = self.root / MAP_FILE
        if set(body) == {'source'}:
            path.unlink(missing_ok=True)
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(guard.render(body))

    def area(self, entries: dict, glue: list) -> None:
        """The source's entries and glue, its baseline kept."""
        body = {k: v for k, v in self.document().items() if k not in ('entries', 'glue')}
        body.update({k: v for k, v in (('entries', entries), ('glue', glue)) if v})
        self.write(body)

    def baseline(self) -> dict:
        return self.document().get('baseline', {})

    def set_baseline(self, key: str, hint) -> None:
        body = self.document()
        body.setdefault('baseline', {})[key] = hint
        self.write(body)

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
        return set(self.fixture.baseline())

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
        for fields in ({'structure': 'differs', 'evidence': ['KeepAliveSuite']},
                       {'class': 'Gap', 'record': 'https://github.com/KD5UJC-XYZ/scarab/issues/932'},
                       {'class': 'Gap', 'structure': 'differs', 'record': 'https://github.com/KD5UJC-XYZ/scarab/issues/9'},
                       {'class': 'Deviation', 'record': 'docs/data/reference-pins.json'}):
            with self.subTest(fields=fields):
                self.fixture.area({key: self.fixture.entry(key, **fields)}, [])
                status, out = self.fixture.run()
                self.assertEqual(status, 0, out)

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
        hint = self.fixture.upstream()
        hint[0]['revision'] = 'd' * 40
        self.fixture.set_baseline('enum net.KeepAlive.State', hint)
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
        self.sneak()
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
        self.fixture.set_baseline('def net.KeepAlive.sneaked', None)

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

    def forget_baseline(self) -> None:
        (self.fixture.root / guard.BASELINE).unlink()
        self.fixture.write({k: v for k, v in self.fixture.document().items() if k != 'baseline'})

    def test_baseline_keys_without_its_record_are_refused(self):
        (self.fixture.root / guard.BASELINE).unlink()
        status, out = self.fixture.run('--write-baseline')
        self.assertEqual(status, 1, out)
        self.assertIn('yet map files list baseline keys', out)

    def test_the_first_baseline_is_written_only_when_the_rest_passes_and_never_again(self):
        path = self.fixture.root / guard.BASELINE
        self.forget_baseline()
        key = 'enum net.KeepAlive.State'
        self.fixture.area({key: self.fixture.entry(key, **{'class': 'Same'})}, [])
        status, out = self.fixture.run('--write-baseline')
        self.assertEqual(status, 1, out)
        self.assertFalse(path.exists(), 'a first baseline is not written while the check fails')
        self.fixture.area({}, [])
        self.assertEqual(self.fixture.run('--write-baseline')[0], 0)
        self.commit_base()
        git(self.fixture.root, 'update-ref', 'refs/remotes/origin/main', 'HEAD')
        self.forget_baseline()
        status, out = self.fixture.run('--write-baseline')
        self.assertEqual(status, 1, out)
        self.assertIn('its base has one', out)
        self.assertFalse(path.exists())

    def test_show_names_the_file_lines_digest_and_listing(self):
        status, out = self.fixture.run('--show', 'def net.KeepAlive.transition')
        self.assertEqual(status, 0)
        self.assertIn(f'{SOURCE}:8-10', out)
        self.assertIn('baseline', out)
        self.assertIn(f'map file {MAP_FILE}', out)
        self.assertEqual(self.fixture.run('--show', 'def net.Nothing')[0], 1)



class LayoutTest(unittest.TestCase):
    """Each key in its source's map file, every file in its one form, and the earlier layout only in a base."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fixture = Fixture(Path(self.tmp.name))
        self.fixture.run('--write-baseline')
        self.key = 'enum net.KeepAlive.State'
        self.fixture.area({self.key: self.fixture.entry(self.key)}, [])
        self.fixture.run('--write-baseline')
        self.assertEqual(self.fixture.run()[0], 0)

    def tearDown(self):
        self.tmp.cleanup()

    def model(self) -> dict:
        return guard.read_tree(guard.disk_files(self.fixture.root)).model()

    def test_a_moved_source_moves_its_keys_with_tidy_and_nothing_else_changes(self):
        root, before = self.fixture.root, self.model()
        moved = root / 'net/src/main/scala/xyz/kd5ujc/scarab/net/ka/KeepAlive.scala'
        moved.parent.mkdir()
        (root / SOURCE).rename(moved)
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn(f'{self.key}: listed in {MAP_FILE}, but defined in', out)
        self.assertEqual(self.fixture.run('--tidy')[0], 0)
        self.assertFalse((root / MAP_FILE).exists(), 'the map file of a source that has none is removed')
        new = root / 'docs/data/correspondence/net/net/ka/KeepAlive.json'
        self.assertEqual(json.loads(new.read_text())['source'], moved.relative_to(root).as_posix())
        self.assertEqual(self.model(), {'net': dict(before['net'])})
        status, out = self.fixture.run()
        self.assertEqual(status, 0, out)

    def test_a_file_out_of_its_form_or_empty_is_refused_and_tidy_corrects_it(self):
        path = self.fixture.root / MAP_FILE
        path.write_text(json.dumps(json.loads(path.read_text())))
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn(f'{MAP_FILE}: not in the one form', out)
        self.assertEqual(self.fixture.run('--tidy')[0], 0)
        self.assertEqual(self.fixture.run()[0], 0)
        empty = self.fixture.root / 'docs/data/correspondence/net/net/Gone.json'
        empty.write_text(guard.render({'source': 'net/src/main/scala/xyz/kd5ujc/scarab/net/Gone.scala'}))
        self.assertIn('lists no definition', self.fixture.run()[1])
        self.assertEqual(self.fixture.run('--tidy')[0], 0)
        self.assertFalse(empty.exists())
        self.assertEqual(self.fixture.run()[0], 0)

    def test_a_key_given_twice_or_in_two_files_is_refused(self):
        path = self.fixture.root / MAP_FILE
        text = path.read_text()
        path.write_text(text.replace('{\n', '{\n "source": "x",\n', 1))
        self.assertIn('is given twice', self.fixture.run()[1])
        path.write_text(text)
        other = self.fixture.root / 'docs/data/correspondence/net/net/Other.json'
        other.write_text(guard.render({'source': 'net/src/main/scala/xyz/kd5ujc/scarab/net/Other.scala',
                                       'entries': {self.key: self.fixture.entry(self.key)}}))
        self.assertIn(f'{self.key}: listed in {MAP_FILE} and in', self.fixture.run()[1])

    def test_a_file_of_the_earlier_layout_is_refused(self):
        for path in (guard.FLAT_BASELINE, 'docs/data/correspondence/net.json'):
            with self.subTest(path=path):
                (self.fixture.root / path).write_text('{}\n')
                status, out = self.fixture.run()
                self.assertEqual(status, 1, out)
                self.assertIn(f'{path}: a file of the earlier layout', out)
                (self.fixture.root / path).unlink()

    def flatten(self) -> None:
        """The map in its earlier layout, from the same data."""
        root = self.fixture.root
        area = guard.read_tree(guard.disk_files(root)).areas[0]
        groups = guard.glue_groups(area.glue)
        (root / guard.MAP / 'net.json').write_text(json.dumps(
            {'area': 'net', 'roots': area.roots, 'entries': area.entries, 'glue': groups}, indent=1))
        (root / guard.FLAT_BASELINE).write_text(json.dumps({'_comment': 'x', 'areas': {'net': area.baseline}}))
        for path in sorted((root / guard.MAP / 'net').rglob('*.json'), reverse=True):
            path.unlink()
        (root / guard.BASELINE).unlink()

    def commit(self, message: str) -> str:
        root = self.fixture.root
        if not (root / '.git').exists():
            git(root, 'init', '-q')
        git(root, 'add', '-A')
        git(root, 'commit', '-q', '-m', message)
        return git(root, 'rev-parse', 'HEAD').strip()

    def migrate(self, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with redirect_stdout(out):
            status = migrate.main(list(args), root=self.fixture.root)
        return status, out.getvalue()

    def test_the_earlier_layout_converts_to_the_same_map_and_a_base_in_it_still_checks_growth(self):
        before = self.model()
        self.flatten()
        base = self.commit('earlier layout')
        git(self.fixture.root, 'update-ref', 'refs/remotes/origin/main', base)
        self.assertEqual(guard.read_flat(guard.revision_files(self.fixture.root, base)).model(), before)
        status, out = self.migrate('--convert')
        self.assertEqual(status, 0, out)
        self.assertIn('the two layouts hold the same map', out)
        self.assertEqual(self.model(), before)
        self.assertFalse((self.fixture.root / guard.FLAT_BASELINE).exists())
        status, out = self.fixture.run()
        self.assertEqual(status, 0, out)
        self.assertNotIn('baseline growth not checked', out)
        self.assertEqual(self.migrate('--verify', base)[0], 0)
        self.fixture.set_baseline('def net.KeepAlive.sneaked', None)
        self.fixture.source(CODE + '\n  def sneaked(n: Int): Int = n\n')
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn('keys added to the baseline since its base', out)
        status, out = self.migrate('--verify', base)
        self.assertEqual(status, 1, out)
        self.assertIn('net baseline: def net.KeepAlive.sneaked', out)

    def test_a_change_made_in_the_earlier_layout_is_carried_over_three_ways(self):
        root = self.fixture.root
        self.flatten()
        base = self.commit('earlier layout')
        mapped = 'def net.KeepAlive.transition'
        flat = json.loads((root / guard.MAP / 'net.json').read_text())
        old = json.loads((root / guard.FLAT_BASELINE).read_text())
        del old['areas']['net'][mapped]
        (root / guard.FLAT_BASELINE).write_text(json.dumps(old))
        heads = {}
        for note in ('', 'changed by the pull request'):
            git(root, 'checkout', '-q', '--detach', base)
            (root / guard.FLAT_BASELINE).write_text(json.dumps(old))
            flat['entries'][mapped] = self.fixture.entry(mapped, **({'note': note} if note else {}))
            (root / guard.MAP / 'net.json').write_text(json.dumps(flat))
            heads[note] = self.commit(f'a change in the earlier layout: {note}')
        git(root, 'checkout', '-q', '--detach', base)
        self.assertEqual(self.migrate('--convert')[0], 0)
        status, out = self.migrate('--port', base, heads[''])
        self.assertEqual(status, 0, out)
        self.assertIn(f'net entries: {mapped} (set)', out)
        self.assertIn(f'net baseline: {mapped} (removed)', out)
        self.assertEqual(self.fixture.document()['entries'][mapped], self.fixture.entry(mapped))
        self.assertNotIn(mapped, self.fixture.baseline())
        self.assertEqual(self.fixture.run()[0], 0)
        status, out = self.migrate('--port', base, heads[''])
        self.assertEqual(status, 0, out)
        self.assertIn('carried over 0 changes', out, 'a second port changes nothing')
        body = self.fixture.document()
        body['entries'][mapped] = self.fixture.entry(mapped, note='changed on the new base')
        self.fixture.write(body)
        text = (root / MAP_FILE).read_text()
        status, out = self.migrate('--port', base, heads['changed by the pull request'])
        self.assertEqual(status, 1, out)
        self.assertIn(f'net entries: {mapped}', out)
        self.assertEqual((root / MAP_FILE).read_text(), text, 'a conflict writes nothing')

    def test_a_missing_area_is_refused_and_tidy_does_not_forget_it(self):
        root = self.fixture.root
        record = json.loads((root / guard.BASELINE).read_text())
        self.assertEqual(record['areas'], ['net'])
        for path in sorted((root / guard.MAP / 'net').rglob('*'), reverse=True):
            path.unlink() if path.is_file() else path.rmdir()
        (root / guard.MAP / 'net').rmdir()
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn(f'{guard.MAP}/net: an area {guard.BASELINE} records, with no map', out)
        self.fixture.run('--tidy')
        self.assertEqual(json.loads((root / guard.BASELINE).read_text())['areas'], ['net'])

    def test_tidy_refuses_a_key_two_files_list_and_a_wrong_source_is_refused(self):
        other = self.fixture.root / 'docs/data/correspondence/net/net/Other.json'
        other.write_text(guard.render({'source': 'net/src/main/scala/xyz/kd5ujc/scarab/net/Other.scala',
                                       'entries': {self.key: self.fixture.entry(self.key, note='other')}}))
        status, out = self.fixture.run('--tidy')
        self.assertEqual(status, 1, out)
        self.assertIn('not tidied', out)
        self.assertTrue(other.exists())
        other.unlink()
        body = self.fixture.document()
        body['source'] = 'net/src/main/scala/net/KeepAlive.scala'
        self.fixture.write(body)
        status, out = self.fixture.run()
        self.assertEqual(status, 1, out)
        self.assertIn('names the source net/src/main/scala/net/KeepAlive.scala, but its key', out)

    def test_verify_and_port_refuse_a_map_that_does_not_read_and_port_a_key_in_two_sections(self):
        root = self.fixture.root
        self.flatten()
        base = self.commit('earlier layout')
        key = 'def net.KeepAlive.helper'
        flat = json.loads((root / guard.MAP / 'net.json').read_text())
        flat['entries'][key] = self.fixture.entry(key)
        (root / guard.MAP / 'net.json').write_text(json.dumps(flat))
        old = json.loads((root / guard.FLAT_BASELINE).read_text())
        del old['areas']['net'][key]
        (root / guard.FLAT_BASELINE).write_text(json.dumps(old))
        head = self.commit('an entry')
        (root / guard.FLAT_BASELINE).write_text('{"areas": {}, "areas": {}}')
        broken = self.commit('a baseline that does not read')
        git(root, 'checkout', '-q', '--detach', base)
        self.assertEqual(self.migrate('--convert')[0], 0)
        status, out = self.migrate('--port', base, broken)
        self.assertEqual(status, 1, out)
        self.assertIn('does not read cleanly', out)
        status, out = self.migrate('--verify', broken)
        self.assertEqual(status, 1, out)
        body = self.fixture.document()
        del body['baseline'][key]
        body['glue'] = [{'definitions': [key], 'reason': 'a helper'}]
        self.fixture.write(body)
        status, out = self.migrate('--port', base, head)
        self.assertEqual(status, 1, out)
        self.assertIn(f'net: {key} would be listed in two of entries, glue and baseline', out)


    def test_the_record_names_each_area_once_and_every_area(self):
        path = self.fixture.root / guard.BASELINE
        for names, message in (([], 'an area docs/data/correspondence/_baseline.json does not record'),
                               (['net', 'net'], 'each once')):
            with self.subTest(names=names):
                path.write_text(guard.render(guard.baseline_document(names)))
                status, out = self.fixture.run()
                self.assertEqual(status, 1, out)
                self.assertIn(message, out)
        self.assertEqual(self.fixture.run('--tidy')[0], 0)
        self.assertEqual(self.fixture.run()[0], 0)


if __name__ == '__main__':
    unittest.main()
