#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read installed OS and Java legal evidence from the actual candidate's exported filesystem."""
import hashlib
from pathlib import Path
from pathlib import PurePosixPath
import tarfile


def inspect(rootfs, legal_directory=None):
    with tarfile.open(rootfs) as archive:
        members = {m.name.lstrip('./'): m for m in archive.getmembers()}

        def read(name, seen=frozenset()):
            if name in seen or len(seen) > 40:
                raise ValueError('filesystem legal-evidence symlink loop')
            parts = PurePosixPath(name).parts
            for index in range(1, len(parts) + 1):
                prefix = '/'.join(parts[:index])
                member = members.get(prefix)
                if member and member.issym():
                    target = PurePosixPath(member.linkname)
                    if not target.is_absolute():
                        target = PurePosixPath(prefix).parent / target
                    normalized = []
                    for part in target.parts + parts[index:]:
                        if part == '..':
                            if not normalized:
                                raise ValueError('escaping legal-evidence symlink')
                            normalized.pop()
                        elif part not in {'/', '.'}:
                            normalized.append(part)
                    return read('/'.join(normalized), seen | {name})
            member = members.get(name)
            if member is None:
                return None
            if member.islnk():
                return read(member.linkname.lstrip('./'), seen | {name})
            if not member.isfile():
                raise ValueError('legal evidence is not a regular file')
            return archive.extractfile(member).read()

        status = read('var/lib/dpkg/status')
        if not status:
            raise ValueError('candidate runtime lacks installed-package database')
        packages = []
        reviews = []
        retained_legal = []

        def retain(name, raw):
            if legal_directory is not None:
                if PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts:
                    raise ValueError('unsafe retained legal-evidence path')
                path = Path(legal_directory) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
                retained_legal.append(dict(path=name, sha256=hashlib.sha256(raw).hexdigest(), size=len(raw)))
        for paragraph in status.decode().split('\n\n'):
            fields = dict(line.split(': ', 1) for line in paragraph.splitlines() if ': ' in line and not line.startswith(' '))
            if fields.get('Status') != 'install ok installed':
                continue
            name = fields['Package']
            copyright_path = 'usr/share/doc/' + name + '/copyright'
            copyright = read(copyright_path)
            if copyright:
                retain(copyright_path, copyright)
            packages.append(dict(name=name, version=fields['Version'], architecture=fields['Architecture'],
                                 copyrightPath=copyright_path,
                                 copyrightSha256=hashlib.sha256(copyright).hexdigest() if copyright else 'unavailable'))
            if not copyright:
                reviews.append(dict(component=name, reason='installed package has no retained copyright file'))
        java_release = read('opt/java/openjdk/release')
        legal = []
        for name, member in sorted(members.items()):
            if name.startswith('opt/java/openjdk/legal/') and member.isfile():
                raw = read(name)
                legal.append(dict(path=name, sha256=hashlib.sha256(raw).hexdigest()))
                retain(name, raw)
        if not packages or not java_release or not legal:
            raise ValueError('OS/JRE inventory or Java legal evidence is incomplete')
        return dict(packages=sorted(packages, key=lambda p: p['name']),
                    dpkgDatabaseSha256=hashlib.sha256(status).hexdigest(),
                    javaRelease=java_release.decode(), javaReleaseSha256=hashlib.sha256(java_release).hexdigest(),
                    javaLegalFiles=legal, retainedLegalFiles=retained_legal), reviews
