package com.trendmicro.tme.grapheditor;

import java.io.IOException;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.JAXBException;

import com.sun.jersey.api.core.InjectParam;
import com.sun.jersey.api.view.Viewable;
import com.trendmicro.codi.CODIException;
import com.trendmicro.codi.ZNode;
import com.trendmicro.mist.proto.ZooKeeperInfo;
import com.trendmicro.mist.proto.ZooKeeperInfo.DropConfig;
import com.trendmicro.tme.broker.BrokerAdmin;
import com.trendmicro.tme.mfr.Exchange;
import com.trendmicro.tme.mfr.ExchangeFarm;

@Path("/exchange")
public class ExchangeManager {
    @InjectParam ExchangeFarm exchangeFarm;
    
    public ExchangeModel getExchange(@PathParam("name") String name) {
        return new ExchangeModel(name);
    }
    
    @Path("/{name}")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Viewable getPage(@PathParam("name") String name) throws CODIException, JAXBException, IOException {
        return new Viewable("/exchange/exchange.jsp", getExchange(name));
    }
    
    @Path("/{name}/drop")
    @PUT
    @Consumes(MediaType.TEXT_PLAIN)
    public void setDrop(@PathParam("name") String name, String policy) throws Exception {
        Exchange ex = new Exchange(name);
        String path = "/global/drop_exchange/" + ex.getName();
        ZooKeeperInfo.DropConfig dropConfig = ZooKeeperInfo.DropConfig.newBuilder().setPolicy(policy.equals("newest") ? DropConfig.Policy.NEWEST: DropConfig.Policy.OLDEST).build();
        
        ZNode node = new ZNode(path);
        if(node.exists()) {
            node.setContent(dropConfig.toString().getBytes());
        }
        else {
            node.create(false, dropConfig.toString().getBytes());
        }
        
        String broker = exchangeFarm.getCurrentExchangeHost(ex);
        if(broker != null) {
            BrokerAdmin brokerAdmin = new BrokerAdmin(broker);
            brokerAdmin.jmxConnectServer();
            try {
                
                if(dropConfig.getPolicy().equals(ZooKeeperInfo.DropConfig.Policy.NEWEST)) {
                    brokerAdmin.setExchangeAttrib(ex, "LimitBehavior", "REJECT_NEWEST");
                }
                else {
                    brokerAdmin.setExchangeAttrib(ex, "LimitBehavior", "REMOVE_OLDEST");
                }
            }
            finally {
                brokerAdmin.jmxCloseServer();
            }
        }
    }
    
    @Path("/{name}/drop")
    @DELETE
    public void setBlock(@PathParam("name") String name) throws Exception {
        Exchange ex = new Exchange(name);
        String path = "/global/drop_exchange/" + ex.getName();
        
        new ZNode(path).delete();
        String broker = exchangeFarm.getCurrentExchangeHost(ex);
        if(broker != null) {
            BrokerAdmin brokerAdmin = new BrokerAdmin(broker);
            brokerAdmin.jmxConnectServer();
            try {
                brokerAdmin.setExchangeAttrib(ex, "LimitBehavior", "FLOW_CONTROL");
            }
            finally {
                brokerAdmin.jmxCloseServer();
            }
        }
    }
    
    @Path("/{name}/size_limit")
    @PUT
    @Consumes(MediaType.TEXT_PLAIN)
    public void setSizeLimit(@PathParam("name") String name, String sizeLimit) throws Exception {
        Exchange ex = new Exchange(name);
        String path = "/global/limit_exchange/" + ex.getName();
        
        ZooKeeperInfo.TotalLimit limit = exchangeFarm.getTotalLimit(ex);
        ZNode node = new ZNode(path);
        if(node.exists()) {
            node.setContent(limit.toBuilder().setSizeBytes(Long.valueOf(sizeLimit)).build().toString().getBytes());
        }
        else {
            node.create(false, limit.toBuilder().setSizeBytes(Long.valueOf(sizeLimit)).build().toString().getBytes());
        }
        
        String broker = exchangeFarm.getCurrentExchangeHost(ex);
        if(broker != null) {
            BrokerAdmin brokerAdmin = new BrokerAdmin(broker);
            brokerAdmin.jmxConnectServer();
            try {
                brokerAdmin.setExchangeAttrib(ex, "MaxTotalMsgBytes", Long.valueOf(sizeLimit));
            }
            finally {
                brokerAdmin.jmxCloseServer();
            }
        }
    }
    
    @Path("/{name}/count_limit")
    @PUT
    @Consumes(MediaType.TEXT_PLAIN)
    public void setCountLimit(@PathParam("name") String name, String countLimit) throws Exception {
        Exchange ex = new Exchange(name);
        String path = "/global/limit_exchange/" + ex.getName();
        
        ZooKeeperInfo.TotalLimit limit = exchangeFarm.getTotalLimit(ex);
        ZNode node = new ZNode(path);
        if(node.exists()) {
            node.setContent(limit.toBuilder().setCount(Long.valueOf(countLimit)).build().toString().getBytes());
        }
        else {
            node.create(false, limit.toBuilder().setCount(Long.valueOf(countLimit)).build().toString().getBytes());
        }
        
        String broker = exchangeFarm.getCurrentExchangeHost(ex);
        if(broker != null) {
            BrokerAdmin brokerAdmin = new BrokerAdmin(broker);
            brokerAdmin.jmxConnectServer();
            try {
                brokerAdmin.setExchangeAttrib(ex, "MaxNumMsgs", Long.valueOf(countLimit));
            }
            finally {
                brokerAdmin.jmxCloseServer();
            }
        }
    }
}
