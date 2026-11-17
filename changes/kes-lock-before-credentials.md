### Fixed (forging)
- **The KES key lock (`--key-lock on`, the default) now holds across a KES rotation.** The lock file sat beside the
  key path as given, so with the rotation layout (`--kes-skey keys/current/kes.skey`) it was created inside the
  generation that `current` pointed at. After a rotation switched the link and `scripts/pool/rotate_kes.py`
  removed the old generation, the lock file was gone and a second instance on the same key paths found a fresh
  one and started. When the key's directory is a symbolic link, the lock file is now beside that link, in the
  directory that holds it, named after both (`keys/.current.kes.skey.scarab-lock`); without a link it stays
  `.kes.skey.scarab-lock` beside the key. The node now needs to be able to create the file there.
