package com.jts.gjcxfzksh.api.common;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.Database;
import com.jts.gjcxfzksh.data.entry.TileNetwork;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

public class DatasourceService {

    public MatsimData matsim_data(DatasourceParam param) {
        return database(param).matsim_data();
    }

    public TileNetwork tile_network(DatasourceParam param) {
        return database(param).tile_network();
    }

    public Database database(DatasourceParam param) {
        return Datasource.data(param.getDatasource());
    }

    public Network network(DatasourceParam param) {
        return matsim_data(param).getNetwork();
    }

    public TransitSchedule schedule(DatasourceParam param) {
        return matsim_data(param).getSchedule();
    }

}
