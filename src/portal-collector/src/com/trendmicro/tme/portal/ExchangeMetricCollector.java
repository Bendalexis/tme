package com.trendmicro.tme.portal;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.jmxtrans.JmxTransformer;
import com.googlecode.jmxtrans.model.JmxProcess;
import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Server;
import com.googlecode.jmxtrans.util.ValidationException;
import com.trendmicro.codi.ZKSessionManager;
import com.trendmicro.tme.mfr.BrokerFarm;
import com.trendmicro.mist.proto.ZooKeeperInfo.Broker;

public class ExchangeMetricCollector {
    private static final String CONFIG_PATH = System.getProperty("com.trendmicro.tme.portal.collector.conf", "/opt/trend/tme/conf/portal-collector/portal-collector.properties");
    private static final Logger logger = LoggerFactory.getLogger(ExchangeMetricCollector.class);
    
    private BrokerFarm brokerFarm;
    private ExchangeMetricWriter writer;
    
    private Query createQuery() {
        Query query = new Query();
        query.setObj("com.sun.messaging.jms.server:type=Destination,subtype=Monitor,desttype=*,name=*");
        query.addAttr("NumMsgs");
        query.addAttr("NumMsgsIn");
        query.addAttr("NumMsgsOut");
        query.addAttr("NumMsgsPendingAcks");
        query.addAttr("NumConsumers");
        query.addAttr("NumProducers");
        query.addAttr("MsgBytesIn");
        query.addAttr("MsgBytesOut");
        query.addAttr("TotalMsgBytes");
        query.addOutputWriter(writer);
        return query;
    }
    
    public ExchangeMetricCollector(BrokerFarm brokerFarm, ExchangeMetricWriter writer) {
        this.brokerFarm = brokerFarm;
        this.writer = writer;
    }
    
    private String getJMXUrl(String broker) {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(broker, 7676));
            String url = null;
            for(String line : IOUtils.toString(sock.getInputStream()).split("\n")) {
                if(line.startsWith("jmxrmi rmi JMX")) {
                    url = line.substring(line.indexOf('=') + 1, line.length() - 1);
                    break;
                }
            }
            logger.info("jmxurl of broker {} = {}", broker, url);
            return url;
        }
        catch(Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
        finally {
            try {
                IOUtils.closeQuietly(sock.getInputStream());
                IOUtils.closeQuietly(sock.getOutputStream());
                sock.close();
            }
            catch(IOException e) {
            }
        }
    }

    private void archiveMetrics() {
        ProcessBuilder processBuilder = new ProcessBuilder(new String[] {
            "/opt/trend/tme/bin/archive_metrics.sh", (String) writer.getSettings().get("outputPath")
        });
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output = IOUtils.toString(process.getInputStream());
            if(process.waitFor() != 0) {
                logger.error("error executing archive script: {}", output);
            }
            process.destroy();
        }
        catch(IOException e) {
            logger.error(e.getMessage(), e);
        }
        catch(InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void run() {
        long lastArchiveTimestamp = System.currentTimeMillis();
        logger.info("Exchange Metric Collector started");
        while(true) {
            JmxProcess jmxProcess = new JmxProcess();
            for(Broker broker : brokerFarm.getAllBrokers().values()) {
                if(broker.getStatus() == Broker.Status.ONLINE) {
                    Server server = new Server();
                    server.setHost(broker.getHost());
                    server.setUsername("admin");
                    server.setPassword("admin");
                    String jmxurl = getJMXUrl(broker.getHost());
                    if(jmxurl == null) {
                        logger.error("cannot obtain JMX URL from broker {}", broker.getHost());
                        continue;
                    }
                    server.setUrl(jmxurl);

                    try {
                        server.addQuery(createQuery());
                    }
                    catch(ValidationException e) {
                        e.printStackTrace();
                    }
                    jmxProcess.addServer(server);
                }
            }
            JmxTransformer transformer = new JmxTransformer();
            try {
                transformer.executeStandalone(jmxProcess);
                Thread.sleep(10000);
            }
            catch(Exception e) {
                logger.error("Error to collect metrics: ", e);
            }

            long currentTimestamp = System.currentTimeMillis();
            if(currentTimestamp - lastArchiveTimestamp > 300000) {
                lastArchiveTimestamp = currentTimestamp;
                archiveMetrics();
            }
        }
    }
    
    public static void main(String[] args) {
        try {
            Properties prop = new Properties();
            prop.load(new FileInputStream(CONFIG_PATH));
            // Let the system properties override the ones in the config file
            prop.putAll(System.getProperties());
            
            String zkQuorum = prop.getProperty("com.trendmicro.tme.portal.collector.zookeeper.quorum");
            int zkTimeout = Integer.valueOf(prop.getProperty("com.trendmicro.tme.portal.collector.zookeeper.timeout"));

            ZKSessionManager.initialize(zkQuorum + prop.getProperty("com.trendmicro.tme.portal.collector.zookeeper.tmeroot"), zkTimeout);
            if(!ZKSessionManager.instance().waitConnected(zkTimeout)) {
                throw new Exception("Cannot connect to ZooKeeper Quorom " + zkQuorum);
            }
            
            ExchangeMetricWriter writer = new ExchangeMetricWriter();
            writer.addSetting("templateFile", prop.getProperty("com.trendmicro.tme.portal.collector.template"));
            writer.addSetting("outputPath", prop.getProperty("com.trendmicro.tme.portal.collector.outputdir"));
            ExchangeMetricCollector collector = new ExchangeMetricCollector(new BrokerFarm(), writer);
            collector.run();
        }
        catch(Exception e) {
            e.printStackTrace();
            logger.error("Portal Collector startup error: ", e);
            System.exit(-1);
        }
    }
}
