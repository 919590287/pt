package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;

@Data
public class DatasourceParam {

    String datasource;

    /** 体检评估空间范围；空值或“全市”表示全市，其余为真实行政区边界名称。 */
    String district;

}
