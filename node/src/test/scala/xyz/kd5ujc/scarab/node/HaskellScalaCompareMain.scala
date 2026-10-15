// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, EraLedgerState}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, LedgerFileEra}

/** Compares a Haskell `state` file of the Preview chain with a snapshot of the Scala node in the
  * same epoch, through the ledger-side mirror: the fields that are fixed for an epoch (the three
  * stake snapshots, the pool distribution, the pools and their VRF keys, the parameters) must be
  * equal where the mirror claims to derive them, and the differences are reported. Not a test: the
  * inputs are real data that is not in the repository.
  *
  * {{{
  * java -Xmx8g -cp <test classpath> xyz.kd5ujc.scarab.node.HaskellScalaCompareMain <haskell state> <scala snapshot> [genesis-dir]
  * }}}
  */
object HaskellScalaCompareMain:
  private def readScala(file: Path): Option[(Option[Long], NodeState)] =
    LegacySnapshots.read(file).toOption.map(s => (s.anchor.map(_.slot.toLong), s.state))

  /** The names of the fields in which two values of a case class differ. */
  private def fields(a: Product, b: Product): List[String] =
    a.productElementNames.toList.zipWithIndex.collect {
      case (name, i) if a.productElement(i) != b.productElement(i) => name
    }

  /** For two maps of one case class, how many entries differ in each field. */
  private def fieldHistogram[K](a: Map[K, ? <: Product], b: Map[K, ? <: Product]): String =
    val common = a.keySet.intersect(b.keySet).toVector
    val byField = common.flatMap(k => fields(a(k), b(k))).groupMapReduce(identity)(_ => 1)(_ + _)
    s"(${common.size} common entries; differing fields: ${byField.toList.sortBy(_._1).mkString(", ")})"

  private def line(what: String, same: Boolean, detail: String = ""): Unit =
    println(f"  ${if same then "equal" else "DIFFERENT"}%-10s $what $detail")

  def main(args: Array[String]): Unit =
    val setup = NodeSetup
      .fromDirectory(Path.of(args.lift(2).getOrElse("fixtures/preview/genesis")))
      .fold(e => sys.error(e.message), identity)
    val env = MirrorEnv(setup.globals.network, setup.geometry, setup.globals.updateQuorum)
    val hs = HaskellLedgerFile
      .decode(Bytes.fromArray(Files.readAllBytes(Path.of(args(0)))))
      .fold(e => sys.error(e.render), identity)
    val (slot, state) = readScala(Path.of(args(1))).getOrElse(sys.error("no snapshot"))
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val haskell = hs.ledger.state match
      case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s)) => s.nes
      case other => sys.error(s"not a Conway state: $other")
    val at =
      slot.fold[WithOrigin[SlotNo]](WithOrigin.Origin)(s => WithOrigin.At(SlotNo.fromUnsigned(s)))
    val scalaNes = MirrorCorpus.ledgerMirror.to[Conway](at, state.ledger).run(env) match
      case Left(e)  => sys.error(s"mirror refused: $e")
      case Right(m) => m.value.nes
    println(
      s"Haskell epoch ${haskell.epoch}, Scala epoch ${scalaNes.epoch} (slot ${slot.getOrElse(-1L)})"
    )
    val h = haskell.es
    val s = scalaNes.es
    val hp = LedgerState.pState[Conway].get(h.ls)
    val sp = LedgerState.pState[Conway].get(s.ls)
    line(
      "snapshots mark stake entries",
      h.snapshots.mark.activeStake == s.snapshots.mark.activeStake
    )
    line("snapshots set stake entries", h.snapshots.set.activeStake == s.snapshots.set.activeStake)
    line("snapshots go stake entries", h.snapshots.go.activeStake == s.snapshots.go.activeStake)
    def poolsExceptCount(x: SnapShot) =
      x.pools.view.mapValues(_.copy(numDelegators = Word64.zero)).toMap
    line(
      "snapshots mark pools but numDelegators",
      poolsExceptCount(h.snapshots.mark) == poolsExceptCount(s.snapshots.mark),
      fieldHistogram(h.snapshots.mark.pools.toMap, s.snapshots.mark.pools.toMap)
    )
    line(
      "snapshots set pools but numDelegators",
      poolsExceptCount(h.snapshots.set) == poolsExceptCount(s.snapshots.set),
      fieldHistogram(h.snapshots.set.pools.toMap, s.snapshots.set.pools.toMap)
    )
    line(
      "snapshots go pools but numDelegators",
      poolsExceptCount(h.snapshots.go) == poolsExceptCount(s.snapshots.go),
      fieldHistogram(h.snapshots.go.pools.toMap, s.snapshots.go.pools.toMap)
    )
    def below(a: SnapShot, b: SnapShot): String =
      val diffs = a.pools.toVector.map((id, p) =>
        p.numDelegators.toBigInt - b.pools.get(id).fold(BigInt(0))(_.numDelegators.toBigInt)
      )
      s"(Haskell above Scala in ${diffs.count(_ > 0)} pools, below in ${diffs.count(_ < 0)}, equal in ${diffs.count(_ == 0)})"
    line(
      "numDelegators set",
      h.snapshots.set.pools.forall((id, p) =>
        s.snapshots.set.pools.get(id).exists(_.numDelegators == p.numDelegators)
      ),
      below(h.snapshots.set, s.snapshots.set)
    )
    line("fee pot", h.snapshots.fee == s.snapshots.fee)
    line("nesPd", haskell.pd == scalaNes.pd)
    line("psStakePools keys", hp.stakePools.keySet == sp.stakePools.keySet)
    line(
      "psStakePools",
      hp.stakePools == sp.stakePools,
      fieldHistogram(hp.stakePools.toMap, sp.stakePools.toMap)
    )
    line(
      "psVRFKeyHashes keys",
      hp.vrfKeyHashes.keySet == sp.vrfKeyHashes.keySet,
      s"(Haskell ${hp.vrfKeyHashes.size}, Scala ${sp.vrfKeyHashes.size})"
    )
    line("psVRFKeyHashes counts", hp.vrfKeyHashes == sp.vrfKeyHashes)
    line("psFutureStakePoolParams", hp.future == sp.future)
    line("psRetiring", hp.retiring == sp.retiring)
    val hg = h.ls.utxo.gov
    val sg = s.ls.utxo.gov
    line(
      "curPParams",
      hg.curPParams == sg.curPParams,
      fields(hg.curPParams, sg.curPParams).toString
    )
    line(
      "prevPParams",
      hg.prevPParams == sg.prevPParams,
      fields(hg.prevPParams, sg.prevPParams).toString
    )
    line(
      "futurePParams",
      hg.futurePParams == sg.futurePParams,
      s"(Haskell ${hg.futurePParams.productPrefix}, Scala ${sg.futurePParams.productPrefix})"
    )
    line("chain account", h.chainAccount == s.chainAccount)
    line("dState accounts delegations", hDelegations(h) == sDelegations(s))
    hs.header.chainDep.state match
      case ChainDepState.Praos(hpraos) =>
        val sp2 = state.praos
        line("praos epoch nonce", hpraos.epochNonce == sp2.epochNonce)
        line("praos previous epoch nonce", hpraos.previousEpochNonce == sp2.previousEpochNonce)
        line("praos last epoch block nonce", hpraos.lastEpochBlockNonce == sp2.lastEpochBlock)
      case other => println(s"  chain-dependent state is not Praos: $other")

  private def hDelegations(es: EpochState[Conway]) =
    es.ls.cert.dState.accounts.states.view.mapValues(a => (a.pool, a.drep)).toMap

  private def sDelegations(es: EpochState[Conway]) = hDelegations(es)
