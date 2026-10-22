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
  final case class Processed[P, J](state: State[P, J], graft: Option[JumpInfo[J]])

  def slot(point: Point): BigInt = point match
    case Point.Origin           => -1
    case Point.Block(number, _) => number.value

  def register[P, J](s: State[P, J], id: P, anchor: Point, caughtUp: Boolean): State[P, J] =
    val dynamo = s.peers.find(_.role match
      case Role.Dynamo(_, _) => true
      case _                 => false)
    val role =
      if caughtUp then Role.Disengaged[J](false)
      else
        dynamo match
          case None       => Role.Dynamo[J](None, slot(anchor))
          case Some(peer) => Role.Jumping(peer.info, Jumper.Happy(true, None))
    s.copy(peers = s.peers :+ Peer(id, role, None, anchor))

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

  def onRollForward[P, J](s: State[P, J], id: P, point: Point, jumpSize: BigInt): State[P, J] =
    s.peers.find(_.id == id).fold(s) { peer =>
      peer.role match
        case Role.Objector(_, _, bad) if bad == point => disengage(s, id, false)
        case Role.Dynamo(_, last) if slot(point) > last + jumpSize =>
          peer.info.fold(s.copy(error = Some(Error.MissingDynamoJumpInfo))) { info =>
            val leader = modify(s, id)(_.copy(role = Role.Dynamo(None, slot(info.tip))))
            leader.copy(peers = leader.peers.map { p =>
              p.role match
                case Role.Jumping(_, Jumper.Happy(fresh, good)) =>
                  p.copy(role = Role.Jumping(Some(info), Jumper.Happy(fresh, good)))
                case _ => p
            })
          }
        case _ => s
    }

  def onRollback[P, J](s: State[P, J], id: P, point: Point): State[P, J] =
    s.peers.find(_.id == id).fold(s) { p =>
      p.role match
        case Role.Dynamo(_, last) if slot(point) < last          => disengage(s, id, false)
        case Role.Objector(_, _, bad) if slot(point) < slot(bad) => disengage(s, id, false)
        case _                                                   => s
    }

  def onAwaitReply[P, J](s: State[P, J], id: P): State[P, J] = disengage(s, id, false)

  def remove[P, J](s: State[P, J], id: P): State[P, J] =
    val without = s.copy(peers = s.peers.filterNot(_.id == id))
    s.peers
      .find(_.id == id)
      .fold(without)(p =>
        p.role match
          case Role.Dynamo(_, _)      => backfill(without)
          case Role.Objector(_, _, _) => electObjector(without)
          case _                      => without
      )

  def rotate[P, J](s: State[P, J], id: P): State[P, J] =
    s.peers.find(_.id == id).fold(s) { old =>
      old.role match
        case Role.Dynamo(_, _) =>
          val ordered = s.copy(peers = s.peers.filterNot(_.id == id) :+ old)
          ordered.peers.find(p => engaged(p.role)) match
            case Some(peer) if peer.id != id =>
              promote(
                modify(ordered, id)(_.copy(role = Role.Jumping(None, Jumper.Happy(true, None)))),
                peer.id
              )
            case _ => s
        case _ => s
    }

  def processJump[P, J](
      s: State[P, J],
      id: P,
      info: JumpInfo[J],
      goodPoint: Boolean,
      accepted: Boolean
  ): Processed[P, J] =
    s.peers.find(_.id == id).fold(Processed(s, Option.empty[JumpInfo[J]])) { peer =>
      def graft(state: State[P, J]): Processed[P, J] =
        Processed(modify(state, id)(_.copy(info = Some(info), tip = info.tip)), Some(info))
      peer.role match
        case Role.Dynamo(_, _) | Role.Objector(_, _, _) if goodPoint =>
          if accepted then graft(s) else Processed(disengage(s, id, true), None)
        case Role.Jumping(pending, jumper) if !goodPoint =>
          jumper match
            case Jumper.Happy(true, _) => if accepted then graft(s) else Processed(s, None)
            case Jumper.Found(_, _, _) =>
              Processed(s.copy(error = Some(Error.UnexpectedFoundIntersectionJump)), None)
            case Jumper.Happy(false, good) =>
              if accepted then
                graft(
                  modify(s, id)(
                    _.copy(role = Role.Jumping(pending, Jumper.Happy(false, Some(info))))
                  )
                )
              else Processed(search(s, id, good.getOrElse(info.prefix(0)), info), None)
            case Jumper.Looking(good, bad) =>
              if accepted then graft(search(s, id, info, bad))
              else Processed(search(s, id, good, info), None)
        case _ => Processed(s, None)
    }

  private def search[P, J](
      s: State[P, J],
      id: P,
      good: JumpInfo[J],
      bad: JumpInfo[J]
  ): State[P, J] =
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
              modify(demoted, id)(_.copy(role = Role.Objector(true, good, bad.tip)))
            case _ =>
              modify(s, id)(_.copy(role = Role.Jumping(None, Jumper.Found(true, good, bad.tip))))
        case None => modify(s, id)(_.copy(role = Role.Objector(true, good, bad.tip)))
    else
      val middle = bad.prefix(bad.headers.size - length / 2)
      modify(s, id)(_.copy(role = Role.Jumping(Some(middle), Jumper.Looking(good, bad))))

  private def engaged[J](r: Role[J]): Boolean = r match
    case Role.Disengaged(_) => false
    case _                  => true
  private def modify[P, J](s: State[P, J], id: P)(f: Peer[P, J] => Peer[P, J]): State[P, J] =
    s.copy(peers = s.peers.map(p => if p.id == id then f(p) else p))
  private def disengage[P, J](s: State[P, J], id: P, restart: Boolean): State[P, J] =
    val changed = modify(s, id)(_.copy(role = Role.Disengaged(restart), info = None))
    s.peers
      .find(_.id == id)
      .fold(changed)(p =>
        p.role match
          case Role.Dynamo(_, _)      => backfill(changed)
          case Role.Objector(_, _, _) => electObjector(changed)
          case _                      => changed
      )
  private def backfill[P, J](s: State[P, J]): State[P, J] =
    val preferred = s.peers
      .find(_.role match
        case Role.Objector(false, _, _) => true
        case _                          => false)
      .orElse(s.peers.find(p => engaged(p.role)))
    preferred.fold(s)(p => promote(s, p.id))
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
  private def electObjector[P, J](s: State[P, J]): State[P, J] =
    val found = s.peers
      .flatMap(p =>
        p.role match
          case Role.Jumping(_, Jumper.Found(starting, good, bad)) =>
            Some((p.id, starting, good, bad))
          case _ => None
      )
      .sortBy(x => slot(x._4))
      .headOption
    found.fold(s)((id, starting, good, bad) =>
      modify(s, id)(_.copy(role = Role.Objector(starting, good, bad)))
    )
