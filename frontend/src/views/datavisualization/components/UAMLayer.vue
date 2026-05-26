<!-- UamLayer -->
<template></template>

<script setup>
import { onMounted, onUnmounted, ref, nextTick } from 'vue'
import { useMapStore } from '@/stores/map'
import { useLayerStore } from '@/stores/layer'
import { storeToRefs } from 'pinia'

import SceneView from '@arcgis/core/views/SceneView'
import TileLayer from '@arcgis/core/layers/TileLayer'
import GraphicsLayer from '@arcgis/core/layers/GraphicsLayer'
import Graphic from '@arcgis/core/Graphic'
import Polyline from '@arcgis/core/geometry/Polyline'
import Point from '@arcgis/core/geometry/Point'
import SimpleLineSymbol from '@arcgis/core/symbols/SimpleLineSymbol'
import SimpleMarkerSymbol from '@arcgis/core/symbols/SimpleMarkerSymbol'
import PictureMarkerSymbol from '@arcgis/core/symbols/PictureMarkerSymbol'
import PointSymbol3D from '@arcgis/core/symbols/PointSymbol3D'
import ObjectSymbol3DLayer from '@arcgis/core/symbols/ObjectSymbol3DLayer'
import Color from '@arcgis/core/Color'
import TileInfo from '@arcgis/core/layers/support/TileInfo'
import SpatialReference from '@arcgis/core/geometry/SpatialReference'
import SceneLayer from '@arcgis/core/layers/SceneLayer'
import { watch } from 'vue'

const $mapStore = useMapStore()
let $view = null
watch($mapStore.getView, (val) => {
  $view = val
  $view.map.add(flightGraphicsLayer)
})
// const $view = $mapStore.getView()
const $layerStore = useLayerStore()
const { layerGroups } = storeToRefs($layerStore)

let dataId = null
let path = null

const flightGraphicsLayer = new GraphicsLayer()
let flightAnimationId = null

// const spatialReferenceStr =
//   'PROJCS["GUANGZHOU2000",GEOGCS["GCS_China_Geodetic_Coordinate_System_2000",DATUM["D_China_2000",SPHEROID["CGCS2000",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Gauss_Kruger"],PARAMETER["False_Easting",0.0],PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",113.28],PARAMETER["Scale_Factor",1.0],PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]'

// const spatialReference = new SpatialReference({ wkt: spatialReferenceStr })
const spatialReference = new SpatialReference({ wkid: 3857 })

const startGraphic = new Graphic({
  geometry: new Point({
    x: 0,
    y: 0,
    z: 0,
    spatialReference
  }),
  symbol: new PictureMarkerSymbol({
    url: new URL('@/assets/images/起点.svg?url', import.meta.url).href,
    width: 15,
    height: 15
  })
})

const endGraphic = new Graphic({
  geometry: new Point({
    x: 0,
    y: 0,
    z: 0,
    spatialReference
  }),
  symbol: new PictureMarkerSymbol({
    url: new URL('@/assets/images/终点.svg?url', import.meta.url).href,
    width: 15,
    height: 15
  })
})

// 3D飞行器
const runnerGraphic = new Graphic({
  geometry: new Point({
    x: 0,
    y: 0,
    z: 0,
    spatialReference
  }),
  symbol: new PointSymbol3D({
    symbolLayers: [
      new ObjectSymbol3DLayer({
        resource: { href: new URL('@/assets/glb/drone.glb', import.meta.url).href },
        width: 20,
        height: 5,
        heading: 270
      })
    ]
  })
})
// const runnerGraphic = new Graphic({
//   geometry: new Point({
//     x: 0,
//     y: 0,
//     z: 0,
//     spatialReference
//   }),
//   symbol: new PictureMarkerSymbol({
//     url: new URL('@/assets/images/终点.svg?url', import.meta.url).href,
//     width: 15,
//     height: 15
//   })
// })

const ROUTE_GRADIENT_STOPS = [
  [0, 57, 117, 237],
  [0.2199, 64, 77, 247],
  [0.4514, 0, 55, 255],
  [0.6875, 8, 247, 255],
  [0.8588, 73, 252, 133],
  [1, 7, 81, 230]
]

function interpolateColor(t) {
  let i = 0
  for (; i < ROUTE_GRADIENT_STOPS.length - 1; i++) {
    if (t <= ROUTE_GRADIENT_STOPS[i + 1][0]) break
  }
  const a = ROUTE_GRADIENT_STOPS[i]
  const b = ROUTE_GRADIENT_STOPS[i + 1]
  const s = (t - a[0]) / (b[0] - a[0])
  return new Color({
    r: a[1] + (b[1] - a[1]) * s,
    g: a[2] + (b[2] - a[2]) * s,
    b: a[3] + (b[3] - a[3]) * s,
    a: 0.7
  })
}

// 清除空绘制图层
function clearFlightGraphics() {
  stopFlightSimulation()
  if (flightGraphicsLayer) flightGraphicsLayer.removeAll()
}

// 停止飞行模拟逻辑
function stopFlightSimulation(onEnd = () => {}) {
  if (flightAnimationId != null) {
    cancelAnimationFrame(flightAnimationId)
    flightAnimationId = null
  }
  runnerGraphic.geometry = new Point({
    x: 0,
    y: 0,
    z: 0,
    spatialReference
  })
  flightGraphicsLayer.remove(runnerGraphic)
  onEnd()
}

// 初始化图层
function initLayer(_routeData) {
  if (dataId == _routeData?.id) return
  clearFlightGraphics()
  if ((_routeData?.points?.length || 0) < 2) return
  dataId = _routeData.id
  path = _routeData.points.map((p) => [p.x, p.y, p.z ?? 0])
  const numSegments = path.length - 1

  // 绘制渐变航线（和飞行时的航线完全一致）
  for (let i = 0; i < numSegments; i++) {
    const t = numSegments > 1 ? i / (numSegments - 1) : 0
    const color = interpolateColor(t)
    const line = new Polyline({
      paths: [[path[i], path[i + 1]]],
      spatialReference
    })
    const sym = new SimpleLineSymbol({
      color: color,
      // color: 'rgba(51, 153, 255, 0.5)',
      width: 10
    })
    flightGraphicsLayer.add(new Graphic({ geometry: line, symbol: sym }))
  }
  // 绘制起点图标（和飞行时的样式完全一致）
  const startPoints = path[0]
  startGraphic.geometry = new Point({
    x: startPoints[0],
    y: startPoints[1],
    z: startPoints[2],
    spatialReference
  })
  flightGraphicsLayer.add(startGraphic)

  // 绘制终点图标（和飞行时的样式完全一致）
  const endPoints = path[path.length - 1]
  endGraphic.geometry = new Point({
    x: endPoints[0],
    y: endPoints[1],
    z: endPoints[2],
    spatialReference
  })
  flightGraphicsLayer.add(endGraphic)
}

// 开始飞行模拟逻辑
function startFlightSimulation(flySpeed = 50, onEnd = () => {}) {
  stopFlightSimulation()

  if (!path || !path.length) return

  // 设置飞行器初始点位
  runnerGraphic.geometry = new Point({
    x: path[0][0],
    y: path[0][1],
    z: path[0][2],
    spatialReference
  })
  flightGraphicsLayer.add(runnerGraphic)

  // 关键新增：飞行开始时自动定位到航线起点
  handleGoTo(
    {
      target: new Point({
        x: path[0][0],
        y: path[0][1],
        z: path[0][2] + 20, // 稍微抬高视角，看得更清楚
        spatialReference
      }),
      zoom: 17.5
    },
    {
      animate: true,
      duration: 500,
      preserveViewProperties: true // 保留倾斜角、朝向等
    }
  )
  // 计算飞行参数
  const numSegments = path.length - 1

  let totalDist = 0
  const segLengths = []
  for (let i = 0; i < numSegments; i++) {
    const a = path[i],
      b = path[i + 1]
    const dist = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2])
    totalDist += dist
    segLengths.push(dist)
  }
  const durationMs = (totalDist / (flySpeed || 1)) * 1000
  const startTime = performance.now()

  function tick(now) {
    const elapsed = (now - startTime) / durationMs
    if (elapsed >= 1) {
      const last = path[path.length - 1]
      const p = new Point({
        x: last[0],
        y: last[1],
        z: last[2],
        spatialReference
      })
      runnerGraphic.geometry = p
      handleGoTo(
        {
          target: p
        },
        { animate: false, preserveViewProperties: true }
      )
      flightAnimationId = null
      stopFlightSimulation(onEnd)
      return
    }

    const targetDist = elapsed * totalDist
    let acc = 0
    let segIdx = 0
    for (; segIdx < numSegments; segIdx++) {
      if (acc + segLengths[segIdx] >= targetDist) break
      acc += segLengths[segIdx]
    }
    const k = segLengths[segIdx] > 0 ? (targetDist - acc) / segLengths[segIdx] : 0
    const a = path[segIdx],
      b = path[segIdx + 1]
    const x = a[0] + (b[0] - a[0]) * k
    const y = a[1] + (b[1] - a[1]) * k
    const z = a[2] + (b[2] - a[2]) * k

    const p = new Point({ x, y, z, spatialReference })
    runnerGraphic.geometry = p
    handleGoTo(
      {
        target: p
      },
      { animate: false, preserveViewProperties: true }
    )

    flightAnimationId = requestAnimationFrame(tick)
  }
  flightAnimationId = requestAnimationFrame(tick)
}

let goToing = false
function handleGoTo(a1, a2) {
  if (goToing) return
  goToing = true
  $view.goTo(...arguments).finally(() => {
    goToing = false
  })
}

// onMounted(() => {
//   $view?.map.add(flightGraphicsLayer)
// })

onUnmounted(() => {
  clearFlightGraphics()
  $layerStore.resetToDefault()
  $view?.environment?.set({ forceWebGLContext: true })
})

// 暴露方法
defineExpose({
  initLayer, // 只生成航线（不飞）
  clearFlightGraphics, // 清空图层
  startFlightSimulation, // 飞行模拟
  stopFlightSimulation // 停止飞行
})
</script>

<style lang="scss" scoped></style>
