package com.jts.gjcxfzksh.data.entry;

import com.jts.gjcxfzksh.data.MatsimData;

public class Database {

    private final MatsimData matsimData;
    private volatile TileNetwork tileNetwork;

    public Database(MatsimData matsimData) {
        this.matsimData = matsimData;
    }

    public MatsimData matsim_data() {
        return matsimData;
    }

    public TileNetwork tile_network() {
        if (tileNetwork == null) {
            synchronized (this) {
                if (tileNetwork == null) {
                    tileNetwork = new TileNetwork(matsim_data().getNetwork());
                }
            }
        }
        return tileNetwork;
    }

}
