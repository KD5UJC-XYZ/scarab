### Added (licence texts in the assembly JAR, #75)

- The node's assembly JAR carries the licence and notice texts of every library and native library bundled in it under
  `META-INF/scarab/licenses/`, with an index of their sources (`licenses/index.json`): the MIT, BSD, ISC and Apache-2.0
  notices their licences require in binary form, the NOTICE files of the Apache-2.0 projects, and the texts of the
  compression libraries linked into RocksDB's JNI library. A candidate build fails when a bundled component has no
  text, when a text changes, or when the index names a component that is no longer bundled.
