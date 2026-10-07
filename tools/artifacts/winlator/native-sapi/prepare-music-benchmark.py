"""Prepare isolated audio computation diagnostics; never start the game."""
import importlib.util
import marshal
from pathlib import Path
import runpy
import shutil
import struct
import sys
import zlib

reader = runpy.run_path(str(Path(__file__).with_name('inspect-arcadelux.py')))
exe = Path(sys.argv[1])
destination = Path(sys.argv[2]).resolve()
portable = Path(sys.argv[3])
destination.mkdir(parents=True, exist_ok=True)
for source in portable.iterdir():
    if source.is_file():
        shutil.copyfile(source, destination / source.name)
pyz, entries = reader['modules'](exe)
audio_modules = {'arcadelux.music', 'arcadelux.engines', 'arcadelux.synth', 'arcadelux.motifs'}
for name, (kind, position, size) in entries.items():
    if name not in audio_modules and not (name == 'numpy' or name.startswith('numpy.')):
        continue
    code = marshal.loads(zlib.decompress(pyz[position:position + size]))
    output = destination.joinpath(*name.split('.'))
    output = output / '__init__.pyc' if kind == 1 else output.with_suffix('.pyc')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(importlib.util.MAGIC_NUMBER + bytes(12) + marshal.dumps(code))
(destination / 'arcadelux' / '__init__.py').write_text('')
data = exe.read_bytes()
cookie = data.rfind(b'MEI\014\013\012\013\016')
_, package_size, toc_position, toc_size, _, _ = struct.unpack('!8sIIII64s', data[cookie:cookie+88])
base = cookie + 88 - package_size
toc = data[base+toc_position:base+toc_position+toc_size]
index = 0
while index < len(toc):
    length, position, size, _, compressed, _ = struct.unpack('!iiiiBB', toc[index:index+18])
    name = toc[index+18:index+length].rstrip(b'\0').decode('utf-8').replace('\\', '/')
    index += length
    if not name.startswith(('numpy/', 'numpy.libs/')):
        continue
    output = (destination / name).resolve()
    if destination not in output.parents:
        raise ValueError('Unsafe archive path')
    output.parent.mkdir(parents=True, exist_ok=True)
    contents = data[base+position:base+position+size]
    output.write_bytes(zlib.decompress(contents) if compressed else contents)
(destination / 'python314._pth').write_text('python314.zip\n.\n')
(destination / 'benchmark.py').write_text('''
import os, sys, time, traceback
from pathlib import Path
root = Path(__file__).resolve().parent
os.chdir(root)
with (root / 'benchmark.log').open('w') as log:
    try:
        started = time.perf_counter()
        import numpy
        from arcadelux import music
        log.write(f'importSeconds={time.perf_counter()-started:.3f}\\n')
        log.flush()
        for seed in (12345, 54321):
            started = time.perf_counter()
            samples = music.make_track('menu', seed, 8, engine='chip')
            log.write(f'seed={seed} musicSeconds={time.perf_counter()-started:.3f} samples={len(samples)}\\n')
            log.flush()
        log.write('done\\n')
    except Exception:
        traceback.print_exc(file=log)
''')
print(f'Prepared isolated music benchmark in {destination}')
