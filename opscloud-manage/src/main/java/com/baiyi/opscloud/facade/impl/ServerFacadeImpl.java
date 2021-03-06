package com.baiyi.opscloud.facade.impl;

import com.baiyi.opscloud.builder.ServerBuilder;
import com.baiyi.opscloud.cloud.server.ICloudServer;
import com.baiyi.opscloud.cloud.server.factory.CloudServerFactory;
import com.baiyi.opscloud.common.base.BusinessType;
import com.baiyi.opscloud.common.base.CloudServerKey;
import com.baiyi.opscloud.common.base.CloudServerStatus;
import com.baiyi.opscloud.common.util.BeanCopierUtils;
import com.baiyi.opscloud.common.util.RegexUtils;
import com.baiyi.opscloud.decorator.ServerDecorator;
import com.baiyi.opscloud.domain.BusinessWrapper;
import com.baiyi.opscloud.domain.DataTable;
import com.baiyi.opscloud.domain.ErrorEnum;
import com.baiyi.opscloud.domain.generator.opscloud.*;
import com.baiyi.opscloud.domain.param.server.ServerParam;
import com.baiyi.opscloud.domain.vo.server.ServerAttributeVO;
import com.baiyi.opscloud.domain.vo.server.ServerVO;
import com.baiyi.opscloud.facade.CloudServerFacade;
import com.baiyi.opscloud.facade.ServerCacheFacade;
import com.baiyi.opscloud.facade.ServerFacade;
import com.baiyi.opscloud.facade.TagFacade;
import com.baiyi.opscloud.server.ServerCenter;
import com.baiyi.opscloud.server.facade.ServerAttributeFacade;
import com.baiyi.opscloud.service.env.OcEnvService;
import com.baiyi.opscloud.service.server.OcServerGroupService;
import com.baiyi.opscloud.service.server.OcServerService;
import com.google.common.base.Joiner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author baiyi
 * @Date 2020/2/21 4:47 下午
 * @Version 1.0
 */
@Service
public class ServerFacadeImpl implements ServerFacade {

    @Resource
    private OcServerService ocServerService;

    @Resource
    private ServerDecorator serverDecorator;

    @Resource
    private OcServerGroupService ocServerGroupService;

    @Resource
    private OcEnvService ocEnvService;

    @Resource
    private ServerAttributeFacade serverAttributeFacade;

    @Resource
    private CloudServerFacade cloudServerFacade;

    @Resource
    private TagFacade tagFacade;

    @Resource
    private ServerCenter serverCenter;

    @Resource
    private ServerCacheFacade serverCacheFacade;

    @Override
    public DataTable<ServerVO.Server> queryServerPage(ServerParam.PageQuery pageQuery) {
        DataTable<OcServer> table = ocServerService.queryOcServerByParam(pageQuery);
        return toServerDataTable(table);
    }

    @Override
    public DataTable<ServerVO.Server> fuzzyQueryServerPage(ServerParam.PageQuery pageQuery) {
        DataTable<OcServer> table = ocServerService.fuzzyQueryOcServerByParam(pageQuery);
        return toServerDataTable(table);
    }

    @Override
    public BusinessWrapper<Boolean> queryServerByServerGroup(ServerParam.QueryByServerGroup queryByServerGroup) {
        Integer serverGroupId = queryByServerGroup.getServerGroupId();
        if (serverGroupId == null || serverGroupId <= 0) {
            if (!StringUtils.isEmpty(queryByServerGroup.getServerGroupName())) {
                OcServerGroup ocServerGroup = ocServerGroupService.queryOcServerGroupByName(queryByServerGroup.getServerGroupName());
                if (ocServerGroup != null)
                    serverGroupId = ocServerGroup.getId();
            }
        }
        if (serverGroupId == null) return new BusinessWrapper<>(ErrorEnum.SERVERGROUP_NOT_EXIST);
        List<ServerVO.Server> servers = ocServerService.queryOcServerByServerGroupId(serverGroupId).stream().map(e ->
                serverDecorator.decorator(BeanCopierUtils.copyProperties(e, ServerVO.Server.class))
        ).collect(Collectors.toList());
        return new BusinessWrapper(servers);
    }

    @Override
    public List<ServerAttributeVO.ServerAttribute> queryServerAttribute(int id) {
        OcServer ocServer = ocServerService.queryOcServerById(id);
        return serverAttributeFacade.queryServerAttribute(ocServer);
    }

    @Override
    public BusinessWrapper<Boolean> saveServerAttribute(ServerAttributeVO.ServerAttribute serverAttribute) {
        return serverAttributeFacade.saveServerAttribute(serverAttribute);
    }

    private DataTable<ServerVO.Server> toServerDataTable(DataTable<OcServer> table) {
        List<ServerVO.Server> page = BeanCopierUtils.copyListProperties(table.getData(), ServerVO.Server.class);
        DataTable<ServerVO.Server> dataTable = new DataTable<>(page.stream().map(e -> serverDecorator.decorator(e)).collect(Collectors.toList()), table.getTotalNum());
        return dataTable;
    }

    @Override
    public BusinessWrapper<Boolean> addServer(ServerVO.Server server) {
        if (StringUtils.isEmpty(server.getPrivateIp()))
            return new BusinessWrapper<>(ErrorEnum.SERVER_PRIVATE_IP_IS_NAME);
        if (ocServerService.queryOcServerByPrivateIp(server.getPrivateIp()) != null)
            return new BusinessWrapper<>(ErrorEnum.SERVER_PRIVATE_IP_CONFLICT);
        if (StringUtils.isEmpty(server.getName()) || !RegexUtils.isServerNameRule(server.getName()))
            return new BusinessWrapper<>(ErrorEnum.SERVER_NAME_NON_COMPLIANCE_WITH_RULES);
        if (server.getServerGroupId() == null)
            return new BusinessWrapper<>(ErrorEnum.SERVER_GROUP_NOT_SELECTED);
        if (ocServerGroupService.queryOcServerGroupById(server.getServerGroupId()) == null)
            return new BusinessWrapper<>(ErrorEnum.SERVERGROUP_NOT_EXIST);
        // 校验SN
        Integer serialNumber = 0;
        try {
            serialNumber = Integer.valueOf(server.getSerialNumber());
        } catch (Exception e) {
            // 序号错误
        }
        if (serialNumber == 0) {
            serialNumber = ocServerService.queryOcServerMaxSerialNumber(server.getServerGroupId(), server.getEnvType());
            server.setSerialNumber(serialNumber + 1);
        }
        OcServer ocServer = ServerBuilder.build(server);
        ocServerService.addOcServer(ocServer);
        // 清理缓存
        serverCacheFacade.evictServerCache(ocServer);
        // 云主机绑定
        if (server.getCloudServerId() != null && server.getCloudServerId() > 0)
            cloudServerFacade.updateCloudServerStatus(server.getCloudServerId(), ocServer.getId(), CloudServerStatus.REGISTER.getStatus());
        // 服务器工厂
        serverCenter.create(ocServer);
        return BusinessWrapper.SUCCESS;
    }

    @Override
    public BusinessWrapper<Boolean> updateServer(ServerVO.Server server) {
        // 校验服务器名称
        if (!RegexUtils.isServerNameRule(server.getName()))
            return new BusinessWrapper<>(ErrorEnum.SERVER_NAME_NON_COMPLIANCE_WITH_RULES);
        // 校验服务器组是否配置
        if (server.getServerGroupId() == null
                || server.getServerGroupId() <= 0
                || ocServerGroupService.queryOcServerGroupById(server.getServerGroupId()) == null) {
            return new BusinessWrapper<>(ErrorEnum.SERVER_GROUP_NOT_SELECTED);
        }
        OcServer ocServer = BeanCopierUtils.copyProperties(server, OcServer.class);
        ocServerService.updateOcServer(ocServer);
        // 清理缓存
        serverCacheFacade.evictServerCache(ocServer);
        // 服务器工厂
        serverCenter.update(ocServer);
        return BusinessWrapper.SUCCESS;
    }

    @Override
    public BusinessWrapper<Boolean> deleteServerById(int id) {
        OcServer ocServer = ocServerService.queryOcServerById(id);
        if (ocServer == null)
            return new BusinessWrapper<>(ErrorEnum.SERVER_NOT_EXIST);
        // 清理缓存
        serverCacheFacade.evictServerCache(ocServer);
        // 删除server的Tag
        List<OcBusinessTag> ocBusinessTagList = tagFacade.queryOcBusinessTagByBusinessTypeAndBusinessId(BusinessType.SERVER.getType(), id);
        if (!ocBusinessTagList.isEmpty())
            tagFacade.deleteTagByList(ocBusinessTagList);
        // 删除server的属性
        List<OcServerAttribute> serverAttributeList = serverAttributeFacade.queryServerAttributeById(id);
        if (!serverAttributeList.isEmpty())
            serverAttributeFacade.deleteServerAttributeByList(serverAttributeList);
        ocServerService.deleteOcServerById(id);
        // 服务器工厂
        serverCenter.remove(ocServer);
        // 设置云服务器离线
        ICloudServer iCloudServer = CloudServerFactory.getCloudServerByKey(CloudServerKey.getKey(ocServer.getServerType()));
        iCloudServer.offline(id);
        return BusinessWrapper.SUCCESS;
    }

    /**
     * 带列号
     *
     * @return
     */
    @Override
    public String acqServerName(OcServer ocServer) {
        OcEnv ocEnv = ocEnvService.queryOcEnvByType(ocServer.getEnvType());
        if (ocEnv == null || ocEnv.getEnvName().equals("prod")) {
            return Joiner.on("-").join(ocServer.getName(), ocServer.getSerialNumber());
        } else {
            return Joiner.on("-").join(ocServer.getName(), ocEnv.getEnvName(), ocServer.getSerialNumber());
        }
    }

    /**
     * 不带列号
     *
     * @return
     */
    @Override
    public String acqHostname(OcServer ocServer) {
        OcEnv ocEnv = ocEnvService.queryOcEnvByType(ocServer.getEnvType());
        if (ocEnv == null || ocEnv.getEnvName().equals("prod")) {
            return ocServer.getName();
        } else {
            return Joiner.on("-").join(ocServer.getName(), ocEnv.getEnvName());
        }
    }
}
