// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.storage.{RestrictedLedgerImage as L, RestrictedStakeImage as S}
import lab.network.ChainSync
import lab.plutus.PlutusExecution
import scala.util.control.NonFatal

/** Partial component export from one immutable coherent snapshot. No restore capability. */
object CoherentStakeImages:
  final case class Sources(genesis: Bytes, parameters: Bytes, manifest: Bytes):
    def rows: Map[String, Bytes] =
      Map("genesis" -> genesis, "parameters" -> parameters, "manifest" -> manifest)

  /** Values must be independently authenticated by the acquisition/controller boundary. */
  final case class SourceBinding(
      contextId: Bytes,
      sourceJoinId: Bytes,
      stakeSourceId: Bytes,
      genesisSHA256: Bytes,
      parametersSHA256: Bytes,
      manifestSHA256: Bytes
  )
  final case class Expected(
      source: SourceBinding,
      ledger: L.Binding,
      stake: S.Binding,
      stateId: Bytes,
      revision: BigInt,
      certificateId: Bytes,
      nonceId: Bytes,
      eligibilityId: Option[Bytes],
      ledgerSHA256: Bytes,
      stakeSHA256: Bytes
  )
  final class Publication private[CoherentStakeImages] (
      val ledger: Bytes,
      val stake: Bytes,
      val expected: Expected
  ):
    val publicationSHA256: Bytes = bindingDigest(expected)
    val completeValidatorState = false
    val restoreAuthorized = false
    val currentOwnerAuthorized = false
  final class VerifiedComponents private[CoherentStakeImages] (
      val ledger: L.UntrustedImage,
      val stake: S.Untrusted
  ):
    val completeValidatorState = false
    val restoreAuthorized = false
    val currentOwnerAuthorized = false
  private def checked[A](f: => A): Either[String, A] =
    try Right(f)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid coherent images"))
  private def get[A](e: Either[String, A]): A =
    e.fold(x => throw new IllegalArgumentException(x), identity)
  private def hash(b: Bytes): Unit =
    require(b != null && b.value != null && b.size == 32, "32-byte binding required")
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def source(context: SequenceInput.Context, s: SourceBinding): Unit =
    require(context != null && s != null, "checked source required")
    Vector(
      s.contextId,
      s.sourceJoinId,
      s.stakeSourceId,
      s.genesisSHA256,
      s.parametersSHA256,
      s.manifestSHA256
    ).foreach(hash)
    require(
      context.diagnosticOnly && context.id == s.contextId && context.stakeSourceId == s.stakeSourceId &&
        context.id == S.sha256(
          raw("native-sequence-diagnostic-context-v1\n" + s.sourceJoinId.hex + "\n")
        ) &&
        context.ledger.environment.genesisDigest == s.genesisSHA256 &&
        context.ledger.environment.parameterDigest == s.parametersSHA256,
      "checked diagnostic source binding mismatch"
    )
    require(
      context.epoch == 0 && context.ledger.profileId == PlutusExecution.ProfileId &&
        context.ledger.environment.plutus.exists(_.networkId == 0),
      "restricted epoch-zero Plutus profile required"
    )
  private def validateExpected(e: Expected): Unit =
    val max = (BigInt(1) << 64) - 1
    def word(n: BigInt): Boolean = n != null && n >= 0 && n <= max
    require(
      e != null && e.source != null && e.ledger != null && e.stake != null && e.eligibilityId != null,
      "expected shape: complete descriptor required"
    )
    val source = e.source; val l = e.ledger; val t = e.stake
    Vector(
      source.contextId,
      source.sourceJoinId,
      source.stakeSourceId,
      source.genesisSHA256,
      source.parametersSHA256,
      source.manifestSHA256,
      e.stateId,
      e.certificateId,
      e.nonceId,
      e.ledgerSHA256,
      e.stakeSHA256,
      l.environmentId,
      t.ledgerImageSHA256,
      t.sourceJoinId,
      t.stakeSourceId,
      t.headerHash
    ).foreach(hash)
    e.eligibilityId.foreach(hash)
    require(
      word(e.revision) && l.profile == PlutusExecution.ProfileId && l.epoch == BigInt(0) &&
        word(l.networkMagic) && l.networkMagic > 0 && l.networkMagic <= 0xffffffffL &&
        !Set(BigInt(1), BigInt(2), BigInt(764824073)).contains(l.networkMagic),
      "expected shape: profile/epoch/revision/network"
    )
    require(
      l.point != null && word(l.point.slot) && l.point.slot > 0 && l.point.slot < 1000 &&
        word(l.point.blockNo) && word(t.slot) && t.slot > 0 && t.slot < 1000 && word(t.blockNo),
      "expected shape: full point bounds"
    )
    hash(l.point.hash)
    require(
      l.sourcePins != null && l.sourcePins.size == 3 &&
        l.sourcePins.keySet == Set("genesis", "parameters", "manifest"),
      "expected shape: exact source domain"
    )
    l.sourcePins.values.foreach(hash)

  private def bindingDigest(e: Expected): Bytes =
    val s = e.source; val l = e.ledger; val t = e.stake
    val fields = Vector(
      "coherent-stake-component-pair-v1",
      s.contextId.hex,
      s.sourceJoinId.hex,
      s.stakeSourceId.hex,
      s.genesisSHA256.hex,
      s.parametersSHA256.hex,
      s.manifestSHA256.hex,
      l.profile,
      l.networkMagic.toString,
      l.epoch.toString,
      l.point.slot.toString,
      l.point.blockNo.toString,
      l.point.hash.hex,
      l.environmentId.hex,
      l.sourcePins("genesis").hex,
      l.sourcePins("parameters").hex,
      l.sourcePins("manifest").hex,
      t.ledgerImageSHA256.hex,
      t.sourceJoinId.hex,
      t.stakeSourceId.hex,
      t.slot.toString,
      t.blockNo.toString,
      t.headerHash.hex,
      e.stateId.hex,
      e.revision.toString,
      e.certificateId.hex,
      e.nonceId.hex,
      e.eligibilityId.fold("absent")(_.hex),
      e.ledgerSHA256.hex,
      e.stakeSHA256.hex
    )
    S.sha256(raw(fields.mkString("\n") + "\n"))

  def encodeSnapshot(
      snapshot: CoherentSequence.Snapshot,
      context: SequenceInput.Context,
      originals: Sources,
      expectedSource: SourceBinding
  ): Either[String, Publication] = checked {
    require(snapshot != null && originals != null, "single owned snapshot and sources required")
    val s = snapshot.state
    require(
      s.supportsRestrictedImageExport,
      "reward/epoch/boundary composition has no complete image"
    )
    source(context, expectedSource)
    require(
      s.contextId == context.id && s.ledger.environment.id == context.ledger.environment.id &&
        s.ledger.profileId == PlutusExecution.ProfileId && s.ledger.environment.epoch == 0,
      "snapshot/source environment mismatch"
    )
    val stake =
      s.stake.getOrElse(throw new IllegalArgumentException("complete stake projection required"))
    val tip = s.certificates.state.tip
    val point = L.Point(tip.slot, tip.blockNo, tip.hash)
    require(
      s.acquisition.tip == ChainSync.Point.Block(get(ChainSync.UInt64.from(tip.slot)), tip.hash) &&
        tip.slot == s.ledger.slot && s.nonces.lastSlot == tip.slot &&
        s.nonces.certificateStateId == s.certificates.state.id &&
        stake.ledgerId == s.ledger.id && stake.revision == s.revision && stake.slot == tip.slot && stake.epoch == 0,
      "single coherent tuple point/identity mismatch"
    )
    require(s.depth == 0 || s.eligibility.nonEmpty, "applied tuple requires checked eligibility")
    L.SourceLimits.foreach { (name, limit) =>
      val bytes = originals.rows(name)
      require(
        bytes != null && bytes.value != null && bytes.size > 0 && bytes.size <= limit,
        "source byte bound"
      )
    }
    val pins = originals.rows.map((name, bytes) => name -> S.sha256(bytes))
    require(
      pins == Map(
        "genesis" -> expectedSource.genesisSHA256,
        "parameters" -> expectedSource.parametersSHA256,
        "manifest" -> expectedSource.manifestSHA256
      ),
      "independent original source pins mismatch"
    )
    val lb = L.Binding(
      s.ledger.profileId,
      BigInt(s.ledger.environment.networkMagic),
      0,
      point,
      s.ledger.environment.id,
      pins
    )
    val ledger = get(L.encode(lb, originals.rows, s.ledger.outputMap, s.ledger.fees))
    val ledgerHash = S.sha256(ledger)
    val sb = S.Binding(
      ledgerHash,
      expectedSource.sourceJoinId,
      expectedSource.stakeSourceId,
      tip.slot,
      tip.blockNo,
      tip.hash
    )
    val stakeBytes = get(S.encode(stake, s.ledger, sb))
    val expected = Expected(
      expectedSource,
      lb,
      sb,
      s.id,
      s.revision,
      s.certificates.state.id,
      s.nonces.id,
      s.eligibility.map(_.contextId),
      ledgerHash,
      S.sha256(stakeBytes)
    )
    new Publication(ledger, stakeBytes, expected)
  }

  /** Expected metadata AND its digest must come from outside the untrusted pair. This checks
    * correspondence, not acquisition authenticity or permission to install a runtime.
    */
  def verify(
      ledgerBytes: Bytes,
      stakeBytes: Bytes,
      expected: Expected,
      independentlyPinnedPublicationSHA256: Bytes,
      context: SequenceInput.Context
  ): Either[String, VerifiedComponents] = checked {
    require(expected != null, "independent expected metadata required")
    validateExpected(expected)
    source(context, expected.source); hash(independentlyPinnedPublicationSHA256)
    require(
      bindingDigest(expected) == independentlyPinnedPublicationSHA256,
      "independent publication pin mismatch"
    )
    Vector(
      expected.stateId,
      expected.certificateId,
      expected.nonceId,
      expected.ledgerSHA256,
      expected.stakeSHA256
    ).foreach(hash)
    expected.eligibilityId.foreach(hash)
    require(
      expected.revision >= 0 && expected.revision <= lab.ledger.ClusterTransition.MaxRevision,
      "revision bound"
    )
    require(
      ledgerBytes != null && stakeBytes != null && ledgerBytes.size <= L.MaxBytes && stakeBytes.size <= S.MaxBytes,
      "component size bounds"
    )
    require(
      S.sha256(ledgerBytes) == expected.ledgerSHA256 && S.sha256(
        stakeBytes
      ) == expected.stakeSHA256,
      "independent component image pins mismatch"
    )
    val l = expected.ledger; val t = expected.stake; val sourcePins = expected.source
    require(
      l.environmentId == context.ledger.environment.id && l.networkMagic == context.ledger.environment.networkMagic &&
        l.sourcePins == Map(
          "genesis" -> sourcePins.genesisSHA256,
          "parameters" -> sourcePins.parametersSHA256,
          "manifest" -> sourcePins.manifestSHA256
        ) &&
        t.ledgerImageSHA256 == expected.ledgerSHA256 && t.sourceJoinId == sourcePins.sourceJoinId &&
        t.stakeSourceId == sourcePins.stakeSourceId && t.slot == l.point.slot && t.blockNo == l.point.blockNo && t.headerHash == l.point.hash,
      "component pair/source/full-point mismatch"
    )
    new VerifiedComponents(
      get(L.decode(ledgerBytes, l)),
      get(S.decode(stakeBytes, t, expected.stakeSHA256))
    )
  }
