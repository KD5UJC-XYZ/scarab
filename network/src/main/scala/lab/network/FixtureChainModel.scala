// SPDX-License-Identifier: Apache-2.0
package lab.network

import ChainSync.*

/** Deterministic synthetic follower history. No consensus chain selection or ledger rollback. */
object FixtureChainModel:
  val MaxHistory = 256
  val MaxTrace = 1024
  final class Model private[FixtureChainModel] (
      val state: State,
      val history: Vector[Point],
      val offered: Vector[Point],
      val trace: Vector[String]
  ):
    def cursor: Point = history.last
    def step[P](
        sender: Role,
        message: Message[P],
        forwardPoint: Option[Point] = None
    ): Either[String, Model] =
      for
        next <- transition(state, sender, message)
        _ <- validateMessage(message, forwardPoint)
        _ <- Either.cond(trace.size < MaxTrace, (), "model trace limit exceeded")
        result <- applyMessage(next, message, forwardPoint)
      yield result

    private def applyMessage[P](
        next: State,
        message: Message[P],
        forwardPoint: Option[Point]
    ): Either[String, Model] =
      var nextHistory = history
      var nextOffered = offered
      val change: Either[String, String] = message match
        case Message.FindIntersect(points) =>
          if points.size > Limits().maxCandidates then Left("candidate limit exceeded")
          else { nextOffered = points; Right("find-intersect") }
        case Message.IntersectFound(point, _) =>
          if !offered.contains(point) then Left("unoffered intersection")
          else
            val i = history.indexOf(point)
            nextHistory = if i >= 0 then history.take(i + 1) else Vector(point)
            nextOffered = Vector.empty
            Right("intersection-found")
        case Message.IntersectNotFound(_) =>
          nextOffered = Vector.empty
          Right("intersection-not-found") // Deliberately preserves follower cursor/history.
        case Message.RollBackward(point, _) =>
          val i = history.indexOf(point)
          if i < 0 then Left("unknown or pruned rollback target: reintersection required")
          else { nextHistory = history.take(i + 1); Right("roll-backward") }
        case Message.RollForward(_, _) =>
          forwardPoint match
            case Some(point @ Point.Block(_, _)) if !history.contains(point) =>
              if history.size >= MaxHistory then
                Left("model history limit exceeded: reintersection required")
              else { nextHistory = history :+ point; Right("roll-forward") }
            case _ => Left("forward requires a distinct explicitly synthetic block point")
        case Message.RequestNext => Right("request-next")
        case Message.AwaitReply  => Right("await-reply")
        case Message.Done        => Right("done")
      change.map(label => new Model(next, nextHistory, nextOffered, trace :+ label))

  private def validPoint(point: Point): Boolean = ChainSyncFixtures.cardanoPoint(point).isRight
  private def validateMessage[P](
      message: Message[P],
      forward: Option[Point]
  ): Either[String, Unit] =
    val points = message match
      case Message.FindIntersect(ps)      => ps
      case Message.RollForward(_, tip)    => Vector(tip.point) ++ forward.toVector
      case Message.RollBackward(p, tip)   => Vector(p, tip.point)
      case Message.IntersectFound(p, tip) => Vector(p, tip.point)
      case Message.IntersectNotFound(tip) => Vector(tip.point)
      case _                              => Vector.empty
    if points.size > Limits().maxCandidates || !points.forall(validPoint) then
      Left("model requires bounded Cardano-width synthetic points")
    else Right(())

  def start(history: Vector[Point] = Vector(Point.Origin)): Either[String, Model] =
    if history.isEmpty || history.size > MaxHistory || history.distinct.size != history.size || !history
        .forall(validPoint)
    then Left("model requires nonempty bounded distinct synthetic history")
    else Right(new Model(State.Idle, history, Vector.empty, Vector.empty))

  /** First matching candidate in client preference order; never sorts by slot. */
  def intersect(
      candidates: Vector[Point],
      serverHistory: Vector[Point]
  ): Either[String, Option[Point]] =
    if candidates.size > Limits().maxCandidates || serverHistory.size > MaxHistory || !candidates
        .forall(validPoint) || !serverHistory.forall(validPoint)
    then Left("intersection model bound exceeded")
    else Right(candidates.find(serverHistory.contains))

  /** A finite fork script exercising both server reply paths and an explicit terminal Done. */
  def demo(): Either[String, Model] =
    def p(slot: Int, hash: Int): Point =
      Point.Block(
        UInt64.from(BigInt(slot)).toOption.get,
        lab.cbor.Bytes(Vector.fill(32)(hash.toByte))
      )
    val a = p(1, 1); val b = p(2, 2); val c = p(3, 3)
    val d = p(2, 4); val e = p(3, 5)
    val tipC = Tip(c, UInt64.from(3).toOption.get)
    val tipE = Tip(e, UInt64.from(3).toOption.get)
    for
      initial <- start(Vector(Point.Origin, a, b))
      found <- intersect(Vector(e, b, Point.Origin), Vector(Point.Origin, a, b, c))
      m1 <- initial.step(Role.Client, Message.FindIntersect(Vector(e, b, Point.Origin)))
      m2 <- m1.step(Role.Server, Message.IntersectFound(found.get, tipC))
      m3 <- m2.step(Role.Client, Message.RequestNext)
      m4 <- m3.step(Role.Server, Message.RollBackward(b, tipC))
      m5 <- m4.step(Role.Client, Message.RequestNext)
      m6 <- m5.step(Role.Server, Message.RollForward("synthetic-C", tipC), Some(c))
      m7 <- m6.step(Role.Client, Message.RequestNext)
      m8 <- m7.step(Role.Server, Message.AwaitReply)
      m9 <- m8.step(Role.Server, Message.RollBackward(a, tipE))
      m10 <- m9.step(Role.Client, Message.RequestNext)
      m11 <- m10.step(Role.Server, Message.RollForward("synthetic-D", tipE), Some(d))
      m12 <- m11.step(Role.Client, Message.RequestNext)
      m13 <- m12.step(Role.Server, Message.RollForward("synthetic-E", tipE), Some(e))
      done <- m13.step(Role.Client, Message.Done)
    yield done
