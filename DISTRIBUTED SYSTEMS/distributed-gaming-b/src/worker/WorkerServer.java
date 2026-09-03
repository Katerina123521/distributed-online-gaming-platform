package worker;

import common.HashUtil;
import common.Request;
import common.Response;
import model.Bet;
import model.Game;
import model.SearchFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class WorkerServer {
    private final int port;
    private final int totalWorkers;
    private final int myWorkerIndex;
    private final List<Game> games;
    private final Map<String, Double> playerStats;
    private final String reducerHost;
    private final int reducerPort;
    private final String srgHost;
    private final int srgPort;

    public WorkerServer(int port, int myWorkerIndex, int totalWorkers,
            String reducerHost, int reducerPort,
            String srgHost, int srgPort) {
        this.port = port;
        this.myWorkerIndex = myWorkerIndex;
        this.totalWorkers = totalWorkers;
        this.reducerHost = reducerHost;
        this.reducerPort = reducerPort;
        this.srgHost = srgHost;
        this.srgPort = srgPort;
        this.games = new ArrayList<>();
        this.playerStats = new HashMap<>();
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[Worker-" + myWorkerIndex + "] Started on port " + port +
                    " | Reducer=" + reducerHost + ":" + reducerPort +
                    " | SRG=" + srgHost + ":" + srgPort);
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket)).start();
            }
        } catch (Exception e) {
            System.err.println("[Worker-" + myWorkerIndex + "] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleClient(Socket socket) {
        try (
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            Request req = (Request) in.readObject();
            String command = req.getCommand();
            if ("PING".equals(command)) {
                out.writeObject(new Response("PONG", null));
            } else if ("ADD_GAME".equals(command)) {
                addGame((Game) req.getPayload());
                out.writeObject(new Response("OK", "Game added successfully."));
                System.out.println("[Worker-" + myWorkerIndex + "] Added game: " +
                        ((Game) req.getPayload()).getGameName());
            } else if ("REMOVE_GAME".equals(command)) {
                String gameName = (String) req.getPayload();
                boolean removed = removeGame(gameName);
                out.writeObject(new Response(removed ? "OK" : "ERROR",
                        removed ? "Game deactivated." : "Game not found."));
            } else if ("EDIT_GAME".equals(command)) {
                Game updated = (Game) req.getPayload();
                boolean edited = editGame(updated);
                out.writeObject(new Response(edited ? "OK" : "ERROR",
                        edited ? "Risk level updated." : "Game not found."));
            } else if ("RATE_GAME".equals(command)) {
                Object[] payload = (Object[]) req.getPayload();
                rateGame((String) payload[0], (Integer) payload[1]);
                out.writeObject(new Response("OK", "Rating submitted."));
            } else if ("PLAY".equals(command)) {
                out.writeObject(processBet((Bet) req.getPayload()));
            } else if ("SYNC_BET".equals(command)) {
                Object[] payload = (Object[]) req.getPayload();
                Bet bet = (Bet) payload[0];
                double netProfit = (Double) payload[1];
                Game game = findGame(bet.getGameName());
                if (game != null) {
                    synchronized (game) {
                        game.updateSystemProfitLoss(-netProfit);
                    }
                    synchronized (playerStats) {
                        double currentTotal = playerStats.getOrDefault(bet.getPlayerId(), 0.0);
                        playerStats.put(bet.getPlayerId(), currentTotal + netProfit);
                    }
                }
                out.writeObject(new Response("OK", "Bet synced."));
            } else if (command != null && command.startsWith("MAP_SEARCH:")) {
                out.writeObject(new Response("OK", "Map phase started."));
                out.flush();
                handleMapSearch(command, (Object[]) req.getPayload());
            } else if (command != null && command.startsWith("MAP_STATS_PROVIDER:")) {
                out.writeObject(new Response("OK", "Map stats phase started."));
                out.flush();
                handleMapStatsProvider(command, (Object[]) req.getPayload());
            } else if (command != null && command.startsWith("MAP_STATS_PLAYER:")) {
                out.writeObject(new Response("OK", "Map stats phase started."));
                out.flush();
                handleMapStatsPlayer(command, (Object[]) req.getPayload());
            } else {
                out.writeObject(new Response("ERROR", "Unknown command: " + command));
            }
            out.flush();
        } catch (Exception e) {
        }
    }

    private void handleMapSearch(String command, Object[] payload) {
        String[] parts = command.split(":");
        String reqId = parts[1];
        int expected = Integer.parseInt(parts[2]);
        SearchFilter filter = (SearchFilter) payload[0];
        int myIndex = (Integer) payload[1];
        int numWorkers = (Integer) payload[2];
        if (myIndex != this.myWorkerIndex) {
            System.out.println(
                    "\n( [ACTIVE REPLICA] Primary Worker " + myIndex + " seems down. As valid Replica "
                            + this.myWorkerIndex + ", taking over MAP_SEARCH! )\n");
        }
        List<Game> matchingGames = new ArrayList<>();
        synchronized (games) {
            for (Game g : games) {
                if (!g.isActive())
                    continue;
                int ownerIndex = HashUtil.getWorkerIndex(g.getGameName(), numWorkers);
                if (ownerIndex != myIndex)
                    continue;
                if (filter.getMinStars() != null && g.getStars() < filter.getMinStars())
                    continue;
                if (filter.getRiskLevel() != null &&
                        !g.getRiskLevel().equalsIgnoreCase(filter.getRiskLevel()))
                    continue;
                if (filter.getBetCategory() != null &&
                        !filter.getBetCategory().equals(getBetCategory(g.getMinBet())))
                    continue;
                matchingGames.add(g);
            }
        }
        sendGamesToReducer(reqId, expected, matchingGames);
    }

    private void handleMapStatsProvider(String command, Object[] payload) {
        String[] parts = command.split(":");
        String reqId = parts[1];
        int expected = Integer.parseInt(parts[2]);
        String providerName = (String) payload[0];
        int myIndex = (Integer) payload[1];
        int numWorkers = (Integer) payload[2];
        if (myIndex != this.myWorkerIndex) {
            System.out.println("\n( [ACTIVE REPLICA] Primary Worker " + myIndex
                    + " is down. Taking over MAP_STATS_PROVIDER as Replica " + this.myWorkerIndex + "! )\n");
        }
        Map<String, Double> partialStats = new HashMap<>();
        synchronized (games) {
            for (Game g : games) {
                int ownerIndex = HashUtil.getWorkerIndex(g.getGameName(), numWorkers);
                if (ownerIndex != myIndex)
                    continue;
                if (g.getProviderName().equalsIgnoreCase(providerName)) {
                    partialStats.put(g.getGameName(), g.getSystemProfitLoss());
                }
            }
        }
        sendStatsToReducer(reqId, expected, partialStats);
    }

    private void handleMapStatsPlayer(String command, Object[] payload) {
        String[] parts = command.split(":");
        String reqId = parts[1];
        int expected = Integer.parseInt(parts[2]);
        String playerId = (String) payload[0];
        int myIndex = (Integer) payload[1];
        int numWorkers = (Integer) payload[2];
        if (myIndex != this.myWorkerIndex) {
            System.out.println("\n( [ACTIVE REPLICA] Primary Worker " + myIndex
                    + " is down. Taking over MAP_STATS_PLAYER as Replica " + this.myWorkerIndex + "! )\n");
        }
        Map<String, Double> partialStats = new HashMap<>();
        int ownerIndex = HashUtil.getWorkerIndex(playerId, numWorkers);
        if (ownerIndex == myIndex) {
            synchronized (playerStats) {
                double total = playerStats.getOrDefault(playerId, 0.0);
                partialStats.put("Total", total);
            }
        }
        sendStatsToReducer(reqId, expected, partialStats);
    }

    private Response processBet(Bet bet) {
        int primaryIndex = HashUtil.getWorkerIndex(bet.getGameName(), this.totalWorkers);
        if (primaryIndex != this.myWorkerIndex) {
            System.out.println("\n( [ACTIVE REPLICA] Warning: Processing PLAY as Replica because Primary "
                    + primaryIndex + " (the normal owner) is offline! )\n");
        }
        Game game = findGame(bet.getGameName());
        if (game == null || !game.isActive()) {
            return new Response("ERROR", "Game '" + bet.getGameName() + "' not found or inactive.");
        }
        try (
                Socket srgSocket = new Socket(srgHost, srgPort);
                ObjectOutputStream out = new ObjectOutputStream(srgSocket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(srgSocket.getInputStream())) {
            out.writeObject(new Request("GET_RANDOM", game.getHashKey()));
            out.flush();
            Response srgResponse = (Response) in.readObject();
            String[] srgPayload = (String[]) srgResponse.getPayload();
            int randNumber = Integer.parseInt(srgPayload[0]);
            String srgHash = srgPayload[1];
            String expectedHash = sha256(randNumber + game.getHashKey());
            if (!expectedHash.equals(srgHash)) {
                System.err.println("[Worker-" + myWorkerIndex + "] Security breach! Hash mismatch.");
                return new Response("ERROR", "Security verification failed.");
            }
            double winAmount;
            if (randNumber % 100 == 0) {
                winAmount = bet.getAmount() * game.getJackpot();
                System.out.println("[Worker-" + myWorkerIndex + "] JACKPOT! rand=" + randNumber);
            } else {
                int index = randNumber % 10;
                double multiplier = getRiskMultiplier(game.getRiskLevel(), index);
                winAmount = bet.getAmount() * multiplier;
            }
            double netProfit = winAmount - bet.getAmount();
            synchronized (game) {
                game.updateSystemProfitLoss(-netProfit);
            }
            synchronized (playerStats) {
                double currentTotal = playerStats.getOrDefault(bet.getPlayerId(), 0.0);
                playerStats.put(bet.getPlayerId(), currentTotal + netProfit);
            }
            System.out.println("[Worker-" + myWorkerIndex + "] Bet processed: " +
                    bet.getPlayerId() + " bet=" + bet.getAmount() +
                    " won=" + winAmount + " net=" + netProfit);
            return new Response("OK",
                    "Bet: " + bet.getAmount() + " | Won: " + winAmount + " | Net: " + netProfit);
        } catch (Exception e) {
            System.err.println("[Worker-" + myWorkerIndex + "] SRG connection failed: " + e.getMessage());
            return new Response("ERROR", "Secured Random Generator is offline.");
        }
    }

    private void sendGamesToReducer(String reqId, int expected, List<Game> gameList) {
        try (
                Socket s = new Socket(reducerHost, reducerPort);
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
            out.writeObject(new Request("REDUCE_PARTIAL:" + reqId + ":" + expected, gameList));
            out.flush();
            in.readObject();
        } catch (Exception e) {
            System.err.println("[Worker-" + myWorkerIndex + "] Cannot reach Reducer: " + e.getMessage());
        }
    }

    private void sendStatsToReducer(String reqId, int expected, Map<String, Double> stats) {
        try (
                Socket s = new Socket(reducerHost, reducerPort);
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
            out.writeObject(new Request("REDUCE_STATS_PARTIAL:" + reqId + ":" + expected, stats));
            out.flush();
            in.readObject();
        } catch (Exception e) {
            System.err.println("[Worker-" + myWorkerIndex + "] Cannot reach Reducer for stats: " + e.getMessage());
        }
    }

    private synchronized void addGame(Game game) {
        Game existing = findGame(game.getGameName());
        if (existing != null) {
            games.remove(existing);
        }
        games.add(game);
    }

    private synchronized Game findGame(String name) {
        for (Game g : games) {
            if (g.getGameName().equals(name))
                return g;
        }
        return null;
    }

    private synchronized boolean removeGame(String name) {
        Game g = findGame(name);
        if (g != null) {
            g.setActive(false);
            return true;
        }
        return false;
    }

    private synchronized boolean editGame(Game updatedGame) {
        Game existing = findGame(updatedGame.getGameName());
        if (existing != null) {
            existing.setRiskLevel(updatedGame.getRiskLevel());
            return true;
        }
        return false;
    }

    private synchronized void rateGame(String gameName, int newRating) {
        Game g = findGame(gameName);
        if (g != null) {
            g.addRating(newRating);
        }
    }

    private double getRiskMultiplier(String riskLevel, int index) {
        double[] low = { 0.0, 0.0, 0.0, 0.1, 0.5, 1.0, 1.1, 1.3, 2.0, 2.5 };
        double[] medium = { 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.5, 2.5, 3.5 };
        double[] high = { 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 6.5 };
        if ("LOW".equalsIgnoreCase(riskLevel))
            return low[index];
        if ("MEDIUM".equalsIgnoreCase(riskLevel))
            return medium[index];
        return high[index];
    }

    private String getBetCategory(double minBet) {
        if (minBet >= 5.0)
            return "$$$";
        if (minBet >= 1.0)
            return "$$";
        return "$";
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] args) {
        int myIndex = (args.length >= 1) ? Integer.parseInt(args[0]) : 0;
        int numWorkers = (args.length >= 2) ? Integer.parseInt(args[1]) : 3;
        int port = (args.length >= 3) ? Integer.parseInt(args[2]) : 6000;
        String configFile = (args.length >= 4) ? args[3] : "config.conf";
        Properties config = new Properties();
        try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
            config.load(reader);
        } catch (Exception e) {
            System.err.println("[Worker] Could not load config file: " + configFile);
        }
        String reducerHost = config.getProperty("reducer.host", "localhost");
        int reducerPort = Integer.parseInt(config.getProperty("reducer.port", "8001"));
        String srgHost = config.getProperty("srg.host", "localhost");
        int srgPort = Integer.parseInt(config.getProperty("srg.port", "7001"));
        new WorkerServer(port, myIndex, numWorkers, reducerHost, reducerPort, srgHost, srgPort).start();
    }
}