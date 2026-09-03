package common;

public class HashUtil {
    public static int getWorkerIndex(String gameName, int numberOfWorkers) {
        int hash = gameName.hashCode();
        return Math.abs(hash) % numberOfWorkers;
    }
}