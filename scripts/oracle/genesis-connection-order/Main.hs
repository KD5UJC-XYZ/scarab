{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
module Main (main) where

import Data.Aeson (Value,object,(.=),encode)
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (sortOn)
import Network.Socket
import Control.Exception (bracket)
import Ouroboros.Network.ConnectionId (ConnectionId(..))
import System.Environment (getArgs)

addressJSON :: SockAddr -> Value
addressJSON address = case address of
 SockAddrInet port host -> let (a,b,c,d) = hostAddressToTuple host in object ["octets" .= [a,b,c,d],"tag" .= ("ipv4"::String),"port" .= (fromIntegral port::Int),"hostWord" .= (fromIntegral host::Integer)]
 SockAddrInet6 port flow words@(a,b,c,d) scope -> let (s1,s2,s3,s4,s5,s6,s7,s8) = hostAddress6ToTuple words in object ["segments" .= [s1,s2,s3,s4,s5,s6,s7,s8],"tag" .= ("ipv6"::String),"port" .= (fromIntegral port::Int),"flowInfo" .= flow,"words" .= [a,b,c,d],"scopeId" .= scope]
 SockAddrUnix path -> object ["tag" .= ("unix"::String),"path" .= path]
connectionJSON :: (Int,ConnectionId SockAddr) -> Value
connectionJSON (identity,ConnectionId{localAddress,remoteAddress}) = object ["id" .= identity,"local" .= addressJSON localAddress,"remote" .= addressJSON remoteAddress]
v4 :: Int -> (Int,Int,Int,Int) -> SockAddr
v4 port (a,b,c,d) = SockAddrInet (fromIntegral port) $ tupleToHostAddress (fromIntegral a,fromIntegral b,fromIntegral c,fromIntegral d)
v6 :: Int -> Int -> (Int,Int,Int,Int,Int,Int,Int,Int) -> Int -> SockAddr
v6 port flow (a,b,c,d,e,f,g,h) scope = SockAddrInet6 (fromIntegral port) (fromIntegral flow) (tupleToHostAddress6 (fromIntegral a,fromIntegral b,fromIntegral c,fromIntegral d,fromIntegral e,fromIntegral f,fromIntegral g,fromIntegral h)) (fromIntegral scope)
row :: String -> [ConnectionId SockAddr] -> Value
row label connections = object ["label" .= label,"input" .= map connectionJSON input,"sortedIds" .= map fst (sortOn snd input),"comparisons" .= [object ["left" .= i,"right" .= j,"ordering" .= show (compare a b)] | (i,a)<-input,(j,b)<-input]]
 where input = zip [(0::Int)..] connections
cases :: [Value]
cases =
 [row "known-address-byte-roundtrips" [ConnectionId (v4 3001 (1,2,3,4)) common,ConnectionId (v6 3001 0 (0x0102,0x0304,0x0506,0x0708,0x090a,0x0b0c,0x0d0e,0x0f10) 0) common]
 ,row "competing-local-and-remote" [ConnectionId (v4 3002 (1,0,0,1)) (v4 1 (1,0,0,1)),ConnectionId (v4 3001 (1,0,0,1)) (v4 65535 (1,0,0,1))]
 ,row "ipv4-hostword-versus-port" [ConnectionId a common | a <- [v4 1 (255,255,255,255),v4 2 (0,0,0,0),v4 3001 (1,0,0,0),v4 3001 (0,0,0,1),v4 3001 (127,0,0,1),v4 3000 (127,0,0,1)]]
 ,row "ipv6-every-field" [ConnectionId a common | a <- [v6 3001 0 zero6 0,v6 3000 1 zero6 0,v6 3000 0 (1,0,0,0,0,0,0,0) 0,v6 3000 0 (0,0,0,0,0,0,0,1) 0,v6 3000 0 zero6 1,v6 3000 0 zero6 0,v6 3000 0 zero6 0]]
 ,row "tcp-family-constructor-order" [ConnectionId (v6 0 0 zero6 0) common,ConnectionId (v4 65535 (255,255,255,255)) common,ConnectionId (v4 0 (0,0,0,0)) common,ConnectionId (v6 65535 0 zero6 0) common]
 ,row "remote-after-equal-local" [ConnectionId common a | a <- addresses]
 ,row "all-tcp-address-pairs" [ConnectionId a b | a <- addresses,b <- addresses]
 ]
 where
 common = v4 3001 (127,0,0,1)
 zero6 = (0,0,0,0,0,0,0,0)
 addresses = [v4 3000 (127,0,0,1),v4 3001 (127,0,0,1),v4 3001 (1,0,0,0),v4 3001 (0,0,0,1),v6 3000 0 zero6 0,v6 3001 0 zero6 0,v6 3001 1 zero6 0,v6 3001 0 zero6 1]
loopback :: IO Value
loopback = withSocketsDo $ bracket (socket AF_INET6 Stream defaultProtocol) close $ \server -> do
 setSocketOption server ReuseAddr 1
 bind server (v6 39133 0 (0,0,0,0,0,0,0,1) 0)
 listen server 1
 bound <- getSocketName server
 bracket (socket AF_INET6 Stream defaultProtocol) close $ \client -> do
  connect client bound
  bracket (fst <$> accept server) close $ \accepted -> do
   clientLocal <- getSocketName client
   clientRemote <- getPeerName client
   serverLocal <- getSocketName accepted
   serverRemote <- getPeerName accepted
   pure $ row "actual-ipv6-loopback-tcp-pair" [ConnectionId clientLocal clientRemote,ConnectionId serverLocal serverRemote]
main :: IO ()
main = do
 args <- getArgs
 actualLoopback <- loopback
 let output = BL.unlines (map encode (cases ++ [actualLoopback]))
 case args of
  [path] -> BL.writeFile path output
  _ -> BL.putStr output
