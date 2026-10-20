#!/usr/bin/env python3
"""Include notices from each statically linked upstream dependency."""
import pathlib
import shutil
import sys
source, destination, neovim = map(pathlib.Path, sys.argv[1:])
for name in ('libuv', 'luajit', 'luv', 'lua_compat53', 'lpeg', 'treesitter', 'treesitter_c',
             'treesitter_lua', 'treesitter_markdown', 'treesitter_query', 'treesitter_vim',
             'treesitter_vimdoc', 'unibilium', 'utf8proc'):
    project = source / name
    notices = [p for p in project.iterdir() if p.is_file() and p.name.lower().startswith(('license', 'copying', 'copyright'))]
    # LPeg distributes its permission notice in its README.
    if name == 'lpeg':
        notices += list(project.glob('README*'))
    if not notices:
        raise RuntimeError(f'Missing license notices for {name}')
    for path in notices:
        shutil.copyfile(path, destination / f'{name}-{path.name}')

# These embedded CJSON sources keep their MIT grants in file headers.
for path in (neovim / 'src/cjson').glob('*.c'):
    text = path.read_text()
    start = text.index('/*')
    end = text.index('*/', start) + 2
    (destination / f'cjson-{path.stem}.txt').write_text(text[start:end] + '\n')
