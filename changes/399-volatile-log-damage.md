### Fixed (volatile blocks, #399)

- A damaged record in the volatile log cuts only the damaged file at its last good record, as the ouroboros-consensus VolatileDB does when it validates its files. Later log files are no longer deleted; `volatileOpened` no longer reports `removedFiles`.
