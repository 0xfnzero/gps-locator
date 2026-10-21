# Offline region data

Source: https://github.com/dr5hn/countries-states-cities-database
Pinned revision: 7795d77d6a801543b9bb90d97a182c885f44c458
Source files: json/countries+states+cities.json and json/counties.json
Imported: 2026-10-22
License: Open Database License (ODbL) 1.0; see LICENSE-ODbL.txt.

This adapted database retains names, native names, IDs, parent relationships and
coordinates rounded to six decimals. It is distributed under ODbL-1.0 separately
from the application's MIT-licensed code. The country files are ordinary gzip JSON
and can be decompressed without proprietary tools. Rebuild using
`python3 tools/import-region-data.py WORLD_JSON COUNTIES_JSON`.

Coverage: 250 countries/territories, 5,327 first-level regions, 152,375 cities/towns,
and 14,051 districts/counties in 26 countries. Seven source counties without a
matching first-level region are omitted. AAPT expands the .gz source assets into
ordinary .json assets when packaging the APK. Coverage and administrative levels
vary; this is not a complete worldwide street/neighborhood directory. Names are
shown in the source language with native aliases where available. Coordinates are
representative points, not street addresses or boundary polygons.
