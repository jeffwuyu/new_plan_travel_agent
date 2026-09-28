<template>
  <div class="map-selector">
    <div class="map-toolbar">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item>
          <button class="crumb" type="button" @click="goRoot">中国</button>
        </el-breadcrumb-item>
        <el-breadcrumb-item v-if="selection.provinceName">
          <button class="crumb" type="button" @click="goProvince">{{ selection.provinceName }}</button>
        </el-breadcrumb-item>
        <el-breadcrumb-item v-if="selection.cityName && selection.cityName !== selection.provinceName">
          {{ selection.cityName }}
        </el-breadcrumb-item>
      </el-breadcrumb>
      <el-tag v-if="modelValue?.region" type="success" effect="plain">{{ modelValue.region }}</el-tag>
    </div>

    <div ref="chartRef" v-loading="loading" class="map-canvas" />

    <div class="map-footer">
      <span>{{ helperText }}</span>
      <el-button v-if="modelValue?.region" size="small" @click="confirmCurrent">使用当前地区</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  getFeatureAdcode,
  getNodeByAdcode,
  getRootNode,
  isDirectProvinceNode,
  isLeafNode,
  loadMapGeoJson
} from '@/assets/geojson/chinaGeoJson'

const props = defineProps({
  modelValue: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:modelValue', 'selected'])

const chartRef = ref(null)
const loading = ref(false)
let chart = null
let mapName = 'china-root'
let featurePropsByName = new Map()
let selectedProvinceNode = null
let selectedCityNode = null

const selection = reactive({
  level: 'china',
  provinceName: '',
  cityName: '',
  districtName: '',
  adcode: '',
  region: ''
})

const helperText = computed(() => {
  if (selection.level === 'china') return '点击省份进入省级地图'
  if (selection.level === 'province') return '点击城市进入区县选择，也可以直接使用当前城市'
  return '点击区县可细化目的地，也可以直接使用当前城市'
})

onMounted(async () => {
  await nextTick()
  chart = echarts.init(chartRef.value)
  chart.on('click', handleMapClick)
  window.addEventListener('resize', resize)
  renderChina()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})

watch(() => props.modelValue, value => {
  if (!value) return
  Object.assign(selection, {
    provinceName: value.provinceName || selection.provinceName,
    cityName: value.cityName || selection.cityName,
    districtName: value.districtName || selection.districtName,
    adcode: value.adcode || selection.adcode,
    region: value.region || selection.region
  })
}, { deep: true })

function resize() {
  chart?.resize()
}

async function renderChina() {
  selection.level = 'china'
  selection.provinceName = ''
  selection.cityName = ''
  selection.districtName = ''
  selectedProvinceNode = null
  selectedCityNode = null
  selection.adcode = ''
  selection.region = ''
  await registerAndRender('china-root', getRootNode())
}

async function renderProvince(provinceNode) {
  selectedProvinceNode = provinceNode
  selection.level = isDirectProvinceNode(provinceNode) ? 'city' : 'province'
  selection.provinceName = provinceNode.name
  selection.cityName = isDirectProvinceNode(provinceNode) ? provinceNode.name : ''
  selection.districtName = ''
  selectedCityNode = isDirectProvinceNode(provinceNode) ? provinceNode : null
  await registerAndRender(`map-${provinceNode.code}`, provinceNode)
}

async function renderCity(cityNode) {
  selectedCityNode = cityNode
  selection.level = 'city'
  selection.cityName = cityNode.name
  selection.districtName = ''
  await registerAndRender(`map-${cityNode.code}`, cityNode)
}

async function registerAndRender(name, node) {
  loading.value = true
  const geoJson = await loadMapGeoJson(node.code)
  mapName = name
  featurePropsByName = new Map((geoJson.features || []).map(feature => [feature.properties?.name, feature.properties || {}]))
  echarts.registerMap(name, geoJson)
  chart?.setOption({
    tooltip: { trigger: 'item', formatter: '{b}' },
    series: [{
      type: 'map',
      map: name,
      roam: true,
      selectedMode: false,
      layoutCenter: ['50%', '50%'],
      layoutSize: '95%',
      label: {
        show: false
      },
      emphasis: {
        label: {
          show: true,
          color: '#111827',
          fontSize: 13,
          fontWeight: 600,
          overflow: 'break',
          width: 110,
          backgroundColor: 'rgba(255, 255, 255, 0.86)',
          borderColor: '#bfdbfe',
          borderWidth: 1,
          borderRadius: 4,
          padding: [3, 5],
          lineHeight: 16
        },
        itemStyle: {
          areaColor: '#c7e0ff'
        }
      },
      itemStyle: {
        areaColor: '#e8f2ff',
        borderColor: '#7aa7d9',
        borderWidth: 1
      }
    }]
  }, true)
  nextTick(resize)
  loading.value = false
}

async function handleMapClick(params) {
  if (loading.value) return
  const name = params.name
  const featureProps = featurePropsByName.get(name) || {}
  const adcode = getFeatureAdcode(featureProps)
  const node = getNodeByAdcode(adcode) || { code: adcode, name, children: [] }
  if (!name) return
  try {
    if (selection.level === 'china') {
      updateSelection({ provinceName: node.name, cityName: '', districtName: '', region: node.name, adcode })
      if (isLeafNode(node)) {
        emit('selected', { ...selection })
        return
      }
      if (isDirectProvinceNode(node)) {
        updateSelection({ cityName: node.name, region: node.name, adcode })
      }
      await renderProvince(node)
      return
    }
    if (selection.level === 'province') {
      updateSelection({ cityName: node.name, districtName: '', region: node.name, adcode })
      if (isLeafNode(node)) {
        emit('selected', { ...selection })
      } else {
        await renderCity(node)
      }
      return
    }
    updateSelection({ districtName: node.name, region: `${selection.cityName} ${node.name}`, adcode })
    emit('selected', { ...selection })
  } catch (err) {
    ElMessage.info('暂无该区域边界数据，已选择当前区域')
    if (selection.level === 'china') {
      selectedProvinceNode = node
    }
    if (selection.level === 'province') {
      selectedCityNode = node
    }
    emit('selected', { ...selection })
  } finally {
    loading.value = false
  }
}

function updateSelection(partial) {
  Object.assign(selection, partial)
  emit('update:modelValue', { ...selection })
}

function confirmCurrent() {
  emit('selected', { ...selection })
}

async function goRoot() {
  await renderChina()
  emit('update:modelValue', { ...selection })
}

async function goProvince() {
  if (!selectedProvinceNode) return
  await renderProvince(selectedProvinceNode)
  updateSelection({
    provinceName: selectedProvinceNode.name,
    cityName: isDirectProvinceNode(selectedProvinceNode) ? selectedProvinceNode.name : '',
    districtName: '',
    adcode: selectedProvinceNode.code,
    region: selectedProvinceNode.name
  })
  emit('update:modelValue', { ...selection })
}
</script>

<style scoped>
.map-selector {
  border: 1px solid #d9e5f2;
  border-radius: 8px;
  padding: 14px;
  background: #f8fbff;
}
.map-toolbar,
.map-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.map-canvas {
  width: 100%;
  height: 420px;
  margin: 12px 0;
  background: #fff;
  border-radius: 6px;
}
.map-footer {
  color: #607086;
  font-size: 13px;
}
.crumb {
  padding: 0;
  border: 0;
  background: transparent;
  color: #337ecc;
  cursor: pointer;
}
@media (max-width: 720px) {
  .map-toolbar,
  .map-footer {
    align-items: flex-start;
    flex-direction: column;
  }
  .map-canvas {
    height: 340px;
  }
}
</style>
