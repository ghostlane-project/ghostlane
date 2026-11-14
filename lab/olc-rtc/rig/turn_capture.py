#!/usr/bin/env python3
# Copyright 2026 The Ghostlane Project contributors.
# SPDX-License-Identifier: Apache-2.0
"""What Chrome sends a TURN server, captured on loopback (lab only: no
carrier, no secret, nothing leaves the runner).

  turn_capture.py serve <out-dir>   the servers, until killed
  turn_capture.py report <out-dir>  the captures, field by field, and JA4

Servers:
  127.0.0.1:5349, :5350 (TCP)  take a TLS ClientHello (TURN over TLS), save
                               it whole and close: the hello is the capture.
  127.0.0.1:3478 (UDP)         a TURN server's first answer as RFC 8489 has
                               it: 401 with the nonce cookie (password
                               algorithms bit set) and PASSWORD-ALGORITHMS
                               (SHA-256, MD5); every request logged.
  127.0.0.1:3479 (UDP)         the classic 401 (REALM, NONCE, no cookie).
  127.0.0.1:3480 (TCP)         the RFC 8489 answer over TCP.
"""
import hashlib, json, os, socket, struct, sys, threading, time

COOKIE = 0x2112A442
NAMES = {0x0001: 'MAPPED-ADDRESS', 0x0006: 'USERNAME', 0x0008: 'MESSAGE-INTEGRITY',
         0x0009: 'ERROR-CODE', 0x000C: 'CHANNEL-NUMBER', 0x000D: 'LIFETIME',
         0x0012: 'XOR-PEER-ADDRESS', 0x0013: 'DATA', 0x0014: 'REALM', 0x0015: 'NONCE',
         0x0016: 'XOR-RELAYED-ADDRESS', 0x0017: 'REQUESTED-ADDRESS-FAMILY',
         0x0018: 'EVEN-PORT', 0x0019: 'REQUESTED-TRANSPORT', 0x001A: 'DONT-FRAGMENT',
         0x001C: 'MESSAGE-INTEGRITY-SHA256', 0x001D: 'PASSWORD-ALGORITHM',
         0x001E: 'USERHASH', 0x0020: 'XOR-MAPPED-ADDRESS', 0x0022: 'RESERVATION-TOKEN',
         0x8000: 'ADDITIONAL-ADDRESS-FAMILY', 0x8002: 'PASSWORD-ALGORITHMS',
         0x8003: 'ALTERNATE-DOMAIN', 0x8022: 'SOFTWARE', 0x8028: 'FINGERPRINT',
         0x802A: 'ICE-CONTROLLING', 0xC057: 'GOOG-NETWORK-INFO', 0xC060: 'GOOG-MISC-INFO'}
lock = threading.Lock()


def log(out, rec):
    with lock, open(os.path.join(out, 'stun.jsonl'), 'a') as f:
        f.write(json.dumps(rec) + '\n')


def attrs(body):
    out, at = [], 0
    while at + 4 <= len(body):
        t, n = struct.unpack('!HH', body[at:at + 4])
        out.append((t, n, body[at + 4:at + 4 + n]))
        at += 4 + n + (-n % 4)
    return out


def attr(t, v):
    return struct.pack('!HH', t, len(v)) + v + b'\0' * (-len(v) % 4)


def answer(req, rfc8489):
    """A 401 to an Allocate, as the mode says; None to anything else."""
    mtype, _, cookie, txid = struct.unpack('!HHI12s', req[:20])
    if mtype != 0x0003 or cookie != COOKIE:
        return None
    err = attr(0x0009, struct.pack('!HBB', 0, 4, 1) + b'Unauthorized')
    realm = attr(0x0014, b'olc.test')
    if rfc8489:
        # RFC 8489 9.2: "obMatJos2", then the security feature set, 24 bits
        # in 4 base64 characters: bit 0 (password algorithms) set = "gAAA".
        nonce = attr(0x0015, b'obMatJos2gAAA' + os.urandom(8).hex().encode())
        algs = attr(0x8002, struct.pack('!HH', 2, 0) + struct.pack('!HH', 1, 0))
        body = err + realm + nonce + algs
    else:
        body = err + realm + attr(0x0015, os.urandom(8).hex().encode())
    return struct.pack('!HHI12s', 0x0113, len(body), COOKIE, txid) + body


def describe(req):
    mtype, n, _, _ = struct.unpack('!HHI12s', req[:20])
    return {'type': '0x%04x' % mtype, 'len': n,
            'attrs': [[NAMES.get(t, '0x%04x' % t), l] for t, l, _ in attrs(req[20:20 + n])],
            'hex': req[:20 + n].hex()}


def udp(out, port, rfc8489):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(('127.0.0.1', port))
    while True:
        req, peer = s.recvfrom(4096)
        if len(req) < 20:
            continue
        rec = describe(req)
        rec.update(transport='udp', port=port, mode='rfc8489' if rfc8489 else 'classic', t=time.time())
        log(out, rec)
        a = answer(req, rfc8489)
        if a:
            s.sendto(a, peer)


def read_exact(c, n):
    b = b''
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x:
            raise EOFError
        b += x
    return b


def tcp_stun(out, port):
    ln = socket.socket()
    ln.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ln.bind(('127.0.0.1', port))
    ln.listen(16)
    while True:
        c, _ = ln.accept()
        threading.Thread(target=tcp_conn, args=(out, port, c), daemon=True).start()


def tcp_conn(out, port, c):
    c.settimeout(20)
    try:
        while True:
            head = read_exact(c, 20)
            n = struct.unpack('!H', head[2:4])[0]
            req = head + read_exact(c, n)
            rec = describe(req)
            rec.update(transport='tcp', port=port, mode='rfc8489', t=time.time())
            log(out, rec)
            a = answer(req, True)
            if a:
                c.sendall(a)
    except (EOFError, OSError):
        pass
    finally:
        c.close()


def tls_hello(out, port):
    ln = socket.socket()
    ln.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ln.bind(('127.0.0.1', port))
    ln.listen(16)
    k = 0
    while True:
        c, _ = ln.accept()
        c.settimeout(10)
        try:
            raw = read_exact(c, 5)
            rec = read_exact(c, struct.unpack('!H', raw[3:5])[0])
            raw += rec
            # One handshake message; more records only if it spans them.
            want = 4 + int.from_bytes(rec[1:4], 'big')
            got = len(rec)
            while got < want:
                h = read_exact(c, 5)
                r = read_exact(c, struct.unpack('!H', h[3:5])[0])
                raw += h + r
                got += len(r)
            k += 1
            with open(os.path.join(out, 'hello-%d-%02d.bin' % (port, k)), 'wb') as f:
                f.write(raw)
        except (EOFError, OSError):
            pass
        finally:
            c.close()


def serve(out):
    os.makedirs(out, exist_ok=True)
    for f, a in ((udp, (out, 3478, True)), (udp, (out, 3479, False)), (tcp_stun, (out, 3480)),
                 (tls_hello, (out, 5349)), (tls_hello, (out, 5350))):
        threading.Thread(target=f, args=a, daemon=True).start()
    print('serving', flush=True)
    while True:
        time.sleep(3600)


GREASE = {0x0a0a + 0x1010 * i for i in range(16)}


def hello_fields(raw):
    """The ClientHello of `raw` (TLS records): its fields, in order."""
    body, at = b'', 0
    while at + 5 <= len(raw):
        n = struct.unpack('!H', raw[at + 3:at + 5])[0]
        body += raw[at + 5:at + 5 + n]
        at += 5 + n
    assert body[0] == 1, 'not a ClientHello'
    h = body[4:4 + int.from_bytes(body[1:4], 'big')]
    f = {'record_version': raw[1:3].hex(), 'record_len': struct.unpack('!H', raw[3:5])[0],
         'hello_len': len(h), 'legacy_version': h[0:2].hex()}
    at = 34
    sid = h[at]; f['session_id_len'] = sid; at += 1 + sid
    n = struct.unpack('!H', h[at:at + 2])[0]
    f['ciphers'] = ['%04x' % struct.unpack('!H', h[at + 2 + i:at + 4 + i])[0] for i in range(0, n, 2)]
    at += 2 + n
    f['compression'] = h[at + 1:at + 1 + h[at]].hex(); at += 1 + h[at]
    end = at + 2 + struct.unpack('!H', h[at:at + 2])[0]; at += 2
    ext = []
    while at + 4 <= end:
        t, n = struct.unpack('!HH', h[at:at + 4])
        ext.append((t, h[at + 4:at + 4 + n]))
        at += 4 + n
    f['extensions'] = [['grease' if t in GREASE else '%04x' % t, len(v)] for t, v in ext]
    d = dict((t, v) for t, v in ext if t not in GREASE)
    u16 = lambda b: ['grease' if x in GREASE else '%04x' % x for x in struct.unpack('!%dH' % (len(b) // 2), b)]
    if 0x0000 in d:
        f['sni'] = d[0][5:].decode()
    if 0x000a in d:
        f['groups'] = u16(d[0x000a][2:])
    if 0x000d in d:
        f['sigalgs'] = u16(d[0x000d][2:])
    if 0x002b in d:
        f['versions'] = u16(d[0x002b][1:])
    if 0x0010 in d:
        b, at2, al = d[0x0010][2:], 0, []
        while at2 < len(b):
            al.append(b[at2 + 1:at2 + 1 + b[at2]].decode()); at2 += 1 + b[at2]
        f['alpn'] = al
    if 0x0033 in d:
        b, at2, ks = d[0x0033][2:], 0, []
        while at2 + 4 <= len(b):
            g, n = struct.unpack('!HH', b[at2:at2 + 4]); ks.append(['grease' if g in GREASE else '%04x' % g, n]); at2 += 4 + n
        f['key_share'] = ks
    for t, name in ((0x001b, 'compress_certificate'), (0x002d, 'psk_modes'), (0x000b, 'ec_point_formats'),
                    (0x44cd, 'alps_new'), (0x4469, 'alps_old'), (0xfe0d, 'ech'), (0x001c, 'record_size_limit')):
        if t in d:
            f[name] = d[t].hex() if len(d[t]) <= 16 else '%d bytes' % len(d[t])
    f['ja4'] = ja4(f, d)
    return f


def ja4(f, d):
    ver = {'0304': '13', '0303': '12'}.get(next((v for v in f.get('versions', []) if v != 'grease'), f['legacy_version']), '00')
    ciphers = [c for c in f['ciphers'] if int(c, 16) not in GREASE]
    exts = [e for e, _ in f['extensions'] if e != 'grease']
    alpn = f.get('alpn', [])
    a = alpn[0] if alpn else ''
    a = (a[0] + a[-1]) if a else '00'
    head = 't%s%s%02d%02d%s' % (ver, 'd' if 'sni' in f else 'i', min(len(ciphers), 99), min(len(exts), 99), a)
    hc = hashlib.sha256(','.join(sorted(ciphers)).encode()).hexdigest()[:12] if ciphers else '000000000000'
    ex = sorted(e for e in exts if e not in ('0000', '0010'))
    sig = [s for s in f.get('sigalgs', []) if s != 'grease']
    he = hashlib.sha256((','.join(ex) + ('_' + ','.join(sig) if sig else '')).encode()).hexdigest()[:12]
    return '%s_%s_%s' % (head, hc, he)


def report(out):
    rows = {}
    for name in sorted(os.listdir(out)):
        if name.startswith('hello-') and name.endswith('.bin'):
            raw = open(os.path.join(out, name), 'rb').read()
            rows[name] = hello_fields(raw)
            open(os.path.join(out, name[:-4] + '.hex'), 'w').write(raw.hex() + '\n')
    stun = []
    if os.path.exists(os.path.join(out, 'stun.jsonl')):
        stun = [json.loads(l) for l in open(os.path.join(out, 'stun.jsonl'))]
    json.dump({'hellos': rows, 'stun': stun}, open(os.path.join(out, 'report.json'), 'w'), indent=1)
    for name, f in rows.items():
        print('==', name, f['ja4'], 'sni=%s' % f.get('sni'), 'alpn=%s' % f.get('alpn'))
        print('   ext order:', ' '.join('%s/%d' % (e, n) for e, n in f['extensions']))
        for k in ('ciphers', 'groups', 'key_share', 'sigalgs', 'versions', 'compress_certificate', 'alps_new', 'alps_old', 'ech', 'record_size_limit', 'psk_modes', 'ec_point_formats', 'hello_len', 'record_len', 'session_id_len'):
            if k in f:
                print('   %s: %s' % (k, f[k]))
    seen = set()
    for r in stun:
        key = (r['transport'], r['mode'], r['type'], tuple(a for a, _ in r['attrs']))
        if key in seen:
            continue
        seen.add(key)
        print('stun %s/%s %s %s' % (r['transport'], r['mode'], r['type'], ' '.join('%s/%d' % (a, n) for a, n in r['attrs'])))


if __name__ == '__main__':
    {'serve': serve, 'report': report}[sys.argv[1]](sys.argv[2])
