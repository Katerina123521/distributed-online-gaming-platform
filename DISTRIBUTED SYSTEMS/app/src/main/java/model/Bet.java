package model;

import java.io.Serializable;

public class Bet implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String gameName;
    private final double amount;
    private final String playerId;

    public Bet(String gameName, double amount, String playerId) {
        this.gameName = gameName;
        this.amount = amount;
        this.playerId = playerId;
    }

    public String getGameName() {
        return gameName;
    }

    public double getAmount() {
        return amount;
    }

    public String getPlayerId() {
        return playerId;
    }
}