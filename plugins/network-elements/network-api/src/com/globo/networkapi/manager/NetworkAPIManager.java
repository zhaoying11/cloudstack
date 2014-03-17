package com.globo.networkapi.manager;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
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
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.NetworkManager;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.org.Grouping;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ConfigurationServer;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.DomainManager;
import com.cloud.user.UserContext;
import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Journal;
import com.cloud.utils.Pair;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.globo.networkapi.NetworkAPIEnvironmentVO;
import com.globo.networkapi.NetworkAPINetworkVO;
import com.globo.networkapi.api.AddNetworkAPIEnvironmentCmd;
import com.globo.networkapi.api.AddNetworkApiHostCmd;
import com.globo.networkapi.api.AddNetworkApiVlanCmd;
import com.globo.networkapi.api.AddNetworkViaNetworkApiCmd;
import com.globo.networkapi.api.ListAllEnvironmentsFromNetworkApiCmd;
import com.globo.networkapi.api.ListNetworkApiEnvironmentsCmd;
import com.globo.networkapi.commands.ActivateNetworkCommand;
import com.globo.networkapi.commands.CreateNewVlanInNetworkAPICommand;
import com.globo.networkapi.commands.DeallocateVlanFromNetworkAPICommand;
import com.globo.networkapi.commands.GetVlanInfoFromNetworkAPICommand;
import com.globo.networkapi.commands.ListAllEnvironmentsFromNetworkAPICommand;
import com.globo.networkapi.commands.RemoveNetworkInNetworkAPICommand;
import com.globo.networkapi.commands.ValidateNicInVlanCommand;
import com.globo.networkapi.dao.NetworkAPIEnvironmentDao;
import com.globo.networkapi.dao.NetworkAPINetworkDao;
import com.globo.networkapi.model.Environment;
import com.globo.networkapi.resource.NetworkAPIResource;
import com.globo.networkapi.response.NetworkAPIAllEnvironmentResponse;
import com.globo.networkapi.response.NetworkAPIVlanResponse;

public class NetworkAPIManager implements NetworkAPIService, PluggableService {

	private static final Logger s_logger = Logger
			.getLogger(NetworkAPIManager.class);

	static final int NUMBER_OF_RESERVED_IPS_FROM_START = 5;
	static final int NUMBER_OF_RESERVED_IPS_BEFORE_END = 5;

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
	PhysicalNetworkDao _physicalNetworkDao;
	@Inject
	NetworkOfferingDao _networkOfferingDao;
	@Inject
	UserDao _userDao;
	@Inject
	NetworkDao _ntwkDao;
	@Inject
	NetworkServiceMapDao _ntwkSrvcDao;
	@Inject
	NetworkAPINetworkDao _napiNetworkDao;
	@Inject
	NetworkAPIEnvironmentDao _napiEnvironmentDao;
	
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
	NetworkManager _networkMgr;
	@Inject
	AccountManager _accountMgr;
	@Inject
	ConfigurationServer _configServer;
	@Inject
	NetworkService _ntwSvc;
	
	@Override
	public boolean canEnable(Long physicalNetworkId) {
		if (physicalNetworkId == null) {
			return false;
		}
		List<NetworkAPIEnvironmentVO> list = _napiEnvironmentDao.listByPhysicalNetworkId(physicalNetworkId);
		if (list.isEmpty()) {
			throw new CloudRuntimeException("Before enable NetworkAPI you must add NetworkAPI Environment to your physical interface");
		}
		return true;
	}

	@Override
    @DB
	public Network createNetwork(String name, String displayText, Long zoneId,
			Long networkOfferingId, Long napiEnvironmentId,
			String networkDomain, ACLType aclType, String accountName,
			Long projectId, Long domainId, Boolean subdomainAccess,
			Boolean displayNetwork, Long aclId)
			throws ResourceAllocationException, ResourceUnavailableException,
			ConcurrentOperationException, InsufficientCapacityException {

		// FIXME Very important: Include permission checks before creating network in networkapi

		DataCenter zone = _dcDao.findById(zoneId);
		if (zone == null) {
			throw new InvalidParameterValueException(
					"Specified zone id was not found");
		}
		
		Long physicalNetworkId = null;
		if (napiEnvironmentId != null) {
			NetworkAPIEnvironmentVO napiEnvironmentVO = null;
			for (PhysicalNetwork pNtwk : _physicalNetworkDao.listByZone(zoneId)) {
				napiEnvironmentVO = _napiEnvironmentDao.findByPhysicalNetworkIdAndEnvironmentId(pNtwk.getId(), napiEnvironmentId);
				if (napiEnvironmentVO != null) {
					break;
				}
			}
			if (napiEnvironmentVO == null) {
				throw new InvalidParameterValueException("Unable to find a relationship between NetworkApi environment and physical network");
			}
			physicalNetworkId = napiEnvironmentVO.getPhysicalNetworkId();
		} else {
			throw new InvalidParameterValueException("NetworkApi EnviromentId was not found");
		}
		
		Answer answer = createNewVlan(zoneId, name, displayText, napiEnvironmentId);

		NetworkAPIVlanResponse response = (NetworkAPIVlanResponse) answer;
		Long napiVlanId = response.getVlanId();

		Network network = null;
		
		try {
			network = this.createNetworkFromNetworkAPIVlan(napiVlanId, zoneId,
				networkOfferingId, physicalNetworkId, networkDomain, aclType,
				accountName, projectId, domainId, subdomainAccess,
				displayNetwork, aclId);
		} catch (Exception e) {
			// Exception when creating network in Cloudstack. Roll back transaction in NetworkAPI
			s_logger.error("Reverting network creation in Network API due to error creating network", e);
			this.deallocateVlanFromNetworkAPI(zoneId, napiVlanId);
			
			throw new ResourceAllocationException(e.getLocalizedMessage(), ResourceType.network);
		}
		
		return network;
	}

	@Override
    @DB
	public Network createNetworkFromNetworkAPIVlan(Long vlanId, Long zoneId,
			Long networkOfferingId, Long physicalNetworkId,
			String networkDomain, ACLType aclType, String accountName, Long projectId,
			Long domainId, Boolean subdomainAccess, Boolean displayNetwork,
			Long aclId) throws ResourceUnavailableException,
			ResourceAllocationException, ConcurrentOperationException,
			InsufficientCapacityException {

		Account caller = UserContext.current().getCaller();

        // Only domain and account ACL types are supported in Action.
        if (aclType == null || !(aclType == ACLType.Domain || aclType == ACLType.Account)) {
            throw new InvalidParameterValueException("AclType should be " + ACLType.Domain + " or " +
            		ACLType.Account + " for network of type " + Network.GuestType.Shared);
        }
		
		boolean isDomainSpecific = true;

		// Validate network offering
		NetworkOfferingVO ntwkOff = _networkOfferingDao
				.findById(networkOfferingId);
		if (ntwkOff == null || ntwkOff.isSystemOnly()) {
			InvalidParameterValueException ex = new InvalidParameterValueException(
					"Unable to find network offering by specified id");
			if (ntwkOff != null) {
				ex.addProxyObject(ntwkOff.getUuid(), "networkOfferingId");
			}
			throw ex;
		} else if (GuestType.Shared != ntwkOff.getGuestType()) {
			InvalidParameterValueException ex = new InvalidParameterValueException(
					"NetworkAPI can handle only network offering with guest type shared");
			if (ntwkOff != null) {
				ex.addProxyObject(ntwkOff.getUuid(), "networkOfferingId");
			}
			throw ex;
		}
		// validate physical network and zone
		// Check if physical network exists
		PhysicalNetwork pNtwk = null;
		if (physicalNetworkId != null) {
			pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
			if (pNtwk == null) {
				throw new InvalidParameterValueException(
						"Unable to find a physical network having the specified physical network id");
			}
		} else {
			throw new InvalidParameterValueException(
					"invalid physicalNetworkId " + physicalNetworkId);
		}

		if (zoneId == null) {
			zoneId = pNtwk.getDataCenterId();
		}

		DataCenter zone = _dcDao.findById(zoneId);
		if (zone == null) {
			throw new InvalidParameterValueException(
					"Specified zone id was not found");
		}

		if (Grouping.AllocationState.Disabled == zone.getAllocationState()
				&& !_accountMgr.isAdmin(caller.getType())) {
			// See DataCenterVO.java
			PermissionDeniedException ex = new PermissionDeniedException(
					"Cannot perform this operation since specified Zone is currently disabled");
			ex.addProxyObject(zone.getUuid(), "zoneId");
			throw ex;
		}

		if (domainId != null) {
			DomainVO domain = _domainDao.findById(domainId);
			if (domain == null) {
				throw new InvalidParameterValueException(
						"Unable to find domain by specified id");
			}
			_accountMgr.checkAccess(caller, domain);
		}

		if (_configMgr.isOfferingForVpc(ntwkOff)) {
			throw new InvalidParameterValueException(
					"Network offering can be used for VPC networks only");
		}
		if (ntwkOff.getInternalLb()) {
			throw new InvalidParameterValueException(
					"Internal Lb can be enabled on vpc networks only");
		}

		Account owner = null;
		if ((accountName != null && domainId != null) || projectId != null) {
			owner = _accountMgr.finalizeOwner(caller, accountName, domainId,
					projectId);
		} else {
			owner = caller;
		}

		UserContext.current().setAccountId(owner.getAccountId());

		// /////// NetworkAPI specific code ///////

		// Get VlanInfo from NetworkAPI
		GetVlanInfoFromNetworkAPICommand cmd = new GetVlanInfoFromNetworkAPICommand();
		cmd.setVlanId(vlanId);

		NetworkAPIVlanResponse response;
		try {
			response = (NetworkAPIVlanResponse) callCommand(cmd, zoneId);
		} catch (ConfigurationException e) {
			// FIXME
			throw new CloudRuntimeException(e);
		}

		if (response == null || !response.getResult()) {
			String msg = "Unable to execute command "
					+ cmd.getClass().getSimpleName();
			s_logger.error(msg);
			// FIXME Understand this exception, and put more specific object
			throw new ResourceUnavailableException(msg, DataCenter.class,
					zoneId);
		}

		long networkAddresLong = response.getNetworkAddress().toLong();
		String networkAddress = NetUtils.long2Ip(networkAddresLong);
		String netmask = response.getMask().ip4();
		String cidr = NetUtils.ipAndNetMaskToCidr(networkAddress, netmask);

		String ranges[] = NetUtils.ipAndNetMaskToRange(networkAddress, netmask);
		String gateway = ranges[0];
		String startIP = NetUtils.long2Ip(NetUtils.ip2Long(ranges[0])
				+ NUMBER_OF_RESERVED_IPS_FROM_START);
		String endIP = NetUtils.long2Ip(NetUtils.ip2Long(ranges[1])
				- NUMBER_OF_RESERVED_IPS_BEFORE_END);
		Long vlanNum = response.getVlanNum();
		// NO IPv6 support yet
		String startIPv6 = null, endIPv6 = null, ip6Gateway = null, ip6Cidr = null;

		s_logger.info("Creating network with name " + response.getVlanName()
				+ " (" + response.getVlanId() + "), network " + networkAddress
				+ " gateway " + gateway + " startIp " + startIP + " endIp "
				+ endIP + " cidr " + cidr);
		// /////// End of NetworkAPI specific code ///////

		Transaction txn = Transaction.currentTxn();
		txn.start();

		Long sharedDomainId = null;
		if (isDomainSpecific) {
			if (domainId != null) {
				sharedDomainId = domainId;
			} else {
				sharedDomainId = owner.getDomainId();
				subdomainAccess = true;
			}
		}

		Network network = _networkMgr.createGuestNetwork(
				networkOfferingId.longValue(), response.getVlanName(),
				response.getVlanDescription(), gateway, cidr,
				String.valueOf(response.getVlanNum()), networkDomain, owner,
				sharedDomainId, pNtwk, zoneId, aclType, subdomainAccess, 
				null, // vpcId,
				ip6Gateway,
				ip6Cidr,
				displayNetwork,
				null // isolatedPvlan
				);
		
		// Delete other network related to this, except NetworkAPIGuru
		// FIXME Improve this search
		for (NetworkVO otherNetwork : _ntwkDao.listAll()) {
			if (otherNetwork.getRelated() == network.getId()) {
				if ("NetworkAPIGuru".equalsIgnoreCase(otherNetwork.getGuruName())) {
					// FIXME Fix related
					s_logger.info("Network with NetworkAPI found " + network.getId());
					network = otherNetwork;
				} else {
					s_logger.info("Destroy network " + otherNetwork.getId() + " " + otherNetwork.getName() + " " + otherNetwork.getGuruName());
					_ntwSvc.deleteNetwork(otherNetwork.getId());
				}
			}
		}

		// Save relashionship with napi and network
		NetworkAPINetworkVO napiNetworkVO = new NetworkAPINetworkVO(vlanId,
				network.getId());
		napiNetworkVO = _napiNetworkDao.persist(napiNetworkVO);

		// if (caller.getType() == Account.ACCOUNT_TYPE_ADMIN || caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
			// Create vlan ip range
			_configMgr.createVlanAndPublicIpRange(pNtwk.getDataCenterId(),
					network.getId(), physicalNetworkId, false, (Long) null,
					startIP, endIP, gateway, netmask, vlanNum.toString(), null,
					startIPv6, endIPv6, ip6Gateway, ip6Cidr);
		// }

		txn.commit();

		// if the network offering has persistent set to true, implement the
		// network
		if (ntwkOff.getIsPersistent()) {
			try {
				if (network.getState() == Network.State.Setup) {
					s_logger.debug("Network id=" + network.getId()
							+ " is already provisioned");
					return network;
				}
				DeployDestination dest = new DeployDestination(zone, null,
						null, null);
				UserVO callerUser = _userDao.findById(UserContext.current()
						.getCallerUserId());
				Journal journal = new Journal.LogJournal("Implementing "
						+ network, s_logger);
				ReservationContext context = new ReservationContextImpl(UUID
						.randomUUID().toString(), journal, callerUser, caller);
				s_logger.debug("Implementing network "
						+ network
						+ " as a part of network provision for persistent network");
				Pair<NetworkGuru, NetworkVO> implementedNetwork = _networkMgr
						.implementNetwork(network.getId(), dest, context);
				if (implementedNetwork.first() == null) {
					s_logger.warn("Failed to provision the network " + network);
				}
				network = implementedNetwork.second();
			} catch (ResourceUnavailableException ex) {
				s_logger.warn("Failed to implement persistent guest network "
						+ network + "due to ", ex);
				CloudRuntimeException e = new CloudRuntimeException(
						"Failed to implement persistent guest network");
				e.addProxyObject(network.getUuid(), "networkId");
				throw e;
			}
		}
		return network;
	}

	protected NetworkAPIVlanResponse createNewVlan(Long zoneId, String name,
			String description, Long networkAPIEnvironmentId) {

		CreateNewVlanInNetworkAPICommand cmd = new CreateNewVlanInNetworkAPICommand();
		cmd.setVlanName(name);
		cmd.setVlanDescription(description);
		cmd.setNetworkAPIEnvironmentId(networkAPIEnvironmentId);

		Answer answer = null;
		try {
			answer = callCommand(cmd, zoneId);
			if (answer == null || !answer.getResult()) {
				throw new CloudRuntimeException(
						"Error creating VLAN in networkAPI: " + (answer == null ? "" : answer.getDetails()));
			}
		} catch (ConfigurationException e) {
			throw new CloudRuntimeException(
					"Error creating VLAN in networkAPI", e);
		}
		return (NetworkAPIVlanResponse) answer;
	}

	private Answer callCommand(Command cmd, Long zoneId)
			throws ConfigurationException {
		
		HostVO napiHost = getNetworkAPIHost(zoneId);
		if (napiHost != null) {
			return _agentMgr.easySend(napiHost.getId(), cmd);
		} else {
			return null;
		}
	}
	
	private HostVO getNetworkAPIHost(Long zoneId) {
		return _hostDao.findByTypeNameAndZoneId(zoneId, Provider.NetworkAPI.getName(), Type.L2Networking);
	}

	@Override
	public Network validateNic(NicProfile nicProfile,
			VirtualMachineProfile<? extends VirtualMachine> vm,
			Network network, DeployDestination dest)
			throws InsufficientVirtualNetworkCapcityException,
			InsufficientAddressCapacityException {

		ValidateNicInVlanCommand cmd = new ValidateNicInVlanCommand();
		cmd.setNicIp(nicProfile.getIp4Address());
		cmd.setVlanId(getNapiVlanId(network.getId()));
		cmd.setVlanNum(Long.valueOf(getVlanNum(nicProfile.getBroadCastUri())));

		String msg = "Unable to validate nic " + nicProfile + " from VM " + vm;
		try {
			Answer answer = this.callCommand(cmd, network.getDataCenterId());
			if (answer == null || !answer.getResult()) {
				msg = answer == null ? msg : answer.getDetails();
				throw new InsufficientVirtualNetworkCapcityException(msg, Nic.class, nicProfile.getId());
			}
		} catch (ConfigurationException e) {
			throw new CloudRuntimeException(msg, e);
		}
		// everything is ok
		return network;
	}

	@Override
	public void implementNetwork(Network network) throws ConfigurationException {
		Long vlanId = getNapiVlanId(network.getId());
		if (vlanId == null) {
			throw new CloudRuntimeException("Inconsistency. Network "
					+ network.getName()
					+ " there is not relation with NetworkAPI");
		}

		GetVlanInfoFromNetworkAPICommand cmd = new GetVlanInfoFromNetworkAPICommand();
		cmd.setVlanId(vlanId);

		Answer answer = callCommand(cmd, network.getDataCenterId());
		if (answer == null || !answer.getResult()) {
			String errorDescription = answer == null ? "no description"
					: answer.getDetails();
			throw new CloudRuntimeException("Error getting VlanId " + vlanId
					+ " in NetworkAPI: " + errorDescription);
		}

		NetworkAPIVlanResponse vlanResponse = (NetworkAPIVlanResponse) answer;
		vlanResponse.getNetworkAddress();
		Long networkId = vlanResponse.getNetworkId();
		if (!vlanResponse.isActive()) {
			// Create network in equipment
			ActivateNetworkCommand cmd_creation = new ActivateNetworkCommand(vlanId,
					networkId);
			Answer creation_answer = callCommand(cmd_creation, network.getDataCenterId());
			if (creation_answer == null || !creation_answer.getResult()) {
				throw new CloudRuntimeException(
						"Unable to create network in NetworkAPI: VlanId "
								+ vlanId + " networkId " + networkId);
			}
			s_logger.info("Network ready to use: VlanId " + vlanId
					+ " networkId " + networkId);
		} else {
			s_logger.warn("Network already created in NetworkAPI: VlanId "
					+ vlanId + " networkId " + networkId);
		}
	}

	/**
	 * Returns VlanId (in NetworkAPI) given an Network. If network is not
	 * associated with NetworkAPI, <code>null</code> will be returned.
	 * 
	 * @param networkId
	 * @return
	 */
	private Long getNapiVlanId(Long networkId) {
		if (networkId == null) {
			return null;
		}
		return _napiNetworkDao.findByNetworkId(networkId).getNapiVlanId();
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
	public NetworkAPIEnvironmentVO addNetworkAPIEnvironment(Long physicalNetworkId, String name, Long napiEnvironmentId) {
		
		if (name == null || name.trim().isEmpty()) {
			throw new InvalidParameterValueException("Invalid name: " + name);
		}
		
		// validate physical network and zone
		// Check if physical network exists
		PhysicalNetwork pNtwk = null;
		if (physicalNetworkId != null) {
			pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
			if (pNtwk == null) {
				throw new InvalidParameterValueException(
						"Unable to find a physical network having the specified physical network id");
			}
		} else {
			throw new InvalidParameterValueException("Invalid physicalNetworkId: " + physicalNetworkId);
		}

		Long zoneId = pNtwk.getDataCenterId();
		
		// now, check if environment exists in NetworkAPI
		if (napiEnvironmentId != null) {
			Environment environment = getEnvironment(physicalNetworkId, napiEnvironmentId);
			if (environment == null) {
				throw new InvalidParameterValueException(
						"Unable to find in NetworkAPI an enviroment having the specified environment id");
			}
		} else {
			throw new InvalidParameterValueException("Invalid networkapi EnvironmentId: " + napiEnvironmentId);
		}
		
		// Check if there is a environment with same id or name in this zone.
		List<NetworkAPIEnvironmentVO> napiEnvironments = listNetworkAPIEnvironmentsFromDB(null, zoneId);
		for (NetworkAPIEnvironmentVO napiEnvironment: napiEnvironments) {
			if (napiEnvironment.getName().equalsIgnoreCase(name)) {
				throw new InvalidParameterValueException("NetworkAPI environment with name " + name + " already exists in zone " + zoneId);
			}
			if (napiEnvironment.getNapiEnvironmentId() == napiEnvironmentId) {
				throw new InvalidParameterValueException("NetworkAPI environment with environmentId " + napiEnvironmentId + " already exists in zoneId " + zoneId);
			}
		}
				
		Transaction txn = Transaction.currentTxn();
		txn.start();

	    NetworkAPIEnvironmentVO napiEnvironmentVO = new NetworkAPIEnvironmentVO(physicalNetworkId, name, napiEnvironmentId);
	    _napiEnvironmentDao.persist(napiEnvironmentVO);

	    txn.commit();
	    return napiEnvironmentVO;
	}

	@Override
	@DB
	public Host addNetworkAPIHost(Long physicalNetworkId, String username, String password, String url) {
		
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
				throw new InvalidParameterValueException(
						"Unable to find a physical network having the specified physical network id");
			}
		} else {
			throw new InvalidParameterValueException("Invalid physicalNetworkId: " + physicalNetworkId);
		}

		Long zoneId = pNtwk.getDataCenterId();

		Map<String, String> params = new HashMap<String, String>();
		params.put("guid", "networkapi");
		params.put("zoneId", String.valueOf(zoneId));
		params.put("name", Provider.NetworkAPI.getName());
		
		String readTimeout = _configServer.getConfigValue(Config.NetworkAPIReadTimeout.key(),
				Config.ConfigurationParameterScope.global.name(), null);
		String connectTimeout = _configServer.getConfigValue(Config.NetworkAPIConnectionTimeout.key(),
				Config.ConfigurationParameterScope.global.name(), null);
		String numberOfRetries = _configServer.getConfigValue(Config.NetworkAPINumberOfRetries.key(),
				Config.ConfigurationParameterScope.global.name(), null);

		params.put("url", url);
		params.put("username", username);
		params.put("password", password);
		params.put("readTimeout", readTimeout);
		params.put("connectTimeout", connectTimeout);
		params.put("numberOfRetries", numberOfRetries);

		Map<String, Object> hostDetails = new HashMap<String, Object>();
		hostDetails.putAll(params);
		
		Transaction txn = Transaction.currentTxn();
		txn.start();

		try {
			NetworkAPIResource resource = new NetworkAPIResource();
				resource.configure(Provider.NetworkAPI.getName(), hostDetails);
			
			Host host = _resourceMgr.addHost(zoneId, resource, resource.getType(),
					params);
			
	       txn.commit();
	       return host;
		} catch (ConfigurationException e) {
            txn.rollback();
            throw new CloudRuntimeException(e);
		}
	}

	@Override
	public List<Class<?>> getCommands() {
		List<Class<?>> cmdList = new ArrayList<Class<?>>();
		cmdList.add(AddNetworkApiVlanCmd.class);
		cmdList.add(AddNetworkViaNetworkApiCmd.class);
		cmdList.add(AddNetworkAPIEnvironmentCmd.class);
		cmdList.add(ListNetworkApiEnvironmentsCmd.class);
		cmdList.add(ListAllEnvironmentsFromNetworkApiCmd.class);
		cmdList.add(AddNetworkApiHostCmd.class);
		return cmdList;
	}
	
	@Override
	public List<Environment> listAllEnvironmentsFromNetworkApi(Long physicalNetworkId) {
		
		// validate physical network and zone
		// Check if physical network exists
		PhysicalNetwork pNtwk = null;
		if (physicalNetworkId != null) {
			pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
			if (pNtwk == null) {
				throw new InvalidParameterValueException(
						"Unable to find a physical network having the specified physical network id");
			}
		} else {
			throw new InvalidParameterValueException("Invalid physicalNetworkId: " + physicalNetworkId);
		}
				
		Long zoneId = pNtwk.getDataCenterId();
		
		ListAllEnvironmentsFromNetworkAPICommand cmd = new ListAllEnvironmentsFromNetworkAPICommand();
		
		Answer answer;
		try {
			answer = callCommand(cmd, zoneId);
		} catch (ConfigurationException ex) {
			throw new CloudRuntimeException("Error getting all environments from NetworkAPI.");
		}
		
		if (answer == null || !answer.getResult()) {
			String errorDescription = answer == null ? "no description"
					: answer.getDetails();
			throw new CloudRuntimeException(
					"Error getting environment list from NetworkAPI: " + errorDescription);
		}
		List<Environment> environments =  ((NetworkAPIAllEnvironmentResponse) answer).getEnvironmentList();
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
		for (Environment environment : listAllEnvironmentsFromNetworkApi(physicaNetworkId)) {
			if (environmentId.equals(environment.getId())) {
				resultEnvironment = environment;
				break;
			}
		}
		return resultEnvironment;
	}

	@Override
	public void removeNetworkFromNetworkAPI(Network network) {
		
		RemoveNetworkInNetworkAPICommand cmd = new RemoveNetworkInNetworkAPICommand();
		Long vlanId = getNapiVlanId(network.getId());
		cmd.setVlanId(vlanId);
		
		Answer answer;
		try {
			answer = callCommand(cmd, network.getDataCenterId());
		} catch (ConfigurationException ex) {
			throw new CloudRuntimeException("Error removing network from NetworkAPI.");
		}
		
		if (answer == null || !answer.getResult()) {
			String errorDescription = answer == null ? "no description"
					: answer.getDetails();
			throw new CloudRuntimeException(
					"Error removing network from NetworkAPI: " + errorDescription);
		}
	}
	
	@Override
	public void deallocateVlanFromNetworkAPI(Network network) {
		
		Transaction txn = Transaction.currentTxn();
		txn.start();

		NetworkAPINetworkVO napiNetworkVO = _napiNetworkDao.findByNetworkId(network.getId());
		if (napiNetworkVO != null) {
			_napiNetworkDao.remove(napiNetworkVO.getId());
		}
		
		this.deallocateVlanFromNetworkAPI(network.getDataCenterId(), napiNetworkVO.getNapiVlanId());
		
		txn.commit();
	}
	
	public void deallocateVlanFromNetworkAPI(Long zoneId, Long vlanId) {
				
		DeallocateVlanFromNetworkAPICommand cmd = new DeallocateVlanFromNetworkAPICommand();
		cmd.setVlanId(vlanId);
		
		Answer answer;
		try {
			answer = callCommand(cmd, zoneId);
		} catch (ConfigurationException ex) {
			throw new CloudRuntimeException("Error deallocating vlan from NetworkAPI.");
		}
		
		if (answer == null || !answer.getResult()) {
			String errorDescription = answer == null ? "no description"
					: answer.getDetails();
			throw new CloudRuntimeException(
					"Error deallocating vlan from NetworkAPI: " + errorDescription);
		}
	}
	
	@Override
	public List<NetworkAPIEnvironmentVO> listNetworkAPIEnvironmentsFromDB(Long physicalNetworkId, Long zoneId) {
		List<NetworkAPIEnvironmentVO> napiEnvironmentsVOList;

		if (physicalNetworkId != null) {
			// Check if physical network exists
			PhysicalNetwork pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
			if (pNtwk == null) {
				throw new InvalidParameterValueException(
						"Unable to find a physical network having the specified physical network id");
			}
			
			napiEnvironmentsVOList = _napiEnvironmentDao.listByPhysicalNetworkId(physicalNetworkId);

		} else if (zoneId != null) {
			// Check if zone exists
			DataCenter zone = _dcDao.findById(zoneId);
			if (zone == null) {
				throw new InvalidParameterValueException(
						"Specified zone id was not found");
			}

			napiEnvironmentsVOList = new ArrayList<NetworkAPIEnvironmentVO>();
			for (PhysicalNetworkVO physicalNetwork : _physicalNetworkDao.listByZone(zoneId)) {
				List<NetworkAPIEnvironmentVO> partialResult = _napiEnvironmentDao.listByPhysicalNetworkId(physicalNetwork.getId());
				if (partialResult != null) {
					napiEnvironmentsVOList.addAll(partialResult);
				}
			}
		} else {
			napiEnvironmentsVOList = _napiEnvironmentDao.listAll();
		}
		
		return napiEnvironmentsVOList;
	}
}
