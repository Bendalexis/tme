package com.trendmicro.tme.mfr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.TextFormat;
import com.trendmicro.codi.DataListener;
import com.trendmicro.codi.DataObserver;
import com.trendmicro.mist.proto.ZooKeeperInfo;

public class BrokerFarm implements DataListener {
    private final static Logger logger = LoggerFactory.getLogger(BrokerFarm.class);
    
    private HashMap<String, ZooKeeperInfo.Broker> allBrokers = new HashMap<String, ZooKeeperInfo.Broker>();
    private HashMap<String, ZooKeeperInfo.Loading> allBrokerLoadings = new HashMap<String, ZooKeeperInfo.Loading>();
    private DataObserver obs = null;
    private long lastUpdateTs = 0;
    
    public BrokerFarm() {
        obs = new DataObserver("/broker", this, true, 0);
        obs.start();
    }
    
    public long getLastUpdateTs() {
        return lastUpdateTs;
    }
    
    public ZooKeeperInfo.Broker getBrokerByHost(String hostname) {
        return allBrokers.get(hostname);
    }
    
    public int getBrokerCount() {
        return allBrokers.size();
    }
    
    public Map<String, ZooKeeperInfo.Loading> getAllLoading() {
        return allBrokerLoadings;
    }
    
    public Map<String, ZooKeeperInfo.Broker> getAllBrokers() {
        return allBrokers;
    }
    
    public boolean checkConnectable(ZooKeeperInfo.Broker broker) {
        if(broker.getStatus() != ZooKeeperInfo.Broker.Status.ONLINE)
            return false;

        boolean connectable = false;
        Socket sock = null;
        try {
            sock = new Socket();
            sock.setReuseAddress(true);
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(broker.getHost(), Integer.parseInt(broker.getPort())));
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            for(int wait_cnt = 0; wait_cnt < 20 && !in.ready(); wait_cnt++){
                try {
                    Thread.sleep(500);
                }
                catch(InterruptedException e) {
                }
            }

            if(in.ready()) {
                if(broker.getBrokerType().equals("openmq")) {
                    String line = in.readLine();
                    if(line.startsWith("101 "))
                        connectable = true;
                    else
                        logger.error("checkConnectable(): get " + line);
                }
            }
        }
        catch(IOException e) {
            logger.warn("checkConnectable() " + e.getMessage(), e);
        }
        finally {
            try {
                sock.getInputStream().close();
                sock.close();
            }
            catch(IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        return connectable;
    }

    @Override
    public void onDataChanged(String parentPath, Map<String, byte[]> changeMap) {
        for(Entry<String, byte[]> ent : changeMap.entrySet()) {
            if(ent.getKey().length() == 0)
                continue;
            else if(ent.getKey().endsWith(".lock"))
                continue;
            
            String host = ent.getKey();
            boolean isLoading = ent.getKey().endsWith("loading");
            if(isLoading)
                host = host.substring(0, host.lastIndexOf('/'));
            if(ent.getValue() == null) {
                if(isLoading)
                    allBrokerLoadings.remove(host);
                else
                    allBrokers.remove(host);
            }
            else {
                try {
                    if(isLoading) {
                        ZooKeeperInfo.Loading.Builder builder = ZooKeeperInfo.Loading.newBuilder();
                        TextFormat.merge(new String(ent.getValue()), builder);
                        allBrokerLoadings.put(host, builder.build());
                    }
                    else {
                        ZooKeeperInfo.Broker.Builder builder = ZooKeeperInfo.Broker.newBuilder();
                        TextFormat.merge(new String(ent.getValue()), builder);
                        allBrokers.put(host, builder.build());
                    }
                }
                catch(Exception e) {
                    logger.error("Failed to parse broker node " + ent.getKey(), e);
                }
            }
        }
        lastUpdateTs = new Date().getTime();
    }
}
