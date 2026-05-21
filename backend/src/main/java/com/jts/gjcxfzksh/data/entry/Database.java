package com.jts.gjcxfzksh.data.entry;

import com.jts.gjcxfzksh.data.MatsimData;

public class Database {

    private final MatsimData matsimData;
    private final TileNetwork tileNetwork;

    public Database(MatsimData matsimData) {
        this.matsimData = matsimData;
        // 瓦片路网
        this.tileNetwork = new TileNetwork(matsim_data().getNetwork());
    }

    public MatsimData matsim_data() {
        return matsimData;
    }

    public TileNetwork tile_network() {
        return tileNetwork;
    }

}
