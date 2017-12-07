package com.insaic.kylin.handler;

import com.insaic.base.exception.ExceptionUtil;
import com.insaic.base.mapper.BeanMapper;
import com.insaic.base.mapper.JsonMapper;
import com.insaic.base.utils.DateStyle;
import com.insaic.base.utils.DateUtil;
import com.insaic.base.utils.Identities;
import com.insaic.base.utils.StringUtil;
import com.insaic.kylin.enums.DonwloadFileStatus;
import com.insaic.kylin.model.kylin.config.KylinConfigInfo;
import com.insaic.kylin.model.kylin.download.DownloadInfo;
import com.insaic.kylin.model.kylin.init.KylinLoadCube;
import com.insaic.kylin.model.kylin.init.KylinLoadModel;
import com.insaic.kylin.model.kylin.init.KylinLoadProject;
import com.insaic.kylin.model.kylin.init.KylinLoadSelectOption;
import com.insaic.kylin.model.kylin.project.*;
import com.insaic.kylin.model.kylin.query.KylinQueryData;
import com.insaic.kylin.model.kylin.query.KylinQueryInfo;
import com.insaic.kylin.model.kylin.receive.*;
import com.insaic.kylin.model.kylin.show.TableShowInfo;
import com.insaic.kylin.service.AuthenticationService;
import com.insaic.kylin.service.HbaseService;
import com.insaic.kylin.service.KylinBaseService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.ehcache.EhCacheCacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;

/**
 * Created by dongyang on 2017/9/13.
 */
@Component
@Lazy(value=false)
public class KylinHandler {
    private static final String CACHE_NAME = "configEhCache";
    private static final String KYLIN_LOAD_SELECT_DATA = "kylinLoadSelectData";
    private static final String KYLIN_LOAD_DATAS = "kylinLoadDatas";
    private static final String KYLIN_PENDING_DATAS = "kylinPendingDatas";
    private static final String KYLIN_PENDING_DATA_FLAG = "kylinPendingDataFlag";
    @Autowired
    @Qualifier("cacheManager")
    private EhCacheCacheManager cacheManager;
    @Autowired
    private AuthenticationService authenticationService;
    @Autowired
    private KylinBaseService kylinBaseService;
    @Autowired
    private HbaseService hbaseService;


    /**
     * @Author dongyang
     * @Describe 初始化载入select信息
     * @Date 2017/9/13 上午11:13
     */
    public Map<String, Object> loadSelectInfo(Boolean cleanCache, String userCode) {
        if (cleanCache) {
            this.resetCache("loadData");
        }
        Map<String, Object> modelMap = (Map<String, Object>)this.getInfoByName("loadData");
        // 权限过滤
        List<KylinLoadSelectOption> kylinLoadSelectOptions = (List<KylinLoadSelectOption>) modelMap.get(KYLIN_LOAD_SELECT_DATA);
        if (CollectionUtils.isNotEmpty(kylinLoadSelectOptions)) {
            List<KylinLoadSelectOption> selectOptions = authenticationService.getSelectMenuByUser(kylinLoadSelectOptions, userCode);
            modelMap.put(KYLIN_LOAD_SELECT_DATA, selectOptions);
        }

        return modelMap;
    }

    /**
     * @Author dongyang
     * @Describe kylin sql query
     * @Date 2017/9/14 下午7:42
     */
    public Map<String, Object> getKylinSql(KylinQueryData queryData) {
        Map<String, Object> modelMap = new HashedMap();
        // 拼接sql
        String sql = this.organizeKylinQuerySql(queryData);
        // 调用kylin接口
        QueryReturnData returnData = this.getKylinQuery(sql, queryData);
        // 翻译表头
        TableShowInfo tableShowInfo = this.translateTableHeadColumn(returnData, queryData);
        // 设置返回内容
        modelMap.put("returnData", tableShowInfo);
        modelMap.put("querySql", sql);

        return modelMap;
    }

    /**
     * @Author dongyang
     * @Describe 添加下载任务
     * @Date 2017/9/18 下午2:16
     */
    public Map<String, Object> addDownloadFile(KylinQueryData queryData) {
        Map<String, Object> modelMap = new HashedMap();
        DownloadInfo downloadInfo = new DownloadInfo();
        downloadInfo.setFileCode(queryData.getCube() + "_" + DateUtil.dateToString(new Date(), DateStyle.YYYYMMDDHHMMSS));
        downloadInfo.setFileName(downloadInfo.getFileCode());
        downloadInfo.setFileStatus(DonwloadFileStatus.generating);
        downloadInfo.setFileStatusName(DonwloadFileStatus.generating.getValue());
        downloadInfo.setKylinQueryData(queryData);
        downloadInfo.setCreateTime(DateUtil.dateToString(new Date(), DateStyle.HH_MM_SS));
        List<DownloadInfo> downloadInfos = (List<DownloadInfo>) this.setInfoByName("downloadInfo", downloadInfo);

        modelMap.put("downloadInfo", downloadInfos);

        return modelMap;
    }

    /**
     * @Author dongyang
     * @Describe 获取刷新待下载文件生成状态
     * @Date 2017/9/18 下午4:57
     */
    public Map<String, Object> refreshDownloadFileStatus() {
        Map<String, Object> modelMap = new HashedMap();
        List<DownloadInfo> downloadInfos = (List<DownloadInfo>) this.getInfoByName("downloadInfo");
        modelMap.put("downloadInfo", downloadInfos);

        return modelMap;
    }

    /**
     * @Author dongyang
     * @Describe
     * @Date 2017/9/18 下午8:21
     */
    public void setDownloadFileStatus(String fileCode) {
        DownloadInfo downloadInfo = new DownloadInfo();
        downloadInfo.setFileCode(fileCode);
        downloadInfo.setFileStatus(DonwloadFileStatus.downLoad);
        downloadInfo.setFileStatusName(DonwloadFileStatus.downLoad.getValue());
        this.setInfoByName("downloadInfo", downloadInfo);
    }

    /**
     * @Author dongyang
     * @Describe 每隔一秒钟,查询待下载任务,并生成excel文件
     * @Date 2017/9/17 下午12:30
     */
    @Scheduled(cron = "*/1 * * * * ?")
    public void dispatcherTimerQueryDownloadTask() {
        try {
            this.executeDownloadTaks();
        } catch (Exception e) {
            ExceptionUtil.handleException(e);
        }
    }

    /**
     * @Author dongyang
     * @Describe 每天晚上11点删除当日生成文件及缓存信息
     * @Date 2017/9/19 下午1:47
     */
    @Scheduled(cron = "0 0 23 * * ?")
    public void dispatcherTimerDeleteDownloadFile() {
        try {
            String path = this.getClass().getResource("/").getPath()
                    + "download/"
                    + DateUtil.dateToString(new Date(), DateStyle.YYYYMMDD) + "/";
            File directory = new File(path);
            if (directory.exists()) {
                FileUtils.deleteDirectory(directory);
            }
            this.resetCache("downloadInfo");
        } catch (Exception e) {
            ExceptionUtil.handleException(e);
        }
    }

    /**
     * @Author dongyang
     * @Describe 获取麒麟配置信息
     * @Date 2017/10/12 下午2:23
     */
    public Map<String, Object> getKylinConfigData() {
        Map<String, Object> modelMap = (Map<String, Object>)this.getInfoByName("loadData");
        List<KylinLoadProject> kylinLoadProjects = (List<KylinLoadProject>) modelMap.get(KYLIN_LOAD_DATAS);
        List<KylinConfigInfo> kylinConfigInfos = this.buildKylinConfigInfo(kylinLoadProjects);
        Map<String, Object> configMap = new HashedMap();
        configMap.put("kylinConfigInfo", kylinConfigInfos);

        return configMap;
    }

    /**
     * @Author dongyang
     * @Describe 获取尚未配置kylin数据
     * @Date 2017/10/17 上午11:42
     */
    public Map<String, Object> getKylinPendingConfigData() {
        Map<String, Object> modelMap = new HashedMap();
        // 1.获取已配置信息
        Map<String, Object> loadData = (Map<String, Object>)this.getInfoByName("loadData");
        // 2.获取cube信息(默认cubeCode唯一)
        Map<String, String> cubes = this.getCubeCodes((List<KylinLoadSelectOption>) loadData.get(KYLIN_LOAD_SELECT_DATA));
        // 3.获取尚未手动配置cube
        List<KylinConfigInfo> kylinPendingConfigInfos = this.getPendingCubes(cubes);
        if (CollectionUtils.isNotEmpty(kylinPendingConfigInfos)) {
            // 存在尚未配置信息
            modelMap.put(KYLIN_PENDING_DATA_FLAG, true);
        } else {
            modelMap.put(KYLIN_PENDING_DATA_FLAG, false);
        }
        modelMap.put(KYLIN_PENDING_DATAS, kylinPendingConfigInfos);

        return modelMap;
    }

    /**
     * @Author dongyang
     * @Describe 保存/更新/删除 配置信息
     * @Date 2017/10/18 上午11:24
     */
    public Map<String, Object> updateConfigInfo(List<KylinConfigInfo> kylinConfigInfos) {
        Map<String, Object> modelMap = new HashedMap();
        // 1.组织新增下拉选项
        List<KylinLoadSelectOption> kylinLoadSelectOptions = this.organizeKylinSelectOptions(kylinConfigInfos);
        // 2.组织新增培新选项
        List<KylinLoadProject> kylinLoadProjects = this.organizeKylinLoadProject(kylinConfigInfos);
        // 3.保存数据
        modelMap.put(KYLIN_LOAD_SELECT_DATA, kylinLoadSelectOptions);
        modelMap.put(KYLIN_LOAD_DATAS, kylinLoadProjects);
        hbaseService.updateKylinLoadInfo(modelMap);
        // 4.更新缓存
        this.setInfoByName("loadData", modelMap);

        return this.getKylinConfigData();
    }

    private List<KylinConfigInfo> getPendingCubes(Map<String, String> cubes) {
        // 1.获取kylin所有cube
        List<CubesReturnData> cubesReturnDatas = this.kylinBaseService.getKylinCubes();
        if (CollectionUtils.isEmpty(cubesReturnDatas)) {
            return null;
        }
        // 2.获取待处理cubeCode
        List<String> pendingCubes = new ArrayList<>();
        for (CubesReturnData returnData :  cubesReturnDatas) {
            if ("READY".equals(returnData.getStatus())) {
                if (MapUtils.isNotEmpty(cubes)) {
                    String cube = cubes.get(returnData.getName());
                    if (StringUtil.isBlank(cube)) {
                        pendingCubes.add(returnData.getName());
                    }
                } else {
                    pendingCubes.add(returnData.getName());
                }
            }
        }
        // 3.获取待处理cube详细信息
        if (CollectionUtils.isEmpty(pendingCubes)) {
            return null;
        }
        List<CubeDescReturnData> cubeDescDatas = new ArrayList<>();
        for (String cube : pendingCubes) {
            List<CubeDescReturnData> cubeDescReturnDatas = this.kylinBaseService.getKylinCubeDesc(cube);
            if (CollectionUtils.isNotEmpty(cubeDescReturnDatas)) {
                cubeDescDatas.addAll(cubeDescReturnDatas);
            }
        }
        // 4.组织返回前端数据
        if (CollectionUtils.isEmpty(cubeDescDatas)) {
            return null;
        }
        return this.getOrganizePendingInfo(cubeDescDatas);
    }

    private List<KylinLoadSelectOption> organizeKylinSelectOptions(List<KylinConfigInfo> kylinConfigInfos) {
        List<KylinLoadSelectOption> kylinLoadSelectOptions = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(kylinConfigInfos)) {
            Map<String, Map<String, List<KylinConfigInfo>>> optionMaps = new HashedMap();
            for (KylinConfigInfo kylinConfigInfo : kylinConfigInfos) {
                if (optionMaps.containsKey(kylinConfigInfo.getProjectCode())) {
                    Map<String, List<KylinConfigInfo>> modelMaps = optionMaps.get(kylinConfigInfo.getProjectCode());
                    if (modelMaps.containsKey(kylinConfigInfo.getModelCode())) {
                        List<KylinConfigInfo> configInfos = modelMaps.get(kylinConfigInfo.getModelCode());
                        configInfos.add(kylinConfigInfo);
                    } else {
                        List<KylinConfigInfo> configInfos = new ArrayList<>();
                        configInfos.add(kylinConfigInfo);
                        modelMaps.put(kylinConfigInfo.getModelCode(), configInfos);
                    }
                } else {
                    Map<String, List<KylinConfigInfo>> modelMaps = new HashedMap();
                    List<KylinConfigInfo> configInfos = new ArrayList<>();
                    configInfos.add(kylinConfigInfo);
                    modelMaps.put(kylinConfigInfo.getModelCode(), configInfos);
                    optionMaps.put(kylinConfigInfo.getProjectCode(), modelMaps);
                }
            }
            if (MapUtils.isNotEmpty(optionMaps)) {
                for (Map.Entry<String, Map<String, List<KylinConfigInfo>>> entry : optionMaps.entrySet()) {
                    String project = entry.getKey();
                    KylinLoadSelectOption parent = new KylinLoadSelectOption();
                    List<KylinLoadSelectOption> childs = new ArrayList<>();
                    parent.setValue(project);
                    Map<String, List<KylinConfigInfo>> modelMap = entry.getValue();
                    for (Map.Entry<String, List<KylinConfigInfo>> entryModel : modelMap.entrySet()) {
                        String model = entryModel.getKey();
                        KylinLoadSelectOption child = new KylinLoadSelectOption();
                        child.setValue(model);
                        List<KylinLoadSelectOption> grandsons = new ArrayList<>();
                        List<KylinConfigInfo> configInfos = entryModel.getValue();
                        for (KylinConfigInfo configInfo : configInfos) {
                            parent.setLabel(configInfo.getProjectName());
                            child.setLabel(configInfo.getModelName());
                            KylinLoadSelectOption grandson = new KylinLoadSelectOption();
                            grandson.setValue(configInfo.getCubeCode());
                            grandson.setLabel(configInfo.getCubeName());
                            grandsons.add(grandson);
                        }
                        child.setChildren(grandsons);
                        childs.add(child);
                    }
                    parent.setChildren(childs);
                    kylinLoadSelectOptions.add(parent);
                }
            }
        }

        return kylinLoadSelectOptions;
    }

    private List<KylinLoadProject> organizeKylinLoadProject(List<KylinConfigInfo> kylinConfigInfos) {
        List<KylinLoadProject> kylinLoadProjects = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(kylinConfigInfos)) {
            for (KylinConfigInfo kylinConfigInfo : kylinConfigInfos) {
                KylinLoadProject project = new KylinLoadProject();
                project.setProjectCode(kylinConfigInfo.getProjectCode());
                project.setProjectName(kylinConfigInfo.getProjectName());
                List<KylinLoadModel> kylinLoadModels = new ArrayList<>();
                List<KylinLoadCube> kylinLoadCubes = new ArrayList<>();
                KylinLoadModel model = new KylinLoadModel();
                model.setModelCode(kylinConfigInfo.getModelCode());
                model.setModelName(kylinConfigInfo.getModelName());
                KylinLoadCube cube = new KylinLoadCube();
                BeanMapper.copy(kylinConfigInfo, cube);
                kylinLoadCubes.add(cube);
                model.setKylinLoadCubes(kylinLoadCubes);
                kylinLoadModels.add(model);
                project.setKylinLoadModels(kylinLoadModels);

                kylinLoadProjects.add(project);
            }
        }

        return kylinLoadProjects;
    }

    private List<KylinConfigInfo> getOrganizePendingInfo(List<CubeDescReturnData> cubeDescDatas) {
        List<KylinConfigInfo> kylinPendingConfigInfos = new ArrayList<>();
        for (CubeDescReturnData returnData : cubeDescDatas) {
            KylinConfigInfo configInfo = new KylinConfigInfo();
            configInfo.setUuid(Identities.uuid2());
            configInfo.setModelCode(returnData.getModelName());
            configInfo.setCubeCode(returnData.getName());
            // 主表信息
            KylinPrimaryTable kylinPrimaryTable = new KylinPrimaryTable();
            List<CubeDescDimension> dimensions = returnData.getCubeDescDimension();
            // 主表维度信息
            List<KylinColumnInfo> dimensionColumnInfos = new ArrayList<>();
            // 伪表信息
            List<KylinForeignTable> kylinForeignTables = new ArrayList<>();
            // 伪表map
            Map<String, List<KylinColumnInfo>> kylinForeignTableMaps = new HashedMap();
            // 关联信息map
            Map<String, List<KylinColumnInfo>> kylinJoinInfoMaps = new HashedMap();
            if (CollectionUtils.isNotEmpty(dimensions)) {
                for (CubeDescDimension cubeDescDimension : dimensions) {
                    if (StringUtil.isBlank(kylinPrimaryTable.getTableCode()) && cubeDescDimension.getDerived() == null) {
                        kylinPrimaryTable.setTableCode(cubeDescDimension.getTable());
                    }
                    if (cubeDescDimension.getDerived() == null) {
                        // 主表维度信息
                        KylinColumnInfo columnInfo = new KylinColumnInfo();
                        columnInfo.setCode(cubeDescDimension.getName());
                        dimensionColumnInfos.add(columnInfo);
                    } else {
                        // 伪表信息
                        if (kylinForeignTableMaps.containsKey(cubeDescDimension.getTable())) {
                            List<KylinColumnInfo> kylinColumnInfos = kylinForeignTableMaps.get(cubeDescDimension.getTable());
                            KylinColumnInfo kylinColumnInfo = new KylinColumnInfo();
                            kylinColumnInfo.setCode(cubeDescDimension.getName());
                            kylinColumnInfos.add(kylinColumnInfo);
                        } else {
                            List<KylinColumnInfo> kylinColumnInfos = new ArrayList<>();
                            KylinColumnInfo kylinColumnInfo = new KylinColumnInfo();
                            kylinColumnInfo.setCode(cubeDescDimension.getName());
                            kylinColumnInfos.add(kylinColumnInfo);
                            kylinForeignTableMaps.put(cubeDescDimension.getTable(), kylinColumnInfos);
                        }
                    }
                    // 关联信息处理
                    this.buildJoinInfo(kylinJoinInfoMaps, cubeDescDimension);
                }
                kylinPrimaryTable.setDimensionColumnInfos(dimensionColumnInfos);
                if (MapUtils.isNotEmpty(kylinForeignTableMaps)) {
                    for (Map.Entry<String, List<KylinColumnInfo>> entry : kylinForeignTableMaps.entrySet()) {
                        KylinForeignTable kylinForeignTable = new KylinForeignTable();
                        kylinForeignTable.setTableCode(entry.getKey());
                        kylinForeignTable.setKylinColumnInfos(entry.getValue());
                        kylinForeignTables.add(kylinForeignTable);
                    }
                    configInfo.setKylinForeignTables(kylinForeignTables);
                }
            }
            List<CubeDescMeasure> cubeDescMeasures = returnData.getCubeDescMeasure();
            // 主表度量信息
            List<KylinColumnInfo> measureColumnInfos = new ArrayList<>();
            // 度量信息
            List<KylinLoadMeasure> kylinLoadMeasures = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(cubeDescMeasures)) {
                for (CubeDescMeasure measure : cubeDescMeasures) {
                    CubeDescMeasureFunction function = measure.getCubeDescMeasureFunction();
                    if (function != null) {
                        CubeDescMeasureFunctionParameter parameter = function.getCubeDescMeasureFunctionParameter();
                        if (!"1".equals(parameter.getValue())) {
                            String replaceInfo = kylinPrimaryTable.getTableCode() + ".";
                            // parameter.getValue() => RPT_DEALER_RENEW_SUCCESS_M_KYLIN.COMPULSORY_NUM (主表.字段)
                            String measureCode = parameter.getValue().replace(replaceInfo, "");
                            KylinColumnInfo columnInfo = new KylinColumnInfo();
                            columnInfo.setCode(measureCode);
                            measureColumnInfos.add(columnInfo);
                            KylinLoadMeasure kylinLoadMeasure = new KylinLoadMeasure();
                            kylinLoadMeasure.setCode(function.getExpression());
                            kylinLoadMeasure.setColumnCode(measureCode);
                            kylinLoadMeasure.setColumnName(measureMap.get(measureCode));
                            kylinLoadMeasures.add(kylinLoadMeasure);
                        }
                    }
                }
                kylinPrimaryTable.setMeasureColumnInfos(measureColumnInfos);
                configInfo.setKylinLoadMeasures(kylinLoadMeasures);
            }
            configInfo.setKylinPrimaryTable(kylinPrimaryTable);
            // 处理关联信息
            List<KylinJoinInfo> kylinJoinInfos = new ArrayList<>();
            // 用户自定义条件
            List<KylinLimitInfo> kylinLimitInfos = new ArrayList<>();
            if (MapUtils.isNotEmpty(kylinJoinInfoMaps)) {
                for (Map.Entry<String, List<KylinColumnInfo>> entry : kylinJoinInfoMaps.entrySet()) {
                    // 关联信息
                    if (!entry.getKey().equals(configInfo.getKylinPrimaryTable().getTableCode())) {
                        KylinJoinInfo kylinJoinInfo = new KylinJoinInfo();
                        kylinJoinInfo.setTableCode(entry.getKey());
                        // 构建关联信息
                        String associationFeild = this.buildAssociationInfo(kylinJoinInfo, entry.getValue(), kylinJoinInfoMaps.get(configInfo.getKylinPrimaryTable().getTableCode()));
                        StringBuffer sb = new StringBuffer();
                        sb.append(configInfo.getKylinPrimaryTable().getTableCode()).append(".").append(associationFeild)
                                .append("=").append(entry.getKey()).append(".").append(associationFeild);
                        kylinJoinInfo.setAssociationSqlInfo(sb.toString());
                        kylinJoinInfos.add(kylinJoinInfo);
                    }
                    // 自定义信息
                    if (CollectionUtils.isNotEmpty(entry.getValue())) {
                        for (KylinColumnInfo columnInfo : entry.getValue()) {
                            KylinLimitInfo kylinLimitInfo = new KylinLimitInfo();
                            kylinLimitInfo.setTableCode(entry.getKey());
                            kylinLimitInfo.setColumnCode(columnInfo.getCode());
                            kylinLimitInfos.add(kylinLimitInfo);
                        }
                    }
                }
                configInfo.setKylinJoinInfos(kylinJoinInfos);
                configInfo.setKylinLimitInfos(kylinLimitInfos);
            }
            kylinPendingConfigInfos.add(configInfo);
        }

        return kylinPendingConfigInfos;
    }

    private String buildAssociationInfo(KylinJoinInfo kylinJoinInfo, List<KylinColumnInfo> foreignTableInfo, List<KylinColumnInfo> primaryTableInfo) {
        String associationFeild = null;
        // 默认foreignTableInfo伪表两个字段,一个关联一个展示字段
        // 且默认关联字段name相同,若不同则前端页面进行调整
        if (CollectionUtils.isNotEmpty(foreignTableInfo)) {
            for (KylinColumnInfo foreign : foreignTableInfo) {
                for (KylinColumnInfo primary : primaryTableInfo) {
                    if (foreign.getCode().equals(primary.getCode())) {
                        kylinJoinInfo.setAssociationColumn(foreign.getCode());
                        associationFeild = foreign.getCode();
                    }
                }
            }
            for (KylinColumnInfo foreign : foreignTableInfo) {
                if (!foreign.getCode().equals(kylinJoinInfo.getAssociationColumn())) {
                    kylinJoinInfo.setShowColumn(foreign.getCode());
                }
            }
        }

        return associationFeild;
    }

    private void buildJoinInfo(Map<String, List<KylinColumnInfo>> kylinJoinInfoMaps, CubeDescDimension cubeDescDimension) {
        if (kylinJoinInfoMaps.containsKey(cubeDescDimension.getTable())) {
            List<KylinColumnInfo> kylinColumnInfos = kylinJoinInfoMaps.get(cubeDescDimension.getTable());
            KylinColumnInfo kylinColumnInfo = new KylinColumnInfo();
            kylinColumnInfo.setCode(cubeDescDimension.getName());
            kylinColumnInfos.add(kylinColumnInfo);
        } else {
            List<KylinColumnInfo> kylinColumnInfos = new ArrayList<>();
            KylinColumnInfo kylinColumnInfo = new KylinColumnInfo();
            kylinColumnInfo.setCode(cubeDescDimension.getName());
            kylinColumnInfos.add(kylinColumnInfo);
            kylinJoinInfoMaps.put(cubeDescDimension.getTable(), kylinColumnInfos);
        }
    }

    private Map<String, String> getCubeCodes(List<KylinLoadSelectOption> selectOptions) {
        Map<String, String> cubes = new HashedMap();
        if (CollectionUtils.isEmpty(selectOptions)) {
            return cubes;
        }
        for (KylinLoadSelectOption selectOption : selectOptions) {
            if (CollectionUtils.isNotEmpty(selectOption.getChildren())) {
                for (KylinLoadSelectOption child : selectOption.getChildren()) {
                    if (CollectionUtils.isNotEmpty(child.getChildren())) {
                        for (KylinLoadSelectOption grandson : child.getChildren()) {
                            if (StringUtil.isNotBlank(grandson.getValue())) {
                                cubes.put(grandson.getValue(), grandson.getValue());
                            }
                        }
                    }
                }
            }
        }

        return cubes;
    }

    private List<KylinConfigInfo> buildKylinConfigInfo(List<KylinLoadProject> kylinLoadProjects) {
        List<KylinConfigInfo> kylinConfigInfos = new ArrayList<>();
        if (CollectionUtils.isEmpty(kylinLoadProjects)) {
            return kylinConfigInfos;
        }
        for (KylinLoadProject project : kylinLoadProjects) {
            if (CollectionUtils.isNotEmpty(project.getKylinLoadModels())) {
                for (KylinLoadModel model : project.getKylinLoadModels()) {
                    if (CollectionUtils.isNotEmpty(model.getKylinLoadCubes())) {
                        for (KylinLoadCube cube : model.getKylinLoadCubes()) {
                            KylinConfigInfo kylinConfigInfo = new KylinConfigInfo();
                            BeanMapper.copy(cube, kylinConfigInfo);
                            kylinConfigInfo.setUuid(cube.getUuid());
                            kylinConfigInfo.setProjectCode(project.getProjectCode());
                            kylinConfigInfo.setProjectName(project.getProjectName());
                            kylinConfigInfo.setModelCode(model.getModelCode());
                            kylinConfigInfo.setModelName(model.getModelName());
                            kylinConfigInfos.add(kylinConfigInfo);
                        }
                    }
                }
            }
        }

        return kylinConfigInfos;
    }

    private TableShowInfo translateTableHeadColumn(QueryReturnData returnData, KylinQueryData queryData) {
        TableShowInfo tableShowInfo = new TableShowInfo();
        if (null != returnData) {
            tableShowInfo.setTableBody(returnData.getResults());
            List<String> tableHead = new ArrayList<>();
            List<QueryColumnMeta> returnColumn = returnData.getQueryColumnMeta();
            if (CollectionUtils.isNotEmpty(returnColumn)) {
                // 字段集合
                List<KylinColumnInfo> translateColumnNames = this.getTranslateColumnName(queryData);
                // 前端传来的维度  聚合函数&&列名
                List<String> measures = new ArrayList<>();
                // 先取出度量信息(按顺序)
                if (CollectionUtils.isNotEmpty(queryData.getMeasures())) {
                    if (CollectionUtils.isNotEmpty(translateColumnNames)) {
                        for (String measure : queryData.getMeasures()) {
                            for (KylinColumnInfo columnInfo : translateColumnNames) {
                                if (measure.equals(columnInfo.getCode())) {
                                    measures.add(columnInfo.getName());
                                }
                            }
                        }
                    }
                }
                for (QueryColumnMeta columnMeta : returnColumn) {
                    String name = columnMeta.getName();
                    if (CollectionUtils.isNotEmpty(translateColumnNames)) {
                        for (KylinColumnInfo columnInfo : translateColumnNames) {
                            if (name.contains("EXPR")) {
                                int index = Integer.valueOf(name.substring(5, name.length())).intValue();
                                tableHead.add(measures.get(index));
                                break;
                            } else {
                                if (columnInfo.getCode().equals(name)) {
                                    tableHead.add(columnInfo.getName());
                                    break;
                                }
                            }

                        }
                    }
                }
            }
            tableShowInfo.setTableHead(tableHead);
        }

        return tableShowInfo;
    }

    private List<KylinColumnInfo> getTranslateColumnName(KylinQueryData queryData) {
        String configName = queryData.getProject() + "_" + queryData.getModel() + "_" + queryData.getCube();
        Object result = getCache().get(configName, Object.class);
        if (result != null) {
            return (List<KylinColumnInfo>) result;
        }
        List<KylinColumnInfo> translateColumnNames = new ArrayList<>();
        // 主表字段
        KylinPrimaryTable primaryTable = queryData.getKylinPrimaryTable();
        List<KylinColumnInfo> columnInfos = primaryTable.getDimensionColumnInfos();
        if (CollectionUtils.isNotEmpty(columnInfos)) {
            translateColumnNames.addAll(columnInfos);
        }
        // 伪表字段
        List<KylinForeignTable> foreignTables = queryData.getKylinForeignTables();
        if (CollectionUtils.isNotEmpty(foreignTables)) {
            for (KylinForeignTable foreignTable : foreignTables) {
                if (CollectionUtils.isNotEmpty(foreignTable.getKylinColumnInfos())) {
                    translateColumnNames.addAll(foreignTable.getKylinColumnInfos());
                }
            }
        }
        // 度量
        List<KylinLoadMeasure> measures = queryData.getKylinLoadMeasures();
        if (CollectionUtils.isNotEmpty(measures)) {
            for (KylinLoadMeasure measure : measures) {
                KylinColumnInfo columnInfo = new KylinColumnInfo();
                columnInfo.setCode(measure.getCode() + "&&" + measure.getColumnCode());
                columnInfo.setName(measure.getName());
                translateColumnNames.add(columnInfo);
            }
        }

        getCache().put(configName, translateColumnNames);

        return translateColumnNames;
    }

    private void executeDownloadTaks() {
        // 1.查询待处理任务
        List<DownloadInfo> downloadInfos = (List<DownloadInfo>) this.getInfoByName("downloadInfo");
        if (CollectionUtils.isEmpty(downloadInfos)) {
            return;
        }
        for (DownloadInfo downloadInfo : downloadInfos) {
            if (DonwloadFileStatus.generating.equals(downloadInfo.getFileStatus())) {
                downloadInfo.getKylinQueryData().setLimit("100000000");
                // 拼接sql
                String sql = this.organizeKylinQuerySql(downloadInfo.getKylinQueryData());
                // 调用kylin接口
                QueryReturnData returnData = this.getKylinQuery(sql, downloadInfo.getKylinQueryData());
                // 翻译表头
                TableShowInfo tableShowInfo = this.translateTableHeadColumn(returnData, downloadInfo.getKylinQueryData());
                // 生成excle
                this.generateExcel(tableShowInfo, downloadInfo);
                // 置状态--已生成excel
                downloadInfo.setFileStatus(DonwloadFileStatus.generated);
                downloadInfo.setFileStatusName(DonwloadFileStatus.generated.getValue());
                downloadInfo.setUpdateTime(DateUtil.dateToString(new Date(), DateStyle.HH_MM_SS));
                this.setInfoByName("downloadInfo", downloadInfo);
            }
        }
    }

    private void generateExcel(TableShowInfo tableShowInfo, DownloadInfo downloadInfo) {
        FileOutputStream out = null;
        try {
            // keep 100 rows in memory, exceeding rows will be flushed to disk
            Workbook wb = new SXSSFWorkbook(100);
            Sheet sh = wb.createSheet();
            Row titleRow = sh.createRow(0);
            List<String> queryColumnNames = tableShowInfo.getTableHead();
            for (int i= 0; i<queryColumnNames.size();i++) {
                String queryColumnname = queryColumnNames.get(i);
                Cell cell = titleRow.createCell(i);
                cell.setCellValue(queryColumnNames.get(i));
            }
            List<List<String>> results = tableShowInfo.getTableBody();
            for(int rownum = 0; rownum < results.size(); rownum++){
                Row row = sh.createRow(rownum + 1);
                List<String> columns = results.get(rownum);
                for(int cellnum = 0; cellnum < columns.size(); cellnum++){
                    Cell cell = row.createCell(cellnum);
                    cell.setCellValue(columns.get(cellnum));
                }
            }
            String path = this.getClass().getResource("/").getPath()
                    + "download/"
                    + DateUtil.dateToString(new Date(), DateStyle.YYYYMMDD) + "/";
            File directory = new File(path);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            String fileName = downloadInfo.getFileCode();
            out = new FileOutputStream(path + fileName + ".xlsx");
            wb.write(out);
            out.close();
        } catch (Exception e) {
            ExceptionUtil.handleException(e);
        }finally {
            if(out != null) {
                IOUtils.closeQuietly(out);
            }
        }
    }

    private QueryReturnData getKylinQuery(String sql, KylinQueryData queryData) {
        KylinQueryInfo kylinQueryInfo = new KylinQueryInfo();
        BeanMapper.copy(queryData, kylinQueryInfo);
        kylinQueryInfo.setSql(sql);
        return kylinBaseService.getKylinQuery(kylinQueryInfo);
    }

    private String organizeKylinQuerySql(KylinQueryData queryData) {
        StringBuffer sql = new StringBuffer("select ");
        // 度量
        if (CollectionUtils.isNotEmpty(queryData.getMeasures())) {
            int index = 1;
            for (String queryMeasure : queryData.getMeasures()) {
                // SUM&&COMPULSORY_NUM
                sql.append(queryMeasure.replace("&&", "(" + queryData.getPrimaryTable() + ".")).append(")");
                if (CollectionUtils.isEmpty(queryData.getDimensions())) {
                    if (index == queryData.getMeasures().size()) {
                        sql.append(" ");
                    } else {
                        sql.append(",");
                    }
                } else {
                    sql.append(",");
                }
                index++;
            }
        }
        // 维度
        StringBuffer groupDimensionInfo = new StringBuffer();
        if (CollectionUtils.isNotEmpty(queryData.getDimensions())) {
            int index = 1;
            for (String queryDimension : queryData.getDimensions()) {
                // queryDimension = factTable + . + column
                // 判断该维度字段是否有关联伪表字段,存在则查询一并展示,不存在则只展示自身
                if (CollectionUtils.isNotEmpty(queryData.getJoinInfos())) {
                    boolean matchFlag = false;
                    for (KylinJoinInfo joinInfo : queryData.getJoinInfos()) {
                        if (queryDimension.equals(joinInfo.getAssociationColumn())) {
                            matchFlag = true;
                            sql.append(queryData.getPrimaryTable()).append(".").append(queryDimension);
                            sql.append(",").append(joinInfo.getTableCode()).append(".").append(joinInfo.getShowColumn());
                            if (CollectionUtils.isNotEmpty(queryData.getDimensions()) && CollectionUtils.isNotEmpty(queryData.getMeasures())) {
                                groupDimensionInfo.append(queryData.getPrimaryTable()).append(".").append(queryDimension);
                                groupDimensionInfo.append(",").append(joinInfo.getTableCode()).append(".").append(joinInfo.getShowColumn());
                            }
                        }
                    }
                    if (!matchFlag) {
                        sql.append(queryData.getPrimaryTable()).append(".").append(queryDimension);
                        if (CollectionUtils.isNotEmpty(queryData.getDimensions()) && CollectionUtils.isNotEmpty(queryData.getMeasures())) {
                            groupDimensionInfo.append(queryData.getPrimaryTable()).append(".").append(queryDimension);
                        }
                    }
                } else {
                    sql.append(queryData.getPrimaryTable()).append(".").append(queryDimension);
                    if (CollectionUtils.isNotEmpty(queryData.getDimensions()) && CollectionUtils.isNotEmpty(queryData.getMeasures())) {
                        groupDimensionInfo.append(queryData.getPrimaryTable()).append(".").append(queryDimension);
                    }
                }
                if (index == queryData.getDimensions().size()) {
                    sql.append(" ");
                    if (CollectionUtils.isNotEmpty(queryData.getDimensions()) && CollectionUtils.isNotEmpty(queryData.getMeasures())) {
                        groupDimensionInfo.append(" ");
                    }
                } else {
                    sql.append(",");
                    if (CollectionUtils.isNotEmpty(queryData.getDimensions()) && CollectionUtils.isNotEmpty(queryData.getMeasures())) {
                        groupDimensionInfo.append(",");
                    }
                }
                index++;
            }
        }
        sql.append(" from ");
        sql.append(queryData.getPrimaryTable()).append(" ").append(queryData.getPrimaryTable());
        // 判断是否关联伪表 ==> 主表的维度字段若在joinInfos中匹配对应字段,则需要匹配
        // 主表 join 伪表 on 条件
        if (CollectionUtils.isNotEmpty(queryData.getJoinInfos()) && CollectionUtils.isNotEmpty(queryData.getDimensions())) {
            for (String dimension : queryData.getDimensions()) {
                for (KylinJoinInfo joinInfo : queryData.getJoinInfos()) {
                    if (dimension.equals(joinInfo.getAssociationColumn())) {
                        sql.append(" join ").append(joinInfo.getTableCode()).append(" ").append(joinInfo.getTableCode());
                        sql.append(" on ").append(joinInfo.getAssociationSqlInfo());
                    }
                }
            }
        }
        // 判断是否添加限制条件
        if (CollectionUtils.isNotEmpty(queryData.getDate()) || CollectionUtils.isNotEmpty(queryData.getLimits())) {
            sql.append(" where 1=1 ");
        }
        // 时间限制条件
        if (CollectionUtils.isNotEmpty(queryData.getDate())) {
            if (CollectionUtils.isNotEmpty(queryData.getDimensions())) {
                List<String> timeLimits =new ArrayList<>(Arrays.asList("BUS_TIME","BUS_DAY","BUS_WEEK","BUS_MONTH","BUS_YEAR","CREATE_TIME","UPDATE_TIME","ETL_TIME"));
                String timeCondition = null;
                for (String time : timeLimits) {
                    for (String dimension : queryData.getDimensions()) {
                        if (time.equalsIgnoreCase(dimension)) {
                            timeCondition = time;
                            break;
                        }
                    }
                    if (StringUtil.isNotBlank(timeCondition)) {
                        break;
                    }
                }
                if (StringUtil.isNotBlank(timeCondition)) {
                    // YYYY_MM_DD
                    if (timeCondition.equals("BUS_TIME") || timeCondition.equals("BUS_DAY") || timeCondition.equals("BUS_WEEK")
                            || timeCondition.equals("BUS_MONTH") || timeCondition.equals("BUS_YEAR")) {
                        sql.append("and ").append(queryData.getPrimaryTable()).append(".").append(timeCondition)
                                .append(">=").append("'").append(DateUtil.dateToString(DateUtil.stringToDate(queryData.getDate().get(0), DateStyle.YYYY_MM_DD), DateStyle.YYYY_MM_DD)).append("'");
                        sql.append(" and ").append(queryData.getPrimaryTable()).append(".").append(timeCondition)
                                .append("<=").append("'").append(DateUtil.dateToString(DateUtil.stringToDate(queryData.getDate().get(1), DateStyle.YYYY_MM_DD), DateStyle.YYYY_MM_DD)).append("'").append(" ");
                    } else {
                        // YYYY_MM_DD_HH_MM_SS
                        sql.append("and ").append(queryData.getPrimaryTable()).append(".").append(timeCondition)
                                .append(">=").append("'").append(DateUtil.dateToString(DateUtil.stringToDate(queryData.getDate().get(0), DateStyle.YYYY_MM_DD_HH_MM_SS), DateStyle.YYYY_MM_DD)).append("'");
                        sql.append(" and ").append(queryData.getPrimaryTable()).append(".").append(timeCondition)
                                .append("<=").append("'").append(DateUtil.dateToString(DateUtil.stringToDate(queryData.getDate().get(1), DateStyle.YYYY_MM_DD_HH_MM_SS), DateStyle.YYYY_MM_DD)).append("'").append(" ");
                    }
                }
            }
        }
        // 用户自定义条件
        if (CollectionUtils.isNotEmpty(queryData.getLimits())) {
            for (Map<String, String> limit : queryData.getLimits()) {
                // 表&&字段
                sql.append(" and ").append(limit.get("code").replace("&&", ".")).append(" like '%").append(limit.get("value")).append("%'");
            }
        }

        // 维度不为空,度量不为空,则需要group by
        if (CollectionUtils.isNotEmpty(queryData.getDimensions()) && CollectionUtils.isNotEmpty(queryData.getMeasures())) {
            sql.append(" group by  ");
            sql.append(groupDimensionInfo);
        }

        return sql.toString();
    }

    private synchronized Object getInfoByName(String configName) {
        Object result = getCache().get(configName, Object.class);
        if (result != null) {
            return result;
        }
        switch (configName) {
            case "loadData":
                Map<String, Object> modelMap = hbaseService.getKylinLoadInfo();
                getCache().put(configName, modelMap);
                if (null == modelMap.get(KYLIN_LOAD_SELECT_DATA)) {
                    this.resetCache("loadData");
                }
                result = modelMap;
                break;
        }

        return result;
    }

    private synchronized Object setInfoByName(String configName, Object obj) {
        Object result = getCache().get(configName, Object.class);
        Object returnInfo = null;
        switch (configName) {
            case "downloadInfo":
                List<DownloadInfo> downloadInfos = null;
                if (null == result) {
                    downloadInfos = new ArrayList<>();
                } else {
                    downloadInfos = (List<DownloadInfo>) result;
                }
                if (CollectionUtils.isNotEmpty(downloadInfos)) {
                    DownloadInfo loadInfo = (DownloadInfo) obj;
                    Iterator<DownloadInfo> it = downloadInfos.iterator();
                    boolean hasFlag = false;
                    while(it.hasNext()){
                        DownloadInfo info = it.next();
                        if (info.getFileCode().equals(loadInfo.getFileCode())) {
                            // 更新文件状态
                            info.setFileStatus(loadInfo.getFileStatus());
                            // 已下载
                            if(info.getFileStatus().equals(DonwloadFileStatus.downLoad)){
                                it.remove();
                            }
                            hasFlag = true;
                        }
                    }
                    if (!hasFlag && (!DonwloadFileStatus.downLoad.equals(loadInfo.getFileStatus()))) {
                        downloadInfos.add((DownloadInfo) obj);
                    }
                } else {
                    downloadInfos.add((DownloadInfo) obj);
                }
                getCache().put(configName, downloadInfos);
                returnInfo = downloadInfos;
                break;
            case "loadData":
                this.resetCache("loadData");
                Map<String, Object> modelMap = (Map<String, Object>)obj;
                getCache().put(configName, modelMap);
                returnInfo = modelMap;
                break;
        }

        return returnInfo;
    }

    private void getSelectLoadData(Map<String, Object> modelMap) {
        // 1.接口获取cube信息
        List<CubesReturnData> cubes = kylinBaseService.getKylinCubes();
        // 2.获取cubeNames集合
        List<String> cubeNames = this.getCubeNames(cubes);
        // 3.获取cube详细内容(描述中的json自定义数据)
        List<KylinCubeInfo> cubeInfos = this.getCubeDetailInfo(cubeNames);
        // 4.转换为前端selectOption数据
        this.convertFrontSelectOptionInfo(cubeInfos, modelMap);
    }

    private void convertFrontSelectOptionInfo(List<KylinCubeInfo> cubeInfos, Map<String, Object> modelMap) {
        if (CollectionUtils.isNotEmpty(cubeInfos)) {
            // 1.projectCode modelCode cubeCode 树形关系整理
            Map<String, Set<String>> projectModels = new HashedMap();
            Map<String, Set<String>> modelCubes = new HashedMap();
            for (KylinCubeInfo cubeInfo : cubeInfos) {
                String projectCode = cubeInfo.getProjectCode();
                String modelCode = cubeInfo.getModelCode();
                String cubeCode = cubeInfo.getCubeCode();
                if (StringUtil.isBlank(projectCode) || StringUtil.isBlank(modelCode) || StringUtil.isBlank(cubeCode)) {
                    continue;
                }
                projectCode = projectCode.trim();
                modelCode = modelCode.trim();
                cubeCode = cubeCode.trim();
                if (projectModels.containsKey(projectCode)) {
                    Set<String> set = projectModels.get(projectCode);
                    set.add(modelCode);
                } else {
                    Set<String> set = new  HashSet();
                    set.add(modelCode);
                    projectModels.put(projectCode, set);
                }
                if (modelCubes.containsKey(modelCode)) {
                    Set<String> set = modelCubes.get(modelCode);
                    set.add(cubeCode);
                } else {
                    Set<String> set = new  HashSet();
                    set.add(cubeCode);
                    modelCubes.put(modelCode, set);
                }
            }
            if(MapUtils.isEmpty(projectModels) || MapUtils.isEmpty(modelCubes)) {
                return;
            }
            List<KylinLoadProject> kylinLoadDatas = new ArrayList<>();
            List<KylinLoadSelectOption> loadSelectInfoOnes = new ArrayList<>();
            for (Map.Entry<String, Set<String>> projectModel : projectModels.entrySet()) {
                KylinLoadProject kylinLoadData = new KylinLoadProject();
                kylinLoadData.setProjectCode(projectModel.getKey());
                KylinLoadSelectOption loadSelectInfoOne = new KylinLoadSelectOption();
                // 第一层projectCode
                loadSelectInfoOne.setValue(projectModel.getKey());
                List<KylinLoadSelectOption> loadSelectInfoTwos = new ArrayList<>();
                Set<String> modelCodes = projectModel.getValue();
                List<KylinLoadModel> kylinLoadModels = new ArrayList<>();
                if (CollectionUtils.isNotEmpty(modelCodes)) {
                    for (String modelCode : modelCodes) {
                        KylinLoadModel kylinLoadModel = new KylinLoadModel();
                        KylinLoadSelectOption loadSelectInfoTwo = new KylinLoadSelectOption();
                        for (Map.Entry<String, Set<String>> modelCube : modelCubes.entrySet()) {
                            if (modelCode.equals(modelCube.getKey())) {
                                kylinLoadModel.setModelCode(modelCode);
                                loadSelectInfoTwo.setValue(modelCode);
                                List<KylinLoadSelectOption> loadSelectInfoThrees = new ArrayList<>();
                                List<KylinLoadCube> kylinLoadCubes = new ArrayList<>();
                                for (String cube : modelCube.getValue()) {
                                    for (KylinCubeInfo cubeInfo : cubeInfos) {
                                        if (cube.equals(cubeInfo.getCubeCode())) {
                                            KylinLoadCube kylinLoadCube = new KylinLoadCube();
                                            KylinLoadSelectOption loadSelectInfoThree = new KylinLoadSelectOption();
                                            if (StringUtil.isBlank(kylinLoadData.getProjectName())) {
                                                kylinLoadData.setProjectName(cubeInfo.getProjectName());
                                            }
                                            if (StringUtil.isBlank(kylinLoadModel.getModelName())) {
                                                kylinLoadModel.setModelName(cubeInfo.getModelName());
                                            }
                                            if (StringUtil.isBlank(loadSelectInfoOne.getLabel())) {
                                                loadSelectInfoOne.setLabel(cubeInfo.getProjectName());
                                            }
                                            if (StringUtil.isBlank(loadSelectInfoTwo.getLabel())) {
                                                loadSelectInfoTwo.setLabel(cubeInfo.getProjectName());
                                            }
                                            kylinLoadCube.setCubeCode(cubeInfo.getCubeCode());
                                            kylinLoadCube.setCubeName(cubeInfo.getCubeName());
                                            if (CollectionUtils.isNotEmpty(cubeInfo.getCubeDescMeasure())) {
                                                List<KylinLoadMeasure> kylinLoadMeasures = new ArrayList<>();
                                                for (CubeDescMeasure measure : cubeInfo.getCubeDescMeasure()) {
                                                    if ("COUNT".equals(measure.getCubeDescMeasureFunction().getExpression())) {
                                                        continue;
                                                    }
                                                    KylinLoadMeasure kylinLoadMeasure = new KylinLoadMeasure();
                                                    kylinLoadMeasure.setCode(measure.getCubeDescMeasureFunction().getExpression());
                                                    kylinLoadMeasure.setName(measureMap.get(measure.getCubeDescMeasureFunction().getExpression()));
                                                    kylinLoadMeasure.setColumnCode(measure.getCubeDescMeasureFunction().getCubeDescMeasureFunctionParameter().getValue());
                                                    if (null != cubeInfo.getKylinPrimaryTables()) {
                                                        KylinPrimaryTable kylinPrimaryTable = cubeInfo.getKylinPrimaryTables();
                                                        String table = kylinPrimaryTable.getTableCode();
                                                        List<KylinColumnInfo> kyliyColumnInfos = kylinPrimaryTable.getMeasureColumnInfos();
                                                        if (CollectionUtils.isNotEmpty(kyliyColumnInfos)) {
                                                            for (KylinColumnInfo kyliyColumnInfo : kyliyColumnInfos) {
                                                                if (kylinLoadMeasure.getColumnCode().equals(table + "." + kyliyColumnInfo.getCode())) {
                                                                    kylinLoadMeasure.setColumnName(kyliyColumnInfo.getName());
                                                                }
                                                            }
                                                        }

                                                    }
                                                    kylinLoadMeasures.add(kylinLoadMeasure);
                                                }
                                                kylinLoadCube.setKylinLoadMeasures(kylinLoadMeasures);
                                            }
                                            kylinLoadCube.setKylinPrimaryTable(cubeInfo.getKylinPrimaryTables());
                                            kylinLoadCube.setKylinForeignTables(cubeInfo.getKylinForeignTables());
                                            kylinLoadCubes.add(kylinLoadCube);
                                            loadSelectInfoThree.setLabel(cubeInfo.getCubeName());
                                            loadSelectInfoThree.setValue(cubeInfo.getCubeCode());
                                            loadSelectInfoThrees.add(loadSelectInfoThree);
                                        }
                                    }
                                }
                                //loadSelectInfoTwo.setLoadSelectInfo(loadSelectInfoThrees);
                                kylinLoadModel.setKylinLoadCubes(kylinLoadCubes);
                            }
                        }
                        loadSelectInfoTwos.add(loadSelectInfoTwo);
                        kylinLoadModels.add(kylinLoadModel);
                    }
                }
                kylinLoadData.setKylinLoadModels(kylinLoadModels);
                kylinLoadDatas.add(kylinLoadData);
                //loadSelectInfoOne.setLoadSelectInfos(loadSelectInfoTwos);
                loadSelectInfoOnes.add(loadSelectInfoOne);
                //kylinLoadSelectData.setLoadSelectInfo(loadSelectInfoOnes);
            }
            //modelMap.put("kylinLoadSelectData", kylinLoadSelectData);
            modelMap.put("kylinLoadDatas", kylinLoadDatas);
        }
    }

    private List<KylinCubeInfo> getCubeDetailInfo(List<String> cubeNames) {
        List<KylinCubeInfo> cubeInfos = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(cubeNames)) {
            for (String cubeName : cubeNames) {
                List<CubeDescReturnData> cubeDescs = kylinBaseService.getKylinCubeDesc(cubeName);
                if (CollectionUtils.isNotEmpty(cubeDescs)) {
                    for (CubeDescReturnData cubeDesc : cubeDescs) {
                        String description = cubeDesc.getDescription();
                        if (StringUtil.isNotBlank(description)) {
                            KylinCubeInfo cubeInfo = JsonMapper.nonEmptyMapper().fromJson(description, KylinCubeInfo.class);
                            if (null != cubeInfo) {
                                cubeInfo.setCubeDescMeasure(cubeDesc.getCubeDescMeasure());
                                cubeInfos.add(cubeInfo);
                            }
                        }
                    }
                }
            }
        }

        return cubeInfos;
    }

    private List<String> getCubeNames(List<CubesReturnData> cubes) {
        List<String> cubeNames = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(cubes)) {
            for (CubesReturnData cube : cubes) {
                if ("READY".equals(cube.getStatus())) {
                    if (StringUtil.isNotBlank(cube.getName())) {
                        cubeNames.add(cube.getName().trim());
                    }
                }
            }
        }

        return cubeNames;
    }

    private synchronized void resetCache() {
        getCache().clear();
    }

    private synchronized void resetCache(String configName) {
        getCache().evict(configName);
    }

    private Cache getCache() {
        return cacheManager.getCache(CACHE_NAME);
    }
    private static final Map<String, String> measureMap = new HashMap<String, String>();
    static {
        measureMap.put("RAW","raw");
        measureMap.put("COUNT","计数");
        measureMap.put("MAX","最大");
        measureMap.put("MIN","最小");
        measureMap.put("SUM","合计");
        measureMap.put("COUNT_DISTINCT","去重计数");
        measureMap.put("TOP_N","TOP_N");
        measureMap.put("EXTENDED_COLUMN","extended_column");
        measureMap.put("PERCENTILE","百分位数");
    }

}