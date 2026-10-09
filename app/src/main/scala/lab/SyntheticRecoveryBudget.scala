// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.header.PraosCertificateState as Certificate
import lab.ledger.{
  ConwayStake as Stake,
  ConwayEpochBoundary as Boundary,
  ConwayRewardPulser as Pulser
}
import scala.util.control.NonFatal

/** Conservative logical payload accounting, not a JVM heap measurement or a wire format. Boundary
  * and certificate-context capsules are deduplicated by authenticated identity before this
  * traversal. Repeated occurrences within the supplied roots are charged repeatedly; traversal
  * stops at the first exceeded limit.
  */
private[lab] object SyntheticRecoveryBudget:
  val MaxEnvelopeBytes = 65535
  val MaxBlockBytes = 1048576
  val MaxHistoricalScalarBits = 256
  val MaxEntries = 200000L
  val MaxPayloadBytes = 16L * 1024 * 1024
  final case class Measurement(entries: Long, payloadBytes: Long)

  private final class Counter:
    private var entries = 0L
    private var bytes = 0L
    def add(n: Long, size: Long): Unit =
      require(
        n >= 0 && size >= 0 && n <= MaxEntries - entries &&
          size <= MaxPayloadBytes - bytes,
        "synthetic recovery aggregate budget exceeded"
      )
      entries += n
      bytes += size
    def blob(b: Bytes): Unit =
      require(b != null, "recovery payload required")
      add(1, b.size.toLong + 8)
    // Fixed scalar allowance includes identities, bounded integers, tags and field framing.
    def scalar(): Unit = add(1, 1024)
    def credential(c: Stake.Credential): Unit =
      blob(c.hash)
      add(1, 1)
    def counts(m: Map[Bytes, BigInt]): Unit =
      m.foreach { (key, _) =>
        blob(key); add(1, 32)
      }
    def accounts(m: Map[Stake.Credential, Stake.Account]): Unit =
      m.foreach { (c, a) =>
        credential(c); add(1, 64); a.delegation.foreach(blob)
      }
    def stakeContext(c: Stake.Context): Unit =
      scalar(); accounts(c.accounts)
      c.pools.foreach { (key, p) =>
        blob(key); scalar(); blob(p.vrf); credential(p.rewardAccount)
        p.owners.foreach(blob)
        p.delegators.foreach(credential)
      }
    def snapshot(s: Stake.Snapshot): Unit =
      scalar()
      s.active.foreach { (c, a) =>
        credential(c); blob(a.pool); add(1, 32)
      }
      s.pools.foreach { (key, p) =>
        blob(key); scalar(); blob(p.vrf); credential(p.rewardAccount)
        p.owners.foreach(blob)
      }
    def snapshots(s: Stake.Snapshots): Unit =
      scalar(); snapshot(s.mark); snapshot(s.set); snapshot(s.go)
    def stake(s: Stake.State): Unit =
      scalar(); stakeContext(s.context)
      s.utxo.foreach { (key, output) =>
        blob(key.id); add(1, 64); blob(output.original)
        output.credential.foreach(credential)
      }
      s.instantaneous.foreach { (c, _) =>
        credential(c); add(1, 32)
      }
      snapshots(s.snapshots)
    def boundaryContext(c: Boundary.Context): Unit =
      scalar(); stake(c.stake); stakeContext(c.application)
      counts(c.previousBlocks); counts(c.currentBlocks)
    def frozen(f: Boundary.Frozen): Unit =
      scalar(); blob(f.previousParameters)
      f.rewardParameters.foreach(p => blob(p.original))
      f.rewardGlobals.foreach(g => blob(g.original))
      val view = Boundary.frozenRecoveryView(f)
      boundaryContext(view.calculation)
      view.applicationBinding.foreach { a =>
        scalar(); counts(a._4)
      }
    def reward(r: Boundary.Reward): Unit =
      blob(r.pool); add(1, 40)
    def rewardSets(m: Map[Stake.Credential, Set[Boundary.Reward]]): Unit =
      m.foreach { (c, rs) =>
        credential(c); rs.foreach(reward)
      }
    def certificate(s: Certificate.State): Unit =
      scalar(); counts(s.counters)
    def certificateContext(c: Certificate.Context): Unit =
      scalar()
      c.registrations.foreach { (key, value) =>
        blob(key); blob(value)
        // The synthetic epoch leadership table is a subset of this pool registry.
        // Charge its key and bounded fraction even when this context has no such table.
        add(1, 96)
      }
    def original(o: BoundedChainFollower.Original): Unit =
      require(
        o != null && o.envelope != null && o.block != null &&
          o.envelope.size <= MaxEnvelopeBytes && o.block.size <= MaxBlockBytes,
        "recovery original individual byte bounds"
      )
      blob(o.envelope); blob(o.block)
    def branch(b: CertificateBranch.Branch): Unit =
      require(b.steps.size <= 8 && b.acquisition.originals.size <= 8, "recovery branch capacity")
      scalar(); certificate(b.initial); certificate(b.state)
      b.acquisition.originals.foreach(original)
      b.steps.foreach { s =>
        scalar(); certificate(s.before); certificate(s.after); blob(s.observation.originalBody)
      }
    def ledger(s: lab.ledger.ClusterTransition.State): Unit =
      scalar(); blob(s.outputMap); add(s.size.toLong, 64L * s.size)
    def state(s: CoherentSequence.State): Unit =
      scalar(); branch(s.certificates); ledger(s.ledger); s.stake.foreach(stake)
      s.syntheticRewards.foreach { r =>
        scalar(); blob(r.profile.parameters.original); blob(r.profile.globals.original)
        counts(r.previousBlocks); counts(r.currentBlocks)
        r.frozen.foreach(frozen)
        r.pulser.foreach { p =>
          scalar(); p.traversal.foreach(credential)
          p.members.foreach { (c, v) =>
            credential(c); reward(v)
          }
          // Prepared work keeps one projection per frozen pool, including snapshot owner sets.
          val workFrozen = Pulser.frozenForRecovery(p)
          frozen(workFrozen)
          snapshot(workFrozen.go)
          workFrozen.go.pools.foreach { (_, _) => scalar() }
          p.completion.foreach { d =>
            scalar();
            d.poolIdentities.foreach { (key, value) =>
              blob(key); blob(value)
            }
            d.members.foreach { (c, v) =>
              credential(c); reward(v)
            }
            rewardSets(d.leaders)
            d.poolTotals.foreach { (key, _) =>
              blob(key); add(1, 96)
            }
            rewardSets(d.completed.rewards)
            scalar(); frozen(d.completed.inputs.frozen)
          }
        }
      }
    def boundary(b: Boundary.Preview): Unit =
      scalar(); boundaryContext(b.before)
      b.balances.foreach { (c, _) =>
        credential(c); add(1, 32)
      }
      snapshots(b.rotation.snapshots); snapshot(b.rotation.leadership)
      b.rewardApplication.foreach { a =>
        scalar(); accounts(a.accounts); rewardSets(a.registered); rewardSets(a.unregistered)
        a.credited.foreach { (c, _) =>
          credential(c); add(1, 32)
        }
      }
    def result: Measurement = Measurement(entries, bytes)

  def measure(
      context: SequenceInput.Context,
      states: Vector[CoherentSequence.State],
      branches: Vector[CertificateBranch.Branch],
      boundaries: Vector[Boundary.Preview],
      certificateContexts: Vector[Certificate.Context],
      originals: Vector[BoundedChainFollower.Original] = Vector.empty
  ): Either[String, Measurement] =
    try
      require(
        context != null && states != null && branches != null && boundaries != null &&
          certificateContexts != null && originals != null,
        "recovery inputs required"
      )
      require(
        states.nonEmpty && states.size <= 9 && branches.size <= 9 &&
          boundaries.size <= 9 && certificateContexts.size <= 11 && originals.size <= 8,
        "recovery record capacity"
      )
      val c = new Counter
      c.scalar()
      originals.foreach(c.original)
      context.originals.foreach { (key, value) =>
        c.add(1, key.length.toLong * 4); c.blob(value)
      }
      context.sourcePins.foreach { (key, value) =>
        c.add(1, key.length.toLong * 4); c.blob(value)
      }
      c.certificateContext(context.certificates); c.certificate(context.certificateSeed)
      c.certificateContext(context.eligibility.certificates)
      c.certificate(context.eligibility.seed)
      context.eligibility.stakes.foreach { (key, _) =>
        c.blob(key); c.add(1, 64)
      }
      c.ledger(context.ledger)
      states.foreach(c.state); branches.foreach(c.branch); boundaries.foreach(c.boundary)
      certificateContexts.foreach(c.certificateContext)
      Right(c.result)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid recovery payload"))
