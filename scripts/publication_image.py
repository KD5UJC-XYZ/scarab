# SPDX-License-Identifier: Apache-2.0
"""Bind actual Docker runtime identities to the complete verified OCI contents."""
from pathlib import Path
import subprocess
import tempfile

import candidate_oci
from publication_common import canonical, decode_json, require, safe_path


INSPECT = ('{"Id":{{json .Id}},"Architecture":{{json .Architecture}},"Os":{{json .Os}},'
           '"RootFS":{{json .RootFS}},"Config":{"User":{{json .Config.User}},'
           '"Entrypoint":{{json .Config.Entrypoint}},"WorkingDir":{{json .Config.WorkingDir}},'
           '"Labels":{{json .Config.Labels}}},"Descriptor":{{json .Descriptor}},"RepoDigests":{{json .RepoDigests}}}')


def expected_image(root, manifest):
    jar = next(row for row in manifest['subjects'] if row['kind'] == 'jar')
    image = next(row for row in manifest['subjects'] if row['kind'] == 'oci-archive')
    return candidate_oci.verify(safe_path(root, image['file']['path']), safe_path(root, jar['file']['path']),
                                manifest['source']['buildIdentity'], manifest['image']['manifest']['digest'])


def inspect_image(reference, environment=None):
    result = subprocess.run(['docker', 'image', 'inspect', '--format', INSPECT, reference], check=True,
                            env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    value = decode_json(result.stdout)
    if value.get('Descriptor') is None:
        value.pop('Descriptor', None)
    return value


def bind_image(reference, expected, output=None, environment=None):
    """Export public IMAGE contents only; never export a container filesystem or request Env."""
    inspected = inspect_image(reference, environment)
    with tempfile.TemporaryDirectory(prefix='scarab-public-image-graph-', dir=output.parent if output else None) as temporary:
        archive = Path(temporary) / 'image.tar'
        subprocess.run(['docker', 'image', 'save', '--output', str(archive), reference], check=True,
                       env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        require(archive.stat().st_size < 2 * 1024 ** 3, 'runtime image export exceeds publication transport budget')
        binding = candidate_oci.bind_docker_image(archive, inspected, expected)
    if output:
        require(not output.exists() and not output.is_symlink(), 'runtime binding receipt must be fresh')
        output.write_bytes(canonical(binding) + b'\n')
    return binding, inspected
