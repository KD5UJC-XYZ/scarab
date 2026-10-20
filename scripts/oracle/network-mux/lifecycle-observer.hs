-- SPDX-License-Identifier: Apache-2.0
-- Appended to the pinned Test.Mux module in a disposable source tree only.
-- The original property bodies and acceptance conditions are retained.

exportLifecycleCases :: FilePath -> Int -> Int -> IO ()
exportLifecycleCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        size = 8 + ordinal `mod` 9
        reqs = unGen arbitrary (mkQCGen caseSeed) size :: [Int]
        acc = unGen arbitrary (mkQCGen (caseSeed + 1)) size :: Int
        Positive sduSize = unGen arbitrary (mkQCGen (caseSeed + 2)) size :: Positive Word16
        fault = [CleanShutdown, CloseOnWrite, CloseOnRead] !! (ordinal `mod` 3)
        -- A bounded lawful function is sufficient to expose loss, duplication and ordering;
        -- the native request/response protocol actually computes and observes every result.
        fn a r = (a + r, a - r)
        (observed, verdict) = runSimOrThrow $ observeLifecycleClose fault sduSize reqs fn acc
        settings = stdArgs { chatty = False, maxSuccess = 1,
                             replay = Just (mkQCGen caseSeed, size) }
    sim <- quickCheckWithResult settings (once verdict)
    io <- quickCheckWithResult settings (once (prop_mux_close_io fault reqs fn acc))
    let responses = either id id <$> observed
        served = take (length (maybe [] id responses)) reqs
        responseWire = maybe [] (map (wire . MsgResp)) responses
        requestWire = map (wire . (MsgReq :: Int -> MsgReqResp Int Int)) served
        clean = case observed of Just (Right _) -> "clean"; Just (Left _) -> "early"; _ -> "error"
    hPutStrLn outputHandle $ intercalate "\t"
      [ "mux-close-v1", show caseSeed, show ordinal, show size, show fault, show sduSize
      , intercalate "," (map show reqs), show acc, clean
      , intercalate "," (maybe [] (map show) responses)
      , intercalate "," requestWire, intercalate "," responseWire
      , result sim, result io ]
    let reminder = unGen arbitrary (mkQCGen (caseSeed + 3)) size :: BL.ByteString
        received@(NonEmptyByteString receivedBytes) =
          unGen arbitrary (mkQCGen (caseSeed + 4)) size
    trailingSim <- quickCheckWithResult settings (once (prop_mux_trailing_bytes_iosim reminder received))
    trailingIo <- quickCheckWithResult settings (once (prop_mux_trailing_bytes_io reminder received))
    hPutStrLn outputHandle $ intercalate "\t"
      [ "mux-trailing-v1", show caseSeed, show ordinal, show size
      , hex reminder, hex receivedBytes, hex (reminder <> receivedBytes)
      , result trailingSim, result trailingIo ]
  where
    result r = if isSuccess r then "pass" else "upstream-failure"
    hex = concatMap (pad . (`showHex` "")) . BL.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
    wire :: MsgReqResp Int Int -> String
    wire = hex . serialise

-- Same simulated network setup as prop_mux_close_sim. The observer records the actual client
-- result after both native applications have joined; the native property still checks both ends.
observeLifecycleClose :: forall s. FaultInjection -> Word16 -> [Int]
                      -> (Int -> Int -> (Int, Int)) -> Int
                      -> IOSim s (Maybe (Either [Int] [Int]), Property)
observeLifecycleClose fault sduSize reqs fn acc = do
  observed <- newTVarIO Nothing
  (chann, chann') <- atomically $ newConnectedAttenuatedChannelPair
                      nullTracer nullTracer noAttenuation noAttenuation
  let context :: forall b. AttenuatedChannel (IOSim s) -> NetworkCtx (AttenuatedChannel (IOSim s)) (IOSim s) b
      context chan = NetworkCtx {
        ncSocket = return chan,
        ncClose = acClose,
        ncMuxBearer = \fd k -> k $ attenuationChannelAsBearer (Mx.SDUSize sduSize) 10 fd }
  verdict <- close_experimentObserved
               (\value -> atomically $ writeTVar observed value)
               False fault nullTracer nullTracer (context chann) (context chann') reqs fn acc
  value <- readTVarIO observed
  return (value, verdict)
  where
    noAttenuation = Attenuation {
      aReadAttenuation = \_ _ -> (1, AttenuatedChannel.Success),
      aWriteAttenuation = Nothing }
