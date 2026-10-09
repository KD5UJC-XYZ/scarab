// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosCertificateState as Certificate
import lab.network.ChainSync
import scala.util.control.NonFatal

/** Pure certificate state paired with the existing byte-checked acquisition branch. It is not a
  * consensus branch. Callers publish the returned branch only after every required stage succeeds.
  */
object CertificateBranch:
  final class Branch private[CertificateBranch] (
      val acquisition: BoundedChainFollower.Checkpoint,
      val initial: Certificate.State,
      val state: Certificate.State,
      val steps: Vector[Certificate.Applied]
  )
  final case class Prepared(
      context: Certificate.Context,
      seed: Certificate.State,
      acquisition: BoundedChainFollower.Checkpoint
  )
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def point(p: Certificate.Point): ChainSync.Point =
    ChainSync.Point.Block(get(ChainSync.UInt64.from(p.slot)), p.hash)

  def replay(
      context: Certificate.Context,
      seed: Certificate.State,
      acquisition: BoundedChainFollower.Checkpoint
  ): Either[String, Branch] = checked {
    require(
      seed.contextId == context.id && point(seed.tip) == acquisition.anchor,
      "certificate seed/acquisition anchor mismatch"
    )
    val steps = acquisition.originals.foldLeft(Vector.empty[Certificate.Applied]) {
      (done, original) =>
        val previous = done.lastOption.fold(seed)(_.after)
        val header = get(ReferenceCaptureCommand.header(original.envelope))
        done :+ get(Certificate.applyHeader(context, previous, header.raw, header.hash))
    }
    new Branch(acquisition, seed, steps.lastOption.fold(seed)(_.after), steps)
  }

  def append(
      context: Certificate.Context,
      branch: Branch,
      original: BoundedChainFollower.Original
  ): Either[String, Branch] =
    for
      acquisition <- BoundedChainFollower.checked(
        branch.acquisition.anchor,
        branch.acquisition.originals :+ original
      )
      next <- replay(context, branch.initial, acquisition)
    yield next

  def rollback(branch: Branch, to: ChainSync.Point): Either[String, Branch] = checked {
    val keep =
      if to == branch.acquisition.anchor then 0
      else branch.steps.indexWhere(s => point(s.after.tip) == to) + 1
    require(keep > 0 || to == branch.acquisition.anchor, "rollback outside certificate window")
    val restored = branch.steps.drop(keep).reverse.foldLeft(branch.state) { (current, applied) =>
      get(Certificate.undo(current, applied))
    }
    val acquisition = get(
      BoundedChainFollower.checked(
        branch.acquisition.anchor,
        branch.acquisition.originals.take(keep)
      )
    )
    new Branch(acquisition, branch.initial, restored, branch.steps.take(keep))
  }

  /** Discard only a checked prefix; never manufacture a new supplied certificate seed. */
  private[lab] def advanceAnchor(branch: Branch, to: ChainSync.Point): Either[String, Branch] =
    checked {
      val drop =
        if to == branch.acquisition.anchor then 0
        else branch.steps.indexWhere(s => point(s.after.tip) == to) + 1
      require(drop > 0 || to == branch.acquisition.anchor, "anchor outside certificate window")
      val initial = if drop == 0 then branch.initial else branch.steps(drop - 1).after
      val acquisition =
        get(BoundedChainFollower.checked(to, branch.acquisition.originals.drop(drop)))
      new Branch(acquisition, initial, branch.state, branch.steps.drop(drop))
    }

  private def read(path: Path): Bytes =
    val in = Files.newInputStream(path)
    val raw =
      try in.readNBytes(4194305)
      finally in.close()
    require(raw.length <= 4194304, "counter evidence exceeds bound")
    Bytes.fromArray(raw)
  private def source(raw: Bytes, digest: Bytes): ReferenceJson.Json =
    require(
      raw != null && raw.value != null && raw.size <= 4194304 &&
        digest != null && digest.value != null && digest.size == 32,
      "bounded counter source required"
    )
    require(ClusterHeaderObservation.sha256(raw) == digest, "counter source digest mismatch")
    ReferenceJson.parse(raw)
  private def hash(s: String, size: Int): Bytes =
    val b = get(Bytes.fromHex(s))
    require(b.size == size && b.hex == s, "canonical counter context hash required")
    b
  private def obj(j: ReferenceJson.Json): Map[String, ReferenceJson.Json] = j match
    case ReferenceJson.Json.Obj(fields) => fields
    case _ => throw new IllegalArgumentException("counter context object required")

  /** Explicit pinned protocol-state export is mandatory: lastSlot alone cannot authenticate an
    * export's block hash. Existing stable-bracket limitations remain. Nothing seeds missing state.
    */
  def loadTransfer(
      directory: Path,
      protocolState: Bytes,
      protocolStateSha256: Bytes
  ): Either[String, Prepared] = checked {
    val input = ClusterTransferCommand.load(directory)
    val manifest = read(directory.resolve("transfer-context.md"))
    require(ClusterHeaderObservation.sha256(manifest) == input.manifestDigest, "manifest changed")
    // Projection from bytes already validated by the existing strict manifest loader; no new parser.
    val ledgerHash = new String(manifest.toArray, "UTF-8").linesIterator
      .find(_.startsWith("preLedgerSha256\t"))
      .get
      .split("\t", -1)(1)
    val genesisRaw = read(directory.resolve("transfer-genesis.md"))
    val timing = ClusterHeaderObservation.bindGenesis(genesisRaw, input.context)
    val genesis = ReferenceJson.parse(genesisRaw)
    val ledger = source(read(directory.resolve("pre-ledger-state.md")), hash(ledgerHash, 32))
    val protocol = source(protocolState, protocolStateSha256)
    import ReferenceJson.{field, uint, string, array}
    require(
      uint(field(protocol, "lastSlot")) == input.context.preSlot,
      "counter snapshot slot mismatch"
    )
    require(uint(field(ledger, "lastEpoch")) == input.context.epoch, "registration epoch mismatch")
    val epochLength = uint(field(genesis, "epochLength"))
    require(
      epochLength > 0 && input.context.preSlot / epochLength == input.context.epoch &&
        input.context.postSlot / epochLength == input.context.epoch,
      "unsupported epoch slot translation"
    )
    val pools = obj(field(ledger, "stakeDistrib", "unPoolDistr")).map { (id, pool) =>
      hash(id, 28) -> hash(string(field(pool, "individualPoolStakeVrf")), 32)
    }
    val context = get(
      Certificate.Context.checked(
        input.context.genesisDigest,
        hash(ledgerHash, 32),
        input.context.epoch * epochLength,
        (input.context.epoch + 1) * epochLength - 1,
        timing.slotsPerKesPeriod,
        timing.maxKesEvolutions,
        pools
      )
    )
    val counters = obj(field(protocol, "oCertCounters")).map((id, n) => hash(id, 28) -> uint(n))
    val tipsRaw = read(directory.resolve("pre-tips.md"))
    val tipsHash = new String(manifest.toArray, "UTF-8").linesIterator
      .find(_.startsWith("preTipsSha256\t"))
      .get
      .split("\t", -1)(1)
    val tip = array(source(tipsRaw, hash(tipsHash, 32))).head
    val anchor =
      Certificate.Point(input.context.preHash, input.context.preSlot, uint(field(tip, "block")))
    val seed = get(Certificate.seed(context, anchor, counters, protocolStateSha256))
    val captures = get(ClusterHeaderObservation.captures(directory.resolve("scala-transfer.md")))
    val acquisition = get(
      BoundedChainFollower.checked(
        input.prePoint,
        captures.map(c => BoundedChainFollower.Original(c.headerEnvelope, c.block))
      )
    )
    require(acquisition.tip == input.postPoint, "counter capture endpoint mismatch")
    Prepared(context, seed, acquisition)
  }
