"""Resolve compiler-host member references without changing retained classes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile


class Cursor:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        assert size >= 0 and self.offset + size <= len(self.data)
        data = self.data[self.offset:self.offset + size]
        self.offset += size
        return data

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack('>H', self.take(2))[0]

    def u4(self):
        return struct.unpack('>I', self.take(4))[0]


def parse(data):
    c = Cursor(data)
    assert c.u4() == 0xcafebabe
    c.take(4)
    pool = [None] * c.u2()
    i = 1
    while i < len(pool):
        tag = c.u1()
        if tag == 1:
            pool[i] = c.take(c.u2()).decode('utf-8', errors='surrogateescape')
        elif tag in [3, 4]:
            c.take(4)
        elif tag in [5, 6]:
            c.take(8)
            i += 1
        elif tag in [7, 8, 16, 19, 20]:
            pool[i] = (tag, c.u2())
        elif tag in [9, 10, 11, 12, 17, 18]:
            pool[i] = (tag, c.u2(), c.u2())
        elif tag == 15:
            pool[i] = (tag, c.u1(), c.u2())
        else:
            raise ValueError('Unsupported constant-pool tag: ' + str(tag))
        i += 1
    def name(index):
        return pool[pool[index][1]] if index else None
    flags = c.u2()
    owner = name(c.u2())
    parent = name(c.u2())
    interfaces = [name(c.u2()) for _ in range(c.u2())]
    members = {}
    for kind in ['field', 'method']:
        for _ in range(c.u2()):
            access, member, descriptor = c.u2(), pool[c.u2()], pool[c.u2()]
            members[(kind, member, descriptor)] = access
            for _ in range(c.u2()):
                c.u2()
                c.take(c.u4())
    references = {}
    for index, row in enumerate(pool):
        if isinstance(row, tuple) and row[0] in [9, 10, 11]:
            target = name(row[1])
            signature = pool[row[2]]
            references[index] = ('field' if row[0] == 9 else 'method', target, pool[signature[1]], pool[signature[2]])
    return {'owner': owner, 'flags': flags, 'parent': parent, 'interfaces': interfaces, 'members': members, 'references': references}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--worker', type=Path, required=True)
    p.add_argument('--entries', type=Path, required=True)
    p.add_argument('--jdk', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    rows = json.loads(args.worker.read_text())
    paths = [args.jar, *[Path(row['path']) for row in rows if not Path(row['path']).name.startswith('swift-export-embeddable')], args.jdk / 'jmods/java.base.jmod']
    archives = [zipfile.ZipFile(path) for path in paths]
    tables = [set(archive.namelist()) for archive in archives]
    cache = {}
    def load(owner):
        if owner not in cache:
            entry = owner + '.class'
            for archive, names in zip(archives, tables):
                actual = entry if entry in names else 'classes/' + entry
                if actual in names:
                    cache[owner] = parse(archive.read(actual))
                    break
            else:
                raise ValueError('Unresolved class: ' + owner)
        return cache[owner]
    def resolve(owner, key):
        queue = [owner]
        seen = set()
        while queue:
            current = queue.pop(0)
            if not current or current in seen:
                continue
            seen.add(current)
            table = load(current)
            if key in table['members']:
                return current, table['members'][key]
            if key[1] != '<init>':
                queue += [table['parent'], *table['interfaces']]
        raise ValueError('Unresolved member: ' + repr((owner, key)))
    def subclass(child, parent):
        while child:
            if child == parent:
                return True
            child = load(child)['parent']
        return False
    output = []
    for entry in json.loads(args.entries.read_text()):
        caller = entry.removesuffix('.class')
        table = load(caller)
        instructions = subprocess.check_output([str(args.jdk / 'bin/javap'), '-p', '-c', '-classpath', str(args.jar), caller.replace('/', '.')], text=True)
        uses = {}
        for line in instructions.splitlines():
            match = re.match(r'\s*\d+:\s*(invokevirtual|invokestatic|invokespecial|invokeinterface|getfield|putfield|getstatic|putstatic)\s+#(\d+)', line)
            if match:
                uses.setdefault(int(match[2]), set()).add(match[1])
        for index, reference in table['references'].items():
            kind, owner, member, descriptor = reference
            if owner.startswith('['):
                assert kind == 'method' and member == 'clone' and descriptor == '()Ljava/lang/Object;'
                continue
            declaring, flags = resolve(owner, (kind, member, descriptor))
            same_package = caller.rsplit('/', 1)[0] == declaring.rsplit('/', 1)[0]
            assert flags & 1 or flags & 2 and caller == declaring or flags & 4 and (same_package or subclass(caller, declaring)) or not flags & 7 and same_package, (caller, reference, declaring, flags)
            for opcode in uses.get(index, []):
                assert bool(flags & 8) == (opcode in ['invokestatic', 'getstatic', 'putstatic']), (caller, reference, opcode, flags)
            output.append({'caller': caller, 'kind': kind, 'owner': owner, 'name': member, 'descriptor': descriptor, 'declaring_owner': declaring, 'fullflags': flags, 'opcodes': sorted(uses.get(index, []))})
    pins = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in [*paths, args.worker, args.entries, Path(__file__)]}
    args.output.write_text(json.dumps({'accepted': True, 'reference_count': len(output), 'references': output, 'input_sha256': pins}, indent=2) + '\n')
    print('PASS exact member descriptors/access/static linkage for ' + str(len(output)) + ' references')


if __name__ == '__main__':
    main()
