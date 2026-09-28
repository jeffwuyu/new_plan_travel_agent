import AREA_TREE from './province-city-level.json'

export const ROOT_ADCODE = '100000'

const DIRECT_AREA_PROVINCES = new Set(['北京市', '天津市', '上海市', '重庆市', '香港特别行政区', '澳门特别行政区'])
const SUMMARY_REGION_NAMES = new Set(['市辖区', '县'])
const DIRECT_COUNTY_PATTERN = /直辖县级行政区划$/
const mapCache = new Map()

function normalizeAdcode(adcode) {
  if (adcode === null || adcode === undefined) return ''
  return String(adcode).trim()
}

function cloneNode(node, children) {
  return {
    ...node,
    code: normalizeAdcode(node.code),
    children
  }
}

function visibleChildren(item) {
  const children = []
  for (const child of item?.children || []) {
    const nestedChildren = visibleChildren(child)
    if (SUMMARY_REGION_NAMES.has(child.name) || DIRECT_COUNTY_PATTERN.test(child.name)) {
      children.push(...nestedChildren)
    } else {
      children.push(cloneNode(child, nestedChildren))
    }
  }
  return children
}

function normalizeProvince(province) {
  const children = visibleChildren(province)
  return cloneNode(province, children)
}

const PROVINCES = AREA_TREE.map(normalizeProvince)
const ROOT_NODE = { code: ROOT_ADCODE, name: '中国', children: PROVINCES }
const nodesByCode = new Map()

function indexNode(node, parent = null, level = 'root') {
  const indexed = { ...node, parent, level }
  nodesByCode.set(indexed.code, indexed)
  indexed.children = (node.children || []).map(child => indexNode(child, indexed, nextLevel(level)))
  return indexed
}

function nextLevel(level) {
  if (level === 'root') return 'province'
  if (level === 'province') return 'city'
  return 'district'
}

const INDEXED_ROOT = indexNode(ROOT_NODE)

function mapBaseUrl() {
  const base = import.meta.env.BASE_URL || '/'
  return `${base.endsWith('/') ? base : `${base}/`}maps`
}

export async function loadMapGeoJson(adcode) {
  const code = normalizeAdcode(adcode)
  if (!code) throw new Error('missing adcode')
  if (mapCache.has(code)) return mapCache.get(code)

  const url = `${mapBaseUrl()}/${code}.json`
  const response = await fetch(url)
  if (!response.ok) {
    throw new Error(`map ${code} not found`)
  }
  const geoJson = await response.json()
  if (!Array.isArray(geoJson.features) || geoJson.features.length === 0) {
    throw new Error(`map ${code} has no features`)
  }
  mapCache.set(code, geoJson)
  return geoJson
}

export function getRootNode() {
  return INDEXED_ROOT
}

export function getNodeByAdcode(adcode) {
  return nodesByCode.get(normalizeAdcode(adcode)) || null
}

export function getChildren(node) {
  return node?.children || []
}

export function isLeafNode(node) {
  return getChildren(node).length === 0
}

export function isDirectProvinceNode(node) {
  return Boolean(node && node.level === 'province' && DIRECT_AREA_PROVINCES.has(node.name))
}

export function getFeatureAdcode(properties = {}) {
  return normalizeAdcode(properties.adcode || properties.adCode || properties.code)
}
