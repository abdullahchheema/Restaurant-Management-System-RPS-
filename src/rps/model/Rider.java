package rps.model;

public record Rider(
    int id,
    String name,
    String phone,
    boolean active
) {
    @Override
    public String toString() {
        return name;
    }
}
