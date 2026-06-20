#!/usr/bin/env python3
"""Throwaway dev TCP relay for the Wear HR PoC. NOT app code, NOT shipped.

Fans the latest JSON line from any connected client to all other clients, and caches the last
line so a newly-connected subscriber (the watch) immediately gets the current state.
Run on the dev host; bridge devices to it with `adb reverse` (real) or 10.0.2.2 (emulator).
"""
import socket
import threading

HOST, PORT = "0.0.0.0", 8787
clients = set()
last_line = None
lock = threading.Lock()


def handle(conn):
    global last_line
    with lock:
        clients.add(conn)
        if last_line is not None:
            try:
                conn.sendall(last_line)
            except OSError:
                pass
    f = conn.makefile("rb")
    try:
        for raw in f:
            if not raw.strip():
                continue
            with lock:
                last_line = raw
                dead = []
                for c in clients:
                    if c is conn:
                        continue
                    try:
                        c.sendall(raw)
                    except OSError:
                        dead.append(c)
                for c in dead:
                    clients.discard(c)
    finally:
        with lock:
            clients.discard(conn)
        try:
            conn.close()
        except OSError:
            pass


def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen(8)
    print(f"wear-poc relay listening on {HOST}:{PORT}")
    while True:
        conn, _ = s.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
