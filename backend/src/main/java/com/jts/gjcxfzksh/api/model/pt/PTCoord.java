package com.jts.gjcxfzksh.api.model.pt;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.matsim.api.core.v01.Coord;

import java.io.Serializable;

/**
 * 坐标
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "坐标")
public class PTCoord implements Serializable {

    private Float x;
    private Float y;
    private Float z;

    /**
     * matsim 坐标转换
     *
     * @param coord matsim坐标对象
     */
    public PTCoord(Coord coord) {
        this.x = (float) coord.getX();
        this.y = (float) coord.getY();
        if (coord.hasZ()) {
            this.z = (float) coord.getZ();
        } else {
            this.z = 0f;
        }
    }

    public PTCoord(double x, double y, double z) {
        this.x = (float) x;
        this.y = (float) y;
        this.z = (float) z;
    }

    public PTCoord(double x, double y) {
        this.x = (float) x;
        this.y = (float) y;
        this.z = 0f;
    }

}
