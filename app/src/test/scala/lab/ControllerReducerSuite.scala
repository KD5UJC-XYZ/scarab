// SPDX-License-Identifier: Apache-2.0
package lab

class ControllerReducerSuite extends munit.FunSuite:
  import ControllerReducer.*
  def id(n: Int): Id = Id(f"$n%064x")
  val source = Store(id(1), id(2))
  def open(j: Journal, incarnation: Int = 90): Machine =
    ControllerReducer.open(j, id(incarnation)).fold(fail(_), identity)
  def request(m: Machine, c: Command): Transition = step(m, Input.Request(m.durable.revision, c))
  def complete(m: Machine, e: Evidence): Transition = step(m, Input.Completed(e))
  def forced(t: Transition): Transition =
    assertEquals(t.reply, Reply.Awaiting)
    val effect = t.effects match
      case Vector(f: Effect.Force) => f
      case other                   => fail(s"expected force, got $other")
    assertEquals(t.state.durable.revision + 1, effect.proposed.revision)
    complete(t.state, Evidence.Forced(effect.ticket))
  def run(m: Machine, c: Command): Transition = forced(request(m, c))
  def lease(m: Machine): Lease = m.durable.lease.get
  def rejected(t: Transition): Unit = assert(t.reply.isInstanceOf[Reply.Rejected], t.toString)
  def probe(m: Machine, number: Int = 10): Machine =
    val reserved = request(m, Command.ReserveLaunch(id(number), id(number + 1)))
    assert(!reserved.effects.exists(_.isInstanceOf[Effect.LaunchExact]))
    val launched = forced(reserved)
    assert(launched.effects.exists(_.isInstanceOf[Effect.LaunchExact]))
    val l = lease(launched.state)
    val bound = forced(
      complete(launched.state, Evidence.ChildBound(l.epoch, l.session, l.launch, id(number + 2)))
    )
    val locked = forced(
      complete(bound.state, Evidence.LockHeld(l.epoch, l.session, id(number + 2)))
    )
    assert(locked.effects.exists(_.isInstanceOf[Effect.Inspect]))
    locked.state
  def initialized: Machine =
    val p = probe(open(Journal(0, 0, Selection.Dormant(source))))
    val l = lease(p)
    val empty = forced(complete(p, Evidence.Inspected(l.epoch, l.session, Disk.Missing))).state
    val c = Claim(source, l.session, 0, id(20))
    val op = Operation(id(21), l.epoch, l.session, None, c)
    val ready = publish(empty, op)
    val serving = run(ready, Command.Activate)
    assert(serving.reply.isInstanceOf[Reply.Serving])
    serving.state
  def publish(m: Machine, op: Operation): Machine =
    val pending = request(m, Command.Begin(op))
    assertEquals(pending.state.durable, m.durable)
    val prepared = forced(pending)
    assertEquals(prepared.reply, Reply.Prepared(op))
    val installed = request(prepared.state, Command.Installed(op))
    assertEquals(installed.reply, Reply.Awaiting)
    assertEquals(installed.effects, Vector(Effect.Verify(lease(installed.state), op.after)))
    rejected(request(installed.state, Command.Begin(op)))
    val verified = complete(installed.state, Evidence.Verified(op.epoch, op.session, op.after))
    assertEquals(verified.reply, Reply.Awaiting)
    val acknowledged = forced(verified)
    assertEquals(acknowledged.reply, Reply.Committed(op))
    acknowledged.state
  def next(m: Machine, number: Int = 30): Operation =
    val a = m.durable.selection.asInstanceOf[Selection.Active].ack
    val l = lease(m)
    Operation(
      id(number),
      l.epoch,
      l.session,
      Some(a),
      a.copy(issuer = l.session, generation = a.generation + 1, digest = id(number + 1))
    )
  def retire(m: Machine): Machine = run(m, Command.Retire(id(80))).state
  def settle(m: Machine): Machine =
    val l = lease(m)
    val proof = l.child.fold[OwnershipEnd](OwnershipEnd.Absent(id(81)))(c =>
      OwnershipEnd.Exited(c, id(81), id(82))
    )
    forced(complete(m, Evidence.OwnershipEnded(l.epoch, l.session, l.launch, proof))).state

  test("prepared committed and serving permissions follow matching force/verification only") {
    val m = initialized
    val op = next(m)
    val proposed = request(m, Command.Begin(op))
    assertEquals(proposed.state.durable, m.durable)
    rejected(complete(proposed.state, Evidence.Forced(Ticket(id(999), 1, op.id, m.durable))))
    val prepared = forced(proposed)
    rejected(complete(prepared.state, Evidence.Verified(op.epoch, op.session, op.after)))
    val done = publish(m, op)
    assertEquals(done.durable.selection, Selection.Active(op.after))
  }
  test("phase-specific duplicates never turn a committed callback back into a write permit") {
    val m = initialized; val op = next(m)
    val pending = run(m, Command.Begin(op)).state
    assertEquals(step(pending, Input.Request(0, Command.Begin(op))).reply, Reply.Prepared(op))
    val done = publish(m, op)
    assertEquals(step(done, Input.Request(0, Command.Begin(op))).reply, Reply.Committed(op))
    assertEquals(step(done, Input.Request(0, Command.Installed(op))).reply, Reply.Committed(op))
    val newer = publish(done, next(done, 40))
    rejected(request(newer, Command.Begin(op)))
    assertEquals(newer.durable.selection.asInstanceOf[Selection.Active].ack.generation, 2L)
  }
  test("old pending recorder cannot change a retired or newer acknowledged session") {
    val m = initialized; val op = next(m)
    val retired = retire(m)
    rejected(request(retired, Command.Begin(op)))
    val p = probe(settle(retired), 50); val l = lease(p)
    val ack = m.durable.selection.asInstanceOf[Selection.Active].ack
    val inspect = complete(p, Evidence.Inspected(l.epoch, l.session, Disk.Present(ack)))
    val verified = forced(complete(inspect.state, Evidence.Verified(l.epoch, l.session, ack))).state
    val serving = run(verified, Command.Activate).state
    val newer = publish(serving, next(serving, 60))
    val bad = request(newer, Command.Begin(op))
    rejected(bad); assertEquals(bad.state.durable, newer.durable)
  }
  test("no matching child does not settle a late create and never authorizes a second launch") {
    val start = open(Journal(0, 0, Selection.Dormant(source)))
    val launching = run(start, Command.ReserveLaunch(id(10), id(11))).state
    val l = lease(launching)
    val absent = complete(launching, Evidence.NoMatchingChild(l.epoch, l.session, l.launch))
    assertEquals(absent.state.durable, launching.durable)
    rejected(request(absent.state, Command.ReserveLaunch(id(50), id(51))))
    val retired = retire(absent.state)
    rejected(request(retired, Command.ReserveLaunch(id(50), id(51))))
    val late = forced(complete(retired, Evidence.ChildBound(l.epoch, l.session, l.launch, id(12))))
    assert(late.effects.exists(_.isInstanceOf[Effect.SettleOrStop]))
    rejected(
      complete(
        late.state,
        Evidence.OwnershipEnded(l.epoch, l.session, l.launch, OwnershipEnd.Absent(id(81)))
      )
    )
    val ended = settle(late.state)
    val second = run(ended, Command.ReserveLaunch(id(50), id(51)))
    assertEquals(lease(second.state).epoch, 2L)
    rejected(complete(second.state, Evidence.ChildBound(l.epoch, l.session, l.launch, id(12))))
  }
  test("reopened force completions bind incarnation and entire proposed content") {
    val journal = Journal(0, 0, Selection.Dormant(source))
    val a = request(open(journal, 90), Command.ReserveLaunch(id(10), id(11)))
    val b = request(open(journal, 91), Command.ReserveLaunch(id(10), id(13)))
    val old = a.effects.head.asInstanceOf[Effect.Force].ticket
    val stale = complete(b.state, Evidence.Forced(old))
    rejected(stale); assertEquals(stale.state.durable, journal)
    val c = request(open(journal, 90), Command.ReserveLaunch(id(10), id(13)))
    rejected(complete(c.state, Evidence.Forced(old)))
    assertEquals(lease(forced(b).state).launch, id(13))
  }
  test("repeated restart preserves pending alternatives and never reuses serving permission") {
    val m = initialized; val op = next(m)
    val pending = run(m, Command.Begin(op)).state
    val loaded = open(pending.durable, 91)
    rejected(request(loaded, Command.Begin(op)))
    val retired = retire(loaded)
    val again = open(retired.durable, 92)
    assertEquals(again.durable.pending, Some(op))
    val p = probe(settle(again), 50); val l = lease(p)
    val inspected = complete(p, Evidence.Inspected(l.epoch, l.session, Disk.Present(op.after)))
    val resolved =
      forced(complete(inspected.state, Evidence.Verified(l.epoch, l.session, op.after))).state
    assertEquals(resolved.durable.selection, Selection.Active(op.after))
    assertEquals(resolved.durable.pending, None)
    assert(run(resolved, Command.Activate).reply.isInstanceOf[Reply.Serving])
  }
  test(
    "initial pending missing image returns Dormant with aborted operation and permits explicit retry"
  ) {
    val p = probe(open(Journal(0, 0, Selection.Dormant(source)))); val l = lease(p)
    val empty = forced(complete(p, Evidence.Inspected(l.epoch, l.session, Disk.Missing))).state
    val op = Operation(id(21), l.epoch, l.session, None, Claim(source, l.session, 0, id(20)))
    val pending = run(empty, Command.Begin(op)).state
    val recovered = probe(settle(retire(open(pending.durable, 91))), 50); val nl = lease(recovered)
    val aborted =
      forced(complete(recovered, Evidence.Inspected(nl.epoch, nl.session, Disk.Missing))).state
    assertEquals(aborted.durable.selection, Selection.Dormant(source))
    assertEquals(aborted.durable.last, Some(Completion(op, Outcome.Aborted)))
    rejected(request(aborted, Command.Activate))
    val retry = Operation(id(61), nl.epoch, nl.session, None, Claim(source, nl.session, 0, id(62)))
    val ready = publish(aborted, retry)
    assertEquals(ready.durable.selection, Selection.Active(retry.after))
  }
  test("migration freezes source and selects only forced acknowledged verified target") {
    val m = settle(retire(initialized)); val target = Store(id(70), source.context)
    val migration = run(m, Command.BeginMigration(id(71), target)).state
    assert(migration.durable.selection.isInstanceOf[Selection.Migrating])
    rejected(request(migration, Command.SelectDestination(id(71))))
    val p = probe(migration, 50); val l = lease(p)
    val empty = forced(complete(p, Evidence.Inspected(l.epoch, l.session, Disk.Missing))).state
    val op = Operation(id(72), l.epoch, l.session, None, Claim(target, l.session, 0, id(73)))
    val ready = publish(empty, op)
    rejected(request(ready, Command.Activate))
    val proposed = request(ready, Command.SelectDestination(id(71)))
    assert(proposed.state.durable.selection.isInstanceOf[Selection.Migrating])
    val selected = forced(proposed)
    assertEquals(selected.reply, Reply.Selected(op.after))
    assert(run(selected.state, Command.Activate).reply.isInstanceOf[Reply.Serving])
  }
  test("journal uncertainty and authoritative verification failure halt instead of fallback") {
    val m = initialized; val update = request(m, Command.Begin(next(m)))
    val ticket = update.effects.head.asInstanceOf[Effect.Force].ticket
    val uncertain = complete(update.state, Evidence.ForceUncertain(ticket))
    assert(uncertain.state.halted)
    rejected(request(uncertain.state, Command.ReserveLaunch(id(50), id(51))))
    val p = probe(settle(retire(m)), 50); val l = lease(p)
    val unknown = complete(
      p,
      Evidence.Inspected(l.epoch, l.session, Disk.Present(Claim(source, l.session, 999, id(99))))
    )
    assertEquals(unknown.reply, Reply.Stopped)
    val failed = complete(p, Evidence.Failed(l.epoch, l.session, Stage.Inspect))
    assert(failed.state.halted)
  }
  test("bounded schemas reject impossible phases malformed inputs and exhausted counters") {
    val m = initialized
    assert(
      ControllerReducer
        .open(m.durable.copy(lease = Some(lease(m).copy(locked = false))), id(90))
        .isLeft
    )
    assert(
      ControllerReducer
        .open(m.durable.copy(lease = Some(lease(m).copy(verified = None))), id(90))
        .isLeft
    )
    assert(ControllerReducer.open(Journal(-1, 0, Selection.Dormant(source)), id(90)).isLeft)
    val max = open(Journal(Long.MaxValue, 0, Selection.Dormant(source)))
    rejected(request(max, Command.ReserveLaunch(id(10), id(11))))
    val epoch = open(Journal(0, Long.MaxValue, Selection.Dormant(source)))
    rejected(request(epoch, Command.ReserveLaunch(id(10), id(11))))
    rejected(step(m, Input.Request(m.durable.revision, null)))
    val retired = retire(m); val l = lease(retired)
    rejected(complete(retired, Evidence.OwnershipEnded(l.epoch, l.session, l.launch, null)))
    rejected(
      request(
        m,
        Command.Begin(next(m).copy(after = next(m).after.copy(generation = Long.MaxValue)))
      )
    )
  }

  test("predecessor recovery aborts pending successor and rejects its old callbacks") {
    val m = initialized; val op = next(m)
    val pending = run(m, Command.Begin(op)).state
    val p = probe(settle(retire(open(pending.durable, 91))), 50); val l = lease(p)
    val inspected = complete(p, Evidence.Inspected(l.epoch, l.session, Disk.Present(op.before.get)))
    val proposed = complete(inspected.state, Evidence.Verified(l.epoch, l.session, op.before.get))
    assertEquals(proposed.state.durable.pending, Some(op))
    val resolved = forced(proposed).state
    assertEquals(resolved.durable.last, Some(Completion(op, Outcome.Aborted)))
    assertEquals(resolved.durable.selection, Selection.Active(op.before.get))
    rejected(request(resolved, Command.Installed(op)))
    rejected(complete(resolved, Evidence.Verified(op.epoch, op.session, op.after)))
    assert(run(resolved, Command.Activate).reply.isInstanceOf[Reply.Serving])
  }
  test("interrupted migration preserves selection and needs fresh target verification") {
    val m = settle(retire(initialized)); val target = Store(id(70), source.context)
    val migration = run(m, Command.BeginMigration(id(71), target)).state
    val p = probe(migration, 50); val l = lease(p)
    val empty = forced(complete(p, Evidence.Inspected(l.epoch, l.session, Disk.Missing))).state
    val op = Operation(id(72), l.epoch, l.session, None, Claim(target, l.session, 0, id(73)))
    val pending = run(empty, Command.Begin(op)).state
    val r = probe(settle(retire(open(pending.durable, 91))), 60); val rl = lease(r)
    rejected(request(r, Command.SelectDestination(id(71))))
    val inspected = complete(r, Evidence.Inspected(rl.epoch, rl.session, Disk.Present(op.after)))
    val ready =
      forced(complete(inspected.state, Evidence.Verified(rl.epoch, rl.session, op.after))).state
    assertEquals(
      ready.durable.selection,
      Selection.Migrating(
        id(71),
        m.durable.selection.asInstanceOf[Selection.Active].ack,
        target,
        Some(op.after)
      )
    )
    val reopened = open(ready.durable, 92)
    rejected(request(reopened, Command.SelectDestination(id(71))))
    val fresh = probe(settle(retire(reopened)), 75); val fl = lease(fresh)
    val again = complete(fresh, Evidence.Inspected(fl.epoch, fl.session, Disk.Present(op.after)))
    val verified =
      forced(complete(again.state, Evidence.Verified(fl.epoch, fl.session, op.after))).state
    val selected = run(verified, Command.SelectDestination(id(71))).state
    assert(run(selected, Command.Activate).reply.isInstanceOf[Reply.Serving])
  }
  test("delayed verification failure is bound to its requested claim") {
    val m = initialized; val old = next(m)
    val done = publish(m, old); val newer = next(done, 40)
    val pending = run(done, Command.Begin(newer)).state
    val checking = request(pending, Command.Installed(newer)).state
    val stale =
      complete(checking, Evidence.Failed(old.epoch, old.session, Stage.Verify, Some(old.after)))
    rejected(stale); assert(!stale.state.halted)
    val matched = complete(
      checking,
      Evidence.Failed(newer.epoch, newer.session, Stage.Verify, Some(newer.after))
    )
    assert(matched.state.halted)
  }
  test("reopened launching lease cannot acquire lock before retirement") {
    val m = run(
      open(Journal(0, 0, Selection.Dormant(source))),
      Command.ReserveLaunch(id(10), id(11))
    ).state
    val l = lease(m)
    rejected(
      complete(open(m.durable, 91), Evidence.ChildBound(l.epoch, l.session, l.launch, id(12)))
    )
    val bound = forced(complete(m, Evidence.ChildBound(l.epoch, l.session, l.launch, id(12)))).state
    rejected(complete(open(bound.durable, 91), Evidence.LockHeld(l.epoch, l.session, id(12))))
  }
