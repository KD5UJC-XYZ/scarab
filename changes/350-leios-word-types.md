### Fixed

- Leios point slots and bitmap words retain the full unsigned 64-bit range. Checked unsigned word types prevent invalid slots, sizes and bitmap words from entering codec message constructors.
- Leios wire EB hashes and transaction hashes use separate byte wrappers matching the native prototype decoder. Their wire wrappers accept arbitrary byte lengths; the ranking-block header announcement and cryptographic verification retain their separate hash-length checks. The generated Blueprint reader's narrower hash constraint remains explicit in comparison tests.
