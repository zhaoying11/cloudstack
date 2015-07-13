/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.globo.globonetwork.cloudstack.manager;

import java.math.BigInteger;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.network.dao.LoadBalancerPortMapDao;
import com.cloud.network.dao.LoadBalancerPortMapVO;
import com.globo.globonetwork.cloudstack.api.ListGloboNetworkLBCacheGroupsCmd;
import com.globo.globonetwork.cloudstack.commands.ListGloboNetworkLBCacheGroupsCommand;
import com.globo.globonetwork.cloudstack.response.GloboNetworkCacheGroupsResponse;
import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.region.PortableIp;
import org.apache.cloudstack.region.PortableIpDao;
import org.apache.cloudstack.region.PortableIpRange;
import org.apache.cloudstack.region.PortableIpRangeDao;
import org.apache.cloudstack.region.PortableIpRangeVO;
import org.apache.cloudstack.region.PortableIpVO;
import org.apache.log4j.Logger;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.CloudException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.Host.Type;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.UserIpv6AddressVO;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.UserIpv6AddressDao;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.lb.LoadBalancingRule.LbHealthCheckPolicy;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancer;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.org.Grouping;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.resource.ResourceManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.DomainManager;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Journal;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.globo.globodns.cloudstack.element.GloboDnsElementService;
import com.globo.globonetwork.client.model.Vlan;
import com.globo.globonetwork.cloudstack.GloboNetworkEnvironmentVO;
import com.globo.globonetwork.cloudstack.GloboNetworkIpDetailVO;
import com.globo.globonetwork.cloudstack.GloboNetworkLoadBalancerEnvironment;
import com.globo.globonetwork.cloudstack.GloboNetworkNetworkVO;
import com.globo.globonetwork.cloudstack.GloboNetworkVipAccVO;
import com.globo.globonetwork.cloudstack.api.AcquireNewIpForLbInGloboNetworkCmd;
import com.globo.globonetwork.cloudstack.api.AddGloboNetworkEnvironmentCmd;
import com.globo.globonetwork.cloudstack.api.AddGloboNetworkHostCmd;
import com.globo.globonetwork.cloudstack.api.AddGloboNetworkLBEnvironmentCmd;
import com.globo.globonetwork.cloudstack.api.AddGloboNetworkRealToVipCmd;
import com.globo.globonetwork.cloudstack.api.AddGloboNetworkVipToAccountCmd;
import com.globo.globonetwork.cloudstack.api.AddNetworkViaGloboNetworkCmd;
import com.globo.globonetwork.cloudstack.api.DelGloboNetworkRealFromVipCmd;
import com.globo.globonetwork.cloudstack.api.DeleteNetworkInGloboNetworkCmd;
import com.globo.globonetwork.cloudstack.api.DisassociateIpAddrFromGloboNetworkCmd;
import com.globo.globonetwork.cloudstack.api.GenerateUrlForEditingVipCmd;
import com.globo.globonetwork.cloudstack.api.ImportGloboNetworkLoadBalancerCmd;
import com.globo.globonetwork.cloudstack.api.ListAllEnvironmentsFromGloboNetworkCmd;
import com.globo.globonetwork.cloudstack.api.ListGloboNetworkCapabilitiesCmd;
import com.globo.globonetwork.cloudstack.api.ListGloboNetworkEnvironmentsCmd;
import com.globo.globonetwork.cloudstack.api.ListGloboNetworkLBEnvironmentsCmd;
import com.globo.globonetwork.cloudstack.api.ListGloboNetworkRealsCmd;
import com.globo.globonetwork.cloudstack.api.ListGloboNetworkVipsCmd;
import com.globo.globonetwork.cloudstack.api.RemoveGloboNetworkEnvironmentCmd;
import com.globo.globonetwork.cloudstack.api.RemoveGloboNetworkLBEnvironmentCmd;
import com.globo.globonetwork.cloudstack.api.RemoveGloboNetworkVipCmd;
import com.globo.globonetwork.cloudstack.commands.AcquireNewIpForLbCommand;
import com.globo.globonetwork.cloudstack.commands.ActivateNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.AddAndEnableRealInGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.AddOrRemoveVipInGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.CreateNewVlanInGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.DeallocateVlanFromGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.DisableAndRemoveRealInGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.GenerateUrlForEditingVipCommand;
import com.globo.globonetwork.cloudstack.commands.GetVipInfoFromGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.GetVlanInfoFromGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.GloboNetworkErrorAnswer;
import com.globo.globonetwork.cloudstack.commands.ListAllEnvironmentsFromGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.RegisterEquipmentAndIpInGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.ReleaseIpFromGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.RemoveNetworkInGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.RemoveVipFromGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.commands.UnregisterEquipmentAndIpInGloboNetworkCommand;
import com.globo.globonetwork.cloudstack.dao.GloboNetworkEnvironmentDao;
import com.globo.globonetwork.cloudstack.dao.GloboNetworkIpDetailDao;
import com.globo.globonetwork.cloudstack.dao.GloboNetworkLoadBalancerEnvironmentDAO;
import com.globo.globonetwork.cloudstack.dao.GloboNetworkNetworkDao;
import com.globo.globonetwork.cloudstack.dao.GloboNetworkVipAccDao;
import com.globo.globonetwork.cloudstack.exception.CloudstackGloboNetworkException;
import com.globo.globonetwork.cloudstack.resource.GloboNetworkResource;
import com.globo.globonetwork.cloudstack.response.GloboNetworkAllEnvironmentResponse;
import com.globo.globonetwork.cloudstack.response.GloboNetworkAllEnvironmentResponse.Environment;
import com.globo.globonetwork.cloudstack.response.GloboNetworkAndIPResponse;
import com.globo.globonetwork.cloudstack.response.GloboNetworkVipResponse;
import com.globo.globonetwork.cloudstack.response.GloboNetworkVipResponse.Real;
import com.globo.globonetwork.cloudstack.response.GloboNetworkVlanResponse;

@Component
@Local({GloboNetworkService.class, PluggableService.class})
public class GloboNetworkManager implements GloboNetworkService, PluggableService, Configurable {

    private static final Logger s_logger = Logger.getLogger(GloboNetworkManager.class);

    static final int NUMBER_OF_RESERVED_IPS_FROM_START = 1;
    static final int NUMBER_OF_RESERVED_IPS_BEFORE_END = 2;
    static final int NUMBER_OF_RESERVED_IPS_FOR_LB_FROM_START = 1;
    static final int NUMBER_OF_RESERVED_IPS_FOR_LB_BEFORE_END = 2;

    private static final ConfigKey<String> GloboNetworkConnectionTimeout = new ConfigKey<String>("Network", String.class, "globonetwork.connectiontimeout", "120000",
            "GloboNetwork connection timeout (in milliseconds)", true, ConfigKey.Scope.Global);
    private static final ConfigKey<String> GloboNetworkReadTimeout = new ConfigKey<String>("Network", String.class, "globonetwork.readtimeout", "120000",
            "GloboNetwork read timeout (in milliseconds)", true, ConfigKey.Scope.Global);
    private static final ConfigKey<String> GloboNetworkNumberOfRetries = new ConfigKey<String>("Network", String.class, "globonetwork.numberofretries", "0",
            "GloboNetwork number of retries", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkVmEquipmentGroup = new ConfigKey<Long>("Network", Long.class, "globonetwork.vm.equipmentgroup", "",
            "Equipment group to be used when registering a VM NIC in GloboNetwork", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmUser = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.user", "83",
            "GloboNetwork model id to be used for User VMs", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmDomainRouter = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.domain.router", "84",
            "GloboNetwork model id to be used for Domain Router VMs", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmConsoleProxy = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.console.proxy", "85",
            "GloboNetwork model id to be used for Console Proxy VMs", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmSecondaryStorageVm = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.secondary.storage", "86",
            "GloboNetwork model id to be used for Secondary Storage VMs", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmElasticIpVm = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.elastic.ip", "87",
            "GloboNetwork model id to be used for Elastic IP VMs", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmElasticLoadBalancerVm = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.elastic.load.balancer", "88",
            "GloboNetwork model id to be used for Elastic Load Balancer VMs", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmInternalLoadBalancerVm = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.internal.load.balancer",
            "89", "GloboNetwork model id to be used for Internal Load Balancer VMs", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Long> GloboNetworkModelVmUserBareMetal = new ConfigKey<Long>("Network", Long.class, "globonetwork.model.vm.user.bare.metal", "90",
            "GloboNetwork model id to be used for User Bare Metal", true, ConfigKey.Scope.Global);
    private static final ConfigKey<String> GloboNetworkDomainPattern = new ConfigKey<String>("Network", String.class, "globonetwork.domain.pattern", "",
            "Domain pattern to ensure in all networks created with GloboNetwork", true, ConfigKey.Scope.Global);
    private static final ConfigKey<String> GloboNetworkDomainSuffix = new ConfigKey<String>("Network", String.class, "globonetwork.domain.suffix", "",
            "Domain suffix to ensure in all networks created with GloboNetwork (empty you are free to create in any domain)", true, ConfigKey.Scope.Global);
    private static final ConfigKey<String> GloboNetworkVIPServerUrl = new ConfigKey<String>("Network", String.class, "globonetwork.vip.server.url", "",
            "Server URL to generate a new VIP request", true, ConfigKey.Scope.Global);
    private static final ConfigKey<Integer> GloboNetworkLBLockTimeout = new ConfigKey<Integer>("Network", Integer.class, "globonetwork.loadbalancer.lock.timeout", "60",
            "GloboNetwork Loadbalancer lock timeout (in seconds). This option avoid concurrent operations.", true, ConfigKey.Scope.Global);
    private static final ConfigKey<String> GloboNetworkLBAllowedSuffixes = new ConfigKey<String>("Network", String.class, "globonetwork.lb.allowed.suffixes", "",
            "Allowed domain suffixes for load balancers created with GloboNetwork. List of domain names separated by commas", true, ConfigKey.Scope.Global);

    // DAOs
    @Inject
    DomainDao _domainDao;
    @Inject
    HostDao _hostDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    HostPodDao _hostPodDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    UserDao _userDao;
    @Inject
    NetworkDao _ntwkDao;
    @Inject
    NicDao _nicDao;
    @Inject
    NetworkServiceMapDao _ntwkSrvcDao;
    @Inject
    GloboNetworkNetworkDao _globoNetworkNetworkDao;
    @Inject
    GloboNetworkEnvironmentDao _globoNetworkEnvironmentDao;
    @Inject
    GloboNetworkVipAccDao _globoNetworkVipAccDao;
    @Inject
    GloboNetworkLoadBalancerEnvironmentDAO _globoNetworkLBEnvironmentDao;
    @Inject
    VMInstanceDao _vmDao;
    @Inject
    IPAddressDao _ipAddrDao;
    @Inject
    PortableIpRangeDao _portableIpRangeDao;
    @Inject
    GloboNetworkIpDetailDao _globoNetworkIpDetailDao;
    @Inject
    PortableIpDao _portableIpDao;
    @Inject
    UserIpv6AddressDao _ipv6AddrDao;
    @Inject
    LoadBalancerPortMapDao _lbPortMapDao;

    // Managers
    @Inject
    NetworkModel _networkManager;
    @Inject
    AgentManager _agentMgr;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    DomainManager _domainMgr;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    AccountManager _accountMgr;
    @Inject
    ProjectManager _projectMgr;
    @Inject
    NetworkService _ntwSvc;
    @Inject
    IpAddressManager _ipAddrMgr;
    @Inject
    LoadBalancingRulesManager _lbMgr;
    @Inject
    LoadBalancingRulesService _lbService;
    @Inject
    GloboDnsElementService _globoDnsService;

    @Override
    public boolean canEnable(Long physicalNetworkId) {
        if (physicalNetworkId == null) {
            return false;
        }
        List<GloboNetworkEnvironmentVO> list = _globoNetworkEnvironmentDao.listByPhysicalNetworkId(physicalNetworkId);
        if (list.isEmpty()) {
            throw new CloudRuntimeException("Before enabling GloboNetwork you must add an environment to your physical interface");
        }
        return true;
    }

    @Override
    @DB
    public Network createNetwork(String name, String displayText, Long zoneId, Long networkOfferingId, Long napiEnvironmentId, String networkDomain, ACLType aclType,
            String accountName, Long projectId, Long domainId, Boolean subdomainAccess, Boolean displayNetwork, Long aclId, Boolean isIpv6) throws ResourceAllocationException,
            ResourceUnavailableException, ConcurrentOperationException, InsufficientCapacityException {

        Account caller = CallContext.current().getCallingAccount();

        if ((accountName != null && domainId != null) || projectId != null) {
            _accountMgr.finalizeOwner(caller, accountName, domainId, projectId);
        }

        DataCenter zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Specified zone id was not found");
        }

        Long physicalNetworkId = null;
        if (napiEnvironmentId != null) {
            GloboNetworkEnvironmentVO napiEnvironmentVO = null;
            for (PhysicalNetwork pNtwk : _physicalNetworkDao.listByZone(zoneId)) {
                napiEnvironmentVO = _globoNetworkEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(pNtwk.getId(), napiEnvironmentId);
                if (napiEnvironmentVO != null) {
                    break;
                }
            }
            if (napiEnvironmentVO == null) {
                throw new InvalidParameterValueException("Unable to find a relationship between GloboNetwork environment and physical network");
            }
            physicalNetworkId = napiEnvironmentVO.getPhysicalNetworkId();
        } else {
            throw new InvalidParameterValueException("GloboNetwork enviromentId was not found");
        }

        // to avoid create a new vlan in GloboNetworkAPI, check first if networkDomain is valid
        if (isSupportedCustomNetworkDomain()) {
            String domainSuffix = GloboNetworkDomainSuffix.value();
            if (StringUtils.isNotBlank(domainSuffix) && !networkDomain.endsWith(domainSuffix)) {
                throw new InvalidParameterValueException("Network domain need ends with " + domainSuffix);
            }
        }

        Answer answer = createNewVlan(zoneId, name, displayText, napiEnvironmentId, isIpv6);

        GloboNetworkVlanResponse response = (GloboNetworkVlanResponse)answer;
        Long napiVlanId = response.getVlanId();

        Network network = null;

        try {
            network = this.createNetworkFromGloboNetworkVlan(napiVlanId, napiEnvironmentId, zoneId, networkOfferingId, physicalNetworkId, networkDomain, aclType, accountName,
                    projectId, domainId, subdomainAccess, displayNetwork, aclId);
        } catch (Exception e) {
            // Exception when creating network in Cloudstack. Roll back transaction in GloboNetwork
            s_logger.error("Reverting network creation in GloboNetwork due to error creating network", e);
            this.deallocateVlanFromGloboNetwork(zoneId, napiVlanId);

            throw new ResourceAllocationException(e.getLocalizedMessage(), ResourceType.network);
        }

        // if the network offering has persistent set to true, implement the
        // network
        NetworkOfferingVO ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        if (ntwkOff.getIsPersistent()) {
            try {
                if (network.getState() == Network.State.Setup) {
                    s_logger.debug("Network id=" + network.getId() + " is already provisioned");
                    return network;
                }
                DeployDestination dest = new DeployDestination(zone, null, null, null);
                UserVO callerUser = _userDao.findById(CallContext.current().getCallingUserId());
                Journal journal = new Journal.LogJournal("Implementing " + network, s_logger);
                ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), journal, callerUser, caller);
                s_logger.debug("Implementing network " + network + " as a part of network provision for persistent network");
                @SuppressWarnings("unchecked")
                Pair<NetworkGuru, NetworkVO> implementedNetwork = (Pair<NetworkGuru, NetworkVO>)_networkMgr.implementNetwork(network.getId(), dest, context);
                if (implementedNetwork.first() == null) {
                    s_logger.warn("Failed to provision the network " + network);
                }
                network = implementedNetwork.second();
            } catch (ResourceUnavailableException ex) {
                s_logger.warn("Failed to implement persistent guest network " + network + "due to ", ex);
                CloudRuntimeException e = new CloudRuntimeException("Failed to implement persistent guest network");
                e.addProxyObject(network.getUuid(), "networkId");
                throw e;
            }
        }

        return network;
    }

    private Network createNetworkFromGloboNetworkVlan(final Long vlanId, final Long napiEnvironmentId, Long zoneId, final Long networkOfferingId, final Long physicalNetworkId,
            final String networkDomain, final ACLType aclType, String accountName, Long projectId, final Long domainId, final Boolean subdomainAccess,
            final Boolean displayNetwork, Long aclId) throws CloudException, ResourceUnavailableException, ResourceAllocationException, ConcurrentOperationException,
            InsufficientCapacityException {

        final Account caller = CallContext.current().getCallingAccount();

        final Account owner;
        if ((accountName != null && domainId != null) || projectId != null) {
            owner = _accountMgr.finalizeOwner(caller, accountName, domainId, projectId);
        } else {
            owner = caller;
        }

        // Only domain and account ACL types are supported in Action.
        if (aclType == null || !(aclType == ACLType.Domain || aclType == ACLType.Account)) {
            throw new InvalidParameterValueException("AclType should be " + ACLType.Domain + " or " + ACLType.Account + " for network of type " + Network.GuestType.Shared);
        }

        final boolean isDomainSpecific = true;

        // Validate network offering
        final NetworkOfferingVO ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        if (ntwkOff == null || ntwkOff.isSystemOnly()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find network offering by specified id");
            if (ntwkOff != null) {
                ex.addProxyObject(ntwkOff.getUuid(), "networkOfferingId");
            }
            throw ex;
        } else if (GuestType.Shared != ntwkOff.getGuestType()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("GloboNetwork can handle only network offering with guest type shared");
            if (ntwkOff != null) {
                ex.addProxyObject(ntwkOff.getUuid(), "networkOfferingId");
            }
            throw ex;
        }
        // validate physical network and zone
        // Check if physical network exists
        final PhysicalNetwork pNtwk;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }
        } else {
            throw new InvalidParameterValueException("invalid physicalNetworkId " + physicalNetworkId);
        }

        if (zoneId == null) {
            zoneId = pNtwk.getDataCenterId();
        }

        final DataCenter zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Specified zone id was not found");
        }

        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isAdmin(caller.getAccountId())) {
            // See DataCenterVO.java
            PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation since specified Zone is currently disabled");
            ex.addProxyObject(zone.getUuid(), "zoneId");
            throw ex;
        }

        if (domainId != null) {
            DomainVO domain = _domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Unable to find domain by specified id");
            }
            _accountMgr.checkAccess(caller, domain);
        }

        if (_configMgr.isOfferingForVpc(ntwkOff)) {
            throw new InvalidParameterValueException("Network offering can't be used for VPC networks");
        }

        // CallContext.register(CallContext.current().getCallingUserId(), owner.getAccountId());

        /////// GloboNetwork specific code ///////

        // Get VlanInfo from GloboNetwork
        GetVlanInfoFromGloboNetworkCommand cmd = new GetVlanInfoFromGloboNetworkCommand();
        cmd.setVlanId(vlanId);

        final GloboNetworkVlanResponse vlanResponse = (GloboNetworkVlanResponse)callCommand(cmd, zoneId);

        String networkAddress = vlanResponse.getNetworkAddress();
        final String netmask = vlanResponse.getMask();
        // FIXME This does not work with IPv6!
        final String cidr = networkAddress + "/" + vlanResponse.getBlock();

        String gatewayStr = null;
        String startIPStr = null;
        String endIPStr = null;
        final Long vlanNum = vlanResponse.getVlanNum();

        String startIPv6Str = null;
        String endIPv6Str = null;
        String ip6GatewayStr = null;

        if (vlanResponse.isv6()) {
            com.googlecode.ipv6.IPv6Network ipv6Network = com.googlecode.ipv6.IPv6Network.fromString(cidr);
            com.googlecode.ipv6.IPv6Address ipv6Start = ipv6Network.getFirst().add(1);
            ip6GatewayStr = ipv6Start.toString();
            startIPv6Str = ipv6Start.add(NUMBER_OF_RESERVED_IPS_FROM_START).toString();

            com.googlecode.ipv6.IPv6Address ipv6End = ipv6Network.getLast();
            endIPv6Str = ipv6End.subtract(NUMBER_OF_RESERVED_IPS_BEFORE_END).toString();
        } else {
            String ranges[] = NetUtils.ipAndNetMaskToRange(networkAddress, netmask);
            gatewayStr = ranges[0];
            startIPStr = NetUtils.long2Ip(NetUtils.ip2Long(ranges[0]) + NUMBER_OF_RESERVED_IPS_FROM_START);
            endIPStr = NetUtils.long2Ip(NetUtils.ip2Long(ranges[1]) - NUMBER_OF_RESERVED_IPS_BEFORE_END);
        }

        final String startIP = startIPStr;
        final String endIP = endIPStr;
        final String gateway = gatewayStr;

        final String startIPv6 = startIPv6Str;
        final String endIPv6 = endIPv6Str;
        final String ip6Gateway = ip6GatewayStr;
        final String ip6Cidr = cidr;

        s_logger.info("Creating network with name " + vlanResponse.getVlanName() + " (" + vlanResponse.getVlanId() + "), network " + networkAddress + " gateway " + gateway
                + " startIp " + startIP + " endIp " + endIP + " cidr " + cidr + " startIPv6 " + startIPv6 + " end IPv6 " + endIPv6 + " IPv6 gateway " + ip6Gateway);
        /////// End of GloboNetwork specific code ///////

        Network network = Transaction.execute(new TransactionCallbackWithException<Network, CloudException>() {

            @Override
            public Network doInTransaction(TransactionStatus status) throws CloudException {
                Boolean newSubdomainAccess = subdomainAccess;
                Long sharedDomainId = null;
                if (isDomainSpecific) {
                    if (domainId != null) {
                        sharedDomainId = domainId;
                    } else {
                        sharedDomainId = owner.getDomainId();
                        newSubdomainAccess = true;
                    }
                }

                String newNetworkDomain = networkDomain;
                if (!isSupportedCustomNetworkDomain()) {
                    // overwrite networkDomain
                    newNetworkDomain = generateNetworkDomain(zone, vlanResponse);
                }

                Network network = _networkMgr.createGuestNetwork(networkOfferingId.longValue(), vlanResponse.getVlanName(), vlanResponse.getVlanDescription(), gateway, cidr,
                        String.valueOf(vlanResponse.getVlanNum()), newNetworkDomain, owner, sharedDomainId, pNtwk, zone.getId(), aclType, newSubdomainAccess, null, // vpcId,
                        ip6Gateway, ip6Cidr, displayNetwork, null // isolatedPvlan
                        );

                // Save relashionship with napi and network
                GloboNetworkNetworkVO napiNetworkVO = new GloboNetworkNetworkVO(vlanId, network.getId(), napiEnvironmentId);
                napiNetworkVO = _globoNetworkNetworkDao.persist(napiNetworkVO);

                // if (caller.getType() == Account.ACCOUNT_TYPE_ADMIN || caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
                // Create vlan ip range
                _configMgr.createVlanAndPublicIpRange(pNtwk.getDataCenterId(), network.getId(), physicalNetworkId, false, (Long)null, startIP, endIP, gateway, netmask,
                        vlanNum.toString(), null, startIPv6, endIPv6, ip6Gateway, ip6Cidr);
                // }
                return network;
            }
        });
        return network;
    }

    protected String generateNetworkDomain(DataCenter zone, GloboNetworkVlanResponse vlan) {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("zone", zone);
        context.put("vlan", vlan);

        String newNetworkDomain = formatter(GloboNetworkDomainPattern.value(), context);
        newNetworkDomain += GloboNetworkDomainSuffix.value().startsWith(".") ? GloboNetworkDomainSuffix.value() : "." + GloboNetworkDomainSuffix.value();
        return newNetworkDomain;
    }

    /**
     * Replace variables in a string template: #{obj.property}.
     * @see http://docs.spring.io/spring/docs/current/spring-framework-reference/html/expressions.html
     * @param template
     * @param context
     * @return
     */
    protected String formatter(String template, Map<String, Object> context) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext(context);
        evalContext.addPropertyAccessor(new MapAccessor());

        ExpressionParser parser = new SpelExpressionParser();
        Expression exp = parser.parseExpression(template, new TemplateParserContext());
        String formatted = exp.getValue(evalContext, String.class);
        return formatted;
    }

    protected GloboNetworkVlanResponse createNewVlan(Long zoneId, String name, String description, Long globoNetworkEnvironmentId, Boolean isIpv6) {

        CreateNewVlanInGloboNetworkCommand cmd = new CreateNewVlanInGloboNetworkCommand();
        cmd.setVlanName(name);
        cmd.setVlanDescription(description);
        cmd.setGloboNetworkEnvironmentId(globoNetworkEnvironmentId);
        cmd.setIsIpv6(isIpv6);

        return (GloboNetworkVlanResponse)callCommand(cmd, zoneId);
    }

    private Answer callCommand(Command cmd, Long zoneId) {
        return callCommand(cmd, zoneId, true);
    }

    private Answer callCommand(Command cmd, Long zoneId, boolean raisesExceptionWhenNoAnswer) {
        HostVO napiHost = getGloboNetworkHost(zoneId);
        if (napiHost == null) {
            throw new CloudstackGloboNetworkException("Could not find the GloboNetwork resource");
        }

        Answer answer = _agentMgr.easySend(napiHost.getId(), cmd);
        if (answer == null || !answer.getResult()) {

            if (answer instanceof GloboNetworkErrorAnswer) {
                GloboNetworkErrorAnswer napiAnswer = (GloboNetworkErrorAnswer)answer;
                throw new CloudstackGloboNetworkException(napiAnswer.getNapiCode(), napiAnswer.getNapiDescription());
            } else {
                if (raisesExceptionWhenNoAnswer) {
                    String msg = "Error executing command " + cmd + ". Maybe GloboNetwork Host is down";
                    msg = answer == null ? msg : answer.getDetails();
                    throw new CloudRuntimeException(msg);
                }
            }
        }

        return answer;
    }

    private HostVO getGloboNetworkHost(Long zoneId) {
        return _hostDao.findByTypeNameAndZoneId(zoneId, Provider.GloboNetwork.getName(), Type.L2Networking);
    }

    @Override
    public Network validateNic(NicProfile nicProfile, VirtualMachineProfile vm, Network network) throws InsufficientVirtualNetworkCapcityException,
            InsufficientAddressCapacityException {

        GetVlanInfoFromGloboNetworkCommand cmd = new GetVlanInfoFromGloboNetworkCommand();
        cmd.setVlanId(getGloboNetworkVlanId(network.getId()));

        String msg = "Unable to get VLAN " + getGloboNetworkVlanId(network.getId()) + " info from GloboNetwork";
        Answer answer = this.callCommand(cmd, network.getDataCenterId());
        if (answer == null || !answer.getResult()) {
            msg = answer == null ? msg : answer.getDetails();
            throw new InsufficientVirtualNetworkCapcityException(msg, Nic.class, nicProfile.getId());
        }

        GloboNetworkVlanResponse vlanResponse = (GloboNetworkVlanResponse) answer;

        String networkAddress = vlanResponse.getNetworkAddress();
        String netmask = vlanResponse.getMask();

        BigInteger ip;
        BigInteger ipRangeStart;
        BigInteger ipRangeEnd;

        if (vlanResponse.isv6()) {
            com.googlecode.ipv6.IPv6Address ipv6 = com.googlecode.ipv6.IPv6Address.fromString(nicProfile.getIp6Address());
            try {
                ip = new BigInteger(ipv6.toInetAddress().getAddress());

                com.googlecode.ipv6.IPv6Network ipv6Network = com.googlecode.ipv6.IPv6Network.fromAddressAndMask(
                        com.googlecode.ipv6.IPv6Address.fromString(networkAddress),
                        com.googlecode.ipv6.IPv6NetworkMask.fromAddress(com.googlecode.ipv6.IPv6Address.fromString(netmask)));

                com.googlecode.ipv6.IPv6Address ipv6Start = ipv6Network.getFirst();
                ipRangeStart = new BigInteger(ipv6Start.toInetAddress().getAddress());

                com.googlecode.ipv6.IPv6Address ipv6End = ipv6Network.getLast();
                ipRangeEnd = new BigInteger(ipv6End.toInetAddress().getAddress());
            } catch (UnknownHostException ex) {
                throw new InvalidParameterValueException("Nic IP " + nicProfile.getIp6Address() + " is invalid");
            }
        } else {
            ip = BigInteger.valueOf(NetUtils.ip2Long(nicProfile.getIp4Address()));
            String ranges[] = NetUtils.ipAndNetMaskToRange(networkAddress, netmask);
            ipRangeStart = BigInteger.valueOf(NetUtils.ip2Long(ranges[0]));
            ipRangeEnd = BigInteger.valueOf(NetUtils.ip2Long(ranges[1]));
        }

        if (!(ip.compareTo(ipRangeStart) >= 0  && ip.compareTo(ipRangeEnd) <= 0)) {
            throw new InvalidParameterValueException("Nic IP " + nicProfile.getIp4Address() + " does not belong to network " + networkAddress + " in vlanId " + cmd.getVlanId());
        }

        // everything is ok
        return network;
    }

    @Override
    public void implementNetwork(Network network) throws ConfigurationException {
        Long vlanId = getGloboNetworkVlanId(network.getId());
        if (vlanId == null) {
            throw new CloudRuntimeException("Inconsistency. Network " + network.getName() + " there is not relation with GloboNetwork");
        }

        GetVlanInfoFromGloboNetworkCommand cmd = new GetVlanInfoFromGloboNetworkCommand();
        cmd.setVlanId(vlanId);

        Answer answer = callCommand(cmd, network.getDataCenterId());

        GloboNetworkVlanResponse vlanResponse = (GloboNetworkVlanResponse)answer;
        Long networkId = vlanResponse.getNetworkId();
        if (!vlanResponse.isActive()) {
            // Create network in equipment
            ActivateNetworkCommand activateCmd = new ActivateNetworkCommand(vlanId, networkId, vlanResponse.isv6());
            Answer cmdAnswer = callCommand(activateCmd, network.getDataCenterId());
            if (cmdAnswer == null || !cmdAnswer.getResult()) {
                throw new CloudRuntimeException("Unable to create network in GloboNetwork: VlanId " + vlanId + " networkId " + networkId);
            }
            s_logger.info("Network ready to use: VlanId " + vlanId + " networkId " + networkId);
        } else {
            s_logger.warn("Network already created in GloboNetwork: VlanId " + vlanId + " networkId " + networkId);
        }
    }

    /**
     * Returns VlanId (in GloboNetwork) given an Network. If network is not
     * associated with GloboNetwork, <code>null</code> will be returned.
     *
     * @param networkId
     * @return
     */
    private Long getGloboNetworkVlanId(Long networkId) {
        if (networkId == null) {
            return null;
        }
        GloboNetworkNetworkVO vo = _globoNetworkNetworkDao.findByNetworkId(networkId);
        if (vo == null) {
            return null;
        }
        return vo.getGloboNetworkVlanId();
    }

    /**
     * Get the number of vlan associate with {@code network}.
     *
     * @param network
     * @return
     */
    private Integer getVlanNum(URI broadcastUri) {
        if (broadcastUri == null) {
            return null;
        }
        try {
            Integer vlanNum = Integer.valueOf(broadcastUri.getHost());
            return vlanNum;
        } catch (NumberFormatException nfe) {
            String msg = "Invalid Vlan number in broadcast URI " + broadcastUri;
            s_logger.error(msg);
            throw new CloudRuntimeException(msg, nfe);
        }
    }

    @Override
    @DB
    public GloboNetworkEnvironmentVO addGloboNetworkEnvironment(Long physicalNetworkId, String name, Long globoNetworkEnvironmentId) {

        if (name == null || name.trim().isEmpty()) {
            throw new InvalidParameterValueException("Invalid name: " + name);
        }

        // validate physical network and zone
        // Check if physical network exists
        PhysicalNetwork pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }
        } else {
            throw new InvalidParameterValueException("Invalid physicalNetworkId: " + physicalNetworkId);
        }

        Long zoneId = pNtwk.getDataCenterId();

        // now, check if environment exists in GloboNetwork
        if (globoNetworkEnvironmentId != null) {
            Environment environment = getEnvironment(physicalNetworkId, globoNetworkEnvironmentId);
            if (environment == null) {
                throw new InvalidParameterValueException("Unable to find in GloboNetwork an enviroment having the specified environment id");
            }
        } else {
            throw new InvalidParameterValueException("Invalid GloboNetwork environmentId: " + globoNetworkEnvironmentId);
        }

        // Check if there is a environment with same id or name in this zone.
        List<GloboNetworkEnvironmentVO> globoNetworkEnvironments = listGloboNetworkEnvironmentsFromDB(null, zoneId);
        for (GloboNetworkEnvironmentVO globoNetworkEnvironment : globoNetworkEnvironments) {
            if (globoNetworkEnvironment.getName().equalsIgnoreCase(name)) {
                throw new InvalidParameterValueException("GloboNetwork environment with name " + name + " already exists in zone " + zoneId);
            }
            if (globoNetworkEnvironment.getGloboNetworkEnvironmentId() == globoNetworkEnvironmentId) {
                throw new InvalidParameterValueException("GloboNetwork environment with environmentId " + globoNetworkEnvironmentId + " already exists in zoneId " + zoneId);
            }
        }

        GloboNetworkEnvironmentVO napiEnvironmentVO = new GloboNetworkEnvironmentVO(physicalNetworkId, name, globoNetworkEnvironmentId);
        _globoNetworkEnvironmentDao.persist(napiEnvironmentVO);
        return napiEnvironmentVO;
    }

    @Override
    @DB
    public GloboNetworkLoadBalancerEnvironment addGloboNetworkLBEnvironment(final String name, Long physicalNetworkId, Long globoNetworkEnvironmentId,
            final Long globoNetworkLBEnvironmentId) throws ResourceAllocationException {

        if (name == null || name.trim().isEmpty()) {
            throw new InvalidParameterValueException("Invalid name: " + name);
        }

        // validate physical network and zone
        // Check if physical network exists
        PhysicalNetwork pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }
        } else {
            throw new InvalidParameterValueException("Invalid physicalNetworkId: " + physicalNetworkId);
        }

        // Check if there is a environment with same id or name in this zone.
        final GloboNetworkEnvironmentVO globoNetworkEnvironment = _globoNetworkEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(pNtwk.getId(), globoNetworkEnvironmentId);
        if (globoNetworkEnvironment == null) {
            throw new InvalidParameterValueException("Could not find a relationship between GloboNetwork Environment " + globoNetworkEnvironmentId + " and physical network "
                    + physicalNetworkId);
        }

        // Check if there is a LB environment with same id or name in this zone.
        List<GloboNetworkLoadBalancerEnvironment> globoNetworkLBEnvironments = listGloboNetworkLBEnvironmentsFromDB(pNtwk.getId(), null, globoNetworkEnvironmentId);
        for (GloboNetworkLoadBalancerEnvironment globoNetworkLBEnvironment : globoNetworkLBEnvironments) {
            if (globoNetworkLBEnvironment.getName().equalsIgnoreCase(name)) {
                throw new InvalidParameterValueException("LB environment with name " + name + " already exists.");
            }
            if (globoNetworkLBEnvironment.getGloboNetworkLoadBalancerEnvironmentId() == globoNetworkLBEnvironmentId) {
                throw new InvalidParameterValueException("Relationship between Environment " + globoNetworkEnvironmentId + " and LB Network " + globoNetworkLBEnvironmentId
                        + " already exists.");
            }
        }

        try {
            GloboNetworkLoadBalancerEnvironment globoNetworkLBEnvironmentVO = Transaction
                    .execute(new TransactionCallbackWithException<GloboNetworkLoadBalancerEnvironment, CloudException>() {

                        @Override
                        public GloboNetworkLoadBalancerEnvironment doInTransaction(TransactionStatus status) throws CloudException {
                            GloboNetworkLoadBalancerEnvironment globoNetworkLBEnvironmentVO = new GloboNetworkLoadBalancerEnvironment(name, globoNetworkEnvironment.getId(),
                                    globoNetworkLBEnvironmentId);
                            _globoNetworkLBEnvironmentDao.persist(globoNetworkLBEnvironmentVO);
                            return globoNetworkLBEnvironmentVO;
                        }
                    });

            return globoNetworkLBEnvironmentVO;

        } catch (CloudException e) {
            // Exception when defining IP ranges in Cloudstack
            throw new ResourceAllocationException(e.getLocalizedMessage(), ResourceType.public_ip);
        }
    }

    @Override
    @DB
    public Host addGloboNetworkHost(Long physicalNetworkId, String username, String password, String url) {

        if (username == null || username.trim().isEmpty()) {
            throw new InvalidParameterValueException("Invalid username: " + username);
        }

        if (password == null || password.trim().isEmpty()) {
            throw new InvalidParameterValueException("Invalid password: " + password);
        }

        if (url == null || url.trim().isEmpty()) {
            throw new InvalidParameterValueException("Invalid url: " + url);
        }

        // validate physical network and zone
        // Check if physical network exists
        PhysicalNetwork pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }
        } else {
            throw new InvalidParameterValueException("Invalid physicalNetworkId: " + physicalNetworkId);
        }

        final Long zoneId = pNtwk.getDataCenterId();

        final Map<String, String> params = new HashMap<String, String>();
        params.put("guid", "globonetwork-" + String.valueOf(zoneId));
        params.put("zoneId", String.valueOf(zoneId));
        params.put("name", Provider.GloboNetwork.getName());

        String readTimeout = GloboNetworkReadTimeout.value();
        String connectTimeout = GloboNetworkConnectionTimeout.value();
        String numberOfRetries = GloboNetworkNumberOfRetries.value();

        params.put("url", url);
        params.put("username", username);
        params.put("password", password);
        params.put("readTimeout", readTimeout);
        params.put("connectTimeout", connectTimeout);
        params.put("numberOfRetries", numberOfRetries);

        final Map<String, Object> hostDetails = new HashMap<String, Object>();
        hostDetails.putAll(params);

        Host host = Transaction.execute(new TransactionCallback<Host>() {

            @Override
            public Host doInTransaction(TransactionStatus status) {
                GloboNetworkResource resource = new GloboNetworkResource();

                try {
                    resource.configure(Provider.GloboNetwork.getName(), hostDetails);

                    Host host = _resourceMgr.addHost(zoneId, resource, resource.getType(), params);
                    return host;
                } catch (ConfigurationException e) {
                    throw new CloudRuntimeException(e);
                }
            }
        });
        return host;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        cmdList.add(AcquireNewIpForLbInGloboNetworkCmd.class);
        cmdList.add(AddGloboNetworkEnvironmentCmd.class);
        cmdList.add(AddGloboNetworkHostCmd.class);
        cmdList.add(AddGloboNetworkLBEnvironmentCmd.class);
        cmdList.add(AddGloboNetworkRealToVipCmd.class);
        cmdList.add(AddGloboNetworkVipToAccountCmd.class);
        cmdList.add(AddNetworkViaGloboNetworkCmd.class);
        cmdList.add(DeleteNetworkInGloboNetworkCmd.class);
        cmdList.add(DelGloboNetworkRealFromVipCmd.class);
        cmdList.add(DisassociateIpAddrFromGloboNetworkCmd.class);
        cmdList.add(GenerateUrlForEditingVipCmd.class);
        cmdList.add(ImportGloboNetworkLoadBalancerCmd.class);
        cmdList.add(ListAllEnvironmentsFromGloboNetworkCmd.class);
        cmdList.add(ListGloboNetworkCapabilitiesCmd.class);
        cmdList.add(ListGloboNetworkEnvironmentsCmd.class);
        cmdList.add(ListGloboNetworkLBEnvironmentsCmd.class);
        cmdList.add(ListGloboNetworkRealsCmd.class);
        cmdList.add(ListGloboNetworkVipsCmd.class);
        cmdList.add(RemoveGloboNetworkEnvironmentCmd.class);
        cmdList.add(RemoveGloboNetworkLBEnvironmentCmd.class);
        cmdList.add(RemoveGloboNetworkVipCmd.class);
        cmdList.add(ListGloboNetworkLBCacheGroupsCmd.class);
        return cmdList;
    }

    @Override
    public List<Environment> listAllEnvironmentsFromGloboNetwork(Long physicalNetworkId) {

        // validate physical network and zone
        // Check if physical network exists
        PhysicalNetwork pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }
        } else {
            throw new InvalidParameterValueException("Invalid physicalNetworkId: " + physicalNetworkId);
        }

        Long zoneId = pNtwk.getDataCenterId();

        ListAllEnvironmentsFromGloboNetworkCommand cmd = new ListAllEnvironmentsFromGloboNetworkCommand();

        Answer answer = callCommand(cmd, zoneId);

        List<Environment> environments = ((GloboNetworkAllEnvironmentResponse)answer).getEnvironmentList();
        return environments;
    }

    /**
     * Get <code>Environment</code> object from environmentId.
     * @param environmentId
     * @return Return null if environment was not found.
     */
    protected Environment getEnvironment(Long physicaNetworkId, Long environmentId) {
        if (environmentId == null) {
            return null;
        }

        Environment resultEnvironment = null;
        for (Environment environment : listAllEnvironmentsFromGloboNetwork(physicaNetworkId)) {
            if (environmentId.equals(environment.getId())) {
                resultEnvironment = environment;
                break;
            }
        }
        return resultEnvironment;
    }

    private void handleNetworkUnavailableError(CloudstackGloboNetworkException e) {
        if (e.getNapiCode() == 116) {
            // If this is the return code, it means that the vlan/network no longer exists in GloboNetwork
            // and we should continue to remove it from CloudStack
            s_logger.warn("Inconsistency between CloudStack and GloboNetwork");
            return;
        } else {
            // Otherwise, there was a different error and we should abort the operation
            throw e;
        }
    }

    public boolean destroyGloboNetwork(long networkId, boolean forced) {

        Network network = _ntwSvc.getNetwork(networkId);
        if (network == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("unable to find network with specified id");
            ex.addProxyObject(String.valueOf(networkId), "networkId");
            throw ex;
        }

        GloboNetworkNetworkVO napiNetworkVO = _globoNetworkNetworkDao.findByNetworkId(network.getId());
        if (napiNetworkVO == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Only networks managed by GloboNetwork may be deleted by this method");
            ex.addProxyObject(String.valueOf(networkId), "networkId");
            throw ex;
        }

        Account caller = CallContext.current().getCallingAccount();
        User userCaller = CallContext.current().getCallingUser();
        String contextId = CallContext.current().getContextId();

        // Perform permission check
        _accountMgr.checkAccess(caller, null, true, network);

        // ACS with allow exclusion of shared network by admin users, so after check
        // permission, let's change to system account to perform destroy network

        try {
            CallContext.register(_accountMgr.getSystemUser(), _accountMgr.getSystemAccount(), contextId);
            return _ntwSvc.deleteNetwork(networkId, forced);
        } finally {
            // restore context
            CallContext.register(userCaller, caller, contextId);
        }
    }

    @Override
    public void removeNetworkFromGloboNetwork(Network network) {

        try {
            // Make sure the VLAN is valid
            this.getVlanInfoFromGloboNetwork(network);

            RemoveNetworkInGloboNetworkCommand cmd = new RemoveNetworkInGloboNetworkCommand();
            Long vlanId = getGloboNetworkVlanId(network.getId());
            cmd.setVlanId(vlanId);

            this.callCommand(cmd, network.getDataCenterId());
        } catch (CloudstackGloboNetworkException e) {
            handleNetworkUnavailableError(e);
        }
    }

    @Override
    @DB
    public void deallocateVlanFromGloboNetwork(Network network) {

        try {
            GloboNetworkNetworkVO napiNetworkVO = _globoNetworkNetworkDao.findByNetworkId(network.getId());
            if (napiNetworkVO != null) {
                this.deallocateVlanFromGloboNetwork(network.getDataCenterId(), napiNetworkVO.getGloboNetworkVlanId());
                _globoNetworkNetworkDao.remove(napiNetworkVO.getId());
            }

        } catch (CloudstackGloboNetworkException e) {
            handleNetworkUnavailableError(e);
        }
    }

    public void deallocateVlanFromGloboNetwork(Long zoneId, Long vlanId) {

        DeallocateVlanFromGloboNetworkCommand cmd = new DeallocateVlanFromGloboNetworkCommand();
        cmd.setVlanId(vlanId);

        this.callCommand(cmd, zoneId);
    }

    @Override
    public List<GloboNetworkEnvironmentVO> listGloboNetworkEnvironmentsFromDB(Long physicalNetworkId, Long zoneId) {
        List<GloboNetworkEnvironmentVO> globoNetworkEnvironmentsVOList;

        if (physicalNetworkId != null) {
            // Check if physical network exists
            PhysicalNetwork pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }

            globoNetworkEnvironmentsVOList = _globoNetworkEnvironmentDao.listByPhysicalNetworkId(physicalNetworkId);

        } else if (zoneId != null) {
            // Check if zone exists
            DataCenter zone = _dcDao.findById(zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Specified zone id was not found");
            }

            globoNetworkEnvironmentsVOList = new ArrayList<GloboNetworkEnvironmentVO>();
            for (PhysicalNetworkVO physicalNetwork : _physicalNetworkDao.listByZone(zoneId)) {
                List<GloboNetworkEnvironmentVO> partialResult = _globoNetworkEnvironmentDao.listByPhysicalNetworkId(physicalNetwork.getId());
                if (partialResult != null) {
                    globoNetworkEnvironmentsVOList.addAll(partialResult);
                }
            }
        } else {
            globoNetworkEnvironmentsVOList = _globoNetworkEnvironmentDao.listAll();
        }

        return globoNetworkEnvironmentsVOList;
    }

    @Override
    public List<GloboNetworkLoadBalancerEnvironment> listGloboNetworkLBEnvironmentsFromDB(Long physicalNetworkId, Long networkId, Long globoNetworkEnvironmentId) {

        Long glbEnvId = null;
        if (networkId != null) {
            // Retrieve glbEnvId from network
            GloboNetworkNetworkVO globoNetworkNetworkVO = _globoNetworkNetworkDao.findByNetworkId(networkId);
            if (globoNetworkNetworkVO == null) {
                throw new InvalidParameterValueException("Unable to find mapping for networkId " + networkId);
            }
            glbEnvId = globoNetworkNetworkVO.getGloboNetworkEnvironmentId();
        } else if (globoNetworkEnvironmentId != null) {
            glbEnvId = globoNetworkEnvironmentId;
        } else {
            throw new InvalidParameterValueException("NetworkId or GloboNetworkEnvironmentId is required");
        }

        // Retrieve napiEnvironment from DB
        GloboNetworkEnvironmentVO globoNetworkEnvironment = _globoNetworkEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(physicalNetworkId, glbEnvId);

        if (globoNetworkEnvironment == null) {
            // No physical network/environment pair registered in the database.
            throw new InvalidParameterValueException("Unable to find a relationship between physical network=" + physicalNetworkId + " and GloboNetwork environment="
                    + globoNetworkEnvironmentId);
        }

        List<GloboNetworkLoadBalancerEnvironment> globoNetworkLBEnvironmentVOList;

        if (glbEnvId != null) {
            globoNetworkLBEnvironmentVOList = _globoNetworkLBEnvironmentDao.listByEnvironmentRefId(globoNetworkEnvironment.getId());

        } else {
            globoNetworkLBEnvironmentVOList = _globoNetworkLBEnvironmentDao.listAll();
        }

        return globoNetworkLBEnvironmentVOList;
    }

    @Override
    @DB
    public boolean removeGloboNetworkEnvironment(Long physicalNetworkId, Long globoNetworkEnvironmentId) {

        // Check if there are any networks in this GloboNetwork environment
        List<GloboNetworkNetworkVO> associationList = _globoNetworkNetworkDao.listByEnvironmentId(globoNetworkEnvironmentId);

        if (!associationList.isEmpty()) {
            throw new InvalidParameterValueException("There are active networks on environment " + globoNetworkEnvironmentId
                    + ". Please delete them before removing this environment.");
        }

        // Retrieve napiEnvironment from DB
        GloboNetworkEnvironmentVO globoNetworkEnvironment = _globoNetworkEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(physicalNetworkId, globoNetworkEnvironmentId);

        if (globoNetworkEnvironment == null) {
            // No physical network/environment pair registered in the database.
            throw new InvalidParameterValueException("Unable to find a relationship between physical network=" + physicalNetworkId + " and GloboNetwork environment="
                    + globoNetworkEnvironmentId);
        }

        boolean result = _globoNetworkEnvironmentDao.remove(globoNetworkEnvironment.getId());

        return result;
    }

    @Override
    @DB
    public boolean removeGloboNetworkLBEnvironment(Long physicalNetworkId, Long globoNetworkEnvironmentId, Long globoNetworkLBEnvironmentId) {

        // Retrieve napiEnvironment from DB
        GloboNetworkEnvironmentVO globoNetworkEnvironment = _globoNetworkEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(physicalNetworkId, globoNetworkEnvironmentId);

        if (globoNetworkEnvironment == null) {
            // No physical network/environment pair registered in the database.
            throw new InvalidParameterValueException("Unable to find a relationship between physical network=" + physicalNetworkId + " and GloboNetwork environment="
                    + globoNetworkEnvironmentId);
        }

        // Retrieve LB Environment from DB
        final GloboNetworkLoadBalancerEnvironment globoNetworkLBEnvironmentVO = _globoNetworkLBEnvironmentDao.findByEnvironmentRefAndLBNetwork(globoNetworkEnvironment.getId(),
                globoNetworkLBEnvironmentId);
        if (globoNetworkLBEnvironmentVO == null) {
            throw new InvalidParameterValueException("Unable to find a relationship between environment " + globoNetworkEnvironmentId + " and LB network "
                    + globoNetworkLBEnvironmentId);
        }

        List<GloboNetworkIpDetailVO> ipDetailVOList = _globoNetworkIpDetailDao.listByLBEnvironmentRef(globoNetworkLBEnvironmentVO.getId());
        if (!ipDetailVOList.isEmpty()) {
            throw new InvalidParameterValueException("There are Load Balancers in environment '" + globoNetworkLBEnvironmentVO.getName() + "'");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                _globoNetworkLBEnvironmentDao.remove(globoNetworkLBEnvironmentVO.getId());

                // never remove portable ip range because can be used in others zones.
//               if (_portableIpRangeDao.findById(globoNetworkLBNetworkVO.getPortableIpRangeId()) != null) {
//                    if portable ip range was removed, is ok.
//                   _configMgr.deletePortableIpRange(globoNetworkLBNetworkVO.getPortableIpRangeId());
//               }
            }
        });
        return true;
    }

    @Override
    public Vlan getVlanInfoFromGloboNetwork(Network network) {
        GetVlanInfoFromGloboNetworkCommand cmd = new GetVlanInfoFromGloboNetworkCommand();
        Long vlanId = getGloboNetworkVlanId(network.getId());
        cmd.setVlanId(vlanId);

        GloboNetworkVlanResponse response = (GloboNetworkVlanResponse)callCommand(cmd, network.getDataCenterId());

        Vlan vlan = new Vlan();
        vlan.setId(response.getVlanId());
        vlan.setName(response.getVlanName());
        vlan.setVlanNum(response.getVlanNum());
        vlan.setDescription(response.getVlanDescription());

        return vlan;
    }

    @Override
    public void registerNicInGloboNetwork(NicProfile nic, VirtualMachineProfile vm, Network network) {

        String msg = "Unable to register nic " + nic + " from VM " + vm + ".";
        if (vm == null || nic == null) {
            throw new CloudRuntimeException(msg + " Invalid nic, virtual machine or network.");
        }

        GloboNetworkNetworkVO globoNetworkNetworkVO = _globoNetworkNetworkDao.findByNetworkId(network.getId());
        if (globoNetworkNetworkVO == null) {
            throw new CloudRuntimeException(msg + " Could not obtain mapping for network in GloboNetwork.");
        }

        Long equipmentGroup = GloboNetworkVmEquipmentGroup.value();
        if (equipmentGroup == null || "".equals(equipmentGroup)) {
            throw new CloudRuntimeException(msg + " Invalid equipment group for VM. Check your GloboNetwork global options.");
        }

        Long equipmentModel = null;
        switch (vm.getType()) {
        case DomainRouter:
            equipmentModel = GloboNetworkModelVmDomainRouter.value();
            break;
        case ConsoleProxy:
            equipmentModel = GloboNetworkModelVmConsoleProxy.value();
            break;
        case SecondaryStorageVm:
            equipmentModel = GloboNetworkModelVmSecondaryStorageVm.value();
            break;
        case ElasticIpVm:
            equipmentModel = GloboNetworkModelVmElasticIpVm.value();
            break;
        case ElasticLoadBalancerVm:
            equipmentModel = GloboNetworkModelVmElasticLoadBalancerVm.value();
            break;
        case InternalLoadBalancerVm:
            equipmentModel = GloboNetworkModelVmInternalLoadBalancerVm.value();
            break;
        case UserBareMetal:
            equipmentModel = GloboNetworkModelVmUserBareMetal.value();
            break;
        default:
            equipmentModel = GloboNetworkModelVmUser.value();
            break;
        }
        if (equipmentModel == null) {
            throw new CloudRuntimeException(msg + " Invalid equipment model for VM of type " + vm.getType() + ". Check your GloboNetwork global options.");
        }

        RegisterEquipmentAndIpInGloboNetworkCommand cmd = new RegisterEquipmentAndIpInGloboNetworkCommand();
        if (nic.getIp4Address() != null) {
            cmd.setNicIp(nic.getIp4Address());
        } else {
            cmd.setNicIp(nic.getIp6Address());
        }
        cmd.setNicDescription("");
        cmd.setVmName(getEquipNameFromUuid(vm.getUuid()));
        cmd.setVlanId(globoNetworkNetworkVO.getGloboNetworkVlanId());
        cmd.setEnvironmentId(globoNetworkNetworkVO.getGloboNetworkEnvironmentId());
        cmd.setEquipmentGroupId(Long.valueOf(equipmentGroup));
        cmd.setEquipmentModelId(Long.valueOf(equipmentModel));

        Answer answer = this.callCommand(cmd, vm.getVirtualMachine().getDataCenterId());
        if (answer == null || !answer.getResult()) {
            msg = answer == null ? msg : answer.getDetails();
            throw new CloudRuntimeException(msg);
        }
    }

    private String getEquipNameFromUuid(String uuid) {
        String instanceNamePrefix = _configDao.getValue(Config.InstanceName.key());
        String equipName = "";
        if (instanceNamePrefix == null || instanceNamePrefix.equals("")) {
            equipName = uuid;
        } else {
            equipName = instanceNamePrefix + "-" + uuid;
        }
        return equipName;
    }

    @Override
    public void unregisterNicInGloboNetwork(NicProfile nic, VirtualMachineProfile vm) {

        String msg = "Unable to unregister nic " + nic + " from VM " + vm + ".";
        if (vm == null || nic == null) {
            throw new CloudRuntimeException(msg + " Invalid nic or virtual machine.");
        }

        Long equipmentGroup = GloboNetworkVmEquipmentGroup.value();
        if (equipmentGroup == null) {
            throw new CloudRuntimeException(msg + " Invalid equipment group for VM. Check your GloboNetwork global options.");
        }

        UnregisterEquipmentAndIpInGloboNetworkCommand cmd = new UnregisterEquipmentAndIpInGloboNetworkCommand();
        if (nic.getIp4Address() != null) {
            cmd.setNicIp(nic.getIp4Address());
            cmd.setIsv6(false);
        } else {
            cmd.setNicIp(nic.getIp6Address());
            cmd.setIsv6(true);
        }
        cmd.setVmName(getEquipNameFromUuid(vm.getUuid()));

        GloboNetworkNetworkVO globoNetworkNetworkVO = _globoNetworkNetworkDao.findByNetworkId(nic.getNetworkId());
        if (globoNetworkNetworkVO != null) {
            cmd.setEnvironmentId(globoNetworkNetworkVO.getGloboNetworkEnvironmentId());
        }

        Answer answer = this.callCommand(cmd, vm.getVirtualMachine().getDataCenterId());
        if (answer == null || !answer.getResult()) {
            msg = answer == null ? msg : answer.getDetails();
            throw new CloudRuntimeException(msg);
        }
    }

    @Override
    public boolean removeVmFromLoadBalancer(VirtualMachineProfile vm) {
        return _lbMgr.removeVmFromLoadBalancers(vm.getId());
    }

    @Override
    public GloboNetworkVipAccVO addGloboNetworkVipToAcc(Long globoNetworkVipId, Long networkId) {

        Account caller = CallContext.current().getCallingAccount();
        Network network = null;
        if (networkId != null) {
            network = _ntwkDao.findById(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Unable to find a network having the specified network id");
            }
        } else {
            throw new InvalidParameterValueException("Invalid networkId: " + networkId);
        }
        // Perform account permission check on network
        _accountMgr.checkAccess(caller, null, true, network);

        boolean isv6 = this.isNetworkv6(network);

        GetVipInfoFromGloboNetworkCommand cmd = new GetVipInfoFromGloboNetworkCommand(globoNetworkVipId, isv6);
        Answer answer = this.callCommand(cmd, network.getDataCenterId());
        String msg = "Could not validate VIP id with GloboNetwork";
        if (answer == null || !answer.getResult()) {
            msg = answer == null ? msg : answer.getDetails();
            throw new CloudRuntimeException(msg);
        }

        // TODO Remove accountId
        Long accountId = network.getAccountId();
        GloboNetworkVipAccVO globoNetworkVipAcc = _globoNetworkVipAccDao.findGloboNetworkVipAcc(globoNetworkVipId, accountId, networkId);
        if (globoNetworkVipAcc != null) {
            // Already exists, continue
            s_logger.info("Association between VIP " + globoNetworkVipId + " and network " + networkId + " already exists");
        } else {
            globoNetworkVipAcc = new GloboNetworkVipAccVO(globoNetworkVipId, accountId, networkId);
            _globoNetworkVipAccDao.persist(globoNetworkVipAcc);
        }

        return globoNetworkVipAcc;
    }

    @Override
    public void associateNicToVip(Long vipId, Nic nic) {
        VMInstanceVO vm = _vmDao.findById(nic.getInstanceId());
        if (vm == null) {
            throw new CloudRuntimeException("There is no VM that belongs to nic " + nic);
        }

        GloboNetworkVipAccVO globoNetworkVipVO = _globoNetworkVipAccDao.findGloboNetworkVip(vipId, nic.getNetworkId());
        if (globoNetworkVipVO == null) {
            throw new InvalidParameterValueException("Vip " + vipId + " is not associated with Cloudstack");
        }

        Network network = _ntwkDao.findById(nic.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("Network " + nic.getNetworkId() + " doesn't exist in Cloudstack");
        }

        AddAndEnableRealInGloboNetworkCommand cmd = new AddAndEnableRealInGloboNetworkCommand();
        cmd.setEquipName(getEquipNameFromUuid(vm.getUuid()));
        cmd.setIp((nic.getIp4Address() != null ? nic.getIp4Address() : nic.getIp6Address()));
        cmd.setVipId(vipId);
        Answer answer = callCommand(cmd, network.getDataCenterId());
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException("Error associating nic " + nic + " to vip " + vipId + ": " + (answer == null ? null : answer.getDetails()));
        }
    }

    @Override
    public void disassociateNicFromVip(Long vipId, Nic nic) {
        DisableAndRemoveRealInGloboNetworkCommand cmd = new DisableAndRemoveRealInGloboNetworkCommand();
        VMInstanceVO vm = _vmDao.findById(nic.getInstanceId());
        if (vm == null) {
            throw new CloudRuntimeException("There is no VM that belongs to nic " + nic);
        }
        cmd.setEquipName(getEquipNameFromUuid(vm.getUuid()));
        cmd.setIp((nic.getIp4Address() != null ? nic.getIp4Address() : nic.getIp6Address()));
        cmd.setVipId(vipId);
        Network network = _ntwkDao.findById(nic.getNetworkId());
        Answer answer = callCommand(cmd, network.getDataCenterId());
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException("Error removing nic " + nic + " from vip " + vipId + ": " + (answer == null ? null : answer.getDetails()));
        }
    }

    @Override
    public List<GloboNetworkVipResponse> listGloboNetworkVips(Long projectId) {

        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<Long>();

        permittedAccounts.add(caller.getId());

        if (projectId != null) {
            Project project = _projectMgr.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by specified id");
            }
            if (!_projectMgr.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                // getProject() returns type ProjectVO.
                InvalidParameterValueException ex = new InvalidParameterValueException("Account " + caller + " cannot access specified project id");
                ex.addProxyObject(project.getUuid(), "projectId");
                throw ex;
            }

            permittedAccounts.add(project.getProjectAccountId());
        }

        // FIXME Improve this search by creating a custom criteria
        List<DataCenterVO> zones = _dcDao.listAllZones();
        List<NetworkVO> allowedNetworks = new ArrayList<NetworkVO>();
        for (DataCenterVO zone : zones) {
            for (Long accountId : permittedAccounts) {
                allowedNetworks.addAll(_ntwkDao.listNetworksByAccount(accountId, zone.getId(), Network.GuestType.Shared, false));
            }
        }

        List<Long> networkIds = new ArrayList<Long>();
        for (NetworkVO networkVO : allowedNetworks) {
            networkIds.add(networkVO.getId());
        }

        if (networkIds.isEmpty()) {
            return new ArrayList<GloboNetworkVipResponse>();
        }

        // Get all vip Ids related to networks
        List<GloboNetworkVipAccVO> globoNetworkVipAccList = _globoNetworkVipAccDao.listByNetworks(networkIds);

        Map<Long, GloboNetworkVipResponse> vips = new HashMap<Long, GloboNetworkVipResponse>();
        for (GloboNetworkVipAccVO globoNetworkVipAcc : globoNetworkVipAccList) {

            Network network = _ntwkDao.findById(globoNetworkVipAcc.getNetworkId());

            GloboNetworkVipResponse vip;

            if (vips.get(globoNetworkVipAcc.getGloboNetworkVipId()) == null) {

                // Vip is not in the returning map yet, get all info from GloboNetwork
                boolean isv6 = this.isNetworkv6(network);

                GetVipInfoFromGloboNetworkCommand cmd = new GetVipInfoFromGloboNetworkCommand(globoNetworkVipAcc.getGloboNetworkVipId(), isv6);
                Answer answer = this.callCommand(cmd, network.getDataCenterId());
                String msg = "Could not list VIPs from GloboNetwork";
                if (answer == null || !answer.getResult()) {
                    msg = answer == null ? msg : answer.getDetails();
                    throw new CloudRuntimeException(msg);
                }
                vip = ((GloboNetworkVipResponse)answer);

                vips.put(globoNetworkVipAcc.getGloboNetworkVipId(), vip);

            } else {
                // Vip is already in the returning map
                vip = vips.get(globoNetworkVipAcc.getGloboNetworkVipId());
            }

            if (vip.getNetworkIds() == null) {
                vip.setNetworkIds(new ArrayList<String>());
            }
            vip.getNetworkIds().add(network.getUuid());

        }
        return new ArrayList<GloboNetworkVipResponse>(vips.values());
    }

    @Override
    public String generateUrlForEditingVip(Long vipId, Network network) {

        GenerateUrlForEditingVipCommand cmd = new GenerateUrlForEditingVipCommand(vipId, GloboNetworkVIPServerUrl.value());
        Answer answer = callCommand(cmd, network.getDataCenterId());
        String msg = "Could not list VIPs from GloboNetwork";
        if (answer == null || !answer.getResult()) {
            msg = answer == null ? msg : answer.getDetails();
            throw new CloudRuntimeException(msg);
        }
        return answer.getDetails();
    }

    @Override
    public void removeGloboNetworkVip(Long napiVipId) {

        Account caller = CallContext.current().getCallingAccount();

        List<GloboNetworkVipAccVO> globoNetworkVipList = _globoNetworkVipAccDao.findByVipId(napiVipId);

        if (globoNetworkVipList == null || globoNetworkVipList.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find an association for VIP " + napiVipId);
        }

        Network network = null;
        for (GloboNetworkVipAccVO globoNetworkVipAccVO : globoNetworkVipList) {
            network = _ntwkDao.findById(globoNetworkVipAccVO.getNetworkId());
            if (network == null) {
                throw new InvalidParameterValueException("Unable to find a network having the specified network id");
            }
            // Perform account permission check on network
            _accountMgr.checkAccess(caller, null, true, network);

            _globoNetworkVipAccDao.remove(globoNetworkVipAccVO.getId());

        }

        RemoveVipFromGloboNetworkCommand cmd = new RemoveVipFromGloboNetworkCommand();
        cmd.setVipId(napiVipId);

        Answer answer = this.callCommand(cmd, network.getDataCenterId());

        String msg = "Could not remove VIP " + napiVipId + " from GloboNetwork";
        if (answer == null || !answer.getResult()) {
            msg = answer == null ? msg : answer.getDetails();
            throw new CloudRuntimeException(msg);
        }
    }

    @Override
    public List<GloboNetworkVipResponse.Real> listGloboNetworkReals(Long vipId) {
        if (vipId == null) {
            throw new InvalidParameterValueException("Invalid VIP id");
        }

        List<GloboNetworkVipAccVO> globoNetworkVips = _globoNetworkVipAccDao.findByVipId(vipId);

        if (globoNetworkVips == null) {
            throw new CloudRuntimeException("Could not find VIP " + vipId);
        }

        List<GloboNetworkVipResponse.Real> reals = new ArrayList<GloboNetworkVipResponse.Real>();

        if (globoNetworkVips.isEmpty()) {
            return reals;
        }

        // We need a network to call commands, any network associated to this VIP will do
        Network network = _ntwkDao.findById(globoNetworkVips.get(0).getNetworkId());

        if (network == null) {
            throw new CloudRuntimeException("Could not find network with networkId " + globoNetworkVips.get(0).getNetworkId());
        }

        boolean isv6 = this.isNetworkv6(network);

        GetVipInfoFromGloboNetworkCommand cmd = new GetVipInfoFromGloboNetworkCommand(vipId, isv6);
        Answer answer = this.callCommand(cmd, network.getDataCenterId());
        String msg = "Could not find VIP from GloboNetwork";
        if (answer == null || !answer.getResult()) {
            msg = answer == null ? msg : answer.getDetails();
            throw new CloudRuntimeException(msg);
        }
        GloboNetworkVipResponse vip = ((GloboNetworkVipResponse)answer);

        for (Real real : vip.getReals()) {
            for (GloboNetworkVipAccVO globoNetworkVipVO : globoNetworkVips) {
                network = _ntwkDao.findById(globoNetworkVipVO.getNetworkId());

                // If real's IP is not within network range, skip it
                if (isv6) {
                    if (!NetUtils.isIp6InNetwork(real.getIp(), network.getCidr())); {
                        continue;
                    }
                } else {
                    if (!NetUtils.isIpWithtInCidrRange(real.getIp(), network.getCidr())) {
                        continue;
                    }
                }

                VMInstanceVO realVM = _vmDao.findByUuid(real.getVmName());
                if (realVM != null) {
                    Nic nic = _nicDao.findByNtwkIdAndInstanceId(globoNetworkVipVO.getNetworkId(), realVM.getId());
                    if (nic != null) {
                        real.setNic(String.valueOf(nic.getId()));
                        real.setNetwork(network.getName());

                        // User VM name rather than UUID
                        real.setVmName(realVM.getHostName());
                    }
                }
            }

            reals.add(real);
        }

        return reals;
    }

    @Override
    public String getConfigComponentName() {
        return GloboNetworkManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {GloboNetworkVIPServerUrl, GloboNetworkConnectionTimeout, GloboNetworkLBLockTimeout, GloboNetworkReadTimeout, GloboNetworkNumberOfRetries,
                GloboNetworkVmEquipmentGroup, GloboNetworkModelVmUser, GloboNetworkModelVmDomainRouter, GloboNetworkModelVmConsoleProxy, GloboNetworkModelVmSecondaryStorageVm,
                GloboNetworkModelVmElasticIpVm, GloboNetworkModelVmElasticLoadBalancerVm, GloboNetworkModelVmInternalLoadBalancerVm, GloboNetworkModelVmUserBareMetal,
                GloboNetworkDomainSuffix, GloboNetworkDomainPattern, GloboNetworkLBAllowedSuffixes};
    }

    protected PortableIpRange getPortableIpRange(Long zoneId, Integer vlanNumber, String networkCidr, String networkGateway) throws ResourceAllocationException,
            ConcurrentOperationException, InvalidParameterValueException, InsufficientCapacityException {

        // There is no relationship between zone and regin yet
        Integer regionId = 1;
        for (PortableIpRange portableIpRange : _portableIpRangeDao.listByRegionId(regionId)) {
            // compare only gateway because vlan doesn't matters for public ip range
            if (portableIpRange.getGateway().equals(networkGateway)) {
                return portableIpRange;
            }
        }
        return null;
    }

    protected PortableIpRangeVO createPortableIpRange(Long zoneId, Integer vlanNumber, String networkCidr, String networkGateway) throws ResourceAllocationException,
            ResourceUnavailableException, ConcurrentOperationException, InvalidParameterValueException, InsufficientCapacityException {
        // FIXME! Portable IP and Portable IP Range are not IPv6 ready

        // There is not relationship between zoneId and region yet.
        //DataCenter dc = _dcDao.findById(zoneId);
        Integer regionId = 1;

        String vlan = BroadcastDomainType.Vlan.toUri(vlanNumber).toString();
        String networkMask = NetUtils.getCidrNetmask(networkCidr);
        String networkAddress = NetUtils.getCidr(networkCidr).first();
        long size = NetUtils.getCidrSize(networkMask);
        String startIP = NetUtils.getIpRangeStartIpFromCidr(networkAddress, size);
        String endIP = NetUtils.getIpRangeEndIpFromCidr(networkAddress, size);

        // shift start ips by NUMBER_OF_RESERVED_IPS_FROM_START and NUMBER_OF_RESERVED_IPS_BEFORE_END
        startIP = NetUtils.long2Ip(NetUtils.ip2Long(startIP) + NUMBER_OF_RESERVED_IPS_FOR_LB_FROM_START);
        endIP = NetUtils.long2Ip(NetUtils.ip2Long(endIP) - NUMBER_OF_RESERVED_IPS_FOR_LB_BEFORE_END);

        PortableIpRangeVO portableIpRange = _configMgr.createPortableIpRange(regionId, startIP, endIP, networkGateway, networkMask, vlan);
        return portableIpRange;
    }

    @Override
    public PublicIp acquireLbIp(final Long networkId, Long projectId, Long lbEnvironmentId) throws ResourceAllocationException, ResourceUnavailableException,
            ConcurrentOperationException, InvalidParameterValueException, InsufficientCapacityException {

        // First of all, check user permission
        final Account caller = CallContext.current().getCallingAccount();

        final Network network;
        if (networkId != null) {
            network = _ntwkDao.findById(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Unable to find a network having the specified network id");
            }
        } else {
            throw new InvalidParameterValueException("Invalid networkId: " + networkId);
        }
        // Perform account permission check on network
        _accountMgr.checkAccess(caller, null, true, network);

        // If project was set, this IP belongs to that project
        // Otherwise, the caller is the owner
        final Account owner;
        if (projectId != null) {
            Project project = _projectMgr.getProject(projectId);
            if (project != null) {
                owner = _accountMgr.getAccount(project.getProjectAccountId());
            } else {
                throw new InvalidParameterValueException("Could not find project with id " + projectId);
            }
        } else {
            owner = caller;
        }

        // Retrieve glbEnvId from network
        GloboNetworkNetworkVO globoNetworkNetworkVO = _globoNetworkNetworkDao.findByNetworkId(networkId);
        if (globoNetworkNetworkVO == null) {
            throw new InvalidParameterValueException("Unable to find mapping for networkId " + networkId);
        }
        Long glbEnvId = globoNetworkNetworkVO.getGloboNetworkEnvironmentId();

        // Retrieve napiEnvironment from DB
        GloboNetworkEnvironmentVO globoNetworkEnvironment = _globoNetworkEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(network.getPhysicalNetworkId(), glbEnvId);

        if (globoNetworkEnvironment == null) {
            // No physical network/environment pair registered in the database.
            throw new InvalidParameterValueException("Unable to find a relationship between physical network=" + network.getPhysicalNetworkId() + " and GloboNetwork environment="
                    + glbEnvId);
        }

        final GloboNetworkLoadBalancerEnvironment globoNetworkLBEnvironment = _globoNetworkLBEnvironmentDao.findById(lbEnvironmentId);
        if (globoNetworkLBEnvironment == null) {
            throw new InvalidParameterValueException("Could not find LB network " + lbEnvironmentId);
        }

        boolean isv6 = this.isNetworkv6(network);

        Long gnLoadBalancerEnvironment = globoNetworkLBEnvironment.getGloboNetworkLoadBalancerEnvironmentId();
        AcquireNewIpForLbCommand cmd = new AcquireNewIpForLbCommand(gnLoadBalancerEnvironment, isv6);

        final GloboNetworkAndIPResponse globoNetwork = (GloboNetworkAndIPResponse)this.callCommand(cmd, network.getDataCenterId());

        try {
            // FIXME! PublicIp class is not IPv6 ready
            PublicIp publicIp = Transaction.execute(new TransactionCallbackWithException<PublicIp, CloudException>() {

                @Override
                public PublicIp doInTransaction(TransactionStatus status) throws CloudException {
                    Long zoneId = network.getDataCenterId();

                    // ensure portable ip range exists
                    String networkGateway = GloboNetworkManager.this.getNetworkGateway(globoNetwork.getNetworkAddress(), globoNetwork.getNetworkMask(), globoNetwork.isv6());
                    PortableIpRange portableIpRange = getPortableIpRange(zoneId, globoNetwork.getVlanNum(), globoNetwork.getNetworkCidr(), networkGateway);
                    if (portableIpRange == null) {
                        portableIpRange = createPortableIpRange(zoneId, globoNetwork.getVlanNum(), globoNetwork.getNetworkCidr(), networkGateway);
                    }

                    IPAddressVO ip = (IPAddressVO)_ipAddrMgr.allocatePortableIp(owner, caller, zoneId, networkId, null, globoNetwork.getIp());

                    GloboNetworkIpDetailVO gnIpDetail = new GloboNetworkIpDetailVO(ip.getId(), globoNetwork.getIpId());
                    gnIpDetail.setGloboNetworkEnvironmentRefId(globoNetworkLBEnvironment.getId());
                    _globoNetworkIpDetailDao.persist(gnIpDetail);

                    VlanVO vlan = _vlanDao.findById(ip.getVlanId());
                    return PublicIp.createFromAddrAndVlan(ip, vlan);
                }
            });
            return publicIp;

        } catch (CloudException e) {
            // Exception when allocating new IP in Cloudstack. Roll back transaction in GloboNetwork
            s_logger.error("Reverting IP allocation in GloboNetwork due to error allocating IP", e);
            ReleaseIpFromGloboNetworkCommand cmdRelease = new ReleaseIpFromGloboNetworkCommand(globoNetwork.getIp(), gnLoadBalancerEnvironment, globoNetwork.isv6());
            this.callCommand(cmdRelease, network.getDataCenterId());
            throw new ResourceAllocationException(e.getLocalizedMessage(), ResourceType.public_ip);
        }
    }

    private String getNetworkGateway(String network, String mask, boolean isv6) {
        if (network == null || mask == null) {
            return null;
        }

        if (isv6) {
            com.googlecode.ipv6.IPv6Network ipv6Network = com.googlecode.ipv6.IPv6Network.fromAddressAndMask(
                    com.googlecode.ipv6.IPv6Address.fromString(network),
                    com.googlecode.ipv6.IPv6NetworkMask.fromAddress(com.googlecode.ipv6.IPv6Address.fromString(mask)));
            return ipv6Network.getFirst().toString();
        } else {
            return NetUtils.getIpRangeStartIpFromCidr(network, NetUtils.getCidrSize(mask));
        }
    }

    @Override
    public boolean disassociateIpAddrFromGloboNetwork(final long ipId) {
        final IPAddressVO ipVO = _ipAddrDao.findById(ipId);
        final UserIpv6AddressVO ipv6VO = _ipv6AddrDao.findById(ipId);
        if (ipVO == null && ipv6VO == null) {
            throw new InvalidParameterValueException("Unable to find ip address with id=" + ipId);
        }

        final boolean isv6 = ipv6VO != null;

        try {
            boolean result = Transaction.execute(new TransactionCallbackWithExceptionNoReturn<CloudException>() {

                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) throws CloudException {
                    GloboNetworkIpDetailVO gnIpDetail = _globoNetworkIpDetailDao.findByIp(ipId);

                    _ipAddrMgr.releasePortableIpAddress(ipId); // FIXME This will not deallocate portable IPv6 ips. PortableIp is not IPv6 ready

                    String ipAddress = isv6 ? ipv6VO.getAddress() : ipVO.getAddress().addr();
                    long dataCenterId = isv6 ? ipv6VO.getDataCenterId() : ipVO.getDataCenterId();

                    if (gnIpDetail != null && gnIpDetail.getGloboNetworkEnvironmentRefId() != null) {
                        GloboNetworkLoadBalancerEnvironment gnLbNetworkVO = _globoNetworkLBEnvironmentDao.findById(gnIpDetail.getGloboNetworkEnvironmentRefId());
                        if (gnLbNetworkVO == null) {
                            throw new InvalidParameterValueException("Could not find mapping between lb environment " + gnIpDetail.getGloboNetworkEnvironmentRefId());
                        }

                        ReleaseIpFromGloboNetworkCommand cmdRelease = new ReleaseIpFromGloboNetworkCommand(ipAddress, gnLbNetworkVO
                                .getGloboNetworkLoadBalancerEnvironmentId(), isv6);
                        GloboNetworkManager.this.callCommand(cmdRelease, dataCenterId);
                        _globoNetworkIpDetailDao.remove(gnIpDetail.getId());
                    } else {
                        s_logger.warn("Ip " + ipAddress + " is not associate with GloboNetwork");
                    }
                }
            });

            return result;
        } catch (CloudException e) {
            throw new CloudRuntimeException("Could not disassociate IP address", e);
        }
    }

    @Override
    public boolean applyLbRuleInGloboNetwork(final Network network, final LoadBalancingRule rule) throws ResourceUnavailableException {
        // Validate params
        if (network == null || rule == null) {
            return false;
        }

        final Account account = _accountMgr.getAccount(network.getAccountId());

        // Information to be used by LB later on
        boolean revokeAnyVM = false;

        // GloboNetwork doesn't allow concurrent call in same load balancer or ip address
        final GlobalLock lock = GlobalLock.getInternLock("globonetworklb-" + rule.getSourceIp().addr());
        try {

            if (!lock.lock(GloboNetworkLBLockTimeout.value())) {
                throw new ResourceUnavailableException(String.format("Failed to acquire lock for load balancer %s" + rule.getUuid()), DataCenter.class, network.getDataCenterId());
            }

            IPAddressVO ipVO = _ipAddrDao.findByIpAndNetworkId(rule.getNetworkId(), rule.getSourceIp().addr());
            if (ipVO == null) {
                throw new InvalidParameterValueException("Ip " + rule.getSourceIp().addr() + " is not associate with network " + rule.getNetworkId());
            }

            GloboNetworkIpDetailVO gnIpDetail = _globoNetworkIpDetailDao.findByIp(ipVO.getId());
            if (gnIpDetail == null) {
                throw new InvalidParameterValueException("Ip " + rule.getSourceIp().addr() + " (id " + ipVO.getId() + ") is not associate with globo network");
            } else if (gnIpDetail.getGloboNetworkEnvironmentRefId() == null) {
                throw new InvalidParameterValueException("Ip " + rule.getSourceIp().addr() + " (id " + ipVO.getId() + ") can't be used to load balancing");
            }

            GloboNetworkLoadBalancerEnvironment gnLbNetworkVO = _globoNetworkLBEnvironmentDao.findById(gnIpDetail.getGloboNetworkEnvironmentRefId());
            if (gnLbNetworkVO == null) {
                throw new InvalidParameterValueException("Could not find mapping between lb environment " + gnIpDetail.getGloboNetworkEnvironmentRefId());
            }

            // Stickness/Persistence
            if (rule.getStickinessPolicies() == null || rule.getStickinessPolicies().size() > 1) {
                throw new InvalidParameterValueException("Invalid stickness policy, list should contain only one");
            }

            // Healthcheck
            if (rule.getHealthCheckPolicies() != null) {
                int numberOfpolicies = 0;
                for (LbHealthCheckPolicy healthcheckpolicy : rule.getHealthCheckPolicies()) {
                    if (!healthcheckpolicy.isRevoked()) {
                        numberOfpolicies++;
                    }
                }
                if (numberOfpolicies > 1) {
                    throw new InvalidParameterValueException("Invalid healthcheck policy, list should contain at maximum one");
                }
            }

            // Port mapping
            List<String> ports = new ArrayList<String>();
            ports.add(rule.getSourcePortStart() + ":" + rule.getDefaultPortStart());
            if (rule.getAdditionalPortMap() != null) {
                for (String portMap : rule.getAdditionalPortMap()) {
                    // Right format of ports has already been validated in validateLBRule()
                    ports.add(portMap);
                }
            }

            // Reals
            List<GloboNetworkVipResponse.Real> realList = new ArrayList<GloboNetworkVipResponse.Real>();
            if(rule.getDestinations() != null) {
                for (LbDestination destVM : rule.getDestinations()) {
                    VMInstanceVO vm = _vmDao.findById(destVM.getInstanceId());
                    if (vm != null) {
                        GloboNetworkVipResponse.Real real = new GloboNetworkVipResponse.Real();
                        real.setIp(destVM.getIpAddress());
                        real.setVmName(getEquipNameFromUuid(vm.getUuid()));
                        real.setPorts(ports);
                        real.setRevoked(destVM.isRevoked());

                        GloboNetworkNetworkVO globoNetworkRealNetworkVO = _globoNetworkNetworkDao.findByNetworkId(destVM.getNetworkId());
                        if (globoNetworkRealNetworkVO == null) {
                            throw new InvalidParameterValueException("Could not obtain mapping for network " + destVM.getNetworkId() + " and VM " + destVM.getInstanceId()
                                    + " in GloboNetwork.");
                        }
                        real.setEnvironmentId(globoNetworkRealNetworkVO.getGloboNetworkEnvironmentId());
                        realList.add(real);

                        if (destVM.isRevoked()) {
                            revokeAnyVM = true;
                        }
                    } else {
                        throw new InvalidParameterValueException("Could not find VM with id " + destVM.getInstanceId());
                    }
                }
            }

            final AddOrRemoveVipInGloboNetworkCommand cmd = new AddOrRemoveVipInGloboNetworkCommand();
            // Vip Id null means new vip, otherwise vip will be updated.
            cmd.setVipId(gnIpDetail.getGloboNetworkVipId());
            // VIP infos
            cmd.setHost(rule.getName());
            cmd.setCache(rule.getCache());
            cmd.setIpv4(rule.getSourceIp().addr());
            cmd.setVipEnvironmentId(gnLbNetworkVO.getGloboNetworkLoadBalancerEnvironmentId());
            cmd.setPorts(ports);
            cmd.setBusinessArea(account.getAccountName());
            cmd.setServiceName(rule.getName());

            // Options and parameters
            cmd.setMethodBal(rule.getAlgorithm());
            cmd.setPersistencePolicy(rule.getStickinessPolicies() == null || rule.getStickinessPolicies().isEmpty() ? null : rule.getStickinessPolicies().get(0));
            cmd.setHealthcheckPolicy(rule.getHealthCheckPolicies() == null || rule.getHealthCheckPolicies().isEmpty() ? null : rule.getHealthCheckPolicies().get(0));
            cmd.setRuleState(rule.getState());

            // Reals infos
            cmd.setRealList(realList);

            Answer answer = GloboNetworkManager.this.callCommand(cmd, network.getDataCenterId());

            if (gnIpDetail.getGloboNetworkVipId() == null && !FirewallRule.State.Revoke.equals(rule.getState())) {
                // persist vip id information if not set
                GloboNetworkVipResponse vipResponse = (GloboNetworkVipResponse)answer;

                gnIpDetail.setGloboNetworkVipId(vipResponse.getId());
                _globoNetworkIpDetailDao.persist(gnIpDetail);
            } else if (gnIpDetail.getGloboNetworkVipId() != null && FirewallRule.State.Revoke.equals(rule.getState())) {
                gnIpDetail.setGloboNetworkVipId(null);
                _globoNetworkIpDetailDao.persist(gnIpDetail);
            }

        } catch (Exception e) {
            // Convert all exceptions to ResourceUnavailable to user have feedback of what happens. All others exceptions
            // only show 'error'
            throw new ResourceUnavailableException("Error applying loadbalancer rules. lb uuid=" + rule.getUuid(), DataCenter.class, network.getDataCenterId(), e);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }

        if (_networkManager.isProviderForNetwork(Provider.GloboDns, network.getId())
                && _networkManager.isProviderEnabledInPhysicalNetwork(network.getPhysicalNetworkId(), Provider.GloboDns.getName())) {
            // If GloboDNS provider is enabled in this network, register/remove DNS name for this load balancer

            // First of all, find the correct LB domain and LB record
            String allowedDomainsOpt = GloboNetworkLBAllowedSuffixes.value();
            List<String> allowedDomains = new ArrayList<String>();
            if (allowedDomainsOpt != null && !allowedDomainsOpt.equals("")) {
                allowedDomains = Arrays.asList(allowedDomainsOpt.split(","));
            }

            String lbDomain = null;
            for (String allowedDomain : allowedDomains) {
                // Remove any whitespaces
                allowedDomain = allowedDomain.trim();
                // Insert the '.' before the domain, if it's not there yet
                allowedDomain = allowedDomain.startsWith(".") ? allowedDomain : "." + allowedDomain;
                if (rule.getName().endsWith(allowedDomain)) {
                    lbDomain = allowedDomain.substring(1); // Remove '.' again for later use in GloboDNS provider
                    break;
                }
            }

            // lbDomain was already validated in validateLbRule. if it's null, there's something wrong
            if (lbDomain == null) {
                throw new ResourceUnavailableException("Load balancer name/domain is invalid", DataCenter.class, network.getDataCenterId());
            }

            // Strip domain from rule name to get only record name
            String lbRecord = getLbRecord(rule.getName(), lbDomain);

            if (rule.getState() == FirewallRule.State.Add && !revokeAnyVM) {
                // If LB is Add and all VMs are Add, then it's first time creating LB, create DNS

                return _globoDnsService.createDnsRecordForLoadBalancer(lbDomain, lbRecord, rule.getSourceIp().addr(), network.getDataCenterId());

            } else if (rule.getState() == FirewallRule.State.Revoke) {
                // If LB is Revoke, then remove DNS

                return _globoDnsService.removeDnsRecordForLoadBalancer(lbDomain, lbRecord, rule.getSourceIp().addr(), network.getDataCenterId());
            } else {
                // Other cases included here are LB is Add and a VM is revoke (removing VM from LB)
                // which should not affect DNS records for LB. Let it pass through
            }
        } else {
            s_logger.warn("Creating Load Balancer without registering DNS because network offering does not have GloboDNS as provider");
        }
        return true;
    }

    private String getLbRecord(String fullLbName, String lbDomain) {
        return fullLbName.substring(0, fullLbName.indexOf(lbDomain) - 1); // -1 is to remove '.' between record and domain
    }

    @Override
    public boolean validateLBRule(Network network, LoadBalancingRule rule) {
        // Validate params
        if (network == null || rule == null) {
            return false;
        }

        GloboNetworkNetworkVO globoNetworkNetworkVO = _globoNetworkNetworkDao.findByNetworkId(network.getId());
        if (globoNetworkNetworkVO == null) {
            throw new InvalidParameterValueException("GloboNetwork Load Balancer work only with networks managed by GloboNetwork. NetworkId=" + network.getId());
        }

        // check additional networks
        if (rule.getAdditionalNetworks() != null) {
            for (Long networkId : rule.getAdditionalNetworks()) {
                GloboNetworkNetworkVO additionalGNNetworkVO = _globoNetworkNetworkDao.findByNetworkId(networkId);
                if (additionalGNNetworkVO == null) {
                    throw new InvalidParameterValueException("GloboNetwork Load Balancer work only with networks managed by GloboNetwork. NetworkId=" + network.getId());
                }
            }
        }

        this.validatePortMaps(rule);

        IPAddressVO ipVO = _ipAddrDao.findByIpAndNetworkId(rule.getNetworkId(), rule.getSourceIp().addr());
        if (ipVO == null) {
            throw new InvalidParameterValueException("Ip " + rule.getSourceIp().addr() + " is not associate with network " + rule.getNetworkId());
        }

        GloboNetworkIpDetailVO gnIpDetail = _globoNetworkIpDetailDao.findByIp(ipVO.getId());
        if (gnIpDetail == null) {
            throw new InvalidParameterValueException("Ip " + rule.getSourceIp().addr() + " (id " + ipVO.getId() + ") is not associate with globo network");
        } else if (gnIpDetail.getGloboNetworkEnvironmentRefId() == null) {
            throw new InvalidParameterValueException("Ip " + rule.getSourceIp().addr() + " (id " + ipVO.getId() + ") can't be used to load balancing");
        }

        // Stickness/Persistence
        if (rule.getStickinessPolicies() != null && rule.getStickinessPolicies().size() > 1) {
            throw new InvalidParameterValueException("Invalid stickness policy, list should contain only one");
        }

        // Healthcheck
        if (rule.getHealthCheckPolicies() != null && rule.getHealthCheckPolicies().size() > 1) {
            throw new InvalidParameterValueException("Invalid healthcheck policy, list should contain only one");
        }

        // Get VIP info
        if (gnIpDetail.getGloboNetworkVipId() != null) {
            boolean isv6 = this.isNetworkv6(network);

            GetVipInfoFromGloboNetworkCommand cmd = new GetVipInfoFromGloboNetworkCommand(gnIpDetail.getGloboNetworkVipId(), isv6);
            Answer answer = this.callCommand(cmd, network.getDataCenterId(), false);
            if (answer != null && answer.getResult()) {
                GloboNetworkVipResponse globoNetworkVip = (GloboNetworkVipResponse)answer;
                // TODO Store ref between lb id and globonetwork vip id to solve this situation.
                // String port = String.format("%d:%d", rule.getSourcePortStart(), rule.getDefaultPortStart());
                // if (!port.equals(globoNetworkVip.getPorts().get(0))) {
                if (!rule.getSourceIp().equals(globoNetworkVip.getIp())) {
                    throw new InvalidParameterValueException("You can create only 1 lb rule per IP.");
                }
                String method = globoNetworkVip.getMethod();
                // Translating to GloboNetwork balancing methods
                if ("least-conn".equals(method)) {
                    method = "leastconn";
                } else if ("round-robin".equals(method)) {
                    method = "roundrobin";
                }
                if (globoNetworkVip.getCreated() && !rule.getAlgorithm().equals(method)) {
                    throw new InvalidParameterValueException("It is not allowed to change balancing method in GloboNetwork.");
                }

                if (!globoNetworkVip.getName().equals(rule.getName())) {
                    throw new InvalidParameterValueException("It is not allowed to change load balancer name in GloboNetwork");
                }
            }
        }

        if (_networkManager.isProviderForNetwork(Provider.GloboDns, network.getId())
                && _networkManager.isProviderEnabledInPhysicalNetwork(network.getPhysicalNetworkId(), Provider.GloboDns.getName())) {
            // If GloboDNS provider is enabled in this network, validate LB name

            // First of all, find the correct LB domain and LB record
            String allowedDomainsOpt = GloboNetworkLBAllowedSuffixes.value();
            List<String> allowedDomains = new ArrayList<String>();
            if (allowedDomainsOpt != null && !allowedDomainsOpt.equals("")) {
                allowedDomains = Arrays.asList(allowedDomainsOpt.split(","));
            }

            String lbDomain = null;
            for (String allowedDomain : allowedDomains) {
                // Remove any whitespaces
                allowedDomain = allowedDomain.trim();
                // Insert the '.' before the domain, if it's not there yet
                allowedDomain = allowedDomain.startsWith(".") ? allowedDomain : "." + allowedDomain;
                if (rule.getName().endsWith(allowedDomain)) {
                    lbDomain = allowedDomain.substring(1);
                    break;
                }
            }

            if (lbDomain == null) {
                // That means there was no match
                // LB cannot be created
                throw new InvalidParameterValueException("Load balancer name " + rule.getName() + " is not in the allowed list for domains.");
            }

            // Finally, validate LB record in GloboDNS
            String lbRecord = getLbRecord(rule.getName(), lbDomain);
            return _globoDnsService.validateDnsRecordForLoadBalancer(lbDomain, lbRecord, rule.getSourceIp().addr(), network.getDataCenterId());
        } else {
            s_logger.warn("Allowing creation of Load Balancer without registering DNS because network offering does not have GloboDNS as provider");
        }

        return true;
    }

    @Override
    public List<String> listGloboNetworkLBCacheGroups(Long lbEnvironmentIdRef, Long networkId) {
        if (lbEnvironmentIdRef == null) {
            throw new InvalidParameterValueException("Invalid LB Environment ID");
        }

        GloboNetworkLoadBalancerEnvironment gnLbEnv = _globoNetworkLBEnvironmentDao.findById(lbEnvironmentIdRef);
        if (gnLbEnv == null) {
            throw new InvalidParameterValueException("Could not find mapping between lb environment " + gnLbEnv.getGloboNetworkEnvironmentRefId());
        }

        if (networkId == null) {
            throw new InvalidParameterValueException("Invalid Network ID");
        }

        Network network = _networkManager.getNetwork(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Cannot find network with ID : " + networkId);
        }

        ListGloboNetworkLBCacheGroupsCommand cmd = new ListGloboNetworkLBCacheGroupsCommand();
        cmd.setLBEnvironmentId(gnLbEnv.getGloboNetworkLoadBalancerEnvironmentId());

        Answer answer = callCommand(cmd, network.getDataCenterId());

        List<String> lbCacheGroups = ((GloboNetworkCacheGroupsResponse)answer).getCacheGroups();
        return lbCacheGroups;

    }

    protected void validatePortMaps(LoadBalancingRule rule) {
        List<Integer> portsAlreadyMapped = new ArrayList<>();
        portsAlreadyMapped.add(rule.getSourcePortStart());
        if (rule.getAdditionalPortMap() != null) {
            for (String portMap : rule.getAdditionalPortMap()) {
                String[] portMapArray = portMap.split(":");
                if (portMapArray.length != 2) {
                    throw new InvalidParameterValueException("Additional port mapping is invalid, should be in the form '80:8080,443:8443'");
                }
                Integer lbPort;
                Integer realPort;
                try {
                    lbPort = Integer.valueOf(portMapArray[0].trim());
                    realPort = Integer.valueOf(portMapArray[1].trim());
                }catch(NumberFormatException e){
                    throw new InvalidParameterValueException("Additional port mapping is invalid. Only numbers are permitted");
                }
                if (portsAlreadyMapped.contains(lbPort)) {
                    throw new InvalidParameterValueException("Additional port mapping is invalid. Duplicated Load Balancer port");
                }
                portsAlreadyMapped.add(lbPort);
            }
        }
    }

    @Override
    public LoadBalancer importGloboNetworkLoadBalancer(Long lbId, final Long networkId, Long projectId) {
        // Validate params
        if (lbId == null) {
            throw new InvalidParameterValueException("Invalid load balancer ID");
        }
        if (networkId == null) {
            throw new InvalidParameterValueException("Invalid network ID");
        }
        final Network network = _ntwkDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Could not find a network with id " + networkId);
        }

        // First of all, check user permission
        final Account caller = CallContext.current().getCallingAccount();
        // Perform account permission check on network
        _accountMgr.checkAccess(caller, null, true, network);

        // If project was set, this LB belongs to that project
        // Otherwise, the caller is the owner
        final Account owner;
        if (projectId != null) {
            Project project = _projectMgr.getProject(projectId);
            if (project != null) {
                owner = _accountMgr.getAccount(project.getProjectAccountId());
            } else {
                throw new InvalidParameterValueException("Could not find project with id " + projectId);
            }
        } else {
            owner = caller;
        }

        // Get VIP info from GloboNetwork
        boolean isv6 = this.isNetworkv6(network);

        GetVipInfoFromGloboNetworkCommand cmd = new GetVipInfoFromGloboNetworkCommand(lbId, isv6);
        Answer answer = this.callCommand(cmd, network.getDataCenterId());

        if (answer != null && answer.getResult()) {
            final GloboNetworkVipResponse globoNetworkLB = (GloboNetworkVipResponse)answer;

            // Check LB IP address
            PortableIpVO portableVO = _portableIpDao.findByIpAddress(globoNetworkLB.getIp());
            if (portableVO == null) {
                throw new CloudRuntimeException("Portable IP " + globoNetworkLB.getIp() + " is not registered within Cloudstack");
            } else if (portableVO.getState() != PortableIp.State.Free) {
                throw new CloudRuntimeException("IP " + globoNetworkLB.getIp() + " is not free to be used in Cloudstack");
            }

            if (globoNetworkLB.getPorts() == null || globoNetworkLB.getPorts().size() == 0) {
                throw new CloudRuntimeException("Invalid port mapping for LB. It is necessary to have at least 1 port mapping for a load balancer");
            }
            final String[] globoNetworkPorts = globoNetworkLB.getPorts().get(0).split(":");
            if (globoNetworkPorts[0] == "" || globoNetworkPorts[1] == "") {
                throw new CloudRuntimeException("Invalid port mapping for LB: " + globoNetworkLB.getPorts().get(0));
            }
            final List<String> additionalPortMapList = (globoNetworkLB.getPorts().size() > 1) ? globoNetworkLB.getPorts().subList(1, globoNetworkLB.getPorts().size()) : null;

            final String algorithm;
            if ("least-conn".equals(globoNetworkLB.getMethod())) {
                algorithm = "leastconn";
            } else if ("round-robin".equals(globoNetworkLB.getMethod())) {
                algorithm = "roundrobin";
            } else {
                throw new CloudRuntimeException("Invalid balancing method: " + globoNetworkLB.getMethod());
            }

            try {
                LoadBalancer lb = Transaction.execute(new TransactionCallbackWithException<LoadBalancer, CloudException>() {

                    public LoadBalancer doInTransaction(TransactionStatus status) throws CloudException {
                        // Allocate IP in Cloudstack
                        Long zoneId = network.getDataCenterId();
                        IPAddressVO ip = (IPAddressVO)_ipAddrMgr.allocatePortableIp(owner, caller, zoneId, networkId, null, globoNetworkLB.getIp());
                        VlanVO vlan = _vlanDao.findById(ip.getVlanId());
                        PublicIp publicIp = PublicIp.createFromAddrAndVlan(ip, vlan);

                        // register ip
                        // Retrieve glbEnvId from network
                        GloboNetworkNetworkVO globoNetworkNetworkVO = _globoNetworkNetworkDao.findByNetworkId(networkId);
                        if (globoNetworkNetworkVO == null) {
                            throw new InvalidParameterValueException("Unable to find mapping for networkId " + networkId);
                        }
                        Long glbEnvId = globoNetworkNetworkVO.getGloboNetworkEnvironmentId();

                        // Retrieve napiEnvironment from DB
                        GloboNetworkEnvironmentVO globoNetworkEnvironment = _globoNetworkEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(network.getPhysicalNetworkId(),
                                glbEnvId);

                        if (globoNetworkEnvironment == null) {
                            // No physical network/environment pair registered in the database.
                            throw new InvalidParameterValueException("Unable to find a relationship between physical network=" + network.getPhysicalNetworkId()
                                    + " and GloboNetwork environment=" + glbEnvId);
                        }

                        GloboNetworkLoadBalancerEnvironment globoNetworkLBEnvironment = _globoNetworkLBEnvironmentDao.findByEnvironmentRefAndLBNetwork(
                                globoNetworkEnvironment.getId(), globoNetworkLB.getLbEnvironmentId());
                        if (globoNetworkLBEnvironment == null) {
                            throw new InvalidParameterValueException("Could not find LB environment " + globoNetworkLB.getLbEnvironmentId());
                        }

                        GloboNetworkIpDetailVO gnIpDetail = new GloboNetworkIpDetailVO(ip.getId(), globoNetworkLB.getIpId());
                        gnIpDetail.setGloboNetworkEnvironmentRefId(globoNetworkLBEnvironment.getId());
                        gnIpDetail.setGloboNetworkVipId(globoNetworkLB.getId());
                        _globoNetworkIpDetailDao.persist(gnIpDetail);

                        // Create LB
                        LoadBalancer lb = _lbMgr.createPublicLoadBalancer(null, globoNetworkLB.getName(), globoNetworkLB.getDetails(), Integer.parseInt(globoNetworkPorts[0], 10),
                                Integer.parseInt(globoNetworkPorts[1], 10), publicIp.getId(), NetUtils.TCP_PROTO, algorithm, false, CallContext.current(), null, Boolean.TRUE, null, null);

                        // Set additional port mappings for LB
                        if (additionalPortMapList != null) {
                            for(String additionalPortMapStr : additionalPortMapList) {
                                if (additionalPortMapStr.split(":").length != 2) {
                                    throw new InvalidParameterValueException("Invalid additional port mapping");
                                }
                                Integer publicPort = Integer.valueOf(additionalPortMapStr.split(":")[0]);
                                Integer privatePort = Integer.valueOf(additionalPortMapStr.split(":")[1]);
                                LoadBalancerPortMapVO lbPortMapVO = new LoadBalancerPortMapVO(lb.getId(), publicPort, privatePort);
                                _lbPortMapDao.persist(lbPortMapVO);
                            }
                        }

                        // Assign VMs that are managed in Cloudstack
                        List<Long> instancesToAdd = new ArrayList<Long>();
                        Map<Long, List<String>> vmIdIpMap = new HashMap<Long, List<String>>();
                        for (GloboNetworkVipResponse.Real real : globoNetworkLB.getReals()) {
                            // For each real, find it in Cloudstack and add it to LB
                            // If it's not found, ignore it (VM not managed by Cloudstack)
                            Nic nic = _nicDao.findByIp4AddressAndNetworkId(real.getIp(), networkId);
                            if (nic == null) {
                                continue;
                            }
                            instancesToAdd.add(nic.getInstanceId());
                            vmIdIpMap.put(nic.getInstanceId(), Arrays.asList(nic.getIp4Address()));
                        }
                        _lbService.assignToLoadBalancer(lb.getId(), instancesToAdd, vmIdIpMap);
                        return lb;
                    }
                });

                return lb;

            } catch (CloudException e) {
                throw new CloudRuntimeException("There was an error when allocating ip " + globoNetworkLB.getIp() + " in Cloudstack");
            }
        }
        return null;
    }

    @Override
    public String getDomainSuffix() {
        return GloboNetworkDomainSuffix.value();
    }

    @Override
    public boolean isSupportedCustomNetworkDomain() {
        return !StringUtils.isNotBlank(GloboNetworkDomainPattern.value());
    }

    @Override
    public List<DataCenter> getAllZonesThatProviderAreEnabled() {
        List<DataCenter> zonesEnabled = new ArrayList<DataCenter>();
        for (DataCenter dc : _dcDao.listEnabledZones()) {
            if (_networkManager.isProviderEnabledInZone(dc.getId(), Provider.GloboNetwork.getName())) {
                zonesEnabled.add(dc);
            }
        }
        return zonesEnabled;
    }

    private boolean isNetworkv6(Network network) {
        return network.getIp6Gateway() != null;
    }

    @Override
    public List<String> listAllowedLbSuffixes() {
        String allowedDomainsOpt = GloboNetworkLBAllowedSuffixes.value();
        List<String> allowedDomains = new ArrayList<String>();

        for (String allowedDomain : allowedDomainsOpt.split(",")) {
            // Remove any whitespaces
            allowedDomain = allowedDomain.trim();
            if (allowedDomainsOpt != null && !allowedDomainsOpt.equals("")) {
                // Insert the '.' before the domain, if it's not there yet
                allowedDomain = allowedDomain.startsWith(".") ? allowedDomain : "." + allowedDomain;
                allowedDomains.add(allowedDomain);
            }
        }
        return allowedDomains;
    }
}