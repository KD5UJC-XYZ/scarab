// SPDX-License-Identifier: Apache-2.0
package lab

/** Pure crash-only controller protocol. Evidence is supplied by future trusted adapters; this
  * reducer performs no I/O, process launch, runtime recovery or authentication of evidence.
  */
object ControllerReducer:
  final case class Id(value: String):
    def valid: Boolean = value != null && value.matches("[0-9a-f]{64}")
  final case class Store(id: Id, context: Id)
  final case class Claim(store: Store, issuer: Id, generation: Long, digest: Id)
  enum Phase:
    case Launching, Probe, Serving, Retired
  final case class Lease(
      epoch: Long,
      session: Id,
      launch: Id,
      store: Store,
      phase: Phase,
      child: Option[Id] = None,
      locked: Boolean = false,
      ended: Boolean = false,
      verified: Option[Claim] = None
  )
  final case class Operation(id: Id, epoch: Long, session: Id, before: Option[Claim], after: Claim)
  enum Outcome:
    case Committed, Aborted
  final case class Completion(operation: Operation, outcome: Outcome)
  enum Selection:
    case Dormant(store: Store)
    case Active(ack: Claim)
    case Migrating(id: Id, source: Claim, target: Store, acknowledged: Option[Claim])
  final case class Journal(
      revision: Long,
      epoch: Long,
      selection: Selection,
      lease: Option[Lease] = None,
      pending: Option[Operation] = None,
      last: Option[Completion] = None,
      recovering: Boolean = false
  )

  final case class Ticket(incarnation: Id, revision: Long, identity: Id, proposed: Journal)
  enum Effect:
    case Force(ticket: Ticket, proposed: Journal)
    case LaunchExact(lease: Lease)
    case AcquireLock(lease: Lease)
    case SettleOrStop(lease: Lease)
    case Inspect(lease: Lease, allowed: Vector[Claim], allowMissing: Boolean)
    case Verify(lease: Lease, claim: Claim)
  enum Reply:
    case Awaiting
    case Rejected(reason: String)
    case Prepared(operation: Operation)
    case Committed(operation: Operation)
    case Serving(lease: Lease)
    case Selected(claim: Claim)
    case Stopped
  enum Command:
    case ReserveLaunch(session: Id, launch: Id)
    case Begin(operation: Operation)
    case Installed(operation: Operation)
    case Activate
    case Retire(reason: Id)
    case BeginMigration(id: Id, target: Store)
    case SelectDestination(id: Id)

  /** Settlement is stronger than a process listing: the old launch issuer/request can no longer
    * create a child. Exited additionally proves the exact child exited and released its lock.
    */
  enum OwnershipEnd:
    case Absent(launchSettled: Id)
    case Exited(child: Id, launchSettled: Id, lockReleased: Id)
  enum Disk:
    case Missing
    case Present(claim: Claim)
  enum Stage:
    case Launch, Lock, Inspect, Verify
  enum Evidence:
    case Failed(epoch: Long, session: Id, stage: Stage, claim: Option[Claim] = None)
    case Forced(ticket: Ticket)
    case ForceUncertain(ticket: Ticket)
    case ChildBound(epoch: Long, session: Id, launch: Id, child: Id)
    case NoMatchingChild(epoch: Long, session: Id, launch: Id)
    case OwnershipEnded(epoch: Long, session: Id, launch: Id, proof: OwnershipEnd)
    case LockHeld(epoch: Long, session: Id, child: Id)
    case Inspected(epoch: Long, session: Id, disk: Disk)
    case Verified(epoch: Long, session: Id, claim: Claim)
  enum Input:
    case Request(expectedRevision: Long, command: Command)
    case Completed(evidence: Evidence)

  private final case class WaitingForce(
      ticket: Ticket,
      proposed: Journal,
      reply: Reply,
      effects: Vector[Effect]
  )
  private enum Work:
    case Inspect(epoch: Long, session: Id)
    case Verify(epoch: Long, session: Id, claim: Claim, installed: Boolean)
  final class Machine private[ControllerReducer] (
      val durable: Journal,
      private[ControllerReducer] val write: Option[WaitingForce],
      private[ControllerReducer] val work: Option[Work],
      val halted: Boolean,
      private[ControllerReducer] val incarnation: Id,
      private[ControllerReducer] val reopened: Boolean
  )
  final case class Transition(state: Machine, reply: Reply, effects: Vector[Effect])

  private def store(s: Store): Boolean =
    s != null && s.id != null && s.context != null && s.id.valid && s.context.valid
  private def claim(c: Claim): Boolean = c != null && store(c.store) && c.issuer != null &&
    c.issuer.valid && c.digest != null && c.digest.valid && c.generation >= 0
  private def operation(o: Operation): Boolean = o != null && o.id != null && o.id.valid &&
    o.epoch > 0 && o.session != null && o.session.valid && o.before != null &&
    o.before
      .forall(claim) && claim(o.after) && o.after.issuer == o.session && o.before.forall { b =>
      b.store == o.after.store && b.generation < Long.MaxValue && o.after.generation == b.generation + 1
    } && (o.before.nonEmpty || o.after.generation == 0)
  private def selected(j: Journal): Store = j.selection match
    case Selection.Dormant(s)                 => s
    case Selection.Active(a)                  => a.store
    case Selection.Migrating(_, _, target, _) => target
  private def expected(j: Journal): Option[Claim] = j.selection match
    case Selection.Active(a)               => Some(a)
    case Selection.Migrating(_, _, _, ack) => ack
    case _                                 => None
  private def live(l: Lease): Boolean = !l.ended && l.phase != Phase.Retired
  private def valid(j: Journal): Boolean =
    j != null && j.revision >= 0 && j.epoch >= 0 && j.selection != null &&
      j.lease != null && j.pending != null && j.last != null &&
      (j.selection match
        case Selection.Dormant(s) => store(s)
        case Selection.Active(a)  => claim(a)
        case Selection.Migrating(id, source, target, ack) =>
          id != null && id.valid && claim(source) && store(
            target
          ) && target.id != source.store.id &&
          target.context == source.store.context && ack != null && ack.forall(c =>
            claim(c) && c.store == target
          )) &&
      j.lease.forall(l =>
        l != null && l.epoch > 0 && l.epoch == j.epoch &&
          l.session != null && l.session.valid && l.launch != null && l.launch.valid && store(
            l.store
          ) &&
          l.phase != null && l.child != null && l.child.forall(i => i != null && i.valid) &&
          l.verified != null && l.verified.forall(c => claim(c) && c.store == l.store) &&
          (!l.locked || l.child.nonEmpty) && (!l.ended || (l.phase == Phase.Retired && !l.locked)) &&
          (l.phase match
            case Phase.Launching => !l.locked && l.verified.isEmpty
            case Phase.Probe =>
              l.locked && l.child.nonEmpty && l.verified.forall(c => expected(j).contains(c))
            case Phase.Serving =>
              l.locked && l.child.nonEmpty && !j.recovering &&
              j.selection
                .isInstanceOf[Selection.Active] && l.verified.nonEmpty && l.verified == expected(j)
            case Phase.Retired => l.verified.isEmpty) &&
          l.store.context == selected(j).context && (!live(l) || l.store == selected(j))
      ) &&
      j.pending.forall(o =>
        operation(o) && o.after.store == selected(j) && o.before == expected(j) &&
          o.epoch <= j.epoch && j.lease.exists(l => o.epoch < l.epoch || o.session == l.session)
      ) &&
      j.last.forall(c =>
        c != null && operation(c.operation) && c.outcome != null &&
          c.operation.epoch <= j.epoch && c.operation.after.store.context == selected(j).context
      )

  /** Load requires a durable image from the future adapter. Reopening always suppresses serving;
    * callers must retire/reconcile any prior launch before a new lease is granted.
    */
  def open(journal: Journal, incarnation: Id): Either[String, Machine] =
    if !valid(journal) || incarnation == null || !incarnation.valid then
      Left("invalid bounded journal schema")
    else Right(new Machine(journal, None, None, false, incarnation, true))

  def step(m: Machine, input: Input): Transition =
    def reject(reason: String) = Transition(m, Reply.Rejected(reason), Vector.empty)
    def halt = Transition(
      new Machine(m.durable, None, None, true, m.incarnation, m.reopened),
      Reply.Stopped,
      Vector.empty
    )
    def await(effects: Effect*) = Transition(m, Reply.Awaiting, effects.toVector)
    def sameLease(epoch: Long, session: Id): Option[Lease] =
      m.durable.lease.filter(l => l.epoch == epoch && l.session == session)
    def nextWork(next: Machine, effects: Vector[Effect]): Machine =
      val work = effects.collectFirst {
        case Effect.Inspect(l, _, _) => Work.Inspect(l.epoch, l.session)
        case Effect.Verify(l, c)     => Work.Verify(l.epoch, l.session, c, false)
      }
      new Machine(next.durable, next.write, work, next.halted, next.incarnation, next.reopened)
    def force(identity: Id, proposed: Journal, reply: Reply, effects: Effect*): Transition =
      if m.durable.revision == Long.MaxValue then reject("controller revision exhausted")
      else
        val j = proposed.copy(revision = m.durable.revision + 1)
        if !valid(j) then reject("invalid proposed journal")
        else
          val ticket = Ticket(m.incarnation, j.revision, identity, j)
          val waiting = WaitingForce(ticket, j, reply, effects.toVector)
          Transition(
            new Machine(m.durable, Some(waiting), None, false, m.incarnation, m.reopened),
            Reply.Awaiting,
            Vector(Effect.Force(ticket, j))
          )
    def inspect(l: Lease): Effect =
      val choices =
        m.durable.pending.fold(expected(m.durable).toVector)(o => o.before.toVector :+ o.after)
      Effect.Inspect(
        l,
        choices,
        m.durable.pending.exists(_.before.isEmpty) || expected(m.durable).isEmpty
      )
    def resolve(lease: Lease, c: Claim, installed: Boolean): Transition =
      val j = m.durable
      j.pending match
        case Some(o) if c == o.after =>
          val selection = j.selection match
            case Selection.Migrating(id, source, target, _) =>
              Selection.Migrating(id, source, target, Some(c))
            case _ => Selection.Active(c)
          force(
            o.id,
            j.copy(
              selection = selection,
              pending = None,
              last = Some(Completion(o, Outcome.Committed)),
              recovering = false,
              lease = Some(lease.copy(verified = Some(c)))
            ),
            if installed then Reply.Committed(o) else Reply.Awaiting
          )
        case Some(o) if o.before.contains(c) && !installed =>
          force(
            o.id,
            j.copy(
              pending = None,
              last = Some(Completion(o, Outcome.Aborted)),
              recovering = false,
              lease = Some(lease.copy(verified = Some(c)))
            ),
            Reply.Awaiting
          )
        case None if expected(j).contains(c) && !installed =>
          force(
            lease.session,
            j.copy(recovering = false, lease = Some(lease.copy(verified = Some(c)))),
            Reply.Awaiting
          )
        case _ => reject("unrecorded verification outcome")

    if m == null || !valid(m.durable) || input == null then
      Transition(m, Reply.Rejected("invalid input/state"), Vector.empty)
    else if m.halted then reject("controller halted; strict reload required")
    else
      m.write match
        case Some(w) =>
          input match
            case Input.Completed(Evidence.Forced(t)) if t == w.ticket =>
              val next = nextWork(
                new Machine(
                  w.proposed,
                  None,
                  None,
                  false,
                  m.incarnation,
                  m.reopened && w.proposed.epoch == m.durable.epoch
                ),
                w.effects
              )
              Transition(next, w.reply, w.effects)
            case Input.Completed(Evidence.ForceUncertain(t)) if t == w.ticket =>
              Transition(
                new Machine(m.durable, None, None, true, m.incarnation, m.reopened),
                Reply.Stopped,
                Vector.empty
              )
            case _ => reject("journal force pending")
        case None =>
          input match
            case Input.Request(rev, command) =>
              val j = m.durable
              val lease = j.lease
              val duplicate = command match
                case Command.Begin(o)
                    if !m.reopened && m.work.isEmpty && j.pending.contains(o) && lease
                      .exists(l => live(l) && l.epoch == o.epoch && l.session == o.session) =>
                  Some(Transition(m, Reply.Prepared(o), Vector.empty))
                case Command.Begin(o)
                    if !m.reopened && j.last
                      .exists(c => c.operation == o && c.outcome == Outcome.Committed) && lease
                      .exists(l => live(l) && l.epoch == o.epoch && l.session == o.session) =>
                  Some(Transition(m, Reply.Committed(o), Vector.empty))
                case Command.Installed(o)
                    if !m.reopened && j.last
                      .exists(c => c.operation == o && c.outcome == Outcome.Committed) && lease
                      .exists(l => live(l) && l.epoch == o.epoch && l.session == o.session) =>
                  Some(Transition(m, Reply.Committed(o), Vector.empty))
                case _ => None
              duplicate.getOrElse {
                if command == null then reject("null command")
                else if m.reopened && !(command.isInstanceOf[Command.Retire] || command
                    .isInstanceOf[Command.ReserveLaunch])
                then reject("startup requires old ownership retirement and fresh launch")
                else if rev != j.revision then reject("stale controller revision")
                else
                  command match
                    case Command.ReserveLaunch(session, launch) =>
                      if session == null || launch == null || !session.valid || !launch.valid ||
                        m.work.nonEmpty || lease.exists(l => !l.ended) || lease.exists(l =>
                          l.session == session || l.launch == launch
                        )
                      then reject("unsettled prior launch or invalid new identity")
                      else if j.epoch == Long.MaxValue then reject("session epoch exhausted")
                      else
                        val l = Lease(j.epoch + 1, session, launch, selected(j), Phase.Launching)
                        force(
                          session,
                          j.copy(epoch = l.epoch, lease = Some(l)),
                          Reply.Awaiting,
                          Effect.LaunchExact(l)
                        )
                    case Command.Retire(reason) =>
                      if reason == null || !reason.valid then reject("invalid retirement")
                      else
                        lease match
                          case Some(l) if !l.ended =>
                            val retired = l.copy(phase = Phase.Retired, verified = None)
                            force(
                              reason,
                              j.copy(lease = Some(retired), recovering = true),
                              Reply.Awaiting,
                              Effect.SettleOrStop(retired)
                            )
                          case _ => reject("no unsettled lease")
                    case Command.Begin(o) =>
                      if j.recovering || m.work.nonEmpty || j.pending.nonEmpty || !operation(o) ||
                        !lease.exists(l =>
                          live(l) && l.locked && l.epoch == o.epoch && l.session == o.session &&
                            (l.phase == Phase.Serving || (l.phase == Phase.Probe && expected(
                              j
                            ).isEmpty))
                        ) ||
                        o.before != expected(j) || o.after.store != selected(j)
                      then reject("publication fence/phase")
                      else force(o.id, j.copy(pending = Some(o)), Reply.Prepared(o))
                    case Command.Installed(o) =>
                      if !j.pending.contains(o) || j.recovering || m.work.nonEmpty ||
                        !lease.exists(l =>
                          live(l) && l.locked && l.epoch == o.epoch && l.session == o.session
                        )
                      then reject("installed fence/phase")
                      else
                        val l = lease.get
                        Transition(
                          new Machine(
                            j,
                            None,
                            Some(Work.Verify(l.epoch, l.session, o.after, true)),
                            false,
                            m.incarnation,
                            m.reopened
                          ),
                          Reply.Awaiting,
                          Vector(Effect.Verify(l, o.after))
                        )
                    case Command.Activate =>
                      lease match
                        case Some(l)
                            if l.phase == Phase.Probe && l.locked && !j.recovering && j.pending.isEmpty &&
                              j.selection.isInstanceOf[Selection.Active] && l.verified == expected(
                                j
                              ) && l.verified.nonEmpty =>
                          val serving = l.copy(phase = Phase.Serving)
                          force(l.session, j.copy(lease = Some(serving)), Reply.Serving(serving))
                        case _ => reject("unverified or unselected serving request")
                    case Command.BeginMigration(id, target) =>
                      j.selection match
                        case Selection.Active(a)
                            if id != null && id.valid && store(
                              target
                            ) && target.context == a.store.context &&
                              target.id != a.store.id && j.pending.isEmpty && lease.forall(
                                _.ended
                              ) =>
                          force(
                            id,
                            j.copy(
                              selection = Selection.Migrating(id, a, target, None),
                              recovering = false
                            ),
                            Reply.Awaiting
                          )
                        case _ => reject("migration requires quiescent acknowledged source")
                    case Command.SelectDestination(id) =>
                      j.selection match
                        case Selection.Migrating(mid, _, _, Some(a))
                            if id == mid && j.pending.isEmpty && !j.recovering &&
                              lease.exists(l =>
                                l.phase == Phase.Probe && l.locked && l.verified.contains(a)
                              ) =>
                          force(id, j.copy(selection = Selection.Active(a)), Reply.Selected(a))
                        case _ => reject("target not verified and acknowledged")
              }
            case Input.Completed(evidence) =>
              val j = m.durable
              evidence match
                case Evidence.Failed(epoch, session, stage, failedClaim) =>
                  sameLease(epoch, session) match
                    case Some(l) if live(l) =>
                      val requested = stage match
                        case Stage.Launch  => l.phase == Phase.Launching && l.child.isEmpty
                        case Stage.Lock    => l.phase == Phase.Launching && l.child.nonEmpty
                        case Stage.Inspect => m.work.contains(Work.Inspect(epoch, session))
                        case Stage.Verify =>
                          m.work.exists {
                            case Work.Verify(e, s, c, _) =>
                              e == epoch && s == session && failedClaim != null && failedClaim
                                .contains(c)
                            case _ => false
                          }
                        case null => false
                      if requested then halt else reject("failure stage not requested")
                    case _ => reject("failure lease fence")
                case Evidence.ChildBound(epoch, session, launch, child) =>
                  sameLease(epoch, session) match
                    case Some(l)
                        if l.launch == launch && !l.ended && l.child.isEmpty && child != null && child.valid =>
                      val bound = l.copy(child = Some(child))
                      if l.phase == Phase.Retired then
                        force(
                          session,
                          j.copy(lease = Some(bound)),
                          Reply.Awaiting,
                          Effect.SettleOrStop(bound)
                        )
                      else if l.phase == Phase.Launching && !m.reopened then
                        force(
                          session,
                          j.copy(lease = Some(bound)),
                          Reply.Awaiting,
                          Effect.AcquireLock(bound)
                        )
                      else reject("child binding phase")
                    case _ => reject("child binding fence")
                case Evidence.NoMatchingChild(epoch, session, launch) =>
                  sameLease(epoch, session) match
                    case Some(l) if l.launch == launch && !l.ended => await(Effect.SettleOrStop(l))
                    case _ => reject("absence observation fence")
                case Evidence.OwnershipEnded(epoch, session, launch, proof) =>
                  sameLease(epoch, session) match
                    case Some(l) if l.launch == launch && l.phase == Phase.Retired && !l.ended =>
                      val settled = proof match
                        case OwnershipEnd.Absent(p) => l.child.isEmpty && p != null && p.valid
                        case OwnershipEnd.Exited(child, p, lock) =>
                          l.child.contains(
                            child
                          ) && p != null && p.valid && lock != null && lock.valid
                        case null => false
                      if !settled then reject("launch/ownership settlement missing")
                      else
                        force(
                          session,
                          j.copy(lease = Some(l.copy(ended = true, locked = false))),
                          Reply.Awaiting
                        )
                    case _ => reject("ownership end fence/phase")
                case Evidence.LockHeld(epoch, session, child) =>
                  sameLease(epoch, session) match
                    case Some(l)
                        if l.phase == Phase.Launching && !m.reopened && l.child.contains(child) =>
                      val probe = l.copy(phase = Phase.Probe, locked = true)
                      force(session, j.copy(lease = Some(probe)), Reply.Awaiting, inspect(probe))
                    case _ => reject("lock evidence fence/phase")
                case Evidence.Inspected(epoch, session, disk) =>
                  sameLease(epoch, session) match
                    case Some(l)
                        if l.phase == Phase.Probe && l.locked && m.work.contains(
                          Work.Inspect(epoch, session)
                        ) =>
                      val choices =
                        j.pending.fold(expected(j).toVector)(o => o.before.toVector :+ o.after)
                      disk match
                        case Disk.Present(c) if choices.contains(c) =>
                          Transition(
                            new Machine(
                              j,
                              None,
                              Some(Work.Verify(epoch, session, c, false)),
                              false,
                              m.incarnation,
                              m.reopened
                            ),
                            Reply.Awaiting,
                            Vector(Effect.Verify(l, c))
                          )
                        case Disk.Missing
                            if expected(j).isEmpty && j.pending.forall(_.before.isEmpty) =>
                          val last =
                            j.pending.map(o => Completion(o, Outcome.Aborted)).orElse(j.last)
                          force(
                            session,
                            j.copy(pending = None, last = last, recovering = false),
                            Reply.Awaiting
                          )
                        case _ => halt
                    case _ => reject("inspection fence/phase")
                case Evidence.Verified(epoch, session, c) =>
                  sameLease(epoch, session) match
                    case Some(l) if l.locked && live(l) =>
                      m.work match
                        case Some(Work.Verify(`epoch`, `session`, expectedClaim, installed))
                            if c == expectedClaim =>
                          resolve(l, c, installed)
                        case _ => reject("verification not requested")
                    case _ => reject("verification lease fence")
                case _ => reject("unexpected completion")
