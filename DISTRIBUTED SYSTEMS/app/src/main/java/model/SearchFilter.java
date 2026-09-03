package model;

import java.io.Serializable;

public class SearchFilter implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Double minStars;
    private final String riskLevel;
    private final String betCategory;

    public SearchFilter(Double minStars, String riskLevel, String betCategory) {
        this.minStars = minStars;
        this.riskLevel = riskLevel;
        this.betCategory = betCategory;
    }

    public Double getMinStars() {
        return minStars;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getBetCategory() {
        return betCategory;
    }
}