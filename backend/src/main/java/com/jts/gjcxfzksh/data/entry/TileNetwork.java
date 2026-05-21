package com.jts.gjcxfzksh.data.entry;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.ArrayList;
import java.util.List;

@Data
@Slf4j
public class TileNetwork {

    public static final int ZOOM = 1;
    public static final double EARTH_RADIUS = 20037508.3427892;

    private Table<Integer, Integer, List<Id<Link>>> tileLinks = HashBasedTable.create();

    public TileNetwork(Network network) {
        network.getLinks().forEach((linkId, link) -> {
            int[] cr = coordInTile(link.getCoord());
            List<Id<Link>> links = tileLinks.get(cr[0], cr[1]);
            if (links == null) {
                links = new ArrayList<>();
                tileLinks.put(cr[0], cr[1], links);
            }
            links.add(linkId);
        });
    }


    /**
     * 定位坐标属于哪个瓦片
     *
     * @param coord 坐标
     * @return [col, row]
     */
    public static int[] coordInTile(Coord coord) {
        int col = (int) Math.floor(((EARTH_RADIUS + coord.getX()) * Math.pow(2, ZOOM)) / (EARTH_RADIUS * 2));
        int row = (int) Math.floor(((EARTH_RADIUS - coord.getY()) * Math.pow(2, ZOOM)) / (EARTH_RADIUS * 2));
        return new int[]{col, row};
    }

    /**
     * 获取瓦片中心坐标
     *
     * @return [x, y]
     */
    public static double[] tileCenter(double row, double col) {
        double x = ((row + 0.5) * (EARTH_RADIUS * 2)) / Math.pow(2, ZOOM) - EARTH_RADIUS;
        double y = EARTH_RADIUS - ((col + 0.5) * (EARTH_RADIUS * 2)) / Math.pow(2, ZOOM);
        return new double[]{x, y};
    }

}
