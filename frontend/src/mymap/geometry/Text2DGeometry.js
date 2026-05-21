/**
 * Text = 3D Text
 *
 * parameters = {
 *  font: <THREE.Font>, // font
 *
 *  size: <float>, // size of the text
 *  curveSegments: <int>, // number of points on the curves
 * }
 */

import {
  ShapeGeometry
} from 'three';

class Text2DGeometry extends ShapeGeometry {

  constructor(text, parameters = {}) {

    const font = parameters.font;

    if (font === undefined) {

      super(); // generate default extrude geometry

    } else {

      const shapes = font.generateShapes(text, parameters.size);
      
      super(shapes, parameters.curveSegments);
    }

    this.type = 'Text2DGeometry';

  }

}


export { Text2DGeometry };
