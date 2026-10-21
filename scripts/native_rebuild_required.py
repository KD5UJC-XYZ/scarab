#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Keep the native CI check present; rebuild only when its exact inputs change or cannot be resolved."""
import json
import os
from pathlib import Path
import re
import subprocess

from check_native_reproducibility import INPUTS

ROOT = Path(__file__).resolve().parents[1]
NATIVE_PATHS = frozenset(INPUTS) | {
    '.github/workflows/native-reproducibility.yml',
    'scripts/native_rebuild_required.py',
    'scripts/tests/test_native_rebuild_required.py',
    'scripts/tests/test_native_reproducibility.py',
}
SHA = re.compile('[0-9a-f]{40}')


def rebuild_required(repo, event_name, event):
    if event_name == 'workflow_dispatch':
        return True, 'manual invocation always rebuilds'
    try:
        if event_name == 'pull_request':
            before = event['pull_request']['base']['sha']
            after = event['pull_request']['head']['sha']
        elif event_name == 'push':
            before, after = event['before'], event['after']
        else:
            return True, 'unsupported or missing event; rebuilding conservatively'
    except (KeyError, TypeError):
        return True, 'missing event range; rebuilding conservatively'
    if any(not isinstance(sha, str) or not SHA.fullmatch(sha) or sha == '0' * 40
           for sha in (before, after)):
        return True, 'invalid or all-zero event range; rebuilding conservatively'
    try:
        for sha in (before, after):
            resolved = subprocess.run(['git', 'cat-file', '-e', sha + '^{commit}'], cwd=repo,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
            if resolved.returncode:
                return True, 'event commit unavailable; rebuilding conservatively'
        if event_name == 'pull_request':
            common = subprocess.run(['git', 'merge-base', before, after], cwd=repo,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
            base = common.stdout.strip()
            if common.returncode or not re.fullmatch(b'[0-9a-f]{40}', base):
                return True, 'PR merge base unavailable; rebuilding conservatively'
            before = base.decode('ascii')
        changed = subprocess.run(['git', 'diff', '--no-ext-diff', '--no-textconv', '--no-renames',
                                  '--name-only', '-z', before, after, '--'], cwd=repo,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if changed.returncode:
            return True, 'Git range comparison failed; rebuilding conservatively'
    except OSError:
        return True, 'Git comparison unavailable; rebuilding conservatively'
    paths = {path for path in changed.stdout.split(b'\0') if path}
    if paths & {path.encode('utf-8') for path in NATIVE_PATHS}:
        return True, 'native build, verifier, test or workflow input changed'
    return False, 'no native input changed in the complete event range'


def main():
    event_name = os.environ.get('GITHUB_EVENT_NAME', '')
    try:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    except (KeyError, OSError, ValueError):
        event = {}
    required, reason = rebuild_required(ROOT, event_name, event)
    print(f'Native rebuild required: {str(required).lower()}; {reason}')
    output = os.environ.get('GITHUB_OUTPUT')
    if not output:
        raise ValueError('GITHUB_OUTPUT is required to publish the rebuild decision')
    with Path(output).open('a') as stream:
        stream.write(f'rebuild={str(required).lower()}\n')


if __name__ == '__main__':
    main()
