-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE ScopedTypeVariables #-}
module Marker (Observer, Marker, calibrate, start, punishment, finish) where

import qualified Control.Concurrent as C
import qualified Control.Exception as E
import Data.Typeable (TypeRep, typeOf)
import qualified Ouroboros.Consensus.Storage.ChainDB.API.Types.InvalidBlockPunishment as P

newtype Observer = Observer TypeRep
data Marker = Marker
  { punishment :: P.InvalidBlockPunishment IO
  , release :: C.MVar ()
  , result :: C.MVar (Either E.SomeException ())
  , done :: C.MVar ()
  }

-- The child creates its own target, publishes it, then blocks until either the
-- real native punishment interrupts it or its owner explicitly releases it.
start :: IO Marker
start = do
 ready <- C.newEmptyMVar
 gate <- C.newEmptyMVar
 outcome <- C.newEmptyMVar
 complete <- C.newEmptyMVar
 _ <- C.forkIO $ E.finally
  (E.try (do p <- P.mkPunishThisThread; C.putMVar ready p; C.takeMVar gate) >>= C.putMVar outcome)
  (C.putMVar complete ())
 p <- C.takeMVar ready
 pure $ Marker p gate outcome complete

complete :: Marker -> IO (Either E.SomeException ())
complete marker = do
 _ <- C.tryPutMVar (release marker) ()
 C.takeMVar (done marker)
 C.takeMVar (result marker)

exceptionType :: E.SomeException -> TypeRep
exceptionType (E.SomeException err) = typeOf err

-- The native exception is deliberately not exported. Discover its actual type
-- using the opaque API itself; never classify it by rendered diagnostic text.
calibrate :: IO Observer
calibrate = do
 marker <- start
 P.enact (punishment marker) P.BlockItself
 outcome <- complete marker
 case outcome of
  Left err -> pure $ Observer (exceptionType err)
  Right () -> fail "native unconditional punishment did not interrupt its target"

finish :: Observer -> Marker -> IO Bool
finish (Observer expected) marker = do
 outcome <- complete marker
 case outcome of
  Right () -> pure False
  Left err | exceptionType err == expected -> pure True
           | otherwise -> E.throwIO err
