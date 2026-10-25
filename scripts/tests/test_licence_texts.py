# SPDX-License-Identifier: Apache-2.0
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import licence_texts as texts
from licence_texts import Finding, Problem

MIT = b'MIT License\nCopyright (c) 2015 Example\n'
BSD = b'BSD 3-Clause\nCopyright (c) 2016 Native\n'


def index():
    return {'schemaVersion': 1,
            'texts': {'lib-1/LICENSE': {'source': {'jar': 'g:lib:1', 'entry': 'LICENSE'}, 'sha256': texts.digest(MIT)},
                      'native-2/LICENSE': {'source': {'file': 'native/LICENSE-native'}, 'sha256': texts.digest(BSD)}},
            'components': {'g:lib:1': {'licence': 'MIT', 'texts': ['lib-1/LICENSE']}},
            'natives': [{'paths': ['natives/libx.so'], 'contents': 'x', 'texts': ['native-2/LICENSE']}]}


def coverage(components=('g:lib:1',), natives=('natives/libx.so',)):
    return {'components': [{'coordinate': c} for c in components], 'nativeResources': [{'path': n} for n in natives]}


def packaged(value):
    return {texts.PACKAGED_INDEX: json.dumps(value).encode(), texts.PACKAGED + 'lib-1/LICENSE': MIT,
            texts.PACKAGED + 'native-2/LICENSE': BSD}


class ArtifactTest(unittest.TestCase):
    def test_a_jar_with_the_texts_of_exactly_its_components_and_natives_passes(self):
        self.assertEqual(texts.verify_artifact(packaged(index()), coverage(), index()), [])

    def test_each_gap_is_its_own_finding(self):
        cases = {
            'no index': ({}, coverage(), [Finding(Problem.MISSING_INDEX, texts.PACKAGED_INDEX)]),
            'new dependency': (packaged(index()), coverage(components=('g:lib:1', 'g:new:2')),
                               [Finding(Problem.UNCOVERED_COMPONENT, 'g:new:2')]),
            'version bump': (packaged(index()), coverage(components=('g:lib:2',)),
                             [Finding(Problem.UNCOVERED_COMPONENT, 'g:lib:2'), Finding(Problem.STALE_COMPONENT, 'g:lib:1')]),
            'new native': (packaged(index()), coverage(natives=('natives/libx.so', 'natives/liby.so')),
                           [Finding(Problem.UNCOVERED_NATIVE, 'natives/liby.so')]),
            'removed native': (packaged(index()), coverage(natives=()), [Finding(Problem.STALE_NATIVE, 'natives/libx.so')]),
        }
        for name, (entries, resolved, expected) in cases.items():
            with self.subTest(name):
                self.assertEqual(texts.verify_artifact(entries, resolved), expected)

    def test_a_missing_or_altered_packaged_text_fails(self):
        missing = packaged(index())
        del missing[texts.PACKAGED + 'native-2/LICENSE']
        self.assertEqual(texts.verify_artifact(missing, coverage()),
                         [Finding(Problem.MISSING_TEXT, texts.PACKAGED + 'native-2/LICENSE')])
        altered = packaged(index())
        altered[texts.PACKAGED + 'lib-1/LICENSE'] = MIT + b' '
        self.assertEqual(texts.verify_artifact(altered, coverage()),
                         [Finding(Problem.CHANGED_TEXT, texts.PACKAGED + 'lib-1/LICENSE')])

    def test_a_packaged_index_that_is_not_the_repository_index_fails(self):
        other = copy.deepcopy(index())
        other['components']['g:lib:1']['licence'] = 'Apache-2.0'
        self.assertEqual(texts.verify_artifact(packaged(other), coverage(), index()),
                         [Finding(Problem.INDEX_DIFFERS, texts.PACKAGED_INDEX)])

    def test_a_component_naming_an_undefined_text_fails(self):
        broken = copy.deepcopy(index())
        broken['components']['g:lib:1']['texts'].append('lib-1/NOTICE')
        self.assertEqual(texts.verify_artifact(packaged(broken), coverage()),
                         [Finding(Problem.UNKNOWN_TEXT, 'g:lib:1 -> lib-1/NOTICE')])


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'licenses/lib-1').mkdir(parents=True)
        (self.root / 'licenses/lib-1/LICENSE').write_bytes(MIT)
        (self.root / 'native').mkdir()
        (self.root / 'native/LICENSE-native').write_bytes(BSD)
        (self.root / texts.INDEX).write_text(json.dumps(index()))

    def test_texts_are_read_from_licenses_or_from_their_own_repository_file(self):
        self.assertEqual(texts.repository_path('lib-1/LICENSE', index()['texts']['lib-1/LICENSE']), 'licenses/lib-1/LICENSE')
        self.assertEqual(texts.repository_path('native-2/LICENSE', index()['texts']['native-2/LICENSE']), 'native/LICENSE-native')
        self.assertEqual(texts.verify_repository(self.root), [])

    def test_a_missing_or_edited_repository_copy_fails(self):
        (self.root / 'licenses/lib-1/LICENSE').write_bytes(MIT.replace(b'2015', b'2016'))
        (self.root / 'native/LICENSE-native').unlink()
        self.assertEqual(texts.verify_repository(self.root), [
            Finding(Problem.CHANGED_TEXT, 'lib-1/LICENSE'),
            Finding(Problem.MISSING_TEXT, 'native-2/LICENSE (native/LICENSE-native)')])

    def test_the_checked_in_texts_have_their_recorded_digests(self):
        self.assertEqual(texts.verify_repository(texts.ROOT), [])

    def test_raw_file_urls_name_the_pinned_commit(self):
        github = {'git': 'https://github.com/o/r', 'commit': 'c' * 40, 'path': 'lib/LICENSE'}
        self.assertEqual(texts.raw_url(github), 'https://raw.githubusercontent.com/o/r/' + 'c' * 40 + '/lib/LICENSE')
        sourceware = {'git': 'https://sourceware.org/git/bzip2.git', 'commit': 'd' * 40, 'path': 'LICENSE'}
        self.assertEqual(texts.raw_url(sourceware),
                         'https://sourceware.org/git/?p=bzip2.git;a=blob_plain;f=LICENSE;hb=' + 'd' * 40)
        with self.assertRaises(ValueError):
            texts.raw_url({'git': 'https://example.org/r.git', 'commit': 'e', 'path': 'LICENSE'})


if __name__ == '__main__':
    unittest.main()
