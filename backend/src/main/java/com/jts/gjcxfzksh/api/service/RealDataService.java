package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.RealDataCommitParam;
import com.jts.gjcxfzksh.api.model.params.RealDataParam;
import com.jts.gjcxfzksh.api.model.params.VehicleCalculationSaveParam;
import com.jts.gjcxfzksh.api.model.vo.RealDataExportVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface RealDataService {

    List<String> areaList();

    Map<String, Object> adminDistricts(String areaName);

    Map<String, Object> busLineStation(String areaName, String versionId);

    Map<String, Object> busLineStation(String areaName, String versionId, String include);

    Map<String, Object> history(String areaName);

    RealDataExportVO exportVersion(String areaName, String versionId, String datasetType, String format);

    Map<String, Object> commitEdits(String username, RealDataCommitParam param);

    Map<String, Object> saveVehicleCalculationResult(String username, VehicleCalculationSaveParam param);

    Map<String, Object> revertEdits(String username, RealDataParam param);

    Map<String, Object> compareUpload(String username, String areaName, String datasetType, List<MultipartFile> files);

}
