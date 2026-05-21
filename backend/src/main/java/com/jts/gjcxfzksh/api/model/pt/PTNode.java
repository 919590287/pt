package com.jts.gjcxfzksh.api.model.pt;

import lombok.Data;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Node;

@Data
public class PTNode {

    private String id;
    private PTCoord coord;

    public PTNode(PTCoord coord) {
        this.coord = coord;
    }

    public PTNode(Node node) {
        this.coord = new PTCoord(node.getCoord());
    }


    public PTNode(Coord coord) {
        this.coord = new PTCoord(coord);
    }

}
