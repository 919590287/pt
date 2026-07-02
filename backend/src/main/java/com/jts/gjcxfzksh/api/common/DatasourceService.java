package com.jts.gjcxfzksh.api.common;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.Database;
import com.jts.gjcxfzksh.data.entry.TileNetwork;
import com.jts.gjcxfzksh.exception.BusinessException;
import jakarta.annotation.Resource;
import org.matsim.api.core.v01.network.Network;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

public class DatasourceService {

    @Resource
    MatsimConfig matsimConfig;

    public MatsimData matsim_data(DatasourceParam param) {
        return database(param).matsim_data();
    }

    public TileNetwork tile_network(DatasourceParam param) {
        return database(param).tile_network();
    }

    public Database database(DatasourceParam param) {
        checkDatasourceAccess(param);
        return Datasource.data(param.getDatasource());
    }

    public Network network(DatasourceParam param) {
        return matsim_data(param).getNetwork();
    }

    public TransitSchedule schedule(DatasourceParam param) {
        return matsim_data(param).getSchedule();
    }

    private void checkDatasourceAccess(DatasourceParam param) {
        if (param == null) {
            throw new BusinessException("请求参数不能为空");
        }
        if (param.getDatasource() == null || param.getDatasource().isBlank()) {
            throw new BusinessException("datasource 不能为空");
        }
        String username = CurrentUser.getUsername();
        if (username != null) {
            matsimConfig.requireSchemeAccess(param.getDatasource(), username);
        }
    }

}
