package com.example.distributedgamingapp;

import common.Request;
import common.Response;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerSocketClient {

    private static final String MASTER_HOST = "10.0.2.2"; // localhost for android emulator
    private static final int MASTER_PORT = 5001;

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    public interface SocketCallback {
        void onSuccess(Response response);
        void onError(String error);
    }

    public static void sendRequestAsync(Request request, SocketCallback callback) {
        executor.execute(() -> {
            try (
                Socket socket = new Socket(MASTER_HOST, MASTER_PORT);
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())
            ) {
                out.writeObject(request);
                out.flush();
                
                Response response = (Response) in.readObject();
                
                if (callback != null) {
                    callback.onSuccess(response);
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) {
                    callback.onError("Connection error: " + e.getMessage());
                }
            }
        });
    }
}
