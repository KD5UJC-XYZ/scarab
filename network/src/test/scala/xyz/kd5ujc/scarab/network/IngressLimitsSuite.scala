// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import munit.FunSuite

/** The ingress queue limits are those of the reference (ouroboros-network 1.2.0.0,
  * `Cardano.Network.NodeToNode`, with the default mini-protocol parameters).
  */
class IngressLimitsSuite extends FunSuite:
  private val limit = IngressLimits.nodeToNode()

  test("ChainSync: 1.1 * 300 pipelined replies * 1400 bytes"):
    assertEquals(limit(Protocols.ChainSync), 462000L)

  test("BlockFetch: 1.1 * max(10 * 2 MiB, 100 * 90112 bytes)"):
    assertEquals(limit(Protocols.BlockFetch), 23068694L)

  test("TxSubmission2: 1.1 * 10 unacknowledged ids * (44 + 65540 bytes)"):
    assertEquals(limit(Protocols.TxSubmission2), 721424L)

  test("KeepAlive: 1.1 * 1280 bytes"):
    assertEquals(limit(Protocols.KeepAlive), 1408L)

  test("Handshake: the message limit of the protocol (4 * 1440 bytes)"):
    assertEquals(limit(Protocols.Handshake), 5760L)

  test("the limits follow the parameters they are derived from"):
    val deeper =
      IngressLimits.nodeToNode(IngressLimits.Parameters(chainSyncPipeliningHighMark = 600))
    assertEquals(deeper(Protocols.ChainSync), 924000L)

  test("node-to-client queues are as large as the field says"):
    assertEquals(IngressLimits.nodeToClient(Protocols.LocalStateQuery), 0xffffffffL)
    assertEquals(limit(Protocols.LocalTxMonitor), 0xffffffffL)
