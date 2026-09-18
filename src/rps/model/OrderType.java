package rps.model;

public enum OrderType {
    DINE_IN("Dine-in"),
    TAKEAWAY("Takeaway"),
    DELIVERY("Delivery");

    private final String label;

    OrderType(String label) {
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
