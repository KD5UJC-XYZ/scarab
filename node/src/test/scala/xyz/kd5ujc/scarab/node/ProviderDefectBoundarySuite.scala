// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.Handle
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.ledger.{
  Header,
  HeaderVrf,
  NodeError,
  ProviderDefect,
  ProviderDefectException,
  Verdicts
}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.read.ChainRead.given

/** A crypto provider defect reaches the session boundary as a fatal defect (issue 393): the Praos
  * header rules throw it, `SessionEnd.scoped` ends the session with it, and the policy stops the
  * node, with no blame for the peer. A genuinely invalid proof is still an invalid header.
  */
class ProviderDefectBoundarySuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private val boom = new IllegalStateException("provider broke")

  private lazy val forged: ForgedChain =
    ForgedChain.forge(0 until 90, epoch = 0).unsafeRunSync()(using munitIORuntime)

  private def ref(h: Header.Shelley) = BlockRef(h.slot, h.hash, h.blockNo)

  private val index = 10

  private def before: NodeHeaderState.Shelley =
    NodeHeaderState.Shelley(
      forged.states(index - 1).protocol,
      Some(ref(forged.headers(index - 1))),
      forged.genesis.consensus.byron.firstShelleyEpoch,
      None
    )

  private def validate(verdicts: Verdicts, header: Header.Shelley) =
    NodeHeaderRules[MirrorState](
      ForgedChain.config.copy(verdicts = verdicts),
      ForgedChain.globals
    ).validate(
      forged.states(index - 1),
      Some(ref(forged.headers(index - 1))),
      before,
      header
    )

  private def stub(vrf: Vrf.Result) = new Verdicts:
    export Verdicts.Direct.{keyHash, opCert, takePrepared, tpraosVrf, witness}
    def praosVrf(
        input: Vrf.Input,
        vkey: Bytes,
        proof: Bytes,
        output: Bytes
    ): Vrf.Result = vrf

  test("the Praos rules throw a VRF provider defect instead of rejecting the header") {
    val failing = Vrf.Result.InternalFailure(boom)
    val thrown =
      intercept[ProviderDefectException](validate(stub(failing), forged.headers(index)))
    assertEquals(thrown.defect, ProviderDefect.VrfVerifier(failing))
    val contract = Vrf.Result.PrimitiveContractViolation(None)
    assertEquals(
      intercept[ProviderDefectException](validate(stub(contract), forged.headers(index))).defect,
      ProviderDefect.VrfVerifier(contract)
    )
  }

  test("negative control: the real verifier still rejects an invalid proof as a typed failure") {
    val header = forged.headers(index)
    val bad = header.vrf match
      case HeaderVrf.Praos(c) =>
        val proof = c.proof.toArray
        proof(3) = (proof(3) ^ 1).toByte
        header.copy(vrf = HeaderVrf.Praos(c.copy(proof = Bytes.fromArray(proof))))
      case other => fail(s"unexpected $other")
    validate(Verdicts.Direct, bad) match
      case Outcome.Invalid(_) => ()
      case other              => fail(s"unexpected $other")
    assert(validate(Verdicts.Direct, header).isInstanceOf[Outcome.Valid[?]])
  }

  test("a provider defect ends the session as a defect and stops the node, without peer blame") {
    NodeScope.resource
      .use { node =>
        SessionEnd.scoped[IO](node) { (_: Handle[IO, SessionEnd], _: Handle[IO, NodeError]) ?=>
          IO(validate(stub(Vrf.Result.InternalFailure(boom)), forged.headers(index)))
            .as(SessionEnd.Returned)
        }
      }
      .map { ended =>
        ended match
          case SessionEnd.Defect(cause: ProviderDefectException) =>
            assertEquals(cause.defect.cause, Some(boom))
          case other => fail(s"unexpected $other")
        FailurePolicy.disposition(ended) match
          case Disposition.StopNode(Fatal.Defect(_: ProviderDefectException)) => ()
          case other => fail(s"unexpected $other")
        assertEquals(SessionEnd.peerError(ended), None)
        assertEquals(Main.FaultExitCode(Fatal.Defect(boom)).code, 7)
      }
  }
