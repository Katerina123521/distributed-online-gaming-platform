package reducer;

import common.Request;
import common.Response;
import model.Game;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ReducerServer {
    private static final int PORT = 8001;
    private static String masterHost;
    private static int masterPort;
    private static final Map<String, List<Game>> partialGameResults = new HashMap<>();
    private static final Map<String, Integer> expectedGameCount = new HashMap<>();
    private static final Map<String, Integer> receivedGameCount = new HashMap<>();
    private static final Map<String, Map<String, Double>> partialStatsResults = new HashMap<>();
    private static final Map<String, Integer> expectedStatsCount = new HashMap<>();
    private static final Map<String, Integer> receivedStatsCount = new HashMap<>();

    public static void main(String[] args) {
        String configFile = (args.length > 0) ? args[0] : "config.conf";
        Properties config = new Properties();
        try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
            config.load(reader);
        } catch (Exception e) {
            System.err.println("[Reducer] Could not load config file: " + configFile);
        }
        masterHost = config.getProperty("master.host", "localhost");
        masterPort = Integer.parseInt(config.getProperty("master.port", "5001"));
        System.out.println("[Reducer] Started on port " + PORT + ". Master is at " +
                masterHost + ":" + masterPort);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleWorker(socket)).start();
            }
        } catch (Exception e) {
            System.err.println("[Reducer] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleWorker(Socket socket) {
        try (
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            Request request = (Request) in.readObject();
            String command = request.getCommand();
            if (command != null && command.startsWith("REDUCE_PARTIAL:")) {
                String[] parts = command.split(":");
                String reqId = parts[1];
                int expected = Integer.parseInt(parts[2]);
                List<Game> partialList = (List<Game>) request.getPayload();
                boolean isLast = false;
                List<Game> finalResult = null;
                synchronized (partialGameResults) {
                    if (!partialGameResults.containsKey(reqId)) {
                        partialGameResults.put(reqId, new ArrayList<>());
                    }
                    partialGameResults.get(reqId).addAll(partialList);
                    expectedGameCount.put(reqId, expected);
                    int received = receivedGameCount.getOrDefault(reqId, 0) + 1;
                    receivedGameCount.put(reqId, received);
                    if (received == expected) {
                        isLast = true;
                        finalResult = partialGameResults.remove(reqId);
                        expectedGameCount.remove(reqId);
                        receivedGameCount.remove(reqId);
                    }
                }
                out.writeObject(new Response("OK", "Partial games received."));
                out.flush();
                if (isLast && finalResult != null) {
                    System.out.println("[Reducer] All " + expected + " workers reported for " +
                            reqId + ". Sending " + finalResult.size() + " games to Master.");
                    sendToMaster("REDUCE_FINAL_RESULT:" + reqId, finalResult);
                }
            } else if (command != null && command.startsWith("REDUCE_STATS_PARTIAL:")) {
                String[] parts = command.split(":");
                String reqId = parts[1];
                int expected = Integer.parseInt(parts[2]);
                Map<String, Double> partialMap = (Map<String, Double>) request.getPayload();
                boolean isLast = false;
                Map<String, Double> finalResult = null;
                synchronized (partialStatsResults) {
                    if (!partialStatsResults.containsKey(reqId)) {
                        partialStatsResults.put(reqId, new HashMap<>());
                    }
                    Map<String, Double> aggregated = partialStatsResults.get(reqId);
                    for (Map.Entry<String, Double> entry : partialMap.entrySet()) {
                        double existing = aggregated.getOrDefault(entry.getKey(), 0.0);
                        aggregated.put(entry.getKey(), existing + entry.getValue());
                    }
                    expectedStatsCount.put(reqId, expected);
                    int received = receivedStatsCount.getOrDefault(reqId, 0) + 1;
                    receivedStatsCount.put(reqId, received);
                    if (received == expected) {
                        isLast = true;
                        finalResult = partialStatsResults.remove(reqId);
                        expectedStatsCount.remove(reqId);
                        receivedStatsCount.remove(reqId);
                    }
                }
                out.writeObject(new Response("OK", "Partial stats received."));
                out.flush();
                if (isLast && finalResult != null) {
                    System.out.println("[Reducer] All " + expected + " workers reported stats for " +
                            reqId + ". Sending to Master.");
                    sendToMaster("REDUCE_STATS_FINAL:" + reqId, finalResult);
                }
            } else {
                out.writeObject(new Response("ERROR", "Unknown command: " + command));
            }
        } catch (Exception e) {
            System.err.println("[Reducer] Error processing worker request: " + e.getMessage());
        }
    }

    private static void sendToMaster(String command, Object payload) {
        try (
                Socket masterSocket = new Socket(masterHost, masterPort);
                ObjectOutputStream out = new ObjectOutputStream(masterSocket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(masterSocket.getInputStream())) {
            out.writeObject(new Request(command, payload));
            out.flush();
            in.readObject();
        } catch (Exception e) {
            System.err.println("[Reducer] Cannot reach Master at " + masterHost + ":" + masterPort +
                    " - " + e.getMessage());
        }
    }
}