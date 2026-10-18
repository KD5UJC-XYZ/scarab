// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import ChainSync.*

/** Deterministic synthetic follower history. No consensus chain selection or ledger rollback. */
object FixtureChainModel:
  enum Error:
    case Protocol(cause: ProtocolError)
    case CandidateLimitExceeded
    case UnofferedIntersection
    case UnknownOrPrunedRollbackTargetReintersectionRequired
    case ModelHistoryLimitExceededReintersectionRequired
    case ForwardRequiresADistinctExplicitlySyntheticBlockPoint
    case ModelRequiresBoundedCardanoWidthSyntheticPoints
    case ModelRequiresNonemptyBoundedDistinctSyntheticHistory
    case IntersectionModelBoundExceeded
    case ModelTraceLimitExceeded

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
    ): Either[Error, Model] =
      for
        next <- transition(state, sender, message).left.map(Error.Protocol(_))
        _ <- validateMessage(message, forwardPoint)
        _ <- Either.cond(trace.size < MaxTrace, (), Error.ModelTraceLimitExceeded)
        result <- applyMessage(next, message, forwardPoint)
      yield result

    private def applyMessage[P](
        next: State,
        message: Message[P],
        forwardPoint: Option[Point]
    ): Either[Error, Model] =
      val change: Either[Error, (Vector[Point], Vector[Point], String)] = message match
        case Message.FindIntersect(points) =>
          if points.size > Limits().maxCandidates then Left(Error.CandidateLimitExceeded)
          else Right((history, points, "find-intersect"))
        case Message.IntersectFound(point, _) =>
          if !offered.contains(point) then Left(Error.UnofferedIntersection)
          else
            val i = history.indexOf(point)
            Right((if i >= 0 then history.take(i + 1) else Vector(point), Vector.empty, "intersection-found"))
        case Message.IntersectNotFound(_) => Right((history, Vector.empty, "intersection-not-found"))
        case Message.RollBackward(point, _) =>
          val i = history.indexOf(point)
          if i < 0 then Left(Error.UnknownOrPrunedRollbackTargetReintersectionRequired)
          else Right((history.take(i + 1), offered, "roll-backward"))
        case Message.RollForward(_, _) => forwardPoint match
          case Some(point @ Point.Block(_, _)) if !history.contains(point) =>
            if history.size >= MaxHistory then Left(Error.ModelHistoryLimitExceededReintersectionRequired)
            else Right((history :+ point, offered, "roll-forward"))
          case _ => Left(Error.ForwardRequiresADistinctExplicitlySyntheticBlockPoint)
        case Message.RequestNext => Right((history, offered, "request-next"))
        case Message.AwaitReply => Right((history, offered, "await-reply"))
        case Message.Done => Right((history, offered, "done"))
      change.map { case (nextHistory, nextOffered, label) =>
        new Model(next, nextHistory, nextOffered, trace :+ label)
      }

  private def validPoint(point: Point): Boolean = ChainSyncFixtures.cardanoPoint(point).isRight
  private def validateMessage[P](
      message: Message[P],
      forward: Option[Point]
  ): Either[Error, Unit] =
    val points = message match
      case Message.FindIntersect(ps)      => ps
      case Message.RollForward(_, tip)    => Vector(tip.point) ++ forward.toVector
      case Message.RollBackward(p, tip)   => Vector(p, tip.point)
      case Message.IntersectFound(p, tip) => Vector(p, tip.point)
      case Message.IntersectNotFound(tip) => Vector(tip.point)
      case _                              => Vector.empty
    if points.size > Limits().maxCandidates || !points.forall(validPoint) then
      Left(Error.ModelRequiresBoundedCardanoWidthSyntheticPoints)
    else Right(())

  def start(history: Vector[Point] = Vector(Point.Origin)): Either[Error, Model] =
    if history.isEmpty || history.size > MaxHistory || history.distinct.size != history.size || !history
        .forall(validPoint)
    then Left(Error.ModelRequiresNonemptyBoundedDistinctSyntheticHistory)
    else Right(new Model(State.Idle, history, Vector.empty, Vector.empty))

  /** First matching candidate in client preference order; never sorts by slot. */
  def intersect(
      candidates: Vector[Point],
      serverHistory: Vector[Point]
  ): Either[Error, Option[Point]] =
    if candidates.size > Limits().maxCandidates || serverHistory.size > MaxHistory || !candidates
        .forall(validPoint) || !serverHistory.forall(validPoint)
    then Left(Error.IntersectionModelBoundExceeded)
    else Right(candidates.find(serverHistory.contains))

  /** A finite fork script exercising both server reply paths and an explicit terminal Done. */
  def demo(): Either[Error, Model] =
    def p(slot: Int, hash: Int): Point =
      Point.Block(
        UInt64.saturating(BigInt(slot)),
        xyz.kd5ujc.scarab.codec.Bytes(Vector.fill(32)(hash.toByte))
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
