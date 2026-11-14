### Added (build)

- `check` runs sbt-header's `headerCheckAll`: every Scala, Java and sbt source of the modules and the build definition starts with `// SPDX-License-Identifier: Apache-2.0`, or `Apache-2.0 AND <licence>` where the code is adapted from another licence, with no copyright line. `headerCreateAll` adds the line to a source that lacks it.
