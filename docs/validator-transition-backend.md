# Private validator transition backend

`ValidatorTransitions` supplies the four operations needed by the node runner:
`snapshot`, `prepare(view, block)`, `publish(prepared)` and `rollbackTo(view, point)`.
This packet adds only the backend, focused tests and this document. Runner,
command and configuration wiring remain with the integrating task.

The package-private types are `Backend[F]`, `View`, `Prepared`, `ConfirmedState`,
`Confirmation`, `Rejection` and `StorageFailure`. `inMemory`, `durableCreate` and
`durableResume` return an owned `Resource[F, Backend[F]]`. Durable factories accept
the existing pending-token recorder and deadlines. A caller never receives the
underlying coordinator runtime, fence, owner, disk store or mutable token reference.

Views and prepared candidates have private constructors and belong to one backend
lifetime. Preparation requires the exact current view. Publication and rollback
reject foreign or stale capabilities before invoking the coordinator. All operations
that can alter the session share one gate, including resource closure. Existing
coordinator preparation, publication, rollback, validation and recovery remain the
only implementations of those transitions.

`Confirmation` distinguishes `Volatile`, `LoadedVerified(token)` and
`Acknowledged(token)`. A recovered checkpoint begins loaded-verified. An unchanged
rollback returns the same view, token and classification; it cannot manufacture a
new acknowledgement or increment generation. A successful durable mutation records
its returned acknowledgement before exposing the new view. Public `lastConfirmed`
reads only this immutable reporting cache and is safe even after termination.

A storage exception terminates the backend. `StorageFailure` carries the last
confirmed state and `potentiallyOlderThanDisk = true`; the caller must preserve
that qualification. Pending-token recording is not acknowledgement. Subsequent
operations return the same terminal failure without querying the poisoned runtime
or retrying publication. Cancellation while awaiting publication is also terminal:
the operation may have reached disk without delivering acknowledgement. Preparation
cancellation does not perform a publication. Resource release still closes the
underlying store after the backend gate has drained.

The integrating runner should keep `View` and `Prepared` private, report
`ConfirmedState` with its confirmation classification, and terminate on
`StorageFailure`. Its error-outcome path must use the carried cache, not request a
fresh snapshot. A pending recorder callback must remain distinct from a confirmed
receipt sink. Factories acquire/verify the backend before peers are opened.

There is deliberately no anchor-advancement operation. The window lane's derived
anchors are rejected by validated checkpoint v1, including an empty retained suffix.
Configuration must reject sustained-window plus durable mode. Sustained volatile
and bounded durable operation remain separate until compacted persistence is designed.

Focused retained-input tests cover cross-mode/session rejection, durable reopen,
loaded/no-op classification, rollback/reapply, concurrent stale candidates, storage
exceptions before and after disk installation, cancellation before disk and after
disk but before acknowledgement delivery, and close/publication ordering. Test
directories are resource-scoped and removed after each test. Run only
`app/testOnly lab.ValidatorTransitionsSuite` with `COHERENT_SEQUENCE_EVIDENCE` set
to the retained empty-plus-two-transaction fixture; the dedicated test lane supplies
that fixture read-only. These are offline unit tests, not crash or power-loss tests.
