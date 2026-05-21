package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TileNetworkParam extends DatasourceParam {

    private int x;
    private int y;

}
