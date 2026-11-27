#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The correspondence map's move from its earlier layout to one map file per source (#1515): the conversion, the
check that both layouts hold the same map, and the port of a pull request's map changes made in the earlier layout.

The earlier layout is one file per area, `docs/data/correspondence/<area>.json` (`roots`, `entries`, `glue`), and the
baseline `docs/data/correspondence-baseline.json`. `check_correspondence.read_flat` reads it and `read_tree` reads the
layout of one file per source; both give the same in-memory map (`Map.model`: per area its comment, roots, entries,
glue keys with their reasons and baseline keys with their hints).

    python3 scripts/migrate_correspondence.py --convert          the working tree's earlier layout to one map file
                                                                  per source, then the check that both hold the
                                                                  same map
    python3 scripts/migrate_correspondence.py --verify REV       the earlier layout at REV and the working tree's
                                                                  map hold the same map: the counts per area, and
                                                                  each difference
    python3 scripts/migrate_correspondence.py --port BASE HEAD   carry over what HEAD changed in the earlier layout
                                                                  since BASE onto the working tree's map

`--port` merges three ways, per key (an entry, a glue key with its reason, a baseline key with its hint, and an area's
comment and roots): what HEAD did not change stays as the working tree has it; what HEAD changed and the working tree
still has as BASE had it takes HEAD's value; what both changed differently is a conflict, and then nothing is
written. Each key goes to its definition's map file in the working tree, so run it after the sources are rebased.

This script, `read_flat` and the earlier layout's branch of `check_correspondence.base_baseline` go once no open pull
request's base has the earlier layout.

Pure standard library.
"""
from __future__ import annotations

from collections import Counter
from pathlib import Path
import argparse
import json
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_correspondence as guard  # noqa: E402

ROOT = guard.ROOT
SECTIONS = ('entries', 'glue', 'baseline')
ATTRIBUTES = ('comment', 'roots')


class Missing:
    """No value: the key is not in that version of the map (a baseline key's null hint is a value)."""

    def __repr__(self) -> str:
        return 'missing'


MISSING = Missing()


def same(a, b) -> bool:
    """Equal as JSON values: `true` is not `1` here, as it is to Python's `==`."""
    if a is MISSING or b is MISSING:
        return a is b
    return json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)


def counts(mapped: guard.Map) -> dict[str, dict[str, int]]:
    return {area.name: {'entries': len(area.entries), 'glue': len(area.glue), 'baseline': len(area.baseline),
                        'hinted': sum(1 for hint in area.baseline.values() if hint is not None),
                        'files': len(set(area.listed.values()))}
            for area in mapped.areas}


def differences(before: dict, after: dict) -> list[str]:
    """Each place where two models differ: an area, an area's comment or roots, or a key of a section."""
    out = []
    for name in sorted(set(before) | set(after)):
        if name not in before or name not in after:
            out.append(f'{name}: an area of only the {"new" if name in after else "earlier"} map')
            continue
        for attribute in ATTRIBUTES:
            if not same(before[name][attribute], after[name][attribute]):
                out.append(f'{name}: its {attribute} differ')
        for section in SECTIONS:
            was, now = before[name][section], after[name][section]
            out += [f'{name} {section}: {key}' for key in sorted(set(was) | set(now))
                    if not same(was.get(key, MISSING), now.get(key, MISSING))]
    return out


def report(label: str, mapped: guard.Map) -> None:
    for name, c in sorted(counts(mapped).items()):
        print(f'{label} {name}: {c["entries"]} entries, {c["glue"]} glue, {c["baseline"]} baseline '
              f'({c["hinted"]} with a hint), in {c["files"]} files')


def found_of(root: Path, mapped: guard.Map) -> dict[str, dict[str, guard.Found]]:
    return {area.name: {f.definition.key: f for f in guard.definitions(root, area)[0]} for area in mapped.areas}


def compare(before: guard.Map, after: guard.Map) -> int:
    """The same map in both, each read without a problem, and a baseline recorded in both or in neither."""
    report('earlier', before)
    report('now', after)
    for label, mapped in (('earlier', before), ('now', after)):
        if mapped.problems:
            print(f'the {label} map does not read cleanly:\n' + '\n'.join(mapped.problems))
            return 1
    found = differences(before.model(), after.model())
    if before.recorded != after.recorded:
        found.append(f'the baseline is recorded in the {"earlier" if before.recorded else "new"} map only')
    if found:
        print(f'the two layouts hold different maps, {len(found)} differences:')
        print('\n'.join(found))
        return 1
    print('the two layouts hold the same map')
    return 0


def convert(root: Path) -> int:
    files = guard.disk_files(root)
    if guard.layout(files) is not guard.Layout.FLAT:
        print(f'no map in the earlier layout here: the layout is {guard.layout(files).value}')
        return 1
    old = guard.read_flat(files)
    if old.problems:
        print('the earlier map does not read cleanly:\n' + '\n'.join(old.problems))
        return 1
    found = found_of(root, old)
    stale = [f'{area.name}: {key}' for area in old.areas for key in sorted(set(area.listed))
             if key not in found[area.name]]
    if stale:
        print('keys that name no current definition, so have no source to be stored with:\n' + '\n'.join(stale))
        return 1
    try:
        guard.write_tree(root, old, found, old.baseline())
    except ValueError as error:
        print(f'not converted: {error}')
        return 1
    flat = [guard.FLAT_BASELINE, *(f'{guard.MAP}/{area.name}.json' for area in old.areas)]
    new = guard.read_tree({k: v for k, v in guard.disk_files(root).items() if k not in flat})
    status = compare(old, new)
    if status == 0:
        for path in flat:
            (root / path).unlink(missing_ok=True)
    return status


def verify(root: Path, revision: str) -> int:
    files = guard.revision_files(root, revision)
    if guard.layout(files) is not guard.Layout.FLAT:
        print(f'{revision}: not the earlier layout: the layout is {guard.layout(files).value}')
        return 1
    return compare(guard.read_flat(files), guard.read_tree(guard.disk_files(root)))


def merged(what: str, base, head, ours, conflicts: list[str]):
    """The three-way merge of one value: `MISSING` where the result has none."""
    if same(head, base) or same(head, ours):
        return ours
    if same(ours, base):
        return head
    conflicts.append(what)
    return ours


def port(root: Path, base: str, head: str) -> int:
    earlier = {}
    for label, revision in (('BASE', base), ('HEAD', head)):
        files = guard.revision_files(root, revision)
        if guard.layout(files) is not guard.Layout.FLAT:
            print(f'{label} {revision}: not the earlier layout: the layout is {guard.layout(files).value}')
            return 1
        read = guard.read_flat(files)
        if not read.recorded:
            print(f'{label} {revision}: its map records no baseline')
            return 1
        if read.problems:
            print(f'{label} {revision}: its map does not read cleanly:\n' + '\n'.join(read.problems))
            return 1
        earlier[label] = {area.name: area for area in read.areas}
    tree = guard.read_tree(guard.disk_files(root))
    if tree.unread or tree.twice or not tree.recorded:
        print('the working tree\'s map does not read cleanly: files that cannot be read, keys two files list in one '
              'section, or no baseline recorded:\n' + '\n'.join(tree.unread + sorted(set(tree.twice))))
        return 1
    ours = {area.name: area for area in tree.areas}
    conflicts: list[str] = []
    changed: list[str] = []
    for name in sorted(set(earlier['BASE']) | set(earlier['HEAD'])):
        was, now = earlier['BASE'].get(name), earlier['HEAD'].get(name)
        if name not in ours or was is None or now is None:
            conflicts.append(f'{name}: an area added or removed on one side: carry it over by hand')
            continue
        area = ours[name]
        for attribute in ATTRIBUTES:
            value = merged(f'{name}: its {attribute}', getattr(was, attribute, MISSING),
                           getattr(now, attribute, MISSING), getattr(area, attribute), conflicts)
            if value != getattr(area, attribute):
                setattr(area, attribute, value)
                changed.append(f'{name}: its {attribute}')
        for section in SECTIONS:
            base_items, head_items = getattr(was, section, {}), getattr(now, section, {})
            items = getattr(area, section)
            for key in sorted(set(base_items) | set(head_items)):
                theirs = head_items.get(key, MISSING)
                value = merged(f'{name} {section}: {key}', base_items.get(key, MISSING), theirs,
                               items.get(key, MISSING), conflicts)
                if not same(value, items.get(key, MISSING)):
                    changed.append(f'{name} {section}: {key} ({"removed" if value is MISSING else "set"})')
                    if value is MISSING:
                        del items[key]
                    else:
                        items[key] = value
    for area in tree.areas:
        listed = Counter(k for s in SECTIONS for k in getattr(area, s))
        conflicts += sorted(f'{area.name}: {key} would be listed in two of entries, glue and baseline'
                            for key, times in listed.items() if times > 1)
    if conflicts:
        print('changed both by HEAD and in the working tree, so nothing is written:\n' + '\n'.join(conflicts))
        return 1
    try:
        guard.write_tree(root, tree, found_of(root, tree), tree.baseline())
    except ValueError as error:
        print(f'not written: {error}')
        return 1
    print(f'carried over {len(changed)} changes:' + ''.join(f'\n  {line}' for line in changed))
    print('now run python3 scripts/check_correspondence.py')
    return 0


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--convert', action='store_true', help='convert the working tree\'s earlier layout')
    mode.add_argument('--verify', metavar='REV', help='compare the earlier layout at REV with the working tree')
    mode.add_argument('--port', nargs=2, metavar=('BASE', 'HEAD'),
                      help='carry over what HEAD changed in the earlier layout since BASE')
    args = parser.parse_args(argv)
    if args.convert:
        return convert(root)
    if args.verify:
        return verify(root, args.verify)
    return port(root, *args.port)


if __name__ == '__main__':
    sys.exit(main())
