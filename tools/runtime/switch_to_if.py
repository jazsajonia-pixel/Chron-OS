#!/usr/bin/env python3
"""Rewrites `switch (x) { case R.id.foo: ... }` into if/else chains.
Resource ids are not compile-time constants in Android library modules, so the runtime's sources need this."""
import re, sys

def find_block_end(s, i):  # s[i] == '{'
    depth = 0; j = i; in_str = None
    while j < len(s):
        c = s[j]
        if in_str:
            if c == '\\': j += 2; continue
            if c == in_str: in_str = None
        elif c in '"\'': in_str = c
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return j
        j += 1
    raise ValueError

def convert(src, counter=[0]):
    out = []; pos = 0
    pat = re.compile(r'switch\s*\(')
    while True:
        m = pat.search(src, pos)
        if not m: out.append(src[pos:]); break
        # expression
        k = m.end(); depth = 1
        while depth: 
            if src[k] == '(': depth += 1
            elif src[k] == ')': depth -= 1
            k += 1
        expr = src[m.end():k-1]
        b = src.index('{', k); e = find_block_end(src, b)
        body = src[b+1:e]
        if 'case R.' not in body:
            out.append(src[pos:b+1]); pos = b+1; continue
        # split into top-level labels
        groups = []; cur = None; depth = 0; i = 0; lines = body.split('\n')
        for line in lines:
            st = line.strip()
            if depth == 0 and (st.startswith('case ') or st.startswith('default:') or st.startswith('default :')):
                label = st[:st.index(':', 5 if st.startswith('case') else 0)] if st.startswith('case') else 'default'
                rest = st[st.index(':')+1:].strip() if st.startswith('case') else st.split(':',1)[1].strip()
                cond = label[5:].strip() if label != 'default' else None
                if cur is not None and not cur['body'].strip():
                    cur['conds'].append(cond)
                else:
                    cur = {'conds': [cond], 'body': ''}; groups.append(cur)
                if rest: cur['body'] += rest + '\n'
            else:
                if cur is not None: cur['body'] += line + '\n'
            depth += line.count('{') - line.count('}')
        counter[0] += 1; var = f'__sw{counter[0]}'
        indent = re.match(r'[ \t]*', src[src.rfind('\n', 0, m.start())+1:m.start()]).group(0)
        parts = [f'int {var} = {expr};\n']
        first = True
        for g in groups:
            bodytxt = g['body'].rstrip()
            # drop the trailing break
            bodytxt = re.sub(r'\n?\s*break;\s*$', '', bodytxt)
            if re.search(r'(^|[;{}\s])break;', bodytxt):
                # a nested break is allowed only inside loops/switches in the body
                if not re.search(r'\b(for|while|switch)\b', bodytxt):
                    raise SystemExit(f'unsupported break in group {g["conds"]}')
            if None in g['conds']:
                head = ' else {' if not first else '{'
            else:
                cond = ' || '.join(f'{var} == {c}' for c in g['conds'])
                head = ('if (' if first else ' else if (') + cond + ') {'
            parts.append(head + '\n' + bodytxt + '\n' + indent + '}')
            first = False
        out.append(src[pos:m.start()]); out.append(''.join(parts)); pos = e+1
    return ''.join(out)

for p in sys.argv[1:]:
    s = open(p).read(); n = convert(s)
    if n != s: open(p, 'w').write(n); print('converted', p)
