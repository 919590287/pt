package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.RealDataCommitParam;
import com.jts.gjcxfzksh.api.model.params.RealDataParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface RealDataService {

    List<String> areaList();

    Map<String, Object> adminDistricts(String areaName);

    Map<String, Object> busLineStation(String areaName, String versionId);

    Map<String, Object> history(String areaName);

    Map<String, Object> commitEdits(String username, RealDataCommitParam param);

    Map<String, Object> revertEdits(String username, RealDataParam param);

    Map<String, Object> compareUpload(String username, String areaName, String datasetType, List<MultipartFile> files);

}
