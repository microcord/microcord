import re, sys

src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for m in re.finditer(r'd2 = \{(.*?)\}, k = ', src, re.S):
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    print(' | '.join(parts))
    print('---')
