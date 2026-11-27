// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.StateLayout
import cats.data.{Chain, NonEmptyChain}
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  Committee,
  GovAction,
  GovActionId,
  GovActionState,
  LedgerDefect,
  Proposal,
  RewardAccount,
  Tx,
  TxIn,
  TxOut,
  Utxo,
  Credential as FlatCredential
}
import xyz.kd5ujc.scarab.ledger.era.{
  EraCertState,
  EraCertsRun,
  EraTx,
  EraTxBody,
  Obligations,
  PParams,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayTxCert}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  CertsEnv as TypedCertsEnv,
  CertsEvent,
  CertsPredFailure,
  ConwayCertEvent,
  ConwayCertPredFailure,
  ConwayCertsEvent,
  ConwayCertsPredFailure,
  ConwayDelegPredFailure,
  ConwayGovCertPredFailure,
  PoolEvent,
  ShelleyPoolPredFailure,
  WithdrawalsRule
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  CertState,
  CompactCoin,
  Conway,
  ConwayCertState,
  ConwayGovState,
  EpochNo,
  NewEpochState,
  StakePool,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.{ConwayTicked, LocalSubmission, MempoolEnv}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LedgerFileCodecs, UtxoCodecs}

/** Conway's certificate rules on the era types (`era.rules.CERTS` and the rules under it), on every
  * transaction of the recorded Conway refusal corpora at the recorded protocol version and at 9, 10
  * and 11, on the transactions of recorded Conway blocks, and on certificates crafted against a
  * recorded state for every predicate failure the corpus never records. Before the flat CERTS was
  * deleted (#85, stage 3), it was these judgments' oracle: from the same ticked state, both rules
  * judged the certificates to the same predicate failures in the same order, or the same
  * certificate state. The era rule is held to those judgments, recorded after the two were found to
  * agree (`fixtures/conway-flat-refusals/certs-judgments.jsonl`): a refusal by the bytes of its
  * failures, an admission by the certificate state it leaves, checked to be held whole by what is
  * recorded of it. The corpora the flat rule judged and the state the crafted cases are made from
  * are named ([[RecordedCorpora]], [[base]]). The refusals where only the certificate rules refused
  * are held to the reference's bytes too.
  */
class ConwayCertsChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  /** A judgment of the era CERTS: a defect, its failures in the reference's order, or the state
    * after with its events.
    */
  private type Verdict = Either[
    LedgerDefect,
    Either[NonEmptyChain[CertsPredFailure[Conway]], (CertState[Conway], Chain[CertsEvent[Conway]])]
  ]

  /** One Conway case as the era types see it: its transaction, the state the mempool judges it in,
    * and the codecs of its corpus's layout.
    */
  private final case class Seen(
      label: String,
      globals: Globals,
      network: Int,
      bytes: Bytes,
      typed: ConwayTx,
      storedPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      pp: PParams[Conway],
      epoch: BigInt,
      cert: ConwayCertState,
      committee: Option[Committee],
      proposals: Map[GovActionId, GovActionState],
      families: Vector[Family],
      reason: Bytes,
      codecs: LedgerFileCodecs
  )

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private val seen: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      val env = MempoolEnv(
        setup.globals,
        setup.geometry,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
        xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
      )
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val stored: Map[TxIn, TxOut] =
          right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        val withUtxo = NewEpochState
          .ledger[Conway]
          .modify(ls =>
            ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(stored))))
          )(nes)
        val ledger = right(s"$what tick")(ConwayTicked.ticked(withUtxo, Some(rc.tipSlot), env))
        val gov: ConwayGovState = ledger.ls.utxo.gov
        Seen(
          what,
          env.rules,
          setup.globals.network.wire,
          bytes,
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          gov.curPParams,
          PParams[Conway](
            right(s"$what typed parameters")(ConwayPParams.fromStored(gov.curPParams))
          ),
          ledger.epoch,
          ledger.ls.cert,
          gov.committee,
          gov.proposals.actions.map(g => g.id -> g).toMap,
          rc.families,
          rc.reason,
          codecs
        )
      }
    }

  /** The corpora the flat CERTS judged before it was deleted, whose judgments are recorded, by
    * name: a corpus added later is not among them.
    */
  private val RecordedCorpora: Set[String] = Set(
    "conway-bootstrap-attributes",
    "conway-bootstrap-attributes-leios-b004fe0c",
    "conway-constructors",
    "conway-constructors-v11",
    "conway-deleg-collateral",
    "conway-devnet",
    "conway-gov-order",
    "conway-gov-proposals",
    "conway-governance",
    "conway-mempool",
    "conway-three-certificates",
    "conway-v11-coverage"
  )

  /** The cases of [[RecordedCorpora]]. */
  private lazy val recordedSeen: Vector[Seen] =
    seen.filter(s => RecordedCorpora.exists(c => s.label.startsWith(s"$c/")))

  /** The recorded judgment `id`, failing the test where none was recorded. */
  private def recorded(id: String): Vector[String] =
    FlatRecordings.certsJudgments.getOrElse(id, fail(s"no recording of $id"))

  /** The parameters in their stored and era forms, edited by `f`. */
  private def withParams(s: Seen, label: String)(
      f: xyz.kd5ujc.scarab.ledger.ProtocolParams => xyz.kd5ujc.scarab.ledger.ProtocolParams
  ): Seen =
    val storedPp = f(s.storedPp)
    s.copy(
      label = s"${s.label}$label",
      storedPp = storedPp,
      pp = PParams[Conway](right("edited parameters")(ConwayPParams.fromStored(storedPp)))
    )

  /** The parameters at major version `major`. */
  private def atVersion(s: Seen, major: Int): Seen =
    withParams(s, s"@v$major")(_.copy(protocolMajor = BigInt(major)))

  /** The era-typed CERTS on `certs` from `s`, under the build of `s` 's globals or `build`, `depth`
    * levels below the root.
    */
  private def typedRun(
      s: Seen,
      certs: Vector[ConwayTxCert],
      build: Option[ReferenceBuild] = None,
      depth: Int = 0
  ): Verdict =
    EraCertsRun.certs(
      build.fold(s.globals)(b =>
        s.globals.copy(ledger = s.globals.ledger.copy(referenceBuild = b))
      ),
      TypedCertsEnv[Conway](
        s.typed,
        s.pp,
        right("epoch")(EpochNo.fromBigInt(s.epoch)),
        s.committee,
        s.proposals
      ),
      s.cert,
      certs,
      depth
    )

  /** The transaction's own certificates, as the era types read them. */
  private def typedCerts(s: Seen): Vector[ConwayTxCert] =
    val tx = EraTx.conway
    val body = EraTxBody.conway
    body.certs[TxLevel.Top](tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top))(using body.top)

  /** The era CERTS's judgment of the transaction's own certificates. */
  private def ownJudgment(s: Seen): Verdict = typedRun(s, typedCerts(s))

  /** A judgment's recorded cell ([[FlatRecordings.certsRefused]],
    * [[FlatRecordings.certsAdmitted]]): no judgment the flat rule was compared on ended with a
    * defect.
    */
  private def cellOf(s: Seen, v: Verdict): String = v match
    case Left(defect) => fail(s"${s.label}: a defect, which no recording holds: $defect")
    case Right(Left(failures)) =>
      assert(
        retirementsWhole(s, failures.toChain.toVector),
        s"${s.label}: a retirement's failure is not the reference's: $failures"
      )
      FlatRecordings.certsRefused(failures.toChain.toVector)
    case Right(Right((after, _))) => FlatRecordings.certsAdmitted(after, s.codecs)

  /** The retirement epochs' mismatches among `failures`, each as POOL reports it. */
  private def retirements(
      failures: Vector[CertsPredFailure[Conway]]
  ): Vector[ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL[Conway]] =
    failures.collect {
      case ConwayCertsPredFailure.CertFailure(
            ConwayCertPredFailure.PoolFailure(
              r: ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL[Conway] @unchecked
            )
          ) =>
        r
    }

  /** Whether each retirement's failure among `failures` is the reference's for its judgment: the
    * retirement's epoch supplied to both mismatches, against the current epoch and against the
    * current epoch and `eMax` summed in `Word64` (`poolTransition`,
    * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Pool.hs:307-322`, cardano-ledger
    * f649f975). The refusal's bytes hold the retirement's epoch once, the second mismatch's
    * (`EncCBOR`, `:157`), so a first mismatch supplied another is not in its cell; this holds it.
    */
  private def retirementsWhole(s: Seen, failures: Vector[CertsPredFailure[Conway]]): Boolean =
    val limit = (s.epoch + s.storedPp.eMax).mod(BigInt(1) << 64)
    retirements(failures).forall { r =>
      r.current.supplied == r.latest.supplied && r.current.expected.toBigInt == s.epoch &&
      r.latest.expected.toBigInt == limit
    }

  // ---- what a judgment reached

  /** A predicate failure of CERTS, by its constructor in the reference (`ConwayDelegPredFailure`,
    * `ShelleyPoolPredFailure`, `ConwayGovCertPredFailure`, `ConwayCertsPredFailure`).
    */
  private enum Reached:
    case IncorrectDepositDELEG, StakeKeyRegisteredDELEG, StakeKeyNotRegisteredDELEG,
      StakeKeyHasNonZeroAccountBalanceDELEG, DelegateeDRepNotRegisteredDELEG,
      DelegateeStakePoolNotRegisteredDELEG, DepositIncorrectDELEG, RefundIncorrectDELEG
    case StakePoolNotRegisteredOnKeyPOOL, StakePoolRetirementWrongEpochPOOL,
      StakePoolCostTooLowPOOL, WrongNetworkPOOL, PoolMedataHashTooBig, VRFKeyHashAlreadyRegistered
    case ConwayDRepAlreadyRegistered, ConwayDRepNotRegistered, ConwayDRepIncorrectDeposit,
      ConwayCommitteeHasPreviouslyResigned, ConwayDRepIncorrectRefund, ConwayCommitteeIsUnknown
    case WithdrawalsNotInRewardsCERTS

  private def reachedBy(f: CertsPredFailure[Conway]): Reached = f match
    case ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(_) =>
      Reached.WithdrawalsNotInRewardsCERTS
    case ConwayCertsPredFailure.CertFailure(c) =>
      c match
        case ConwayCertPredFailure.DelegFailure(d) =>
          d match
            case ConwayDelegPredFailure.IncorrectDepositDELEG(_) => Reached.IncorrectDepositDELEG
            case ConwayDelegPredFailure.StakeKeyRegisteredDELEG(_) =>
              Reached.StakeKeyRegisteredDELEG
            case ConwayDelegPredFailure.StakeKeyNotRegisteredDELEG(_) =>
              Reached.StakeKeyNotRegisteredDELEG
            case ConwayDelegPredFailure.StakeKeyHasNonZeroAccountBalanceDELEG(_) =>
              Reached.StakeKeyHasNonZeroAccountBalanceDELEG
            case ConwayDelegPredFailure.DelegateeDRepNotRegisteredDELEG(_) =>
              Reached.DelegateeDRepNotRegisteredDELEG
            case ConwayDelegPredFailure.DelegateeStakePoolNotRegisteredDELEG(_) =>
              Reached.DelegateeStakePoolNotRegisteredDELEG
            case ConwayDelegPredFailure.DepositIncorrectDELEG(_) => Reached.DepositIncorrectDELEG
            case ConwayDelegPredFailure.RefundIncorrectDELEG(_)  => Reached.RefundIncorrectDELEG
        case ConwayCertPredFailure.PoolFailure(p) =>
          p match
            case ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL(_) =>
              Reached.StakePoolNotRegisteredOnKeyPOOL
            case ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL(_, _) =>
              Reached.StakePoolRetirementWrongEpochPOOL
            case ShelleyPoolPredFailure.StakePoolCostTooLowPOOL(_) =>
              Reached.StakePoolCostTooLowPOOL
            case ShelleyPoolPredFailure.WrongNetworkPOOL(_, _)     => Reached.WrongNetworkPOOL
            case ShelleyPoolPredFailure.PoolMedataHashTooBig(_, _) => Reached.PoolMedataHashTooBig
            case ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(_, _) =>
              Reached.VRFKeyHashAlreadyRegistered
        case ConwayCertPredFailure.GovCertFailure(g) =>
          g match
            case ConwayGovCertPredFailure.ConwayDRepAlreadyRegistered(_) =>
              Reached.ConwayDRepAlreadyRegistered
            case ConwayGovCertPredFailure.ConwayDRepNotRegistered(_) =>
              Reached.ConwayDRepNotRegistered
            case ConwayGovCertPredFailure.ConwayDRepIncorrectDeposit(_) =>
              Reached.ConwayDRepIncorrectDeposit
            case ConwayGovCertPredFailure.ConwayCommitteeHasPreviouslyResigned(_) =>
              Reached.ConwayCommitteeHasPreviouslyResigned
            case ConwayGovCertPredFailure.ConwayDRepIncorrectRefund(_) =>
              Reached.ConwayDRepIncorrectRefund
            case ConwayGovCertPredFailure.ConwayCommitteeIsUnknown(_) =>
              Reached.ConwayCommitteeIsUnknown

  private def reached(v: Verdict): Set[Reached] =
    v.toOption
      .flatMap(_.left.toOption)
      .fold(Set.empty[Reached])(_.toChain.toVector.map(reachedBy).toSet)

  // ---- the recorded corpus

  private def versions(s: Seen): Vector[Seen] =
    s +: Vector(9, 10, 11).map(atVersion(s, _))

  test("the corpus has Conway cases, each read by the era types, and the recorded corpora"):
    assert(clue(seen.size) > 0)
    assertEquals(
      RecordedCorpora -- seen.map(_.label.takeWhile(_ != '/')),
      Set.empty[String],
      "the recorded corpora not loaded"
    )

  /** The corpus cases' judgments at their recorded version and at 9, 10 and 11, compared with the
    * recording as they are made.
    */
  private lazy val corpusJudged: Vector[(String, Vector[Verdict])] =
    recordedSeen.map { c =>
      val id = s"corpus/${c.label}"
      val judged = versions(c).map(s => s -> ownJudgment(s))
      assertEquals(judged.map((s, v) => cellOf(s, v)), recorded(id), c.label)
      id -> judged.map(_._2)
    }

  test("CERTS on the era types gives the flat rule's recorded judgment of every recorded case"):
    // the flat CERTS, before it was deleted, judged each case at its recorded protocol version and
    // at 9, 10 and 11, and the era CERTS gave the same failures in the same order, or the same
    // certificate state
    assertEquals(corpusJudged.size, 112, "the recorded cases")

  /** The corpus cases (at their recorded version) that record each failure. */
  private lazy val corpusReach: Map[Reached, Int] =
    seen
      .flatMap(s => reached(ownJudgment(s)).toVector)
      .groupMapReduce(identity)(_ => 1)(_ + _)

  test("the corpus cases that reach each predicate failure, and those with certificates"):
    val withCerts = seen.count(_.typed.body.certs.toVector.nonEmpty)
    val refused = seen.count(s => reached(ownJudgment(s)).nonEmpty)
    println(
      s"ConwayCertsChecksSuite: ${seen.size} Conway cases, $withCerts with certificates, " +
        s"$refused refused by CERTS"
    )
    def carrying(kind: PartialFunction[ConwayTxCert, Unit]): Int =
      seen.count(s => s.typed.body.certs.toVector.exists(kind.isDefinedAt))
    val deleg = carrying { case ConwayTxCert.ConwayTxCertDeleg(_) => () }
    val pool = carrying { case ConwayTxCert.ConwayTxCertPool(_) => () }
    val gov = carrying { case ConwayTxCert.ConwayTxCertGov(_) => () }
    println(s"  cases carrying a DELEG certificate: $deleg, POOL: $pool, GOVCERT: $gov")
    Reached.values.toVector.sortBy(_.toString).foreach { r =>
      println(s"  $r: ${corpusReach.getOrElse(r, 0)} corpus cases")
    }
    assert(withCerts > 0)

  test("the certificate state's obligations are the deposits it holds"):
    seen.foreach { s =>
      val c = s.cert
      val o = EraCertState.conway.obligationCertState(c)
      val stake = c.dState.accounts.states.valuesIterator.map(_.deposit.toBigInt).sum
      val pool = c.pState.stakePools.valuesIterator.map(_.deposit.toBigInt).sum
      val drep = c.vState.dreps.valuesIterator.map(_.deposit.toBigInt).sum
      assertEquals(
        (o.oblStake.value, o.oblPool.value, o.oblDRep.value, o.oblProposal.value),
        (stake, pool, drep, BigInt(0)),
        s.label
      )
      assertEquals(Obligations.sumObligation(o).value, stake + pool + drep, s.label)
    }

  // ---- recorded blocks

  /** The transactions of the recorded Conway blocks (Preview), each read by Conway's decoder. */
  private lazy val blockTxs: Vector[(String, ConwayTx)] =
    Vector("fixtures/preview/conway.hex", "fixtures/preview/conway-governance.hex").flatMap { f =>
      java.nio.file.Files
        .readString(Path.of(f))
        .linesIterator
        .zipWithIndex
        .map((l, i) => (s"$f:${i + 1}", l.trim.split("\\s+").last))
        .filter((_, hex) => hex.nonEmpty)
        .toVector
        .flatMap { (at, hex) =>
          xyz.kd5ujc.scarab.ledger.Block.decodeShelley(right(s"$at hex")(Bytes.fromHex(hex))) match
            case Right(b: xyz.kd5ujc.scarab.ledger.Block.Shelley) if b.era == Era.Conway =>
              b.transactions.zipWithIndex.map((t, k) =>
                (s"$at#$k", right(s"$at#$k typed")(ConwayTx.decode(t, StateLayout.Node11_1)))
              )
            case other => fail(s"$at: not a Conway block: $other")
        }
    }

  /** The block transactions' judgments from [[base]] at versions 9, 10 and 11, compared with the
    * recording as they are made.
    */
  private lazy val blocksJudged: Vector[(String, Vector[Verdict])] =
    blockTxs.map { (at, typed) =>
      val id = s"blocks/$at"
      val judged = Vector(9, 10, 11).map(atVersion(base, _)).map { s =>
        val on = s.copy(label = s"${s.label} $at", typed = typed)
        on -> ownJudgment(on)
      }
      assertEquals(judged.map((s, v) => cellOf(s, v)), recorded(id), at)
      id -> judged.map(_._2)
    }

  test("CERTS on the era types gives the flat rule's recorded judgment of every block transaction"):
    assertEquals(blocksJudged.size, 62, "the recorded block transactions")

  // ---- crafted certificates, read by Conway's decoder

  private def node(v: Value): Node = Node(v, Bytes.empty)
  private def uint(n: BigInt): Node = node(Value.UInt(n))
  private def bytesN(b: Bytes): Node = node(Value.ByteString(b))
  private def arr(xs: Node*): Node = node(Value.Arr(xs.toVector))
  private val nul: Node = node(Value.Null)

  private def hash(seed: Int, size: Int): Bytes =
    Bytes.fromArray(Array.tabulate(size)(i => ((seed * 31 + i * 7) & 0xff).toByte))

  private def keyCred(seed: Int): Node = arr(uint(0), bytesN(hash(seed, 28)))
  private def flatKey(seed: Int): FlatCredential = FlatCredential.Key(hash(seed, 28))
  private def anchor(seed: Int): Node =
    arr(node(Value.Text(s"https://example.com/$seed")), bytesN(hash(seed, 32)))

  private enum D:
    case Key(seed: Int)
    case Abstain
    case NoConfidence

  private def drep(d: D): Node = d match
    case D.Key(seed)    => arr(uint(0), bytesN(hash(seed, 28)))
    case D.Abstain      => arr(uint(2))
    case D.NoConfidence => arr(uint(3))

  private def rewardAccount(network: Int, seed: Int): Bytes = accountOf(network, flatKey(seed))

  /** A reward account's bytes: its header (`0xe0` for a key, `0xf0` for a script, with the network)
    * and the credential's hash.
    */
  private def accountOf(network: Int, c: FlatCredential): Bytes =
    val header = c match
      case FlatCredential.Key(_)    => 0xe0
      case FlatCredential.Script(_) => 0xf0
    Bytes.fromArray((header | network).toByte +: c.hash.toArray)

  /** A Conway certificate's encoding (`conway.cddl`). */
  private object C:
    def reg(c: Int): Node = arr(uint(0), keyCred(c))
    def unreg(c: Int): Node = arr(uint(1), keyCred(c))
    def delegStake(c: Int, pool: Bytes): Node = arr(uint(2), keyCred(c), bytesN(pool))
    def regDeposit(c: Int, coin: BigInt): Node = arr(uint(7), keyCred(c), uint(coin))
    def unregRefund(c: Int, coin: BigInt): Node = arr(uint(8), keyCred(c), uint(coin))
    def voteDeleg(c: Int, d: D): Node = arr(uint(9), keyCred(c), drep(d))
    def stakeVoteDeleg(c: Int, pool: Bytes, d: D): Node =
      arr(uint(10), keyCred(c), bytesN(pool), drep(d))
    def stakeRegDeleg(c: Int, pool: Bytes, coin: BigInt): Node =
      arr(uint(11), keyCred(c), bytesN(pool), uint(coin))
    def voteRegDeleg(c: Int, d: D, coin: BigInt): Node =
      arr(uint(12), keyCred(c), drep(d), uint(coin))
    def stakeVoteRegDeleg(c: Int, pool: Bytes, d: D, coin: BigInt): Node =
      arr(uint(13), keyCred(c), bytesN(pool), drep(d), uint(coin))
    def authHot(cold: Int, hot: Int): Node = arr(uint(14), keyCred(cold), keyCred(hot))
    def resign(cold: Int): Node = arr(uint(15), keyCred(cold), anchor(cold))
    def regDRep(d: Int, coin: BigInt): Node = arr(uint(16), keyCred(d), uint(coin), anchor(d))
    def unregDRep(d: Int, coin: BigInt): Node = arr(uint(17), keyCred(d), uint(coin))
    def updateDRep(d: Int): Node = arr(uint(18), keyCred(d), nul)
    def retire(pool: Bytes, epoch: BigInt): Node = arr(uint(4), bytesN(pool), uint(epoch))
    def regPool(
        operator: Bytes,
        vrf: Bytes,
        cost: BigInt,
        account: Bytes,
        metadataHash: Option[Bytes] = None
    ): Node =
      arr(
        uint(3),
        bytesN(operator),
        bytesN(vrf),
        uint(1000),
        uint(cost),
        node(Value.Tag(30, arr(uint(1), uint(10)))),
        bytesN(account),
        arr(),
        arr(),
        metadataHash.fold(nul)(h => arr(node(Value.Text("https://example.com/p")), bytesN(h)))
      )

  /** The encoding read again, as a block's certificate is: its own bytes in each node. */
  private def read(n: Node): Node =
    right("read")(Cbor.decode(right("encode")(Cbor.encode(n.value, Cbor.Limits.Unbounded))))

  /** The crafted certificates as Conway's decoder reads them, each read by the flat decoder too,
    * which still reads a relayed transaction's.
    */
  private def certsOf(certs: Vector[Node]): Vector[ConwayTxCert] =
    certs.map(read).map { n =>
      right("flat certificate")(Certificate.decode(n, Era.Conway))
      right("certificate")(ConwayTxCert.decode(n, StateLayout.Node11_1))
    }

  /** The recorded state to craft against, by its case's label: it has two pools, an account and a
    * DRep registered, so the crafted delegations have somewhere to move.
    */
  private lazy val base: Seen =
    val label = "conway-bootstrap-attributes/utxo-bootstrap-attributes-too-big"
    val s = seen.find(_.label == label).getOrElse(fail(s"no case $label"))
    assert(
      s.cert.pState.stakePools.size >= 2 && s.cert.dState.accounts.states.nonEmpty &&
        s.cert.vState.dreps.nonEmpty,
      s"$label has not two pools, an account and a DRep"
    )
    s

  /** A crafted judgment: its id, `crafted/` then the label of the state it is judged from and the
    * case's name, and the era CERTS's judgment of `certs` from `s`.
    */
  private final case class Crafted(id: String, verdict: Verdict)

  /** The era CERTS's judgment of `certs` from `s`, compared with the recording. */
  private def compared(s: Seen, name: String, certs: Vector[Node]): Crafted =
    val id = s"crafted/${s.label}/$name"
    val verdict = typedRun(s, certsOf(certs))
    assertEquals(Vector(cellOf(s, verdict)), recorded(id), id)
    Crafted(id, verdict)

  /** [[compared]] for certificates the rule accepts, so the states are compared. */
  private def accepted(s: Seen, name: String, certs: Vector[Node]): Crafted =
    val judged = compared(s, name, certs)
    assert(judged.verdict.exists(_.isRight), s"${s.label}: $name is refused: ${judged.verdict}")
    judged

  private def keyDeposit(s: Seen): BigInt = s.storedPp.keyDeposit
  private def drepDeposit(s: Seen): BigInt = s.storedPp.drepDeposit

  /** The crafted scenarios, at protocol versions 9, 10 and 11. */
  private def scenarios(at: Seen): Vector[Crafted] =
    // a minimum pool cost a registration can fall below
    val s = withParams(at, "")(pp => pp.copy(minPoolCost = pp.minPoolCost.max(BigInt(1000))))
    val pools = s.cert.pState.stakePools.keys.toVector.map(_.bytes)
    val pool = pools.head
    val otherPool = pools.lift(1).getOrElse(pool)
    val account =
      s.cert.dState.accounts.states.keys.headOption.map(xyz.kd5ujc.scarab.ledger.rules.Convert.back)
    val unknownPool = hash(900, 28)
    val kd = keyDeposit(s)
    val dd = drepDeposit(s)
    // DELEG
    val v1 = compared(
      s,
      "registrations",
      Vector(C.regDeposit(1, kd + 1), C.reg(1), C.regDeposit(2, kd), C.reg(2))
    )
    val v2 = compared(
      s,
      "unregistrations",
      Vector(
        C.unreg(3),
        C.unregRefund(3, kd),
        C.regDeposit(4, kd),
        C.unregRefund(4, kd + 5),
        C.regDeposit(5, kd),
        C.unregRefund(5, kd),
        C.unreg(5)
      )
    )
    val v3 = compared(
      s,
      "delegations",
      Vector(
        C.delegStake(6, pool),
        C.regDeposit(6, kd),
        C.delegStake(6, unknownPool),
        C.voteDeleg(6, D.Key(700)),
        C.voteDeleg(6, D.Abstain),
        C.stakeVoteDeleg(6, pool, D.NoConfidence),
        C.delegStake(6, otherPool),
        C.delegStake(6, otherPool)
      )
    )
    val v4 = compared(
      s,
      "registrations with delegation",
      Vector(
        C.stakeRegDeleg(7, pool, kd),
        C.stakeRegDeleg(8, unknownPool, kd + 1),
        C.voteRegDeleg(9, D.Key(701), kd),
        C.stakeVoteRegDeleg(10, pool, D.Abstain, kd),
        C.stakeVoteRegDeleg(7, pool, D.Abstain, kd),
        C.unregRefund(7, kd),
        C.unregRefund(10, kd)
      )
    )
    // DReps, their delegators, and a delegation moved between them
    val v5 = compared(
      s,
      "DReps",
      Vector(
        C.regDRep(20, dd + 1),
        C.regDRep(20, dd),
        C.updateDRep(21),
        C.updateDRep(20),
        C.regDRep(22, dd),
        C.voteRegDeleg(23, D.Key(20), kd),
        C.voteDeleg(23, D.Key(22)),
        C.voteDeleg(23, D.Key(22)),
        C.stakeVoteRegDeleg(24, pool, D.Key(22), kd),
        C.unregDRep(22, dd + 1),
        C.unregDRep(22, dd),
        C.unregDRep(20, dd),
        C.voteDeleg(24, D.Key(20)),
        C.unreg(23)
      )
    )
    val vAccount = account.toVector.map { a =>
      val hashOf = a.hash
      val existing = a match
        case FlatCredential.Key(_)    => arr(uint(0), bytesN(hashOf))
        case FlatCredential.Script(_) => arr(uint(1), bytesN(hashOf))
      compared(
        s,
        "a registered account",
        Vector(
          arr(uint(9), existing, drep(D.Key(30))),
          arr(uint(2), existing, bytesN(otherPool)),
          arr(uint(8), existing, uint(kd + 7))
        )
      )
    }
    // POOL
    val network = s.network
    val minCost = s.storedPp.minPoolCost
    val v6 = compared(
      s,
      "pool registrations",
      Vector(
        C.regPool(hash(40, 28), hash(40, 32), minCost, rewardAccount(1 - network, 40)),
        C.regPool(
          hash(41, 28),
          hash(41, 32),
          minCost,
          rewardAccount(network, 41),
          Some(hash(41, 33))
        ),
        C.regPool(hash(42, 28), hash(42, 32), minCost - 1, rewardAccount(network, 42)),
        C.regPool(hash(43, 28), hash(40, 32), minCost, rewardAccount(network, 43)),
        C.regPool(hash(40, 28), hash(44, 32), minCost, rewardAccount(network, 40)),
        C.regPool(hash(40, 28), hash(45, 32), minCost, rewardAccount(network, 40)),
        C.regPool(hash(40, 28), hash(45, 32), minCost, rewardAccount(network, 40)),
        C.regPool(hash(40, 28), hash(42, 32), minCost, rewardAccount(network, 40))
      )
    )
    val existingVrf = s.cert.pState.stakePools.values.head.vrf.bytes
    val v7 = compared(
      s,
      "pool retirements",
      Vector(
        C.retire(unknownPool, s.epoch + 1),
        C.retire(pool, s.epoch),
        C.retire(pool, s.epoch + 1000000),
        C.retire(pool, s.epoch + 1),
        C.regPool(pool, existingVrf, minCost, rewardAccount(network, 46)),
        C.regPool(hash(47, 28), existingVrf, minCost, rewardAccount(network, 47))
      )
    )
    // the committee, current and proposed
    val cold = 50
    val proposed = 51
    val committee = Committee(Map(flatKey(cold) -> BigInt(1000)), Rational(1, 2))
    val proposal = GovActionState(
      GovActionId(hash(52, 32), 0),
      Map.empty,
      Map.empty,
      Map.empty,
      Proposal(
        0,
        RewardAccount(network, flatKey(52)),
        GovAction
          .UpdateCommittee(None, Set.empty, Map(flatKey(proposed) -> BigInt(1000)), Rational(1, 2))
      ),
      0,
      1000
    )
    val withCommittee = s.copy(
      committee = Some(committee),
      proposals = s.proposals.updated(proposal.id, proposal)
    )
    val v8 = compared(
      withCommittee,
      "committee",
      Vector(
        C.authHot(53, 54),
        C.authHot(cold, 55),
        C.authHot(proposed, 56),
        C.resign(cold),
        C.authHot(cold, 57),
        C.resign(proposed),
        C.resign(proposed)
      )
    )
    val v9 = compared(s, "an unknown committee", Vector(C.authHot(cold, 55), C.resign(proposed)))
    // sequences every version accepts, so the states they end in are compared too: a refusal
    // carries its failures and not its state
    val existing = account.map(a =>
      a match
        case FlatCredential.Key(h)    => arr(uint(0), bytesN(h))
        case FlatCredential.Script(h) => arr(uint(1), bytesN(h))
    )
    val moves = accepted(
      s,
      "delegations moved",
      Vector(
        C.regDRep(20, dd),
        C.regDRep(22, dd),
        C.voteRegDeleg(23, D.Key(20), kd),
        C.voteDeleg(23, D.Key(22)),
        C.stakeVoteRegDeleg(24, pool, D.Key(22), kd),
        C.voteDeleg(24, D.Key(20)),
        C.voteDeleg(24, D.Abstain),
        C.voteDeleg(24, D.Key(22)),
        C.delegStake(23, otherPool),
        C.stakeVoteDeleg(23, pool, D.Key(20)),
        C.regDeposit(25, kd),
        C.stakeVoteDeleg(25, otherPool, D.Key(22)),
        C.unregDRep(22, dd),
        C.updateDRep(20),
        C.unregRefund(23, kd),
        C.unreg(24)
      ) ++ existing.toVector.flatMap(e =>
        Vector(
          arr(uint(9), e, drep(D.Key(20))),
          arr(uint(2), e, bytesN(otherPool)),
          arr(uint(10), e, bytesN(pool), drep(D.NoConfidence)),
          arr(uint(9), e, drep(D.Key(20)))
        )
      )
    )
    val pooled = accepted(
      s,
      "pools registered, re-registered and retired",
      Vector(
        C.regPool(hash(70, 28), hash(70, 32), minCost, rewardAccount(network, 70)),
        C.regPool(hash(70, 28), hash(71, 32), minCost, rewardAccount(network, 70)),
        C.regPool(hash(70, 28), hash(72, 32), minCost, rewardAccount(network, 70)),
        C.retire(hash(70, 28), s.epoch + 1),
        C.regPool(hash(70, 28), hash(73, 32), minCost, rewardAccount(network, 70)),
        C.stakeRegDeleg(26, hash(70, 28), kd),
        C.retire(pool, s.epoch + 1),
        C.regPool(pool, existingVrf, minCost, rewardAccount(network, 74))
      )
    )
    val members = accepted(
      withCommittee,
      "committee members",
      Vector(
        C.authHot(cold, 55),
        C.authHot(cold, 56),
        C.authHot(proposed, 57),
        C.resign(proposed),
        C.resign(cold)
      )
    )
    Vector(v1, v2, v3, v4, v5, v6, v7, v8, v9, moves, pooled, members) ++ vAccount

  /** The crafted scenarios' judgments, each compared with the recording as it is made. */
  private lazy val craftedJudged: Vector[Crafted] =
    Vector(9, 10, 11).flatMap(v => scenarios(atVersion(base, v))) ++ baseCases ++ funded

  test(
    "crafted certificates are given the flat rule's recorded judgments at versions 9, 10 and 11"
  ):
    assertEquals(craftedJudged.size, 293, "the crafted judgments")

  // ---- the base case of CERTS: withdrawals and the DReps' expiries

  /** `s` with its transaction's body edited by `f`, read again by Conway's decoder. */
  private def edited(s: Seen)(f: Vector[(Node, Node)] => Vector[(Node, Node)]): Seen =
    val root = right("envelope")(Cbor.decode(s.bytes))
    val again = root.value match
      case Value.Arr(parts) =>
        parts.head.value match
          case Value.Map(fields) =>
            right("re-encode")(
              Cbor.encode(
                Value.Arr(node(Value.Map(f(fields))) +: parts.tail),
                Cbor.Limits.Unbounded
              )
            )
          case other => fail(s"a body that is not a map: $other")
      case other => fail(s"an envelope that is not an array: $other")
    // the flat decoder, which still reads a relayed transaction, reads the edited envelope too,
    // to the same body
    val flat = right("edited flat")(Tx.fromEnvelope(again, Era.Conway))
    val typed = right("edited typed")(ConwayTx.fromEnvelope(again, StateLayout.Node11_1))
    val tx = EraTx.conway
    val body = EraTxBody.conway
    assertEquals(
      body.id[TxLevel.Top](tx.body[TxLevel.Top](typed)(using tx.bodyOf.top))(using body.top).hex,
      flat.body.id.hex,
      "the edited body's id by either decoder"
    )
    s.copy(bytes = again, typed = typed)

  private def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)

  /** The base case at versions 9, 10 and 11: withdrawals of an unregistered account, of another
    * network and of less than the balance; a draining one; and the DReps' expiries moved by the
    * corpus's transactions with proposals or votes, with dormant epochs counted and without.
    */
  private lazy val baseCases: Vector[Crafted] =
    Vector(9, 10, 11).flatMap { v =>
      val s = atVersion(base, v)
      val (registered, held) = s.cert.dState.accounts.states.head
      val back = xyz.kd5ujc.scarab.ledger.rules.Convert.back(registered)
      val registeredAccount = accountOf(s.network, back)
      val balance = held.balance.toBigInt
      def withWithdrawals(entries: Vector[(Node, Node)]): Seen =
        edited(s)(fields =>
          fields.filterNot(f => isKey(5)(f._1)) :+ (uint(5) -> node(Value.Map(entries)))
        )
      val withdrawing = withWithdrawals(
        Vector(
          bytesN(registeredAccount) -> uint(balance + 1),
          bytesN(rewardAccount(s.network, 60)) -> uint(5),
          bytesN(accountOf(1 - s.network, back)) -> uint(balance)
        )
      )
      val draining = withWithdrawals(Vector(bytesN(registeredAccount) -> uint(balance)))
      val dormant = s.cert.copy(vState =
        s.cert.vState.copy(numDormantEpochs = right("dormant")(EpochNo.fromBigInt(3)))
      )
      val voting = recordedSeen
        .filter { c =>
          val b = c.typed.body
          b.proposalProcedures.toVector.nonEmpty || b.votingProcedures.unVotingProcedures.nonEmpty
        }
        .map(atVersion(_, v))
      // a proposal and a vote of each registered DRep, crafted into the transaction
      val voters = s.cert.vState.dreps.keys.toVector.collect {
        case xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(h) =>
          arr(uint(2), bytesN(h.bytes))
        case xyz.kd5ujc.scarab.ledger.state.Credential.ScriptHashObj(h) =>
          arr(uint(3), bytesN(h.bytes))
      }
      val registeredDRep = s.cert.vState.dreps.keys.toVector.collect {
        case xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(h) =>
          arr(uint(0), bytesN(h.bytes))
        case xyz.kd5ujc.scarab.ledger.state.Credential.ScriptHashObj(h) =>
          arr(uint(1), bytesN(h.bytes))
      }
      val quiet = edited(s)(_.filterNot(f => isKey(5)(f._1) || isKey(19)(f._1) || isKey(20)(f._1)))
      val votingOnly = edited(s)(fields =>
        fields.filterNot(f => isKey(5)(f._1) || isKey(19)(f._1) || isKey(20)(f._1)) ++ Option
          .when(voters.nonEmpty)(
            uint(19) -> node(
              Value.Map(
                voters.map(v =>
                  v -> node(
                    Value.Map(Vector(arr(bytesN(hash(63, 32)), uint(0)) -> arr(uint(1), nul)))
                  )
                )
              )
            )
          )
          .toVector
      )
      val proposing = edited(s)(fields =>
        fields.filterNot(f => isKey(19)(f._1) || isKey(20)(f._1)) ++ Vector(
          uint(20) -> arr(
            arr(
              uint(s.storedPp.govActionDeposit),
              bytesN(rewardAccount(s.network, 62)),
              arr(uint(6)),
              anchor(62)
            )
          )
        ) ++ Option
          .when(voters.nonEmpty)(
            uint(19) -> node(
              Value.Map(
                voters.map(v =>
                  v -> node(
                    Value.Map(Vector(arr(bytesN(hash(63, 32)), uint(0)) -> arr(uint(1), nul)))
                  )
                )
              )
            )
          )
          .toVector
      )
      // in the third case, with no proposal, the dormant epochs stay counted: a DRep registered
      // (versioned at 9) and updated, a registered one updated, and the DReps' votes, each with the
      // count taken off
      Vector(
        accepted(
          proposing.copy(cert = dormant),
          "a proposal and votes, dormant epochs",
          Vector.empty
        ),
        accepted(proposing, "a proposal and votes", Vector.empty),
        accepted(
          quiet.copy(cert = dormant),
          "DReps registered and updated, dormant epochs",
          Vector(C.regDRep(80, s.storedPp.drepDeposit), C.updateDRep(80)) ++ registeredDRep.map(c =>
            arr(uint(18), c, nul)
          )
        ),
        accepted(
          votingOnly.copy(cert = dormant),
          "votes without a proposal, dormant epochs",
          Vector.empty
        ),
        compared(withdrawing, "withdrawals", Vector.empty),
        accepted(draining, "a draining withdrawal", Vector(C.regDeposit(61, keyDeposit(s))))
      ) ++ voting.flatMap(c =>
        Vector(
          compared(c.copy(cert = dormant), "proposals and votes, dormant epochs", Vector.empty),
          compared(c, "proposals and votes", Vector.empty)
        )
      )
    }

  /** An account holding a balance, unregistered, at versions 10 and 11. */
  private lazy val funded: Vector[Crafted] =
    Vector(10, 11).map { v =>
      val s = atVersion(base, v)
      val (registered, held) = s.cert.dState.accounts.states.head
      val accounts = s.cert.dState.accounts
      val fundedState = s.cert.copy(dState =
        s.cert.dState.copy(accounts =
          accounts.copy(states =
            accounts.states
              .updated(registered, held.copy(balance = CompactCoin.fromUnsigned(12345L)))
          )
        )
      )
      val existing = xyz.kd5ujc.scarab.ledger.rules.Convert.back(registered) match
        case FlatCredential.Key(h)    => arr(uint(0), bytesN(h))
        case FlatCredential.Script(h) => arr(uint(1), bytesN(h))
      compared(s.copy(cert = fundedState), "a funded account", Vector(arr(uint(1), existing)))
    }

  test("the base case and a funded account are given the flat rule's recorded judgments"):
    assertEquals((baseCases.size, funded.size), (252, 2))

  // ---- the reference's failure types: refusals, builds and events

  /** The rules whose refusals CERTS's are: CERTS and the rules below it. */
  private val CertsFamilies: Set[Family] =
    Set(Family.CERTS, Family.DELEG, Family.POOL, Family.GOVCERT)

  test(
    "where only the certificate rules refused, the era-typed CERTS's refusal is the reference's"
  ):
    // CERTS two levels down, under MEMPOOL and LEDGER, its failures under LEDGER's, encoded by
    // Conway's encoder as the node sends a refusal
    val certsOnly = seen.filter(s => s.families.nonEmpty && s.families.forall(CertsFamilies))
    assert(clue(certsOnly.size) >= 15, "too few cases only the certificate rules refuse")
    assert(certsOnly.exists(_.families.size >= 3), "no case with three failures")
    assert(certsOnly.exists(_.families.contains(Family.CERTS)), "no withdrawal case")
    certsOnly.foreach { s =>
      val failures = typedRun(s, typedCerts(s), depth = 2) match
        case Right(Left(fs)) =>
          fs.toChain.toVector.map(
            xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayCertsFailure[Conway](_)
          )
        case other => fail(s"${s.label}: $other")
      assertEquals(ConwayFailureCborCheck.direct(failures).hex, s.reason.hex, s.label)
    }

  /** [[base]] with withdrawals of more than an account holds, of an unregistered account and of
    * another network, at `version`.
    */
  private def withdrawing(version: Int): Seen =
    val s = atVersion(base, version)
    val (registered, held) = s.cert.dState.accounts.states.head
    val back = xyz.kd5ujc.scarab.ledger.rules.Convert.back(registered)
    edited(s)(fields =>
      fields.filterNot(f => isKey(5)(f._1)) :+ (uint(5) -> node(
        Value.Map(
          Vector(
            bytesN(accountOf(s.network, back)) -> uint(held.balance.toBigInt + 1),
            bytesN(rewardAccount(s.network, 60)) -> uint(5)
          )
        )
      ))
    )

  test("CERTS's base case checks the withdrawals only under the 11.1 build below version 11"):
    // `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`: at 11.1 LEDGER's from version 11,
    // at 29a7d656 and 9df9f98c LEDGER's at every version, so CERTS's base case never runs there
    // and leaves the state as it was
    // the judgment's build is the globals', so each build is run through the entry point
    for
      v <- Vector(9, 10, 11)
      build <- ReferenceBuild.values.toVector
    do
      val s = withdrawing(v)
      val label = s"${s.label}: v$v under ${build.name}"
      val judged = typedRun(s, Vector.empty, Some(build))
      if v < 11 && build == ReferenceBuild.Release11_1 then
        assert(
          judged.exists(_.left.exists(_.toChain.toVector match
            case Vector(ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(ws)) => ws.size == 2
            case _                                                               => false)),
          s"$label: $judged"
        )
      else assertEquals(judged.map(_.map(_._1)), Right(Right(s.cert)), label)
    assertEquals(
      ReferenceBuild.values.toVector.map(WithdrawalsRule.of),
      Vector(WithdrawalsRule.LedgerAboveVersion10, WithdrawalsRule.Ledger, WithdrawalsRule.Ledger)
    )

  test("POOL tells a registration and a re-registration, embedded in CERT's and CERTS's events"):
    val s = withParams(base, "")(pp => pp.copy(minPoolCost = BigInt(0)))
    val operator = hash(950, 28)
    val account = rewardAccount(s.network, 951)
    val certs = Vector(
      C.reg(952),
      C.regPool(operator, hash(953, 32), 0, account),
      C.regPool(operator, hash(953, 32), 0, account)
    )
    val typed = certsOf(certs)
    val pool =
      right("operator")(xyz.kd5ujc.scarab.ledger.state.KeyHash.fromBytes[StakePool](operator))
    val events = typedRun(s, typed) match
      case Right(Right((_, es))) => es.toVector
      case other                 => fail(s"refused: $other")
    def embedded(e: PoolEvent[Conway]): ConwayCertsEvent[Conway] =
      ConwayCertsEvent.CertEvent(ConwayCertEvent.PoolEvent(e))
    assertEquals(
      events,
      Vector(embedded(PoolEvent.RegisterPool(pool)), embedded(PoolEvent.ReregisterPool(pool)))
    )

  test("every predicate failure of CERTS is reached by the corpus or a crafted case"):
    val all = corpusReach.keySet ++ craftedJudged.flatMap(c => reached(c.verdict))
    assertEquals(Reached.values.toSet -- all, Set.empty[Reached])

  test("the recording of the flat CERTS's judgments holds this suite's alone, each once"):
    val ids = corpusJudged.map(_._1) ++ blocksJudged.map(_._1) ++ craftedJudged.map(_.id)
    assertEquals(ids.diff(ids.distinct), Vector.empty[String], "ids judged twice")
    assertEquals(ids.toSet, FlatRecordings.certsJudgments.keySet)
    val verdicts =
      corpusJudged.flatMap(_._2) ++ blocksJudged.flatMap(_._2) ++ craftedJudged.map(_.verdict)
    assertEquals(
      (verdicts.count(_.exists(_.isLeft)), verdicts.count(_.exists(_.isRight))),
      (191, 736),
      "the refusals and admissions compared"
    )

  // ---- what an admission's cell holds

  /** An admission of [[base]] that registers, re-registers and retires pools, and the state it
    * leaves.
    */
  private lazy val pooled: (Seen, ConwayCertState) =
    val s = atVersion(base, 10)
    val id = s"crafted/${s.label}/pools registered, re-registered and retired"
    craftedJudged.find(_.id == id).map(_.verdict) match
      case Some(Right(Right((after, _)))) => (s, after)
      case other                          => fail(s"$id: $other")

  test("an admission's cell holds the relays a state keeps, which its encoding writes anew"):
    // the encoding writes a pool's relays in its own form, so a state holding them as other bytes
    // (an indefinite empty list for the definite one) has the same encoding; the cell's second
    // digest, of the bytes kept, tells them apart, as the flat comparison of the whole state did
    val (s, after) = pooled
    val pool = right("operator")(
      xyz.kd5ujc.scarab.ledger.state.KeyHash.fromBytes[StakePool](hash(70, 28))
    )
    assertEquals(after.pState.stakePools.get(pool).map(_.relays), Some(Bytes.empty))
    val indefinite = right("relays")(Bytes.fromHex("9fff"))
    val mutant = ConwayCertState.pState.modify(p =>
      p.copy(stakePools = p.stakePools.updatedWith(pool)(_.map(_.copy(relays = indefinite))))
    )(after)
    assertEquals(
      FlatRecordings.certsEncoded(mutant, s.codecs),
      FlatRecordings.certsEncoded(after, s.codecs),
      "the encoding is the same"
    )
    assert(FlatRecordings.certsHeldWhole(mutant, s.codecs))
    assertNotEquals(
      FlatRecordings.certsAdmitted(mutant, s.codecs),
      FlatRecordings.certsAdmitted(after, s.codecs)
    )

  test("a refused retirement's two mismatches are the reference's, which its cell holds once"):
    // the crafted retirements of a registered pool at the current epoch and a million epochs on,
    // past the limit `eMax` sets: both mismatches supply the epoch, the first against the current
    // epoch, the second against the limit
    Vector(9, 10, 11).foreach { v =>
      val s = atVersion(base, v)
      val id = s"crafted/${s.label}/pool retirements"
      val failures = craftedJudged.find(_.id == id).map(_.verdict) match
        case Some(Right(Left(fs))) => fs.toChain.toVector
        case other                 => fail(s"$id: $other")
      def epoch(n: BigInt): EpochNo = right("epoch")(EpochNo.fromBigInt(n))
      val limit = epoch(s.epoch + s.storedPp.eMax)
      val expected = Vector(s.epoch, s.epoch + 1000000).map(e =>
        ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL[Conway](
          xyz.kd5ujc.scarab.ledger.era.Mismatch(epoch(e), epoch(s.epoch)),
          xyz.kd5ujc.scarab.ledger.era.Mismatch(epoch(e), limit)
        )
      )
      assertEquals(
        retirements(failures).sortBy(_.current.supplied.toBigInt),
        expected,
        id
      )
      // a mutant whose first mismatch supplies the current epoch has the same cell, and is refused
      val first = retirements(failures).maxBy(_.current.supplied.toBigInt)
      val mutated = failures.map {
        case ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.PoolFailure(r))
            if r == first =>
          ConwayCertsPredFailure.CertFailure[Conway](
            ConwayCertPredFailure.PoolFailure[Conway](
              first.copy(current = first.current.copy(supplied = epoch(s.epoch)))
            )
          )
        case other => other
      }
      assertNotEquals(mutated, failures, id)
      assertEquals(
        FlatRecordings.certsRefused(mutated),
        FlatRecordings.certsRefused(failures),
        s"$id: the cells"
      )
      assert(retirementsWhole(s, failures), id)
      assert(!retirementsWhole(s, mutated), id)
    }
