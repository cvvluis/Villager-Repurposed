#!/usr/bin/env python3
"""Fail when a class inside the release jar references a de/villagerplus class that is not in the jar.

The release jar strips *Checks and dev classes (see build.gradle). A production class that still touches one of them
fails at runtime with NoClassDefFoundError (that is how 0.11.2 crashed a world on entry). Usage: check-jar-refs.py <jar>
"""
import re, struct, sys, zipfile

def utf8_constants(data):
    # minimal constant pool walk; returns all CONSTANT_Utf8 strings
    pos = 10
    count = struct.unpack('>H', data[8:10])[0]
    out = []
    i = 1
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack('>H', data[pos + 1:pos + 3])[0]
            out.append(data[pos + 3:pos + 3 + n].decode('utf-8', 'replace'))
            pos += 3 + n
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag in (5, 6):
            pos += 9; i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 3
        elif tag == 15:
            pos += 4
        else:
            raise SystemExit('bad constant pool tag %d' % tag)
        i += 1
    return out

# Dev-only entry points that load these classes lazily (guarded by a dev file/command); never on a normal code path.
GUARDED = {
    ('de/villagerplus/client/ClientChecks', 'de/villagerplus/client/VillagerPlusClient'),
    ('de/villagerplus/dev/Checks090', 'de/villagerplus/StatusCommand'),
    ('de/villagerplus/dev/FoodDiag', 'de/villagerplus/StatusCommand'),
}

def is_test_class(name):
    return 'Checks' in name or name.startswith('de/villagerplus/dev/')

def main(path):
    z = zipfile.ZipFile(path)
    names = {n[:-6] for n in z.namelist() if n.endswith('.class')}
    bad = {}
    pat = re.compile(r'(?<![A-Za-z0-9_/$])(de/villagerplus/[A-Za-z0-9_/$]+)')
    for n in sorted(names):
        for s in utf8_constants(z.read(n + '.class')):
            for ref in pat.findall(s):
                if ref in names or ref.rsplit('$', 1)[0] in names:
                    continue
                if is_test_class(n) or (ref, n.split('$')[0]) in GUARDED:
                    continue
                bad.setdefault(ref, set()).add(n)
    if bad:
        for ref, users in sorted(bad.items()):
            print('MISSING %s referenced by %s' % (ref, ', '.join(sorted(users)[:5])))
        sys.exit(1)
    print('jar reference check OK (%d classes)' % len(names))

main(sys.argv[1])
