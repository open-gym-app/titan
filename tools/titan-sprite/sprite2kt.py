"""Converts the Titan Companion canvas's sprite SVG into Kotlin pixel data for TitanSprite. See README.md."""
import sys, re
from html.parser import HTMLParser

INK = {'K':'K','W':'W','S':'S','G':'G','H':'H','E':'E','D':'D','L':'L','accent':'ACCENT','bar':'BAR',
       'faint':'FAINT','ghost':'GHOST','food':'FOOD','water':'WATER','waterLine':'WATER_LINE'}
# class -> part name; first matching class wins
PART = [('tt-legl','LEG_L'),('tt-legr','LEG_R'),('tt-me','ME'),('tt-lean','LEAN'),('tt-upper','UPPER'),
        ('acc-fx','FX'),('tt-core','CORE'),('acc-bar','BAR'),('tt-head','HEAD'),('tt-brow','BROW'),
        ('tt-smile','SMILE'),('tt-mouth','MOUTH'),('g-headband','HEADBAND'),('g-shades','SHADES'),('g-crown','CROWN'),
        ('acc-z','Z'),('tt-z1','Z1'),('tt-z2','Z2'),('acc-food','FOOD_ACC'),('tt-food','FOOD'),('tt-bite','BITE'),
        ('acc-speed','SPEED'),('acc-rival','RIVAL_ACC'),('tt-rival','RIVAL'),('acc-spark','SPARK_ACC'),('tt-sparkg','SPARK'),
        ('acc-splash','SPLASH'),('tt-spl1','SPLASH_1'),('tt-spl2','SPLASH_2'),('tt-eye','EYE'),('tt-shadow','SHADOW')]

def part_of(cls):
    cs=cls.split()
    for c,p in PART:
        if c in cs: return p
    return None

class Node:
    def __init__(s,part,alpha=1.0): s.part=part; s.alpha=alpha; s.pixels=[]; s.children=[]

def unit(v):
    f=float(v)/2.5
    if abs(f-round(f))>1e-9: raise SystemExit(f'off-grid value {v}')
    return int(round(f))

class P(HTMLParser):
    def __init__(s):
        super().__init__(); s.root=Node('ROOT'); s.stack=[s.root]; s.skipped=[]
    def handle_starttag(s,tag,attrs):
        a=dict(attrs)
        cls=a.get('class','')
        if tag=='g':
            part=part_of(cls)
            if part is None:
                if 'acc-water' in cls or 'tt-wave' in cls:
                    s.stack.append(None); return
                raise SystemExit(f'unknown group {cls}')
            n=Node(part,float(a.get('opacity','1')))
            s.stack[-1].children.append(n) if s.stack[-1] is not None else None
            s.stack.append(n)
        elif tag=='rect':
            if s.stack[-1] is None: return
            m=re.match(r'\{\{c\.(\w+)\}\}',a['fill'])
            px=(unit(a['x']),unit(a['y']),unit(a['width']),unit(a['height']),INK[m.group(1)])
            part=part_of(cls) if cls else None
            if part:
                n=Node(part,float(a.get('opacity','1'))); n.pixels.append(px)
                parent=s.stack[-1]
                # several rects can share one class (the z's): merge into one node
                same=[c for c in parent.children if c.part==part and not c.children]
                (same[0].pixels.append(px) if same and part in ('Z1','Z2','EYE') and part!='EYE' else parent.children.append(n))
            else:
                s.stack[-1].pixels.append(px)
        else:
            s.skipped.append(tag)
    def handle_endtag(s,tag):
        if tag=='g': s.stack.pop()

p=P(); p.feed(open(sys.argv[1]).read())

def emit(n,ind):
    pad='    '*ind
    out=[f'{pad}node(']
    out.append(f'{pad}    SpritePart.{n.part},')
    if n.alpha!=1.0: out.append(f'{pad}    alpha = {n.alpha}f,')
    if n.pixels:
        out.append(f'{pad}    pixels = listOf(')
        for (x,y,w,h,ink) in n.pixels:
            out.append(f'{pad}        px({x}, {y}, {w}, {h}, {ink}),')
        out.append(f'{pad}    ),')
    if n.children:
        out.append(f'{pad}    children = listOf(')
        for c in n.children: out.extend(emit(c,ind+2)); out[-1]+=','
        out.append(f'{pad}    ),')
    out.append(f'{pad})')
    return out

lines=emit(p.root,0)
count=sum(1 for l in lines if 'px(' in l)
print('''package com.titan.core.ui.component.titan

import com.titan.core.ui.component.titan.SpriteInk.ACCENT
import com.titan.core.ui.component.titan.SpriteInk.BAR
import com.titan.core.ui.component.titan.SpriteInk.D
import com.titan.core.ui.component.titan.SpriteInk.E
import com.titan.core.ui.component.titan.SpriteInk.FAINT
import com.titan.core.ui.component.titan.SpriteInk.FOOD
import com.titan.core.ui.component.titan.SpriteInk.G
import com.titan.core.ui.component.titan.SpriteInk.GHOST
import com.titan.core.ui.component.titan.SpriteInk.H
import com.titan.core.ui.component.titan.SpriteInk.K
import com.titan.core.ui.component.titan.SpriteInk.L
import com.titan.core.ui.component.titan.SpriteInk.S
import com.titan.core.ui.component.titan.SpriteInk.W
import com.titan.core.ui.component.titan.SpriteInk.WATER_LINE

/*
 * The titan, pixel by pixel: GENERATED from the Titan Companion canvas's sprite artboard
 * (`Titan.dc.html`). One unit is one sprite pixel on a 64 x 40 grid; a rect is
 * `px(x, y, width, height, ink)`. The tree is the drawing's own grouping, because the poses move
 * whole groups — see [TitanPoses].
 *
 * Do not edit by hand: regenerate from the artboard instead, so the two cannot drift.
 */
''')
print(f'/** The sprite, {count} rects. */')
print('internal val TITAN_SPRITE: SpriteNode =')
lines[0]=lines[0]
print('\n'.join('    '+l if i>0 else '    '+l for i,l in enumerate(lines)))
print(f'// skipped non-rect elements: {sorted(set(p.skipped))}', file=sys.stderr)
print(f'rects: {count}', file=sys.stderr)
