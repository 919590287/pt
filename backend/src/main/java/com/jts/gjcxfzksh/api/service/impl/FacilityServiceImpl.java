package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.StationInfoParam;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.vo.FacilityVO;
import com.jts.gjcxfzksh.api.service.FacilityService;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimPassengerProfileCache;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.cache.MatsimStationPanelCache;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FacilityServiceImpl extends DatasourceService implements FacilityService {


    @Override
    public List<FacilityVO> facilityAll(DatasourceParam param) {
        List<Object> cached = MatsimPrecomputedCache.readStations(matsim_data(param));
        if (cached != null) {
            return (List<FacilityVO>) (List<?>) cached;
        }
        List<FacilityVO> facilityVoList = new ArrayList<>();
        Map<Id<TransitStopFacility>, TransitStopFacility> facilityMap = schedule(param).getFacilities();
        for (Id<TransitStopFacility> facilityId : facilityMap.keySet()) {
            TransitStopFacility facility = facilityMap.get(facilityId);
            FacilityVO vo = new FacilityVO();
            vo.setFacilityName(facility.getName());
            vo.setFacilityId(facilityId.toString());
            vo.setCoord(new PTCoord(facility.getCoord()));
            facilityVoList.add(vo);
        }
        return facilityVoList;
    }

    @Override
    public Map<String, Object> stationPanel(DatasourceParam param) {
        return MatsimStationPanelCache.readStationPanelIndex(matsim_data(param));
    }

    @Override
    public Map<String, Object> stationPanelDetail(StationInfoParam param) {
        MatsimData data = matsim_data(param);
        return MatsimPassengerProfileCache.applyStationProfile(data,
                MatsimStationPanelCache.readStationPanelDetail(
                        data, param.getStationName(), param.getFacilityId()));
    }
}
