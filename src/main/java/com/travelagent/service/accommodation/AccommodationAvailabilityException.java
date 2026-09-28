package com.travelagent.service.accommodation;

public class AccommodationAvailabilityException extends RuntimeException {

    private final String code;

    public AccommodationAvailabilityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AccommodationAvailabilityException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
