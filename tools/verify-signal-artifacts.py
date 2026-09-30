#!/usr/bin/env python3
"""Verify libsignal artifacts against Signal's release-signing key, before pinning their checksums.

verification-metadata.xml pins SHA-256, which proves an artifact is the one first downloaded - not
that it is genuine. For org.signal that first download comes from build-artifacts.signal.org, the
only place libsignal is published after 0.86.5, so it is checked here against the key that signed
the Maven Central releases this app shipped before (Maven Central requires signed artifacts).

Why not gpg: every public keyserver serves this key without its user ID (keys.openpgp.org strips
unverified ones), and gpg and gpgv both refuse a key with no user ID. This checks the OpenPGP v4
RSA signature directly: SHA-2 over the file and the hashed signature subpackets, PKCS#1 v1.5.

    curl -fsSL https://keys.openpgp.org/vks/v1/by-fingerprint/2F6EB0E577CA8474A2F9E676BB7EE61AD5989884 -o signal.asc
    tools/verify-signal-artifacts.py signal.asc FILE FILE.asc [FILE FILE.asc ...]

Exits non-zero unless the key is the pinned one and every signature is good. Measured on
2026-09-30: libsignal-client and libsignal-android 0.103.0 (Signal's repository) and 0.86.5 (Maven
Central) all GOOD under this key; a truncated jar and a jar with one byte changed both BAD.
"""
import base64, hashlib, sys
def dearmor(t):
    lines=t.strip().splitlines(); i=lines.index(''); body=[l for l in lines[i+1:] if not l.startswith('=') and not l.startswith('-----')]
    return base64.b64decode(''.join(body))
def packets(b):
    i=0
    while i<len(b):
        h=b[i]; i+=1
        if h&0x40:
            tag=h&0x3f; l=b[i]; i+=1
            if l>=192 and l<224: l=((l-192)<<8)+b[i]+192; i+=1
            elif l==255: l=int.from_bytes(b[i:i+4],'big'); i+=4
        else:
            tag=(h>>2)&0xf; lt=h&3; n=[1,2,4][lt]; l=int.from_bytes(b[i:i+n],'big'); i+=n
        yield tag,b[i:i+l]; i+=l
def mpi(b,i):
    bits=int.from_bytes(b[i:i+2],'big'); n=(bits+7)//8; return int.from_bytes(b[i+2:i+2+n],'big'), i+2+n
pairs = sys.argv[2:]
if len(sys.argv) < 2 or not pairs or len(pairs) % 2:
    # Refused rather than tolerated: zip() drops an unpaired trailing file, so a missing .asc used to
    # exit 0 having verified nothing.
    sys.exit("usage: verify-signal-artifacts.py KEY.asc FILE FILE.asc [FILE FILE.asc ...]")
key=dearmor(open(sys.argv[1]).read())
pk=[p for t,p in packets(key) if t==6][0]
assert pk[0]==4 and pk[5]==1, "expect v4 RSA"
fpr=hashlib.sha1(b'\x99'+len(pk).to_bytes(2,'big')+pk).hexdigest().upper()
n,i=mpi(pk,6); e,_=mpi(pk,i)
print("key fingerprint", fpr)
PINNED = "2F6EB0E577CA8474A2F9E676BB7EE61AD5989884"
if fpr != PINNED:
    sys.exit("not Signal's release key: expected " + PINNED)
bad = 0
H={8:('sha256',bytes.fromhex('3031300d060960864801650304020105000420')),10:('sha512',bytes.fromhex('3051300d060960864801650304020305000440')),9:('sha384',bytes.fromhex('3041300d060960864801650304020205000430'))}
for data_path,sig_path in zip(sys.argv[2::2],sys.argv[3::2]):
    sig=[p for t,p in packets(dearmor(open(sig_path).read())) if t==2][0]
    assert sig[0]==4
    halg=sig[3]; hl=int.from_bytes(sig[4:6],'big'); hashed_end=6+hl
    ul=int.from_bytes(sig[hashed_end:hashed_end+2],'big'); j=hashed_end+2+ul+2
    s,_=mpi(sig,j)
    name,prefix=H[halg]; h=hashlib.new(name)
    with open(data_path,'rb') as f:
        for chunk in iter(lambda:f.read(1<<20),b''): h.update(chunk)
    h.update(sig[:hashed_end]); h.update(b'\x04\xff'+hashed_end.to_bytes(4,'big'))
    d=h.digest(); k=(n.bit_length()+7)//8
    em=pow(s,e,n).to_bytes(k,'big'); t=prefix+d
    ok = em == b'\x00\x01'+b'\xff'*(k-len(t)-3)+b'\x00'+t
    print(("GOOD " if ok else "BAD  ")+data_path)
    bad += 0 if ok else 1
sys.exit(1 if bad else 0)
