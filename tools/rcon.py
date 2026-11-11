import os, socket, struct, sys, time

def pkt(rid, typ, body):
    data = struct.pack('<ii', rid, typ) + body.encode('utf8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data

def recv(s):
    raw = b''
    while len(raw) < 4:
        c = s.recv(4 - len(raw))
        if not c:
            raise EOFError
        raw += c
    n = struct.unpack('<i', raw)[0]
    buf = b''
    while len(buf) < n:
        buf += s.recv(n - len(buf))
    rid, typ = struct.unpack('<ii', buf[:8])
    return rid, typ, buf[8:-2].decode('utf8', 'replace')

def main():
    cmds = sys.argv[1:]
    # Parallel worktrees use different ports: set VP_RCON_PORT (default 25575).
    s = socket.create_connection(('127.0.0.1', int(os.environ.get('VP_RCON_PORT', '25575'))), timeout=30)
    s.sendall(pkt(1, 3, 'vptest'))
    rid, _, _ = recv(s)
    if rid == -1:
        print('AUTH FAILED'); return
    for c in cmds:
        s.sendall(pkt(2, 2, c))
        _, _, body = recv(s)
        print('> ' + c)
        if body:
            print(body)
    s.close()

main()
