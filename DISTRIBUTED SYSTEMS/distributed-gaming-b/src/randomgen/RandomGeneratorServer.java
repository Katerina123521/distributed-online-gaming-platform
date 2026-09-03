package randomgen;

import common.Request;
import common.Response;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class RandomGeneratorServer {
    private static final int PORT = 7001;
    private static final int BUFFER_SIZE = 100;
    private static final Map<String, GameBuffer> gameBuffers = new HashMap<>();

    public static void main(String[] args) {
        System.out.println("[SRG] Secured Random Generator started on port " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleRequest(socket)).start();
            }
        } catch (Exception e) {
            System.err.println("[SRG] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleRequest(Socket socket) {
        try (
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            Request request = (Request) in.readObject();
            if ("GET_RANDOM".equals(request.getCommand())) {
                String secret = (String) request.getPayload();
                GameBuffer buffer;
                synchronized (gameBuffers) {
                    if (!gameBuffers.containsKey(secret)) {
                        buffer = new GameBuffer(BUFFER_SIZE);
                        gameBuffers.put(secret, buffer);
                        startProducer(buffer);
                        System.out.println("[SRG] New buffer created for secret: " + secret);
                    } else {
                        buffer = gameBuffers.get(secret);
                    }
                }
                int randomNumber = buffer.take();
                String hashResult = sha256(randomNumber + secret);
                System.out.println("[SRG] Consumed random=" + randomNumber + " for secret=" + secret);
                out.writeObject(new Response("OK", new String[] {
                        String.valueOf(randomNumber),
                        hashResult
                }));
            } else {
                out.writeObject(new Response("ERROR", "Unknown command."));
            }
            out.flush();
        } catch (Exception e) {
            System.err.println("[SRG] Error handling request: " + e.getMessage());
        }
    }

    private static void startProducer(GameBuffer buffer) {
        Thread producerThread = new Thread(() -> {
            Random rand = new Random();
            while (true) {
                try {
                    int number = rand.nextInt(1000);
                    buffer.put(number);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        producerThread.setDaemon(true);
        producerThread.start();
    }

    static class GameBuffer {
        private final ArrayList<Integer> buffer;
        private final int maxSize;

        public GameBuffer(int maxSize) {
            this.buffer = new ArrayList<>();
            this.maxSize = maxSize;
        }

        public synchronized void put(int value) throws InterruptedException {
            while (buffer.size() == maxSize) {
                wait();
            }
            buffer.add(value);
            notifyAll();
        }

        public synchronized int take() throws InterruptedException {
            while (buffer.isEmpty()) {
                wait();
            }
            int value = buffer.remove(0);
            notifyAll();
            return value;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}