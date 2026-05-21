package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.model.pt.PTLink;

import java.util.List;

public interface NetworkService {

    List<PTLink> tile(TileNetworkParam param);

}
