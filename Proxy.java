import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Proxy {

    private static int TIMEOUT = 2000;
    private static int MAX_SIZE = 10000;


    private final static String CMD_GET = "GET";
    private final static String CMD_SET = "SET";
    private final static String CMD_QUIT = "QUIT";
    private static String ARG_NAMES = "NAMES";
    private static String ARG_VALUE = "VALUE";
    private static String RESPONSE_OK = "OK";
    private static String RESPONSE_NA = "NA";


    private final static String HELLO_PROXY = "HELLO_PROXY";
    private final static String PROXY_SIG = "PROXY_SIG";
    private final static String P_CMD_SET = "SET_P";
    private final static String P_CMD_QUIT = "QUIT_P";
    private final static String P_CMD_GET_NAMES = "GET_NAMES_P";
    private final static String P_CMD_GET_VALUE = "GET_VALUE_P";

    enum Protocol { TCP, UDP }
    enum NodeType { SERVER, PROXY }

    static class NodeConfig {
        String host;
        int port;
        NodeConfig(String host, int port) { this.host = host; this.port = port; }
    }

    static class Node {
        NodeConfig config;
        Protocol protocol;
        NodeType type;

        Node(NodeConfig config, Protocol protocol, NodeType type) {
            this.config = config;
            this.protocol = protocol;
            this.type = type;
        }
        public String toString() { return type + " " + protocol + " " + config.host + ":" + config.port; }
    }

    private int listenPort;
    private List<NodeConfig> nodesToConnect = new ArrayList<>();
    private List<Node> activeNodes = Collections.synchronizedList(new ArrayList<>());
    private Set<String> requestHistory = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Queue<String> requestHistoryOrder = new ConcurrentLinkedQueue<>();
    private ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    private void parseArgs(String[] args) {
        try {
            for (int i = 0; i < args.length; ) {
                switch (args[i]) {
                    case "-port":
                        listenPort = Integer.parseInt(args[i + 1]);
                        i += 2;
                        break;
                    case "-server":
                        String host = args[i + 1];
                        int port = Integer.parseInt(args[i + 2]);
                        nodesToConnect.add(new NodeConfig(host, port));
                        i += 3;
                        break;
                    default:
                        i++;
                        break;
                }
            }
        } catch (Exception e) {
            System.err.println("Usage: java Proxy -port <p> -server <h> <p> ...");
            System.exit(1);
        }
    }

    private void start(String[] args) {
        parseArgs(args);

        System.out.println("PROXY STARTING on port " + listenPort);

        executor.submit(this::TCPServer);
        executor.submit(this::UDPServer);

        findActiveNodes();

        System.out.println("PROXY READY");
    }

    public static void main(String[] args) {
        new Proxy().start(args);
    }


    private void findActiveNodes() {
        System.out.println("Searching through " + nodesToConnect.size() + " nodes...");

        for (NodeConfig cfg : nodesToConnect) {

            Node node = checkNode(cfg, Protocol.TCP);
            if (node == null) {
                node = checkNode(cfg, Protocol.UDP);
            }

            if (node != null) {
                activeNodes.add(node);
                System.out.println("Added Neighbor: " + node);
            } else {
                System.err.println("Node unreachable: " + cfg.host + ":" + cfg.port);
            }
        }
    }

    private Node checkNode(NodeConfig cfg, Protocol proto) {
        try {
            String response = sendCommand(cfg.host, cfg.port, proto, HELLO_PROXY);

            if (response == null) return null;

            if (response.trim().equals(PROXY_SIG)) {
                return new Node(cfg, proto, NodeType.PROXY);
            } else {
                return new Node(cfg, proto, NodeType.SERVER);
            }
        } catch (Exception e) {
            return null;
        }
    }


    private String processRequest(String inputLine) {
        if (inputLine == null) return RESPONSE_NA;

        Scanner sc = new Scanner(inputLine);
        if (!sc.hasNext()) return RESPONSE_NA;

        String cmd = sc.next();
        String reqId;
        boolean isProxyRequest = false;

        if (cmd.endsWith("_P")) {
            isProxyRequest = true;
            if (!sc.hasNext()) return RESPONSE_NA;
            reqId = sc.next();
        } else {
            reqId = UUID.randomUUID().toString();
        }

        if (checkRequest(reqId)) {
            if (cmd.contains("NAMES")) return "OK 0";
            return RESPONSE_NA;
        }
        markRequest(reqId);


        String result = RESPONSE_NA;
        cmd = isProxyRequest ? cmd.replace("_P", "") : cmd;

        try {
            switch (cmd) {
                case HELLO_PROXY:
                    return PROXY_SIG;

                case CMD_GET:
                    if (!sc.hasNext()) return RESPONSE_NA;
                    String sub = sc.next();
                    if (sub.equals(ARG_NAMES)) {
                        result = getNames(reqId);
                    } else if (sub.equals(ARG_VALUE)) {
                        if (!sc.hasNext()) return RESPONSE_NA;
                        String key = sc.next();
                        result = getValue(reqId, key);
                    }
                    break;

                case "GET_NAMES":
                    result = getNames(reqId);
                    break;

                case "GET_VALUE":
                    if (!sc.hasNext()) return RESPONSE_NA;
                    String keyP = sc.next();
                    result = getValue(reqId, keyP);
                    break;

                case CMD_SET:
                    if (!sc.hasNext()) return RESPONSE_NA;
                    String keyS = sc.next();
                    if (!sc.hasNextInt()) return RESPONSE_NA;
                    int valS = sc.nextInt();
                    result = set(reqId, keyS, valS);
                    break;

                case CMD_QUIT:
                    quit(reqId);
                    return null;

                default:
                    return RESPONSE_NA;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return RESPONSE_NA;
        }
        return result;
    }



    private String getNames(String reqId) {
        Set<String> collectedKeys = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (Node node : activeNodes) {
            futures.add(executor.submit(() -> {
                String cmdToSend;
                if (node.type == NodeType.PROXY) {
                    cmdToSend = P_CMD_GET_NAMES + " " + reqId;
                } else {
                    cmdToSend = CMD_GET + " " + ARG_NAMES;
                }

                String resp = sendToNode(node, cmdToSend);
                if (resp != null && resp.startsWith("OK")) {
                    Scanner s = new Scanner(resp);
                    s.next(); // OK
                    if (s.hasNextInt()) {
                        int count = s.nextInt();
                        for (int i = 0; i < count; i++) {
                            if (s.hasNext()) collectedKeys.add(s.next());
                        }
                    }
                }
            }));
        }

        waitForAll(futures);

        StringBuilder sb = new StringBuilder("OK ").append(collectedKeys.size());
        for (String k : collectedKeys) {
            sb.append(" ").append(k);
        }
        return sb.toString();
    }

    private String getValue(String reqId, String key) {
        ExecutorCompletionService<String> ecs = new ExecutorCompletionService<>(executor);
        int tasks = 0;

        for (Node node : activeNodes) {
            tasks++;
            ecs.submit(() -> {
                String cmd;
                if (node.type == NodeType.PROXY) {
                    cmd = P_CMD_GET_VALUE + " " + reqId + " " + key;
                } else {
                    cmd = CMD_GET + " " + ARG_VALUE + " " + key;
                }
                return sendToNode(node, cmd);
            });
        }

        String finalResult = RESPONSE_NA;

        for (int i = 0; i < tasks; i++) {
            try {
                Future<String> f = ecs.poll(TIMEOUT, TimeUnit.MILLISECONDS);
                if (f != null) {
                    String res = f.get();
                    if (res != null && res.startsWith("OK")) {
                        return res;
                    }
                }
            } catch (Exception ignored) {}
        }

        return finalResult;
    }

    private String set(String reqId, String key, int value) {
        List<Future<String>> futures = new ArrayList<>();

        for (Node node : activeNodes) {
            futures.add(executor.submit(() -> {
                String cmd;
                if (node.type == NodeType.PROXY) {
                    cmd = P_CMD_SET + " " + reqId + " " + key + " " + value;
                } else {
                    cmd = CMD_SET + " " + key + " " + value;
                }
                return sendToNode(node, cmd);
            }));
        }

        boolean anySuccess = false;
        for (Future<String> f : futures) {
            try {
                String res = f.get(TIMEOUT, TimeUnit.MILLISECONDS);
                if (res != null && res.startsWith("OK")) {
                    anySuccess = true;
                }
            } catch (Exception ignored) {}
        }

        return anySuccess ? RESPONSE_OK : RESPONSE_NA;
    }

    private void quit(String reqId) {
        for (Node node : activeNodes) {
            executor.submit(() -> {
                String cmd;
                if (node.type == NodeType.PROXY) {
                    cmd = P_CMD_QUIT + " " + reqId;
                } else {
                    cmd = CMD_QUIT;
                }
                sendToNode(node, cmd);
            });
        }
        try {Thread.sleep(500);} catch (InterruptedException ignored) {}
        this.running = false;
        executor.shutdownNow();
        System.exit(0);
    }



    private String sendToNode(Node node, String cmd) {
        return sendCommand(node.config.host, node.config.port, node.protocol, cmd);
    }
    private String sendCommand(String host, int port, Protocol protocol, String cmd) {
        if (protocol == Protocol.TCP) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 1000);
                s.setSoTimeout(TIMEOUT);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));

                out.println(cmd);

                if (cmd.startsWith("QUIT") || cmd.startsWith(P_CMD_QUIT)) return null;

                return in.readLine();
            } catch (Exception e) {
                return null;
            }
        } else {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setSoTimeout(TIMEOUT);
                byte[] data = (cmd + "\n").getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(host), port);
                ds.send(packet);

                if (cmd.startsWith("QUIT") || cmd.startsWith(P_CMD_QUIT)) return null;

                byte[] buf = new byte[1024];
                DatagramPacket receive = new DatagramPacket(buf, buf.length);
                ds.receive(receive);

                return new String(receive.getData(), 0, receive.getLength()).trim();
            } catch (Exception e) {
                return null;
            }
        }
    }


    private void TCPServer() {
        try (ServerSocket ss = new ServerSocket(listenPort)) {
            while (running) {
                Socket client = ss.accept();
                executor.submit(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                         PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

                        client.setSoTimeout(TIMEOUT * 2);
                        String line = in.readLine();
                        if (line != null) {
                            String response = processRequest(line);
                            if (response != null) {
                                out.println(response);
                            }
                        }
                    } catch (IOException ignored) {}
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void UDPServer() {
        try (DatagramSocket ds = new DatagramSocket(listenPort)) {
            byte[] buffer = new byte[1024];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                ds.receive(packet);

                final String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                final SocketAddress address = packet.getSocketAddress();

                executor.submit(() -> {
                    String response = processRequest(msg);
                    if (response != null) {
                        try {
                            byte[] outBuf = response.getBytes();
                            DatagramPacket respPkt = new DatagramPacket(outBuf, outBuf.length, address);
                            ds.send(respPkt);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private boolean checkRequest(String reqId) {
        return requestHistory.contains(reqId);
    }
    private void markRequest(String reqId) {
        requestHistory.add(reqId);
        requestHistoryOrder.add(reqId);
        if (requestHistory.size() > MAX_SIZE) {
            String old = requestHistoryOrder.poll();
            if (old != null) requestHistory.remove(old);
        }
    }
    private void waitForAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get(TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
        }
    }
}