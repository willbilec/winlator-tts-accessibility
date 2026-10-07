"""Read packaged code objects for diagnostics; never import or run game code.

Run with the portable Python version matching the package (3.14).
"""
import dis
import marshal
from pathlib import Path
import struct
import sys
import types
import zlib


def modules(path):
    data = Path(path).read_bytes()
    cookie = data.rfind(b'MEI\014\013\012\013\016')
    if cookie < 0:
        raise ValueError('No PyInstaller archive')
    _, size, offset, length, version, _ = struct.unpack('!8sIIII64s', data[cookie:cookie + 88])
    if version != sys.version_info.major * 100 + sys.version_info.minor:
        raise ValueError(f'Python {version} required')
    base = cookie + 88 - size
    toc = data[base + offset:base + offset + length]
    index = 0
    while index < len(toc):
        length, position, compressed, _, flag, kind = struct.unpack('!iiiiBB', toc[index:index + 18])
        index += length
        if chr(kind) == 'z':
            pyz = data[base + position:base + position + compressed]
            if flag:
                pyz = zlib.decompress(pyz)
            toc_offset = struct.unpack('!i', pyz[8:12])[0]
            entries = dict(marshal.loads(pyz[toc_offset:]))
            return pyz, entries
    raise ValueError('No PYZ archive')


def functions(code):
    yield code
    for value in code.co_consts:
        if isinstance(value, types.CodeType):
            yield from functions(value)


if __name__ == '__main__':
    pyz, entries = modules(sys.argv[1])
    selected = sys.argv[2:]
    for name in ['arcadelux.app', 'arcadelux.speech', 'arcadelux.main']:
        _, offset, size = entries[name]
        code = marshal.loads(zlib.decompress(pyz[offset:offset + size]))
        for function in functions(code):
            if selected and not any(term in function.co_qualname for term in selected):
                continue
            if not selected and not any(term in function.co_qualname for term in
                    ['say_and_wait', 'estimate', 'is_speaking', 'wait_key']):
                continue
            print(f'\n{name}: {function.co_qualname}')
            print('arguments:', function.co_varnames[:function.co_argcount + function.co_kwonlyargcount])
            dis.dis(function, depth=0)
