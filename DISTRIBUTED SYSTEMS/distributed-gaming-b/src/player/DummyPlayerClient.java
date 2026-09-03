package player;

import common.Request;
import common.Response;
import model.Bet;
import model.Game;
import model.SearchFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

public class DummyPlayerClient {
    private static String masterHost;
    private static int masterPort;
    private static String playerId;
    private static double balance = 0.0;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String configFile = (args.length > 0) ? args[0] : "config.conf";
        Properties config = new Properties();
        try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
            config.load(reader);
        } catch (Exception e) {
            System.err.println("[Player] Could not load config file: " + configFile);
        }
        masterHost = config.getProperty("master.host", "localhost");
        masterPort = Integer.parseInt(config.getProperty("master.port", "5001"));
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║      DUMMY PLAYER CONSOLE - PHASE A   ║");
        System.out.println("║  Distributed Online Gaming Platform  ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("Master: " + masterHost + ":" + masterPort + "\n");
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter your Player ID (e.g. Player123): ");
        playerId = scanner.nextLine().trim();
        if (playerId.isEmpty())
            playerId = "GuestPlayer";
        System.out.println("Welcome, " + playerId + "!\n");
        List<Game> lastSearchResults = null;
        boolean running = true;
        while (running) {
            System.out.println("─────────────────────────────────────────");
            System.out.printf("  Player: %-15s  Balance: %6.2f%n", playerId, balance);
            System.out.println("─────────────────────────────────────────");
            System.out.println("  1. Refresh balance (addBalance)        ");
            System.out.println("  2. Search games                        ");
            System.out.println("  3. Bet (choose from list)              ");
            System.out.println("  4. Rate a game                         ");
            System.out.println("  5. My stats (MapReduce)                ");
            System.out.println("  0. Exit                                ");
            System.out.println("─────────────────────────────────────────");
            System.out.print("Choice: ");
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    System.out.print("How many FUN tokens to add? ");
                    try {
                        double amount = Double.parseDouble(scanner.nextLine().trim());
                        addBalance(amount);
                    } catch (NumberFormatException e) {
                        System.out.println("[ERROR] Invalid value.");
                    }
                    break;
                case "2":
                    System.out.println("\n--- SEARCH FILTERS ---");
                    System.out.println("(Press Enter to skip filter)");
                    System.out.print("Min stars (1-5): ");
                    String starsInput = scanner.nextLine().trim();
                    Double minStars = starsInput.isEmpty() ? null : Double.parseDouble(starsInput);
                    System.out.print("Risk level (LOW/MEDIUM/HIGH): ");
                    String riskInput = scanner.nextLine().trim();
                    String riskLevel = riskInput.isEmpty() ? null : riskInput.toUpperCase();
                    System.out.print("Bet category ($, $$, $$$): ");
                    String catInput = scanner.nextLine().trim();
                    String betCat = catInput.isEmpty() ? null : catInput;
                    SearchFilter filter = new SearchFilter(minStars, riskLevel, betCat);
                    Response searchResp = sendRequest(new Request("SEARCH", filter));
                    if (searchResp != null && "OK".equals(searchResp.getStatus())) {
                        lastSearchResults = (List<Game>) searchResp.getPayload();
                        System.out.println("\n--- SEARCH RESULTS (total: " +
                                lastSearchResults.size() + ") ---");
                        if (lastSearchResults.isEmpty()) {
                            System.out.println("  No games found with these filters.");
                        } else {
                            for (int i = 0; i < lastSearchResults.size(); i++) {
                                Game g = lastSearchResults.get(i);
                                System.out.printf("  [%d] %-20s | Risk: %-6s | Stars: %.1f | MinBet: %.2f%n",
                                        i + 1, g.getGameName(), g.getRiskLevel(),
                                        g.getStars(), g.getMinBet());
                            }
                        }
                        System.out.println("──────────────────────────────────────────────────────");
                    } else {
                        printResponse(searchResp);
                    }
                    break;
                case "3":
                    if (lastSearchResults == null || lastSearchResults.isEmpty()) {
                        System.out.println("[WARN] First search for games (option 2).");
                        break;
                    }
                    System.out.println("\n--- SELECT A GAME ---");
                    for (int i = 0; i < lastSearchResults.size(); i++) {
                        Game g = lastSearchResults.get(i);
                        System.out.printf("  [%d] %s (MinBet: %.2f)%n", i + 1, g.getGameName(), g.getMinBet());
                    }
                    System.out.print("Game number (1-" + lastSearchResults.size() + "): ");
                    try {
                        int gameIdx = Integer.parseInt(scanner.nextLine().trim()) - 1;
                        if (gameIdx < 0 || gameIdx >= lastSearchResults.size()) {
                            System.out.println("[ERROR] Invalid choice.");
                            break;
                        }
                        Game selectedGame = lastSearchResults.get(gameIdx);
                        System.out.printf("Bet amount (MinBet: %.2f, MaxBet: %.2f, Balance: %.2f): ",
                                selectedGame.getMinBet(), selectedGame.getMaxBet(), balance);
                        double betAmount = Double.parseDouble(scanner.nextLine().trim());
                        if (betAmount < selectedGame.getMinBet()) {
                            System.out.println("[ERROR] Bet below minimum (" +
                                    selectedGame.getMinBet() + ").");
                            break;
                        }
                        if (betAmount > selectedGame.getMaxBet()) {
                            System.out.println("[ERROR] Bet above maximum (" +
                                    selectedGame.getMaxBet() + ").");
                            break;
                        }
                        if (betAmount > balance) {
                            System.out.println("[ERROR] Not enough balance! (Balance: " +
                                    balance + ")");
                            break;
                        }
                        balance -= betAmount;
                        System.out.println("[INFO] Sending bet for '" +
                                selectedGame.getGameName() + "'...");
                        Bet bet = new Bet(selectedGame.getGameName(), betAmount, playerId);
                        Response playResp = sendRequest(new Request("PLAY", bet));
                        if (playResp != null && "OK".equals(playResp.getStatus())) {
                            String resultStr = (String) playResp.getPayload();
                            System.out.println("\n★ BET RESULT ★");
                            System.out.println("  " + resultStr);
                            double wonAmount = extractWonAmount(resultStr);
                            balance += wonAmount;
                            System.out.printf("  New Balance: %.2f FUN%n", balance);
                            System.out.println("──────────────────────────────────────");
                        } else {
                            balance += betAmount;
                            System.out.println("[ERROR] Bet failed. Amount returned.");
                            printResponse(playResp);
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("[ERROR] Invalid number.");
                    }
                    break;
                case "4":
                    System.out.print("Game name: ");
                    String rateGameName = scanner.nextLine().trim();
                    System.out.print("Rating (1-5 stars): ");
                    try {
                        int rating = Integer.parseInt(scanner.nextLine().trim());
                        if (rating < 1 || rating > 5) {
                            System.out.println("[ERROR] Rating must be 1-5.");
                            break;
                        }
                        Response rateResp = sendRequest(new Request("RATE_GAME",
                                new Object[] { rateGameName, rating }));
                        printResponse(rateResp);
                    } catch (NumberFormatException e) {
                        System.out.println("[ERROR] Invalid rating.");
                    }
                    break;
                case "5":
                    Response statsResp = sendRequest(new Request("GET_PLAYER_STATS", playerId));
                    if (statsResp != null && "OK".equals(statsResp.getStatus())) {
                        Map<String, Double> stats = (Map<String, Double>) statsResp.getPayload();
                        double total = stats.getOrDefault("Total", 0.0);
                        System.out.printf("%n--- MapReduce Stats for '%s' ---%n", playerId);
                        System.out.printf("  Total Profit/Loss: %+.2f FUN%n", total);
                        System.out.println("──────────────────────────────────────");
                    } else {
                        printResponse(statsResp);
                    }
                    break;
                case "0":
                    System.out.printf("Disconnecting. Final balance: %.2f FUN. Goodbye!%n", balance);
                    running = false;
                    break;
                default:
                    System.out.println("[WARN] Invalid choice.");
            }
            System.out.println();
        }
        scanner.close();
    }

    public static void addBalance(double amount) {
        if (amount <= 0) {
            System.out.println("[WARN] Amount must be positive.");
            return;
        }
        balance += amount;
        System.out.printf("[Wallet] Added %.2f FUN. New balance: %.2f FUN%n", amount, balance);
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

    private static double extractWonAmount(String resultStr) {
        try {
            String[] parts = resultStr.split("\\|");
            return Double.parseDouble(parts[1].split(":")[1].trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static void printResponse(Response response) {
        if (response == null) {
            System.out.println("[ERROR] No response received.");
            return;
        }
        String symbol = "OK".equals(response.getStatus()) ? "✓" : "✗";
        System.out.println("[" + symbol + "] " + response.getStatus() + ": " + response.getPayload());
    }
}