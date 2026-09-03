package common;

import java.io.Serializable;

public class Response implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String status;
    private final Object payload;

    public Response(String status, Object payload) {
        this.status = status;
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public Object getPayload() {
        return payload;
    }
}