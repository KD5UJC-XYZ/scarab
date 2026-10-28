// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.network.{ConnectionEnd, LocalTxSubmissionServer}

/** The listener's connection outcome. Service and unexpected failures retain their causes until
  * logging, including local-client connections that do not enter the node-to-node manager.
  */
enum InboundEnd:
  case Connection(end: ConnectionEnd)
  case Local(end: LocalTxSubmissionServer.End)
  case Storage(error: StorageError)
  case Defect(cause: Throwable)

  /** What would stop the node on a node-to-node connection ended a node-to-client one alone. */
  case Fault(fault: Fatal)

  def message: String = this match
    case Connection(end) => end.message
    case Local(end)      => end.message
    case Storage(error)  => s"storage: ${error.message}"
    case Defect(cause)   => s"failed: ${cause.getClass.getSimpleName}: ${cause.getMessage}"
    case Fault(fault)    => fault.message
