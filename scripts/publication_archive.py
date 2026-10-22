# SPDX-License-Identifier: Apache-2.0
"""Bounded whole-candidate transport; signature verification belongs before unpack()."""
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile

from publication_common import file_digest, require

MAX_BYTES = 12 * 1024 ** 3
MAX_FILE_BYTES = 8 * 1024 ** 3
MAX_FILES = 20000
MAX_ARCHIVE_BYTES = MAX_BYTES + MAX_FILES * 1024 + 10240
FORBIDDEN = {'.git', '.cache', '__pycache__', 'node_modules'}


def checked_name(root, name):
    # Archive members preserve package paths such as libstdc++6; they are not URL asset names.
    require(isinstance(name, str), 'artifact path must be text')
    relative = PurePosixPath(name)
    require(name and not relative.is_absolute() and all(part not in {'', '.', '..'} for part in name.split('/'))
            and all(re.fullmatch(r'[A-Za-z0-9_.+-]+', part) for part in relative.parts), 'unsafe artifact path')
    path = Path(root).joinpath(*relative.parts)
    require(not any(parent.is_symlink() for parent in [path, *path.parents]), 'symlink artifact path')
    require(not FORBIDDEN.intersection(Path(name).parts), 'candidate transport contains Git or cache files')
    return path


def pack(candidate_root, output_tar):
    """Freeze every regular candidate file, including nested independent proof inputs."""
    root, output = Path(candidate_root), Path(output_tar)
    require(root.is_dir() and not root.is_symlink(), 'candidate root must be a real directory')
    require(not output.resolve().is_relative_to(root.resolve()), 'archive must be outside candidate root')
    require(not output.exists() and not output.is_symlink(), 'archive output must be fresh')
    files, total = [], 0
    for directory, directories, names in os.walk(root, followlinks=False):
        for name in sorted(directories + names):
            path = Path(directory) / name
            checked_name(root, path.relative_to(root).as_posix())
            info = path.lstat()
            require(stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode), 'only regular candidate files and directories allowed')
            if stat.S_ISREG(info.st_mode):
                total += info.st_size
                files.append((path.relative_to(root).as_posix(), path, info))
                require(info.st_size <= MAX_FILE_BYTES and total <= MAX_BYTES and len(files) <= MAX_FILES,
                        'candidate transport exceeds extraction budget')
    require(files, 'candidate transport is empty')
    with output.open('xb') as destination, tarfile.open(fileobj=destination, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, path, original in sorted(files):
            with path.open('rb') as stream:
                before = os.fstat(stream.fileno())
                require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) ==
                        (original.st_dev, original.st_ino, original.st_size, original.st_mtime_ns), 'candidate changed while archiving')
                item = tarfile.TarInfo(name)
                item.size, item.mode = before.st_size, 0o644
                archive.addfile(item, stream)
                after = os.fstat(stream.fileno())
                require((after.st_size, after.st_mtime_ns) == (before.st_size, before.st_mtime_ns), 'candidate changed while archiving')
    require(output.stat().st_size <= MAX_ARCHIVE_BYTES, 'archive exceeds transport budget')
    return dict(path=str(output), sha256=file_digest(output), size=output.stat().st_size)


def unpack(archive, fresh_root):
    """Extract an already authenticated uncompressed USTAR archive into a new directory.

    Parse headers directly so extended metadata cannot bypass member/size accounting.
    This does not authenticate bytes or establish candidate validity; callers must also
    bind candidate.json to its expected hash and run the strict candidate validator.
    """
    source, root = Path(archive), Path(fresh_root)
    require(source.is_file() and not source.is_symlink() and source.stat().st_size <= MAX_ARCHIVE_BYTES
            and source.stat().st_size % 512 == 0,
            'invalid or oversized candidate archive')
    require(not root.exists() and not root.is_symlink(), 'extraction directory must be fresh')
    require(not any(p.is_symlink() for p in root.parents), 'symlink extraction parent')
    root.mkdir(parents=True)
    names, total = set(), 0
    with source.open('rb') as stream:
        while True:
            header = stream.read(512)
            require(len(header) == 512, 'truncated candidate archive header')
            if header == bytes(512):
                require(stream.read(512) == bytes(512), 'candidate archive needs two end blocks')
                tail = stream.read(10241)
                require(len(tail) <= 10240 and not tail.strip(b'\0') and names, 'invalid candidate archive trailer')
                return
            item = tarfile.TarInfo.frombuf(header, encoding='utf-8', errors='strict')
            require(header[257:263] == b'ustar\0' and item.type == tarfile.REGTYPE,
                    'only regular USTAR members allowed; metadata and links refused')
            path = checked_name(root, item.name)
            require(item.name not in names and not item.linkname and item.size >= 0, 'duplicate or invalid candidate member')
            names.add(item.name)
            total += item.size
            require(item.size <= MAX_FILE_BYTES and total <= MAX_BYTES and len(names) <= MAX_FILES,
                    'candidate transport exceeds extraction budget')
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as output:
                remaining = item.size
                while remaining:
                    chunk = stream.read(min(1024 * 1024, remaining))
                    require(chunk, 'truncated candidate member')
                    output.write(chunk)
                    remaining -= len(chunk)
            padding = stream.read((-item.size) % 512)
            require(len(padding) == (-item.size) % 512 and not padding.strip(b'\0'), 'invalid member padding')
