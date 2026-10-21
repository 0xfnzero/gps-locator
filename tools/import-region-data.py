#!/usr/bin/env python3
"""Build compact offline region assets from CSC JSON exports (ODbL-1.0).
Usage: python3 tools/import-region-data.py countries+states+cities.json counties.json
See app/src/main/assets/regions/README.md for the pinned source and license.
"""
import gzip
import json
import sys
from collections import defaultdict
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/regions'
root.mkdir(parents=True, exist_ok=True)
world = json.loads(Path(sys.argv[1]).read_text())
counties = defaultdict(list)
for row in json.loads(Path(sys.argv[2]).read_text()):
    counties[row['state_id']].append(row)

def point(row):
    try:
        lat, lon = float(row['latitude']), float(row['longitude'])
        assert -90 <= lat <= 90 and -180 <= lon <= 180
        return [round(lat, 6), round(lon, 6)]
    except (TypeError, ValueError, KeyError, AssertionError):
        return None

def place(row):
    return dict(id=row['id'], name=row['name'], native=row.get('native') or '', point=point(row))

index = []
for country in world:
    entry = dict(code=country['iso2'], name=country['name'], point=point(country))
    index.append(entry)
    regions = []
    for state in country['states']:
        item = place(state)
        item['districts'] = [dict(place(d), parent=d.get('parent_id')) for d in counties[state['id']]]
        item['cities'] = [dict(place(c), district=c.get('county_id')) for c in state['cities']]
        regions.append(item)
    content = json.dumps(regions, ensure_ascii=False, separators=(',', ':')).encode()
    (root / (entry['code'] + '.json.gz')).write_bytes(gzip.compress(content, mtime=0))
(root / 'countries.json').write_text(json.dumps(index, ensure_ascii=False, separators=(',', ':'))+'\n')
print(f'Wrote {len(index)} country files')
