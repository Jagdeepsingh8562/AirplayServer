#!/usr/bin/env python3
"""Treats the receiver's port the way a bad network does, and checks it still answers afterwards.

    python3 tools/connection_abuse.py 192.168.1.26 <port>

The port is the one `dns-sd -L "Fire Stick" _airplay._tcp local.` prints. This cannot reach the mirror stream
reader (that only opens after a completed pairing); it covers the HTTP/RTSP front: connections that close, half
requests, garbage, hard resets (RST) and more connections than the receiver allows (10). While it runs,
`adb logcat -s raop` should show "Connection closed" and "Connection error ... reset by peer" lines and never a crash.
"""
import socket
import struct
import sys
import time


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    host, port = sys.argv[1], int(sys.argv[2])

    def connect():
        return socket.create_connection((host, port), timeout=3)

    def reset(sock):
        # SO_LINGER with a zero timeout makes close() send a RST instead of a FIN
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        sock.close()

    s = connect()
    s.close()
    print("a) silent connect, clean close: done")

    s = connect()
    s.sendall(b"POST /fp-setup RTSP/1.0\r\nCSeq: 1\r\nContent-Len")
    time.sleep(0.2)
    reset(s)
    print("b) half a request, then RST: done")

    s = connect()
    s.sendall(bytes(range(256)) * 4)
    time.sleep(0.2)
    reset(s)
    print("c) garbage, then RST: done")

    socks = []
    for i in range(14):
        try:
            socks.append(connect())
        except OSError as error:
            print("   connection %d refused: %s" % (i, error))
    time.sleep(0.5)
    for s in socks:
        reset(s)
    print("d) 14 silent connections (the limit is 10), then RST: done")

    time.sleep(1.0)
    s = connect()
    s.settimeout(3)
    s.sendall(b"GET /info HTTP/1.1\r\nHost: x\r\n\r\n")
    started = time.time()
    try:
        reply = s.recv(200).split(b"\r\n")[0].decode(errors="replace")
    except OSError as error:
        print("e) NO ANSWER after the abuse: %s" % error)
        sys.exit(1)
    finally:
        s.close()
    print("e) answered after the abuse: %s in %.2f s" % (reply, time.time() - started))


if __name__ == "__main__":
    main()
