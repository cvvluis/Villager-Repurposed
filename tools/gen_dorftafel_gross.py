#!/usr/bin/env python3
"""Generates the assets of the large village board (dorftafel_gross): models, blockstates, item model, map textures.

Run from the VillagerPlus folder:  python tools/gen_dorftafel_gross.py
The board is 4 columns (left, middle, inner, right) by 2 rows. The frame uses vanilla planks and stripped logs of one wood
(8 woods, chosen per village), the two middle columns hold a recessed parchment map table.
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'villagerplus')
MODELS = os.path.join(ROOT, 'models', 'block')
TEX = os.path.join(ROOT, 'textures', 'block')
WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry']
PARTS = ['left', 'middle', 'inner', 'right']
HALVES = ['lower', 'upper']

# --- map table texture: one parchment of 32 x 23 px, cut into the four middle cells --------------------------------

MAP_W, MAP_H = 32, 23
UPPER_ROWS = 10          # the upper cell shows the top 10 rows of the map, the lower cell the other 13


def parchment():
    rnd = random.Random(7)
    img = Image.new('RGBA', (MAP_W, MAP_H))
    px = img.load()

    def height(x, y):
        return (math.sin(x * 0.31) + math.sin(y * 0.47 + 1.3) + math.sin((x + y) * 0.21 + 0.7) + 3) / 6

    for y in range(MAP_H):
        for x in range(MAP_W):
            h = height(x, y)
            r, g, b = 224, 201, 150
            n = rnd.randint(-6, 6)
            if h < 0.30:                       # a lake
                r, g, b = 150, 178, 190
            elif int(h * 9) != int(height(x + 1, y) * 9) or int(h * 9) != int(height(x, y + 1) * 9):
                r, g, b = 188, 160, 112       # contour line
            px[x, y] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)), 255)
    for x in range(MAP_W):                     # darker rim
        for y in (0, MAP_H - 1):
            px[x, y] = (150, 118, 74, 255)
    for y in range(MAP_H):
        for x in (0, MAP_W - 1):
            px[x, y] = (150, 118, 74, 255)
    # compass rose in the lower right corner
    cx, cy = MAP_W - 5, MAP_H - 5
    for d in range(-2, 3):
        px[cx + d, cy] = (92, 62, 34, 255)
        px[cx, cy + d] = (92, 62, 34, 255)
    px[cx, cy - 3] = (160, 40, 30, 255)
    return img


def cut_cell(img, part, half):
    cell = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    x0 = 0 if part == 'middle' else 16
    if half == 'upper':
        cell.paste(img.crop((x0, 0, x0 + 16, UPPER_ROWS)), (0, 16 - UPPER_ROWS))
    else:
        cell.paste(img.crop((x0, UPPER_ROWS, x0 + 16, MAP_H)), (0, 0))
    return cell


# --- models ---------------------------------------------------------------------------------------------------------

def element(frm, to, side, top=None):
    top = top or side
    faces = {'north': {'texture': side}, 'south': {'texture': side}, 'east': {'texture': side}, 'west': {'texture': side},
             'up': {'texture': top}, 'down': {'texture': top}}
    return {'from': frm, 'to': to, 'faces': faces}


def model(wood, part, half):
    t = {
        'planks': 'minecraft:block/%s_planks' % wood,
        'log': 'minecraft:block/stripped_%s_log' % wood,
        'logtop': 'minecraft:block/stripped_%s_log_top' % wood,
        'particle': 'minecraft:block/%s_planks' % wood,
    }
    els = [element([0, 0, 9], [16, 16, 11], '#planks')]                      # back plate
    upper = half == 'upper'
    if part in ('left', 'right'):
        els.append(element([0, 0, 6], [16, 16, 9], '#planks'))              # side panel
        post = ([12, 0, 5], [16, 16, 9]) if part == 'left' else ([0, 0, 5], [4, 16, 9])
        els.append(element(post[0], post[1], '#log', '#logtop'))            # post at the outer edge (left = +x)
    else:
        t['map'] = 'villagerplus:block/dorftafel_karte_%s_%s' % (part, half)
        lo, hi = (0, UPPER_ROWS) if upper else (3, 16)
        map_face = {'north': {'texture': '#map'}, 'south': {'texture': '#planks'}, 'east': {'texture': '#planks'},
                    'west': {'texture': '#planks'}, 'up': {'texture': '#planks'}, 'down': {'texture': '#planks'}}
        els.append({'from': [0, lo, 8], 'to': [16, hi, 9], 'faces': map_face})  # recessed map table
    if upper:
        els.append(element([0, 10, 5], [16, 16, 9], '#planks'))             # header bar (the village name goes here)
    else:
        els.append(element([0, 0, 5], [16, 3, 9], '#planks'))               # bottom beam
    return {'textures': t, 'elements': els}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=1)
        f.write('\n')


def main():
    img = parchment()
    os.makedirs(TEX, exist_ok=True)
    for part in ('middle', 'inner'):
        for half in HALVES:
            cut_cell(img, part, half).save(os.path.join(TEX, 'dorftafel_karte_%s_%s.png' % (part, half)))
    variants = {}
    rot = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
    for wood in WOODS:
        for part in PARTS:
            for half in HALVES:
                name = 'dorftafel_gross_%s_%s_%s' % (wood, part, half)
                write(os.path.join(MODELS, name + '.json'), model(wood, part, half))
                for facing, y in rot.items():
                    entry = {'model': 'villagerplus:block/' + name}
                    if y:
                        entry['y'] = y
                    variants['facing=%s,half=%s,part=%s,wood=%s' % (facing, half, part, wood)] = entry
    write(os.path.join(ROOT, 'blockstates', 'dorftafel_gross.json'), {'variants': variants})
    write(os.path.join(ROOT, 'items', 'dorftafel_gross.json'),
          {'model': {'type': 'minecraft:model', 'model': 'villagerplus:block/dorftafel_gross_oak_middle_lower'}})
    # a 8x enlarged preview of the parchment for a quick look (not shipped)
    preview = os.environ.get('DORFTAFEL_PREVIEW')
    if preview:
        img.resize((MAP_W * 8, MAP_H * 8), Image.NEAREST).save(preview)
    print('models', len(WOODS) * len(PARTS) * len(HALVES), 'variants', len(variants))


if __name__ == '__main__':
    main()
