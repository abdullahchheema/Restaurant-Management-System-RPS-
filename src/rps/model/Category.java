package rps.model;

public record Category(
    int id,
    String name,
    int displayOrder,
    boolean active
) {
    @Override
    public String toString() {
        return name;
    }
}
