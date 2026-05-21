package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.vo.FacilityVO;
import com.jts.gjcxfzksh.api.service.FacilityService;
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
}
