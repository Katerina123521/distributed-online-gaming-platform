package manager;

import common.Request;
import common.Response;
import model.Game;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ManagerClient {
    private static String masterHost;
    private static int masterPort;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String configFile = (args.length > 0) ? args[0] : "config.conf";
        Properties config = new Properties();
        try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
            config.load(reader);
        } catch (Exception e) {
            System.err.println("[Manager] Could not load config file: " + configFile);
        }
        masterHost = config.getProperty("master.host", "localhost");
        masterPort = Integer.parseInt(config.getProperty("master.port", "5001"));
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║       MANAGER CONSOLE - PHASE A      ║");
        System.out.println("║  Distributed Online Gaming Platform  ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("Master: " + masterHost + ":" + masterPort + "\n");
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        while (running) {
            System.out.println("───────────────────────────────────────");
            System.out.println("             MENU OPTIONS              ");
            System.out.println("  1. Add game (from JSON)              ");
            System.out.println("  2. Remove game                       ");
            System.out.println("  3. Modify risk level                 ");
            System.out.println("  4. Provider stats (MapReduce)        ");
            System.out.println("  5. Game list (MapReduce)             ");
            System.out.println("  6. 1 player stats (MapReduce)        ");
            System.out.println("  0. Exit                              ");
            System.out.println("───────────────────────────────────────");
            System.out.print("Choice: ");
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    System.out.print("Enter JSON file path (e.g. games/game1.json): ");
                    String jsonPath = scanner.nextLine().trim();
                    Game newGame = loadGameFromJson(jsonPath);
                    if (newGame == null) {
                        System.out.println("[ERROR] Could not load file: " + jsonPath);
                        break;
                    }
                    System.out.println("[INFO] Loaded: " + newGame.getGameName() +
                            " (Risk: " + newGame.getRiskLevel() + ")");
                    Response addResp = sendRequest(new Request("ADD_GAME", newGame));
                    printResponse(addResp);
                    break;
                case "2":
                    System.out.print("Game name to remove: ");
                    String removeGameName = scanner.nextLine().trim();
                    Response removeResp = sendRequest(new Request("REMOVE_GAME", removeGameName));
                    printResponse(removeResp);
                    break;
                case "3":
                    System.out.print("Game name: ");
                    String editGameName = scanner.nextLine().trim();
                    System.out.print("New risk level (LOW / MEDIUM / HIGH): ");
                    String newRisk = scanner.nextLine().trim().toUpperCase();
                    Game editGame = new Game(editGameName, "", 0, 0, "", 0, 0, newRisk, "");
                    Response editResp = sendRequest(new Request("EDIT_GAME", editGame));
                    printResponse(editResp);
                    break;
                case "4":
                    System.out.print("Provider name: ");
                    String providerName = scanner.nextLine().trim();
                    Response statsResp = sendRequest(new Request("GET_PROVIDER_STATS", providerName));
                    if (statsResp != null && "OK".equals(statsResp.getStatus())) {
                        Map<String, Double> stats = (Map<String, Double>) statsResp.getPayload();
                        System.out.println("\n--- MapReduce Stats for '" + providerName + "' ---");
                        double total = 0.0;
                        for (Map.Entry<String, Double> e : stats.entrySet()) {
                            System.out.printf("  Game '%-20s' : %+.2f FUN%n", e.getKey(), e.getValue());
                            total += e.getValue();
                        }
                        System.out.printf("  %-24s : %+.2f FUN%n", "TOTAL", total);
                        System.out.println("─────────────────────────────────────────");
                    } else {
                        printResponse(statsResp);
                    }
                    break;
                case "5":
                    Response listResp = sendRequest(new Request("LIST_GAMES",
                            new model.SearchFilter(null, null, null)));
                    if (listResp != null && "OK".equals(listResp.getStatus())) {
                        java.util.List<Game> games = (java.util.List<Game>) listResp.getPayload();
                        System.out.println("\n--- Active Games List (total: " + games.size() + ") ---");
                        for (Game g : games) {
                            System.out.printf("  %-20s | Risk: %-6s | Stars: %.1f | MinBet: %.2f%n",
                                    g.getGameName(), g.getRiskLevel(), g.getStars(), g.getMinBet());
                        }
                        System.out.println("────────────────────────────────────────────────────");
                    } else {
                        printResponse(listResp);
                    }
                    break;
                case "6":
                    System.out.print("Player ID: ");
                    String playerId = scanner.nextLine().trim();
                    Response playerStatsResp = sendRequest(new Request("GET_PLAYER_STATS", playerId));
                    if (playerStatsResp != null && "OK".equals(playerStatsResp.getStatus())) {
                        Map<String, Double> stats = (Map<String, Double>) playerStatsResp.getPayload();
                        double total = stats.getOrDefault("Total", 0.0);
                        System.out.printf("%n--- MapReduce Stats for player '%s' ---%n", playerId);
                        System.out.printf("  Total System Profit/Loss: %+.2f FUN%n", total);
                        System.out.println("─────────────────────────────────────────");
                    } else {
                        printResponse(playerStatsResp);
                    }
                    break;
                case "0":
                    System.out.println("Disconnecting. Goodbye!");
                    running = false;
                    break;
                default:
                    System.out.println("[WARN] Invalid choice. Try again.");
                    break;
            }
            System.out.println();
        }
        scanner.close();
    }

    private static Response sendRequest(Request request) {
        try (
                Socket socket = new Socket(masterHost, masterPort);
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            out.writeObject(request);
            out.flush();
            return (Response) in.readObject();
        } catch (Exception e) {
            System.err.println("[ERROR] Could not communicate with Master: " + e.getMessage());
            return null;
        }
    }

    private static void printResponse(Response response) {
        if (response == null) {
            System.out.println("[ERROR] No response received.");
            return;
        }
        String symbol = "OK".equals(response.getStatus()) ? "✓" : "✗";
        System.out.println("[" + symbol + "] Status: " + response.getStatus() +
                " | " + response.getPayload());
    }

    private static Game loadGameFromJson(String filePath) {
        try {
            String json = new String(Files.readAllBytes(Paths.get(filePath)));
            String gameName = extractString(json, "GameName");
            String providerName = extractString(json, "ProviderName");
            double stars = extractDouble(json, "Stars");
            int noOfVotes = extractInt(json, "NoOfVotes");
            String gameLogo = extractString(json, "GameLogo");
            double minBet = extractDouble(json, "MinBet");
            double maxBet = extractDouble(json, "MaxBet");
            String riskLevel = extractString(json, "RiskLevel").toUpperCase();
            String hashKey = extractString(json, "HashKey");
            if (gameName.isEmpty() || hashKey.isEmpty()) {
                System.err.println("[ERROR] JSON file missing required fields (GameName, HashKey).");
                return null;
            }
            return new Game(gameName, providerName, stars, noOfVotes, gameLogo, minBet, maxBet, riskLevel, hashKey);
        } catch (Exception e) {
            System.err.println("[ERROR] JSON parse failure: " + e.getMessage());
            return null;
        }
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    /** Εξαγωγή double πεδίου από JSON με regex. */
    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    /** Εξαγωγή int πεδίου από JSON με regex. */
    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}