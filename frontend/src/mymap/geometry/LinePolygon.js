import * as THREE from "three";

export const LINE_STYLE = {
  NONE: 1, // 不显示
  SOLID: 2, // 实线
  DASHED: 3, // 虚线
};

export const LINE_WIDTH_STYLE = {
  UNAUTO: 1, // 固定值
  AUTO: 2, // 根据数据值变化
};

export class LinePolygonGeometry extends THREE.BufferGeometry {
  constructor(lineList = []) {
    super();
    this.type = "LinePolygonGeometry";
    this.isLinePolygonGeometry = true;

    const attrPosition = new Array();
    const attrPosition2 = new Array();
    const attrSide = new Array();
    const attrDistance = new Array();
    // const attrColor = new Array();
    const attrPickColor = new Array();
    const attrUv = new Array();
    const attrIndex = new Array();
    let indexOffset = 0;

    for (const { points, pickColor } of lineList) {
      addLine(points, pickColor);
    }

    this.setAttribute("position", new THREE.Float32BufferAttribute(attrPosition, 3));
    this.setAttribute("position2", new THREE.Float32BufferAttribute(attrPosition2, 3));
    this.setAttribute("side", new THREE.Float32BufferAttribute(attrSide, 2));
    this.setAttribute("distance", new THREE.Float32BufferAttribute(attrDistance, 1));
    this.setAttribute("pickColor", new THREE.Float32BufferAttribute(attrPickColor, 3));
    this.setAttribute("uv", new THREE.Float32BufferAttribute(attrUv, 2));
    this.setIndex(attrIndex);
    if (attrPosition.length) {
      this.computeBoundingBox();
      this.computeBoundingSphere();
    }

    function addLine(points, pickColor) {
      const pColor = new THREE.Color(pickColor);
      for (let i1 = 1, l1 = points.length; i1 < l1; i1++) {
        const [fromX, fromY, fromZ = 0] = points[i1 - 1];
        const [toX, toY, toZ = 0] = points[i1];
        addSegment(fromX, fromY, fromZ, toX, toY, toZ, pColor);
      }
    }

    function addSegment(fromX, fromY, fromZ, toX, toY, toZ, pColor) {
      const baseIndex = indexOffset;

      attrPosition.push(fromX, fromY, fromZ, fromX, fromY, fromZ, toX, toY, toZ, toX, toY, toZ);
      attrPosition2.push(toX, toY, toZ, toX, toY, toZ, fromX, fromY, fromZ, fromX, fromY, fromZ);
      // 线段方向，宽度位移方向
      attrSide.push(1, 0.5, 1, -0.5, -1, 0.5, -1, -0.5);
      attrDistance.push(0, 0, 0, 0);
      attrPickColor.push(
        pColor.r, pColor.g, pColor.b,
        pColor.r, pColor.g, pColor.b,
        pColor.r, pColor.g, pColor.b,
        pColor.r, pColor.g, pColor.b,
      );
      attrUv.push(0, 0, 1, 0, 0, 1, 1, 1);
      attrIndex.push(baseIndex, baseIndex + 1, baseIndex + 3, baseIndex, baseIndex + 3, baseIndex + 2);

      indexOffset += 4;
    }
  }
}

export class LineSegmentPolygonGeometry extends THREE.BufferGeometry {
  constructor(lineList = []) {
    super();
    this.type = "LineSegmentPolygonGeometry";
    this.isLineSegmentPolygonGeometry = true;

    let segmentCount = 0;
    for (const item of lineList) {
      segmentCount += item.links?.length || 0;
      segmentCount += item.segments?.length || 0;
    }

    const vertexCount = segmentCount * 4;
    const attrPosition = new Float32Array(vertexCount * 3);
    const attrPosition2 = new Float32Array(vertexCount * 3);
    const attrSide = new Float32Array(vertexCount * 2);
    const attrDistance = new Float32Array(vertexCount);
    const attrFlow = new Float32Array(vertexCount);
    const attrPickColor = new Float32Array(vertexCount * 3);
    const attrUv = new Float32Array(vertexCount * 2);
    const attrIndex = vertexCount > 65535 ? new Uint32Array(segmentCount * 6) : new Uint16Array(segmentCount * 6);

    let positionOffset = 0;
    let position2Offset = 0;
    let sideOffset = 0;
    let flowOffset = 0;
    let pickColorOffset = 0;
    let uvOffset = 0;
	    let indexOffset = 0;
	    let vertexOffset = 0;
	    let flowMin = Infinity;
	    let flowMax = -Infinity;
	    let hasFlowData = false;

    for (const item of lineList) {
      const pColor = new THREE.Color(item.pickColor);
      if (item.links) addLinks(item.links, item.center || [0, 0], pColor);
      if (item.segments) addLine(item.segments, pColor);
    }

    this.setAttribute("position", new THREE.BufferAttribute(attrPosition, 3));
    this.setAttribute("position2", new THREE.BufferAttribute(attrPosition2, 3));
    this.setAttribute("side", new THREE.BufferAttribute(attrSide, 2));
    this.setAttribute("distance", new THREE.BufferAttribute(attrDistance, 1));
    this.setAttribute("flow", new THREE.BufferAttribute(attrFlow, 1));
    this.setAttribute("pickColor", new THREE.BufferAttribute(attrPickColor, 3));
    this.setAttribute("uv", new THREE.BufferAttribute(attrUv, 2));
    this.setIndex(new THREE.BufferAttribute(attrIndex, 1));
    if (vertexCount) {
      this.computeBoundingBox();
      this.computeBoundingSphere();
	    }
	    this.flowMin = Number.isFinite(flowMin) ? flowMin : 0;
	    this.flowMax = Number.isFinite(flowMax) ? flowMax : 0;
	    this.hasFlowData = hasFlowData;

    function addLine(segments, pColor) {
      for (let i1 = 0, l1 = segments.length; i1 < l1; i1++) {
        const [from, to] = segments[i1];
        const [fromX, fromY, fromZ = 0] = from;
        const [toX, toY, toZ = 0] = to;
        addSegment(fromX, fromY, fromZ, toX, toY, toZ, pColor, 0);
      }
    }

    function addLinks(links, center, pColor) {
      const [centerX, centerY] = center;
      const orderedLinks = links
        .map((link, index) => ({ link, index, flow: getLinkFlow(link) }))
        .sort((a, b) => (a.flow - b.flow) || (a.index - b.index));

      for (let i = 0, l = orderedLinks.length; i < l; i++) {
        const { link, flow } = orderedLinks[i];
        const { from, to } = link;
        addSegment(
          from.x - centerX,
          from.y - centerY,
          from.z ?? 0,
          to.x - centerX,
          to.y - centerY,
          to.z ?? 0,
          pColor,
          flow,
        );
      }
    }

    function getLinkFlow(link) {
      const flow = Number(
        link.flow ??
        link.trafficVolume ??
        link.traffic_volume ??
        link.simulatedTrafficVolume ??
        link.simulated_traffic_volume ??
        0,
      );
	      if (!Number.isFinite(flow)) {
	        return 0;
	      }
	      if (flow > 0) {
	        hasFlowData = true;
	      }
	      if (flow < flowMin) {
	        flowMin = flow;
	      }
      if (flow > flowMax) {
        flowMax = flow;
      }
      return flow;
    }

    function addSegment(fromX, fromY, fromZ, toX, toY, toZ, pColor, flow) {
      const baseIndex = vertexOffset;

      attrPosition[positionOffset++] = fromX;
      attrPosition[positionOffset++] = fromY;
      attrPosition[positionOffset++] = fromZ;
      attrPosition[positionOffset++] = fromX;
      attrPosition[positionOffset++] = fromY;
      attrPosition[positionOffset++] = fromZ;
      attrPosition[positionOffset++] = toX;
      attrPosition[positionOffset++] = toY;
      attrPosition[positionOffset++] = toZ;
      attrPosition[positionOffset++] = toX;
      attrPosition[positionOffset++] = toY;
      attrPosition[positionOffset++] = toZ;

      attrPosition2[position2Offset++] = toX;
      attrPosition2[position2Offset++] = toY;
      attrPosition2[position2Offset++] = toZ;
      attrPosition2[position2Offset++] = toX;
      attrPosition2[position2Offset++] = toY;
      attrPosition2[position2Offset++] = toZ;
      attrPosition2[position2Offset++] = fromX;
      attrPosition2[position2Offset++] = fromY;
      attrPosition2[position2Offset++] = fromZ;
      attrPosition2[position2Offset++] = fromX;
      attrPosition2[position2Offset++] = fromY;
      attrPosition2[position2Offset++] = fromZ;

      attrSide[sideOffset++] = 1;
      attrSide[sideOffset++] = 0.5;
      attrSide[sideOffset++] = 1;
      attrSide[sideOffset++] = -0.5;
      attrSide[sideOffset++] = -1;
      attrSide[sideOffset++] = 0.5;
      attrSide[sideOffset++] = -1;
      attrSide[sideOffset++] = -0.5;

      attrFlow[flowOffset++] = flow;
      attrFlow[flowOffset++] = flow;
      attrFlow[flowOffset++] = flow;
      attrFlow[flowOffset++] = flow;

      for (let i = 0; i < 4; i++) {
        attrPickColor[pickColorOffset++] = pColor.r;
        attrPickColor[pickColorOffset++] = pColor.g;
        attrPickColor[pickColorOffset++] = pColor.b;
      }

      attrUv[uvOffset++] = 0;
      attrUv[uvOffset++] = 0;
      attrUv[uvOffset++] = 1;
      attrUv[uvOffset++] = 0;
      attrUv[uvOffset++] = 0;
      attrUv[uvOffset++] = 1;
      attrUv[uvOffset++] = 1;
      attrUv[uvOffset++] = 1;

      attrIndex[indexOffset++] = baseIndex;
      attrIndex[indexOffset++] = baseIndex + 1;
      attrIndex[indexOffset++] = baseIndex + 3;
      attrIndex[indexOffset++] = baseIndex;
      attrIndex[indexOffset++] = baseIndex + 3;
      attrIndex[indexOffset++] = baseIndex + 2;

      vertexOffset += 4;
	    }
		  }
		}

// export class LinePolygonMaterial extends THREE.Material {
//   constructor(argu) {
//     super();
//     this.isLinePolygonMaterial = true;
//     const { color = 0xff0000, opacity = 1, lineStyle = LINE_STYLE.SOLID, lineWidth = 50, lineOffset = 0, colorBar = null, ...params } = argu || {};
//     // this.alphaTest = 0.1;
//     // this.transparent = true;
//     // this.depthWrite = false;
//     this.defines = {
//       USE_COLOR_BAR: !!colorBar,
//     };
//     this.uniforms = {
//       diffuse: {
//         value: new THREE.Color(color),
//       },
//       opacity: {
//         value: opacity,
//       },
//       lineStyle: {
//         value: lineStyle,
//       },
//       lineWidth: {
//         value: lineWidth,
//       },
//       lineOffset: {
//         value: lineOffset,
//       },
//     };
//     this.vertexShader = `
//       #include <common>
//       #include <logdepthbuf_pars_vertex>

//       attribute vec2 side;
//       attribute float value;
//       attribute float distance;
//       attribute vec3 position2;

//       varying vec3 vColor;
//       varying vec2 vUv;
//       varying float vValue;
//       varying float vDistance;

//       uniform float lineWidth;
//       uniform float lineOffset;
//       uniform mat3 uvTransform;

//       void main() {
//         vValue = value;
//         vDistance = distance;

//         #ifdef USE_MAP
//           vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
//         #endif

//         vec3 transformed = vec3(position);

//         // 线段
//         vec2 dir = normalize(position.xy - position2.xy) * side.x;
//         // 线段法向量
//         vec2 normal = vec2(-dir.y, dir.x);
//         // 宽度位移
//         vec2 width = normal * lineWidth * side.y;
//         // 线段位移
//         vec2 offset = normal * lineOffset;
//         // 顶点位置
//         transformed = vec3(position.xy + width + offset, position.z);

//         gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed, 1.0 );

//         #include <logdepthbuf_vertex>
//       }
//     `;
//     this.fragmentShader = `
//       #include <common>
//       #include <logdepthbuf_pars_fragment>

//       uniform float lineWidth;
//       uniform float lineStyle;

//       uniform vec3 diffuse;
//       uniform float opacity;
//       uniform sampler2D map;
//       uniform sampler2D colorBar;
//       uniform float maxValue;
//       uniform float minValue;

//       varying vec3 vColor;
//       varying vec2 vUv;
//       varying float vValue;
//       varying float vDistance;

//       void main() {
//         vec4 diffuseColor = vec4( diffuse, opacity );

//         #include <logdepthbuf_fragment>

//         #ifdef USE_COLOR_BAR
//           float p = 0.0;
//           if(maxValue != minValue) {
//             p = (vValue - minValue) / (maxValue - minValue);
//           }
//           if(p> 1.0) p = 1.0;
//           if(p< 0.0) p = 0.0;
//           vec4 barDiffuseColor = texture2D(colorBar, vec2(p , 0.5));
//           diffuseColor = barDiffuseColor;
//           diffuseColor.a *= opacity;
//         #endif

//         if(lineStyle == ${Number(LINE_STYLE.DASHED).toFixed(1)}){
//           float dl = mod(vDistance / (lineWidth * 3.0), 1.0);
//           if(0.50 < dl && dl <= 1.0){
//             diffuseColor.a = 0.0;
//           }
//         } else if(lineStyle == ${Number(LINE_STYLE.NONE).toFixed(1)}){
//           diffuseColor.a = 0.0;
//         }

//         gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);

//       }
//     `;
//     this.setValues(params);
//   }
// }

export class LinePolygonMaterial extends THREE.MeshBasicMaterial {
	  constructor(argu) {
	    const {
	      lineStyle = LINE_STYLE.SOLID,
	      lineWidth = 50,
	      lineOffset = 0,
	      useFlowControl = false,
	      hasFlowData = false,
	      flowMin = 0,
	      flowMax = 0,
	      flowMinWidth = 1,
	      flowMaxWidth = 40,
	      flowWidthStep = 20,
	      resolution = null,
	      depthTest = true,
	      depthWrite = false,
	      transparent = true,
	      polygonOffset = true,
	      polygonOffsetFactor = -1,
	      polygonOffsetUnits = -1,
	      ...params
	    } = argu || {};
    super({ transparent, polygonOffset, polygonOffsetFactor, polygonOffsetUnits, ...params });
    // Keep depth testing so 3D buildings can occlude lines, but do not let
    // road/route layers write depth and fight each other on the same plane.
    this.depthTest = depthTest;
    this.depthWrite = depthWrite;
    this.forceSinglePass = true;
    this.userData.lineWidth = lineWidth;
    this.userData.lineOffset = lineOffset;
	    this.userData.lineStyle = lineStyle;
	    this.userData.useFlowControl = useFlowControl;
	    this.userData.hasFlowData = hasFlowData;
	    this.userData.flowMin = flowMin;
    this.userData.flowMax = flowMax;
    this.userData.flowMinWidth = flowMinWidth;
    this.userData.flowMaxWidth = flowMaxWidth;
    this.userData.flowWidthStep = flowWidthStep;
	    this.userData.resolution = resolution?.isVector2
	      ? resolution
	      : new THREE.Vector2(
	        typeof window !== "undefined" ? window.innerWidth || 1 : 1,
	        typeof window !== "undefined" ? window.innerHeight || 1 : 1,
	      );
    this.onBeforeCompile = (shader) => {
      this.userData.shader = shader;
      
      shader.uniforms.lineStyle = { value: this.userData.lineStyle };
      shader.uniforms.lineWidth = { value: this.userData.lineWidth };
	      shader.uniforms.lineOffset = { value: this.userData.lineOffset };
	      shader.uniforms.useFlowControl = { value: this.userData.useFlowControl };
	      shader.uniforms.hasFlowData = { value: this.userData.hasFlowData };
	      shader.uniforms.flowMin = { value: this.userData.flowMin };
      shader.uniforms.flowMax = { value: this.userData.flowMax };
      shader.uniforms.flowMinWidth = { value: this.userData.flowMinWidth };
      shader.uniforms.flowMaxWidth = { value: this.userData.flowMaxWidth };
      shader.uniforms.flowWidthStep = { value: this.userData.flowWidthStep };
	      shader.uniforms.resolution = { value: this.userData.resolution };

      shader.vertexShader = shader.vertexShader.replace(
        "#include <common>",
        `
        #include <common>
        
        attribute vec2 side;
        attribute float distance;
        attribute float flow;
        attribute vec3 position2;

        uniform float lineWidth;
	        uniform float lineOffset;
	        uniform bool useFlowControl;
	        uniform bool hasFlowData;
	        uniform float flowMin;
	        uniform float flowMax;
			        uniform float flowMinWidth;
			        uniform float flowMaxWidth;
			        uniform float flowWidthStep;
			        uniform vec2 resolution;

		        varying float vDistance;
		        varying float vFlowRatio;
		        float vEffectiveLineWidth;

			        float flowLineWidth(float value) {
			          float baseLineWidth = max(3.0, lineWidth);
			          if (value < 0.064) {
			            return baseLineWidth;
			          } else if (value < 0.216) {
			            return baseLineWidth + flowWidthStep;
			          } else if (value < 0.512) {
			            return baseLineWidth + flowWidthStep * 2.0;
			          }
			          return baseLineWidth + flowWidthStep * 3.0;
			        }

		      `,
      );
      shader.vertexShader = shader.vertexShader.replace(
        "#include <begin_vertex>",
	        `
	          #include <begin_vertex>

		          if (flowMax > flowMin) {
		            vFlowRatio = clamp((flow - flowMin) / (flowMax - flowMin), 0.0, 1.0);
			          } else {
			            vFlowRatio = flowMax > 0.0 ? 1.0 : 0.0;
			          }
			          float adaptiveLineWidth = flowLineWidth(vFlowRatio);
			          vEffectiveLineWidth = useFlowControl && hasFlowData ? adaptiveLineWidth : lineWidth;

	          vec2 dir = normalize(position.xy - position2.xy) * side.x;
	          vec2 normal = vec2(-dir.y, dir.x);
	          vec2 width = normal * vEffectiveLineWidth * side.y;
	          vec2 offset = normal * lineOffset;
	          transformed = vec3(position.xy + width + offset, position.z);
	        `,
      );
	      shader.vertexShader = shader.vertexShader.replace(
	        "#include <project_vertex>",
	        `
	          vec4 mvPosition = vec4(transformed, 1.0);

	          #ifdef USE_BATCHING
	            mvPosition = batchingMatrix * mvPosition;
	          #endif

	          #ifdef USE_INSTANCING
	            mvPosition = instanceMatrix * mvPosition;
	          #endif

	          mvPosition = modelViewMatrix * mvPosition;
	          gl_Position = projectionMatrix * mvPosition;
	        `,
	      );
      shader.fragmentShader = shader.fragmentShader.replace(
        "#include <common>",
        `
        #include <common>

	        uniform bool useFlowControl;
	        uniform bool hasFlowData;
	        varying float vFlowRatio;

			        vec3 flowColor(float value) {
			          if (value < 0.064) {
			            return vec3(0.1725, 0.4824, 0.7137);
			          } else if (value < 0.216) {
			            return vec3(1.0, 1.0, 0.7490);
			          } else if (value < 0.512) {
			            return vec3(0.9922, 0.6824, 0.3804);
		          }
		          return vec3(0.8431, 0.0980, 0.1098);
        }
        `,
      );
      shader.fragmentShader = shader.fragmentShader.replace(
        "#include <color_fragment>",
        `
	        #include <color_fragment>
	        if (useFlowControl && hasFlowData) {
	          diffuseColor.rgb = flowColor(vFlowRatio);
	        }
        `,
      );
    };
  }
}
