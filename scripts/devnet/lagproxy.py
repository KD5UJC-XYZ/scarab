# SPDX-License-Identifier: Apache-2.0
"""A TCP proxy for the `peercut` scenario: it forwards to one upstream node, slowly, and on a signal
stops forwarding altogether without closing anything (a peer that has gone silent, which the
follower can only notice by its own timeouts).

    python3 lagproxy.py --listen PORT --upstream PORT --delay SECONDS --control FILE
    python3 lagproxy.py --route LISTEN:UPSTREAM [--route ...] --delay SECONDS --control FILE

Every chunk read from either side is forwarded after `delay`, in order. While FILE exists nothing is
forwarded and new connections are accepted and left silent. It runs in the network namespace of the
reference container, next to the nodes that bind loopback. With repeated `--route` one process serves
several listeners (the `faults` scenario puts every link between nodes behind one, so that a link has ports
of its own to which the kernel's traffic shaping can be addressed). Standard library only.
"""
import argparse
import asyncio
import functools
import os


async def pipe(reader, writer, delay, frozen):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            await asyncio.sleep(delay)
            while frozen():
                await asyncio.sleep(0.2)
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen", type=int)
    p.add_argument("--upstream", type=int)
    p.add_argument("--route", action="append", default=[], metavar="LISTEN:UPSTREAM")
    p.add_argument("--delay", type=float, default=0.1)
    p.add_argument("--control", required=True)
    args = p.parse_args()
    routes = [tuple(int(port) for port in route.split(":")) for route in args.route]
    if args.listen is not None and args.upstream is not None:
        routes.append((args.listen, args.upstream))
    if not routes:
        p.error("give --listen and --upstream, or at least one --route")

    def frozen():
        return os.path.exists(args.control)

    async def serve(upstream, client_reader, client_writer):
        if frozen():
            # a silent peer: accept, say nothing, forward nothing
            try:
                await client_reader.read()
            finally:
                client_writer.close()
            return
        try:
            up_reader, up_writer = await asyncio.open_connection("127.0.0.1", upstream)
        except OSError:
            client_writer.close()
            return
        await asyncio.gather(
            pipe(client_reader, up_writer, args.delay, frozen),
            pipe(up_reader, client_writer, args.delay, frozen),
        )

    servers = [await asyncio.start_server(functools.partial(serve, upstream), "127.0.0.1", listen)
               for listen, upstream in routes]
    await asyncio.gather(*(server.serve_forever() for server in servers))


if __name__ == "__main__":
    asyncio.run(main())
