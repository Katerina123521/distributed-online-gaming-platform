package model;

import java.io.Serializable;

public class Game implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String gameName;
    private final String providerName;
    private double stars;
    private int noOfVotes;
    private String gameLogo;
    private double minBet;
    private double maxBet;
    private String riskLevel;
    private final String hashKey;
    private double systemProfitLoss;
    private boolean isActive;
    private double jackpot;

    public Game(String gameName, String providerName, double stars, int noOfVotes, String gameLogo, double minBet,
            double maxBet, String riskLevel, String hashKey) {
        this.gameName = gameName;
        this.providerName = providerName;
        this.stars = stars;
        this.noOfVotes = noOfVotes;
        this.gameLogo = gameLogo;
        this.minBet = minBet;
        this.maxBet = maxBet;
        this.riskLevel = riskLevel;
        this.hashKey = hashKey;
        this.systemProfitLoss = 0.0;
        this.isActive = true;
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            this.jackpot = 40.0;
        } else if ("MEDIUM".equalsIgnoreCase(riskLevel)) {
            this.jackpot = 20.0;
        } else {
            this.jackpot = 10.0;
        }
    }

    public String getGameName() {
        return gameName;
    }

    public String getProviderName() {
        return providerName;
    }

    public double getStars() {
        return stars;
    }

    public int getNoOfVotes() {
        return noOfVotes;
    }

    public String getGameLogo() {
        return gameLogo;
    }

    public double getMinBet() {
        return minBet;
    }

    public double getMaxBet() {
        return maxBet;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getHashKey() {
        return hashKey;
    }

    public boolean isActive() {
        return isActive;
    }

    public double getJackpot() {
        return jackpot;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public void setStars(double stars) {
        this.stars = stars;
    }

    public void setMinBet(double minBet) {
        this.minBet = minBet;
    }

    public void setMaxBet(double maxBet) {
        this.maxBet = maxBet;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public synchronized void addRating(int newStars) {
        double totalStars = (this.stars * this.noOfVotes) + newStars;
        this.noOfVotes++;
        this.stars = totalStars / this.noOfVotes;
    }

    public synchronized void updateSystemProfitLoss(double amount) {
        this.systemProfitLoss += amount;
    }

    public synchronized double getSystemProfitLoss() {
        return systemProfitLoss;
    }

    @Override
    public String toString() {
        return "Game{'" + gameName + "', provider='" + providerName + "', active=" + isActive +
                ", risk='" + riskLevel + "', stars=" + String.format("%.1f", stars) +
                ", jackpot=" + jackpot + "}";
    }
}