{-# LANGUAGE DataKinds, NamedFieldPuns, ScopedTypeVariables, TypeApplications #-}
module Transport (captureAt) where

import qualified Cardano.Network.NodeToClient as N
import Control.Exception (throwIO)
import Control.Tracer (nullTracer)
import Data.IORef
import qualified Data.Map.Strict as Map
import Data.Proxy (Proxy(..))
import qualified Network.Mux.Trace as Mux
import Ouroboros.Consensus.Block (CodecConfig, Point)
import Ouroboros.Consensus.Cardano.Node ()
import qualified Ouroboros.Consensus.Network.NodeToClient as C
import qualified Ouroboros.Consensus.Node.NetworkProtocolVersion as C
import qualified Ouroboros.Network.Mux as Mux
import qualified Ouroboros.Network.Protocol.LocalStateQuery.Client as LSQ
import qualified Ouroboros.Network.Protocol.LocalStateQuery.Type as LSQ
import Session

-- The caller supplies the codec configuration and exact expected wire version.
-- No alternative version or acquire target is selected silently.
-- withIOManager/connectTo own socket teardown; async failures propagate outward.
captureAt :: CodecConfig Block -> N.NodeToClientVersionData -> N.NodeToClientVersion
          -> FilePath -> Point Block -> IO (Either String Capture)
captureAt config versionData wanted socket requested = do
  result <- newIORef (Left "connection ended before a released capture")
  case Map.lookup wanted (C.supportedNodeToClientVersions (Proxy @Block)) of
    Nothing -> pure (Left "requested node-to-client version unsupported by pinned library")
    Just blockVersion -> N.withIOManager $ \manager -> do
      let C.Codecs{C.cChainSyncCodec,C.cTxSubmissionCodec,C.cStateQueryCodec,C.cTxMonitorCodec} =
            C.clientCodecs config blockVersion wanted
          protocols = N.NodeToClientProtocols
            { N.localChainSyncProtocol = Mux.InitiatorProtocolOnly $
                Mux.mkMiniProtocolCbFromPeer $ const (nullTracer,cChainSyncCodec,N.chainSyncPeerNull)
            , N.localTxSubmissionProtocol = Mux.InitiatorProtocolOnly $
                Mux.mkMiniProtocolCbFromPeer $ const (nullTracer,cTxSubmissionCodec,N.localTxSubmissionPeerNull)
            , N.localStateQueryProtocol = Mux.InitiatorProtocolOnly $
                Mux.mkMiniProtocolCbFromPeerSt $ const
                  (nullTracer,cStateQueryCodec,LSQ.StateIdle,LSQ.localStateQueryClientPeer (session requested result))
            , N.localTxMonitorProtocol = Mux.InitiatorProtocolOnly $
                Mux.mkMiniProtocolCbFromPeer $ const (nullTracer,cTxMonitorCodec,N.localTxMonitorPeerNull)
            }
          versions = N.versionedNodeToClientProtocols wanted versionData protocols
      outcome <- N.connectTo (N.localSnocket manager)
        N.NetworkConnectTracers{N.nctMuxTracers=Mux.nullTracers,N.nctHandshakeTracer=nullTracer}
        versions socket
      case outcome of
        Left err -> throwIO err
        Right _ -> readIORef result
