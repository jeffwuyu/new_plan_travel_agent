# China Administrative Boundary Maps

These GeoJSON files are used only as front-end destination selection hit areas.
They are not a legal or authoritative surveying boundary source.

- Source: Alibaba DataV GeoAtlas compatible endpoint, `https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json`.
- Download date: 2026-06-08.
- File naming: `{adcode}.json`, for example `100000.json`, `330000.json`, `330800.json`.
- Coverage: China root map, province-level maps with city features, and city-level maps with district/county features where the source provides them.
- Runtime behavior: files are lazy-loaded by `frontend/src/assets/geojson/chinaGeoJson.js`.

Some administrative summary nodes such as `市辖区`, `县`, and `*直辖县级行政区划` are flattened by the local metadata layer so users click the real visible child regions instead of those grouping labels.
