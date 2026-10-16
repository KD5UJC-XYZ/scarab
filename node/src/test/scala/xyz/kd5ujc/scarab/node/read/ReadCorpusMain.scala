// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.node.{LegacySnapshots, MirrorRun, NodeSetup, Projection}
import xyz.kd5ujc.scarab.node.ntc.{
  EraHistory,
  NtcGolden,
  NtcQuery,
  QueryAnswers,
  QueryEnv,
  QueryView
}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.FileLedgerSnapshots

/** The two readings of the real states of a Preview sync (one snapshot per epoch boundary, the
  * corpus of `PreviewMirrorMain`): of each snapshot, the K1 projection and the answers to the
  * ledger queries (`NtcGolden.battery`) from the node's flat state and from its mirror. Not a test:
  * the input is data that is not in the repository.
  *
  * {{{
  * java -Xmx16g -cp <test classpath> xyz.kd5ujc.scarab.node.read.ReadCorpusMain <snapshots> [stride] [genesis-dir] [offset]
  * }}}
  *
  * Exit status 1 if any projection or any answer differs.
  */
object ReadCorpusMain:
  private def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  def main(args: Array[String]): Unit =
    val dir = Path.of(args(0))
    val stride = args.lift(1).fold(1)(_.toInt)
    val offset = args.lift(3).fold(0)(_.toInt)
    val setup = NodeSetup
      .fromDirectory(Path.of(args.lift(2).getOrElse("fixtures/preview/genesis")))
      .fold(e => sys.error(e.message), identity)
    val env = setup.mirrorEnv()
    val queryEnv = QueryEnv(
      setup.byronParams
        .flatMap(_.startTime)
        .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
      setup.geometry,
      setup.praos.forks,
      setup.byronParams.fold(EraHistory.ByronParams(50, 1000, 5))(b =>
        EraHistory.ByronParams(b.epochSlots, BigInt(b.slotLengthMillis), BigInt(b.securityParam))
      ),
      setup.globals.maxSupply,
      setup.genesis.raw,
      env.ledger
    )
    val flat: ChainRead[NodeState] = ChainRead.nodeState(env)
    val viaMirror: ChainRead[MirrorState] = summon[ChainRead[MirrorState]]
    val files = Files
      .list(dir)
      .iterator
      .asScala
      .filter(f => FileLedgerSnapshots.isSnapshot(f.getFileName.toString))
      .toVector
      .sortBy(_.getFileName.toString)
      .zipWithIndex
      .collect { case (f, i) if i % stride == offset => f }
    var projections, answered, failures = 0
    files.foreach { file =>
      LegacySnapshots.read(file, env.forks) match
        case Left(e) => println(s"${file.getFileName}: unreadable, $e"); failures += 1
        case Right(direct) =>
          val state = direct.state
          val anchor: Option[BlockRef] = direct.anchor
          val label = s"${file.getFileName} epoch ${state.ledger.epoch}"
          val point =
            anchor.fold[Point](Point.Origin)(a => Point.Block(UInt64.saturating(a.slot), a.hash))
          val blockNo = anchor.map(_.blockNo)
          MirrorRun.run(env)(_.toMirror(anchor, state)) match
            case Left(e) => println(s"$label: not mirrored: $e"); failures += 1
            case Right(mirrored) =>
              val mirror = mirrored.value
              val utxo = xyz.kd5ujc.scarab.ledger.Utxo.empty
              // the projection
              val (f, m) = (
                Projection.node(point, state, utxo)(using flat),
                Projection.node(point, mirror, utxo)(using viaMirror)
              )
              (f, m) match
                case (Right(a), Right(b)) =>
                  projections += 1
                  ProjectionDiff.firstDifferenceOfMirror(a, b).foreach { d =>
                    println(s"$label: projection differs at $d"); failures += 1
                  }
                  if projections % 25 == 1 then println(s"$label: projection ${sha256(a.noSpaces)}")
                case other => println(s"$label: projection refused $other"); failures += 1
              // the answers, in the Conway era
              if flat.era(state) == Era.Conway then
                NtcGolden.battery(state.ledger).foreach { (name, q) =>
                  val query = NtcQuery.IfCurrent(Era.Conway.index, q)
                  val a = QueryAnswers.answerOrWhy(
                    queryEnv,
                    QueryView(point, blockNo, state)(using flat),
                    Map.empty,
                    query
                  )
                  val b = QueryAnswers.answerOrWhy(
                    queryEnv,
                    QueryView(point, blockNo, mirror)(using viaMirror),
                    Map.empty,
                    query
                  )
                  if a != b then
                    val shown = (x: Either[?, Bytes]) => x.fold(_.toString, bs => sha256(bs.hex))
                    println(s"$label: $name differs: ${shown(a)} / ${shown(b)}"); failures += 1
                  else answered += 1
                }
    }
    println(
      s"${files.size} snapshots, $projections projections compared, $answered answers compared, $failures differences"
    )
    if failures > 0 then sys.exit(1)
