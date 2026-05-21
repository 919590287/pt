package com.jts.gjcxfzksh.api.model.vo;

import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import lombok.Data;

@Data
public class FacilityVO {

    String facilityId;
    String facilityName;
    PTCoord coord;

}
