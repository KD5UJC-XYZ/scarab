# Pending reward snapshot V5 fixture

**Status: Current** — compatibility and continuation regression for [#135](https://github.com/KD5UJC-XYZ/scarab/issues/135).

`rewards-pending-v5.bin` is a 20,227-byte `SnapshotV5` payload (without the file-store checksum trailer). Its SHA-256 is
`6a7f779bc05f7349dfa2700ed77ab6a2afc40b1fc057d19225ec4beb61f1de88`.

Since #395 the suites read it through the registered V5 → V7 conversion (`SnapshotV7.fromV5`), as
opening a database that holds it would; the bytes stay the previous-version input.

The fixture producer, [RewardSnapshotFixtureMain](../scala/xyz/kd5ujc/scarab/storage/RewardSnapshotFixtureMain.scala), applies the maintained era-transition blocks using `fixtures/eras/genesis`, then inserts the captured Conway-v10 pending reward state from the [unchanged Haskell API oracle](../../../../storage/src/test/resources/rewards-1.19.0.1/README.md). This deliberately constructed state tests the reward wire layout and continuation, rather than claiming historical block admission for the inserted state.

The produced payload was subsequently decoded and rewritten by the **pre-change opaque reward-pulser writer**, compiled against the retained pre-change production classes, whose Scala sources match main `7dfcded14465305de328827c890146b062621476` (the original class copy was built on the source-equivalent PR196 head `38936f9b`). That writer round-trip produced exactly the same bytes. The standalone writer source SHA-256 is `8172fa92361dfd8f25052b72e5599580f3f3bd2a157fca71c703dbe2ada3bf95`; its invocation, source, class inventory and output remain in the private evidence directory `/tmp/scarab/issue-135`. The current producer and oracle inputs are retained so the fixture construction is reviewable.

[RewardSnapshotSuite](../scala/xyz/kd5ujc/scarab/storage/RewardSnapshotSuite.scala) checks unchanged V5 re-encoding, one-pulse checkpoint/restart, subsequent exact continuation and completion, and rollback to the immutable earlier prefix. Together with the unchanged sidecar fingerprint golden this proves that replacing the opaque decoder with typed state does not require a persisted-format migration.
