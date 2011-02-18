package com.trendmicro.mist;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.PropertyConfigurator;
import org.nocrala.tools.texttablefmt.CellStyle;
import org.nocrala.tools.texttablefmt.CellStyle.HorizontalAlign;
import org.nocrala.tools.texttablefmt.Table;

import com.trendmicro.codi.ZKSessionManager;
import com.trendmicro.mist.mfr.BrokerFarm;
import com.trendmicro.mist.mfr.CommandHandler;
import com.trendmicro.mist.mfr.ExchangeFarm;
import com.trendmicro.mist.mfr.RouteFarm;
import com.trendmicro.mist.proto.GateTalk;
import com.trendmicro.mist.proto.ZooKeeperInfo;
import com.trendmicro.mist.session.ConsumerSession;
import com.trendmicro.mist.session.ProducerSession;
import com.trendmicro.mist.session.Session;
import com.trendmicro.mist.session.SessionPool;
import com.trendmicro.mist.util.Exchange;
import com.trendmicro.spn.common.util.Utils;

public class Daemon {
    private static boolean shutdownRequested = false;
    private static Log logger = LogFactory.getLog(Daemon.class);
    private ServerSocket server;
    private ArrayList<ServiceProvider> services = new ArrayList<ServiceProvider>();
    private CellStyle numberStyle = new CellStyle(HorizontalAlign.right);

    private void addDaemonShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                instance.shutdown();
            }
        });
    }

    private void shutdown() {
        shutdownRequested = true;
        for(Session sess : SessionPool.pool.values()) {
            if(sess != null) {
                try {
                    if(sess instanceof ConsumerSession)
                        sess.detach(GateTalk.Request.Role.SOURCE);
                    else if(sess instanceof ProducerSession)
                        sess.detach(GateTalk.Request.Role.SINK);
                }
                catch(Exception e) {
                    logger.error(Utils.convertStackTrace(e));
                }
            }
        }
        for(Connection conn : connectionPool)
            conn.close();
        logger.info("MISTd shutdown");
    }

    private void setupEnvironment() {
        String pid = Utils.getCurrentPid();
        try {
            BufferedWriter pidfile = new BufferedWriter(new FileWriter(namePidfile));
            pidfile.write(pid);
            pidfile.newLine();
            pidfile.close();
            new File(namePidfile).deleteOnExit();
        }
        catch(IOException e) {
            logger.fatal(String.format("can not create `%s'", namePidfile));
            logger.fatal(e.getMessage());
            System.exit(-1);
        }

        logger.info(String.format("%s, pid = %s", namePidfile, pid));

        addDaemonShutdownHook();
    }

    private int getFreeServiceCount() {
        synchronized(services) {
            int i;
            int cnt = 0;
            for(i = 0; i < services.size(); i++) {
                if(services.get(i).isReady())
                    cnt++;
            }
            return cnt;
        }
    }

    private boolean bindServicePort(int tryCount) {
        for(int i = 0; i < tryCount; i++) {
            try {
                server = new ServerSocket(DAEMON_PORT);
                return true;
            }
            catch(Exception e) {
                logger.error(e.getMessage());
                Utils.justSleep(1000);
            }
        }
        return false;
    }

    // //////////////////////////////////////////////////////////////////////////////

    public static String nameTempDir;
    public static String nameConfigDir;
    public static String namePidfile;
    public static String nameLogfile;
    public static String nameMISTConfig;
    public static String nameLog4jConfig;
    public static String clientID;
    public static Properties propMIST = new Properties();

    public static Daemon instance;

    public static final int DAEMON_PORT = 9498;
    public static final int SERVICE_THREAD_NUM = 4;
    public static final int GOC_UPLOAD_SIZE = 2 * 1024;
    public static final int MAX_TRANSMIT_MESSAGE_SIZE = 512 * 1024;
    public static final int MAX_MESSAGE_SIZE = 20 * 1024 * 1024;

    public static List<Connection> connectionPool = Collections.synchronizedList(new ArrayList<Connection>());
    public static Map<String, ExchangeMetric> exchangeStat = Collections.synchronizedMap(new HashMap<String, ExchangeMetric>());
    public static ArrayList<Thread> deadServiceList = new ArrayList<Thread>();

    static {
        nameTempDir = "/var/run/tme";
        nameConfigDir = "/usr/share/mist/etc";
        namePidfile = nameTempDir + "/mistd.pid";
        nameLogfile = nameTempDir + "/mistd.log";
        nameMISTConfig = nameConfigDir + "/mistd.properties";
        nameLog4jConfig = nameConfigDir + "/mistd.log4j";

        clientID = Utils.getHostIP() + "," + Utils.getCurrentPid();

        String cfg_name = System.getProperty("mistd.config", nameMISTConfig);
        try {
            propMIST.load(new FileInputStream(cfg_name));
        }
        catch(IOException e) {
            System.err.printf("can not load config file `%s'%n", cfg_name);
        }
    }

    public Daemon() {
        new File(nameTempDir).mkdirs();
        try {
            Properties prop = new Properties();
            prop.load(new FileInputStream(System.getProperty("mistd.log.log4j", nameLog4jConfig)));
            String logStdout = System.getProperty("mistd.log.stdout");
            if(logStdout == null)
                logStdout = propMIST.getProperty("mistd.log.stdout", "false");
            if(logStdout.equals("true"))
                prop.setProperty("log4j.rootLogger", prop.getProperty("log4j.rootLogger") + ", stdout");
            prop.setProperty("log4j.logger.org.apache.zookeeper", "ERROR, R");
            prop.setProperty("log4j.appender.R.File", nameLogfile);
            PropertyConfigurator.configure(prop);
            logger.info(nameLogfile);
        }
        catch(IOException e) {
            System.err.println(e.getMessage());
        }
    }

    public static Connection getConnection(GateTalk.Connection conn_config) {
        synchronized(connectionPool) {
            for(Connection conn : connectionPool) {
                if(conn.getHostName().equals(conn_config.getHostName()) && conn.getType().equals(conn_config.getBrokerType())) {
                    conn.increaseReference();
                    return conn;
                }
            }
        }
        try {
            Connection conn = new Connection(conn_config);
            conn.open();
            synchronized(connectionPool) {
                connectionPool.add(conn);
            }
            conn.increaseReference();
            return conn;
        }
        catch(MistException e) {
            logger.error(e.getMessage());
        }
        return null;
    }

    public static Connection getConnection(String host) {
        ZooKeeperInfo.Broker broker = BrokerFarm.getInstance().getBrokerByHost(host);
        GateTalk.Connection.Builder conn_builder = GateTalk.Connection.newBuilder();
        conn_builder.setBrokerType(broker.getBrokerType());
        conn_builder.setHostName(broker.getHost());
        conn_builder.setHostPort(broker.getPort());
        if(broker.getAccountCount() > 0) {
            conn_builder.setUsername(broker.getAccount(0).getUser());
            conn_builder.setPassword(broker.getAccount(0).getPassword());
        }
        else {
            conn_builder.setUsername("");
            conn_builder.setPassword("");
        }

        return getConnection(conn_builder.build());
    }

    public String getDaemonStatus(String input) {
        StringWriter strOut = new StringWriter();
        strOut.write(String.format("MIST %s (%s)%n", Version.getVersion(), clientID));
        strOut.write(String.format("%d service threads%n", services.size()));
        if(services.size() > 0) {
            Table tab = new Table(2);
            tab.addCell("ID");
            tab.addCell("Status");
            for(ServiceProvider s : services) {
                tab.addCell(String.valueOf(s.getId()));
                tab.addCell(s.isReady() ? "idle": "busy");
            }
            strOut.write(tab.render() + "\n");
        }
        strOut.write(String.format("%d brokers available%n", BrokerFarm.getInstance().getBrokerCount()));
        if(BrokerFarm.getInstance().getBrokerCount() > 0) {
            Table tab = new Table(2);
            tab.addCell("Host");
            tab.addCell("Status");
            for(Entry<String, ZooKeeperInfo.Broker> ent : BrokerFarm.getInstance().getAllBrokers().entrySet()) {
                tab.addCell(ent.getValue().getHost() + ":" + ent.getValue().getPort());
                tab.addCell(ent.getValue().getStatus().toString());
            }
            strOut.write(tab.render() + "\n");
        }
        strOut.write(String.format("%d exchanges transmitted%n", exchangeStat.size()));
        if(exchangeStat.size() > 0) {
            Table tab = new Table(7);
            tab.addCell("Exchange");
            tab.addCell("In-Count");
            tab.addCell("In-Bytes");
            tab.addCell("Out-Count");
            tab.addCell("Out-Bytes");
            tab.addCell("Ref-Count");
            tab.addCell("De-Ref-Count");
            for(Map.Entry<String, ExchangeMetric> e : exchangeStat.entrySet()) {
                ExchangeMetric info = e.getValue();
                tab.addCell(e.getKey());
                tab.addCell(String.valueOf(info.getMessageInCount()), numberStyle);
                tab.addCell(String.valueOf(info.getMessageInBytes()), numberStyle);
                tab.addCell(String.valueOf(info.getMessageOutCount()), numberStyle);
                tab.addCell(String.valueOf(info.getMessageOutBytes()), numberStyle);
                tab.addCell(String.valueOf(info.getGOCReferenceCount()), numberStyle);
                tab.addCell(String.valueOf(info.getGOCDeReferenceCount()), numberStyle);
            }
            strOut.write(tab.render() + "\n");
        }
        return strOut.toString();
    }

    public static synchronized ExchangeMetric getExchangeMetric(Exchange exchange) {
        if(exchangeStat.containsKey(exchange.toString()))
            return exchangeStat.get(exchange.toString());
        ExchangeMetric metric = new ExchangeMetric();
        exchangeStat.put(exchange.toString(), metric);
        return metric;
    }

    public static boolean isShutdownRequested() {
        return shutdownRequested;
    }

    public static boolean isRunning() {
        if(new File(namePidfile).exists()) {
            try {
                BufferedReader in = new BufferedReader(new FileReader(namePidfile));
                String line = in.readLine();
                int pid = Integer.parseInt(line);
                in.close();
                if(new File("/proc/" + pid).exists())
                    return true;
            }
            catch(NumberFormatException e) {
                System.err.printf("%s, not correct pid%n", e.getMessage());
            }
            catch(IOException e) {
                System.err.printf("can not read `%s'%n", namePidfile);
            }
        }
        return false;
    }

    public void run() {
        if(isRunning()) {
            System.err.println("Another daemon running, exit");
            System.exit(-1);
        }
        setupEnvironment();

        try {
            ZKSessionManager.initialize(Daemon.propMIST.getProperty("mistd.zookeeper"), Integer.valueOf(Daemon.propMIST.getProperty("mistd.zookeeper.timeout")));
            ZKSessionManager.instance().waitConnected();
            logger.info(String.format("MISTd started (%s) @ %s", Version.getVersion(), Utils.getHostIP()));

            CommandHandler.getInstance();
            ExchangeFarm.getInstance();
            BrokerFarm.getInstance();
            RouteFarm.getInstance();

            if(!bindServicePort(10)) {
                logger.error("unable to bind daemon service port, exit");
                System.exit(-1);
            }

            do {
                synchronized(services) {
                    int freeCount = getFreeServiceCount();
                    if(freeCount < SERVICE_THREAD_NUM) {
                        ServiceProvider provider = new ServiceProvider(server);
                        String name = String.format("service-%d", provider.getId());
                        provider.createThread(name);
                        provider.startThread();
                        services.add(provider);
                        logger.info(String.format("launch %s", name));
                    }
                    else if(freeCount > SERVICE_THREAD_NUM + 2) {
                        ServiceProvider providerToKick = null;
                        for(ServiceProvider provider : services) {
                            if(provider.isReady()) {
                                provider.stopThread();
                                providerToKick = provider;
                                break;
                            }
                        }
                        if(providerToKick != null)
                            services.remove(providerToKick);
                    }
                }

                if(deadServiceList.size() > 0) {
                    synchronized(deadServiceList) {
                        Iterator<Thread> iter = deadServiceList.iterator();
                        while(iter.hasNext()) {
                            Thread t = iter.next();
                            t.join();
                            logger.info(t.getName() + " joined");
                            iter.remove();
                        }
                    }
                }
                Utils.justSleep(10);
            } while(!isShutdownRequested());
        }
        catch(Exception e) {
            logger.fatal(Utils.convertStackTrace(e));
        }
    }

    public static void main(String args[]) {
        instance = new Daemon();
        instance.run();
    }
}
