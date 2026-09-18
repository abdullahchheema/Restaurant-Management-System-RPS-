package rps.model;

public enum DeliveryRunStatus {
    OPEN("Out for delivery"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    DeliveryRunStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
