package common;

import java.io.Serializable;

public class Request implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String command;
    private final Object payload;

    public Request(String command, Object payload) {
        this.command = command;
        this.payload = payload;
    }

    public String getCommand() {
        return command;
    }

    public Object getPayload() {
        return payload;
    }
}