// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Pure transition system for consensus 82ecba329 ChainSync/Client/Jumping.hs. Payloads retain
  * validated candidate/header-state snapshots; an accepted instruction is grafted by the owner.
  */
object ChainSyncJumping:
  final case class JumpInfo[+J](anchor: Point, headers: Vector[Point], payload: J):
    def tip: Point = headers.lastOption.getOrElse(anchor)
    def prefix(length: Int): JumpInfo[J] = copy(headers = headers.take(length))
  enum Jumper[J]:
    case Happy(fresh: Boolean, good: Option[JumpInfo[J]])
    case Looking(good: JumpInfo[J], bad: JumpInfo[J])
    case Found(starting: Boolean, good: JumpInfo[J], bad: Point)
  enum Role[J]:
    case Dynamo(starting: Option[JumpInfo[J]], lastJumpSlot: BigInt)
    case Objector(starting: Boolean, good: JumpInfo[J], bad: Point)
    case Jumping(pending: Option[JumpInfo[J]], state: Jumper[J])
    case Disengaged(restart: Boolean)
  final case class Peer[P, J](id: P, role: Role[J], info: Option[JumpInfo[J]], tip: Point)
  enum Error:
    case MissingDynamoJumpInfo, UnexpectedFoundIntersectionJump
  final case class State[P, J](
      peers: Vector[Peer[P, J]] = Vector.empty[Peer[P, J]],
      error: Option[Error] = None
  )
  enum Instruction[+J]:
    case RunNormally, Restart, Wait
    case Failed(error: Error)
    case Jump(info: JumpInfo[J], goodPoint: Boolean)
  final case class Next[P, J](state: State[P, J], instruction: Instruction[J])
  final case class Processed[P, J](
      state: State[P, J],
      graft: Option[JumpInfo[J]],
      event: Option[Event[P]]
  )

  /** Why a dynamo or an objector lost its role: `TraceCsjReason` (consensus 82ecba329, consensus
    * 82ecba329, Jumping.hs:1096-1098).
    */
  enum Reason:
    case Disengage, Disconnect

  /** What a transition of one peer's handle reports: `TraceEventCsj` (consensus 82ecba329,
    * consensus 82ecba329, Jumping.hs:1083-1094), traced under `Consensus.CSJ` labelled with that
    * peer. `BlockedOnJump` is traced by the runtime, which is what blocks (consensus 82ecba329,
    * Jumping.hs:296-304).
    */
  enum Event[+P]:
    case BecomingObjector(previousObjector: Option[P])
    case BlockedOnJump
    case InitializedAsDynamo
    case NoLongerDynamo(newDynamo: Option[P], reason: Reason)
    case NoLongerObjector(newObjector: Option[P], reason: Reason)
    case SentJumpInstruction(jumpTarget: Point)

    /** The event with each peer it names as `f` names it. */
    def map[Q](f: P => Q): Event[Q] = this match
      case BecomingObjector(previous)     => BecomingObjector(previous.map(f))
      case BlockedOnJump                  => BlockedOnJump
      case InitializedAsDynamo            => InitializedAsDynamo
      case NoLongerDynamo(next, reason)   => NoLongerDynamo(next.map(f), reason)
      case NoLongerObjector(next, reason) => NoLongerObjector(next.map(f), reason)
      case SentJumpInstruction(target)    => SentJumpInstruction(target)

  /** The rotation of the dynamo, `TraceEventDbf` (consensus 82ecba329, Jumping.hs:1078-1080),
    * traced under `Consensus.DevotedBlockFetch`.
    */
  final case class RotatedDynamo[P](oldPeer: P, newPeer: P):
    def map[Q](f: P => Q): RotatedDynamo[Q] = RotatedDynamo(f(oldPeer), f(newPeer))

  /** A transition's new state and what it reports, as the reference's STM transitions return the
    * trace event they cause with the state they write (consensus 82ecba329, Jumping.hs:312,
    * 943-947).
    */
  final case class Step[P, J, +E](state: State[P, J], event: Option[E])
  private def quiet[P, J](s: State[P, J]): Step[P, J, Nothing] = Step(s, None)

  def slot(point: Point): BigInt = point match
    case Point.Origin           => -1
    case Point.Block(number, _) => number.value

  /** `registerClient` and `engageClient` (consensus 82ecba329, Jumping.hs:842-884): the first
    * engaged peer becomes the dynamo, which it reports.
    */
  def register[P, J](
      s: State[P, J],
      id: P,
      anchor: Point,
      caughtUp: Boolean
  ): Step[P, J, Event[P]] =
    val dynamo = s.peers.find(_.role match
      case Role.Dynamo(_, _) => true
      case _                 => false)
    val (role, event) =
      if caughtUp then (Role.Disengaged[J](false), None)
      else
        dynamo match
          case None       => (Role.Dynamo[J](None, slot(anchor)), Some(Event.InitializedAsDynamo))
          case Some(peer) => (Role.Jumping(peer.info, Jumper.Happy(true, None)), None)
    Step(s.copy(peers = s.peers :+ Peer(id, role, None, anchor)), event)

  def update[P, J](s: State[P, J], id: P, info: JumpInfo[J]): State[P, J] =
    modify(s, id)(p =>
      p.role match
        case Role.Disengaged(_) => p
        case _                  => p.copy(info = Some(info), tip = info.tip)
    )

  def next[P, J](s: State[P, J], id: P): Next[P, J] =
    if s.error.isDefined then
      Next(s, Instruction.Failed(s.error.getOrElse(Error.MissingDynamoJumpInfo)))
    else
      s.peers.find(_.id == id) match
        case None => Next(s, Instruction.Wait)
        case Some(peer) =>
          peer.role match
            case Role.Disengaged(true) =>
              Next(modify(s, id)(_.copy(role = Role.Disengaged(false))), Instruction.Restart)
            case Role.Disengaged(false) | Role.Dynamo(None, _) | Role.Objector(false, _, _) =>
              Next(s, Instruction.RunNormally)
            case Role.Dynamo(Some(info), last) =>
              Next(
                modify(s, id)(_.copy(role = Role.Dynamo(None, last))),
                Instruction.Jump(info, true)
              )
            case Role.Objector(true, good, bad) =>
              Next(
                modify(s, id)(_.copy(role = Role.Objector(false, good, bad))),
                Instruction.Jump(good, true)
              )
            case Role.Jumping(None, _) => Next(s, Instruction.Wait)
            case Role.Jumping(Some(info), jumper) =>
              val started = jumper match
                case Jumper.Happy(true, good) => Jumper.Happy(false, good)
                case other                    => other
              Next(
                modify(s, id)(_.copy(role = Role.Jumping(None, started))),
                Instruction.Jump(info, false)
              )

  /** `onRollForward` (consensus 82ecba329, Jumping.hs:471-511): an objector that agrees with its
    * rejected jump is disengaged; a dynamo past the jump size sends its jumpers the jump, which it
    * reports with its target.
    */
  def onRollForward[P, J](
      s: State[P, J],
      id: P,
      point: Point,
      jumpSize: BigInt
  ): Step[P, J, Event[P]] =
    s.peers.find(_.id == id).fold(quiet(s)) { peer =>
      peer.role match
        case Role.Objector(_, _, bad) if bad == point => disengage(s, id, false, Reason.Disengage)
        case Role.Dynamo(_, last) if slot(point) > last + jumpSize =>
          peer.info.fold(quiet(s.copy(error = Some(Error.MissingDynamoJumpInfo)))) { info =>
            val leader = modify(s, id)(_.copy(role = Role.Dynamo(None, slot(info.tip))))
            Step(
              leader.copy(peers = leader.peers.map { p =>
                p.role match
                  case Role.Jumping(_, Jumper.Happy(fresh, good)) =>
                    p.copy(role = Role.Jumping(Some(info), Jumper.Happy(fresh, good)))
                  case _ => p
              }),
              Some(Event.SentJumpInstruction(info.tip))
            )
          }
        case _ => quiet(s)
    }

  /** `onRollBackward` (consensus 82ecba329, Jumping.hs:518-540). */
  def onRollback[P, J](s: State[P, J], id: P, point: Point): Step[P, J, Event[P]] =
    s.peers.find(_.id == id).fold(quiet(s)) { p =>
      p.role match
        case Role.Dynamo(_, last) if slot(point) < last => disengage(s, id, false, Reason.Disengage)
        case Role.Objector(_, _, bad) if slot(point) < slot(bad) =>
          disengage(s, id, false, Reason.Disengage)
        case _ => quiet(s)
    }

  /** `onAwaitReply` (consensus 82ecba329, Jumping.hs:546-566). */
  def onAwaitReply[P, J](s: State[P, J], id: P): Step[P, J, Event[P]] =
    disengage(s, id, false, Reason.Disengage)

  /** `unregisterClient` (consensus 82ecba329, Jumping.hs:886-900): a dynamo or an objector that
    * disconnects is replaced, and it reports its successor.
    */
  def remove[P, J](s: State[P, J], id: P): Step[P, J, Event[P]] =
    val without = s.copy(peers = s.peers.filterNot(_.id == id))
    s.peers
      .find(_.id == id)
      .fold(quiet(without))(p =>
        p.role match
          case Role.Dynamo(_, _) =>
            val (after, dynamo) = backfill(without)
            Step(after, Some(Event.NoLongerDynamo(dynamo, Reason.Disconnect)))
          case Role.Objector(_, _, _) =>
            val (after, objector) = electObjector(without)
            Step(after, Some(Event.NoLongerObjector(objector, Reason.Disconnect)))
          case _ => quiet(without)
      )

  /** `rotateDynamo` (consensus 82ecba329, Jumping.hs:908-947): the dynamo goes to the end of the
    * queue and the first other engaged peer replaces it, which it reports.
    */
  def rotate[P, J](s: State[P, J], id: P): Step[P, J, RotatedDynamo[P]] =
    s.peers.find(_.id == id).fold(quiet(s)) { old =>
      old.role match
        case Role.Dynamo(_, _) =>
          val ordered = s.copy(peers = s.peers.filterNot(_.id == id) :+ old)
          ordered.peers.find(p => engaged(p.role)) match
            case Some(peer) if peer.id != id =>
              Step(
                promote(
                  modify(ordered, id)(_.copy(role = Role.Jumping(None, Jumper.Happy(true, None)))),
                  peer.id
                ),
                Some(RotatedDynamo(id, peer.id))
              )
            case _ => quiet(s)
        case _ => quiet(s)
    }

  def processJump[P, J](
      s: State[P, J],
      id: P,
      info: JumpInfo[J],
      goodPoint: Boolean,
      accepted: Boolean
  ): Processed[P, J] =
    s.peers.find(_.id == id).fold(Processed(s, Option.empty[JumpInfo[J]], None)) { peer =>
      def graft(step: Step[P, J, Event[P]]): Processed[P, J] =
        Processed(
          modify(step.state, id)(_.copy(info = Some(info), tip = info.tip)),
          Some(info),
          step.event
        )
      def refused(step: Step[P, J, Event[P]]): Processed[P, J] =
        Processed(step.state, None, step.event)
      peer.role match
        case Role.Dynamo(_, _) | Role.Objector(_, _, _) if goodPoint =>
          if accepted then graft(quiet(s))
          else refused(disengage(s, id, true, Reason.Disengage))
        case Role.Jumping(pending, jumper) if !goodPoint =>
          jumper match
            case Jumper.Happy(true, _) =>
              if accepted then graft(quiet(s)) else refused(quiet(s))
            case Jumper.Found(_, _, _) =>
              refused(quiet(s.copy(error = Some(Error.UnexpectedFoundIntersectionJump))))
            case Jumper.Happy(false, good) =>
              if accepted then
                graft(
                  quiet(
                    modify(s, id)(
                      _.copy(role = Role.Jumping(pending, Jumper.Happy(false, Some(info))))
                    )
                  )
                )
              else refused(search(s, id, good.getOrElse(info.prefix(0)), info))
            case Jumper.Looking(good, bad) =>
              if accepted then graft(search(s, id, info, bad))
              else refused(search(s, id, good, info))
        case _ => refused(quiet(s))
    }

  /** `lookForIntersection` and `maybeElectNewObjector` (consensus 82ecba329, Jumping.hs:688-735): a
    * jumper that has found where it disagrees with the dynamo becomes the objector when it has
    * none, or when its disagreement is older than the objector's, and reports the objector it
    * replaced.
    */
  private def search[P, J](
      s: State[P, J],
      id: P,
      good: JumpInfo[J],
      bad: JumpInfo[J]
  ): Step[P, J, Event[P]] =
    val index = bad.headers.indexOf(good.tip)
    val length =
      if good.tip == bad.anchor then bad.headers.size
      else if index >= 0 then bad.headers.size - index - 1
      else bad.headers.size
    if length <= 1 then
      val previous = s.peers.find(_.role match
        case Role.Objector(_, _, _) => true
        case _                      => false)
      previous match
        case Some(peer) =>
          peer.role match
            case Role.Objector(starting, oldGood, oldBad) if slot(oldBad) > slot(bad.tip) =>
              val demoted = modify(s, peer.id)(
                _.copy(role = Role.Jumping(None, Jumper.Found(starting, oldGood, oldBad)))
              )
              Step(
                modify(demoted, id)(_.copy(role = Role.Objector(true, good, bad.tip))),
                Some(Event.BecomingObjector(Some(peer.id)))
              )
            case _ =>
              quiet(
                modify(s, id)(_.copy(role = Role.Jumping(None, Jumper.Found(true, good, bad.tip))))
              )
        case None =>
          Step(
            modify(s, id)(_.copy(role = Role.Objector(true, good, bad.tip))),
            Some(Event.BecomingObjector(None))
          )
    else
      val middle = bad.prefix(bad.headers.size - length / 2)
      quiet(modify(s, id)(_.copy(role = Role.Jumping(Some(middle), Jumper.Looking(good, bad)))))

  private def engaged[J](r: Role[J]): Boolean = r match
    case Role.Disengaged(_) => false
    case _                  => true
  private def modify[P, J](s: State[P, J], id: P)(f: Peer[P, J] => Peer[P, J]): State[P, J] =
    s.copy(peers = s.peers.map(p => if p.id == id then f(p) else p))

  /** `disengage`, then the election of a new dynamo or objector if the peer was one, which it
    * reports with `reason` (consensus 82ecba329, the callers of `disengage` and `startDisengaging`
    * in Jumping.hs:471-605).
    */
  private def disengage[P, J](
      s: State[P, J],
      id: P,
      restart: Boolean,
      reason: Reason
  ): Step[P, J, Event[P]] =
    val changed = modify(s, id)(_.copy(role = Role.Disengaged(restart), info = None))
    s.peers
      .find(_.id == id)
      .fold(quiet(changed))(p =>
        p.role match
          case Role.Dynamo(_, _) =>
            val (after, dynamo) = backfill(changed)
            Step(after, Some(Event.NoLongerDynamo(dynamo, reason)))
          case Role.Objector(_, _, _) =>
            val (after, objector) = electObjector(changed)
            Step(after, Some(Event.NoLongerObjector(objector, reason)))
          case _ => quiet(changed)
      )

  /** `backfillDynamo` (consensus 82ecba329, Jumping.hs:957-977): the new dynamo, if any engaged
    * peer is left.
    */
  private def backfill[P, J](s: State[P, J]): (State[P, J], Option[P]) =
    val preferred = s.peers
      .find(_.role match
        case Role.Objector(false, _, _) => true
        case _                          => false)
      .orElse(s.peers.find(p => engaged(p.role)))
    (preferred.fold(s)(p => promote(s, p.id)), preferred.map(_.id))
  private def promote[P, J](s: State[P, J], id: P): State[P, J] =
    s.peers.find(_.id == id).fold(s) { leader =>
      s.copy(peers = s.peers.map { p =>
        if p.id == id then
          val role = p.role match
            case Role.Objector(false, good, _) => Role.Dynamo[J](None, slot(good.tip))
            case _                             => Role.Dynamo(p.info, slot(p.tip))
          p.copy(role = role)
        else if engaged(p.role) then
          p.copy(role = Role.Jumping(leader.info, Jumper.Happy(true, None)))
        else p
      })
    }

  /** `electNewObjector` (consensus 82ecba329, Jumping.hs:1053-1075): the new objector, if a jumper
    * has found its intersection.
    */
  private def electObjector[P, J](s: State[P, J]): (State[P, J], Option[P]) =
    val found = s.peers
      .flatMap(p =>
        p.role match
          case Role.Jumping(_, Jumper.Found(starting, good, bad)) =>
            Some((p.id, starting, good, bad))
          case _ => None
      )
      .sortBy(x => slot(x._4))
      .headOption
    (
      found.fold(s)((id, starting, good, bad) =>
        modify(s, id)(_.copy(role = Role.Objector(starting, good, bad)))
      ),
      found.map(_._1)
    )
