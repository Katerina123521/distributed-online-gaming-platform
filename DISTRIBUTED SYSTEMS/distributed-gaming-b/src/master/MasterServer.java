package master;

import common.HashUtil;
import common.Request;
import common.Response;
import model.Bet;
import model.Game;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MasterServer {
    private static final int PORT = 5001;
    private static String[] workerHosts;
    private static int[] workerPorts;
    private static final Map<String, Object> locks = new HashMap<>();
    private static final Map<String, Object> results = new HashMap<>();

    public static void main(String[] args) {
        String workersFile = (args.length > 0) ? args[0] : "workers.conf";
        loadWorkers(workersFile);
        if (workerHosts == null || workerHosts.length == 0) {
            System.err.println("[Master] ERROR: No workers found in " + workersFile + ". Exiting.");
            return;
        }
        System.out.println("[Master] Loaded " + workerHosts.length + " workers:");
        for (int i = 0; i < workerHosts.length; i++) {
            System.out.println("  Worker[" + i + "] = " + workerHosts[i] + ":" + workerPorts[i]);
        }
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[Master] Server started on port " + PORT + ". Waiting for connections...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread clientThread = new Thread(() -> handleClient(clientSocket));
                clientThread.start();
            }
        } catch (Exception e) {
            System.err.println("[Master] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void loadWorkers(String filePath) {
        List<String> hosts = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    hosts.add(parts[0].trim());
                    ports.add(Integer.parseInt(parts[1].trim()));
                }
            }
        } catch (Exception e) {
            System.err.println("[Master] Could not read workers file: " + e.getMessage());
        }
        workerHosts = hosts.toArray(new String[0]);
        workerPorts = new int[ports.size()];
        for (int i = 0; i < ports.size(); i++) {
            workerPorts[i] = ports.get(i);
        }
    }

    private static void handleClient(Socket socket) {
        try (
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            Request request = (Request) in.readObject();
            String command = request.getCommand();
            if (command != null && (command.startsWith("REDUCE_FINAL_RESULT:") ||
                    command.startsWith("REDUCE_STATS_FINAL:"))) {
                String reqId = command.substring(command.indexOf(':') + 1);
                Object lock;
                synchronized (locks) {
                    lock = locks.get(reqId);
                }
                if (lock != null) {
                    synchronized (lock) {
                        synchronized (results) {
                            results.put(reqId, request.getPayload());
                        }
                        lock.notifyAll();
                    }
                }
                out.writeObject(new Response("OK", "Master received reduced data."));
            } else {
                Response response = processRequest(request);
                out.writeObject(response);
            }
            out.flush();
        } catch (Exception e) {
        }
    }

    private static Response processRequest(Request request) {
        String command = request.getCommand();
        if (command == null) {
            return new Response("ERROR", "Null command received.");
        }
        if ("SEARCH".equals(command) || "LIST_GAMES".equals(command) ||
                "GET_PROVIDER_STATS".equals(command) || "GET_PLAYER_STATS".equals(command)) {
            String reqId = UUID.randomUUID().toString();
            Object lock = new Object();
            synchronized (locks) {
                locks.put(reqId, lock);
            }
            String mapCommand;
            if ("SEARCH".equals(command) || "LIST_GAMES".equals(command)) {
                mapCommand = "MAP_SEARCH:";
            } else if ("GET_PROVIDER_STATS".equals(command)) {
                mapCommand = "MAP_STATS_PROVIDER:";
            } else {
                mapCommand = "MAP_STATS_PLAYER:";
            }
            int numWorkers = workerHosts.length;
            for (int i = 0; i < numWorkers; i++) {
                String fullCmd = mapCommand + reqId + ":" + numWorkers;
                Object mapPayload = new Object[] { request.getPayload(), i, numWorkers };
                final int wi = i;
                new Thread(() -> {
                    boolean success = false;
                    for (int offset = 0; offset < numWorkers; offset++) {
                        int targetIdx = (wi + offset) % numWorkers;
                        Response r = sendToWorker(workerHosts[targetIdx], workerPorts[targetIdx],
                                new Request(fullCmd, mapPayload));
                        if (!"ERROR".equals(r.getStatus())) {
                            success = true;
                            break;
                        }
                    }
                    if (!success) {
                        System.err.println("[Master] MAP task failed for all replicas on index " + wi);
                    }
                }).start();
            }
            Object finalResult = null;
            synchronized (lock) {
                try {
                    while (true) {
                        boolean ready;
                        synchronized (results) {
                            ready = results.containsKey(reqId);
                        }
                        if (ready) {
                            synchronized (results) {
                                finalResult = results.remove(reqId);
                            }
                            break;
                        }
                        lock.wait(5000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (locks) {
                locks.remove(reqId);
            }
            return new Response("OK", finalResult);
        }
        if ("ADD_GAME".equals(command) || "REMOVE_GAME".equals(command) ||
                "EDIT_GAME".equals(command) || "RATE_GAME".equals(command)) {
            String gameName = extractGameName(command, request.getPayload());
            int numWorkers = workerHosts.length;
            int primaryIndex = HashUtil.getWorkerIndex(gameName, numWorkers);
            int replicaIndex = (primaryIndex + 1) % numWorkers;
            System.out.println("[Master] Command=" + command + ", Game='" + gameName +
                    "' -> Dual Write to Worker[" + primaryIndex + "] & Worker[" + replicaIndex + "]");
            Response r1 = sendToWorker(workerHosts[primaryIndex], workerPorts[primaryIndex], request);
            Response r2 = sendToWorker(workerHosts[replicaIndex], workerPorts[replicaIndex], request);
            if ("ERROR".equals(r1.getStatus()) && "ERROR".equals(r2.getStatus())) {
                return new Response("ERROR", "Both primary and replica workers are unreachable.");
            }
            return "ERROR".equals(r1.getStatus()) ? r2 : r1;
        }
        if ("PLAY".equals(command)) {
            Bet bet = (Bet) request.getPayload();
            int numWorkers = workerHosts.length;
            int workerIndex = HashUtil.getWorkerIndex(bet.getGameName(), numWorkers);
            int replicaIndex = (workerIndex + 1) % numWorkers;
            System.out.println("[Master] PLAY request for '" + bet.getGameName() +
                    "' -> Trying Primary Worker[" + workerIndex + "]");
            Response resp = sendToWorker(workerHosts[workerIndex], workerPorts[workerIndex], request);
            int successfulWorker = workerIndex;
            if ("ERROR".equals(resp.getStatus())) {
                System.out.println("[Master] Primary down. Falling back to Replica Worker[" + replicaIndex + "]");
                resp = sendToWorker(workerHosts[replicaIndex], workerPorts[replicaIndex], request);
                successfulWorker = replicaIndex;
            }
            if (!"ERROR".equals(resp.getStatus())) {
                try {
                    int otherNode = (successfulWorker == workerIndex) ? replicaIndex : workerIndex;
                    String resultStr = (String) resp.getPayload();
                    String[] parts = resultStr.split("\\|");
                    double netProfit = Double.parseDouble(parts[2].split(":")[1].trim());
                    Request syncReq = new Request("SYNC_BET", new Object[] { bet, netProfit });
                    new Thread(() -> sendToWorker(workerHosts[otherNode], workerPorts[otherNode], syncReq)).start();
                } catch (Exception e) {
                    System.err.println("[Master] Could not sync bet to replica: " + e.getMessage());
                }
            }
            return resp;
        }
        return new Response("ERROR", "Unknown command: " + command);
    }

    private static String extractGameName(String command, Object payload) {
        if ("ADD_GAME".equals(command) || "EDIT_GAME".equals(command)) {
            return ((Game) payload).getGameName();
        }
        if ("RATE_GAME".equals(command)) {
            return (String) ((Object[]) payload)[0];
        }
        return (String) payload;
    }

    private static Response sendToWorker(String host, int port, Request request) {
        try (
                Socket s = new Socket(host, port);
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
            out.writeObject(request);
            out.flush();
            return (Response) in.readObject();
        } catch (Exception e) {
            System.err.println("[Master] Cannot reach Worker at " + host + ":" + port +
                    " - " + e.getMessage());
            return new Response("ERROR", "Worker at " + host + ":" + port + " is unreachable.");
        }
    }
}